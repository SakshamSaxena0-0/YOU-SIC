package local.yousic.n;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The music library, in the same shape the Windows app keeps (main.js scanLibrary): library.json with
 * { version, folders, tracks: [{ id, path, folder, title, artist, album, ..., cover, fmt }] } and covers/
 * with <key>.jpg (up to 1000 px) and <key>_s.jpg (220 px).
 *
 * Songs come from Android's media library (MediaStore), limited to the folders in the list (by default
 * every storage volume, so all the music on the phone). Rescans are incremental: unchanged files keep
 * their entry.
 */
final class Library {
    interface Progress { void update(int done, int total, boolean finished); }

    final Context ctx;
    final File file, coverDir;
    private JSONObject lib;
    /** path -> MediaStore id, to open a song by its path */
    private final Map<String, Long> ids = new ConcurrentHashMap<>();
    private final Set<String> paths = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> coverLocks = new ConcurrentHashMap<>();

    Library(Context ctx) {
        this.ctx = ctx;
        file = new File(ctx.getFilesDir(), "library.json");
        coverDir = new File(ctx.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        coverDir.mkdirs();
    }

    static Uri audioUri() { return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; }

    // ---------- library.json ----------

    synchronized JSONObject load() {
        if (lib != null) return lib;
        String s = Files.read(file);
        try { lib = s == null ? null : new JSONObject(s); } catch (JSONException e) { lib = null; }
        try {
            if (lib == null) lib = new JSONObject().put("version", 2).put("folders", new JSONArray()).put("tracks", new JSONArray());
            // first start: every storage volume (internal storage, SD card), so all the music is found
            if (lib.optJSONArray("folders") == null || (lib.optJSONArray("folders").length() == 0 && !lib.optBoolean("foldersSet"))) {
                JSONArray roots = new JSONArray();
                for (String r : volumeRoots()) roots.put(r);
                lib.put("folders", roots);
            }
        } catch (JSONException ignored) { }
        JSONArray tracks = lib.optJSONArray("tracks");
        if (tracks != null) for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t != null) paths.add(t.optString("path"));
        }
        save();
        return lib;
    }

    private synchronized void save() {
        try { Files.writeAtomic(file, lib.toString()); } catch (IOException e) { Log.w(App.TAG, "library save", e); }
    }

    synchronized void addFolder(String path) {
        load();
        JSONArray f = lib.optJSONArray("folders");
        for (int i = 0; i < f.length(); i++) if (f.optString(i).equalsIgnoreCase(path)) return;
        f.put(path);
        try { lib.put("foldersSet", true); } catch (JSONException ignored) { }
        save();
    }

    synchronized void removeFolder(String path) {
        load();
        JSONArray f = lib.optJSONArray("folders"), keep = new JSONArray();
        for (int i = 0; i < f.length(); i++) if (!f.optString(i).equals(path)) keep.put(f.optString(i));
        try { lib.put("folders", keep).put("foldersSet", true); } catch (JSONException ignored) { }
        save();
    }

    boolean has(String path) { return paths.contains(path); }

    /** A song's content:// address (from the last scan, or looked up by its path). */
    Uri uriFor(String path) {
        Long id = ids.get(path);
        if (id == null) {
            try (Cursor c = ctx.getContentResolver().query(audioUri(), new String[]{MediaStore.Audio.Media._ID},
                    MediaStore.Audio.Media.DATA + "=?", new String[]{path}, null)) {
                if (c != null && c.moveToFirst()) { id = c.getLong(0); ids.put(path, id); }
            } catch (Exception e) {
                Log.w(App.TAG, "lookup " + path, e);
            }
        }
        return id == null ? null : ContentUris.withAppendedId(audioUri(), id);
    }

    /** Internal storage and any SD cards. */
    List<String> volumeRoots() {
        Set<String> out = new LinkedHashSet<>();
        out.add(Environment.getExternalStorageDirectory().getPath());
        try {
            StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
            if (Build.VERSION.SDK_INT >= 30) {
                for (StorageVolume v : sm.getStorageVolumes()) if (v.getDirectory() != null) out.add(v.getDirectory().getPath());
            } else {
                File[] all = new File("/storage").listFiles();
                if (all != null) for (File d : all) {
                    String n = d.getName();
                    if (!n.equals("emulated") && !n.equals("self") && d.isDirectory()) out.add(d.getPath());
                }
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(out);
    }

    // ---------- scanning ----------

    private static final class Row {
        long id, size, added, modified, durationMs, albumId;
        int track, year, disc;
        String path, title, artist, albumArtist, album, genre, composer, mime, name;
    }

    void scan(boolean force, Progress progress) {
        JSONObject cur = load();
        List<String> roots = new ArrayList<>();
        JSONArray f = cur.optJSONArray("folders");
        for (int i = 0; f != null && i < f.length(); i++) roots.add(f.optString(i).replaceAll("/+$", ""));
        Map<String, JSONObject> old = new HashMap<>();
        JSONArray prevTracks = cur.optJSONArray("tracks");
        for (int i = 0; prevTracks != null && i < prevTracks.length(); i++) {
            JSONObject t = prevTracks.optJSONObject(i);
            if (t != null) old.put(t.optString("path"), t);
        }
        Set<String> coverKeys = ConcurrentHashMap.newKeySet(), failed = ConcurrentHashMap.newKeySet();
        String[] have = coverDir.list();
        if (have != null) for (String n : have) if (n.endsWith("_s.jpg")) coverKeys.add(n.substring(0, n.length() - 6));

        List<Row> rows = query(roots);
        final int total = rows.size();
        progress.update(0, total, false);
        final JSONObject[] result = new JSONObject[total];
        final AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
        for (int i = 0; i < total; i++) {
            final int k = i;
            pool.execute(() -> {
                try { result[k] = track(rows.get(k), old.get(rows.get(k).path), force, coverKeys, failed); }
                catch (Exception e) { Log.w(App.TAG, "scan " + rows.get(k).path, e); }
                int d = done.incrementAndGet();
                if (d % 10 == 0) progress.update(d, total, false);
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }

        JSONArray tracks = new JSONArray();
        Set<String> nowPaths = new LinkedHashSet<>();
        for (JSONObject t : result) if (t != null) { tracks.put(t); nowPaths.add(t.optString("path")); }
        synchronized (this) {
            try { lib.put("tracks", tracks); } catch (JSONException ignored) { }
            paths.clear();
            paths.addAll(nowPaths);
            save();
        }
        progress.update(total, total, true);
    }

    private List<Row> query(List<String> roots) {
        List<String> cols = new ArrayList<>();
        for (String c : new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.DISPLAY_NAME}) cols.add(c);
        if (Build.VERSION.SDK_INT >= 30) { cols.add("album_artist"); cols.add("genre"); cols.add("disc_number"); }
        List<Row> rows = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        try (Cursor c = cr.query(audioUri(), cols.toArray(new String[0]), MediaStore.Audio.Media.IS_MUSIC + "!=0", null, null)) {
            if (c == null) return rows;
            int iId = c.getColumnIndex(MediaStore.Audio.Media._ID), iData = c.getColumnIndex(MediaStore.Audio.Media.DATA),
                    iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE), iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST),
                    iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM), iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID),
                    iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION), iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE),
                    iAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED), iMod = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED),
                    iMime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE), iTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK),
                    iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR), iComp = c.getColumnIndex(MediaStore.Audio.Media.COMPOSER),
                    iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME), iAA = c.getColumnIndex("album_artist"),
                    iGenre = c.getColumnIndex("genre"), iDisc = c.getColumnIndex("disc_number");
            while (c.moveToNext()) {
                Row r = new Row();
                r.path = str(c, iData);
                if (r.path.isEmpty() || !under(r.path, roots)) continue;
                r.id = c.getLong(iId);
                r.title = str(c, iTitle); r.artist = clean(str(c, iArtist)); r.album = clean(str(c, iAlbum));
                r.albumId = iAlbumId < 0 ? 0 : c.getLong(iAlbumId);
                r.durationMs = iDur < 0 ? 0 : c.getLong(iDur); r.size = iSize < 0 ? 0 : c.getLong(iSize);
                r.added = iAdded < 0 ? 0 : c.getLong(iAdded); r.modified = iMod < 0 ? 0 : c.getLong(iMod);
                r.mime = str(c, iMime); r.composer = clean(str(c, iComp)); r.name = str(c, iName);
                r.year = iYear < 0 ? 0 : c.getInt(iYear);
                int tr = iTrack < 0 ? 0 : c.getInt(iTrack); // Android stores disc * 1000 + track
                r.track = tr % 1000; r.disc = tr / 1000;
                r.albumArtist = clean(str(c, iAA)); r.genre = clean(str(c, iGenre));
                String dn = str(c, iDisc);
                if (!dn.isEmpty()) try { r.disc = Integer.parseInt(dn.split("/")[0].trim()); } catch (NumberFormatException ignored) { }
                rows.add(r);
                ids.put(r.path, r.id);
            }
        } catch (Exception e) {
            Log.e(App.TAG, "media query", e);
        }
        return rows;
    }

    private static boolean under(String path, List<String> roots) {
        for (String r : roots) {
            if (path.length() > r.length() && path.regionMatches(true, 0, r, 0, r.length()) && path.charAt(r.length()) == '/') return true;
        }
        return false;
    }

    private static String str(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return "";
        String s = c.getString(i);
        return s == null ? "" : s.trim();
    }

    /** Android fills in "<unknown>" for missing tags; the app wants them empty. */
    private static String clean(String s) {
        return s == null || s.equals("<unknown>") ? "" : s;
    }

    private JSONObject track(Row r, JSONObject prev, boolean force, Set<String> coverKeys, Set<String> failed) throws JSONException {
        long mtime = r.modified * 1000L;
        if (!force && prev != null && prev.optLong("mtime") == mtime && prev.optLong("size") == r.size) {
            // unchanged: keep it, but pick up a cover that has appeared since
            if (prev.isNull("cover")) {
                String key = albumKey(r);
                if (coverKeys.contains(key)) prev.put("cover", key);
            }
            return prev;
        }
        File f = new File(r.path);
        String folder = f.getParent() == null ? "" : f.getParent();
        String fileTitle = r.name.isEmpty() ? f.getName() : r.name;
        int dot = fileTitle.lastIndexOf('.');
        if (dot > 0) fileTitle = fileTitle.substring(0, dot);
        String artist = !r.artist.isEmpty() ? r.artist : r.albumArtist;
        JSONObject t = new JSONObject();
        t.put("id", Files.sha1(r.path)).put("path", r.path).put("folder", folder).put("mtime", mtime).put("size", r.size)
                .put("added", prev != null ? prev.optLong("added", r.added * 1000L) : r.added * 1000L)
                .put("title", r.title.isEmpty() ? fileTitle : r.title).put("artist", artist)
                .put("albumArtist", !r.albumArtist.isEmpty() ? r.albumArtist : artist).put("album", r.album)
                .put("genre", r.genre).put("composer", r.composer).put("year", r.year).put("track", r.track).put("disc", r.disc)
                .put("duration", r.durationMs / 1000.0);
        Uri uri = ContentUris.withAppendedId(audioUri(), r.id);
        t.put("fmt", format(uri, r));
        t.put("cover", cover(uri, r, coverKeys, failed));
        return t;
    }

    private static String albumKeyOf(String albumArtist, String album, String folder) {
        return album.isEmpty() ? Files.sha1(folder.toLowerCase(Locale.ROOT)) : Files.sha1((albumArtist + "|" + album).toLowerCase(Locale.ROOT));
    }

    private static String albumKey(Row r) {
        String artist = !r.artist.isEmpty() ? r.artist : r.albumArtist;
        String folder = new File(r.path).getParent();
        return albumKeyOf(!r.albumArtist.isEmpty() ? r.albumArtist : artist, r.album, folder == null ? "" : folder);
    }

    // ---------- format: codec, sample rate, bits, bitrate ----------

    private JSONObject format(Uri uri, Row r) throws JSONException {
        String ext = r.path.contains(".") ? r.path.substring(r.path.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : "";
        String mime = r.mime;
        int rate = 0, channels = 0, bitrate = 0, bits = 0;
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(MediaFormat.KEY_MIME);
                if (m == null || !m.startsWith("audio/")) continue;
                mime = m;
                if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (mf.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = mf.getInteger(MediaFormat.KEY_BIT_RATE) / 1000;
                if (mf.containsKey("bits-per-sample")) bits = mf.getInteger("bits-per-sample");
                if (bits == 0 && mf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    int enc = mf.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    bits = enc == 2 ? 16 : enc == 3 ? 8 : enc == 4 ? 32 : enc == 21 ? 24 : enc == 22 ? 32 : 0;
                }
                break;
            }
        } catch (Exception e) {
            // unreadable: keep the file playable with what MediaStore knows
        } finally {
            ex.release();
        }
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String codec = m.contains("flac") ? "FLAC" : m.contains("alac") ? "ALAC" : m.contains("mpeg") || m.contains("mp3") ? "MPEG 1 Layer 3"
                : m.contains("mp4a") || m.contains("aac") ? "AAC" : m.contains("opus") ? "Opus" : m.contains("vorbis") ? "Vorbis I"
                : m.contains("raw") || m.contains("wav") || m.contains("pcm") ? "PCM" : ext;
        boolean lossless = codec.equals("FLAC") || codec.equals("ALAC") || codec.equals("PCM");
        if (bits == 0 && lossless) bits = TagReader.bitsPerSample(ctx, uri);
        if (bitrate == 0 && r.durationMs > 0) bitrate = (int) Math.round(r.size * 8.0 / (r.durationMs / 1000.0) / 1000.0);
        return new JSONObject().put("codec", codec).put("container", ext).put("sampleRate", rate).put("bits", bits)
                .put("bitrate", bitrate).put("channels", channels).put("lossless", lossless);
    }

    // ---------- covers ----------

    /** The album's cover key, extracting the art the first time an album is seen (null if it has none). */
    private Object cover(Uri uri, Row r, Set<String> coverKeys, Set<String> failed) {
        String key = albumKey(r);
        if (coverKeys.contains(key)) return key;
        Object lock = coverLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (coverKeys.contains(key)) return key;
            if (failed.contains(key)) return JSONObject.NULL;
            Bitmap bm = embeddedArt(uri);
            if (bm == null && Build.VERSION.SDK_INT >= 29) {
                // Android's own thumbnail: embedded art, or an AlbumArt/cover/folder image beside the song
                try { bm = ctx.getContentResolver().loadThumbnail(uri, new Size(1000, 1000), null); } catch (Exception ignored) { }
            }
            if (bm == null && Build.VERSION.SDK_INT < 29) bm = legacyAlbumArt(r.albumId);
            if (bm != null && saveCover(key, bm)) { coverKeys.add(key); return key; }
            failed.add(key);
            return JSONObject.NULL;
        }
    }

    private Bitmap embeddedArt(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            byte[] pic = mmr.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 1000) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private Bitmap legacyAlbumArt(long albumId) {
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Albums.ALBUM_ART}, MediaStore.Audio.Albums._ID + "=?",
                new String[]{String.valueOf(albumId)}, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return BitmapFactory.decodeFile(c.getString(0));
        } catch (Exception ignored) { }
        return null;
    }

    private boolean saveCover(String key, Bitmap bm) {
        try {
            Bitmap big = bm.getWidth() > 1000 ? Bitmap.createScaledBitmap(bm, 1000, Math.max(1, bm.getHeight() * 1000 / bm.getWidth()), true) : bm;
            Bitmap small = Bitmap.createScaledBitmap(bm, 220, Math.max(1, bm.getHeight() * 220 / Math.max(1, bm.getWidth())), true);
            try (FileOutputStream o = new FileOutputStream(new File(coverDir, key + ".jpg"))) { big.compress(Bitmap.CompressFormat.JPEG, 92, o); }
            try (FileOutputStream o = new FileOutputStream(new File(coverDir, key + "_s.jpg"))) { small.compress(Bitmap.CompressFormat.JPEG, 85, o); }
            return true;
        } catch (Exception e) {
            Log.w(App.TAG, "cover " + key, e);
            return false;
        }
    }

    /** Opens a song for reading (the content:// address, or the file itself if the app may read all files). */
    ParcelFileDescriptor open(String path) throws IOException {
        Uri uri = uriFor(path);
        if (uri != null) {
            try { return ctx.getContentResolver().openFileDescriptor(uri, "r"); } catch (Exception ignored) { }
        }
        return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static FileInputStream stream(ParcelFileDescriptor pfd) {
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }
}
