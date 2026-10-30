package local.yousic.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Playback outside the app: a media notification and lock-screen controls (MediaSession), headset and
 * Bluetooth buttons and pausing when headphones are unplugged. While music plays it runs in the foreground
 * with a wake lock, so the page keeps playing with the screen off and after the app is closed from the
 * recent apps list.
 *
 * The music itself plays in the page (Web Audio, with the equalizer and effects); this service only
 * mirrors what the page tells it (Bridge.nowPlaying) and sends the buttons back as commands.
 *
 * Audio focus (pausing for a call or another player, resuming after a short interruption, ducking) is left
 * to the WebView: it requests focus itself whenever the page's audio plays. A second request from here
 * took the focus away from the WebView, which then paused the music.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTE = 1;
    /** How long a pause lasts before the service leaves the foreground. Between two songs the page reports
     *  "not playing" for a moment; leaving the foreground then would fail to come back with the screen off
     *  (Android lets a background app start a foreground service only in a few cases). */
    private static final long LEAVE_FOREGROUND_MS = 10_000;

    private static PlaybackService inst;
    private static JSONObject info;
    private static volatile boolean playing;
    /** Android refused to start the service (the app was in the background); try again when the app is opened. */
    private static boolean startRefused;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable leaveForeground = this::leaveForeground;
    private MediaSession session;
    private boolean foreground, noisyOn;
    private PowerManager.WakeLock wake;
    private Bitmap art;
    private String artKey = "";

    static boolean isPlaying() { return playing; }

    /** From the page (any thread): what is playing, as JSON, or "" for nothing. */
    static void update(Context c, String json) {
        JSONObject i = null;
        try { if (json != null && !json.isEmpty()) i = new JSONObject(json); } catch (JSONException ignored) { }
        final JSONObject next = i;
        new Handler(Looper.getMainLooper()).post(() -> {
            info = next;
            playing = next != null && next.optBoolean("playing");
            if (inst != null) inst.apply();
            else if (playing) start(c);
        });
    }

    private static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, PlaybackService.class));
            startRefused = false;
        } catch (Exception e) {
            startRefused = true;
            android.util.Log.w(MainActivity.TAG, "start service", e);
        }
    }

    /** The app came to the front (main thread): start the service now if Android refused it earlier. */
    static void appVisible(Context c) {
        if (startRefused && inst == null && playing) start(c);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("What is playing, with play, pause, previous and next");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        wake =getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yousic:playback");
        wake.setReferenceCounted(false);

        session = new MediaSession(this, "YouSic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { command("play"); }
            @Override public void onPause() { command("pause"); }
            @Override public void onStop() { command("pause"); }
            @Override public void onSkipToNext() { command("next"); }
            @Override public void onSkipToPrevious() { command("prev"); }
            @Override public void onSeekTo(long pos) { command("seek:" + (pos / 1000.0)); }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) {
            // started by update() with startForegroundService(): a foreground notification is due right away
            if (!foreground) goForeground(build());
        } else if ("dismiss".equals(a)) {
            if (!playing) { stopSelf(); return START_NOT_STICKY; }
        } else {
            command(a);
        }
        apply();
        return START_NOT_STICKY;
    }

    /** A button (notification, lock screen, headset): tell the page. */
    private void command(String c) {
        Bridge b = MainActivity.bridge;
        if (b != null) b.emit("cmd", JSONObject.quote(c));
    }

    private void apply() {
        main.removeCallbacks(leaveForeground);
        if (info == null) {
            session.setActive(false);
            if (wake.isHeld()) wake.release();
            noisy(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            stopSelf();
            return;
        }
        String key = info.optString("cover", "");
        if (!key.equals(artKey)) {
            artKey = key;
            art = key.isEmpty() || key.equals("null") ? null : loadArt(key);
        }
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, info.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, info.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, info.optString("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (info.optDouble("duration", 0) * 1000));
        if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(m.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        (long) (info.optDouble("position", 0) * 1000), playing ? (float) info.optDouble("rate", 1) : 0f,
                        SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);

        Notification n = build();
        if (playing) {
            if (!wake.isHeld()) wake.acquire();
            noisy(true);
            goForeground(n);
        } else {
            noisy(false);
            // stay in the foreground (and awake) for a while: this may only be the gap between two songs
            getSystemService(NotificationManager.class).notify(NOTE, n);
            if (foreground) main.postDelayed(leaveForeground, LEAVE_FOREGROUND_MS);
            else if (wake.isHeld()) wake.release();
        }
    }

    /** Paused for a while: the notification can be swiped away and the phone may sleep. */
    private void leaveForeground() {
        if (playing || !foreground) return;
        stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
        if (wake.isHeld()) wake.release();
        getSystemService(NotificationManager.class).notify(NOTE, build());
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, n);
            foreground = true;
        } catch (Exception e) {
            // Android refused (the app is in the background and no button was pressed): show the
            // notification anyway; the music keeps playing while the wake lock holds
            android.util.Log.w(MainActivity.TAG, "foreground", e);
            getSystemService(NotificationManager.class).notify(NOTE, n);
        }
    }

    private Bitmap loadArt(String key) {
        File f = new File(new File(getFilesDir(), "covers"), key + ".jpg");
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= 512) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification build() {
        JSONObject i = info == null ? new JSONObject() : info;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(i.optString("title", "YouSic"))
                .setContentText(i.optString("artist"))
                .setSubText(i.optString("album"))
                .setLargeIcon(art)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(action("dismiss", 9))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Previous", action("prev", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "Pause" : "Play", action("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Next", action("next", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    // ---------- headphones unplugged ----------

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction()) && playing) command("pause");
        }
    };

    private void noisy(boolean on) {
        if (on == noisyOn) return;
        noisyOn = on;
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (!on) unregisterReceiver(noisyReceiver);
        else if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
    }

    @Override
    public void onTaskRemoved(Intent root) {
        if (!playing) stopSelf();
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        main.removeCallbacks(leaveForeground);
        noisy(false);
        if (wake.isHeld()) wake.release();
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
