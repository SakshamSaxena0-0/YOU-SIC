package local.yousic.player;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Android side of window.pa (see android/web/pa-android.js and preload.js on Windows).
 *
 * The page calls PaNative.call(id, method, argsJson); the work runs off the UI thread and the answer comes
 * back as window.__paResolve(id, json). The library can be thousands of tracks, so it is not sent this way:
 * it is written to library.json and the page fetches it (Server: /__pa/library.json).
 */
final class Bridge {
    private final Context app;
    private final Library lib;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** scans, one at a time, in order */
    private final ExecutorService scanExec = Executors.newSingleThreadExecutor();
    /** everything else (settings, lyrics), so it doesn't wait behind a scan */
    private final ExecutorService exec = Executors.newCachedThreadPool();
    private static final String[] STORES = {"settings", "stats", "playlists"};

    private int pendingFolder = -1, pendingFiles = -1;

    Bridge(Context app, Library lib) {
        this.app = app;
        this.lib = lib;
    }

    // ---------- called by the page ----------

    @JavascriptInterface
    public void call(final int id, final String method, String argsJson) {
        JSONArray a;
        try { a = new JSONArray(argsJson == null ? "[]" : argsJson); } catch (JSONException e) { a = new JSONArray(); }
        final JSONArray args = a;
        switch (method) {
            case "getLibrary":
                exec.execute(() -> { lib.load(); resolve(id, "true"); });
                break;
            case "scan":
                scanExec.execute(() -> { scan(args.optBoolean(0)); resolve(id, "true"); });
                break;
            case "addFolder":
                main.post(() -> pickFolder(id));
                break;
            case "removeFolder":
                scanExec.execute(() -> { lib.removeFolder(args.optString(0)); scan(false); resolve(id, "true"); });
                break;
            case "lyrics":
                exec.execute(() -> {
                    JSONObject l = null;
                    try { l = Lyrics.get(app, lib, args.optString(0)); } catch (Exception e) { Log.w(MainActivity.TAG, "lyrics", e); }
                    resolve(id, l == null ? null : l.toString());
                });
                break;
            case "load":
                exec.execute(() -> {
                    File f = store(args.optString(0));
                    resolve(id, f == null ? null : Files.read(f));
                });
                break;
            case "save":
                exec.execute(() -> resolve(id, String.valueOf(write(args.optString(0), args.optString(1)))));
                break;
            case "requestFileAccess":
                main.post(() -> requestFileAccess(id));
                break;
            default:
                resolve(id, null);
        }
    }

    /** Used when the app goes to the background, so the last state is on disk before Android can close it. */
    @JavascriptInterface
    public boolean saveSync(String name, String json) {
        return write(name, json);
    }

    /** What is playing, for the notification, lock screen and headset buttons ("" = nothing). */
    @JavascriptInterface
    public void nowPlaying(String json) {
        PlaybackService.update(app, json);
    }

    /** Whether the app may read any file (needed for .lrc lyrics files next to songs). */
    @JavascriptInterface
    public boolean fileAccess() {
        return Files.canReadAll(app);
    }

    /** For the page's diagnostics panel: versions and what failed (see pa-android.js). */
    @JavascriptInterface
    public String diag() {
        StringBuilder b = new StringBuilder();
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        b.append("WebView: ").append(wv == null ? "?" : wv.packageName + " " + wv.versionName).append('\n');
        b.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        b.append("Music permission: ").append(MainActivity.canReadMusic(app)).append('\n');
        if (MainActivity.mainError != null) b.append("Page load: ").append(MainActivity.mainError).append(" (loaded the second way)\n");
        Server s = MainActivity.server;
        if (s != null) for (String e : s.errors) b.append(e).append('\n');
        return b.toString();
    }

    @JavascriptInterface
    public void moveToBack() {
        main.post(() -> { if (MainActivity.current != null) MainActivity.current.moveTaskToBack(true); });
    }

    // ---------- answers to the page ----------

    /** Resolves the page's promise with a JSON text (or null). */
    void resolve(int id, String json) {
        final String lit = json == null ? "null" : JSONObject.quote(json);
        main.post(() -> {
            WebView w = MainActivity.web;
            if (w != null) w.evaluateJavascript("window.__paResolve&&window.__paResolve(" + id + "," + lit + ")", null);
        });
    }

    // ---------- buttons outside the page: notification, lock screen, headset, home-screen widget ----------

    private final java.util.ArrayList<String> waiting = new java.util.ArrayList<>();
    private boolean listening;

    /** A button for the page (main thread). Kept until the page listens: a widget button may have started it. */
    void command(String c) {
        if (listening) emit("cmd", JSONObject.quote(c));
        else waiting.add(c);
    }

