package local.yousic.n;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;

import java.util.ArrayList;
import java.util.List;

/** The only screen: the stage, edge to edge. Asks once for the music and notification permissions. */
public final class MainActivity extends Activity implements Lib.Listener {
    private static final int REQ = 1;
    private Stage stage;
    private App app;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = App.get();
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        stage = new Stage(this, app);
        setContentView(stage);
        w.setBackgroundDrawable(null); // the stage's backdrop covers the window: no fill under it every frame
        app.lib.listen(this);
        List<String> need = new ArrayList<>();
        String audio = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(audio) != PackageManager.PERMISSION_GRANTED) need.add(audio);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) start();
        else requestPermissions(need.toArray(new String[0]), REQ);
    }

    private boolean started;

    private void start() {
        if (started) return;
        started = true;
        app.lib.loadAndScan();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == Stage.REQ_VIS) { stage.onPermission(req, results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED); return; }
        start();
    }

    /** The library has loaded: carry on with the last session (paused) if nothing plays yet. */
    @Override
    public void onLibrary() {
        if (app.engine.track() == null) app.engine.restore();
    }

    @Override
    protected void onDestroy() {
        // the library and the player outlive this screen: let go of it
        app.lib.unlisten(this);
        app.lib.unlisten(stage.library);
        app.engine.unlisten(stage.player);
        app.engine.unlisten(stage.library);
        if (stage.lyrics != null) stage.lyrics.close();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!stage.back()) moveTaskToBack(true);
    }
}
