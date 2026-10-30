package local.yousic.n;

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
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * Playback outside the app: the media notification, lock screen and headset / Bluetooth buttons
 * (MediaSession), pausing when headphones are unplugged, and running in the foreground while music plays
 * so it keeps playing with the screen off.
 */
public final class PlayerService extends Service implements Engine.Listener {
    private static final String CHANNEL = "playback";
    private static final int NOTE = 1;
    /** paused this long: the notification can be swiped away (a song change briefly reports "paused") */
    private static final long LEAVE_FOREGROUND_MS = 10_000;

    private MediaSession session;
    private Engine engine;
    private boolean foreground, noisyOn;
    private Bitmap art;
    private String artKey = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable leave = this::leaveForeground;

    /** Started when playback starts (from the app, in the foreground). */
    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, PlayerService.class)); }
        catch (Exception e) { Log.w(App.TAG, "start service", e); }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        engine = App.get().engine;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        session = new MediaSession(this, "YouSicN");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { engine.play(); }
            @Override public void onPause() { engine.pause(); }
            @Override public void onStop() { engine.pause(); }
            @Override public void onSkipToNext() { engine.next(); }
            @Override public void onSkipToPrevious() { engine.prev(); }
            @Override public void onSeekTo(long pos) { engine.seekTo(pos); update(); }
        });
        session.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        engine.listen(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (!foreground) goForeground(build());
        if (a != null && !a.equals("dismiss")) engine.command(a);
        if ("dismiss".equals(a) && !engine.isPlaying()) { stopSelf(); return START_NOT_STICKY; }
        update();
        return START_NOT_STICKY;
    }

    @Override public void onTrack(Lib.Track t) { update(); }
    @Override public void onState(boolean playing) { update(); }

    private final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void update() {
        Lib.Track t = engine.track();
        if (t == null) return;
        String key = t.cover == null ? "" : t.cover;
        if (!key.equals(artKey)) {
            // decoded off the main thread; the notification is updated again once it is ready
            artKey = key;
            art = null;
            if (!key.isEmpty()) bg.execute(() -> {
                File f = App.get().lib.coverFile(key, false);
                final Bitmap b = f.isFile() ? decode(f, 512) : null;
                if (b != null) main.post(() -> { if (key.equals(artKey)) { art = b; update(); } });
            });
        }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title).putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album).putLong(MediaMetadata.METADATA_KEY_DURATION, engine.durationMs())
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        boolean playing = engine.isPlaying();
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, engine.positionMs(), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
        Notification n = build();
        main.removeCallbacks(leave);
        if (playing) {
            noisy(true);
            goForeground(n);
        } else {
            noisy(false);
            getSystemService(NotificationManager.class).notify(NOTE, n);
            if (foreground) main.postDelayed(leave, LEAVE_FOREGROUND_MS);
        }
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTE, n);
            foreground = true;
        } catch (Exception e) {
            Log.w(App.TAG, "foreground", e);
            getSystemService(NotificationManager.class).notify(NOTE, n);
        }
    }

    private void leaveForeground() {
        if (engine.isPlaying() || !foreground) return;
        stopForeground(STOP_FOREGROUND_DETACH);
        foreground = false;
        getSystemService(NotificationManager.class).notify(NOTE, build());
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, Engine.intent(this, a), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification build() {
        Lib.Track t = engine.track();
        boolean playing = engine.isPlaying();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(t == null ? "YouSic" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setLargeIcon(art)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(action("dismiss", 9))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Previous", action("prev", 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "Pause" : "Play", action("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Next", action("next", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) engine.pause(); }
    };

    private void noisy(boolean on) {
        if (on == noisyOn) return;
        noisyOn = on;
        if (!on) { unregisterReceiver(noisyReceiver); return; }
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, f);
    }

    static Bitmap decode(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    @Override
    public void onDestroy() {
        engine.unlisten(this);
        main.removeCallbacks(leave);
        noisy(false);
        session.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
