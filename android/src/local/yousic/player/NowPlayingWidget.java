package local.yousic.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The home-screen widget ("Moon Player", 5 x 3 cells), styled after a starry-night wallpaper: the cover is a
 * moon with the song's progress orbiting it, and the song's waveform is a mountain range that lights up as
 * it plays. Prev, play/pause and next control the app (PlaybackService; with the app closed, it starts the
 * page in the background); a tap anywhere else opens the app.
 *
 * Drawn as bitmaps once per song, play state and size. While a song plays only the progress moves: the
 * ring, the lit width of the range and two stars are updated every few seconds while the screen is on,
 * and the times are chronometers that tick by themselves.
 */
public class NowPlayingWidget extends AppWidgetProvider {
    /** PlaybackService action prefix for the buttons: "widget:prev", "widget:toggle", "widget:next". */
    static final String ACTION = "widget:";

    // the wallpaper's palette
    private static final int STAR = 0xFFF2F3F8, DIM = 0xFFA3A8BC, ICE = 0xFF8CB5D3, ICE_HI = 0xFFC4DDF0,
            MIST = 0xFF71768C, HAZE = 0xFF3C3D4F, SLOPE = 0xFF151628, INK = 0xFF0A0A16;
    // the design is drawn on a 992 x 756 grid (the 5 x 3 cells on a 1080 px wide phone) and scaled to the widget
    private static final float DW = 992, DH = 756;
    private static final long TICK_MS = 5000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context appCtx;
    /** What the page last said (null until it says anything; the saved last song is shown meanwhile). */
    private static JSONObject info;
    private static boolean playing;
    /** SystemClock.elapsedRealtime() when info's position was current. */
    private static long infoAt;
    private static final Map<Integer, Geo> geos = new HashMap<>();
    private static final Map<Integer, String> drawn = new HashMap<>();

    // ---------- from the app ----------

