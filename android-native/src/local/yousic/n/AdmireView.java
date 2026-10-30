package local.yousic.n;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Admire the art: the cover alone, as large as the screen allows, at the picture's own resolution (read
 * straight from the song: the saved covers stop at 1000 px), on black with no other UI and the system bars
 * hidden. It grows out of the player's cover. Pinch or double tap to zoom, drag to look round; tap or swipe
 * down to go back.
 */
@SuppressLint("ViewConstructor")
public final class AdmireView extends View {
    private final Theme th;
    private final Stage stage;
    private final Lib.Track track;
    private Bitmap bmp;
    private BitmapShader shader;
    private final RectF from = new RectF(), fit = new RectF(), dst = new RectF(), src = new RectF();
    private final Matrix m = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), bg = new Paint();
    /** 0 = the player's cover, 1 = full screen */
    private float t;
    private float zoom = 1, panX, panY, dragY;
    private ValueAnimator anim;
    private boolean closing;
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;

    AdmireView(Context c, Stage s, Lib.Track tr, RectF start) {
        super(c);
        th = Theme.get(c);
        stage = s;
        track = tr;
        from.set(start);
        bg.setColor(0xFF000000);
        // straight away: the saved cover; then the full picture
        setBitmap(Covers.get(tr.cover, Covers.LARGE, this::setBitmap));
        final String path = tr.path;
        new Thread(() -> {
            Bitmap full = App.get().lib.scanner.fullArt(path, 3000);
            if (full != null) { full.prepareToDraw(); post(() -> setBitmap(full)); }
        }, "admire").start();
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { close(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                final float z0 = zoom, px0 = panX, py0 = panY;
                final boolean in = zoom < 1.5f;
                final float target = in ? 2.5f : 1;
                final float fx = e.getX(), fy = e.getY();
                // zoomed in about the tap (the point under the finger stays put)
                final float tx = in ? (fx - fit.centerX()) * (1 - target) : 0, ty = in ? (fy - fit.centerY()) * (1 - target) : 0;
                ValueAnimator a = ValueAnimator.ofFloat(0, 1);
                a.setDuration(Motion.ms(Motion.STATE));
                a.setInterpolator(Motion.EASE);
                a.addUpdateListener(v -> {
                    float k = (float) v.getAnimatedValue();
                    zoom = z0 + (target - z0) * k; panX = px0 + (tx - px0) * k; panY = py0 + (ty - py0) * k;
                    clampPan();
                    invalidate();
                });
                a.start();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom > 1.01f) { panX -= dx; panY -= dy; clampPan(); }
                else dragY = Math.max(0, dragY - dy);
                invalidate();
                return true;
            }
        });
    }

    private void setBitmap(Bitmap b) {
        if (b == null) return;
        if (bmp != null && b.getWidth() <= bmp.getWidth()) return; // keep the sharper one
        bmp = b;
        shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        layoutFit();
        invalidate();
    }

    void open() {
        t = 0;
        animateTo(1, Motion.ms(Motion.POPUP_ENTER), Motion.EASE_OUT, null);
    }

    void close() {
        if (closing) return;
        closing = true;
        final float z0 = zoom, px0 = panX, py0 = panY, d0 = dragY;
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(t, 0);
        anim.setDuration(Motion.ms(Motion.PAGE));
        anim.setInterpolator(Motion.EASE);
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue(), k = v / Math.max(0.001f, 1);
            t = v;
            zoom = 1 + (z0 - 1) * k; panX = px0 * k; panY = py0 * k; dragY = d0 * k;
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { stage.admireClosed(AdmireView.this); }
        });
        anim.start();
    }

    private void animateTo(float to, long ms, android.animation.TimeInterpolator ip, Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(t, to);
        anim.setDuration(ms);
        anim.setInterpolator(ip);
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        if (end != null) anim.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { end.run(); } });
        anim.start();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutFit(); }

    /** as large as the screen allows, keeping its shape */
    private void layoutFit() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || bmp == null) return;
        float k = Math.min(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
        float bw = bmp.getWidth() * k, bh = bmp.getHeight() * k;
        fit.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
    }

    private void zoomBy(float f, float fx, float fy) {
        float nz = Math.max(1, Math.min(6, zoom * f));
        f = nz / zoom;
        // keep the point under the fingers where it is
        panX = (panX - (fx - fit.centerX())) * f + (fx - fit.centerX());
        panY = (panY - (fy - fit.centerY())) * f + (fy - fit.centerY());
        zoom = nz;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        float mx = Math.max(0, (fit.width() * zoom - getWidth()) / 2), my = Math.max(0, (fit.height() * zoom - getHeight()) / 2);
        panX = Math.max(-mx, Math.min(mx, panX));
        panY = Math.max(-my, Math.min(my, panY));
        if (zoom <= 1.001f) { panX = 0; panY = 0; }
    }

    @Override protected void onDraw(Canvas c) {
        float drag = Math.min(1, dragY / (getHeight() * 0.4f));
        bg.setAlpha(Math.round(255 * t * (1 - drag)));
        c.drawRect(0, 0, getWidth(), getHeight(), bg);
        if (bmp == null) return;
        // from the player's cover to the screen, then zoom and pan
        float cw = fit.width() * zoom, ch = fit.height() * zoom;
        float cx = fit.centerX() + panX, cy = fit.centerY() + panY + dragY;
        float scaleDrag = 1 - 0.25f * drag;
        cw *= scaleDrag; ch *= scaleDrag;
        dst.set(Theme.lerp(from.left, cx - cw / 2, t), Theme.lerp(from.top, cy - ch / 2, t),
                Theme.lerp(from.right, cx + cw / 2, t), Theme.lerp(from.bottom, cy + ch / 2, t));
        // the cover is square in the player: crop to a square there, open out to the whole picture here
        float side = Math.min(bmp.getWidth(), bmp.getHeight());
        float sw = Theme.lerp(side, bmp.getWidth(), t), sh = Theme.lerp(side, bmp.getHeight(), t);
        src.set((bmp.getWidth() - sw) / 2, (bmp.getHeight() - sh) / 2, (bmp.getWidth() + sw) / 2, (bmp.getHeight() + sh) / 2);
        m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        float rad = Theme.lerp(th.px(th.coverRadius), 0, t);
        c.drawRoundRect(dst, rad, rad, paint);
        paint.setShader(null);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closing) return true;
        scale.onTouchEvent(e);
        if (!scale.isInProgress()) gestures.onTouchEvent(e);
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && dragY > 0) {
            if (dragY > getHeight() * 0.15f) close();
            else {
                final float d0 = dragY;
                ValueAnimator back = ValueAnimator.ofFloat(1, 0);
                back.setDuration(Motion.ms(Motion.STATE));
                back.setInterpolator(Motion.EASE_OUT);
                back.addUpdateListener(v -> { dragY = d0 * (float) v.getAnimatedValue(); invalidate(); });
                back.start();
            }
        }
        return true;
    }
}
