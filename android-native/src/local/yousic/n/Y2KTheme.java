package local.yousic.n;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * Y2K Subway: a dim teal-black night lit the Y2K way. Two button materials set in a brushed-chrome bezel: the
 * smoked lens (dark glass, an aqua neon glyph) and candy gel (glossy teal with a white crescent of gloss on top,
 * for anything switched on); frosted glass panels with a bright hairline; a glass frame and a holographic edge
 * round the cover, crosshair marks at its corners and lime stickers while it plays; the hero play lens with a
 * ring of teal light and a segmented LED meter. Michroma for titles, Share Tech Mono for the small print.
 * Ported from the YouSic theme (renderer/css/subway.css, waveform.js _drawY2k).
 */
public final class Y2KTheme extends Theme {
    static final int BG0 = 0xFF050B0C, BG1 = 0xFF0A1A1B, BG2 = 0xFF0F2A2B, TEAL = 0xFF3FE0C5, TEAL_DEEP = 0xFF0B6B66,
            AQUA = 0xFF7FF3FF, LILAC = 0xFFB79CFF, PINK = 0xFFFF7AD9, LIME = 0xFFC8FF6A, GOLD = 0xFFFFE58A,
            CHROME_HI = 0xFFF4FBFF, CHROME_MID = 0xFF9FB4BD, CHROME_LO = 0xFF4B5D66, TEXT = 0xFFE9FFFB, TEXT_DIM = 0xFF9FC4BD,
            GEL_INK = 0xFF032825, WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
    private static final int[] HOLO = { AQUA, LILAC, PINK, GOLD };
    /** brushed chrome: a conic sweep reads as turned metal */
    private static final int[] BEZEL = { 0xFFF4FBFF, 0xFF4B5D66, 0xFFC9D8DE, 0xFF5F747D, 0xFFF4FBFF, 0xFF4B5D66, 0xFFDFEAEE, 0xFFF4FBFF };
    private static final float[] BEZEL_AT = { 0, 0.14f, 0.29f, 0.44f, 0.57f, 0.73f, 0.87f, 1 };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), q = new Paint(Paint.ANTI_ALIAS_FLAG), art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sticker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF a = new RectF(), b = new RectF();
    private final Matrix m = new Matrix();

    @Override public String name() { return "Y2K Subway"; }

    Y2KTheme(Context c) {
        super(c, 0.9f);
        bg = BG0; ink = BG0; text = TEXT; textDim = TEXT_DIM; accent = AQUA; accentHi = TEAL; accent2 = PINK;
        line = alpha(WHITE, 0.1f); panel = BG1; panelHi = BG2; stamp = TEXT;
        titleFace = c.getResources().getFont(R.font.michroma);
        uiFace = c.getResources().getFont(R.font.manrope_medium);
        uiMedium = c.getResources().getFont(R.font.manrope_semibold);
        uiBold = c.getResources().getFont(R.font.manrope_bold);
        numFace = c.getResources().getFont(R.font.share_tech_mono);
        stampFace = numFace;
        // Michroma is very wide: its sizes stay well under the other skins'
        titleSize = 22; headerSize = 17; artistSize = 14; timeSize = 11; coverRadius = 12;
        titleCaps = true;
        titleGlow = alpha(TEAL, 0.5f);
        playGlyphGlow = alpha(AQUA, 0.9f);
        sticker.setTypeface(numFace);
        sticker.setLetterSpacing(0.1f);
        sticker.setTextAlign(Paint.Align.CENTER);
    }

    // ---------- helpers ----------

    /** an elliptical radial gradient (CSS radial-gradient(rx ry at cx cy, ...)) */
    private Shader ellipse(float cx, float cy, float rx, float ry, int[] colors, float[] at) {
        RadialGradient g = new RadialGradient(0, 0, 1, colors, at, Shader.TileMode.CLAMP);
        Matrix mm = new Matrix();
        mm.setScale(Math.max(1, rx), Math.max(1, ry));
        mm.postTranslate(cx, cy);
        g.setLocalMatrix(mm);
        return g;
    }

    private Shader bezel(float cx, float cy) {
        SweepGradient s = new SweepGradient(cx, cy, BEZEL, BEZEL_AT);
        Matrix mm = new Matrix();
        mm.setRotate(120, cx, cy); // CSS conic "from 210deg" (0 = up) is 120 deg from Android's 3 o'clock
        s.setLocalMatrix(mm);
        return s;
    }

    private Shader holo(RectF r) { return new LinearGradient(r.left, r.top, r.right, r.bottom, HOLO, null, Shader.TileMode.CLAMP); }

    /** the smoked lens in its bezel: dark glass, a faint gloss on top and teal light pooling at the bottom */
    private void lens(Canvas c, RectF r, float rad, float bezelW, boolean lit, float dim) {
        p.setShader(bezel(r.centerX(), r.centerY()));
        p.setAlpha(Math.round(255 * dim));
        c.drawRoundRect(r, rad, rad, p);
        a.set(r); a.inset(bezelW, bezelW);
        float ir = Math.max(0, rad - bezelW);
        p.setShader(new LinearGradient(0, a.top, 0, a.bottom, 0xFF16383A, 0xFF06100F, Shader.TileMode.CLAMP));
        p.setAlpha(255);
        c.drawRoundRect(a, ir, ir, p);
        // teal light pooling at the bottom (brighter when lit / pressed)
        p.setShader(ellipse(a.centerX(), a.bottom + a.height() * 0.04f, a.width() * (lit ? 0.8f : 0.76f), a.height() * (lit ? 0.62f : 0.52f),
                new int[]{ alpha(lit ? AQUA : TEAL, lit ? 0.62f : 0.42f), 0 }, new float[]{ 0, lit ? 0.76f : 0.74f }));
        c.drawRoundRect(a, ir, ir, p);
        // the gloss: a soft crescent on the top half
        p.setShader(ellipse(a.centerX(), a.top, a.width() * 0.84f, a.height() * 0.5f,
                new int[]{ alpha(WHITE, 0.3f), alpha(WHITE, 0.05f), 0 }, new float[]{ 0, 0.74f, 0.75f }));
        c.drawRoundRect(a, ir, ir, p);
        p.setShader(null);
    }

    /** candy gel in its bezel: lit teal glass, a crisp white crescent on top, aqua light pooling at the bottom */
    private void gel(Canvas c, RectF r, float rad, float bezelW, boolean pressed) {
        p.setShader(bezel(r.centerX(), r.centerY()));
        p.setShadowLayer(px(7), 0, 0, alpha(TEAL, pressed ? 0.3f : 0.45f));
        c.drawRoundRect(r, rad, rad, p);
        p.clearShadowLayer();
        a.set(r); a.inset(bezelW, bezelW);
        float ir = Math.max(0, rad - bezelW);
        p.setShader(new LinearGradient(0, a.top, 0, a.bottom, pressed ? TEAL_DEEP : TEAL, pressed ? mix(TEAL_DEEP, BLACK, 0.7f) : TEAL_DEEP, Shader.TileMode.CLAMP));
        c.drawRoundRect(a, ir, ir, p);
        p.setShader(ellipse(a.centerX(), a.bottom + a.height() * 0.04f, a.width() * 0.7f, a.height() * 0.55f,
                new int[]{ alpha(AQUA, 0.95f), 0 }, new float[]{ 0, 0.72f }));
        c.drawRoundRect(a, ir, ir, p);
        p.setShader(ellipse(a.centerX(), a.top + a.height() * 0.02f, a.width() * 0.88f, a.height() * 0.54f,
                new int[]{ alpha(WHITE, pressed ? 0.5f : 0.78f), alpha(WHITE, 0.14f), 0 }, new float[]{ 0, 0.72f, 0.73f }));
        c.drawRoundRect(a, ir, ir, p);
        p.setShader(null);
    }

    /** frosted glass: a faint diagonal sheen over the night, a bright hairline and a highlight along the top */
    private void glass(Canvas c, RectF r, float rad, float body) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(BG1, body));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, alpha(WHITE, 0.14f), alpha(WHITE, 0.03f), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setColor(alpha(WHITE, 0.28f));
        c.drawRoundRect(r.left + px(0.5f), r.top + px(0.5f), r.right - px(0.5f), r.bottom - px(0.5f), rad, rad, p);
        p.setColor(alpha(WHITE, 0.35f));
        c.drawLine(r.left + rad, r.top + px(1.5f), r.right - rad, r.top + px(1.5f), p);
        p.setStyle(Paint.Style.FILL);
    }

    // ---------- the night ----------

    @Override
    public void paintBackdrop(Canvas c, int w, int h, float gx, float gy) {
        // radial-gradient(120% 90% at 50% 40%, bg-1, bg-0)
        q.setShader(ellipse(w * 0.5f, h * 0.4f, w * 1.2f, h * 0.9f, new int[]{ BG1, BG0 }, null));
        c.drawRect(0, 0, w, h, q);
        // aurora: three soft blobs, teal at the top left, lilac at the right, pink at the foot
        float vmax = Math.max(w, h) / 100f, r = 30 * vmax;
        q.setShader(new RadialGradient(-18 * vmax + r, -22 * vmax + r, r, new int[]{ alpha(TEAL, 0.18f), 0 }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, q);
        q.setShader(new RadialGradient(w + 20 * vmax - r, h * 0.1f + r, r, new int[]{ alpha(LILAC, 0.14f), 0 }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, q);
        q.setShader(new RadialGradient(w * 0.2f + r, h + 30 * vmax - r, r, new int[]{ alpha(PINK, 0.12f), 0 }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, q);
        q.setShader(null);
    }

    private final Paint panelPaint = new Paint();

    @Override
    public void drawPanel(Canvas c, int w, int h) {
        panelPaint.setColor(alpha(BG0, 0.9f));
        c.drawRect(0, 0, w, h, panelPaint);
    }

    @Override public void drawScrim(Canvas c, int w, int h, float t) { c.drawColor(alpha(BG0, 0.66f * t)); }

    // ---------- the cover: glass frame, holographic edge, crosshairs, stickers ----------

    @Override public float glowFront(float playing) { return 0.6f; }
    @Override public float glowBack() { return 0.6f; }

    /** not a glow here: the soft shadow the cover casts (0 30px 60px -20px) */
    @Override
    public void drawGlow(Canvas c, RectF r, float strength) {
        if (strength <= 0.005f || r.width() < px(120)) return;
        float cx = r.centerX(), cy = r.centerY() + r.height() * 0.08f;
        float rx = r.width() * 0.58f, ry = r.height() * 0.58f;
        q.setShader(ellipse(cx, cy, rx, ry, new int[]{ alpha(BLACK, strength), alpha(BLACK, strength * 0.6f), 0 }, new float[]{ 0, 0.7f, 1 }));
        c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, q);
        q.setShader(null);
    }

    @Override
    public void drawCover(Canvas c, RectF r, Bitmap bmp, float playing, Paint unused) {
        boolean big = r.width() > px(200);
        drawGlow(c, r, glowFront(playing) * glowScale);
        float rad = Math.max(px(3), Math.min(px(coverRadius), px(coverRadius) * r.width() / px(300)));
        float ring = Math.max(px(1.5f), Math.min(px(6), r.width() * 0.017f));
        // the glass frame
        glass(c, r, rad, 0.6f);
        b.set(r); b.inset(ring, ring);
        float ir = Math.max(px(2), rad - ring);
        if (bmp != null) {
            BitmapShader s = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float side = Math.min(bmp.getWidth(), bmp.getHeight()), k = b.width() / side;
            m.setScale(k, k);
            m.postTranslate(b.left - (bmp.getWidth() - side) / 2 * k, b.top - (bmp.getHeight() - side) / 2 * k);
            s.setLocalMatrix(m);
            art.setShader(s);
            c.drawRoundRect(b, ir, ir, art);
            art.setShader(null);
        } else {
            p.setColor(BG2);
            c.drawRoundRect(b, ir, ir, p);
            p.setColor(TEXT_DIM);
            Icons.draw(c, Icons.NOTE, b.centerX(), b.centerY(), b.width() * 0.32f, p);
        }
        // the 1 px holographic edge
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setShader(holo(r));
        c.drawRoundRect(r.left + px(0.5f), r.top + px(0.5f), r.right - px(0.5f), r.bottom - px(0.5f), rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        if (!big || glowScale == 0) return;
        // crosshair marks just outside the corners
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setColor(alpha(AQUA, 0.7f));
        float o = px(8), arm = px(9);
        for (int sx = 0; sx < 2; sx++) for (int sy = 0; sy < 2; sy++) {
            float x = sx == 0 ? r.left - o : r.right + o, y = sy == 0 ? r.top - o : r.bottom + o;
            c.drawLine(x - arm, y, x + arm, y, p);
            c.drawLine(x, y - arm, x, y + arm, p);
        }
        p.setStyle(Paint.Style.FILL);
        // lime stickers while it plays
        if (playing > 0.5f) {
            sticker(c, "HI-FI", r.left - px(8), r.top - px(6), false);
            sticker(c, "NOW PLAYING", r.right + px(10), r.bottom + px(6), true);
        }
    }

    private void sticker(Canvas c, String s, float x, float y, boolean fromRightBottom) {
        sticker.setTextSize(px(9));
        float tw = sticker.measureText(s), w = tw + px(18), h = px(18);
        float left = fromRightBottom ? x - w : x, top = fromRightBottom ? y - h : y;
        c.save();
        c.rotate(-4, left + w / 2, top + h / 2);
        a.set(left, top, left + w, top + h);
        q.setColor(alpha(BLACK, 0.45f));
        c.drawRoundRect(a.left, a.top + px(3), a.right, a.bottom + px(3), h / 2, h / 2, q);
        q.setColor(LIME);
        c.drawRoundRect(a, h / 2, h / 2, q);
        sticker.setColor(GEL_INK);
        c.drawText(s, a.centerX(), a.centerY() - (sticker.ascent() + sticker.descent()) / 2, sticker);
        c.restore();
    }

    /** the card's back: an opaque teal-night panel with a holographic edge */
    @Override
    public void drawCardBack(Canvas c, RectF r, float radius) {
        drawGlow(c, r, glowBack() * glowScale);
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, BG2, BG1, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setShader(holo(r));
        c.drawRoundRect(r.left + px(0.5f), r.top + px(0.5f), r.right - px(0.5f), r.bottom - px(0.5f), radius, radius, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }

    // ---------- play: the hero lens ----------

    private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void drawPlay(Canvas c, float cx, float cy, float size, float morph, float playing, float progress) {
        float R = size * 0.63f;
        // a glow behind the orb while playing
        if (playing > 0) {
            q.setShader(new RadialGradient(cx, cy, R + px(26), new int[]{ alpha(TEAL, 0.5f * playing), alpha(TEAL, 0.18f * playing), 0 },
                    new float[]{ R / (R + px(26)), 0.82f, 1 }, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R + px(26), q);
            q.setShader(null);
        }
        // the progress: a segmented LED meter, the played part lit teal
        float rr = R + px(8);
        ringP.setStyle(Paint.Style.STROKE);
        ringP.setStrokeWidth(px(3));
        ringP.setPathEffect(new DashPathEffect(new float[]{ px(3), px(2.4f) }, 0));
        ringP.setColor(alpha(CHROME_HI, 0.16f));
        c.drawCircle(cx, cy, rr, ringP);
        if (progress > 0) {
            ringP.setColor(TEAL);
            ringP.setShadowLayer(px(4), 0, 0, TEAL);
            c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, -90, 360 * progress, false, ringP);
            ringP.clearShadowLayer();
        }
        ringP.setPathEffect(null);
        // the brushed-chrome bezel, the dark glass core
        b.set(cx - R, cy - R, cx + R, cy + R);
        p.setShader(bezel(cx, cy));
        c.drawOval(b, p);
        float core = R - px(5);
        p.setShader(new LinearGradient(0, cy - core, 0, cy + core, 0xFF16383A, 0xFF06100F, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, core, p);
        p.setShader(new RadialGradient(cx, cy, core, new int[]{ alpha(TEAL, 0.42f + 0.1f * playing), 0 }, new float[]{ 0, 0.58f }, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, core, p);
        p.setShader(ellipse(cx, cy + core * 1.04f, core * 1.52f, core * 1.04f, new int[]{ alpha(TEAL, 0.42f), 0 }, new float[]{ 0, 0.74f }));
        c.drawCircle(cx, cy, core, p);
        p.setShader(ellipse(cx, cy - core, core * 1.68f, core, new int[]{ alpha(WHITE, 0.3f), alpha(WHITE, 0.05f), 0 }, new float[]{ 0, 0.74f, 0.75f }));
        c.drawCircle(cx, cy, core, p);
        p.setShader(null);
        // the ring of light just inside the bezel
        ringP.setStrokeWidth(px(6));
        ringP.setColor(alpha(TEAL, 0.22f));
        c.drawCircle(cx, cy, core - px(3), ringP);
        ringP.setStrokeWidth(px(2));
        ringP.setColor(alpha(AQUA, 0.85f));
        ringP.setShadowLayer(px(7), 0, 0, alpha(TEAL, 0.85f));
        c.drawCircle(cx, cy, core - px(1), ringP);
        ringP.clearShadowLayer();
        // the glyph: white-hot at the core, aqua neon round it
        drawPlayGlyph(c, cx, cy, px(32) * (size / px(70)), morph, mix(AQUA, CHROME_HI, 0.35f));
    }

    // ---------- the seek bar (waveform.js _drawY2k) ----------

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rb = new RectF();

    @Override
    public void drawSeek(Canvas c, RectF r, float[] peaks, float progress, boolean dragging) {
        int n = peaks.length;
        float step = r.width() / n, bw = Math.max(px(1.4f), step * 0.55f), mid = r.top + r.height() * 0.64f;
        Shader played = new LinearGradient(0, r.top + px(4), 0, mid, new int[]{ TEAL, LILAC, PINK }, null, Shader.TileMode.CLAMP);
        for (int i = 0; i < n; i++) {
            float x = r.left + i * step + (step - bw) / 2, v = peaks[i];
            float top = Math.max(px(1.5f), v * (mid - r.top - px(4))), bot = Math.max(px(1), v * (r.bottom - mid - px(3)) * 0.6f);
            boolean isPlayed = (i + 0.5f) / n <= progress;
            rb.set(x, mid - top, x + bw, mid);
            if (isPlayed) { bar.setShader(played); bar.setAlpha(255); }
            else { bar.setShader(null); bar.setColor(CHROME_LO); bar.setAlpha(Math.round(255 * 0.45f)); }
            c.drawRoundRect(rb, bw / 2, bw / 2, bar);
            bar.setShader(null);
            if (isPlayed) { bar.setColor(PINK); bar.setAlpha(Math.round(255 * 0.3f)); }
            else { bar.setColor(CHROME_LO); bar.setAlpha(Math.round(255 * 0.18f)); }
            rb.set(x, mid + px(2), x + bw, mid + px(2) + bot);
            c.drawRoundRect(rb, bw / 2, bw / 2, bar);
        }
        // the playhead: a small chrome orb in a teal ring
        float px0 = r.left + progress * r.width(), rad = px(dragging ? 6 : 5);
        ringP.setStyle(Paint.Style.STROKE);
        ringP.setStrokeWidth(px(1.5f));
        ringP.setColor(TEAL);
        ringP.setShadowLayer(px(4), 0, 0, TEAL);
        c.drawCircle(px0, mid, rad + px(1.5f), ringP);
        ringP.clearShadowLayer();
        bar.setShader(new RadialGradient(px0 - rad * 0.35f, mid - rad * 0.4f, rad * 1.3f, new int[]{ CHROME_HI, CHROME_MID, CHROME_LO }, new float[]{ 0, 0.55f, 1 }, Shader.TileMode.CLAMP));
        bar.setAlpha(255);
        c.drawCircle(px0, mid, rad, bar);
        bar.setShader(null);
    }

    // ---------- controls ----------

    @Override
    public void drawChip(Canvas c, RectF r, float radius, boolean pressed, boolean on) {
        float bz = r.height() > px(30) ? px(1.5f) : px(1);
        if (on) gel(c, r, radius, bz, pressed);
        else lens(c, r, radius, bz, pressed, 1);
    }

    @Override
    public void drawRound(Canvas c, float cx, float cy, float r, boolean pressed, boolean small) {
        b.set(cx - r, cy - r, cx + r, cy + r);
        lens(c, b, r, px(2), pressed, small ? 0.7f : 1);
    }

    @Override public int icon(boolean on, boolean dim) { return on ? GEL_INK : dim ? CHROME_MID : AQUA; }
    @Override public int iconGlow(boolean on) { return on ? 0 : alpha(AQUA, 0.75f); }

    /** a glass dock */
    @Override
    public void drawNavBar(Canvas c, RectF r, float radius) { glass(c, r, px(14), 0.8f); }

    @Override
    public void drawSheet(Canvas c, RectF r, float radius) { glass(c, r, Math.min(radius, px(14)), 0.94f); }

    /** list rows: the playing one is glass with a holographic bar at its left */
    @Override
    public void drawRowHighlight(Canvas c, RectF r, float radius, boolean pressed, boolean selected, boolean playing) {
        float rad = px(12);
        if (playing || selected) {
            p.setColor(alpha(WHITE, 0.06f));
            c.drawRoundRect(r, rad, rad, p);
            p.setColor(alpha(selected ? AQUA : TEAL, selected ? 0.16f : 0.1f));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(1));
            p.setColor(alpha(selected ? AQUA : WHITE, selected ? 0.6f : 0.2f));
            c.drawRoundRect(r.left + px(0.5f), r.top + px(0.5f), r.right - px(0.5f), r.bottom - px(0.5f), rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            if (playing) {
                a.set(r.left + px(1), r.top + px(8), r.left + px(4), r.bottom - px(8));
                p.setShader(new LinearGradient(0, a.top, 0, a.bottom, HOLO, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(a, px(2), px(2), p);
                p.setShader(null);
            }
        }
        if (pressed) {
            p.setColor(alpha(AQUA, 0.1f));
            c.drawRoundRect(r, rad, rad, p);
        }
    }

    private final Paint visPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float visTop = -1;
    private final RectF vb = new RectF();

    @Override
    public void drawVis(Canvas c, RectF r, float[] v, int n, float t) {
        if (r.top != visTop) {
            visTop = r.top;
            visPaint.setShader(new LinearGradient(0, r.bottom, 0, r.top, new int[]{ alpha(TEAL, 0.05f), alpha(TEAL, 0.38f), alpha(AQUA, 0.55f) },
                    new float[]{ 0, 0.55f, 1 }, Shader.TileMode.CLAMP));
        }
        visPaint.setAlpha(Math.round(255 * t));
        float step = r.width() / n, bw = step * 0.5f;
        for (int i = 0; i < n; i++) {
            float x = r.left + i * step + (step - bw) / 2, h = Math.max(bw, v[i] * r.height());
            vb.set(x, r.bottom - h, x + bw, r.bottom);
            c.drawRoundRect(vb, px(1.5f), px(1.5f), visPaint);
        }
    }

    /** off: a dark slot with a chrome knob; on: lit gel */
    @Override
    public void drawSwitch(Canvas c, float cx, float cy, float on) {
        float w = px(36), h = px(20);
        b.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        if (on > 0.5f) gel(c, b, h / 2, px(1), false);
        else {
            p.setColor(alpha(BLACK, 0.45f));
            c.drawRoundRect(b, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(1));
            p.setColor(alpha(WHITE, 0.28f));
            c.drawRoundRect(b, h / 2, h / 2, p);
            p.setStyle(Paint.Style.FILL);
        }
        float kx = cx - w / 2 + h / 2 + (w - h) * on, kr = h / 2 - px(3);
        p.setShader(new LinearGradient(0, cy - kr, 0, cy + kr, new int[]{ CHROME_HI, CHROME_MID, CHROME_LO, CHROME_MID, CHROME_HI },
                new float[]{ 0, 0.48f, 0.52f, 0.8f, 1 }, Shader.TileMode.CLAMP));
        c.drawCircle(kx, cy, kr, p);
        p.setShader(null);
    }
}
