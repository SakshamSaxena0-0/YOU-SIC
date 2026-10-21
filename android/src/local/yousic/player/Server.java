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

    Server(Context ctx, Library lib) {
        this.ctx = ctx;
        this.assets = ctx.getAssets();
        this.lib = lib;
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
                return ok("image/jpeg", null, new FileInputStream(f), f.length(), "max-age=31536000");
            }
            if (p.equals("/__pa/library.json")) {
                lib.load();
                return ok("application/json", "utf-8", new FileInputStream(lib.file), lib.file.length(), "no-store");
            }
            if (p.equals("/") || p.isEmpty()) p = "/renderer/index.html";
            for (String dir : ASSET_DIRS) {
                if (p.startsWith(dir) && !p.contains("..")) {
                    String type = mime(p);
                    InputStream in;
                    try { in = assets.open("www" + p); } catch (IOException e) { return status(404); }
                    return ok(type, type.startsWith("text/") || type.endsWith("json") || type.endsWith("javascript") ? "utf-8" : null, in, -1, "no-cache");
                }
            }
            return status(404);
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "serve " + p, e);
            return status(500);
        }
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
        h.put("Content-Length", String.valueOf(len));
        if (code == 206) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        InputStream body = "HEAD".equalsIgnoreCase(req.getMethod()) ? new ByteArrayInputStream(new byte[0]) : new Limited(in, len);
        return new WebResourceResponse(mime(path), null, code, code == 206 ? "Partial Content" : "OK", h, body);
    }

    private static Map<String, String> baseHeaders(String cache) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", cache);
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    private static WebResourceResponse ok(String type, String enc, InputStream in, long len, String cache) {
        Map<String, String> h = baseHeaders(cache);
        if (len >= 0) h.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(type, enc, 200, "OK", h, in);
    }

    private static WebResourceResponse status(int code) {
        return new WebResourceResponse("text/plain", "utf-8", code, code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error",
                baseHeaders("no-store"), new ByteArrayInputStream(new byte[0]));
    }

    /** Reads at most n bytes, then reports the end (closing the file when done). */
    private static final class Limited extends FilterInputStream {
        private long left;
        Limited(InputStream in, long n) { super(in); left = n; }
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
        @Override public long skip(long n) throws IOException {
            long s = super.skip(Math.min(n, left));
            left -= s;
            return s;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
    }
}
