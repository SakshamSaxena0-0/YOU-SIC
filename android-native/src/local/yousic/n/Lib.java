package local.yousic.n;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The music library in memory: the songs Library.java scans from Android's media store (library.json), and
 * the categories built from them (albums, artists, folders, genres). Rebuilt after every scan; listeners
 * hear about it on the main thread.
 */
public final class Lib {
    public static final class Track {
        public String id, path, folder, title, artist, albumArtist, album, genre, cover, codec, container = "", albumKey;
        public int year, track, disc, sampleRate, bits, bitrate, channels;
        public long size, added;
        public double duration;
        public boolean lossless;
    }

    /** A category: an album, an artist, a folder, a genre. */
    public static final class Group {
        public final String key, title;
        public String sub = "", cover;
        public final List<Track> tracks = new ArrayList<>();
        Group(String key, String title) { this.key = key; this.title = title; }
    }

    public interface Listener { void onLibrary(); }

    public final Library scanner;
    public volatile List<Track> tracks = Collections.emptyList();
    public volatile List<Group> albums = Collections.emptyList(), artists = Collections.emptyList(),
            folders = Collections.emptyList(), genres = Collections.emptyList();
    public final Map<String, Track> byId = new HashMap<>();
    public volatile boolean scanning;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    Lib(Context c) { scanner = new Library(c); }

    public void listen(Listener l) { listeners.add(l); }
    public void unlisten(Listener l) { listeners.remove(l); }

    /** Loads what the last scan found, then scans again (only changed files are read). Background thread. */
    public void loadAndScan() {
        new Thread(() -> {
            build(scanner.load());
            scanning = true;
            scanner.scan(false, (done, total, finished) -> { });
            scanning = false;
            build(scanner.load());
        }, "library").start();
    }

    /** Scans again (after the music folders changed, or on request). */
    public void rescan() {
        new Thread(() -> {
            scanning = true;
            scanner.scan(false, (done, total, finished) -> { });
            scanning = false;
            build(scanner.load());
        }, "library").start();
    }

    private void build(JSONObject lib) {
        JSONArray a = lib == null ? null : lib.optJSONArray("tracks");
        List<Track> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Track t = new Track();
            t.id = o.optString("id"); t.path = o.optString("path"); t.folder = o.optString("folder");
            t.title = o.optString("title"); t.artist = o.optString("artist"); t.albumArtist = o.optString("albumArtist");
            t.album = o.optString("album"); t.genre = o.optString("genre");
            t.cover = o.isNull("cover") ? null : o.optString("cover", null);
            t.year = o.optInt("year"); t.track = o.optInt("track"); t.disc = o.optInt("disc"); t.duration = o.optDouble("duration", 0);
            t.size = o.optLong("size"); t.added = o.optLong("added");
            JSONObject f = o.optJSONObject("fmt");
            if (f != null) {
                t.codec = f.optString("codec"); t.sampleRate = f.optInt("sampleRate"); t.bits = f.optInt("bits"); t.lossless = f.optBoolean("lossless");
                t.bitrate = f.optInt("bitrate"); t.channels = f.optInt("channels"); t.container = f.optString("container");
            }
            out.add(t);
        }
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, (x, y) -> col.compare(x.title, y.title));
        Map<String, Group> al = new LinkedHashMap<>(), ar = new LinkedHashMap<>(), fo = new LinkedHashMap<>(), ge = new LinkedHashMap<>();
        Map<String, Track> ids = new HashMap<>();
        for (Track t : out) {
            ids.put(t.id, t);
            String aa = t.albumArtist.isEmpty() ? t.artist : t.albumArtist;
            String akey = (aa + "|" + t.album).toLowerCase(Locale.ROOT);
            t.albumKey = t.album.isEmpty() ? akey + "|" + t.folder : akey;
            group(al, t.albumKey, t.album.isEmpty() ? new File(t.folder).getName() : t.album, t).sub = aa;
            group(ar, t.artist.toLowerCase(Locale.ROOT), t.artist.isEmpty() ? "Unknown artist" : t.artist, t);
            group(fo, t.folder, new File(t.folder).getName(), t).sub = t.folder;
            if (!t.genre.isEmpty()) group(ge, t.genre.toLowerCase(Locale.ROOT), t.genre, t);
        }
        Comparator<Group> byTitle = (x, y) -> col.compare(x.title, y.title);
        Comparator<Track> inAlbum = (x, y) -> x.disc != y.disc ? x.disc - y.disc : x.track != y.track ? x.track - y.track : col.compare(x.title, y.title);
        List<Group> albumsL = sorted(al, byTitle), artistsL = sorted(ar, byTitle), foldersL = sorted(fo, byTitle), genresL = sorted(ge, byTitle);
        for (Group g : albumsL) Collections.sort(g.tracks, inAlbum);
        for (Group g : foldersL) Collections.sort(g.tracks, (x, y) -> x.path.compareToIgnoreCase(y.path));
        for (Group g : artistsL) g.sub = count(g.tracks.size());
        for (Group g : genresL) g.sub = count(g.tracks.size());
        main.post(() -> {
            tracks = out; albums = albumsL; artists = artistsL; folders = foldersL; genres = genresL;
            byId.clear(); byId.putAll(ids);
            for (Listener l : listeners) l.onLibrary();
        });
    }

    private static Group group(Map<String, Group> m, String key, String title, Track t) {
        Group g = m.get(key);
        if (g == null) { g = new Group(key, title); m.put(key, g); }
        g.tracks.add(t);
        if (g.cover == null && t.cover != null) g.cover = t.cover;
        return g;
    }

    private static List<Group> sorted(Map<String, Group> m, Comparator<Group> c) {
        List<Group> l = new ArrayList<>(m.values());
        Collections.sort(l, c);
        return l;
    }

    public static String count(int n) { return n == 1 ? "1 song" : n + " songs"; }

    public File coverFile(String key, boolean small) {
        return new File(scanner.coverDir, key + (small ? "_s.jpg" : ".jpg"));
    }
}
