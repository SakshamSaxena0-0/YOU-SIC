package local.yousic.n;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.Locale;

/**
 * Equalizer and tone: on / off, presets, ten bands (drag a band up or down), and three knobs: preamp, bass,
 * treble (drag up / down; double tap for 0). Values glide to a preset in 300 ms.
 */
@SuppressLint("ViewConstructor")
public final class EqScreen extends android.view.View {
    private final Theme th;
    private final Effects fx;
    private final Stage stage;
    int insetTop, reserveBottom;
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            chipP = new TextPaint(Paint.ANTI_ALIAS_FLAG), valP = new TextPaint(Paint.ANTI_ALIAS_FLAG), knobP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), graph = new RectF(), eqSwitch = new RectF(), toneSwitch = new RectF(), limSwitch = new RectF();
    private final RectF[] presetR = new RectF[Effects.PRESETS.length];
    private final RectF[] knobR = { new RectF(), new RectF(), new RectF() };
    private final Path curve = new Path();
    private float presetScroll, presetMax;
    private int touchBand = -1, touchKnob = -1, touchPreset = -1;
    private float downX, downY, lastY, lastX;
    private boolean movedTouch, presetDrag;
    private long lastTapTime;
    private int lastTapKnob = -1;
    private final int slop;
    private ValueAnimator glide;

    EqScreen(Context c, Effects fx, Stage s) {
        super(c);
        th = Theme.get(c);
        this.fx = fx;
        stage = s;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        for (int i = 0; i < presetR.length; i++) presetR[i] = new RectF();
        titleP.setTypeface(th.titleFace); titleP.setTextSize(th.px(th.headerSize)); titleP.setColor(th.text);
        small.setTypeface(th.uiMedium); small.setTextSize(th.px(11)); small.setColor(th.textDim); small.setTextAlign(Paint.Align.CENTER);
        chipP.setTypeface(th.uiMedium); chipP.setTextSize(th.px(13.5f)); chipP.setColor(th.text);
        valP.setTypeface(th.numFace); valP.setTextSize(th.px(10.5f)); valP.setColor(th.accent); valP.setTextAlign(Paint.Align.CENTER);
        knobP.setTypeface(th.uiMedium); knobP.setTextSize(th.px(12)); knobP.setColor(th.textDim); knobP.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    private float dp(float v) { return v * th.dp; }

    @Override protected void onLayout(boolean ch, int l, int t, int rr, int b) {
        int w = rr - l, h = b - t;
        float top = insetTop + dp(84);
        eqSwitch.set(w - dp(80), insetTop + dp(22), w - dp(20), insetTop + dp(58));
        // presets: a row of chips
        float x = dp(20), py = top;
        for (int i = 0; i < presetR.length; i++) {
            float cw = chipP.measureText(Effects.PRESETS[i]) + dp(28);
            presetR[i].set(x, py, x + cw, py + dp(38));
            x += cw + dp(8);
        }
        presetMax = Math.max(0, x - w + dp(12));
        float bottom = h - reserveBottom - dp(12);
        float knobsH = dp(150);
        graph.set(dp(28), top + dp(70), w - dp(28), bottom - knobsH - dp(30));
        float ky = bottom - knobsH + dp(10), ks = Math.min(dp(84), (w - dp(40)) / 3.6f);
        for (int i = 0; i < 3; i++) {
            float cx = w * (i + 1) / 4f;
            knobR[i].set(cx - ks / 2, ky, cx + ks / 2, ky + ks);
        }
        toneSwitch.set(dp(20), bottom - dp(36), dp(130), bottom);
        limSwitch.set(w - dp(130), bottom - dp(36), w - dp(20), bottom);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        th.drawPanel(c, w, getHeight());
        c.drawText("Equalizer", dp(20), insetTop + dp(52), titleP);
        th.drawSwitch(c, eqSwitch.centerX(), eqSwitch.centerY(), fx.eqOn ? 1 : 0);
        // presets
        c.save();
        c.clipRect(0, presetR[0].top - dp(4), w, presetR[0].bottom + dp(4));
        c.translate(-presetScroll, 0);
        for (int i = 0; i < presetR.length; i++) {
            th.drawChip(c, presetR[i], presetR[i].height() / 2, i == touchPreset && !presetDrag, fx.eqOn && fx.preset == i);
            chipP.setColor(fx.eqOn && fx.preset == i ? th.accentHi : th.text);
            c.drawText(Effects.PRESETS[i], presetR[i].left + dp(14), presetR[i].centerY() - (chipP.ascent() + chipP.descent()) / 2, chipP);
        }
        c.restore();
        // the bands
        float alpha = fx.eqOn ? 1 : 0.4f;
        int n = Effects.BANDS;
        float step = graph.width() / n, zero = graph.centerY();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, dp(0.8f)));
        p.setColor(Theme.alpha(th.text, 0.12f));
        c.drawLine(graph.left, zero, graph.right, zero, p);
        c.drawLine(graph.left, graph.top, graph.right, graph.top, p);
        c.drawLine(graph.left, graph.bottom, graph.right, graph.bottom, p);
        curve.reset();
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = graph.left + step * (i + 0.5f);
            ys[i] = zero - fx.gain(i) / Effects.MAX_DB * graph.height() / 2;
            ys[i] = Math.max(graph.top, Math.min(graph.bottom, ys[i]));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Theme.alpha(th.text, 0.14f));
            c.drawLine(xs[i], graph.top, xs[i], graph.bottom, p);
            p.setColor(Theme.alpha(th.accent, 0.7f * alpha));
            c.drawLine(xs[i], zero, xs[i], ys[i], p);
            c.drawText(Effects.LABELS[i], xs[i], graph.bottom + dp(20), small);
            float g = fx.gain(i);
            valP.setAlpha(Math.round(255 * alpha));
            c.drawText((g > 0 ? "+" : "") + String.format(Locale.ROOT, g == Math.round(g) ? "%.0f" : "%.1f", g), xs[i], graph.top - dp(8), valP);
        }
        // a smooth line through the bands
        curve.moveTo(graph.left, ys[0]);
        curve.lineTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            float mx = (xs[i - 1] + xs[i]) / 2;
            curve.cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i]);
        }
        curve.lineTo(graph.right, ys[n - 1]);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(Theme.alpha(th.accentHi, alpha));
        c.drawPath(curve, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            p.setColor(i == touchBand ? th.accentHi : Theme.alpha(th.accent, alpha));
            c.drawCircle(xs[i], ys[i], dp(i == touchBand ? 9 : 7), p);
            p.setColor(th.ink);
            c.drawCircle(xs[i], ys[i], dp(3), p);
        }
        // knobs: preamp, bass, treble
        String[] names = { "Preamp", "Bass", "Treble" };
        float[] vals = { fx.preamp, fx.bass, fx.treble };
        boolean[] on = { fx.eqOn, fx.toneOn, fx.toneOn };
        for (int i = 0; i < 3; i++) drawKnob(c, knobR[i], vals[i], names[i], on[i], i == touchKnob);
        drawToggle(c, toneSwitch, "Tone", fx.toneOn);
        drawToggle(c, limSwitch, "Limiter", fx.limiterOn);
    }

    private void drawKnob(Canvas c, RectF k, float v, String name, boolean on, boolean active) {
        float cx = k.centerX(), cy = k.centerY(), rad = k.width() / 2;
        th.drawRound(c, cx, cy, rad, active, false);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(3));
        r.set(cx - rad - dp(7), cy - rad - dp(7), cx + rad + dp(7), cy + rad + dp(7));
        p.setColor(Theme.alpha(th.text, 0.14f));
        c.drawArc(r, 135, 270, false, p);
        float frac = v / Effects.MAX_DB; // -1..1, 0 at the top
        p.setColor(Theme.alpha(th.accentHi, on ? 1 : 0.4f));
        c.drawArc(r, 270, 135 * frac, false, p);
        // the pointer
        double ang = Math.toRadians(270 + 135 * frac);
        p.setStrokeWidth(dp(3));
        c.drawLine(cx + (float) Math.cos(ang) * rad * 0.35f, cy + (float) Math.sin(ang) * rad * 0.35f,
                cx + (float) Math.cos(ang) * rad * 0.75f, cy + (float) Math.sin(ang) * rad * 0.75f, p);
        p.setStyle(Paint.Style.FILL);
        valP.setAlpha(255);
        c.drawText((v > 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f dB", v), cx, k.bottom + dp(22), valP);
        c.drawText(name.toUpperCase(Locale.ROOT), cx, k.top - dp(14), knobP);
    }

    private void drawToggle(Canvas c, RectF t, String label, boolean on) {
        th.drawChip(c, t, t.height() / 2, false, on);
        chipP.setColor(on ? th.accentHi : th.text);
        c.drawText(label, t.left + dp(14), t.centerY() - (chipP.ascent() + chipP.descent()) / 2, chipP);
        th.drawSwitch(c, t.right - dp(30), t.centerY(), on ? 1 : 0);
    }

    // ---------- touch ----------

    private int bandAt(float x, float y) {
        if (y < graph.top - dp(24) || y > graph.bottom + dp(24)) return -1;
        int i = (int) ((x - graph.left) / (graph.width() / Effects.BANDS));
        return i >= 0 && i < Effects.BANDS ? i : -1;
    }

    private int knobAt(float x, float y) {
        for (int i = 0; i < 3; i++) { r.set(knobR[i]); r.inset(-dp(16), -dp(16)); if (r.contains(x, y)) return i; }
        return -1;
    }

    private void setBand(int i, float y) {
        float db = (graph.centerY() - y) / (graph.height() / 2) * Effects.MAX_DB;
        db = Math.round(Math.max(-Effects.MAX_DB, Math.min(Effects.MAX_DB, db)) * 2) / 2f;
        if (db != fx.bands[i]) { fx.bands[i] = db; fx.preset = -1; fx.eqOn = true; fx.apply(); invalidate(); }
    }

    private void setKnob(int k, float v) {
        v = Math.round(Math.max(-Effects.MAX_DB, Math.min(Effects.MAX_DB, v)) * 2) / 2f;
        if (k == 0) { fx.preamp = v; fx.eqOn = true; } else if (k == 1) { fx.bass = v; fx.toneOn = true; } else { fx.treble = v; fx.toneOn = true; }
        fx.apply();
        invalidate();
    }

    private float knobVal(int k) { return k == 0 ? fx.preamp : k == 1 ? fx.bass : fx.treble; }

    /** a preset: the bands glide to it */
    private void preset(int i) {
        if (glide != null) glide.cancel();
        final float[] from = fx.bands.clone(), to = Effects.preset(i);
        fx.preset = i;
        fx.eqOn = true;
        glide = ValueAnimator.ofFloat(0, 1);
        glide.setDuration(Motion.ms(Motion.KNOB_VALUE));
        glide.setInterpolator(Motion.EASE);
        glide.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int k = 0; k < Effects.BANDS; k++) fx.bands[k] = from[k] + (to[k] - from[k]) * t;
            invalidate();
        });
        glide.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { System.arraycopy(to, 0, fx.bands, 0, Effects.BANDS); fx.apply(); invalidate(); }
        });
        glide.start();
        fx.apply();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = x; downY = lastY = y; movedTouch = false; presetDrag = false;
                touchBand = -1; touchKnob = -1; touchPreset = -1;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (y >= presetR[0].top && y <= presetR[0].bottom) {
                    for (int i = 0; i < presetR.length; i++) if (x + presetScroll >= presetR[i].left && x + presetScroll <= presetR[i].right) touchPreset = i;
                    if (touchPreset < 0) touchPreset = -2;
                } else if ((touchKnob = knobAt(x, y)) >= 0) {
                    long now = System.currentTimeMillis();
                    if (lastTapKnob == touchKnob && now - lastTapTime < 300) setKnob(touchKnob, 0);
                    lastTapTime = now; lastTapKnob = touchKnob;
                } else if ((touchBand = bandAt(x, y)) >= 0) {
                    setBand(touchBand, y);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) movedTouch = true;
                if (touchPreset != -1 && movedTouch) {
                    presetDrag = true;
                    presetScroll = Math.max(0, Math.min(presetMax, presetScroll - (x - lastX)));
                } else if (touchKnob >= 0) {
                    setKnob(touchKnob, knobVal(touchKnob) + (lastY - y) / dp(8) * 0.5f);
                } else if (touchBand >= 0) {
                    int b = bandAt(x, graph.centerY());
                    if (b >= 0) touchBand = b;
                    setBand(touchBand, y);
                }
                lastX = x; lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!movedTouch) {
                    if (touchPreset >= 0) preset(touchPreset);
                    else if (eqSwitch.contains(x, y)) { fx.eqOn = !fx.eqOn; fx.apply(); }
                    else if (toneSwitch.contains(x, y)) { fx.toneOn = !fx.toneOn; fx.apply(); }
                    else if (limSwitch.contains(x, y)) { fx.limiterOn = !fx.limiterOn; fx.apply(); }
                }
                touchBand = -1; touchKnob = -1; touchPreset = -1; presetDrag = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchBand = -1; touchKnob = -1; touchPreset = -1; presetDrag = false;
                invalidate();
                return true;
            default: return true;
        }
    }
}
