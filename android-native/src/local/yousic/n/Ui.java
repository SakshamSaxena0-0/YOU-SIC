package local.yousic.n;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** The small parts every screen uses: icon buttons, text chips, and layout helpers. */
public final class Ui {
    private Ui() {}

    public static void place(View v, int x, int y, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, w), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Math.max(0, h), View.MeasureSpec.EXACTLY));
        v.layout(x, y, x + w, y + h);
    }

    public static void tick(View v) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }

    /** A button: an icon (and maybe a label) on a chip, a round button, or bare. */
    @SuppressLint("ViewConstructor")
    public static final class Btn extends View {
        public static final int CHIP = 0, ROUND = 1, ROUND_SMALL = 2, PLAIN = 3;
        public int icon;
        public final int style;
        public String label;
        public boolean on, dim;
        public float iconDp = 22;
        private final Theme th;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Btn(Context c, int icon, int style) {
            super(c);
            th = Theme.get(c);
            this.icon = icon;
            this.style = style;
            setClickable(true);
            tp.setTypeface(th.uiMedium);
            tp.setTextSize(th.px(th.compact ? 13 : 14));
        }

        public Btn click(Runnable r) { setOnClickListener(v -> r.run()); return this; }
        public Btn longClick(Runnable r) { setOnLongClickListener(v -> { r.run(); return true; }); return this; }
        public Btn label(String s) { label = s; invalidate(); return this; }

        public void set(boolean on) { if (this.on != on) { this.on = on; invalidate(); } }
        public void setIcon(int i) { if (icon != i) { icon = i; invalidate(); } }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean pressed = isPressed();
            switch (style) {
                case CHIP:
                    r.set(0, 0, w, h);
                    th.drawChip(c, r, h / 2f, pressed, on);
                    break;
                case ROUND: case ROUND_SMALL:
                    th.drawRound(c, w / 2f, h / 2f, Math.min(w, h) / 2f, pressed, style == ROUND_SMALL);
                    break;
                default:
                    if (pressed || on) {
                        r.set(0, 0, w, h);
                        th.drawChip(c, r, h / 2f, pressed, on);
                    }
                    break;
            }
            int col = th.icon(on, dim);
            if (pressed && style == PLAIN && !on) col = th.accentHi;
            p.setColor(col);
            int glow = th.iconGlow(on);
            if (glow != 0) p.setShadowLayer(th.px(4), 0, 0, glow); else p.clearShadowLayer();
            float s = th.px(iconDp);
            if (label != null && icon != 0) {
                float tw = tp.measureText(label);
                float total = s + th.px(6) + tw, x = (w - total) / 2;
                Icons.draw(c, icon, x + s / 2, h / 2f, s, p);
                tp.setColor(col);
                c.drawText(label, x + s + th.px(6), h / 2f - (tp.ascent() + tp.descent()) / 2, tp);
            } else if (label != null) {
                tp.setColor(col);
                tp.setTextAlign(Paint.Align.CENTER);
                c.drawText(label, w / 2f, h / 2f - (tp.ascent() + tp.descent()) / 2, tp);
                tp.setTextAlign(Paint.Align.LEFT);
            } else {
                Icons.draw(c, icon, w / 2f, h / 2f, s, p);
            }
        }
    }

    /** A line of text on a pill as wide as the text (the song's title and artist under the cover). */
    @SuppressLint("ViewConstructor")
    public static final class TextChip extends View {
        public final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Theme th;
        private final RectF r = new RectF();
        private String text = "";
        private CharSequence shown = "";
        public float padX;

        public TextChip(Context c) {
            super(c);
            th = Theme.get(c);
            setClickable(true);
            padX = th.px(14);
        }

        public void setText(String s) {
            text = s == null ? "" : s;
            fit();
            invalidate();
        }

        private void fit() {
            int w = getWidth();
            if (w > 0) shown = TextUtils.ellipsize(text, tp, w - 2 * padX, TextUtils.TruncateAt.END);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { fit(); }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

        /** the pill's width for the text shown */
        public float pillWidth() { return tp.measureText(shown, 0, shown.length()) + 2 * padX; }

        @Override protected void onDraw(Canvas c) {
            if (shown.length() == 0) return;
            float h = getHeight();
            r.set(0, 0, Math.min(getWidth(), pillWidth()), h);
            th.drawChip(c, r, h / 2f, isPressed(), false);
            c.drawText(shown, 0, shown.length(), padX, h / 2f - (tp.ascent() + tp.descent()) / 2, tp);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            // only the pill is the button
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && e.getX() > pillWidth()) return false;
            return super.onTouchEvent(e);
        }
    }
}
