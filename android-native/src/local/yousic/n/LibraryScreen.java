package local.yousic.n;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.widget.EdgeEffect;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * The library: a stack of list pages (categories -> albums -> songs) under a header, with the mini player
 * at the bottom. Pages change with a 250 ms accelerate-decelerate transition; swipe right to go
 * back (released: a 500 dp/s fling or past 30 % commits, then it settles at the finger's speed).
 */
@SuppressLint("ViewConstructor")
public final class LibraryScreen extends ViewGroup implements Engine.Listener, Lib.Listener {
    static final class Item {
        String title, sub = "", right = "", cover;
        int icon;           // 0 = cover / none, else a category glyph
        Object data;        // what tapping it opens or plays
        CharSequence eTitle, eSub; int eWidth; // ellipsized for the current width
    }

    static final class Page {
        String title, sub;
        final List<Item> items = new ArrayList<>();
        boolean tracks;     // a list of songs (plays from them)
        List<Lib.Track> trackList;
        String key = "";
        ListPage view;
    }

    final Theme th;
    final Engine engine;
    final Lib lib;
    final Header header;
    final MiniPlayer mini;
    final List<Page> stack = new ArrayList<>();
    int insetTop, insetBottom;
    Runnable onClose;
    private float transition = -1; // 0..1 while pages change
    private ValueAnimator anim;
    private Choreographer.FrameCallback linear;
    private final int slop;
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean swiping, decided;

    LibraryScreen(Context c, Engine e, Lib l) {
        super(c);
        th = Theme.get(c);
        engine = e; lib = l;
        header = new Header(c);
        mini = new MiniPlayer(c);
        addView(header);
        addView(header.back);
        addView(mini);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        header.back.setOnClickListener(v -> back());
        mini.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
        engine.listen(this);
        lib.listen(this);
        stack.add(root());
        addPage(stack.get(0));
    }

    // ---------- pages ----------

    private Page root() {
        Page p = new Page();
        p.title = "Library"; p.sub = Lib.count(lib.tracks.size());
        p.items.add(cat("All songs", Lib.count(lib.tracks.size()), 1, "all"));
        p.items.add(cat("Albums", lib.albums.size() + " albums", 2, "albums"));
        p.items.add(cat("Artists", lib.artists.size() + " artists", 3, "artists"));
        p.items.add(cat("Folders", lib.folders.size() + " folders", 4, "folders"));
        p.items.add(cat("Genres", lib.genres.size() + " genres", 5, "genres"));
        return p;
    }

    private static Item cat(String t, String sub, int icon, String data) {
        Item i = new Item(); i.title = t; i.sub = sub; i.icon = icon; i.data = data; return i;
    }

    private Page groups(String title, List<Lib.Group> groups, String kind) {
        Page p = new Page();
        p.title = title; p.sub = groups.size() + " " + title.toLowerCase();
        for (Lib.Group g : groups) {
            Item i = new Item(); i.title = g.title; i.sub = g.sub; i.cover = g.cover; i.data = g; i.right = String.valueOf(g.tracks.size());
            p.items.add(i);
        }
        p.key = kind;
        return p;
    }

    private Page tracks(String title, List<Lib.Track> list, String key, boolean byAlbum) {
        Page p = new Page();
        p.title = title; p.sub = Lib.count(list.size()); p.tracks = true; p.trackList = list; p.key = key;
        for (Lib.Track t : list) {
            Item i = new Item(); i.title = t.title; i.sub = byAlbum ? t.artist : t.artist + (t.album.isEmpty() ? "" : "  ·  " + t.album);
            i.cover = t.cover; i.data = t; i.right = PlayerScreen.fmt((long) (t.duration * 1000));
            p.items.add(i);
        }
        return p;
    }

    void open(Item it, Page from) {
        Object d = it.data;
        if (from.tracks && d instanceof Lib.Track) {
            engine.playList(from.trackList, from.trackList.indexOf(d), from.title, from.key);
            return;
        }
        Page p;
        if ("all".equals(d)) p = tracks("All songs", lib.tracks, "all", false);
        else if ("albums".equals(d)) p = groups("Albums", lib.albums, "album");
        else if ("artists".equals(d)) p = groups("Artists", lib.artists, "artist");
        else if ("folders".equals(d)) p = groups("Folders", lib.folders, "folder");
        else if ("genres".equals(d)) p = groups("Genres", lib.genres, "genre");
        else if (d instanceof Lib.Group) { Lib.Group g = (Lib.Group) d; p = tracks(g.title, g.tracks, from.key + ":" + g.key, "album".equals(from.key)); }
        else return;
        push(p);
    }

    private void addPage(Page p) {
        p.view = new ListPage(getContext(), p);
        addView(p.view, indexOfChild(header));
        requestLayout();
    }

    void push(Page p) {
        if (transition >= 0) return;
        stack.add(p);
        addPage(p);
        header.set(stack.get(stack.size() - 2), p);
        p.view.setTranslationX(getWidth()); // no frame of it in place before the slide starts
        apply(0);
        runTransition(0, 1, true, 0);
    }

    /** One page back; false at the root. */
    boolean back() {
        if (stack.size() < 2 || transition >= 0) return stack.size() >= 2;
        header.set(stack.get(stack.size() - 2), stack.get(stack.size() - 1));
        runTransition(1, 0, false, 0);
        return true;
    }

    /** progress 1 = the top page fully in; 0 = the page under it */
    private void apply(float t) {
        transition = t;
        int n = stack.size();
        int w = getWidth();
        Page top = stack.get(n - 1), under = n > 1 ? stack.get(n - 2) : null;
        top.view.setTranslationX((1 - t) * w);
        if (under != null) {
            under.view.setVisibility(VISIBLE);
            under.view.setTranslationX(-0.3f * w * t);
            under.view.setAlpha(1 - 0.5f * t);
        }
        header.progress(t);
    }

    private void runTransition(float from, float to, boolean forward, float velocityPerSec) {
        stopTransition();
        final boolean commitPop = to == 0;
        if (Math.abs(velocityPerSec) > 0) {
            // carry on at the finger's speed (linear)
            float min = 1000f / Motion.ms(Motion.PAGE);
            float speed = Math.abs(velocityPerSec);
            speed = to > from ? Math.max(min, Math.min(Motion.SETTLE_MAX, speed)) : Math.max(Motion.SETTLE_BACK_MIN, Math.min(Motion.SETTLE_MAX, speed));
            final float v = to > from ? speed : -speed;
            final long start = System.nanoTime();
            linear = new Choreographer.FrameCallback() {
                @Override public void doFrame(long now) {
                    float t = from + v * (now - start) / 1e9f;
                    boolean done = (v > 0 && t >= to) || (v < 0 && t <= to) || Motion.speed == Motion.SPEED_OFF;
                    apply(done ? to : t);
                    if (done) { linear = null; endTransition(commitPop); }
                    else Choreographer.getInstance().postFrameCallback(this);
                }
            };
            Choreographer.getInstance().postFrameCallback(linear);
            return;
        }
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(Motion.ms(Motion.PAGE));
        anim.setInterpolator(Motion.PAGE_EASE);
        anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { if (anim == a) { anim = null; endTransition(commitPop); } }
        });
        anim.start();
    }

    private void stopTransition() {
        if (anim != null) { ValueAnimator a = anim; anim = null; a.cancel(); }
        if (linear != null) { Choreographer.getInstance().removeFrameCallback(linear); linear = null; }
    }

    private void endTransition(boolean popped) {
        transition = -1;
        int n = stack.size();
        if (popped && n > 1) {
            Page top = stack.remove(n - 1);
            removeView(top.view);
            Page cur = stack.get(n - 2);
            cur.view.setTranslationX(0); cur.view.setAlpha(1);
            header.set(cur, cur);
        } else {
            Page cur = stack.get(n - 1);
            cur.view.setTranslationX(0);
            if (n > 1) stack.get(n - 2).view.setVisibility(INVISIBLE);
            header.set(cur, cur);
        }
        header.progress(1);
    }

    // ---------- swipe right to go back ----------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (stack.size() < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX(); downY = e.getY(); decided = false; swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (decided) return swiping;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    decided = true;
                    swiping = dx > 0 && Math.abs(dx) > Math.abs(dy) * 1.4f && transition < 0;
                    if (swiping) {
                        lastX = e.getX();
                        header.set(stack.get(stack.size() - 2), stack.get(stack.size() - 1));
                        vt = VelocityTracker.obtain();
                    }
                }
                return swiping;
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!swiping) return false;
        vt.addMovement(e);
        float w = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float p = Math.max(0, Math.min(1, (e.getX() - downX) / w));
                apply(1 - p);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity(), vdp = vx / th.dp;
                float progress = Math.max(0, Math.min(1, (e.getX() - downX) / w)); // how far back
                boolean commit = Math.abs(vdp) >= Motion.FLING_DP_S ? vx > 0 : progress > Motion.COMMIT_FRACTION;
                boolean nearEnd = commit ? progress > 1 - Motion.SETTLE_NEAR_END : progress < Motion.SETTLE_NEAR_END;
                float perSec = nearEnd || Math.abs(vdp) < Motion.FLING_DP_S ? 0 : vx / w;
                float t = 1 - progress;
                transition = t;
                if (commit) runTransition(t, 0, false, perSec);
                else runTransition(t, 1, true, perSec);
                vt.recycle(); vt = null;
                swiping = false;
                return true;
            }
            default: return true;
        }
    }

    // ---------- updates ----------

    @Override public void onTrack(Lib.Track t) { mini.invalidate(); for (Page p : stack) p.view.invalidate(); }
    @Override public void onState(boolean playing) { mini.invalidate(); }

    @Override public void onLibrary() {
        // a new scan: rebuild the root (deeper pages keep what they show until reopened)
        Page r = stack.get(0), fresh = root();
        r.items.clear(); r.items.addAll(fresh.items); r.sub = fresh.sub;
        r.view.invalidate();
        if (stack.size() == 1) header.set(r, r);
    }

    // ---------- layout ----------

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }

    @Override
    protected void onLayout(boolean ch, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int hh = insetTop + Math.round(84 * th.dp);
        int mh = insetBottom + Math.round(76 * th.dp);
        place(header, 0, 0, w, hh);
        int bs = Math.round(48 * th.dp), by = insetTop + Math.round(10 * th.dp);
        place(header.back, Math.round(6 * th.dp), by, bs, bs);
        place(mini, 0, h - mh, w, mh);
        for (Page p : stack) {
            place(p.view, 0, 0, w, h);
            p.view.padTop = hh; p.view.padBottom = mh;
        }
        if (transition < 0) header.set(stack.get(stack.size() - 1), stack.get(stack.size() - 1));
    }

    private static void place(View v, int x, int y, int w, int h) {
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        v.layout(x, y, x + w, y + h);
    }

    @Override protected void onDraw(Canvas c) { th.drawPanel(c, getWidth(), getHeight()); }

    /** The mini player's cover, in this view's coordinates (for the album art transition). */
    RectF miniCoverRect() { return new RectF(mini.cover.left, mini.getTop() + mini.cover.top, mini.cover.right, mini.getTop() + mini.cover.bottom); }

    // =====================================================================================================
    /** A list of rows, drawn straight on the canvas (only the visible rows), scrolled and flung with the
     *  platform's stretch at the ends. */
    final class ListPage extends View {
        final Page page;
        float scroll;
        int padTop, padBottom;
        private final OverScroller scroller;
        private final EdgeEffect edgeTop, edgeBottom;
        private VelocityTracker v;
        private float lastY, startY, startX;
        private boolean dragging;
        private int pressed = -1;
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), sub = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                right = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), coverP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Path clip = new Path();
        private final android.graphics.Rect src = new android.graphics.Rect();
        private final int touchSlop;
        final float rowH;

        ListPage(Context c, Page p) {
            super(c);
            page = p;
            scroller = new OverScroller(c);
            edgeTop = new EdgeEffect(c); edgeBottom = new EdgeEffect(c);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            rowH = th.px(p.items.size() > 0 && p.items.get(0).icon != 0 ? 68 : 66);
            title.setTypeface(th.uiMedium); title.setTextSize(th.px(th.rowTitle)); title.setColor(th.text);
            sub.setTypeface(th.uiFace); sub.setTextSize(th.px(th.rowSub)); sub.setColor(th.textDim);
            right.setTypeface(th.numFace); right.setTextSize(th.px(11.5f)); right.setColor(th.textDim); right.setTextAlign(Paint.Align.RIGHT);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { edgeTop.setSize(w, h); edgeBottom.setSize(w, h); }

        float maxScroll() { return Math.max(0, padTop + page.items.size() * rowH + padBottom + th.px(12) - getHeight()); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            float pad = th.px(20);
            float coverS = th.px(46), coverR = th.px(8);
            int first = Math.max(0, (int) ((scroll - padTop) / rowH));
            int last = Math.min(page.items.size() - 1, (int) ((scroll + getHeight() - padTop) / rowH));
            Lib.Track playing = engine.track();
            c.save();
            c.clipRect(0, padTop, w, getHeight() - padBottom); // not under the header or the mini player
            for (int i = first; i <= last; i++) {
                Item it = page.items.get(i);
                float top = padTop + i * rowH - scroll, cy = top + rowH / 2;
                if (i == pressed) {
                    fill.setColor(Theme.alpha(th.accent, 0.10f));
                    c.drawRoundRect(th.px(8), top + th.px(3), w - th.px(8), top + rowH - th.px(3), th.px(12), th.px(12), fill);
                }
                boolean isPlaying = playing != null && it.data == playing;
                float x = pad;
                if (it.icon != 0) {
                    drawIcon(c, it.icon, x + coverS / 2, cy, coverS);
                    x += coverS + th.px(16);
                } else if (it.cover != null || page.tracks || it.data instanceof Lib.Group) {
                    r.set(x, cy - coverS / 2, x + coverS, cy + coverS / 2);
                    Bitmap b = Covers.get(it.cover, true, bm -> invalidate());
                    clip.reset(); clip.addRoundRect(r, coverR, coverR, Path.Direction.CW);
                    c.save(); c.clipPath(clip);
                    if (b != null) {
                        int s = Math.min(b.getWidth(), b.getHeight());
                        src.set((b.getWidth() - s) / 2, (b.getHeight() - s) / 2, (b.getWidth() + s) / 2, (b.getHeight() + s) / 2);
                        c.drawBitmap(b, src, r, coverP);
                    } else {
                        fill.setColor(th.panelHi); c.drawRect(r, fill);
                        fill.setColor(Theme.alpha(th.textDim, 0.45f)); // a note
                        float n = coverS * 0.16f, nx = r.centerX(), ny = r.centerY();
                        c.drawCircle(nx - n * 0.35f, ny + n * 0.7f, n * 0.5f, fill);
                        c.drawRect(nx + n * 0.02f, ny - n * 1.1f, nx + n * 0.22f, ny + n * 0.7f, fill);
                    }
                    c.restore();
                    x += coverS + th.px(14);
                }
                float rightW = it.right.isEmpty() ? 0 : right.measureText(it.right) + th.px(14);
                int avail = (int) (w - x - pad - rightW);
                if (it.eWidth != avail) {
                    it.eTitle = TextUtils.ellipsize(it.title, title, avail, TextUtils.TruncateAt.END);
                    it.eSub = TextUtils.ellipsize(it.sub, sub, avail, TextUtils.TruncateAt.END);
                    it.eWidth = avail;
                }
                title.setColor(isPlaying ? th.accent : th.text);
                c.drawText(it.eTitle, 0, it.eTitle.length(), x, cy - th.px(3), title);
                c.drawText(it.eSub, 0, it.eSub.length(), x, cy - sub.ascent() + th.px(2), sub);
                if (!it.right.isEmpty()) c.drawText(it.right, w - pad, cy + th.px(4), right);
                if (isPlaying) {
                    fill.setColor(th.accentHi);
                    fill.setShadowLayer(th.px(6), 0, 0, th.accentHi);
                    c.drawCircle(x - th.px(7), cy - th.px(8), th.px(2.5f), fill);
                    fill.clearShadowLayer();
                }
            }
            c.restore();
            boolean more = false;
            if (!edgeTop.isFinished()) { more |= edgeTop.draw(c); }
            if (!edgeBottom.isFinished()) {
                c.save(); c.rotate(180, w / 2f, getHeight() / 2f);
                more |= edgeBottom.draw(c);
                c.restore();
            }
            if (more) postInvalidateOnAnimation();
        }

        private void drawIcon(Canvas c, int icon, float cx, float cy, float s) {
            fill.setColor(Theme.alpha(th.accent, 0.16f));
            c.drawRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, th.px(12), th.px(12), fill);
            fill.setColor(th.accent);
            fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(th.px(1.8f)); fill.setStrokeCap(Paint.Cap.ROUND);
            float a = s * 0.22f;
            switch (icon) {
                case 1: // songs: a note
                    c.drawLine(cx + a * 0.4f, cy - a, cx + a * 0.4f, cy + a * 0.6f, fill);
                    fill.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy + a * 0.65f, a * 0.42f, fill); break;
                case 2: // albums: a record
                    c.drawCircle(cx, cy, a * 1.1f, fill); fill.setStyle(Paint.Style.FILL); c.drawCircle(cx, cy, a * 0.25f, fill); break;
                case 3: // artists: a person
                    c.drawCircle(cx, cy - a * 0.45f, a * 0.45f, fill);
                    c.drawArc(cx - a * 0.9f, cy + a * 0.1f, cx + a * 0.9f, cy + a * 1.6f, 200, 140, false, fill); break;
                case 4: // folders
                    r.set(cx - a * 1.1f, cy - a * 0.7f, cx + a * 1.1f, cy + a * 0.8f);
                    c.drawRoundRect(r, a * 0.25f, a * 0.25f, fill);
                    c.drawLine(cx - a * 1.1f, cy - a * 0.3f, cx + a * 1.1f, cy - a * 0.3f, fill); break;
                default: // genres: waves
                    for (int k = -1; k <= 1; k++) c.drawLine(cx - a, cy + k * a * 0.6f, cx + a * (k == 0 ? 0.5f : 1), cy + k * a * 0.6f, fill);
            }
            fill.setStyle(Paint.Style.FILL);
        }

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                float y = scroller.getCurrY();
                float max = maxScroll();
                if (y < 0 && scroll >= 0 && edgeTop.isFinished()) edgeTop.onAbsorb((int) scroller.getCurrVelocity());
                if (y > max && scroll <= max && edgeBottom.isFinished()) edgeBottom.onAbsorb((int) scroller.getCurrVelocity());
                scroll = Math.max(0, Math.min(max, y));
                if (y < 0 || y > max) scroller.abortAnimation();
                postInvalidateOnAnimation();
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (v == null) v = VelocityTracker.obtain();
            v.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!scroller.isFinished()) { scroller.abortAnimation(); dragging = true; }
                    lastY = startY = e.getY(); startX = e.getX();
                    if (!dragging) { pressed = rowAt(e.getY()); postDelayed(pressShow, 60); }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float y = e.getY();
                    if (!dragging && Math.abs(y - startY) > touchSlop) { dragging = true; pressed = -1; getParent().requestDisallowInterceptTouchEvent(true); invalidate(); }
                    if (dragging) {
                        float d = lastY - y;
                        float max = maxScroll();
                        float ns = scroll + d;
                        // past the ends: stretch (Android 12+), released it springs back
                        if (ns < 0) { edgeTop.onPullDistance(-ns / getHeight(), e.getX() / getWidth()); ns = 0; }
                        else if (ns > max) { edgeBottom.onPullDistance((ns - max) / getHeight(), 1 - e.getX() / getWidth()); ns = max; }
                        else {
                            if (!edgeTop.isFinished() && d > 0) edgeTop.onPullDistance(-d / getHeight(), 0.5f);
                            if (!edgeBottom.isFinished() && d < 0) edgeBottom.onPullDistance(d / getHeight(), 0.5f);
                        }
                        scroll = ns;
                        invalidate();
                    }
                    lastY = y;
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    removeCallbacks(pressShow);
                    if (dragging) {
                        v.computeCurrentVelocity(1000);
                        float vy = -v.getYVelocity();
                        edgeTop.onRelease(); edgeBottom.onRelease();
                        scroller.fling(0, (int) scroll, 0, (int) vy, 0, 0, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
                        postInvalidateOnAnimation();
                    } else {
                        int row = rowAt(e.getY());
                        if (row >= 0) {
                            pressed = row; invalidate();
                            final Item it = page.items.get(row);
                            postDelayed(() -> { pressed = -1; invalidate(); }, Motion.SELECTOR_FADE);
                            open(it, page);
                        }
                    }
                    dragging = false;
                    v.recycle(); v = null;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(pressShow);
                    pressed = -1; dragging = false;
                    edgeTop.onRelease(); edgeBottom.onRelease();
                    if (v != null) { v.recycle(); v = null; }
                    invalidate();
                    return true;
                default: return true;
            }
        }

        private final Runnable pressShow = this::invalidate;

        private int rowAt(float y) {
            int i = (int) ((y + scroll - padTop) / rowH);
            return y < padTop || i < 0 || i >= page.items.size() ? -1 : i;
        }
    }

    // =====================================================================================================
    /** The header: back, the page's title and what it holds. During a page change the two titles cross. */
    final class Header extends View {
        final PlayerScreen.GlyphButton back;
        private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), subP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Page from, to;
        private float t = 1;

        Header(Context c) {
            super(c);
            back = new PlayerScreen.GlyphButton(c, PlayerScreen.GlyphButton.BACK, false);
            titleP.setTypeface(th.titleFace); titleP.setTextSize(th.px(th.headerSize)); titleP.setColor(th.text);
            titleP.setShadowLayer(th.px(12), 0, 0, Theme.alpha(th.accent, 0.45f));
            subP.setTypeface(th.uiFace); subP.setTextSize(th.px(12)); subP.setColor(th.textDim);
        }

        /** a = the page underneath, b = the top page (progress 1 shows b) */
        void set(Page a, Page b) { from = a; to = b; invalidate(); }
        void progress(float p) { t = p; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            // a soft dusk behind the header, so the list scrolls under it
            th.drawPanel(c, getWidth(), getHeight());
            boolean showBack = stack.size() > 1;
            back.setAlpha(showBack ? 1 : 0);
            back.setClickable(showBack);
            float x = th.px(showBack ? 56 : 20), base = insetTop + th.px(44);
            if (from != null && from != to && t < 1 && t >= 0) {
                // the old title leaves to the left, the new one arrives from the right
                drawTitle(c, from, x - th.px(40) * t, base, 1 - t);
                drawTitle(c, to, x + th.px(40) * (1 - t), base, t);
            } else if (to != null) {
                drawTitle(c, to, x, base, 1);
            }
        }

        private void drawTitle(Canvas c, Page p, float x, float y, float a) {
            int al = Math.round(255 * Math.max(0, Math.min(1, a)));
            titleP.setAlpha(al); subP.setAlpha(al);
            CharSequence t1 = TextUtils.ellipsize(p.title, titleP, getWidth() - x - th.px(20), TextUtils.TruncateAt.END);
            c.drawText(t1, 0, t1.length(), x, y, titleP);
            c.drawText(p.sub == null ? "" : p.sub, x, y + th.px(20), subP);
        }
    }

    // =====================================================================================================
    /** The mini player: the song playing, play / pause, its progress along the top edge; tap for the player. */
    final class MiniPlayer extends View implements Choreographer.FrameCallback {
        final RectF cover = new RectF();
        private final TextPaint t1 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF playR = new RectF();
        boolean coverHidden;
        private long lastPx = -1;

        MiniPlayer(Context c) {
            super(c);
            setClickable(true);
            t1.setTypeface(th.titleFace); t1.setTextSize(th.px(18)); t1.setColor(th.text);
            t2.setTypeface(th.uiMedium); t2.setTextSize(th.px(12.5f)); t2.setColor(th.accent);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = th.px(48), y = th.px(14);
            cover.set(th.px(16), y, th.px(16) + s, y + s);
            playR.set(w - th.px(70), y - th.px(4), w - th.px(14), y + s + th.px(4));
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Choreographer.getInstance().postFrameCallback(this); }
        @Override protected void onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow(); }

        @Override public void doFrame(long now) {
            if (engine.isPlaying() && getWidth() > 0) {
                long d = engine.durationMs();
                long px = d > 0 ? engine.positionMs() * getWidth() / d : 0;
                if (px != lastPx) { lastPx = px; invalidate(); }
            }
            if (isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            th.drawPanel(c, w, getHeight());
            fill.setColor(th.line);
            c.drawRect(0, 0, w, th.px(1), fill);
            Lib.Track t = engine.track();
            long d = engine.durationMs();
            float p = d > 0 ? engine.positionMs() / (float) d : 0;
            fill.setColor(th.accentHi);
            c.drawRect(0, 0, w * p, th.px(2), fill);
            if (!coverHidden) {
                Bitmap b = t == null ? null : Covers.get(t.cover, false, bm -> invalidate());
                th.drawCover(c, cover, b, 0, null);
            }
            float x = cover.right + th.px(14), avail = playR.left - x - th.px(8);
            CharSequence a = TextUtils.ellipsize(t == null ? "Nothing playing" : t.title, t1, avail, TextUtils.TruncateAt.END);
            CharSequence b2 = TextUtils.ellipsize(t == null ? "" : t.artist, t2, avail, TextUtils.TruncateAt.END);
            c.drawText(a, 0, a.length(), x, cover.centerY() - th.px(3), t1);
            c.drawText(b2, 0, b2.length(), x, cover.centerY() + th.px(15), t2);
            // play / pause
            th.drawPlayGlyph(c, playR.centerX(), playR.centerY(), th.px(22), engine.isPlaying() ? 1 : 0, th.text);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP && playR.contains(e.getX(), e.getY())) {
                setPressed(false);
                engine.toggle();
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
