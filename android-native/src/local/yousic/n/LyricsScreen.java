package local.yousic.n;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The lyrics (swipe up on the album art): from a .lrc / .txt beside the song or its tags. Timed lyrics follow
 * the song, the line being sung lit and kept in the middle; tap a line to go there. Swipe down to close.
 */
@SuppressLint("ViewConstructor")
public final class LyricsScreen extends View implements Choreographer.FrameCallback, Engine.Listener {
    private static final class Line { double t; String text; StaticLayout layout; float top; }

    private final Theme th;
    private final Engine engine;
    private final Stage stage;
    int insetTop, reserveBottom;
    private final TextPaint lineP = new TextPaint(Paint.ANTI_ALIAS_FLAG), titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            subP = new TextPaint(Paint.ANTI_ALIAS_FLAG), msgP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Line> lines = new ArrayList<>();
    private boolean synced, loading;
    private String message;
    private Lib.Track track;
    private float scroll, contentH;
    private int current = -1;
    private long manualUntil;
    private final OverScroller scroller;
    private VelocityTracker vt;
    private float downY, lastY, dragDown;
    private boolean dragging, closingDrag;
    private final int slop;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int layoutW = -1;
    private final RectF r = new RectF();

    LyricsScreen(Context c, Engine e, Stage s) {
        super(c);
        th = Theme.get(c);
        engine = e; stage = s;
        scroller = new OverScroller(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        lineP.setTypeface(th.uiBold); lineP.setTextSize(th.px(22)); lineP.setColor(th.text);
        titleP.setTypeface(th.titleFace); titleP.setTextSize(th.px(24)); titleP.setColor(th.text);
        subP.setTypeface(th.uiMedium); subP.setTextSize(th.px(13)); subP.setColor(th.accent);
        msgP.setTypeface(th.uiFace); msgP.setTextSize(th.px(15)); msgP.setColor(th.textDim);
        setClickable(true);
    }

    void open() {
        engine.listen(this);
        load(engine.track());
        Choreographer.getInstance().postFrameCallback(this);
    }

    void close() {
        engine.unlisten(this);
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void onTrack(Lib.Track t) { load(t); }

    private void load(Lib.Track t) {
        track = t;
        lines.clear();
        synced = false;
        message = null;
        scroll = 0; current = -1; layoutW = -1;
        if (t == null) { message = "Nothing playing"; invalidate(); return; }
        loading = true;
        invalidate();
        final Lib.Track want = t;
        new Thread(() -> {
            JSONObject l = null;
            try { l = Lyrics.get(getContext(), App.get().lib.scanner, want.path); } catch (Exception ignored) { }
            final JSONObject res = l;
            main.post(() -> {
                if (track != want) return;
                loading = false;
                if (res == null) {
                    message = "No lyrics for this song.\nPut a .lrc or .txt file with its name beside it, or embed lyrics in its tags.";
                } else if (res.optJSONArray("synced") != null) {
                    JSONArray a = res.optJSONArray("synced");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        Line ln = new Line(); ln.t = o.optDouble("t"); ln.text = o.optString("text"); lines.add(ln);
                    }
                    synced = true;
                } else {
                    for (String s : res.optString("text", "").split("\\r?\\n")) { Line ln = new Line(); ln.t = -1; ln.text = s; lines.add(ln); }
                }
                layoutW = -1;
                invalidate();
            });
        }, "lyrics").start();
    }

    private void layoutLines() {
        int w = getWidth() - Math.round(th.px(48));
        if (w <= 0 || w == layoutW) return;
        layoutW = w;
        float y = 0;
        for (Line ln : lines) {
            String s = ln.text.isEmpty() ? " " : ln.text;
            ln.layout = StaticLayout.Builder.obtain(s, 0, s.length(), lineP, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.05f).build();
            ln.top = y;
            y += ln.layout.getHeight() + th.px(synced ? 18 : 6);
        }
        contentH = y;
    }

    private float areaTop() { return insetTop + th.px(96); }
    private float areaBottom() { return getHeight() - reserveBottom - th.px(8); }

    @Override public void doFrame(long now) {
        if (!isAttachedToWindow()) return;
        if (synced && !lines.isEmpty()) {
            double pos = engine.positionMs() / 1000.0;
            int cur = -1;
            for (int i = 0; i < lines.size(); i++) { if (lines.get(i).t <= pos + 0.15) cur = i; else break; }
            if (cur != current) { current = cur; invalidate(); }
            if (System.currentTimeMillis() > manualUntil && !dragging && scroller.isFinished() && current >= 0 && lines.get(current).layout != null) {
                Line ln = lines.get(current);
                float want = ln.top + ln.layout.getHeight() / 2f - (areaBottom() - areaTop()) * 0.4f;
                want = Math.max(0, Math.min(maxScroll(), want));
                if (Math.abs(want - scroll) > 0.5f) { scroll += (want - scroll) * 0.14f; invalidate(); }
            }
        }
        if (scroller.computeScrollOffset()) { scroll = Math.max(0, Math.min(maxScroll(), scroller.getCurrY())); invalidate(); }
        Choreographer.getInstance().postFrameCallback(this);
    }

    private float maxScroll() { return Math.max(0, contentH - (areaBottom() - areaTop()) * 0.5f); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth();
        th.drawPanel(c, w, getHeight());
        th.drawScrim(c, w, getHeight(), 0.35f);
        float x = th.px(24);
        if (track != null) {
            CharSequence t = TextUtils.ellipsize(track.title, titleP, w - 2 * x, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), x, insetTop + th.px(44), titleP);
            CharSequence a = TextUtils.ellipsize(track.artist, subP, w - 2 * x, TextUtils.TruncateAt.END);
            c.drawText(a, 0, a.length(), x, insetTop + th.px(68), subP);
        }
        if (loading || message != null) {
            float y = getHeight() * 0.42f;
            for (String s : (loading ? "Looking for lyrics..." : message).split("\n")) {
                StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), msgP, w - Math.round(2 * x)).setAlignment(Layout.Alignment.ALIGN_CENTER).build();
                c.save(); c.translate(x, y); l.draw(c); c.restore();
                y += l.getHeight() + th.px(10);
            }
            return;
        }
        layoutLines();
        c.save();
        c.clipRect(0, areaTop(), w, areaBottom());
        float top = areaTop() - scroll;
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (ln.layout == null) continue;
            float y = top + ln.top;
            if (y > areaBottom() || y + ln.layout.getHeight() < areaTop()) continue;
            boolean now = synced && i == current;
            lineP.setColor(!synced ? th.text : now ? th.accentHi : i < current ? Theme.alpha(th.text, 0.45f) : Theme.alpha(th.text, 0.7f));
            c.save();
            c.translate(x, y);
            ln.layout.draw(c);
            c.restore();
        }
        c.restore();
    }

    private int lineAt(float y) {
        float cy = y - areaTop() + scroll;
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (ln.layout != null && cy >= ln.top - th.px(8) && cy < ln.top + ln.layout.getHeight() + th.px(8)) return i;
        }
        return -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.abortAnimation();
                downY = lastY = e.getY(); dragging = false; closingDrag = false; dragDown = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float d = e.getY() - lastY;
                if (!dragging && Math.abs(e.getY() - downY) > slop) {
                    dragging = true;
                    // pulled down from the top (or a short page): close
                    closingDrag = e.getY() > downY && (scroll <= 0 || lines.isEmpty());
                }
                if (dragging) {
                    if (closingDrag) { dragDown = Math.max(0, dragDown + d); setTranslationY(dragDown); }
                    else { scroll = Math.max(0, Math.min(maxScroll(), scroll - d)); manualUntil = System.currentTimeMillis() + 3000; invalidate(); }
                }
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vy = vt.getYVelocity();
                vt.recycle(); vt = null;
                if (closingDrag) {
                    if (dragDown > getHeight() * 0.18f || vy / th.dp > Motion.FLING_DP_S) stage.closeLyrics();
                    else animate().translationY(0).setDuration(Motion.ms(Motion.STATE)).start();
                } else if (dragging) {
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll()));
                    manualUntil = System.currentTimeMillis() + 3000;
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && synced) {
                    int i = lineAt(e.getY());
                    if (i >= 0) { engine.seekTo((long) (lines.get(i).t * 1000)); manualUntil = 0; }
                }
                dragging = false;
                return true;
            }
            default: return true;
        }
    }
}
