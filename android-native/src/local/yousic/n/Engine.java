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
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Playback: Android's MediaPlayer, gapless (the next song is prepared while one plays and handed over with
 * setNextMediaPlayer), with the queue, shuffle, repeat, audio focus and the saved session. Main thread only.
 */
public final class Engine {
    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    public interface Listener {
        /** a new song (or the same one again from the start) */
        default void onTrack(Lib.Track t) {}
        /** playing or paused */
        default void onState(boolean playing) {}
        /** shuffle / repeat / the list changed */
        default void onModes() {}
    }

    private final Context ctx;
    private final Lib lib;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;

    private List<Lib.Track> list = new ArrayList<>();
    private int[] order = new int[0];
    private int pos = -1;
    private String source = "", sourceKey = "";
    public boolean shuffle;
    public int repeat = REPEAT_ALL;

    private MediaPlayer cur, next;
    private int nextPos = -1;
    /** the next song is prepared and handed to MediaPlayer, which will start it itself */
    private boolean nextLinked;
    private boolean prepared, wantPlay, playing;
    private Lib.Track track;
    private int pendingSeek;

    private AudioFocusRequest focus;
    private boolean resumeOnGain;

    Engine(Context c, Lib lib) {
        ctx = c.getApplicationContext();
        this.lib = lib;
        audio = ctx.getSystemService(AudioManager.class);
        prefs = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE);
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", REPEAT_ALL);
    }

    public void listen(Listener l) { listeners.add(l); }
    public void unlisten(Listener l) { listeners.remove(l); }

    public Lib.Track track() { return track; }
    public boolean isPlaying() { return playing; }
    public String source() { return source; }
    public String sourceKey() { return sourceKey; }
    public int index() { return pos; }
    public int size() { return list.size(); }

    public long positionMs() {
        if (cur != null && prepared) try { return cur.getCurrentPosition(); } catch (Exception ignored) { }
        return pendingSeek;
    }

    public long durationMs() {
        if (cur != null && prepared) try { return cur.getDuration(); } catch (Exception ignored) { }
        return track == null ? 0 : (long) (track.duration * 1000);
    }

    /** The song before / after the current one in play order (for the album art pager), or null. */
    public Lib.Track peek(int delta) {
        if (list.isEmpty() || pos < 0) return null;
        int p = pos + delta;
        if (p < 0 || p >= order.length) {
            if (repeat != REPEAT_ALL) return null;
            p = (p + order.length) % order.length;
        }
        return list.get(order[p]);
    }

    // ---------- the queue ----------

    /** Plays a list from one of its songs (index -1 with shuffle: a random one). */
    public void playList(List<Lib.Track> tracks, int index, String title, String key) {
        if (tracks.isEmpty()) return;
        list = new ArrayList<>(tracks);
        source = title; sourceKey = key;
        if (shuffle && index < 0) index = new Random().nextInt(list.size());
        buildOrder(Math.max(0, index));
        load(0, true);
        for (Listener l : listeners) l.onModes();
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
            int curIdx = order[pos];
            buildOrder(curIdx);
            prepareNext();
        }
        for (Listener l : listeners) l.onModes();
        save();
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs.edit().putInt("repeat", repeat).apply();
        prepareNext();
        for (Listener l : listeners) l.onModes();
    }

    // ---------- transport ----------

    public void toggle() { if (playing) pause(); else play(); }

    public void play() {
        if (track == null) {
            if (!restore() && !lib.tracks.isEmpty()) playList(lib.tracks, shuffle ? -1 : 0, "All songs", "all");
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
        if (list.isEmpty()) return;
        int p = pos + delta;
        if (p >= order.length || p < 0) {
            if (repeat == REPEAT_ALL || user) p = (p + order.length) % order.length;
            else { pause(); seekTo(0); return; }
        }
        pos = p;
        load(0, wantPlay || user);
    }

    // ---------- MediaPlayer ----------

    private void load(int atMs, boolean play) {
        release();
        track = list.get(order[pos]);
        pendingSeek = atMs;
        wantPlay = play;
        cur = create(track);
        prepared = false;
        if (cur == null) { main.postDelayed(() -> step(1, false), 300); return; }
        final MediaPlayer mp = cur;
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
            if (p == cur) main.postDelayed(() -> step(1, false), 300);
            return true;
        });
        try { mp.prepareAsync(); } catch (Exception e) { Log.w(App.TAG, "prepare", e); }
        for (Listener l : listeners) l.onTrack(track);
        save();
    }

    private MediaPlayer create(Lib.Track t) {
        Uri uri = lib.scanner.uriFor(t.path);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
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
        if (next != null) { try { if (cur != null) cur.setNextMediaPlayer(null); } catch (Exception ignored) { } next.release(); next = null; }
        if (cur == null || !prepared) return;
        int p = repeat == REPEAT_ONE ? pos : pos + 1;
        if (p >= order.length) { if (repeat == REPEAT_ALL) p = 0; else return; }
        Lib.Track t = list.get(order[p]);
        MediaPlayer mp = create(t);
        if (mp == null) return;
        final int np = p;
        final MediaPlayer forCur = cur;
        next = mp;
        nextPos = np;
        mp.setOnCompletionListener(this::onCompletion);
        // prepared off the main thread (a synchronous prepare can block it for a fifth of a second)
        mp.setOnPreparedListener(p2 -> {
            if (p2 != next || forCur != cur) return;
            try { cur.setNextMediaPlayer(p2); nextLinked = true; } catch (Exception e) { Log.w(App.TAG, "gapless " + t.path, e); }
        });
        mp.setOnErrorListener((p2, w, x) -> { if (p2 == next) { next = null; p2.release(); } return true; });
        try { mp.prepareAsync(); } catch (Exception e) { Log.w(App.TAG, "gapless " + t.path, e); next = null; mp.release(); }
    }

    private void onCompletion(MediaPlayer mp) {
        if (mp != cur) return;
        if (next != null && nextLinked) {
            // MediaPlayer has already started the next one
            nextLinked = false;
            cur.release();
            cur = next; next = null;
            prepared = true;
            pos = nextPos;
            track = list.get(order[pos]);
            cur.setOnErrorListener((p, w, x) -> { if (p == cur) main.postDelayed(() -> step(1, false), 300); return true; });
            for (Listener l : listeners) l.onTrack(track);
            prepareNext();
            save();
        } else {
            if (next != null) { next.release(); next = null; }
            setPlaying(false);
            if (repeat != REPEAT_OFF || pos + 1 < order.length) step(repeat == REPEAT_ONE ? 0 : 1, false);
        }
    }

    private void release() {
        nextLinked = false;
        if (next != null) { next.release(); next = null; }
        if (cur != null) { try { cur.release(); } catch (Exception ignored) { } cur = null; }
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
                if (cur != null) cur.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (cur != null) cur.setVolume(1f, 1f);
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
        prefs.edit().putString("ids", ids.toString()).putString("order", ord.toString()).putInt("pos", pos)
                .putLong("at", positionMs()).putString("source", source).putString("sourceKey", sourceKey).apply();
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
        load((int) prefs.getLong("at", 0), false);
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
