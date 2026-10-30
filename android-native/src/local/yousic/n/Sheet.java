package local.yousic.n;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * A popup menu or dialog rising from the bottom over a dimmed screen (350 ms in, 200 ms out):
 * a title, rows (an icon, text, a value, a radio, switch or tick), and buttons along the foot. Drag it down to
 * dismiss it (a flick faster than 1800 dp/s, or past a third), or tap outside.
 */
@SuppressLint("ViewConstructor")
public final class Sheet extends View {
    public static final int NONE = 0, RADIO = 1, SWITCH = 2, TICK = 3;

    public static final class Item {
        public int icon, check;
        public String text, value;
        public boolean checked, header, disabled, stay;
        public Runnable action;

        public Item(int icon, String text, Runnable action) { this.icon = icon; this.text = text; this.action = action; }
        public static Item header(String text) { Item i = new Item(0, text, null); i.header = true; return i; }
        public Item value(String v) { value = v; return this; }
        public Item radio(boolean on) { check = RADIO; checked = on; return this; }
        public Item toggle(boolean on) { check = SWITCH; checked = on; stay = true; return this; }
        public Item tick(boolean on) { check = TICK; checked = on; return this; }
        public Item stay() { stay = true; return this; }
        public Item disabled() { disabled = true; return this; }
    }

    public String title;
    public final List<Item> items = new ArrayList<>();
    public final List<Item> buttons = new ArrayList<>();
    public Runnable onDismiss;
    /** removes this from the screen (set by the stage) */
    Runnable remover;
    int bottomInset, topInset;

