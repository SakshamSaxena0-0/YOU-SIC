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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
 * Bluetooth buttons, audio focus (pause for a call or another player, resume after) and pausing when
 * headphones are unplugged. While music plays it runs in the foreground with a wake lock, so the page keeps
 * playing with the screen off and after the app is closed from the recent apps list.
 *
 * The music itself plays in the page (Web Audio, with the equalizer and effects); this service only
 * mirrors what the page tells it (Bridge.nowPlaying) and sends the buttons back as commands.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTE = 1;

    private static PlaybackService inst;
    private static JSONObject info;
    private static volatile boolean playing;

    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean hasFocus, resumeOnGain, foreground, noisyOn;
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
            else if (playing) {
                Intent it = new Intent(c, PlaybackService.class);
                try { c.startForegroundService(it); } catch (Exception e) { android.util.Log.w(MainActivity.TAG, "start service", e); }
            }
        });
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

        audio = getSystemService(AudioManager.class);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yousic:playback");
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
        if (info == null) {
            session.setActive(false);
            releaseFocus();
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
            requestFocus();
            if (!wake.isHeld()) wake.acquire();
            noisy(true);
            goForeground(n);
        } else {
            if (wake.isHeld()) wake.release();
            noisy(false);
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; }
            getSystemService(NotificationManager.class).notify(NOTE, n);
        }
    }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTE, n);
        foreground = true;
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

    // ---------- audio focus: calls, other players ----------

    private void requestFocus() {
        if (hasFocus) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onFocus)
                    .build();
        }
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void releaseFocus() {
        if (focus != null && hasFocus) audio.abandonAudioFocusRequest(focus);
        hasFocus = false;
    }

    private void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                resumeOnGain = false;
                if (playing) command("pause");
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnGain = playing;
                if (playing) command("pause");
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                if (resumeOnGain) { resumeOnGain = false; command("play"); }
                break;
            default:
                break; // ducking is done by the system
        }
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
        noisy(false);
        releaseFocus();
        if (wake.isHeld()) wake.release();
        session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
