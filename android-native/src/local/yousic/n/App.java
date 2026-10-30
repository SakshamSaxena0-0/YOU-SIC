package local.yousic.n;

import android.app.Application;
import android.content.SharedPreferences;

/** The app: one library and one player for every screen and the playback service. */
public final class App extends Application {
    public static final String TAG = "YouSicN";
    private static App inst;

    public Lib lib;
    public Engine engine;
    public Effects fx;
    public Vis vis;
    public SharedPreferences prefs;

    public String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    public static App get() { return inst; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        Motion.speed = prefs.getInt("animSpeed", Motion.SPEED_NORMAL);
        lib = new Lib(this);
        engine = new Engine(this, lib);
        fx = new Effects(this, engine.session);
        vis = new Vis(engine.session);
    }
}
