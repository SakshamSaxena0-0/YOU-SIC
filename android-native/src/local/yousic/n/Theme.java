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
    /** the song's title in capitals; a glow round titles (0: none) */
    public boolean titleCaps;
    public int titleGlow;
    /** a glow round the play / pause glyph (0: none) */
    protected int playGlyphGlow;

    /** the interface's scale: every size in the app is in these "dp" (Android's, a little smaller) */
    public static final float UI_SCALE = 0.9f;

    protected Theme(Context c) { dp = c.getResources().getDisplayMetrics().density * UI_SCALE; }

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

    // ---------- controls: chips, round buttons, the bar at the bottom, popups, the visualizer ----------

    protected final Paint ctl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    /** A pill behind a button or a label (on: the button is switched on). */
    public void drawChip(Canvas c, RectF r, float radius, boolean pressed, boolean on) {
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(on ? alpha(accent, pressed ? 0.36f : 0.24f) : alpha(ink, pressed ? 0.74f : 0.5f));
        c.drawRoundRect(r, radius, radius, ctl);
    }

    /** A round transport button; small: the category steps. */
    public void drawRound(Canvas c, float cx, float cy, float r, boolean pressed, boolean small) {
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(alpha(ink, small ? (pressed ? 0.55f : 0.32f) : (pressed ? 0.85f : 0.62f)));
        c.drawCircle(cx, cy, r, ctl);
    }

    /** Icon colour: on (switched on), dim (secondary). */
    public int icon(boolean on, boolean dim) { return on ? accentHi : dim ? alpha(text, 0.55f) : text; }

    /** A glow round icons (0: none). */
    public int iconGlow(boolean on) { return 0; }

    /** A list row that is pressed, selected or playing. */
    public void drawRowHighlight(Canvas c, RectF r, float radius, boolean pressed, boolean selected, boolean playing) {
        drawChip(c, r, radius, pressed, selected);
    }

    /** The navigation bar at the bottom. */
    public void drawNavBar(Canvas c, RectF r, float radius) {
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(alpha(ink, 0.86f));
        c.drawRoundRect(r, radius, radius, ctl);
    }

    /** A popup menu or dialog. */
    public void drawSheet(Canvas c, RectF r, float radius) {
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(panelHi);
        c.drawRoundRect(r, radius, radius, ctl);
    }

    /** Behind a popup: the screen dims (a = 0..1). */
    public void drawScrim(Canvas c, int w, int h, float a) { c.drawColor(alpha(0xFF000000, 0.5f * a)); }

    /** The visualizer: n bands 0..1, bars rising from the bottom of r. */
    public void drawVis(Canvas c, RectF r, float[] v, int n, float alpha) {
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(alpha(accent, 0.35f * alpha));
        float step = r.width() / n, bw = step * 0.62f;
        for (int i = 0; i < n; i++) {
            float x = r.left + i * step + (step - bw) / 2, h = Math.max(bw, v[i] * r.height());
            tmp.set(x, r.bottom - h, x + bw, r.bottom);
            c.drawRoundRect(tmp, bw / 2, bw / 2, ctl);
        }
    }

    /** The light round the cover (0 = none). While the cover turns over it is drawn by the pager, behind it, and
     *  the card itself is drawn without it (glowScale 0) into a GPU layer that just turns. */
    public float glowScale = 1f;
    public void drawGlow(Canvas c, RectF r, float strength) {}
    /** the glow's strength round the cover's front (playing 0..1) and its back */
    public float glowFront(float playing) { return 0; }
    public float glowBack() { return 0; }

    /** The back of the cover (tap the art to turn it over): a card the song's details are written on. */
    public void drawCardBack(Canvas c, RectF r, float radius) { drawSheet(c, r, radius); }

    /** A switch (for menus and dialogs). */
    public void drawSwitch(Canvas c, float cx, float cy, float on) {
        float w = px(36), h = px(20);
        ctl.setStyle(Paint.Style.FILL);
        ctl.setColor(mix(accent, alpha(text, 0.25f), on));
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        c.drawRoundRect(tmp, h / 2, h / 2, ctl);
        ctl.setColor(on > 0.5f ? ink : text);
        c.drawCircle(cx - w / 2 + h / 2 + (w - h) * on, cy, h / 2 - px(3), ctl);
    }

    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path glyph = new android.graphics.Path();

    /** play (morph 0) to pause (morph 1): each half of the triangle becomes a bar */
    public void drawPlayGlyph(Canvas c, float cx, float cy, float s, float t, int color) {
        glyphPaint.setColor(color);
        if (playGlyphGlow != 0) glyphPaint.setShadowLayer(px(6), 0, 0, playGlyphGlow); else glyphPaint.clearShadowLayer();
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

    /** YouSic's skins, in its order. */
    public static final String[] NAMES = { "Liquid Glass", "Cyberpunk", "Y2K Subway", "Pixel", "Vector Surrealism", "Liminal", "Midnight" };

    /** This skin's name. */
    public abstract String name();

    /** whether a skin has been ported to the native app yet */
    public static boolean ready(String name) { return name.equals("Liminal") || name.equals("Y2K Subway"); }

    private static Theme current;

    private static Theme create(Context c, String name) {
        if ("Y2K Subway".equals(name)) return new Y2KTheme(c);
        return new LiminalTheme(c);
    }

    public static Theme get(Context c) {
        if (current == null) {
            Context app = c.getApplicationContext();
            current = create(app, app.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("skin", "Liminal"));
        }
        return current;
    }

    /** Switches skin (the screens are then built again). */
    public static void use(Context c, String name) {
        Context app = c.getApplicationContext();
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("skin", name).apply();
        current = create(app, name);
    }
}