    private final Theme th;
    private float open, drag, scroll, maxScroll;
    private ValueAnimator anim;
    private boolean closing;
    private final RectF panel = new RectF(), row = new RectF();
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), textP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            valueP = new TextPaint(Paint.ANTI_ALIAS_FLAG), headP = new TextPaint(Paint.ANTI_ALIAS_FLAG), btnP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float titleH, itemH, headH, btnH, radius;
    private float listTop, listBottom;
    private int pressed = -1, pressedBtn = -1;
    private final int slop;
    private VelocityTracker vt;
    private float downY, lastY;
    private boolean outside, moved, dragging;

    public Sheet(Context c, String title) {
        super(c);
        th = Theme.get(c);
        this.title = title;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        titleH = th.px(56); itemH = th.px(48); headH = th.px(34); btnH = th.px(52); radius = th.px(24);
        titleP.setTypeface(th.titleFace); titleP.setTextSize(th.px(th.titleCaps ? 17 : 22)); titleP.setColor(th.text);
        textP.setTypeface(th.uiMedium); textP.setTextSize(th.px(14.5f)); textP.setColor(th.text);
        valueP.setTypeface(th.uiFace); valueP.setTextSize(th.px(12.5f)); valueP.setColor(th.textDim); valueP.setTextAlign(Paint.Align.RIGHT);
        headP.setTypeface(th.uiMedium); headP.setTextSize(th.px(11)); headP.setColor(th.accent); headP.setLetterSpacing(0.16f);
        btnP.setTypeface(th.uiBold); btnP.setTextSize(th.px(15)); btnP.setColor(th.accent); btnP.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public Item add(Item i) { items.add(i); return i; }
    public Item button(String text, Runnable action) { Item i = new Item(0, text, action); buttons.add(i); return i; }

    @Override public boolean hasOverlappingRendering() { return false; }

    void show() {
        open = 0;
        animateTo(1, Motion.ms(Motion.POPUP_ENTER), Motion.EASE_OUT, null);
    }

    public void dismiss() {
        if (closing) return;
        closing = true;
        animateTo(0, Motion.ms(Motion.POPUP_EXIT), Motion.EASE_IN, () -> {
            if (remover != null) remover.run();
            if (onDismiss != null) onDismiss.run();
        });
    }

    private void animateTo(float to, long ms, android.animation.TimeInterpolator ip, Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(open, to);
        anim.setDuration(ms);
        anim.setInterpolator(ip);
        anim.addUpdateListener(a -> { open = (float) a.getAnimatedValue(); invalidate(); });
        if (end != null) anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { end.run(); }
        });
        anim.start();
    }

    private float contentH() {
        float h = 0;
        for (Item i : items) h += i.header ? headH : itemH;
        return h;
    }

    @Override protected void onLayout(boolean ch, int l, int t, int r, int b) { measurePanel(); }

    private void measurePanel() {
        int w = getWidth(), h = getHeight();
        float pw = Math.min(w - th.px(16), th.px(520));
        float head = title == null ? th.px(10) : titleH;
        float foot = buttons.size() * btnH + (buttons.isEmpty() ? th.px(10) : 0);
        float maxH = h - bottomInset - topInset - th.px(40);
        float list = Math.min(contentH(), maxH - head - foot);
        float ph = head + list + foot;
        float bottom = h - bottomInset - th.px(8);
        panel.set((w - pw) / 2, bottom - ph, (w + pw) / 2, bottom);
        listTop = panel.top + head;
        listBottom = listTop + list;
        maxScroll = Math.max(0, contentH() - list);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    /** Call after changing the items while shown. */
    public void refresh() { measurePanel(); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        th.drawScrim(c, getWidth(), getHeight(), open);
        float dy = (1 - open) * th.px(36) + drag;
        float sc = 0.94f + 0.06f * open;
        int save = c.save();
        c.translate(0, dy);
        c.scale(sc, sc, panel.centerX(), panel.bottom);
        if (open < 1) c.saveLayerAlpha(panel, Math.round(255 * open));
        th.drawSheet(c, panel, radius);
        if (title != null) {
            CharSequence t = TextUtils.ellipsize(title, titleP, panel.width() - th.px(48), TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), panel.left + th.px(24), panel.top + th.px(36), titleP);
        }
        c.save();
        c.clipRect(panel.left, listTop, panel.right, listBottom);
        float y = listTop - scroll;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float rh = it.header ? headH : itemH;
            if (y + rh > listTop && y < listBottom) drawItem(c, it, i, y, rh);
            y += rh;
        }
        c.restore();
        float by = listBottom;
        for (int i = 0; i < buttons.size(); i++) {
            p.setColor(th.line);
            c.drawRect(panel.left + th.px(16), by, panel.right - th.px(16), by + Math.max(1, th.px(0.8f)), p);
            if (i == pressedBtn) {
                row.set(panel.left + th.px(6), by + th.px(4), panel.right - th.px(6), by + btnH - th.px(4));
                th.drawChip(c, row, th.px(18), true, false);
            }
            btnP.setAlpha(buttons.get(i).disabled ? 90 : 255);
            c.drawText(buttons.get(i).text, panel.centerX(), by + btnH / 2 - (btnP.ascent() + btnP.descent()) / 2, btnP);
            by += btnH;
        }
        c.restoreToCount(save);
    }

    private void drawItem(Canvas c, Item it, int i, float y, float rh) {
        float l = panel.left, r = panel.right;
        if (it.header) {
            c.drawText(it.text.toUpperCase(java.util.Locale.ROOT), l + th.px(24), y + rh - th.px(10), headP);
            return;
        }
        if (i == pressed && !it.disabled) {
            row.set(l + th.px(8), y + th.px(2), r - th.px(8), y + rh - th.px(2));
            th.drawChip(c, row, th.px(16), true, false);
        }
        int alpha = it.disabled ? 100 : 255;
        float x = l + th.px(24);
        if (it.icon != 0) {
            p.setColor(it.checked && it.check == RADIO ? th.accentHi : th.text);
            p.setAlpha(alpha);
            Icons.draw(c, it.icon, x + th.px(10), y + rh / 2, th.px(19), p);
            x += th.px(38);
        }
        float right = r - th.px(24);
        if (it.check == SWITCH) {
            th.drawSwitch(c, right - th.px(18), y + rh / 2, it.checked ? 1 : 0);
            right -= th.px(48);
        } else if (it.check == RADIO) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(1.8f));
            p.setColor(it.checked ? th.accentHi : Theme.alpha(th.text, 0.4f));
            c.drawCircle(right - th.px(9), y + rh / 2, th.px(9), p);
            p.setStyle(Paint.Style.FILL);
            if (it.checked) c.drawCircle(right - th.px(9), y + rh / 2, th.px(4.5f), p);
            right -= th.px(30);
        } else if (it.check == TICK && it.checked) {
            p.setColor(th.accentHi);
            Icons.draw(c, Icons.CHECK, right - th.px(10), y + rh / 2, th.px(20), p);
            right -= th.px(30);
        }
        float valueW = 0;
        if (it.value != null && !it.value.isEmpty()) {
            CharSequence v = TextUtils.ellipsize(it.value, valueP, (right - x) * 0.6f, TextUtils.TruncateAt.MIDDLE);
            valueP.setAlpha(alpha);
            c.drawText(v, 0, v.length(), right, y + rh / 2 - (valueP.ascent() + valueP.descent()) / 2, valueP);
            valueW = valueP.measureText(v, 0, v.length()) + th.px(12);
        }
        textP.setColor(it.checked && it.check == RADIO ? th.accentHi : th.text);
        textP.setAlpha(alpha);
        CharSequence t = TextUtils.ellipsize(it.text, textP, right - x - valueW, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), x, y + rh / 2 - (textP.ascent() + textP.descent()) / 2, textP);
    }

    private int itemAt(float y) {
        if (y < listTop || y > listBottom) return -1;
        float yy = listTop - scroll;
        for (int i = 0; i < items.size(); i++) {
            float rh = items.get(i).header ? headH : itemH;
            if (y >= yy && y < yy + rh) return items.get(i).header ? -1 : i;
            yy += rh;
        }
        return -1;
    }

    private int buttonAt(float y) {
        if (y < listBottom) return -1;
        int i = (int) ((y - listBottom) / btnH);
        return i >= 0 && i < buttons.size() ? i : -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing) return true;
        float x = e.getX(), y = e.getY() - drag;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (anim != null) anim.cancel();
                open = 1;
                outside = !panel.contains(x, y);
                downY = lastY = e.getY(); moved = false; dragging = false;
                vt = VelocityTracker.obtain(); vt.addMovement(e);
                if (!outside) { pressed = itemAt(y); pressedBtn = buttonAt(y); invalidate(); }
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float d = e.getY() - lastY;
                if (!moved && Math.abs(e.getY() - downY) > slop) { moved = true; pressed = -1; pressedBtn = -1; }
                if (moved && !outside) {
                    if (drag == 0 && (scroll > 0 || d < 0) && maxScroll > 0 && !dragging) {
                        scroll = Math.max(0, Math.min(maxScroll, scroll - d));
                    } else {
                        dragging = true;
                        drag = Math.max(0, drag + d);
                    }
                    invalidate();
                }
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float vy = 0;
                if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                if (outside && !moved) { dismiss(); return true; }
                if (dragging) {
                    if (vy / th.dp > Motion.DIALOG_DRAG_DP_S || drag > panel.height() / 3) { dismiss(); return true; }
                    final float from = drag;
                    ValueAnimator back = ValueAnimator.ofFloat(from, 0);
                    back.setDuration(Motion.ms(Motion.STATE));
                    back.setInterpolator(Motion.EASE_OUT);
                    back.addUpdateListener(a -> { drag = (float) a.getAnimatedValue(); invalidate(); });
                    back.start();
                    dragging = false;
                    return true;
                }
                if (!moved) {
                    int i = itemAt(y), b = buttonAt(y);
                    if (i >= 0 && i == pressed) tap(items.get(i));
                    else if (b >= 0 && b == pressedBtn) tap(buttons.get(b));
                }
                pressed = -1; pressedBtn = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1; pressedBtn = -1;
                if (vt != null) { vt.recycle(); vt = null; }
                drag = 0;
                invalidate();
                return true;
            default: return true;
        }
    }

    private void tap(Item it) {
        if (it.disabled) return;
        if (it.check == SWITCH) it.checked = !it.checked;
        if (it.action != null) it.action.run();
        if (it.stay) refresh(); else dismiss();
    }
}
