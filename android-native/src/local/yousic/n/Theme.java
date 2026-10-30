package local.yousic.n;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * A look: colours, type, and how the backdrop, cover, play button, seek bar and list rows are drawn. Every
 * screen draws through the current theme, so a theme is one class. Sizes are in dp (the YouSic themes'
 * CSS px are dp on the phone).
 */
public abstract class Theme {
    public final float dp;
    // ---- colours ----
    public int bg, text, textDim, accent, accentHi, accent2, line, panel, panelHi, ink, stamp;
    // ---- type ----
    public Typeface titleFace, uiFace, uiMedium, uiBold, numFace, stampFace;
    public boolean titleItalic;
    public float titleSize = 34, artistSize = 15, albumSize = 13, timeSize = 11, rowTitle = 15, rowSub = 12.5f, headerSize = 26;
    /** the cover's corner radius */
    public float coverRadius = 14;

    protected Theme(Context c) { dp = c.getResources().getDisplayMetrics().density; }

    public float px(float dps) { return dps * dp; }

    /** The backdrop, painted once per size into a bitmap: the glow sits at (gx, gy). */
    public abstract void paintBackdrop(Canvas c, int w, int h, float gx, float gy);

    /** Drawn over everything (a film edge, grain...). Called each frame the overlay is redrawn. */
    public void drawOverlay(Canvas c, int w, int h, int grainStep) {}
    /** How often the overlay changes by itself (ms; 0 = static). */
    public int overlayStepMs() { return 0; }

    /** The library's panel over the backdrop. */
    public abstract void drawPanel(Canvas c, int w, int h);

    /** The cover (or its placeholder) with the theme's frame and light. */
    public abstract void drawCover(Canvas c, RectF r, Bitmap art, float playing, Paint p);

    /** The play button: morph 0 = play triangle, 1 = pause bars; playing 0..1 drives the halo. */
    public abstract void drawPlay(Canvas c, float cx, float cy, float size, float morph, float playing, float progress);

    /** The seek bar: peaks 0..1, progress 0..1. */
    public abstract void drawSeek(Canvas c, RectF r, float[] peaks, float progress, boolean dragging);

    /** A small round button (prev / next / shuffle / repeat...) glyph colour when on / off. */
    public int glyph(boolean on) { return on ? accentHi : text; }

    /** Behind the title block, so text reads over the glow. */
    public void drawInfoShade(Canvas c, RectF r) {}

    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path glyph = new android.graphics.Path();

    /** play (morph 0) to pause (morph 1): each half of the triangle becomes a bar */
    public void drawPlayGlyph(Canvas c, float cx, float cy, float s, float t, int color) {
        glyphPaint.setColor(color);
        float h = s * 0.82f, bw = s * 0.27f, gap = s * 0.2f;
        float top = cy - h / 2, bot = cy + h / 2;
        // the triangle's left half (points to the right): left edge at x0, tip at x0 + w
        float w = h * 0.9f, x0 = cx - w * 0.42f;
        glyph.reset();
        // left piece: triangle part [x0..x0+w/2] -> bar [cx-gap/2-bw .. cx-gap/2]
        float l0 = lerp(x0, cx - gap / 2 - bw, t), l1 = lerp(x0 + w / 2, cx - gap / 2, t);
        float lTop1 = lerp(cy - h / 4, top, t), lBot1 = lerp(cy + h / 4, bot, t);
        glyph.moveTo(l0, top); glyph.lineTo(l1, lTop1); glyph.lineTo(l1, lBot1); glyph.lineTo(l0, bot); glyph.close();
        // right piece: triangle part [x0+w/2 .. x0+w] -> bar [cx+gap/2 .. cx+gap/2+bw]
        float r0 = lerp(x0 + w / 2, cx + gap / 2, t), r1 = lerp(x0 + w, cx + gap / 2 + bw, t);
        float rTop0 = lerp(cy - h / 4, top, t), rBot0 = lerp(cy + h / 4, bot, t);
        float rTop1 = lerp(cy, top, t), rBot1 = lerp(cy, bot, t);
        glyph.moveTo(r0, rTop0); glyph.lineTo(r1, rTop1); glyph.lineTo(r1, rBot1); glyph.lineTo(r0, rBot0); glyph.close();
        c.drawPath(glyph, glyphPaint);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = a >>> 24;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = b >>> 24;
        return (Math.round(aa * t + ba * (1 - t)) << 24) | (Math.round(ar * t + br * (1 - t)) << 16)
                | (Math.round(ag * t + bg * (1 - t)) << 8) | Math.round(ab * t + bb * (1 - t));
    }

    /** color-mix(in srgb, c p%, transparent) */
    public static int alpha(int c, float p) { return (Math.round(255 * p) << 24) | (c & 0xFFFFFF); }

    private static Theme current;

    public static Theme get(Context c) {
        if (current == null) current = new LiminalTheme(c.getApplicationContext());
        return current;
    }
}