    /** What is playing changed (main thread; from PlaybackService.update). */
    static void onNowPlaying(Context c, JSONObject next, boolean isPlaying) {
        appCtx = c.getApplicationContext();
        if (next != null) {
            info = next;
            save(appCtx, next, isPlaying);
        }
        playing = next != null && isPlaying;
        infoAt = SystemClock.elapsedRealtime();
        refresh(appCtx, false);
        main.removeCallbacks(tick);
        if (playing) main.postDelayed(tick, TICK_MS);
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!playing || appCtx == null) return;
            if (appCtx.getSystemService(PowerManager.class).isInteractive()) refresh(appCtx, false);
            main.postDelayed(this, TICK_MS);
        }
    };

    // ---------- from the launcher ----------

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        appCtx = c.getApplicationContext();
        for (int id : ids) draw(appCtx, m, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        appCtx = c.getApplicationContext();
        draw(appCtx, m, id); // resized
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        for (int id : ids) { geos.remove(id); drawn.remove(id); }
    }

    // ---------- updating ----------

    /** Redraws what changed: everything for a new song, play state or size, otherwise only the progress. */
    private static void refresh(Context c, boolean full) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, NowPlayingWidget.class));
        JSONObject i = current(c);
        boolean pl = playing && info != null;
        for (int id : ids) {
            Geo g = geos.get(id);
            if (full || g == null || !key(i, pl).equals(drawn.get(id)) || !g.sameSize(c, m, id)) draw(c, m, id);
            else {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_player);
                progress(v, g, i, pl);
                m.partiallyUpdateAppWidget(id, v);
            }
        }
    }

    private static void draw(Context c, AppWidgetManager m, int id) {
        Geo g = new Geo(c, m, id);
        JSONObject i = current(c);
        boolean pl = playing && info != null;
        g.ridge(wave(i), i == null ? 0 : (i.optString("title") + i.optString("artist")).hashCode());
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_player);
        v.setImageViewBitmap(R.id.bg, background(c, g, i, pl));
        v.setImageViewBitmap(R.id.lit, lit(g));
        Bitmap star = star(8 * g.s);
        g.starW = star.getWidth();
        v.setImageViewBitmap(R.id.ring_star, star);
        Bitmap head = head(g);
        g.headW = head.getWidth();
        v.setImageViewBitmap(R.id.head, head);

        float px = TypedValue.COMPLEX_UNIT_PX;
        v.setTextViewTextSize(R.id.elapsed, (int) px, 30 * g.s);
        v.setTextViewTextSize(R.id.remain, (int) px, 30 * g.s);
        if (Build.VERSION.SDK_INT >= 31) {
            int P = TypedValue.COMPLEX_UNIT_PX;
            float ring = 399 * g.s; // the ring drawable's ratios are for a 196 radius, 5 thick ring in 399
            v.setViewLayoutWidth(R.id.ring, ring, P);
            v.setViewLayoutHeight(R.id.ring, ring, P);
            v.setViewLayoutMargin(R.id.ring, RemoteViews.MARGIN_LEFT, g.cx - ring / 2, P);
            v.setViewLayoutMargin(R.id.ring, RemoteViews.MARGIN_TOP, g.cy - ring / 2, P);
            v.setViewLayoutWidth(R.id.lit, g.w, P);
            v.setViewLayoutMargin(R.id.elapsed, RemoteViews.MARGIN_LEFT, 64 * g.sx, P);
            v.setViewLayoutMargin(R.id.elapsed, RemoteViews.MARGIN_TOP, 486 * g.sy, P);
            v.setViewLayoutMargin(R.id.remain, RemoteViews.MARGIN_RIGHT, 64 * g.sx, P);
            v.setViewLayoutMargin(R.id.remain, RemoteViews.MARGIN_TOP, 486 * g.sy, P);
            hit(v, R.id.hit_prev, 543 * g.sx, g.by, 120 * g.s);
            hit(v, R.id.hit_play, 676 * g.sx, g.by, 180 * g.s);
            hit(v, R.id.hit_next, 809 * g.sx, g.by, 120 * g.s);
        } else {
            // no way to place them: keep only what needs no positioning
            v.setViewVisibility(R.id.lit_clip, View.GONE);
            v.setViewVisibility(R.id.head, View.GONE);
            v.setViewVisibility(R.id.ring_star, View.GONE);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setOnClickPendingIntent(R.id.hit_prev, button(c, "prev", 21));
        v.setOnClickPendingIntent(R.id.hit_play, button(c, "toggle", 22));
        v.setOnClickPendingIntent(R.id.hit_next, button(c, "next", 23));
        progress(v, g, i, pl);
        m.updateAppWidget(id, v);
        geos.put(id, g);
        drawn.put(id, key(i, pl));
    }

    private static void hit(RemoteViews v, int id, float x, float y, float size) {
        int P = TypedValue.COMPLEX_UNIT_PX;
        v.setViewLayoutWidth(id, size, P);
        v.setViewLayoutHeight(id, size, P);
        v.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, x - size / 2, P);
        v.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, y - size / 2, P);
    }

    private static PendingIntent button(Context c, String a, int code) {
        Intent i = new Intent(c, PlaybackService.class).setAction(ACTION + a);
        return PendingIntent.getForegroundService(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The moving parts: the ring, the lit part of the range, its two stars, and the times. */
    private static void progress(RemoteViews v, Geo g, JSONObject i, boolean pl) {
        double dur = i == null ? 0 : i.optDouble("duration", 0), pos = position(i, pl);
        float p = dur > 0 ? (float) Math.min(1, pos / dur) : 0;
        v.setProgressBar(R.id.ring, 1000, Math.round(p * 1000), false);
        long now = SystemClock.elapsedRealtime();
        v.setChronometer(R.id.elapsed, now - (long) (pos * 1000), null, pl);
        v.setChronometer(R.id.remain, now + (long) ((dur - pos) * 1000), "−%s", pl);
        v.setChronometerCountDown(R.id.remain, true);
        v.setViewVisibility(R.id.remain, dur > 0 ? View.VISIBLE : View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= 31) {
            int P = TypedValue.COMPLEX_UNIT_PX;
            float x = p * g.w, hx = Math.max(g.headW / 2f, Math.min(g.w - g.headW / 2f, x)); // the star stays inside
            v.setViewLayoutWidth(R.id.lit_clip, x, P);
            v.setViewLayoutMargin(R.id.head, RemoteViews.MARGIN_LEFT, hx - g.headW / 2f, P);
            v.setViewLayoutMargin(R.id.head, RemoteViews.MARGIN_TOP, g.ridgeAt(hx) - 30 * g.s - g.headW / 2f, P);
            double a = -Math.PI / 2 + p * 2 * Math.PI;
            v.setViewLayoutMargin(R.id.ring_star, RemoteViews.MARGIN_LEFT, (float) (g.cx + g.orbit * Math.cos(a)) - g.starW / 2f, P);
            v.setViewLayoutMargin(R.id.ring_star, RemoteViews.MARGIN_TOP, (float) (g.cy + g.orbit * Math.sin(a)) - g.starW / 2f, P);
        }
    }

    // ---------- state ----------

    private static JSONObject current(Context c) {
        return info != null ? info : saved(c);
    }

    private static double position(JSONObject i, boolean pl) {
        if (i == null) return 0;
        double pos = i.optDouble("position", 0);
        if (pl && info == i) pos += (SystemClock.elapsedRealtime() - infoAt) / 1000.0 * i.optDouble("rate", 1);
        double dur = i.optDouble("duration", 0);
        return dur > 0 ? Math.max(0, Math.min(dur, pos)) : Math.max(0, pos);
    }

    /** What the full picture shows: when this changes, everything is drawn again. */
    private static String key(JSONObject i, boolean pl) {
        if (i == null) return "none";
        JSONArray w = i.optJSONArray("wave");
        return i.optString("title") + '\n' + i.optString("artist") + '\n' + i.optString("cover") + '\n' + (w == null ? 0 : w.toString().hashCode()) + '\n' + pl;
    }

    private static void save(Context c, JSONObject i, boolean pl) {
        prefs(c).edit().putString("last", i.toString()).apply();
    }

    private static JSONObject saved(Context c) {
        String s = prefs(c).getString("last", null);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (JSONException e) { return null; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widget", Context.MODE_PRIVATE);
    }

    /** The song's waveform (48 peaks from the page) or, until it is decoded, one made up from its title. */
    private static float[] wave(JSONObject i) {
        JSONArray a = i == null ? null : i.optJSONArray("wave");
        int n = 48;
        float[] w = new float[n];
        if (a != null && a.length() > 1) {
            for (int k = 0; k < n; k++) w[k] = (float) a.optDouble(k * (a.length() - 1) / (n - 1), 0.3);
            return w;
        }
        Random r = new Random(i == null ? 77 : (i.optString("title") + i.optString("artist")).hashCode());
        float v = 0.5f;
        for (int k = 0; k < n; k++) { v = Math.max(0.15f, Math.min(1, v + (r.nextFloat() - 0.5f) * 0.55f)); w[k] = v; }
        return w;
    }

    // ---------- geometry ----------

    private static final class Geo {
        final int w, h;
        final float sx, sy, s, cx, cy, r, orbit, by, ridgeTop;
        float[] rx, ry;
        int starW, headW;

        Geo(Context c, AppWidgetManager m, int id) {
            float[] dp = sizeDp(m, id);
            float d = c.getResources().getDisplayMetrics().density;
            w = Math.max(200, Math.round(dp[0] * d));
            h = Math.max(160, Math.round(dp[1] * d));
            sx = w / DW; sy = h / DH; s = Math.min(sx, sy);
            r = 160 * s; orbit = 196 * s;
            cx = Math.max(236 * sx, orbit + 24 * s); cy = 262 * sy;
            by = 412 * sy;
            ridgeTop = 560 * sy;
        }

        boolean sameSize(Context c, AppWidgetManager m, int id) {
            float[] dp = sizeDp(m, id);
            float d = c.getResources().getDisplayMetrics().density;
            return Math.max(200, Math.round(dp[0] * d)) == w && Math.max(160, Math.round(dp[1] * d)) == h;
        }

        /** The widget's size in portrait (the launcher reports width from the smaller and height from the taller). */
        static float[] sizeDp(AppWidgetManager m, int id) {
            Bundle o = m.getAppWidgetOptions(id);
            int wd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), hd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            if (wd <= 0 || hd <= 0) { wd = 353; hd = 269; }
            return new float[] { wd, hd };
        }

        /**
         * The range: peaks from the waveform (its loud and quiet parts spread apart), every other point dropped
         * into a valley; heights and spacing vary a little (the same way for the same song), so it reads as
         * mountains rather than a comb. It rises out of the ground at both ends (there is no card to end on).
         */
        void ridge(float[] wave, long seed) {
            int n = wave.length;
            Random r = new Random(seed);
            float lo = 1, hi = 0;
            for (float v : wave) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            float span = h - ridgeTop - 40 * sy, step = (float) w / (n - 1);
            rx = new float[n]; ry = new float[n];
            for (int k = 0; k < n; k++) {
                float v = hi - lo > 0.05f ? 0.3f + 0.7f * (wave[k] - lo) / (hi - lo) : wave[k];
                float peak = k % 2 == 1 ? v * (0.8f + r.nextFloat() * 0.2f) : v * (0.35f + r.nextFloat() * 0.4f);
                rx[k] = k == 0 || k == n - 1 ? k * step : (k + (r.nextFloat() - 0.5f) * 0.6f) * step;
                float t = rx[k] / w, e = smooth(Math.min(t, 1 - t) / 0.12f);
                ry[k] = h - e * (h - (ridgeTop + (1 - peak) * span));
            }
        }

        private static float smooth(float x) {
            x = Math.max(0, Math.min(1, x));
            return x * x * (3 - 2 * x);
        }

        float ridgeAt(float x) {
            for (int k = 1; k < rx.length; k++) if (x <= rx[k]) {
                float t = (x - rx[k - 1]) / Math.max(1, rx[k] - rx[k - 1]);
                return ry[k - 1] + (ry[k] - ry[k - 1]) * t;
            }
            return ry[ry.length - 1];
        }

        Path ridgePath(float dy) {
            Path p = new Path();
            p.moveTo(0, h - dy);
            for (int k = 0; k < rx.length; k++) p.lineTo(rx[k], ry[k] - dy);
            p.lineTo(w, h - dy);
            p.close();
            return p;
        }
    }

    // ---------- drawing ----------

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(color);
        return p;
    }

    private static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    private static Bitmap background(Context c, Geo g, JSONObject i, boolean pl) {
        // no card: everything floats on the wallpaper, whose sky and stars show through
        Bitmap b = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        float s = g.s;

        // the orbit, dotted
        Paint orbit = paint(alpha(ICE, 0.16f));
        orbit.setStyle(Paint.Style.STROKE);
        orbit.setStrokeWidth(3 * s);
        orbit.setStrokeCap(Paint.Cap.ROUND);
        orbit.setPathEffect(new DashPathEffect(new float[] { 2 * s, 12 * s }, 0));
        k.drawCircle(g.cx, g.cy, g.orbit, orbit);

        moon(c, k, g, i);

        // text: NOW PLAYING, the title (one line, or two smaller), the artist
        float tx = 470 * g.sx, tw = g.w - tx - 44 * g.sx;
        TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        label.setTypeface(font(c, R.font.michroma));
        label.setTextSize(27 * s);
        label.setLetterSpacing(0.26f);
        label.setColor(DIM);
        float y = 92 * g.sy - label.ascent();
        k.drawText(pl ? "NOW PLAYING" : "PAUSED", tx, y, label);
        y += label.descent() + 26 * s;

        String title = i == null ? "YouSic" : i.optString("title", "");
        String artist = i == null ? "Tap play to start" : i.optString("artist", "");
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(font(c, R.font.manrope_bold));
        tp.setColor(STAR);
        tp.setTextSize(56 * s);
        int lines = 1;
        if (tp.measureText(title) > tw) { tp.setTextSize(46 * s); lines = 2; }
        StaticLayout tl = StaticLayout.Builder.obtain(title, 0, title.length(), tp, (int) tw)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.02f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        k.save();
        k.translate(tx, y);
        tl.draw(k);
        k.restore();
        y += tl.getHeight() + 8 * s;

        TextPaint ap = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        ap.setTypeface(font(c, R.font.manrope_medium));
        ap.setColor(DIM);
        ap.setTextSize(36 * s);
        k.drawText(TextUtils.ellipsize(artist, ap, tw, TextUtils.TruncateAt.END).toString(), tx, y - ap.ascent(), ap);

        controls(k, g, pl);

        // the song's range, dark, fading into the sky at the bottom (the lit part is the "lit" layer above it)
        Paint range = paint(SLOPE);
        float top = minY(g.ry);
        range.setShader(new LinearGradient(0, top, 0, g.h, new int[] { SLOPE, alpha(SLOPE, 0.85f), alpha(SLOPE, 0) }, new float[] { 0, 0.55f, 1 }, Shader.TileMode.CLAMP));
        k.drawPath(g.ridgePath(0), range);
        return b;
    }

    /** The cover, lit like the moon (or, with no cover, an original night sleeve). */
    private static void moon(Context c, Canvas k, Geo g, JSONObject i) {
        float cx = g.cx, cy = g.cy, R = g.r;
        Path clip = new Path();
        clip.addCircle(cx, cy, R, Path.Direction.CW);
        k.save();
        k.clipPath(clip);
        Bitmap art = i == null ? null : cover(c, i.optString("cover", ""), Math.round(2 * R));
        RectF box = new RectF(cx - R, cy - R, cx + R, cy + R);
        if (art != null) {
            int aw = art.getWidth(), ah = art.getHeight(), side = Math.min(aw, ah);
            Rect src = new Rect((aw - side) / 2, (ah - side) / 2, (aw + side) / 2, (ah + side) / 2);
            k.drawBitmap(art, src, box, paint(0xFFFFFFFF));
        } else {
            Paint sky = paint(0xFF000000);
            sky.setShader(new LinearGradient(0, cy - R, 0, cy + R, new int[] { 0xFF1D2C55, 0xFF6A5A9C, 0xFFE39A86, 0xFFF6D3A6 }, new float[] { 0, 0.5f, 0.78f, 1 }, Shader.TileMode.CLAMP));
            k.drawRect(box, sky);
            k.drawCircle(cx + 0.11f * R, cy + 0.25f * R, 0.39f * R, paint(0xEBFFE7C4));
            Path hills = new Path();
            hills.moveTo(cx - R, cy + 0.75f * R);
            hills.lineTo(cx - 0.44f * R, cy + 0.33f * R);
            hills.lineTo(cx - 0.06f * R, cy + 0.63f * R);
            hills.lineTo(cx + 0.38f * R, cy + 0.25f * R);
            hills.lineTo(cx + R, cy + 0.69f * R);
            hills.lineTo(cx + R, cy + R);
            hills.lineTo(cx - R, cy + R);
            hills.close();
            k.drawPath(hills, paint(0xFF141A33));
        }
        Paint shade = paint(0xFF000000);
        shade.setShader(new RadialGradient(cx - 0.3f * R, cy - 0.3f * R, 1.5f * R, new int[] { 0x00000000, 0x00000000, 0x9E000000 }, new float[] { 0, 0.45f, 1 }, Shader.TileMode.CLAMP));
        k.drawRect(box, shade);
        k.restore();
        Paint rim = paint(alpha(ICE_HI, 0.75f));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(3 * g.s);
        k.drawArc(box, 135, 90, false, rim);
    }

    private static void controls(Canvas k, Geo g, boolean pl) {
        float s = g.s, y = g.by;
        Paint glyph = paint(STAR);
        glyph.setStrokeWidth(6 * s);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStyle(Paint.Style.FILL_AND_STROKE);
        for (int dir : new int[] { -1, 1 }) { // previous (points left), next (points right)
            float x = (dir < 0 ? 543 : 809) * g.sx, tip = x + dir * 13 * s, back = x - dir * 23 * s;
            Path t = new Path();
            t.moveTo(back, y - 24 * s);
            t.lineTo(tip, y);
            t.lineTo(back, y + 24 * s);
            t.close();
            k.drawPath(t, glyph);
            Paint bar = paint(STAR);
            bar.setStrokeWidth(7 * s);
            bar.setStrokeCap(Paint.Cap.ROUND);
            float bx = x + dir * 23 * s;
            k.drawLine(bx, y - 22 * s, bx, y + 22 * s, bar);
        }
        float px = 676 * g.sx;
        Paint ring = paint(alpha(ICE, 0.35f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * s);
        k.drawCircle(px, y, 88 * s, ring);
        k.drawCircle(px, y, 72 * s, paint(STAR));
        Paint ink = paint(INK);
        if (pl) {
            k.drawRoundRect(px - 24 * s, y - 26 * s, px - 9 * s, y + 26 * s, 5 * s, 5 * s, ink);
            k.drawRoundRect(px + 9 * s, y - 26 * s, px + 24 * s, y + 26 * s, 5 * s, 5 * s, ink);
        } else {
            Path t = new Path();
            t.moveTo(px - 18 * s, y - 30 * s);
            t.lineTo(px + 32 * s, y);
            t.lineTo(px - 18 * s, y + 30 * s);
            t.close();
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(6 * s);
            ink.setStyle(Paint.Style.FILL_AND_STROKE);
            k.drawPath(t, ink);
        }
    }

    /** The range where it has played: lit like the wallpaper's mist, clipped to the played width. */
    private static Bitmap lit(Geo g) {
        float top = minY(g.ry) - 2;
        Bitmap b = Bitmap.createBitmap(g.w, Math.max(1, Math.round(g.h - top)), Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        Paint p = paint(ICE);
        p.setShader(new LinearGradient(0, 0, 0, g.h - top, new int[] { ICE, MIST, alpha(HAZE, 0.6f), alpha(HAZE, 0) }, new float[] { 0, 0.3f, 0.7f, 1 }, Shader.TileMode.CLAMP));
        k.drawPath(g.ridgePath(top), p);
        return b;
    }

    /** A star: a dot with a soft glow and a four-point glint. */
    private static Bitmap star(float r) {
        int size = Math.round(r * 9) | 1;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawStar(new Canvas(b), size / 2f, size / 2f, r);
        return b;
    }

    private static void drawStar(Canvas k, float x, float y, float r) {
        Paint glow = paint(ICE_HI);
        glow.setShader(new RadialGradient(x, y, r * 4.2f, alpha(ICE_HI, 0.5f), alpha(ICE_HI, 0f), Shader.TileMode.CLAMP));
        k.drawCircle(x, y, r * 4.2f, glow);
        Path g = new Path();
        float a = r * 4.4f, n = r * 0.32f;
        g.moveTo(x, y - a); g.lineTo(x + n, y); g.lineTo(x, y + a); g.lineTo(x - n, y); g.close();
        g.moveTo(x - a, y); g.lineTo(x, y - n); g.lineTo(x + a, y); g.lineTo(x, y + n); g.close();
        Paint glint = paint(ICE_HI);
        glint.setAlpha(217);
        k.drawPath(g, glint);
        k.drawCircle(x, y, r, paint(ICE_HI));
    }

    /** The playhead: a star on the ridge with a line down to the bottom. */
    private static Bitmap head(Geo g) {
        float r = 7 * g.s;
        int w = Math.round(r * 9) | 1;
        int h = Math.round(g.h - minY(g.ry) + 40 * g.s + w);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b);
        Paint line = paint(ICE_HI);
        line.setStrokeWidth(3 * g.s);
        line.setShader(new LinearGradient(0, w / 2f, 0, h, ICE_HI, alpha(ICE_HI, 0), Shader.TileMode.CLAMP));
        k.drawLine(w / 2f, w / 2f, w / 2f, h, line);
        drawStar(k, w / 2f, w / 2f, r);
        return b;
    }

    private static float minY(float[] ys) {
        float m = Float.MAX_VALUE;
        for (float v : ys) m = Math.min(m, v);
        return m;
    }

    private static final Map<Integer, Typeface> fonts = new HashMap<>();

    private static Typeface font(Context c, int res) {
        Typeface t = fonts.get(res);
        if (t == null) {
            try { t = c.getResources().getFont(res); } catch (Exception e) { t = Typeface.DEFAULT_BOLD; }
            fonts.put(res, t);
        }
        return t;
    }

    /** The song's cover, as the app keeps it (files/covers/<key>.jpg), decoded near the size it is drawn. */
    private static Bitmap cover(Context c, String key, int size) {
        if (key.isEmpty() || key.equals("null")) return null;
        File f = new File(new File(c.getFilesDir(), "covers"), key + ".jpg");
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
