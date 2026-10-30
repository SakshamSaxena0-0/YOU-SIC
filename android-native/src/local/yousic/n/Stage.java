package local.yousic.n;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * The window's content, back to front: the theme's backdrop (painted once into a bitmap), the player, the
 * library, the cover in flight between them, and the theme's overlay (film edge, grain). Opening the
 * library flies the album art into the mini player (250 ms); closing flies it back.
 */
@SuppressLint("ViewConstructor")
public final class Stage extends FrameLayout {
    final Theme th;
    final Backdrop backdrop;
    final PlayerScreen player;
    final LibraryScreen library;
    final FlyCover fly;
    final Overlay overlay;
    private float libT;           // 0 = player, 1 = library
    private ValueAnimator anim;
    private final RectF from = new RectF(), to = new RectF();

    Stage(Context c, App app) {
        super(c);
        th = Theme.get(c);
        backdrop = new Backdrop(c);
        player = new PlayerScreen(c, app.engine);
        library = new LibraryScreen(c, app.engine, app.lib);
        fly = new FlyCover(c);
        overlay = new Overlay(c);
        addView(backdrop);
        addView(player);
        addView(library);
        addView(fly, new LayoutParams(1, 1));
        addView(overlay);
        library.setVisibility(GONE);
        fly.setVisibility(GONE);
        player.onOpenLibrary = this::openLibrary;
        library.onClose = this::closeLibrary;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        int top = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
        int bottom = in.getInsets(WindowInsets.Type.systemBars()).bottom;
        player.insetTop = top; player.insetBottom = bottom;
        library.insetTop = top; library.insetBottom = bottom;
        player.requestLayout(); library.requestLayout();
        return in;
    }

    public boolean libraryOpen() { return libT > 0.5f; }

    /** Back: a page back in the library, or the library closed; false when there is nowhere to go. */
    public boolean back() {
        if (libraryOpen()) {
            if (!library.back()) closeLibrary();
            return true;
        }
        return false;
    }

    // ---------- player <-> library ----------

    void openLibrary() { animateLib(1, Motion.AA_TO_LIB); }
    void closeLibrary() { animateLib(0, Motion.LIB_TO_AA); }

    private void animateLib(float target, int duration) {
        if (anim != null) anim.cancel();
        // the cover flies from the player's art to the mini player's
        Lib.Track t = App.get().engine.track();
        fly.art = t == null ? null : Covers.get(t.cover, false, b -> { fly.art = b; fly.invalidate(); });
        from.set(player.artRect);
        from.offset(player.getLeft(), player.getTop());
        to.set(library.miniCoverRect());
        to.offset(library.getLeft(), library.getTop());
        int size = Math.round(from.width());
        fly.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        fly.layout(0, 0, size, size);
        fly.setPivotX(0); fly.setPivotY(0);
        library.setVisibility(VISIBLE);
        player.setVisibility(VISIBLE);
        fly.setVisibility(t == null ? GONE : VISIBLE);
        player.pager.cards[1].setVisibility(INVISIBLE);
        library.mini.coverHidden = true; library.mini.invalidate();
        final float start = libT;
        anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration(Motion.ms(duration));
        anim.setInterpolator(Motion.PAGE_EASE);
        anim.addUpdateListener(a -> applyLib((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                anim = null;
                fly.setVisibility(GONE);
                library.mini.coverHidden = false; library.mini.invalidate();
                player.pager.cards[1].setVisibility(App.get().engine.track() == null ? INVISIBLE : VISIBLE);
                if (libT >= 1) player.setVisibility(INVISIBLE); // covered: not drawn at all
                if (libT <= 0) library.setVisibility(GONE);
            }
        });
        anim.start();
    }

    private void applyLib(float t) {
        libT = t;
        library.setAlpha(Math.min(1, t * 1.4f));
        library.setTranslationY((1 - t) * th.px(36));
        player.setAlpha(1 - t);
        // the cover's rectangle, from the player's art to the mini player's
        float s = Theme.lerp(1, to.width() / Math.max(1, from.width()), t);
        fly.setScaleX(s); fly.setScaleY(s);
        fly.setTranslationX(Theme.lerp(from.left, to.left, t));
        fly.setTranslationY(Theme.lerp(from.top, to.top + library.getTranslationY(), t));
        fly.playing = 1 - t;
    }

    // =====================================================================================================
    /** The cover in flight (drawn once at full size; scaled and moved). */
    final class FlyCover extends View {
        Bitmap art;
        float playing;
        private final RectF r = new RectF();
        FlyCover(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) { r.set(0, 0, getWidth(), getHeight()); th.drawCover(c, r, art, 1, null); }
    }

    /** The theme's backdrop: painted once per size (with its glow behind the cover) and then just shown. */
    final class Backdrop extends View {
        private Bitmap bmp;
        Backdrop(Context c) { super(c); }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            if (w > 0 && h > 0 && (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h)) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                float gx = player.artRect.width() > 0 ? player.artRect.centerX() : w / 2f;
                float gy = player.artRect.width() > 0 ? player.artRect.centerY() : h * 0.3f;
                th.paintBackdrop(new Canvas(bmp), w, h, gx, gy);
                bmp.prepareToDraw();
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas c) { if (bmp != null) c.drawBitmap(bmp, 0, 0, null); }
    }

    /** The theme's overlay (film edge and grain for Liminal), stepping on its own clock while visible. */
    final class Overlay extends View {
        private int step;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                step++;
                invalidate();
                if (th.overlayStepMs() > 0 && hasWindowFocus()) postDelayed(this, th.overlayStepMs());
            }
        };
        Overlay(Context c) { super(c); setWillNotDraw(false); }

        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            removeCallbacks(tick);
            if (focused && th.overlayStepMs() > 0) postDelayed(tick, th.overlayStepMs());
        }

        @Override protected void onDraw(Canvas c) { th.drawOverlay(c, getWidth(), getHeight(), step); }
    }
}
