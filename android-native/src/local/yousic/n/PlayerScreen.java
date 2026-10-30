package local.yousic.n;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * The player: the album art (swipe it for the previous / next song), the song, the seek bar and the
 * controls. Every part is its own view, so a change redraws only that part; the art moves by translation
 * alone (its picture is recorded once).
 */
@SuppressLint("ViewConstructor")
public final class PlayerScreen extends ViewGroup implements Engine.Listener {
    final Theme th;
    final Engine engine;
    final ArtPager pager;
    final InfoView info;
    final SeekView seek;
    final PlayView play;
    final GlyphButton prev, next, shuffle, repeat, libraryBtn, menuBtn;
    final SourceView sourceView;
    int insetTop, insetBottom;
    /** the art's rectangle in this view (for the library transition) */
    final RectF artRect = new RectF();
    Runnable onOpenLibrary;

    PlayerScreen(Context c, Engine e) {
        super(c);
        setClipChildren(false); // the cover's glow reaches past the pager
        th = Theme.get(c);
        engine = e;
        pager = new ArtPager(c);
        info = new InfoView(c);
        seek = new SeekView(c);
        play = new PlayView(c);
        prev = new GlyphButton(c, GlyphButton.PREV, false); next = new GlyphButton(c, GlyphButton.NEXT, false);
        shuffle = new GlyphButton(c, GlyphButton.SHUFFLE, true); repeat = new GlyphButton(c, GlyphButton.REPEAT, true);
        libraryBtn = new GlyphButton(c, GlyphButton.LIBRARY, false); menuBtn = new GlyphButton(c, GlyphButton.MENU, false);
        sourceView = new SourceView(c);
        for (View v : new View[]{ pager, info, seek, play, prev, next, shuffle, repeat, libraryBtn, sourceView, menuBtn }) addView(v);
        play.setOnClickListener(v -> engine.toggle());
        prev.setOnClickListener(v -> pager.animateStep(-1));
        next.setOnClickListener(v -> pager.animateStep(1));
        shuffle.setOnClickListener(v -> engine.setShuffle(!engine.shuffle));
        repeat.setOnClickListener(v -> engine.cycleRepeat());
        libraryBtn.setOnClickListener(v -> { if (onOpenLibrary != null) onOpenLibrary.run(); });
        engine.listen(this);
        onTrack(engine.track());
        onModes();
        play.setPlaying(engine.isPlaying(), false);
    }

    @Override public void onTrack(Lib.Track t) {
        pager.bind(false);
        info.set(t, true);
        seek.setTrack(t);
        sourceView.invalidate();
    }

    @Override public void onState(boolean playing) {
        play.setPlaying(playing, true);
        seek.ticking(playing);
    }

    @Override public void onModes() {
        shuffle.on = engine.shuffle; shuffle.invalidate();
        repeat.on = engine.repeat != Engine.REPEAT_OFF; repeat.mode = engine.repeat; repeat.invalidate();
        pager.bind(false);
        sourceView.invalidate();
    }

