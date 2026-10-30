package local.yousic.n;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Playback: Android's MediaPlayer, gapless (the next song is prepared while one plays and handed over with
 * setNextMediaPlayer), with the list it plays from, its categories (the next / previous album, folder...),
 * the queue, shuffle, repeat, likes, the sleep timer, audio focus and the saved session. Called on the main
 * thread; opening and releasing players (calls into the media server that can take tens of ms) run on `io`, so a
 * song change never stalls a frame. Every player joins one audio session, so the effects stay attached.
 */
public final class Engine {
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2, REPEAT_ADVANCE = 3;

    public interface Listener {
        /** a new song (or the same one again from the start) */
        default void onTrack(Lib.Track t) {}
        /** playing or paused */
        default void onState(boolean playing) {}
        /** shuffle / repeat / the list changed */
        default void onModes() {}
        /** likes, the queue, the sleep timer */
        default void onExtras() {}
    }

    private final Context ctx;
    private final Lib lib;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "engine-io"));
    /** bumped by every load / prepareNext, so a player opened for a song since replaced is dropped */
    private int gen, nextGen;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs, ratings;
    /** the audio session every player joins (the equalizer and visualizer attach to it) */
    public final int session;

    private List<Lib.Track> list = new ArrayList<>();
    private int[] order = new int[0];
    private int pos = -1;
    private String source = "", sourceKey = "";
    public boolean shuffle;
    public int repeat = REPEAT_ALL;
    /** songs to play next, before the list carries on */
    private final List<Lib.Track> queue = new ArrayList<>();
    private final LinkedList<String> recent = new LinkedList<>();

    private MediaPlayer cur, next;
    private int nextPos = -1;
    private boolean nextFromQueue;
    /** the next song is prepared and handed to MediaPlayer, which will start it itself */
    private boolean nextLinked;
    private boolean prepared, wantPlay, playing;
    private Lib.Track track;
    private int pendingSeek;

    private AudioFocusRequest focus;
    private boolean resumeOnGain;
    private float duck = 1f, fade = 1f;

    private long sleepAt;
    private boolean sleepEndOfSong;

    Engine(Context c, Lib lib) {
        ctx = c.getApplicationContext();
        this.lib = lib;
        audio = ctx.getSystemService(AudioManager.class);
        session = audio.generateAudioSessionId();
        prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE);
        ratings = ctx.getSharedPreferences("ratings", Context.MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_ALL);
        for (String id : prefs.getString("recent", "").split(",")) if (!id.isEmpty()) recent.add(id);
    }

    public void listen(Listener l) { listeners.add(l); }
    public void unlisten(Listener l) { listeners.remove(l); }

    public Lib.Track track() { return track; }
    public boolean isPlaying() { return playing; }
    public String source() { return source; }
    public String sourceKey() { return sourceKey; }
    public int index() { return pos; }
    public int size() { return list.size(); }
    public List<Lib.Track> list() { return list; }

    public long positionMs() {
        if (cur != null && prepared) try { return cur.getCurrentPosition(); } catch (Exception ignored) { }
        return pendingSeek;
    }

    public long durationMs() {
        if (cur != null && prepared) try { return cur.getDuration(); } catch (Exception ignored) { }
        return track == null ? 0 : (long) (track.duration * 1000);
    }

    /** The song before / after the current one (queue first), or null. */
    public Lib.Track peek(int delta) {
        if (delta == 1 && !queue.isEmpty()) return queue.get(0);
        if (list.isEmpty() || pos < 0) return null;
        int p = pos + delta;
        if (p < 0 || p >= order.length) {
            if (repeat == REPEAT_ADVANCE) {
                Lib.Group g = peekCategory(delta > 0 ? 1 : -1);
                if (g != null && !g.tracks.isEmpty()) return g.tracks.get(0);
            }
            if (repeat != REPEAT_ALL) return null;
            p = (p + order.length) % order.length;
        }
        return list.get(order[p]);
    }

    /** The queue, then the list in play order from the current song on. */
    public List<Lib.Track> upcoming() {
        List<Lib.Track> out = new ArrayList<>(queue);
        for (int i = Math.max(0, pos); i < order.length; i++) out.add(list.get(order[i]));
        return out;
    }

    // ---------- the list ----------

    /** Plays a list from one of its songs (index -1: the first, or with shuffle a random one). */
    public void playList(List<Lib.Track> tracks, int index, String title, String key) {
        if (tracks.isEmpty()) return;
        list = new ArrayList<>(tracks);
        source = title; sourceKey = key;
        if (index < 0) index = shuffle ? new Random().nextInt(list.size()) : 0;
        buildOrder(Math.min(index, list.size() - 1));
        load(list.get(order[pos]), 0, true);
        for (Listener l : listeners) l.onModes();
    }

    /** Every song, shuffled. */
    public void shuffleAll() {
        if (!shuffle) { shuffle = true; prefs.edit().putBoolean("shuffle", true).apply(); }
        playList(lib.tracks, -1, "All songs", "all");
    }

    private void buildOrder(int first) {
        int n = list.size();
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        if (shuffle && n > 1) {
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < n; i++) if (i != first) rest.add(i);
            Collections.shuffle(rest);
            order[0] = first;
            for (int i = 0; i < rest.size(); i++) order[i + 1] = rest.get(i);
            pos = 0;
        } else {
            pos = first;
        }
    }

    public void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        prefs.edit().putBoolean("shuffle", on).apply();
        if (!list.isEmpty() && pos >= 0) {
            buildOrder(order[pos]);
            prepareNext();
        }
        for (Listener l : listeners) l.onModes();
        save();
    }

    public void setRepeat(int r) {
        repeat = r;
        prefs.edit().putInt("repeat", repeat).apply();
        prepareNext();
        for (Listener l : listeners) l.onModes();
    }

    /** The repeat button: off, the list, the song. */
    public void cycleRepeat() { setRepeat(repeat == REPEAT_OFF ? REPEAT_ALL : repeat == REPEAT_ALL || repeat == REPEAT_ADVANCE ? REPEAT_ONE : REPEAT_OFF); }

    // ---------- categories: the album, folder, artist or genre the list is ----------

    /** "album", "artist", "folder", "genre", or "" for a list that is not one category. */
    public String kind() {
        int i = sourceKey.indexOf(':');
        return i > 0 ? sourceKey.substring(0, i) : "";
    }

    List<Lib.Group> groupsOf(String kind) {
        switch (kind) {
            case "album": return lib.albums;
            case "artist": return lib.artists;
            case "folder": return lib.folders;
            case "genre": return lib.genres;
            default: return null;
        }
    }

    /** The category after / before this one (for a list that is not a category: the albums, from this song's). */
    public Lib.Group peekCategory(int dir) {
        String k = kind();
        List<Lib.Group> gs = groupsOf(k);
        String key = gs == null ? null : sourceKey.substring(k.length() + 1);
        if (gs == null) { gs = lib.albums; key = track == null ? null : track.albumKey; }
        if (gs == null || gs.isEmpty() || key == null) return null;
        int i = -1;
        for (int j = 0; j < gs.size(); j++) if (gs.get(j).key.equals(key)) { i = j; break; }
        if (i < 0) return null;
        return gs.get((i + dir + gs.size()) % gs.size());
    }

    /** What the category steps go through: "album", "folder"... */
    public String categoryKind() { return groupsOf(kind()) == null ? "album" : kind(); }

    /** Plays the next / previous category from its start; false if there is none. */
    public boolean stepCategory(int dir) {
        Lib.Group g = peekCategory(dir);
        if (g == null) return false;
        playList(g.tracks, -1, g.title, categoryKind() + ":" + g.key);
        return true;
    }

    // ---------- the queue ----------

    public void playNext(List<Lib.Track> ts) { queue.addAll(0, ts); prepareNext(); extras(); }
    public void enqueue(List<Lib.Track> ts) { queue.addAll(ts); prepareNext(); extras(); }
    public int queued() { return queue.size(); }
    public void clearQueue() { queue.clear(); prepareNext(); extras(); }

    /** Plays a song from the queue or the list (the queue page). */
    public void jump(Lib.Track t) {
        int qi = queue.indexOf(t);
        if (qi >= 0) { queue.remove(qi); load(t, 0, true); extras(); return; }
        for (int i = 0; i < order.length; i++) if (list.get(order[i]) == t) { pos = i; load(t, 0, true); return; }
    }

    // ---------- likes ----------

    public int rating(Lib.Track t) { return t == null ? 0 : ratings.getInt(t.id, 0); }

    public void setRating(Lib.Track t, int r) {
        if (t == null) return;
        if (r == 0) ratings.edit().remove(t.id).apply(); else ratings.edit().putInt(t.id, r).apply();
        extras();
    }

    public List<Lib.Track> liked() {
        List<Lib.Track> out = new ArrayList<>();
        for (Lib.Track t : lib.tracks) if (ratings.getInt(t.id, 0) > 0) out.add(t);
        return out;
    }

    public List<Lib.Track> recent() {
        List<Lib.Track> out = new ArrayList<>();
        for (String id : recent) { Lib.Track t = lib.byId.get(id); if (t != null) out.add(t); }
        return out;
    }

    private void extras() { for (Listener l : listeners) l.onExtras(); }

    // ---------- the sleep timer ----------

    /** Pauses after this many minutes (0: off). The last 8 s fade out. */
    public void setSleep(int minutes) {
        main.removeCallbacks(sleepFade);
        sleepEndOfSong = false;
        sleepAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60_000L : 0;
        fade = 1f;
        volume();
        if (sleepAt > 0) main.postDelayed(sleepFade, Math.max(0, sleepAt - 8000 - SystemClock.elapsedRealtime()));
        prepareNext();
        extras();
    }

    /** Pauses when this song ends. */
    public void sleepAfterSong() {
        main.removeCallbacks(sleepFade);
        sleepAt = 0;
        fade = 1f;
        volume();
        sleepEndOfSong = true;
        prepareNext();
        extras();
    }

    public long sleepRemainingMs() { return sleepAt > 0 ? Math.max(0, sleepAt - SystemClock.elapsedRealtime()) : 0; }
    public boolean sleepingAfterSong() { return sleepEndOfSong; }
    public boolean sleepOn() { return sleepAt > 0 || sleepEndOfSong; }

    private final Runnable sleepFade = new Runnable() {
        @Override public void run() {
            if (sleepAt == 0) return;
            long left = sleepAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                sleepAt = 0;
                pause();
                fade = 1f;
                volume();
                extras();
                return;
            }
            fade = Math.min(1f, left / 8000f);
            volume();
            main.postDelayed(this, 100);
        }
    };

    private void volume() {
        float v = duck * fade;
        if (cur != null) try { cur.setVolume(v, v); } catch (Exception ignored) { }
    }

    // ---------- transport ----------

    public void toggle() { if (playing) pause(); else play(); }

    public void play() {
        if (track == null) {
            if (!restore() && !lib.tracks.isEmpty()) playList(lib.tracks, -1, "All songs", "all");
            if (track == null) return;
        }
        wantPlay = true;
        if (!requestFocus()) return;
        if (cur != null && prepared) {
            try { cur.start(); } catch (Exception e) { Log.w(App.TAG, "start", e); }
            setPlaying(true);
        }
    }

    public void pause() {
        wantPlay = false;
        resumeOnGain = false;
        if (cur != null && prepared) try { cur.pause(); } catch (Exception ignored) { }
        setPlaying(false);
        save();
    }

    public void next() { step(1, true); }

    /** Within the first 3 s: the previous song; later: this one from the start (as every player does). */
    public void prev() {
        if (positionMs() > 3000) { seekTo(0); return; }
        step(-1, true);
    }

    public void seekTo(long ms) {
        if (cur != null && prepared) {
            try { cur.seekTo((int) ms); } catch (Exception ignored) { }
        } else {
            pendingSeek = (int) ms;
        }
        save();
    }

    /** Plays the song this many places along the play order (the album art pager uses +-1). */
    public void step(int delta, boolean user) {
        if (delta == 1 && !queue.isEmpty()) {
            load(queue.remove(0), 0, wantPlay || user);
            extras();
            return;
        }
        if (list.isEmpty()) return;
        int p = pos + delta;
        if (p >= order.length || p < 0) {
            if (repeat == REPEAT_ADVANCE && stepCategory(delta > 0 ? 1 : -1)) return;
            if (repeat == REPEAT_ALL || user) p = (p + order.length) % order.length;
            else { pause(); seekTo(0); return; }
        }
        pos = p;
        load(list.get(order[pos]), 0, wantPlay || user);
    }

    // ---------- MediaPlayer ----------

    private void load(Lib.Track t, int atMs, boolean play) {
        release();
        track = t;
        pendingSeek = atMs;
        wantPlay = play;
        prepared = false;
        recent.remove(t.id);
        recent.addFirst(t.id);
        while (recent.size() > 100) recent.removeLast();
        final int g = ++gen;
        io.execute(() -> {
            final MediaPlayer mp = create(t);
            main.post(() -> start(g, mp));
        });
        for (Listener l : listeners) l.onTrack(track);
        save();
    }

    /** The player for load number `g`, opened: prepare it (unless another song was chosen meanwhile). */
    private void start(int g, MediaPlayer mp) {
        if (g != gen) { if (mp != null) io.execute(mp::release); return; }
        if (mp == null) { skipSoon(); return; }
        cur = mp;
        volume();
        mp.setOnPreparedListener(p -> {
            if (p != cur) return;
            prepared = true;
            if (pendingSeek > 0) p.seekTo(pendingSeek);
            if (wantPlay && requestFocus()) { p.start(); setPlaying(true); }
            prepareNext();
        });
        mp.setOnCompletionListener(this::onCompletion);
        mp.setOnErrorListener((p, what, extra) -> {
            Log.w(App.TAG, "playback error " + what + "/" + extra + " " + (track == null ? "" : track.path));
            if (p == cur) skipSoon();
            return true;
        });
        try { mp.prepareAsync(); } catch (Exception e) { Log.w(App.TAG, "prepare", e); }
    }

    /** This song can't be played: on to the next (unless the user picks another first). */
    private void skipSoon() {
        final int g = gen;
        main.postDelayed(() -> { if (g == gen) step(1, false); }, 300);
    }

    private MediaPlayer create(Lib.Track t) {
        Uri uri = lib.scanner.uriFor(t.path);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setAudioSessionId(session);
            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            if (uri != null) mp.setDataSource(ctx, uri); else mp.setDataSource(t.path);
            return mp;
        } catch (Exception e) {
            Log.w(App.TAG, "open " + t.path, e);
            mp.release();
            return null;
        }
    }

    /** Gapless: the following song, prepared now and started by MediaPlayer the instant this one ends. */
    private void prepareNext() {
        nextLinked = false;
        final int g = ++nextGen;
        if (next != null) {
            try { if (cur != null) cur.setNextMediaPlayer(null); } catch (Exception ignored) { }
            io.execute(next::release);
            next = null;
        }
        if (cur == null || !prepared || sleepEndOfSong) return;
        final Lib.Track t;
        final int np;
        final boolean fromQueue;
        if (repeat == REPEAT_ONE) { t = track; np = pos; fromQueue = false; }
        else if (!queue.isEmpty()) { t = queue.get(0); np = pos; fromQueue = true; }
        else {
            int p = pos + 1;
            if (p >= order.length) { if (repeat == REPEAT_ALL) p = 0; else return; } // advancing a category: not gapless
            t = list.get(order[p]); np = p; fromQueue = false;
        }
        final MediaPlayer forCur = cur;
        io.execute(() -> {
            final MediaPlayer mp = create(t);
            if (mp == null) return;
            main.post(() -> {
                if (g != nextGen || forCur != cur) { io.execute(mp::release); return; }
                next = mp;
                nextPos = np;
                nextFromQueue = fromQueue;
                mp.setOnCompletionListener(this::onCompletion);
                mp.setOnPreparedListener(p2 -> {
                    if (p2 != next || forCur != cur) return;
                    try { cur.setNextMediaPlayer(p2); nextLinked = true; } catch (Exception e) { Log.w(App.TAG, "gapless " + t.path, e); }
                });
                mp.setOnErrorListener((p2, w, x) -> { if (p2 == next) { next = null; io.execute(p2::release); } return true; });
                try { mp.prepareAsync(); } catch (Exception e) { Log.w(App.TAG, "gapless " + t.path, e); next = null; io.execute(mp::release); }
            });
        });
    }

    private void onCompletion(MediaPlayer mp) {
        if (mp != cur) return;
        if (next != null && nextLinked) {
            // MediaPlayer has already started the next one
            nextLinked = false;
            io.execute(cur::release);
            cur = next; next = null;
            nextGen++;
            prepared = true;
            if (nextFromQueue && !queue.isEmpty()) { track = queue.remove(0); extras(); }
            else { pos = nextPos; track = list.get(order[pos]); }
            recent.remove(track.id);
            recent.addFirst(track.id);
            volume();
            cur.setOnErrorListener((p, w, x) -> { if (p == cur) skipSoon(); return true; });
            for (Listener l : listeners) l.onTrack(track);
            prepareNext();
            save();
        } else {
            if (next != null) { io.execute(next::release); next = null; }
            setPlaying(false);
            if (sleepEndOfSong) {
                // the sleep timer: the next song, ready and paused
                sleepEndOfSong = false;
                wantPlay = false;
                step(1, false);
                extras();
                return;
            }
            boolean more = repeat != REPEAT_OFF || pos + 1 < order.length || !queue.isEmpty();
            if (more) step(repeat == REPEAT_ONE ? 0 : 1, false);
        }
    }

    private void release() {
        nextLinked = false;
        nextGen++;
        final MediaPlayer n = next, c = cur;
        next = null; cur = null;
        if (n != null || c != null) io.execute(() -> {
            if (c != null) try { c.release(); } catch (Exception ignored) { }
            if (n != null) n.release();
        });
        prepared = false;
    }

    private void setPlaying(boolean p) {
        if (p == playing) return;
        playing = p;
        for (Listener l : listeners) l.onState(p);
        if (p) PlayerService.start(ctx);
    }

    // ---------- audio focus: calls, other players ----------

    private boolean requestFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onFocus, main)
                    .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS: pause(); break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (playing) { pause(); resumeOnGain = true; }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                duck = 0.25f;
                volume();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                duck = 1f;
                volume();
                if (resumeOnGain) { resumeOnGain = false; play(); }
                break;
            default: break;
        }
    }

    // ---------- the saved session ----------

    private void save() {
        if (track == null) return;
        StringBuilder ids = new StringBuilder();
        for (Lib.Track t : list) ids.append(t.id).append(',');
        StringBuilder ord = new StringBuilder();
        for (int o : order) ord.append(o).append(',');
        StringBuilder rec = new StringBuilder();
        for (String id : recent) rec.append(id).append(',');
        prefs.edit().putString("ids", ids.toString()).putString("order", ord.toString()).putInt("pos", pos)
                .putLong("at", positionMs()).putString("source", source).putString("sourceKey", sourceKey)
                .putString("recent", rec.toString()).apply();
    }

    /** The last list, song and position (paused), if the library still has them. */
    public boolean restore() {
        if (track != null || lib.tracks.isEmpty()) return track != null;
        String ids = prefs.getString("ids", "");
        if (ids.isEmpty()) return false;
        List<Lib.Track> l = new ArrayList<>();
        for (String id : ids.split(",")) { Lib.Track t = lib.byId.get(id); if (t != null) l.add(t); }
        if (l.isEmpty()) return false;
        String[] o = prefs.getString("order", "").split(",");
        int[] ord = new int[l.size()];
        boolean ok = o.length == l.size();
        for (int i = 0; ok && i < o.length; i++) { try { ord[i] = Integer.parseInt(o[i]); ok = ord[i] < l.size(); } catch (NumberFormatException e) { ok = false; } }
        if (!ok) for (int i = 0; i < ord.length; i++) ord[i] = i;
        list = l; order = ord;
        pos = Math.max(0, Math.min(prefs.getInt("pos", 0), l.size() - 1));
        source = prefs.getString("source", ""); sourceKey = prefs.getString("sourceKey", "");
        load(list.get(order[pos]), (int) prefs.getLong("at", 0), false);
        for (Listener lis : listeners) lis.onModes();
        return true;
    }

    /** For the notification and the service: a command by name. */
    public void command(String c) {
        switch (c) {
            case "toggle": toggle(); break;
            case "play": play(); break;
            case "pause": pause(); break;
            case "next": next(); break;
            case "prev": prev(); break;
            default: break;
        }
    }

    static Intent intent(Context c, String action) { return new Intent(c, PlayerService.class).setAction(action); }
}