    /** From the page: it is listening for commands now. */
    @JavascriptInterface
    public void commandsReady() {
        main.post(() -> {
            listening = true;
            for (String c : waiting) emit("cmd", JSONObject.quote(c));
            waiting.clear();
        });
    }

    /** An event for the page: "progress" (library scan) or "cmd" (prev, next, toggle, play, pause, seek:N). */
    void emit(String type, String json) {
        final String js = "window.__paEmit&&window.__paEmit(" + JSONObject.quote(type) + "," + JSONObject.quote(json) + ")";
        main.post(() -> {
            WebView w = MainActivity.web;
            if (w != null) w.evaluateJavascript(js, null);
        });
    }

    private void scan(boolean force) {
        if (!MainActivity.canReadMusic(app)) { lib.load(); return; }
        lib.scan(force, (done, total, finished) -> {
            try {
                JSONObject m = new JSONObject();
                m.put("phase", finished ? "done" : "scan").put("done", done).put("total", total);
                emit("progress", m.toString());
            } catch (JSONException ignored) { }
        });
    }

    // ---------- settings files ----------

    private File store(String name) {
        for (String s : STORES) if (s.equals(name)) return new File(app.getFilesDir(), name + ".json");
        return null;
    }

    private boolean write(String name, String json) {
        File f = store(name);
        if (f == null || json == null) return false;
        try { Files.writeAtomic(f, json); return true; } catch (Exception e) { Log.w(MainActivity.TAG, "save " + name, e); return false; }
    }

    // ---------- adding a folder: Android's folder picker ----------

    private void pickFolder(int id) {
        MainActivity a = MainActivity.current;
        if (a == null) { resolve(id, "true"); return; }
        pendingFolder = id;
        String[] missing = MainActivity.missingPermissions(a);
        if (!MainActivity.canReadMusic(a) && missing.length > 0) { a.requestPermissions(missing, MainActivity.REQ_PERMS_FOLDER); return; }
        openTree(a);
    }

    void afterFolderPermission(Activity a) {
        if (pendingFolder < 0) return;
        if (MainActivity.canReadMusic(a)) { openTree(a); return; }
        int id = pendingFolder; pendingFolder = -1;
        // refused for good: Android won't ask again, so open the app's settings page where it can be allowed
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (!a.shouldShowRequestPermissionRationale(perm)) {
            try {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName())));
            } catch (Exception ignored) { }
        }
        resolve(id, "true");
    }

    @SuppressWarnings("deprecation")
    private void openTree(Activity a) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQ_TREE);
        } catch (Exception e) {
            int id = pendingFolder; pendingFolder = -1;
            scanExec.execute(() -> { scan(false); resolve(id, "true"); });
        }
    }

    // ---------- reading .lrc files: "All files access" ----------

    @SuppressWarnings("deprecation")
    private void requestFileAccess(int id) {
        MainActivity a = MainActivity.current;
        if (a == null || Files.canReadAll(app)) { resolve(id, String.valueOf(Files.canReadAll(app))); return; }
        pendingFiles = id;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + app.getPackageName())), MainActivity.REQ_FILES);
            } catch (Exception e) {
                a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), MainActivity.REQ_FILES);
            }
        } else {
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MainActivity.REQ_FILES);
        }
    }

    void onActivityResult(int req, int result, Intent data) {
        if (req == MainActivity.REQ_FILES && pendingFiles >= 0) {
            int id = pendingFiles; pendingFiles = -1;
            resolve(id, String.valueOf(Files.canReadAll(app)));
        } else if (req == MainActivity.REQ_TREE && pendingFolder >= 0) {
            final int id = pendingFolder; pendingFolder = -1;
            final String path = result == Activity.RESULT_OK && data != null ? treePath(data.getData()) : null;
            scanExec.execute(() -> {
                if (path != null) lib.addFolder(path);
                scan(false);
                resolve(id, "true");
            });
        }
    }

    /** The folder Android's picker returned, as a file path (that is how the library knows folders). */
    static String treePath(Uri tree) {
        if (tree == null) return null;
        try {
            String doc = DocumentsContract.getTreeDocumentId(tree);
            String auth = tree.getAuthority();
            if ("com.android.externalstorage.documents".equals(auth)) {
                int c = doc.indexOf(':');
                String vol = c < 0 ? doc : doc.substring(0, c), rest = c < 0 ? "" : doc.substring(c + 1);
                String root = "primary".equalsIgnoreCase(vol) ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
                return rest.isEmpty() ? root : root + "/" + rest;
            }
            if ("com.android.providers.downloads.documents".equals(auth)) {
                if (doc.startsWith("raw:")) return doc.substring(4);
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath();
            }
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "tree " + tree, e);
        }
        return null;
    }
}
