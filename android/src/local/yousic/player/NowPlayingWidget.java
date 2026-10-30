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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The home-screen widget ("Moon Player", 5 x 3 cells), styled after a starry-night wallpaper and drawn with
 * no background: the cover is a moon with the song's progress orbiting it, and a thin bar along the bottom
 * shows it too (tap the bar to jump there). Prev, play/pause and next control the app (PlaybackService; with
 * the app closed, it starts the page in the background); a tap anywhere else opens the app.
 *
 * Drawn as a bitmap once per song, play state and size. While a song plays only the progress moves: the
 * ring, the bar and their stars are updated every few seconds while the screen is on, and the times are
 * chronometers that tick by themselves.
 */
public class NowPlayingWidget extends AppWidgetProvider {
    /** PlaybackService action prefix for the buttons: "widget:prevcat", "widget:prev", "widget:toggle", "widget:next",
     *  "widget:nextcat", "widget:seekpct:0.5". */
    static final String ACTION = "widget:";
    /** A tap on the bar: that fraction of the song ("seekpct:0.3125"). */
    static final String SEEK = "seekpct:";
    /** The bar can't be dragged (widgets only get taps): it is split into this many tap-to-seek zones. */
    private static final int ZONES = 24;

    // the wallpaper's palette
    private static final int STAR = 0xFFF2F3F8, DIM = 0xFFA3A8BC, ICE = 0xFF8CB5D3, ICE_HI = 0xFFC4DDF0, INK = 0xFF0A0A16;
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
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_player);
        v.setImageViewBitmap(R.id.bg, background(c, g, i, pl));
        Bitmap star = star(8 * g.s);
        g.starW = star.getWidth();
        v.setImageViewBitmap(R.id.ring_star, star);
        Bitmap knob = star(5 * g.s);
        g.knobW = knob.getWidth();
        v.setImageViewBitmap(R.id.bar_star, knob);

        float px = TypedValue.COMPLEX_UNIT_PX;
        v.setTextViewTextSize(R.id.elapsed, (int) px, 28 * g.s);
        v.setTextViewTextSize(R.id.remain, (int) px, 28 * g.s);
        if (Build.VERSION.SDK_INT >= 31) {
            int P = TypedValue.COMPLEX_UNIT_PX;
            float ring = 399 * g.s; // the ring drawable's ratios are for a 196 radius, 5 thick ring in 399
            v.setViewLayoutWidth(R.id.ring, ring, P);
            v.setViewLayoutHeight(R.id.ring, ring, P);
            v.setViewLayoutMargin(R.id.ring, RemoteViews.MARGIN_LEFT, g.cx - ring / 2, P);
            v.setViewLayoutMargin(R.id.ring, RemoteViews.MARGIN_TOP, g.cy - ring / 2, P);
            float thick = 7 * g.s, zone = 84 * g.s;
            v.setViewLayoutWidth(R.id.bar, g.barW, P);
            v.setViewLayoutHeight(R.id.bar, thick, P);
            v.setViewLayoutMargin(R.id.bar, RemoteViews.MARGIN_LEFT, g.barL, P);
            v.setViewLayoutMargin(R.id.bar, RemoteViews.MARGIN_TOP, g.barY - thick / 2, P);
            v.setViewLayoutWidth(R.id.seek_zones, g.barW, P);
            v.setViewLayoutHeight(R.id.seek_zones, zone, P);
            v.setViewLayoutMargin(R.id.seek_zones, RemoteViews.MARGIN_LEFT, g.barL, P);
            v.setViewLayoutMargin(R.id.seek_zones, RemoteViews.MARGIN_TOP, g.barY - zone / 2, P);
            v.setViewLayoutMargin(R.id.elapsed, RemoteViews.MARGIN_LEFT, g.barL, P);
            v.setViewLayoutMargin(R.id.elapsed, RemoteViews.MARGIN_TOP, g.timesY, P);
            v.setViewLayoutMargin(R.id.remain, RemoteViews.MARGIN_RIGHT, g.w - g.barL - g.barW, P);
            v.setViewLayoutMargin(R.id.remain, RemoteViews.MARGIN_TOP, g.timesY, P);
            hit(v, R.id.hit_prevcat, X_PREV_CAT * g.sx, g.by, 84 * g.s);
            hit(v, R.id.hit_prev, X_PREV * g.sx, g.by, 90 * g.s);
            hit(v, R.id.hit_play, X_PLAY * g.sx, g.by, 124 * g.s);
            hit(v, R.id.hit_next, X_NEXT * g.sx, g.by, 90 * g.s);
            hit(v, R.id.hit_nextcat, X_NEXT_CAT * g.sx, g.by, 84 * g.s);
        } else {
            // no way to place them: the stars that move go
            v.setViewVisibility(R.id.ring_star, View.GONE);
            v.setViewVisibility(R.id.bar_star, View.GONE);
        }
        // the bar's tap-to-seek zones
        v.removeAllViews(R.id.seek_zones);
        for (int z = 0; z < ZONES; z++) {
            RemoteViews zone = new RemoteViews(c.getPackageName(), R.layout.widget_seek_zone);
            zone.setOnClickPendingIntent(R.id.zone, button(c, SEEK + (z + 0.5) / ZONES, 100 + z));
            v.addView(R.id.seek_zones, zone);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setOnClickPendingIntent(R.id.hit_prevcat, button(c, "prevcat", 24));
        v.setOnClickPendingIntent(R.id.hit_prev, button(c, "prev", 21));
        v.setOnClickPendingIntent(R.id.hit_play, button(c, "toggle", 22));
        v.setOnClickPendingIntent(R.id.hit_next, button(c, "next", 23));
        v.setOnClickPendingIntent(R.id.hit_nextcat, button(c, "nextcat", 25));
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

    /** The moving parts: the ring and the bar, their stars, and the times. */
    private static void progress(RemoteViews v, Geo g, JSONObject i, boolean pl) {
        double dur = i == null ? 0 : i.optDouble("duration", 0), pos = position(i, pl);
        float p = dur > 0 ? (float) Math.min(1, pos / dur) : 0;
        v.setProgressBar(R.id.ring, 1000, Math.round(p * 1000), false);
        v.setProgressBar(R.id.bar, 1000, Math.round(p * 1000), false);
        long now = SystemClock.elapsedRealtime();
        v.setChronometer(R.id.elapsed, now - (long) (pos * 1000), null, pl);
        v.setChronometer(R.id.remain, now + (long) ((dur - pos) * 1000), "−%s", pl);
        v.setChronometerCountDown(R.id.remain, true);
        v.setViewVisibility(R.id.remain, dur > 0 ? View.VISIBLE : View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= 31) {
            int P = TypedValue.COMPLEX_UNIT_PX;
            double a = -Math.PI / 2 + p * 2 * Math.PI;
            v.setViewLayoutMargin(R.id.ring_star, RemoteViews.MARGIN_LEFT, (float) (g.cx + g.orbit * Math.cos(a)) - g.starW / 2f, P);
            v.setViewLayoutMargin(R.id.ring_star, RemoteViews.MARGIN_TOP, (float) (g.cy + g.orbit * Math.sin(a)) - g.starW / 2f, P);
            v.setViewLayoutMargin(R.id.bar_star, RemoteViews.MARGIN_LEFT, g.barL + p * g.barW - g.knobW / 2f, P);
            v.setViewLayoutMargin(R.id.bar_star, RemoteViews.MARGIN_TOP, g.barY - g.knobW / 2f, P);
        }
    }

    /** The length of the song the widget shows, in seconds (0 if unknown). */
    static double duration(Context c) {
        JSONObject i = current(c);
        return i == null ? 0 : i.optDouble("duration", 0);
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
        return i.optString("title") + '\n' + i.optString("artist") + '\n' + i.optString("cover") + '\n' + pl;
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

    // ---------- geometry ----------

    private static final class Geo {
        final int w, h;
        /** oy: how far everything moves down to sit in the middle of the widget; title: the title's top;
         *  by: the buttons' centre line; bar: the progress bar's left end, width and centre line */
        final float sx, sy, s, oy, cx, cy, r, orbit, title, by, barL, barW, barY, timesY;
        int starW, knobW;

        Geo(Context c, AppWidgetManager m, int id) {
            float[] dp = sizeDp(m, id);
            float d = c.getResources().getDisplayMetrics().density;
            w = Math.max(200, Math.round(dp[0] * d));
            h = Math.max(160, Math.round(dp[1] * d));
            sx = w / DW; sy = h / DH; s = Math.min(sx, sy);
            r = 160 * s; orbit = 196 * s;
            oy = 60 * sy; // the design's content runs from 30 (the ring's star) to 600 (the times) of 756
            cx = Math.max(236 * sx, orbit + 24 * s); cy = 262 * sy + oy;
            title = 115 * sy + oy; // title, artist and buttons centred beside the moon
            by = 335 * sy + oy;
            barL = 64 * sx; barW = (DW - 128) * sx; barY = 540 * sy + oy;
            timesY = 562 * sy + oy;
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

        // text: the title (one line, or two smaller), the artist
        float tx = 470 * g.sx, tw = g.w - tx - 44 * g.sx, y = g.title;
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

    // the buttons' centres on the design grid: previous category, previous, play / pause, next, next category
    private static final float X_PREV_CAT = 504, X_PREV = 594, X_PLAY = 700, X_NEXT = 806, X_NEXT_CAT = 896;

    private static void controls(Canvas k, Geo g, boolean pl) {
        float s = g.s, y = g.by;
        Paint glyph = paint(STAR);
        glyph.setStrokeWidth(4.5f * s);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStyle(Paint.Style.FILL_AND_STROKE);
        Paint bar = paint(STAR);
        bar.setStrokeWidth(5.5f * s);
        bar.setStrokeCap(Paint.Cap.ROUND);
        Paint list = paint(alpha(STAR, 0.8f));
        list.setStyle(Paint.Style.STROKE);
        list.setStrokeWidth(4 * s);
        list.setStrokeCap(Paint.Cap.ROUND);
        list.setStrokeJoin(Paint.Join.ROUND);
        for (int dir : new int[] { -1, 1 }) { // the previous ones point left, the next ones right
            // previous / next song: a triangle against a bar
            float x = (dir < 0 ? X_PREV : X_NEXT) * g.sx, tip = x + dir * 10 * s, back = x - dir * 17 * s;
            Path t = new Path();
            t.moveTo(back, y - 18 * s);
            t.lineTo(tip, y);
            t.lineTo(back, y + 18 * s);
            t.close();
            k.drawPath(t, glyph);
            float bx = x + dir * 17 * s;
            k.drawLine(bx, y - 16 * s, bx, y + 16 * s, bar);
            // previous / next category: a list with a chevron on its outer side
            float cx = (dir < 0 ? X_PREV_CAT : X_NEXT_CAT) * g.sx, l0 = cx - dir * 18 * s, l1 = cx + dir * 2 * s;
            for (int row = -1; row <= 1; row++) k.drawLine(l0, y + row * 11 * s, l1, y + row * 11 * s, list);
            Path ch = new Path();
            ch.moveTo(cx + dir * 10 * s, y - 11 * s);
            ch.lineTo(cx + dir * 20 * s, y);
            ch.lineTo(cx + dir * 10 * s, y + 11 * s);
            k.drawPath(ch, list);
        }
        float px = X_PLAY * g.sx;
        Paint ring = paint(alpha(ICE, 0.35f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * s);
        k.drawCircle(px, y, 62 * s, ring);
        k.drawCircle(px, y, 50 * s, paint(STAR));
        Paint ink = paint(INK);
        if (pl) {
            k.drawRoundRect(px - 17 * s, y - 19 * s, px - 6 * s, y + 19 * s, 4 * s, 4 * s, ink);
            k.drawRoundRect(px + 6 * s, y - 19 * s, px + 17 * s, y + 19 * s, 4 * s, 4 * s, ink);
        } else {
            Path t = new Path();
            t.moveTo(px - 13 * s, y - 22 * s);
            t.lineTo(px + 23 * s, y);
            t.lineTo(px - 13 * s, y + 22 * s);
            t.close();
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(4.5f * s);
            ink.setStyle(Paint.Style.FILL_AND_STROKE);
            k.drawPath(t, ink);
        }
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
