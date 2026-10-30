package local.yousic.n;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Liminal: a vintage dream. A soft dusk gradient seen through old film: a warm sunset glow behind the
 * cover, light leaks, a vignette, jumping grain and the rounded edge of a film print; the play button is
 * the sun and the seek bar is sunset light on water. Ported from the YouSic theme's tokens
 * (renderer/css/liminal.tokens.css, liminal.css).
 */
public final class LiminalTheme extends Theme {
    // the palette: dusk, on faded film (the shadows never reach black)
    static final int INK = 0xFF241A26, DUSK = 0xFF3A2C40, DUSK_HI = 0xFF4A3A52, CREAM = 0xFFFFF2E2, HAZE = 0xFFD9C6DA,
            PEACH = 0xFFF7C59F, GOLD = 0xFFFFD98A, ROSE = 0xFFEBA6B3, SKY = 0xFF8E9CC8, STAMP = 0xFFFFA552;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), grainPaint = new Paint(), filmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap grain;
    private final Matrix m = new Matrix();
    private final Path path = new Path();
    private final ColorMatrixColorFilter faded;
    private Bitmap filmEdge;
    private int filmW, filmH;
    // grain jumps between four places, like real film (YouSic: steps(4) a second)
    private static final float[][] JUMP = { {0, 0}, {-37, 23}, {51, -12}, {-18, -44} };

    LiminalTheme(Context c) {
        super(c);
        bg = INK; ink = INK; text = CREAM; textDim = HAZE; accent = PEACH; accentHi = GOLD; accent2 = ROSE;
        line = alpha(CREAM, 0.18f); panel = DUSK; panelHi = DUSK_HI; stamp = STAMP;
        titleFace = c.getResources().getFont(R.font.cormorant_italic_semibold);
        uiFace = c.getResources().getFont(R.font.plex_regular);
        uiMedium = c.getResources().getFont(R.font.plex_medium);
        uiBold = c.getResources().getFont(R.font.plex_semibold);
        numFace = c.getResources().getFont(R.font.jetbrains_mono);
        stampFace = c.getResources().getFont(R.font.dseg7_italic);
        titleSize = 36; headerSize = 30;
        // grain: 200 dp of noise, tiled
        int g = Math.max(64, Math.round(px(200) / 2));
        grain = Bitmap.createBitmap(g, g, Bitmap.Config.ARGB_8888);
        int[] pxs = new int[g * g];
        Random r = new Random(7);
        for (int i = 0; i < pxs.length; i++) { int v = r.nextInt(256); pxs[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        grain.setPixels(pxs, 0, g, 0, 0, g, g);
        grainPaint.setShader(new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grainPaint.setBlendMode(BlendMode.OVERLAY);
        // the cover: a faded print (sepia 0.14, saturate 0.92, contrast 0.96)
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0.92f);
        ColorMatrix sep = new ColorMatrix(new float[]{
                0.86f + 0.14f * 0.393f, 0.14f * 0.769f, 0.14f * 0.189f, 0, 0,
                0.14f * 0.349f, 0.86f + 0.14f * 0.686f, 0.14f * 0.168f, 0, 0,
                0.14f * 0.272f, 0.14f * 0.534f, 0.86f + 0.14f * 0.131f, 0, 0,
                0, 0, 0, 1, 0 });
        cm.postConcat(sep);
        float k = 0.96f, o = 128 * (1 - k);
        cm.postConcat(new ColorMatrix(new float[]{ k, 0, 0, 0, o, 0, k, 0, 0, o, 0, 0, k, 0, o, 0, 0, 0, 1, 0 }));
        faded = new ColorMatrixColorFilter(cm);
    }

    // ---------- the backdrop: one soft gradient, a glow behind the cover, blooms, leaks, vignette ----------

    @Override
    public void paintBackdrop(Canvas c, int w, int h, float gx, float gy) {
        float vmax = Math.max(w, h) / 100f;
        c.drawColor(INK);
        // linear-gradient(172deg, ...): almost top to bottom, leaning slightly
        double a = Math.toRadians(172 - 90);
        float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
        float half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
        fill.setShader(new LinearGradient(w / 2f - dx * half, h / 2f - dy * half, w / 2f + dx * half, h / 2f + dy * half,
                new int[]{ mix(SKY, INK, 0.30f), mix(HAZE, DUSK, 0.22f), mix(ROSE, DUSK, 0.34f), mix(PEACH, DUSK, 0.34f) },
                new float[]{ 0, 0.42f, 0.74f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        ellipse(c, w * 0.02f, h * 0.78f, 50 * vmax, 40 * vmax, alpha(HAZE, 0.20f), 0.7f);
        ellipse(c, w * 0.96f, h * 0.96f, 55 * vmax, 45 * vmax, alpha(ROSE, 0.42f), 0.7f);
        ellipse(c, w * 0.92f, h * 0.04f, 60 * vmax, 45 * vmax, alpha(SKY, 0.42f), 0.7f);
        // the sunset glow behind the cover
        float r = 40 * vmax;
        fill.setShader(new RadialGradient(gx, gy, r, new int[]{ alpha(GOLD, 0.46f), alpha(PEACH, 0.22f), 0 },
                new float[]{ 0, 16f / 40f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        // light leaks, screened onto the picture
        fill.setBlendMode(BlendMode.SCREEN);
        ellipse(c, 0, h * 0.14f, w * 0.34f, h * 0.44f, alpha(STAMP, 0.34f), 0.7f);
        ellipse(c, w, h * 0.92f, w * 0.30f, h * 0.45f, alpha(ROSE, 0.30f), 0.7f);
        fill.setBlendMode(null);
        // dusk gathering at the top
        fill.setShader(new LinearGradient(0, 0, 0, h * 0.24f, new int[]{ alpha(INK, 0.78f), alpha(INK, 0.50f), 0 },
                new float[]{ 0, 0.10f / 0.24f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.24f, fill);
        // vignette: radial-gradient(125% 100% at 50% 45%, transparent 52%, ink 55% 82%, ink 88%)
        float rx = w * 1.25f, ry = h * 1.0f;
        float cx = w / 2f, cy = h * 0.45f;
        // the farthest corner sets the gradient's 100%
        float far = (float) Math.hypot(Math.max(cx, w - cx) / rx, Math.max(cy, h - cy) / ry);
        RadialGradient vg = new RadialGradient(0, 0, far, new int[]{ 0, 0, alpha(INK, 0.55f), alpha(INK, 0.88f) },
                new float[]{ 0, 0.52f, 0.82f, 1 }, Shader.TileMode.CLAMP);
        m.reset(); m.setScale(rx, ry); m.postTranslate(cx, cy);
        vg.setLocalMatrix(m);
        fill.setShader(vg);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    /** radial-gradient(rx ry at x y, color, transparent stop) */
    private void ellipse(Canvas c, float x, float y, float rx, float ry, int color, float stop) {
        RadialGradient g = new RadialGradient(0, 0, 1, new int[]{ color, 0 }, new float[]{ 0, stop }, Shader.TileMode.CLAMP);
        m.reset(); m.setScale(rx, ry); m.postTranslate(x, y);
        g.setLocalMatrix(m);
        fill.setShader(g);
        c.drawRect(Math.max(0, x - rx), Math.max(0, y - ry), x + rx, y + ry, fill);
        fill.setShader(null);
    }

    // ---------- over everything: jumping grain and the edge of a film print ----------

    @Override public int overlayStepMs() { return 250; }

    @Override
    public void drawOverlay(Canvas c, int w, int h, int step) {
        float[] j = JUMP[step & 3];
        m.reset(); m.setScale(2, 2); m.postTranslate(px(j[0]), px(j[1]));
        grainPaint.getShader().setLocalMatrix(m);
        grainPaint.setAlpha(Math.round(255 * 0.14f));
        c.drawRect(0, 0, w, h, grainPaint);
        if (filmEdge == null || filmW != w || filmH != h) buildFilmEdge(w, h);
        c.drawBitmap(filmEdge, 0, 0, null);
    }

    /** inset 4 dp, 16 dp corners: ink outside, a soft burnt shadow inside */
    private void buildFilmEdge(int w, int h) {
        filmW = w; filmH = h;
        filmEdge = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(filmEdge);
        RectF r = new RectF(px(4), px(4), w - px(4), h - px(4));
        float rad = px(16);
        path.reset();
        path.addRect(0, 0, w, h, Path.Direction.CW);
        path.addRoundRect(r, rad, rad, Path.Direction.CCW);
        filmPaint.setColor(INK);
        k.drawPath(path, filmPaint);
        filmPaint.setStyle(Paint.Style.STROKE);
        filmPaint.setStrokeWidth(px(24));
        filmPaint.setColor(alpha(INK, 0.6f));
        filmPaint.setMaskFilter(new BlurMaskFilter(px(14), BlurMaskFilter.Blur.NORMAL));
        RectF in = new RectF(r);
        in.inset(-px(8), -px(8));
        k.save();
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        k.clipPath(path);
        k.drawRoundRect(in, rad + px(8), rad + px(8), filmPaint);
        k.restore();
        filmPaint.setMaskFilter(null);
        filmPaint.setStyle(Paint.Style.FILL);
    }

    // ---------- the library panel ----------

    private final Paint panelPaint = new Paint();
    private int panelH = -1;

    @Override
    public void drawPanel(Canvas c, int w, int h) {
        if (h != panelH) { panelH = h; panelPaint.setShader(new LinearGradient(0, 0, 0, h, alpha(DUSK_HI, 0.92f), alpha(DUSK, 0.94f), Shader.TileMode.CLAMP)); }
        c.drawRect(0, 0, w, h, panelPaint);
    }

    // ---------- the cover: a faded print with soft corners, glowing at the edges ----------

    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();

    @Override
    public void drawCover(Canvas c, RectF r, Bitmap art, float playing, Paint p) {
        float rad = px(coverRadius) * (r.width() / px(300));
        rad = Math.max(px(4), Math.min(px(coverRadius), rad));
        // the gold glow round it (box-shadow 0 0 40px -6px gold 55%; brighter while playing)
        float glow = r.width() * 0.16f;
        glowPaint.setShader(new RadialGradient(r.centerX(), r.centerY(), r.width() * 0.5f + glow,
                new int[]{ alpha(GOLD, 0.55f + 0.1f * playing), alpha(GOLD, 0.55f + 0.1f * playing), 0 },
                new float[]{ 0, r.width() * 0.5f / (r.width() * 0.5f + glow), 1 }, Shader.TileMode.CLAMP));
        c.drawRoundRect(r.left - glow, r.top - glow, r.right + glow, r.bottom + glow, rad + glow, rad + glow, glowPaint);
        glowPaint.setShader(null);
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(path);
        if (art != null) {
            int side = Math.min(art.getWidth(), art.getHeight());
            src.set((art.getWidth() - side) / 2, (art.getHeight() - side) / 2, (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
            coverPaint.setColorFilter(faded);
            coverPaint.setAlpha(p == null ? 255 : p.getAlpha());
            c.drawBitmap(art, src, r, coverPaint);
        } else {
            coverPaint.setColorFilter(null);
            coverPaint.setShader(new LinearGradient(0, r.top, 0, r.bottom, DUSK_HI, DUSK, Shader.TileMode.CLAMP));
            c.drawRect(r, coverPaint);
            coverPaint.setShader(null);
            // a note
            fill.setColor(alpha(HAZE, 0.6f));
            float s = r.width() * 0.16f, cx = r.centerX(), cy = r.centerY();
            c.drawCircle(cx - s * 0.35f, cy + s * 0.7f, s * 0.45f, fill);
            c.drawRect(cx + s * 0.05f, cy - s * 1.1f, cx + s * 0.2f, cy + s * 0.7f, fill);
        }
        c.restore();
        // the print's edge: 1 dp of cream
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(px(1));
        fill.setColor(alpha(CREAM, 0.40f + 0.15f * playing));
        c.drawRoundRect(r, rad, rad, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    // ---------- play: the sun ----------

    private final Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void drawPlay(Canvas c, float cx, float cy, float size, float morph, float playing, float progress) {
        float r = size / 2;
        // the halo: full while music plays, fading when paused
        float halo = r * (1.9f + 0.5f * playing);
        sun.setShader(new RadialGradient(cx, cy, halo, new int[]{ alpha(GOLD, 0.30f + 0.25f * playing), alpha(PEACH, 0.14f + 0.16f * playing), 0 },
                new float[]{ r / halo, 0.62f, 1 }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, halo, sun);
        // the progress: a thin ring of light round it
        float rr = r * 1.43f;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(px(1.5f));
        ring.setColor(alpha(CREAM, 0.18f));
        c.drawCircle(cx, cy, rr, ring);
        if (progress > 0) {
            ring.setStrokeWidth(px(2.5f));
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(GOLD);
            c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90, 360 * progress, false, ring);
        }
        // the sun: radial-gradient(circle at 50% 42%, cream 0 22%, gold 48%, peach 78%, peach/rose)
        sun.setShader(new RadialGradient(cx, cy - r * 0.16f, r * 1.08f,
                new int[]{ CREAM, CREAM, GOLD, PEACH, mix(PEACH, ROSE, 0.7f) }, new float[]{ 0, 0.22f, 0.48f, 0.78f, 1 }, Shader.TileMode.CLAMP));
        sun.setAlpha(Math.round(255 * (0.88f + 0.12f * playing)));
        c.drawCircle(cx, cy, r, sun);
        sun.setShader(null);
        sun.setAlpha(255);
        ring.setStrokeWidth(px(1));
        ring.setColor(alpha(CREAM, 0.7f + 0.3f * playing));
        c.drawCircle(cx, cy, r, ring);
        // the glyph: a triangle that folds into two bars
        drawPlayGlyph(c, cx, cy, px(30) * (size / px(74)), morph, INK);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ---------- the seek bar: sunset light on water ----------

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rb = new RectF();

    @Override
    public void drawSeek(Canvas c, RectF r, float[] peaks, float progress, boolean dragging) {
        int n = peaks.length;
        float step = r.width() / n, bw = Math.max(px(1.2f), step * 0.5f), line = r.top + r.height() * 0.6f;
        float pxp = r.left + progress * r.width();
        bar.setShader(new LinearGradient(r.left, 0, Math.max(pxp, r.left + 1), 0, new int[]{ ROSE, PEACH, GOLD }, new float[]{ 0, 0.6f, 1 }, Shader.TileMode.CLAMP));
        Shader played = bar.getShader();
        for (int i = 0; i < n; i++) {
            float x = r.left + i * step + (step - bw) / 2, v = peaks[i];
            float up = Math.max(px(1.5f), v * (line - r.top - px(4))), down = Math.max(px(1), v * (r.bottom - line - px(2)) * 0.7f);
            boolean isPlayed = (i + 0.5f) / n <= progress;
            if (isPlayed) { bar.setShader(played); bar.setAlpha(255); }
            else { bar.setShader(null); bar.setColor(HAZE); bar.setAlpha(Math.round(255 * 0.34f)); }
            rb.set(x, line - up - px(1), x + bw, line - px(1));
            c.drawRoundRect(rb, bw / 2, bw / 2, bar);
            bar.setAlpha(isPlayed ? Math.round(255 * 0.3f) : Math.round(255 * 0.12f));
            rb.set(x, line + px(1.5f), x + bw, line + px(1.5f) + down);
            c.drawRoundRect(rb, bw / 2, bw / 2, bar);
        }
        bar.setShader(null);
        // the playhead: a small sun on the waterline
        float pr = px(dragging ? 6 : 4.5f);
        bar.setShader(new RadialGradient(pxp, line, pr * 3.2f, new int[]{ alpha(GOLD, 0.55f), 0 }, null, Shader.TileMode.CLAMP));
        c.drawCircle(pxp, line, pr * 3.2f, bar);
        bar.setShader(new RadialGradient(pxp, line, pr, new int[]{ CREAM, GOLD, PEACH }, new float[]{ 0, 0.6f, 1 }, Shader.TileMode.CLAMP));
        c.drawCircle(pxp, line, pr, bar);
        bar.setShader(null);
    }

    @Override
    public void drawInfoShade(Canvas c, RectF r) {
        int shade = alpha(mix(mix(DUSK, INK, 0.45f), 0, 1f), 0.66f);
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{ 0, shade, shade, 0 }, new float[]{ 0, 0.26f, 0.76f, 1 }, Shader.TileMode.CLAMP));
        c.drawRect(r, fill);
        fill.setShader(null);
    }
}
