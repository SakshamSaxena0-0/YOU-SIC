package local.yousic.player;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts the YouSic interface (the same renderer/ the Windows app uses) in a WebView.
 *
 * The WebView is kept in a static field and created on a MutableContextWrapper, so while music plays it
 * outlives this activity: closing the app from the recent apps list leaves the song playing (the
 * PlaybackService keeps the process alive), and opening it again re-attaches the same page.
 */
public class MainActivity extends Activity {
    static final String TAG = "YouSic";
    static final String ORIGIN = "https://appassets.androidplatform.net/";
    static final int REQ_PERMS = 1, REQ_PERMS_FOLDER = 2, REQ_TREE = 3, REQ_FILES = 4;

    static WebView web;
    static MutableContextWrapper webCtx;
    static Bridge bridge;
    static Server server;
    static MainActivity current;
    /** why the page had to be loaded the second way (for the diagnostics), or null */
    static String mainError;
    private static boolean triedFallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        boolean fresh = createPage(this);
        if (!fresh) {
            webCtx.setBaseContext(this);
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
        }
        setContentView(web);
        if (fresh) {
            String[] missing = missingPermissions(this);
            if (missing.length > 0) requestPermissions(missing, REQ_PERMS);
            else load();
        }
    }

    /** Creates the page (WebView, server, bridge) unless it exists. True if it was created now. Main thread. */
    private static boolean createPage(Context base) {
        if (web != null) return false;
        Context app = base.getApplicationContext();
        Library library = new Library(app);
        server = new Server(app, library);
        bridge = new Bridge(app, library);
        triedFallback = false;
        webCtx = new MutableContextWrapper(base);
        web = new WebView(webCtx);
        setup(web, server);
        return true;
    }

    /**
     * Starts the page without opening the app (a widget button pressed while the app was closed): the music
     * plays in it. The activity adopts it when the app is opened. False if it can't start (no permission
     * to read music yet: the app has to be opened once).
     */
    static boolean startPageInBackground(Context c) {
        if (web != null) return true;
        if (!canReadMusic(c)) return false;
        createPage(c.getApplicationContext());
        load();
        return true;
    }

    private static void load() {
        web.loadUrl(ORIGIN + "renderer/index.html");
    }

    /** Reading music, and (Android 13+) showing the playback notification. */
    static String[] missingPermissions(Context c) {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_AUDIO);
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        return missing.toArray(new String[0]);
    }

    static boolean canReadMusic(Context c) {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private static void setup(WebView w, Server server) {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        w.setBackgroundColor(0xFF07070A);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // the page plays the music: keep its process important even while the app is out of sight
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        w.addJavascriptInterface(bridge, "PaNative");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return server.handle(request);
            }

            // links out of the app (lyrics searches) open in the browser
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().startsWith(ORIGIN)) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    view.getContext().startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "no app for " + request.getUrl());
                }
                return true;
            }

            // The page itself failed to load: load the app's copy of it directly instead (on the same origin, so
            // everything else still comes through shouldInterceptRequest), and remember why for the diagnostics.
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) fallback(view, "HTTP " + response.getStatusCode() + " " + response.getReasonPhrase());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) fallback(view, error.getDescription() + " (" + error.getErrorCode() + ")");
            }

            // if the page's process dies, start again with a fresh WebView rather than crash
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.e(TAG, "renderer gone, crashed=" + detail.didCrash());
                if (view == web) {
                    ViewGroup parent = (ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.destroy();
                    web = null;
                    if (current != null) current.recreate();
                }
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
    }

    private static void fallback(WebView view, String why) {
        Log.e(TAG, "page failed: " + why);
        mainError = why;
        if (triedFallback) return;
        triedFallback = true;
        String url = ORIGIN + "renderer/index.html";
        try {
            view.loadDataWithBaseURL(url, server.readAsset("/renderer/index.html"), "text/html", "utf-8", null);
        } catch (Exception e) {
            view.loadDataWithBaseURL(null, "<body style='background:#07070a;color:#eee;font:14px sans-serif;padding:16px'><h3>YouSic couldn't open its page</h3><pre style='white-space:pre-wrap'>"
                    + why + "\n" + e + "</pre>", "text/html", "utf-8", null);
        }
    }

    /** Android's back button: the page goes back a step if it can (closes a menu, leaves the library...). */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.__paBack ? window.__paBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    /** Leaving the app: save settings, stats and the session now (Android may close the app without warning). */
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.evaluateJavascript("window.__paPause && window.__paPause()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlaybackService.appVisible(this);
        if (web != null) web.evaluateJavascript("window.__paResume && window.__paResume()", null);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        if (web != null && web.getContext() == webCtx) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            webCtx.setBaseContext(getApplicationContext());
            // closed while nothing plays: let everything go
            if (isFinishing() && !PlaybackService.isPlaying()) {
                web.destroy();
                web = null;
                bridge = null;
                PlaybackService.stop(getApplicationContext());
            }
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_PERMS) load();
        else if (req == REQ_PERMS_FOLDER && bridge != null) bridge.afterFolderPermission(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (bridge != null) bridge.onActivityResult(req, result, data);
    }
}