    // ---------- layout (phone, portrait): top bar, art, song, seek, controls ----------

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        float dp = th.dp;
        int pad = Math.round(28 * dp);
        int top = insetTop + Math.round(6 * dp);
        int bar = Math.round(48 * dp);
        place(libraryBtn, Math.round(12 * dp), top, bar, bar);
        place(menuBtn, w - Math.round(12 * dp) - bar, top, bar, bar);
        place(sourceView, Math.round(12 * dp) + bar, top, w - 2 * (Math.round(12 * dp) + bar), bar);
        int controlsH = Math.round(112 * dp);
        int bottom = h - insetBottom - Math.round(18 * dp);
        int controlsTop = bottom - controlsH;
        int seekH = Math.round(76 * dp);
        int seekTop = controlsTop - seekH - Math.round(4 * dp);
        int infoH = Math.round(96 * dp);
        int infoTop = seekTop - infoH;
        int artTop = top + bar + Math.round(14 * dp);
        int artSize = Math.min(w - 2 * pad, infoTop - artTop - Math.round(14 * dp));
        int artLeft = (w - artSize) / 2;
        artRect.set(artLeft, artTop, artLeft + artSize, artTop + artSize);
        place(pager, 0, artTop - Math.round(30 * dp), w, artSize + Math.round(60 * dp));
        pager.setCard(artLeft, Math.round(30 * dp), artSize);
        place(info, pad, infoTop, w - 2 * pad, infoH);
        place(seek, pad - Math.round(6 * dp), seekTop, w - 2 * pad + Math.round(12 * dp), seekH);
        int cy = controlsTop + controlsH / 2;
        int ps = Math.round(112 * dp), sb = Math.round(56 * dp), tb = Math.round(48 * dp);
        place(play, w / 2 - ps / 2, cy - ps / 2, ps, ps);
        int gap = Math.round(74 * dp);
        place(prev, w / 2 - gap - sb / 2 - Math.round(12 * dp), cy - sb / 2, sb, sb);
        place(next, w / 2 + gap - sb / 2 + Math.round(12 * dp), cy - sb / 2, sb, sb);
        place(shuffle, pad - Math.round(10 * dp), cy - tb / 2, tb, tb);
        place(repeat, w - pad + Math.round(10 * dp) - tb, cy - tb / 2, tb, tb);
    }

    private static void place(View v, int x, int y, int w, int h) {
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        v.layout(x, y, x + w, y + h);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        // the dimmer air behind the song and the seek bar keeps their text readable over the glow
        th.drawInfoShade(c, new RectF(0, info.getTop() - 44 * th.dp, getWidth(), seek.getBottom() + 20 * th.dp));
        super.dispatchDraw(c);
    }

    // =====================================================================================================
    /** The album art: the current cover with the previous and next on either side; drag to change song. */
    final class ArtPager extends ViewGroup {
        final CoverCard[] cards = { new CoverCard(getContext()), new CoverCard(getContext()), new CoverCard(getContext()) };
        int cardLeft, cardTop, cardSize, spacing;
        float offset; // px the cards are dragged by (negative: towards the next song)
        private VelocityTracker vt;
        private float downX, downY, lastX;
        private boolean dragging;
        private final int slop;
        private ValueAnimator anim;
        private Choreographer.FrameCallback linear;
        private int pendingDelta;

        ArtPager(Context c) {
            super(c);
            for (CoverCard k : cards) addView(k);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClipChildren(false);
        }

        void setCard(int left, int top, int size) {
            cardLeft = left; cardTop = top; cardSize = size;
            spacing = size + Math.round(24 * th.dp);
            for (int i = 0; i < 3; i++) place(cards[i], left, top, size, size);
            apply();
        }

        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) { }

        /** Puts the current song's cover in the middle and its neighbours either side. */
        void bind(boolean force) {
            if (anim != null || linear != null || dragging || pendingDelta != 0) return;
            cards[0].set(engine.peek(-1));
            cards[1].set(engine.track());
            cards[2].set(engine.peek(1));
            cards[1].playing = engine.isPlaying() ? 1 : 0;
            offset = 0;
            apply();
        }

        private void apply() {
            for (int i = 0; i < 3; i++) {
                float x = (i - 1) * spacing + offset;
                cards[i].setTranslationX(x);
                // the neighbours sit a little smaller and dimmer
                float d = Math.min(1, Math.abs(x) / (float) Math.max(1, spacing));
                float s = 1 - 0.08f * d;
                cards[i].setScaleX(s); cards[i].setScaleY(s);
                cards[i].setAlpha(1 - 0.35f * d);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = e.getX(); downY = e.getY();
                    if (anim != null || linear != null) { stopSettle(); dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(e.getY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        offset += e.getX() - lastX;
                        // no neighbour that way: resist
                        if ((offset > 0 && engine.peek(-1) == null) || (offset < 0 && engine.peek(1) == null)) offset *= 0.92f;
                        apply();
                    }
                    lastX = e.getX();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        release(vt.getXVelocity());
                    }
                    dragging = false;
                    vt.recycle(); vt = null;
                    return true;
                default:
                    return true;
            }
        }

        /** A fling over 500 dp/s decides; otherwise past 30 %. */
        private void release(float vx) {
            float progress = Math.abs(offset) / spacing;
            int dir = offset < 0 ? 1 : -1; // 1 = next song
            float vdp = vx / th.dp;
            boolean commit;
            if (Math.abs(vdp) >= Motion.FLING_DP_S) commit = (vx < 0 && dir == 1) || (vx > 0 && dir == -1);
            else commit = progress > Motion.COMMIT_FRACTION;
            if (engine.peek(dir) == null) commit = false;
            boolean useVelocity = commit ? progress <= 1 - Motion.SETTLE_NEAR_END : progress >= Motion.SETTLE_NEAR_END;
            if (commit) {
                pendingDelta = dir;
                engine.step(dir, true);
            }
            float target = commit ? -dir * spacing : 0;
            if (useVelocity && Math.abs(vdp) >= Motion.FLING_DP_S) settleLinear(target, vx);
            else settleTimed(target);
        }

        /** Carries on at the finger's speed (linear), kept between one transition per duration and 8 a second. */
        private void settleLinear(float target, float vx) {
            float dist = target - offset;
            float perSec = Math.abs(vx) / spacing;
            float min = 1000f / Motion.ms(Motion.PAGE);
            perSec = Math.max(min, Math.min(Motion.SETTLE_MAX, perSec));
            final float speed = perSec * spacing * Math.signum(dist == 0 ? 1 : dist);
            final float from = offset;
            final long start = System.nanoTime();
            linear = new Choreographer.FrameCallback() {
                @Override public void doFrame(long now) {
                    float t = (now - start) / 1e9f;
                    float o = from + speed * t;
                    boolean done = (speed > 0 && o >= target) || (speed < 0 && o <= target) || Motion.speed == Motion.SPEED_OFF;
                    offset = done ? target : o;
                    apply();
                    if (done) { linear = null; finishSettle(); }
                    else Choreographer.getInstance().postFrameCallback(this);
                }
            };
            Choreographer.getInstance().postFrameCallback(linear);
        }

        private void settleTimed(float target) {
            final float from = offset;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(Motion.ms(Motion.PAGE));
            anim.setInterpolator(Motion.PAGE_EASE);
            anim.addUpdateListener(a -> { offset = from + (target - from) * (float) a.getAnimatedValue(); apply(); });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { if (anim == a) { anim = null; finishSettle(); } }
            });
            anim.start();
        }

        private void stopSettle() {
            if (anim != null) { ValueAnimator a = anim; anim = null; a.cancel(); }
            if (linear != null) { Choreographer.getInstance().removeFrameCallback(linear); linear = null; }
            if (pendingDelta != 0) {
                // the song already changed: the dragged cards now belong one place over
                offset += pendingDelta * spacing;
                pendingDelta = 0;
                cards[0].set(engine.peek(-1)); cards[1].set(engine.track()); cards[2].set(engine.peek(1));
                apply();
            }
        }

        private void finishSettle() {
            pendingDelta = 0;
            bind(true);
        }

        /** The prev / next buttons: the covers slide over as if swiped. */
        void animateStep(int dir) {
            if (engine.peek(dir) == null) { engine.step(dir, true); return; }
            stopSettle();
            pendingDelta = dir;
            engine.step(dir, true);
            settleTimed(-dir * spacing);
        }
    }

    // =====================================================================================================
    /** One cover, drawn once (moved by translation). */
    final class CoverCard extends View {
        Lib.Track track;
        Bitmap art;
        float playing;

        CoverCard(Context c) { super(c); setLayerType(LAYER_TYPE_NONE, null); }

        void set(Lib.Track t) {
            if (t == track) return;
            track = t;
            art = null;
            if (t != null && t.cover != null) {
                final Lib.Track want = t;
                art = Covers.get(t.cover, false, b -> { if (track == want) { art = b; invalidate(); } });
            }
            setVisibility(t == null ? INVISIBLE : VISIBLE);
            invalidate();
        }

        private final RectF r = new RectF();

        @Override protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            th.drawCover(c, r, art, playing, null);
        }
    }

    // =====================================================================================================
    /** The song: title, artist, album. A new song drifts up out of the haze. */
    final class InfoView extends View {
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), artist = new TextPaint(Paint.ANTI_ALIAS_FLAG), album = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private CharSequence t1 = "", t2 = "", t3 = "";
        private Lib.Track track;

        InfoView(Context c) {
            super(c);
            title.setTypeface(th.titleFace); title.setTextSize(th.px(th.titleSize)); title.setColor(th.text);
            title.setShadowLayer(th.px(14), 0, 0, Theme.alpha(th.accent, 0.55f));
            artist.setTypeface(th.uiMedium); artist.setTextSize(th.px(th.artistSize)); artist.setColor(th.accent);
            artist.setLetterSpacing(0.02f);
            album.setTypeface(th.uiFace); album.setTextSize(th.px(th.albumSize)); album.setColor(th.textDim);
        }

        void set(Lib.Track t, boolean animate) {
            boolean changed = t != track;
            track = t;
            layoutText();
            invalidate();
            if (changed && animate && t != null && Motion.speed != Motion.SPEED_OFF) {
                setAlpha(0); setTranslationY(th.px(8));
                animate().alpha(1).translationY(0).setDuration(Motion.ms(800)).setInterpolator(new PathInterpolator(0.25f, 0.8f, 0.3f, 1f)).start();
            }
        }

        private void layoutText() {
            int w = getWidth();
            if (w == 0) return;
            if (track == null) { t1 = "YouSic"; t2 = "Tap play to start"; t3 = ""; return; }
            t1 = TextUtils.ellipsize(track.title, title, w, TextUtils.TruncateAt.END);
            t2 = TextUtils.ellipsize(track.artist.isEmpty() ? "Unknown artist" : track.artist, artist, w, TextUtils.TruncateAt.END);
            t3 = TextUtils.ellipsize(track.album, album, w, TextUtils.TruncateAt.END);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { layoutText(); }

        @Override protected void onDraw(Canvas c) {
            float y = -title.ascent() + th.px(4);
            c.drawText(t1, 0, t1.length(), 0, y, title);
            y += title.descent() + th.px(6) - artist.ascent();
            c.drawText(t2, 0, t2.length(), 0, y, artist);
            y += artist.descent() + th.px(3) - album.ascent();
            c.drawText(t3, 0, t3.length(), 0, y, album);
        }
    }

    // =====================================================================================================
    /** The seek bar with the times. Redrawn only when the playhead has moved a pixel; drag to seek. */
    final class SeekView extends View implements Choreographer.FrameCallback {
        private float[] peaks = new float[72];
        private float progress, dragProgress = -1;
        private final TextPaint times = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private boolean ticking;
        private long lastDrawnPx = -1;
        private String el = "0:00", rem = "-0:00";

        SeekView(Context c) {
            super(c);
            times.setTypeface(th.stampFace); times.setTextSize(th.px(th.timeSize)); times.setColor(th.stamp);
            times.setShadowLayer(th.px(5), 0, 0, th.ink);
            times.setLetterSpacing(0.04f);
        }

        void setTrack(Lib.Track t) {
            // a stand-in waveform from the song's id until the real one is decoded
            java.util.Random r = new java.util.Random(t == null ? 1 : t.id.hashCode());
            float v = 0.5f;
            for (int i = 0; i < peaks.length; i++) { v = Math.max(0.15f, Math.min(1, v + (r.nextFloat() - 0.5f) * 0.5f)); peaks[i] = v; }
            update(true);
        }

        void ticking(boolean on) {
            ticking = on;
            Choreographer.getInstance().removeFrameCallback(this);
            if (on && isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this);
            update(true);
        }

        @Override public void doFrame(long now) {
            update(false);
            if (ticking && isAttachedToWindow() && getVisibility() == VISIBLE) Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); ticking(engine.isPlaying()); }
        @Override protected void onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow(); }

        /** Redraws only when something visible moved: the playhead by a pixel, or the time by a second. */
        void update(boolean force) {
            long dur = engine.durationMs(), pos = engine.positionMs();
            progress = dur > 0 ? Math.min(1f, pos / (float) dur) : 0;
            long px = Math.round(progress * getWidth());
            String e = fmt(pos), r = "-" + fmt(Math.max(0, dur - pos));
            if (force || px != lastDrawnPx || !e.equals(el)) {
                lastDrawnPx = px; el = e; rem = r;
                invalidate();
                play.setProgress(progress);
            }
        }

        @Override protected void onDraw(Canvas c) {
            bar.set(th.px(6), 0, getWidth() - th.px(6), getHeight() - th.px(22));
            float p = dragProgress >= 0 ? dragProgress : progress;
            th.drawSeek(c, bar, peaks, p, dragProgress >= 0);
            float y = getHeight() - th.px(6);
            c.drawText(el, th.px(6), y, times);
            c.drawText(rem, getWidth() - th.px(6) - times.measureText(rem), y, times);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            float p = Math.max(0, Math.min(1, (e.getX() - th.px(6)) / (getWidth() - th.px(12))));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: getParent().requestDisallowInterceptTouchEvent(true); dragProgress = p; invalidate(); return true;
                case MotionEvent.ACTION_MOVE: dragProgress = p; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    engine.seekTo((long) (p * engine.durationMs()));
                    dragProgress = -1; update(true); return true;
                case MotionEvent.ACTION_CANCEL: dragProgress = -1; invalidate(); return true;
                default: return true;
            }
        }
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    // =====================================================================================================
    /** Play / pause: the theme's play button, morphing between the two in 320 ms. */
    final class PlayView extends View {
        private float morph, playing, progress;
        private ValueAnimator anim;

        PlayView(Context c) { super(c); setClickable(true); }

        void setPlaying(boolean p, boolean animate) {
            float to = p ? 1 : 0;
            if (anim != null) anim.cancel();
            if (!animate || Motion.speed == Motion.SPEED_OFF) { morph = to; playing = to; invalidate(); return; }
            final float m0 = morph, p0 = playing;
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(Motion.ms(Motion.PLAY_STATE));
            anim.setInterpolator(Motion.PAGE_EASE);
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                morph = m0 + (to - m0) * t; playing = p0 + (to - p0) * t;
                invalidate();
            });
            anim.start();
        }

        void setProgress(float p) {
            if (Math.abs(p - progress) * 360 < 0.6f && p != 0) return; // under a pixel of the ring
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float size = th.px(74) * Math.min(1f, getWidth() / th.px(112));
            float press = isPressed() ? th.px(1) : 0;
            th.drawPlay(c, getWidth() / 2f, getHeight() / 2f + press, size, morph, playing, progress);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    // =====================================================================================================
    /** A small button drawn as a line glyph. */
    static final class GlyphButton extends View {
        static final int PREV = 0, NEXT = 1, SHUFFLE = 2, REPEAT = 3, LIBRARY = 4, MENU = 5, BACK = 6;
        final int kind;
        final boolean toggle;
        boolean on;
        int mode;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Theme th;

        GlyphButton(Context c, int kind, boolean toggle) {
            super(c);
            this.kind = kind; this.toggle = toggle;
            th = Theme.get(c);
            setClickable(true);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, s = th.px(kind == PREV || kind == NEXT ? 26 : 22);
            int col = toggle && !on ? Theme.alpha(th.text, 0.6f) : toggle ? th.accentHi : th.text;
            if (isPressed()) col = th.accentHi;
            p.setColor(col);
            path.reset();
            switch (kind) {
                case PREV: case NEXT: {
                    float d = kind == NEXT ? 1 : -1;
                    p.setStyle(Paint.Style.FILL);
                    float h = s * 0.62f, w = s * 0.5f;
                    path.moveTo(cx - d * w * 0.45f, cy - h / 2); path.lineTo(cx + d * w * 0.55f, cy); path.lineTo(cx - d * w * 0.45f, cy + h / 2); path.close();
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(2.4f));
                    c.drawLine(cx + d * w * 0.7f, cy - h / 2, cx + d * w * 0.7f, cy + h / 2, p);
                    break;
                }
                case SHUFFLE: {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(1.8f));
                    float a = s * 0.42f;
                    path.moveTo(cx - a, cy - a * 0.6f); path.cubicTo(cx, cy - a * 0.6f, cx, cy + a * 0.6f, cx + a, cy + a * 0.6f);
                    path.moveTo(cx - a, cy + a * 0.6f); path.cubicTo(cx, cy + a * 0.6f, cx, cy - a * 0.6f, cx + a, cy - a * 0.6f);
                    path.moveTo(cx + a * 0.62f, cy - a * 0.95f); path.lineTo(cx + a, cy - a * 0.6f); path.lineTo(cx + a * 0.62f, cy - a * 0.25f);
                    path.moveTo(cx + a * 0.62f, cy + a * 0.25f); path.lineTo(cx + a, cy + a * 0.6f); path.lineTo(cx + a * 0.62f, cy + a * 0.95f);
                    c.drawPath(path, p);
                    dot(c, cx, cy + s * 0.75f);
                    break;
                }
                case REPEAT: {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(1.8f));
                    float a = s * 0.42f, r = a * 0.4f;
                    RectF box = new RectF(cx - a, cy - a * 0.55f, cx + a, cy + a * 0.55f);
                    c.drawRoundRect(box, r, r, p);
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(cx + a * 0.05f, cy - a * 0.95f); path.lineTo(cx + a * 0.45f, cy - a * 0.55f); path.lineTo(cx + a * 0.05f, cy - a * 0.15f); path.close();
                    c.drawPath(path, p);
                    if (mode == Engine.REPEAT_ONE) {
                        p.setTextSize(th.px(9)); p.setTypeface(th.uiBold); p.setTextAlign(Paint.Align.CENTER);
                        c.drawText("1", cx, cy + th.px(3.2f), p);
                    }
                    dot(c, cx, cy + s * 0.75f);
                    break;
                }
                case LIBRARY: {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(2));
                    float a = s * 0.42f;
                    for (int i = -1; i <= 1; i++) c.drawLine(cx - a, cy + i * a * 0.55f, cx + a * (i == 1 ? 0.2f : 1), cy + i * a * 0.55f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + a * 0.75f, cy + a * 0.62f, a * 0.28f, p);
                    break;
                }
                case MENU: {
                    p.setStyle(Paint.Style.FILL);
                    for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * s * 0.3f, th.px(2.2f), p);
                    break;
                }
                case BACK: {
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(th.px(2.2f));
                    float a = s * 0.3f;
                    path.moveTo(cx + a * 0.5f, cy - a); path.lineTo(cx - a * 0.5f, cy); path.lineTo(cx + a * 0.5f, cy + a);
                    c.drawPath(path, p);
                    break;
                }
                default: break;
            }
        }

        /** "on": a small glowing dot under the glyph */
        private void dot(Canvas c, float x, float y) {
            if (!on) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(th.accentHi);
            p.setShadowLayer(th.px(6), 0, 0, th.accentHi);
            c.drawCircle(x, y, th.px(2.5f), p);
            p.clearShadowLayer();
        }
    }

    // =====================================================================================================
    /** "Playing from": where the song came from, and its place there. */
    final class SourceView extends View {
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), name = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        SourceView(Context c) {
            super(c);
            label.setTypeface(th.uiMedium); label.setTextSize(th.px(10)); label.setLetterSpacing(0.22f); label.setColor(th.textDim); label.setTextAlign(Paint.Align.CENTER);
            name.setTypeface(th.uiMedium); name.setTextSize(th.px(14)); name.setColor(th.text); name.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            c.drawText("PLAYING FROM", cx, getHeight() / 2f - th.px(4), label);
            String src = engine.source().isEmpty() ? "Library" : engine.source();
            if (engine.size() > 0) src += "  ·  " + (engine.index() + 1) + " / " + engine.size();
            CharSequence s = TextUtils.ellipsize(src, name, getWidth(), TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), cx, getHeight() / 2f + th.px(14), name);
        }
    }
}
