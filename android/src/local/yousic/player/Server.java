package local.yousic.player;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the page loads, served from inside the app (the Windows app's app:// protocol in main.js):
 *   /renderer/..., /node_modules/butterchurn.../lib, /android/..., /build/...   the app's files (APK assets)
 *   /media/<base64url path>    a song, with byte ranges so seeking works
 *   /cover/<key>(_s).jpg       a cover
 *   /__pa/library.json         the library
 */
final class Server {
    static final String HOST = "appassets.androidplatform.net";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final String[] ASSET_DIRS = {"/renderer/", "/node_modules/butterchurn/lib/", "/node_modules/butterchurn-presets/lib/", "/android/", "/build/"};
    private static final Map<String, String> MIME = new HashMap<>();
    static {
        String[][] m = {{"html", "text/html"}, {"js", "text/javascript"}, {"mjs", "text/javascript"}, {"css", "text/css"},
                {"svg", "image/svg+xml"}, {"png", "image/png"}, {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"json", "application/json"},
                {"woff2", "font/woff2"}, {"txt", "text/plain"}, {"ico", "image/x-icon"},
                {"mp3", "audio/mpeg"}, {"flac", "audio/flac"}, {"m4a", "audio/mp4"}, {"mp4", "audio/mp4"}, {"aac", "audio/aac"},
                {"ogg", "audio/ogg"}, {"oga", "audio/ogg"}, {"opus", "audio/ogg"}, {"wav", "audio/wav"}, {"webm", "audio/webm"}, {"weba", "audio/webm"}};
        for (String[] e : m) MIME.put(e[0], e[1]);
    }

    private final Context ctx;
    private final AssetManager assets;
    private final Library lib;
    /** the last few failures, for the page's diagnostics (Bridge.diag) */
    final java.util.Deque<String> errors = new java.util.concurrent.ConcurrentLinkedDeque<>();

    Server(Context ctx, Library lib) {
        this.ctx = ctx;
        this.assets = ctx.getAssets();
        this.lib = lib;
    }

    /** The app's own copy of a page file (for MainActivity's fallback load). */
    String readAsset(String path) throws IOException {
        try (InputStream in = assets.open("www" + path)) {
            return new String(Files.readAll(in), StandardCharsets.UTF_8);
        }
    }

    WebResourceResponse handle(WebResourceRequest req) {
        Uri u = req.getUrl();
        if (!HOST.equals(u.getHost())) return null;
        String p = u.getPath() == null ? "/" : u.getPath();
        try {
            if (p.startsWith("/media/")) return media(req, p.substring(7));
            if (p.startsWith("/cover/")) {
                String name = p.substring(7);
                if (!name.matches("[a-f0-9]{40}(_s)?\\.jpg")) return status(400);
                File f = new File(lib.coverDir, name);
                if (!f.isFile()) return status(404);
                return ok("image/jpeg", null, new FileInputStream(f), "max-age=31536000");
            }
            if (p.equals("/__pa/library.json")) {
                lib.load();
                return ok("application/json", "utf-8", new FileInputStream(lib.file), "no-store");
            }
            if (p.equals("/") || p.isEmpty()) p = "/renderer/index.html";
            for (String dir : ASSET_DIRS) {
                if (p.startsWith(dir) && !p.contains("..")) {
                    String type = mime(p);
                    InputStream in;
                    try {
                        in = assets.open("www" + p);
                    } catch (IOException e) {
                        return explain(404, p, "the app's file www" + p + " could not be opened: " + e + "\n\n" + assetList());
                    }
                    return ok(type, type.startsWith("text/") || type.endsWith("json") || type.endsWith("javascript") ? "utf-8" : null, in, "no-cache");
                }
            }
            return explain(404, p, "no such address in the app");
        } catch (Throwable e) {
            Log.w(MainActivity.TAG, "serve " + p, e);
            return explain(500, p, Log.getStackTraceString(e));
        }
    }

    /**
     * An error with a page that says what went wrong (an error with no body only gets WebView's bare
     * "Web page not available"). Shown in place of the app if its own page fails to load.
     */
    WebResourceResponse explain(int code, String path, String why) {
        Log.e(MainActivity.TAG, code + " " + path + ": " + why);
        errors.addLast(code + " " + path + ": " + why);
        while (errors.size() > 12) errors.pollFirst();
        String html = "<!doctype html><meta name=viewport content='width=device-width'><body style='background:#07070a;color:#eee;"
                + "font:14px sans-serif;padding:16px'><h3>YouSic couldn't load " + esc(path) + " (" + code + ")</h3><pre style='white-space:"
                + "pre-wrap;font-size:12px;color:#bbb'>" + esc(why) + "\n\nAndroid " + android.os.Build.VERSION.RELEASE + " (API "
                + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "</pre>";
        return new WebResourceResponse("text/html", "utf-8", code, code == 404 ? "Not Found" : "Error", baseHeaders("no-store"),
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private String assetList() {
        StringBuilder b = new StringBuilder("What the app has inside:\n");
        for (String d : new String[]{"", "www", "www/renderer"}) {
            try { b.append("  ").append(d.isEmpty() ? "(top)" : d).append(": ").append(java.util.Arrays.toString(assets.list(d))).append('\n'); }
            catch (Exception e) { b.append("  ").append(d).append(": ").append(e).append('\n'); }
        }
        return b.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String mime(String p) {
        int dot = p.lastIndexOf('.');
        String t = dot < 0 ? null : MIME.get(p.substring(dot + 1).toLowerCase(Locale.ROOT));
        return t == null ? "application/octet-stream" : t;
    }

    private static String header(WebResourceRequest req, String name) {
        Map<String, String> h = req.getRequestHeaders();
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /** A song, with HTTP byte ranges (the audio element asks for pieces as it plays and seeks). */
    private WebResourceResponse media(WebResourceRequest req, String b64) throws IOException {
        String path = new String(Base64.decode(b64, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), StandardCharsets.UTF_8);
        if (!lib.has(path)) return status(403);
        ParcelFileDescriptor pfd = lib.open(path);
        long size = pfd.getStatSize();
        long start = 0, end = size - 1;
        int code = 200;
        String range = header(req, "Range");
        if (range != null && size > 0) {
            Matcher m = RANGE.matcher(range);
            if (m.find()) {
                String a = m.group(1), b = m.group(2);
                if (a.isEmpty() && !b.isEmpty()) start = Math.max(0, size - Long.parseLong(b));
                else {
                    start = a.isEmpty() ? 0 : Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                }
                if (start > end || start >= size) {
                    pfd.close();
                    Map<String, String> h = baseHeaders("no-cache");
                    h.put("Content-Range", "bytes */" + size);
                    return new WebResourceResponse("text/plain", null, 416, "Range Not Satisfiable", h, new ByteArrayInputStream(new byte[0]));
                }
                code = 206;
            }
        }
        FileInputStream in = Library.stream(pfd);
        in.getChannel().position(start);
        long len = end - start + 1;
        Map<String, String> h = baseHeaders("no-cache");
        h.put("Accept-Ranges", "bytes");
        if (code == 206) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        InputStream body = "HEAD".equalsIgnoreCase(req.getMethod()) ? new ByteArrayInputStream(new byte[0]) : new Range(in, start, len);
        return new WebResourceResponse(mime(path), null, code, code == 206 ? "Partial Content" : "OK", h, body);
    }

    private static Map<String, String> baseHeaders(String cache) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", cache);
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    // No Content-Length of our own: WebView works it out from the stream's available(), and two different
    // Content-Length headers would break the response.
    private static WebResourceResponse ok(String type, String enc, InputStream in, String cache) {
        return new WebResourceResponse(type, enc, 200, "OK", baseHeaders(cache), in);
    }

    private WebResourceResponse status(int code) {
        return explain(code, "", code == 403 ? "not a song in the library" : code == 400 ? "bad address" : "not found");
    }

    /**
     * One byte range of a song, already positioned at its start.
     *
     * WebView reads intercepted responses in its own way: it takes available() as the size of the whole
     * stream, checks the request's Range against it, skips to the range's first byte and sets Content-Length.
     * The range is applied here already (with a 206 and Content-Range for the media player), so available()
     * reports the bytes up to the range's end (making WebView's check and Content-Length come out right) and
     * skip() does nothing (so the start isn't skipped twice).
     */
    private static final class Range extends FilterInputStream {
        private final long start;
        private long left;
        Range(InputStream in, long start, long len) { super(in); this.start = start; left = len; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int n) throws IOException {
            if (left <= 0) return -1;
            int r = super.read(buf, off, (int) Math.min(n, left));
            if (r > 0) left -= r;
            return r;
        }
        @Override public long skip(long n) { return n; }
        @Override public int available() { return (int) Math.min(Integer.MAX_VALUE, start + left); }
    }
}
