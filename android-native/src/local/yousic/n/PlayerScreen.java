package local.yousic.n;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The player: the album art across the top (its buttons on it: like, dislike, the queue,
 * the song's menu), the title and artist on chips, a row of buttons (visualization, sleep timer, repeat, shuffle),
 * the transport (previous / next category, previous / next song, play), the seek bar with the times, and the
 * format line. The art: swipe sideways for the previous / next song, further for the previous / next category,
 * down (or tap) for the list the song plays from, up for the lyrics.
 */
@SuppressLint("ViewConstructor")
public final class PlayerScreen extends ViewGroup implements Engine.Listener {
    final Theme th;
    final Engine engine;
    final Stage stage;
    final VisView vis;
    final ArtPager pager;
    final Ui.Btn like, dislike, listBtn, moreBtn;
    final Ui.TextChip titleChip, artistChip;
    /** the row of four buttons (long press between them to choose them) */
    final Ui.Btn[] slots = new Ui.Btn[4];
    final int[] slotType = new int[4];
    private final View slotGap;
    static final int S_NONE = 0, S_VIS = 1, S_TIMER = 2, S_REPEAT = 3, S_SHUFFLE = 4, S_LYRICS = 5, S_EQ = 6, S_QUEUE = 7, S_SEARCH = 8, S_INFO = 9;
    static final String[] SLOT_NAMES = { "Empty", "Visualization", "Sleep timer", "Repeat", "Shuffle", "Lyrics", "Equalizer", "Queue", "Search", "Audio info" };
    static final int[] SLOT_ICONS = { 0, Icons.VIS, Icons.TIMER, Icons.REPEAT, Icons.SHUFFLE, Icons.LYRICS, Icons.EQ, Icons.QUEUE, Icons.SEARCH, Icons.INFO };
    final Ui.Btn prevCat, prev, next, nextCat;
    final PlayView play;
    final SeekView seek;
    final MetaView meta;
    int insetTop, reserveBottom;
    /** the art's rectangle in this view (for the library transition) */
    final RectF artRect = new RectF();

    PlayerScreen(Context c, Engine e, Stage s) {
        super(c);
        setClipChildren(false); // the cover's glow and the play button's halo reach past their views
        th = Theme.get(c);
        engine = e;
        stage = s;
        vis = new VisView(c);
        pager = new ArtPager(c);
        like = new Ui.Btn(c, Icons.THUMB_UP, Ui.Btn.ROUND);
        dislike = new Ui.Btn(c, Icons.THUMB_DOWN, Ui.Btn.ROUND);
        listBtn = new Ui.Btn(c, Icons.LIST, Ui.Btn.ROUND);
        moreBtn = new Ui.Btn(c, Icons.MORE, Ui.Btn.ROUND);
        titleChip = new Ui.TextChip(c);
        titleChip.tp.setTypeface(th.titleFace); titleChip.tp.setTextSize(th.px(th.titleSize * 0.72f)); titleChip.tp.setColor(th.text);
        artistChip = new Ui.TextChip(c);
        artistChip.tp.setTypeface(th.uiMedium); artistChip.tp.setTextSize(th.px(15)); artistChip.tp.setColor(th.accent);
        String saved = stage.prefs().getString("slots", S_VIS + "," + S_TIMER + "," + S_REPEAT + "," + S_SHUFFLE);
        String[] parts = saved.split(",");
        for (int i = 0; i < 4; i++) {
            final int k = i;
            try { slotType[i] = Integer.parseInt(parts[i]); } catch (Exception ex) { slotType[i] = S_NONE; }
            slots[i] = new Ui.Btn(c, 0, Ui.Btn.CHIP);
            slots[i].click(() -> slotClick(slotType[k])).longClick(() -> slotLong(slotType[k]));
        }
        slotGap = new View(c);
        slotGap.setOnLongClickListener(v -> { editButtons(); return true; });
        prevCat = new Ui.Btn(c, Icons.PREV_CAT, Ui.Btn.ROUND_SMALL);
        prev = new Ui.Btn(c, Icons.PREV, Ui.Btn.ROUND);
        next = new Ui.Btn(c, Icons.NEXT, Ui.Btn.ROUND);
        nextCat = new Ui.Btn(c, Icons.NEXT_CAT, Ui.Btn.ROUND_SMALL);
        prevCat.dim = true; nextCat.dim = true;
        prev.iconDp = 30; next.iconDp = 30; prevCat.iconDp = 24; nextCat.iconDp = 24;
        play = new PlayView(c);
        seek = new SeekView(c);
        meta = new MetaView(c);
        for (View v : new View[]{ vis, pager, like, dislike, listBtn, moreBtn, titleChip, artistChip, slotGap, slots[0], slots[1], slots[2], slots[3],
                prevCat, prev, next, nextCat, play, seek, meta }) addView(v);

        play.setOnClickListener(v -> engine.toggle());
        prev.click(() -> pager.animateStep(-1));
        next.click(() -> pager.animateStep(1));
        prevCat.click(() -> pager.animateCategory(-1));
        nextCat.click(() -> pager.animateCategory(1));
        like.click(() -> rate(1));
        dislike.click(() -> rate(-1));
        listBtn.click(stage::openQueue);
        moreBtn.click(() -> stage.trackMenu(engine.track()));
        titleChip.setOnClickListener(v -> stage.openSource());
        titleChip.setOnLongClickListener(v -> { stage.trackMenu(engine.track()); return true; });
        artistChip.setOnClickListener(v -> stage.openArtist(engine.track()));
        artistChip.setOnLongClickListener(v -> { stage.trackMenu(engine.track()); return true; });
        meta.setOnClickListener(v -> meta.cycle());
        meta.setOnLongClickListener(v -> { stage.audioInfo(engine.track()); return true; });

        engine.listen(this);
        onTrack(engine.track());
        onModes();
        onExtras();
        play.setPlaying(engine.isPlaying(), false);
    }

    /** like / dislike: the same again clears it; in stars mode the stars sheet */
    private void rate(int dir) {
        Lib.Track t = engine.track();
        if (t == null) return;
        if (stage.ratingMode() == Stage.RATE_STARS) { stage.starsSheet(t); return; }
        int cur = engine.rating(t);
        int want = dir > 0 ? 5 : -1;
        engine.setRating(t, cur == want ? 0 : want);
        Ui.tick(dir > 0 ? like : dislike);
    }

    // ---------- the row of buttons ----------

    private void slotClick(int type) {
        switch (type) {
            case S_VIS: stage.toggleVis(); break;
            case S_TIMER: stage.sleepTimer(); break;
            case S_REPEAT: engine.cycleRepeat(); break;
            case S_SHUFFLE: engine.setShuffle(!engine.shuffle); break;
            case S_LYRICS: stage.openLyrics(); break;
            case S_EQ: stage.toggleEq(); break;
            case S_QUEUE: stage.openQueue(); break;
            case S_SEARCH: stage.openSearch(); break;
            case S_INFO: stage.audioInfo(engine.track()); break;
            default: editButtons();
        }
    }

    private void slotLong(int type) {
        switch (type) {
            case S_VIS: stage.visMenu(); break;
            case S_TIMER: stage.sleepTimer(); break;
            case S_REPEAT: stage.repeatMenu(); break;
            case S_SHUFFLE: stage.shuffleMenu(); break;
            case S_EQ: stage.eqMenu(); break;
            default: editButtons();
        }
    }

    /** Long press between the buttons to edit them. */
    void editButtons() {
        Sheet s = new Sheet(getContext(), "Buttons");
        String[] where = { "First on the left", "Second on the left", "Second on the right", "First on the right" };
        for (int i = 0; i < 4; i++) {
            final int k = i;
            s.add(new Sheet.Item(SLOT_ICONS[slotType[i]] == 0 ? Icons.CLOSE : SLOT_ICONS[slotType[i]], where[i], () -> pickButton(k)).value(SLOT_NAMES[slotType[i]]));
        }
        s.add(new Sheet.Item(Icons.SCAN, "Back to the usual four", () -> {
            int[] d = { S_VIS, S_TIMER, S_REPEAT, S_SHUFFLE };
            System.arraycopy(d, 0, slotType, 0, 4);
            saveSlots();
        }));
        stage.showSheet(s);
    }

    private void pickButton(int k) {
        Sheet s = new Sheet(getContext(), "Button");
        for (int t = 0; t < SLOT_NAMES.length; t++) {
            final int type = t;
            s.add(new Sheet.Item(SLOT_ICONS[t] == 0 ? Icons.CLOSE : SLOT_ICONS[t], SLOT_NAMES[t], () -> { slotType[k] = type; saveSlots(); }).radio(slotType[k] == t));
        }
        stage.showSheet(s);
    }

    private void saveSlots() {
        stage.prefs().edit().putString("slots", slotType[0] + "," + slotType[1] + "," + slotType[2] + "," + slotType[3]).apply();
        refreshSlots();
        requestLayout();
    }

    /** icons, on / off and labels for whatever the four buttons are */
    void refreshSlots() {
        for (int i = 0; i < 4; i++) {
            Ui.Btn b = slots[i];
            int t = slotType[i];
            b.setVisibility(t == S_NONE ? INVISIBLE : VISIBLE);
            int icon = SLOT_ICONS[t];
            boolean on = false;
            String label = null;
            switch (t) {
                case S_VIS: on = stage.visOn(); break;
                case S_TIMER: {
                    on = engine.sleepOn();
                    long left = engine.sleepRemainingMs();
                    label = engine.sleepingAfterSong() ? "end" : left > 0 ? (left + 59_999) / 60_000 + "m" : null;
                    break;
                }
                case S_REPEAT:
                    on = engine.repeat != Engine.REPEAT_OFF;
                    icon = engine.repeat == Engine.REPEAT_ONE ? Icons.REPEAT_ONE : engine.repeat == Engine.REPEAT_ADVANCE ? Icons.REPEAT_ADVANCE : Icons.REPEAT;
                    break;
                case S_SHUFFLE: on = engine.shuffle; break;
                case S_EQ: on = stage.fx.eqOn || stage.fx.toneOn; break;
                default: break;
            }
            b.setIcon(icon);
            b.set(on);
            if ((b.label == null) != (label == null)) requestLayout();
            b.label(label);
        }
    }

    @Override public void onTrack(Lib.Track t) {
        if (pager.flipped) pager.flip(false, false);
        pager.bind(false);
        boolean changed = titleChip.getTag() != t;
        titleChip.setTag(t);
        titleChip.setText(t == null ? "YouSic" : t.title);
        artistChip.setText(t == null ? "Tap play to start" : t.artist.isEmpty() ? "Unknown artist" : t.artist);
        if (changed && t != null && Motion.speed != Motion.SPEED_OFF) {
            for (View v : new View[]{ titleChip, artistChip }) {
                v.setAlpha(0); v.setTranslationY(th.px(8));
                v.animate().alpha(1).translationY(0).setDuration(Motion.ms(Motion.STATE)).setInterpolator(Motion.EASE_OUT).start();
            }
        }
        seek.setTrack(t);
        meta.update();
        onExtras();
    }

    @Override public void onState(boolean playing) {
        play.setPlaying(playing, true);
        seek.ticking(playing);
        vis.playing(playing);
    }

    @Override public void onModes() {
        refreshSlots();
        pager.bind(false);
        meta.update();
    }

    private final Runnable timerTick = this::onExtras;

    @Override public void onExtras() {
        String likeLabel0 = like.label;
        int likeVis0 = like.getVisibility(), dislikeVis0 = dislike.getVisibility();
        Lib.Track t = engine.track();
        int mode = stage.ratingMode();
        int r = engine.rating(t);
        like.setVisibility(mode == Stage.RATE_OFF ? GONE : VISIBLE);
        dislike.setVisibility(mode == Stage.RATE_THUMBS ? VISIBLE : GONE);
        if (mode == Stage.RATE_STARS) {
            like.setIcon(r > 0 ? Icons.STAR_FILLED : Icons.STAR);
            like.label = r > 0 ? String.valueOf(r) : null;
            like.set(r > 0);
        } else {
            like.setIcon(Icons.THUMB_UP);
            like.label = null;
            like.set(r > 0);
            dislike.set(r < 0);
        }
        like.invalidate();
        refreshSlots();
        long left = engine.sleepRemainingMs();
        removeCallbacks(timerTick);
        if (left > 0) postDelayed(timerTick, Math.min(30_000, left % 60_000 + 50));
        // only a label coming or going changes a button's width
        if ((likeLabel0 == null) != (like.label == null) || likeVis0 != like.getVisibility() || dislikeVis0 != dislike.getVisibility()) requestLayout();
    }

    /** The player is shown again (it stops its clocks while covered). */
    void onShown() {
        seek.ticking(engine.isPlaying());
        vis.kick();
    }

    // ---------- swipe up anywhere for the lyrics (the art and the seek bar handle their own touches) ----------

    private float iDownX, iDownY;
    private boolean iTracking, iSwiping;
    private VelocityTracker ivt;

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                iDownX = e.getX(); iDownY = e.getY(); iTracking = true; iSwiping = false;
                if (ivt != null) ivt.recycle();
                ivt = VelocityTracker.obtain(); ivt.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (!iTracking || e.getPointerCount() > 1) return false;
                ivt.addMovement(e);
                float dx = e.getX() - iDownX, dy = e.getY() - iDownY;
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (dy < -slop * 1.5f && Math.abs(dy) > Math.abs(dx) * 1.3f) { iSwiping = true; return true; }
                if (Math.abs(dx) > slop * 2 || dy > slop * 2) iTracking = false;
                return false;
            }
            default:
                iTracking = false;
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!iSwiping) return false;
        if (ivt != null) ivt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                float vy = 0;
                if (ivt != null) { ivt.computeCurrentVelocity(1000); vy = ivt.getYVelocity() / th.dp; ivt.recycle(); ivt = null; }
                float dy = e.getY() - iDownY;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (dy < -th.px(56) || vy < -Motion.FLING_DP_S)) stage.openLyrics();
                iSwiping = false;
                return true;
            }
            default: return true;
        }
    }

    // ---------- layout (phone, portrait) ----------

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }

    private int dp(float v) { return Math.round(v * th.dp); }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int bottom = h - reserveBottom - dp(2);
        int metaTop = bottom - dp(26);
        int seekTop = metaTop - dp(46);
        int transH = dp(104), transTop = seekTop - dp(4) - transH;
        int btnH = dp(44), btnTop = transTop - dp(2) - btnH;
        int artTop = insetTop + dp(8);
        int artSize = Math.min(w - dp(24), btnTop - dp(14) - dp(34) - dp(6) - dp(44) - dp(14) - artTop);
        // the song right under the art; any room to spare goes between it and the buttons
        int titleTop = artTop + artSize + dp(14), artistTop = titleTop + dp(44) + dp(6);
        int artLeft = (w - artSize) / 2;
        artRect.set(artLeft, artTop, artLeft + artSize, artTop + artSize);
        Ui.place(pager, 0, artTop - dp(40), w, artSize + dp(80));
        pager.setCard(artLeft, dp(40), artSize);
        // on the art: like / dislike at its left foot, the queue and the menu at its right
        int ab = dp(44), am = dp(12), ay = artTop + artSize - am - ab;
        Ui.place(like, artLeft + am, ay, like.label != null ? dp(64) : ab, ab);
        Ui.place(dislike, artLeft + am + (like.label != null ? dp(64) : ab) + dp(8), ay, ab, ab);
        Ui.place(moreBtn, artLeft + artSize - am - ab, ay, ab, ab);
        Ui.place(listBtn, artLeft + artSize - am - 2 * ab - dp(8), ay, ab, ab);
        int pad = artLeft;
        Ui.place(titleChip, pad, titleTop, w - 2 * pad, dp(44));
        Ui.place(artistChip, pad, artistTop, w - 2 * pad, dp(34));
        int bw = dp(76), gap = dp(8);
        Ui.place(slots[0], pad, btnTop, bw, btnH);
        Ui.place(slots[1], pad + bw + gap, btnTop, slots[1].label != null ? bw + dp(14) : bw, btnH);
        Ui.place(slots[3], w - pad - bw, btnTop, bw, btnH);
        Ui.place(slots[2], w - pad - 2 * bw - gap, btnTop, bw, btnH);
        // between the two pairs: long press to edit the buttons
        Ui.place(slotGap, slots[1].getRight(), btnTop, Math.max(0, slots[2].getLeft() - slots[1].getRight()), btnH);
        int cy = transTop + transH / 2, cx = w / 2;
        int ps = dp(100), ns = dp(64), cs = dp(50);
        Ui.place(play, cx - ps / 2, cy - ps / 2, ps, ps);
        int off = Math.min(dp(92), (w / 2 - pad) * 92 / 168);
        Ui.place(prev, cx - off - ns / 2, cy - ns / 2, ns, ns);
        Ui.place(next, cx + off - ns / 2, cy - ns / 2, ns, ns);
        int offc = Math.min(dp(154), w / 2 - pad - cs / 2);
        Ui.place(prevCat, cx - offc - cs / 2, cy - cs / 2, cs, cs);
        Ui.place(nextCat, cx + offc - cs / 2, cy - cs / 2, cs, cs);
        Ui.place(vis, pad, transTop - dp(30), w - 2 * pad, seekTop - transTop + dp(34));
        Ui.place(seek, pad - dp(4), seekTop, w - 2 * pad + dp(8), dp(46));
        Ui.place(meta, pad, metaTop, w - 2 * pad, dp(26));
    }

    // =====================================================================================================
    /** The album art: the current cover with the previous and next on either side. */
    final class ArtPager extends ViewGroup {
        final CoverCard[] cards = { new CoverCard(getContext()), new CoverCard(getContext()), new CoverCard(getContext()) };
        int cardLeft, cardTop, cardSize, spacing;
        float offset; // px the cards are dragged by (negative: towards the next song)
        private VelocityTracker vt;
        private float downX, downY, lastX;
        private boolean dragging, vertical, moved;
        private final int slop;
        private ValueAnimator anim;
        private Choreographer.FrameCallback linear;
        private int pendingDelta;
        /** dragged far enough to change category: 1 = next, -1 = previous, 0 = a song */
        private int catMode;
        private Lib.Group catGroup;
        private final TextPaint hint1 = new TextPaint(Paint.ANTI_ALIAS_FLAG), hint2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hintR = new RectF();
        /** the current cover is turned over: the song's details on its back */
        boolean flipped;
        private ValueAnimator flipAnim;
        /** how far over the card is (0 front .. 1 back), for the glow behind it while it turns */
        private float flipT;
        private final RectF glowR = new RectF();
        /** the web player's flip: 820 ms, lifting off the page mid-turn and overshooting a little as it lands */
        private final android.animation.TimeInterpolator FLIP_EASE = new PathInterpolator(0.3f, 1.18f, 0.45f, 1f);

        ArtPager(Context c) {
            super(c);
            for (CoverCard k : cards) addView(k);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClipChildren(false);
            setWillNotDraw(false);
            hint1.setTypeface(th.uiMedium); hint1.setTextSize(th.px(11)); hint1.setLetterSpacing(0.16f); hint1.setColor(th.accent); hint1.setTextAlign(Paint.Align.CENTER);
            hint2.setTypeface(th.titleFace); hint2.setTextSize(th.px(22)); hint2.setColor(th.text); hint2.setTextAlign(Paint.Align.CENTER);
        }

        void setCard(int left, int top, int size) {
            cardLeft = left; cardTop = top; cardSize = size;
            spacing = size + dp(24);
            for (int i = 0; i < 3; i++) Ui.place(cards[i], left, top, size, size);
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
            catMode = 0;
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
            float fade = (1 - Math.min(1, Math.abs(offset) / (spacing * 0.3f))) * (flipped ? 0 : 1);
            if (flipAnim == null) for (View v : new View[]{ like, dislike, listBtn, moreBtn }) v.setAlpha(fade);
            invalidate();
        }

        /** Turns the current cover over (the song's details) or back. */
        void flip(boolean on, boolean animate) {
            if (on && engine.track() == null) return;
            if (on == flipped) return;
            flipped = on;
            final CoverCard k = cards[1];
            if (flipAnim != null) { ValueAnimator a = flipAnim; flipAnim = null; a.cancel(); }
            k.flipping = false;
            k.setLayerType(LAYER_TYPE_NONE, null);
            for (View v : new View[]{ like, dislike, listBtn, moreBtn }) v.setClickable(!on);
            if (!animate || Motion.speed == Motion.SPEED_OFF) {
                k.setRotationY(0); k.setScaleX(1); k.setScaleY(1);
                k.back = on; k.mirror = false;
                k.invalidate();
                for (View v : new View[]{ like, dislike, listBtn, moreBtn }) v.setAlpha(on ? 0 : 1);
                return;
            }
            k.setCameraDistance(th.dp * 6000);
            // the card is drawn once per face into a GPU layer which then just turns (drawing it in 3D every
            // frame costs the GPU ~15 ms); its glow can't go in the layer, so the pager draws it behind
            k.flipping = true;
            k.setLayerType(LAYER_TYPE_HARDWARE, null);
            k.invalidate();
            final float a = on ? 0 : 180, b = on ? 180 : 360;
            flipAnim = ValueAnimator.ofFloat(0, 1);
            flipAnim.setDuration(Motion.ms(820));
            flipAnim.setInterpolator(FLIP_EASE);
            flipAnim.addUpdateListener(an -> {
                float e = (float) an.getAnimatedValue();
                float ang = a + (b - a) * e;
                k.setRotationY(ang);
                float s = e < 0.45f ? 1 - 0.1f * (e / 0.45f) : 0.9f + 0.1f * ((e - 0.45f) / 0.55f);
                k.setScaleX(s); k.setScaleY(s);
                float m = ((ang % 360) + 360) % 360;
                boolean behind = m > 90 && m < 270;
                if (behind != k.mirror) { k.mirror = behind; k.back = behind; k.invalidate(); }
                float btn = on ? Math.max(0, 1 - e * 3) : Math.max(0, Math.min(1, (e - 0.6f) * 2.5f));
                for (View v : new View[]{ like, dislike, listBtn, moreBtn }) v.setAlpha(btn);
                flipT = on ? e : 1 - e;
                invalidate();
            });
            flipAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    if (flipAnim != an) return;
                    flipAnim = null;
                    // at rest: no 3D turn at all, just the face
                    k.setRotationY(0); k.setScaleX(1); k.setScaleY(1);
                    k.back = flipped; k.mirror = false;
                    k.flipping = false;
                    k.setLayerType(LAYER_TYPE_NONE, null);
                    k.invalidate();
                    invalidate();
                    for (View v : new View[]{ like, dislike, listBtn, moreBtn }) v.setAlpha(flipped ? 0 : 1);
                }
            });
            flipAnim.start();
        }

        /** a tap on the art: the back's buttons, else turn it over */
        private void tapArt(float x, float y) {
            if (flipped && flipAnim == null) {
                CoverCard k = cards[1];
                float cx = x - k.getLeft() - k.getTranslationX(), cy = y - k.getTop() - k.getTranslationY();
                for (int i = 0; i < k.actions.size(); i++) if (k.actionR.get(i).contains(cx, cy)) { k.act(k.actions.get(i)); return; }
            }
            flip(!flipped, true);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // only the art itself
                    if (e.getY() < cardTop || e.getY() > cardTop + cardSize) { vt.recycle(); vt = null; return false; }
                    downX = lastX = e.getX(); downY = e.getY(); moved = false; vertical = false;
                    if (anim != null || linear != null) { stopSettle(); dragging = true; moved = true; getParent().requestDisallowInterceptTouchEvent(true); }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!dragging && !vertical && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true;
                        if (Math.abs(dx) > Math.abs(dy)) dragging = true; else vertical = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        if (flipped) flip(false, false); // a swipe turns to the covers
                        offset += e.getX() - lastX;
                        // no neighbour that way: resist
                        if ((offset > 0 && engine.peek(-1) == null) || (offset < 0 && engine.peek(1) == null)) offset *= 0.92f;
                        updateCategoryMode();
                        apply();
                    } else if (vertical) {
                        // the cover follows the finger a little; let go past the line to act
                        cards[1].setTranslationY(dy * 0.28f);
                    }
                    lastX = e.getX();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    vt.computeCurrentVelocity(1000);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (dragging) release(vt.getXVelocity());
                    else if (vertical) {
                        float dy = e.getY() - downY, vdy = vt.getYVelocity() / th.dp;
                        cards[1].animate().translationY(0).setDuration(Motion.ms(Motion.STATE)).setInterpolator(Motion.EASE_OUT).start();
                        if (up && (dy > th.px(56) || (vdy > Motion.FLING_DP_S && dy > 0))) stage.openSource();
                        else if (up && (dy < -th.px(56) || (vdy < -Motion.FLING_DP_S && dy < 0))) stage.openLyrics();
                    } else if (up && !moved) {
                        tapArt(e.getX(), e.getY());
                    }
                    dragging = false; vertical = false;
                    vt.recycle(); vt = null;
                    return true;
                }
                default:
                    return true;
            }
        }

        /** Dragged three quarters of the way: the next / previous category instead of the song. */
        private void updateCategoryMode() {
            int want = Math.abs(offset) > spacing * 0.75f ? (offset < 0 ? 1 : -1) : 0;
            if (want == catMode) return;
            catMode = want;
            Ui.tick(this);
            if (want != 0) {
                catGroup = engine.peekCategory(want);
                if (catGroup == null) { catMode = 0; return; }
                cards[want > 0 ? 2 : 0].set(catGroup.tracks.isEmpty() ? null : catGroup.tracks.get(0));
            } else {
                cards[0].set(engine.peek(-1));
                cards[2].set(engine.peek(1));
            }
        }

        /** A fling over 500 dp/s decides; otherwise past 30 %. */
        private void release(float vx) {
            float progress = Math.abs(offset) / spacing;
            int dir = offset < 0 ? 1 : -1; // 1 = next song
            float vdp = vx / th.dp;
            boolean commit;
            if (catMode != 0) commit = !(Math.abs(vdp) >= Motion.FLING_DP_S && ((vx > 0 && dir == 1) || (vx < 0 && dir == -1)));
            else if (Math.abs(vdp) >= Motion.FLING_DP_S) commit = (vx < 0 && dir == 1) || (vx > 0 && dir == -1);
            else commit = progress > Motion.COMMIT_FRACTION;
            if (catMode == 0 && engine.peek(dir) == null) commit = false;
            boolean useVelocity = commit ? progress <= 1 - Motion.SETTLE_NEAR_END : progress >= Motion.SETTLE_NEAR_END;
            if (commit) {
                pendingDelta = dir;
                if (catMode != 0) {
                    if (!engine.stepCategory(dir)) pendingDelta = 0;
                    else stage.toast(catLabel(dir) + ": " + catGroup.title);
                }
                else engine.step(dir, true);
            } else if (catMode != 0) {
                catMode = 0;
                cards[0].set(engine.peek(-1)); cards[2].set(engine.peek(1));
            }
            float target = commit ? -dir * spacing : 0;
            if (useVelocity && Math.abs(vdp) >= Motion.FLING_DP_S) settleLinear(target, vx);
            else settleTimed(target);
        }

        String catLabel(int dir) {
            String k = engine.categoryKind();
            return (dir > 0 ? "Next " : "Previous ") + k;
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
                catMode = 0;
                cards[0].set(engine.peek(-1)); cards[1].set(engine.track()); cards[2].set(engine.peek(1));
                apply();
            }
        }

        private void finishSettle() {
            pendingDelta = 0;
            catMode = 0;
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

        /** The category buttons: the next category's first cover slides in. */
        void animateCategory(int dir) {
            Lib.Group g = engine.peekCategory(dir);
            if (g == null) { stage.toast("No other " + engine.categoryKind()); return; }
            stopSettle();
            cards[dir > 0 ? 2 : 0].set(g.tracks.isEmpty() ? null : g.tracks.get(0));
            pendingDelta = dir;
            String label = catLabel(dir);
            engine.stepCategory(dir);
            stage.toast(label + ": " + g.title);
            settleTimed(-dir * spacing);
        }

        @Override protected void dispatchDraw(Canvas c) {
            CoverCard k = cards[1];
            if (k.flipping) {
                // the glow stays on the page while the card turns, fading to the back's dimmer light
                float s = k.getScaleX(), half = cardSize * s / 2, cx = cardLeft + cardSize / 2f, cy = cardTop + cardSize / 2f;
                glowR.set(cx - half, cy - half, cx + half, cy + half);
                float f = Math.max(0, Math.min(1, flipT));
                th.drawGlow(c, glowR, th.glowFront(k.playing) * (1 - f) + th.glowBack() * f);
            }
            super.dispatchDraw(c);
            if (catMode == 0 || catGroup == null) return;
            // over the incoming cover: what letting go here will play
            float cx = cardLeft + cardSize / 2f, cy = cardTop + cardSize / 2f;
            String l1 = catLabel(catMode).toUpperCase(Locale.ROOT);
            CharSequence l2 = TextUtils.ellipsize(catGroup.title, hint2, cardSize * 0.8f, TextUtils.TruncateAt.END);
            float w = Math.max(hint1.measureText(l1), hint2.measureText(l2, 0, l2.length())) + th.px(40);
            hintR.set(cx - w / 2, cy - th.px(38), cx + w / 2, cy + th.px(38));
            th.drawChip(c, hintR, th.px(22), false, true);
            c.drawText(l1, cx, cy - th.px(10), hint1);
            c.drawText(l2, 0, l2.length(), cx, cy + th.px(20), hint2);
        }
    }

    // =====================================================================================================
    /** One cover, drawn once (moved by translation). */
    final class CoverCard extends View {
        Lib.Track track;
        Bitmap art;
        float playing;
        /** showing the back (the song's details); mirror: seen from behind mid-turn; flipping: turning now */
        boolean back, mirror, flipping;
        final List<String> actions = new ArrayList<>();
        final List<RectF> actionR = new ArrayList<>();

        CoverCard(Context c) { super(c); }

        void set(Lib.Track t) {
            if (t == track) return;
            track = t;
            art = null;
            if (t != null && t.cover != null) {
                final Lib.Track want = t;
                art = Covers.get(t.cover, false, b -> { if (track == want) { art = b; invalidate(); } });
            }
            setVisibility(t == null && this != pager.cards[1] ? INVISIBLE : VISIBLE);
            invalidate();
        }

        private final RectF r = new RectF();

        @Override protected void onDraw(Canvas c) {
            r.set(0, 0, getWidth(), getHeight());
            if (flipping) th.glowScale = 0;
            if (back && track != null) {
                if (mirror) { c.save(); c.scale(-1, 1, getWidth() / 2f, 0); }
                drawBack(c, r, track);
                if (mirror) c.restore();
            } else th.drawCover(c, r, art, playing, null);
            th.glowScale = 1;
        }

        void act(String a) {
            Lib.Track t = track;
            if (t == null) return;
            switch (a) {
                case "album": stage.openGroup("album", Stage.find(App.get().lib.albums, t.albumKey)); break;
                case "artist": stage.openArtist(t); break;
                case "folder": stage.openGroup("folder", Stage.find(App.get().lib.folders, t.folder)); break;
                default: break;
            }
        }

        // ---- the back of the card: the song's details (the web player's flip card) ----

        private final TextPaint kick = new TextPaint(Paint.ANTI_ALIAS_FLAG), ttl = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                art2 = new TextPaint(Paint.ANTI_ALIAS_FLAG), dt = new TextPaint(Paint.ANTI_ALIAS_FLAG), dd = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                chip = new TextPaint(Paint.ANTI_ALIAS_FLAG), foot = new TextPaint(Paint.ANTI_ALIAS_FLAG), btn = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF q = new RectF();

        private void drawBack(Canvas c, RectF r, Lib.Track t) {
            float S = r.width();
            float rad = Math.min(th.px(th.coverRadius), Math.max(th.px(4), th.px(th.coverRadius) * S / th.px(300)));
            th.drawCardBack(c, r, rad);
            float pad = Math.max(th.px(14), Math.min(th.px(30), S * 0.07f));
            float x = r.left + pad, w = S - 2 * pad;
            float k = S / th.px(360); // the type follows the card's size
            kick.setTypeface(th.uiMedium); kick.setTextSize(th.px(10.5f) * k); kick.setLetterSpacing(0.13f); kick.setColor(th.accent);
            ttl.setTypeface(th.titleFace); ttl.setTextSize(Math.max(th.px(18), Math.min(th.px(30), S * 0.07f))); ttl.setColor(th.text);
            art2.setTypeface(th.uiMedium); art2.setTextSize(Math.max(th.px(12), Math.min(th.px(17), S * 0.044f))); art2.setColor(th.accent);
            dt.setTypeface(th.uiFace); dt.setTextSize(th.px(11) * k); dt.setColor(th.textDim);
            dd.setTypeface(th.uiBold); dd.setTextSize(th.px(13.5f) * k); dd.setColor(th.text);
            chip.setTypeface(th.numFace); chip.setTextSize(th.px(10.5f) * k); chip.setColor(th.accentHi);
            foot.setTypeface(th.uiMedium); foot.setTextSize(th.px(12) * k); foot.setColor(th.textDim);
            btn.setTypeface(th.uiMedium); btn.setTextSize(th.px(12) * k); btn.setColor(th.text);
            float y = r.top + pad;
            // kicker: SONG INFO · TRACK 3 · 2:27
            String pos = (t.track > 0 ? "TRACK " + t.track + (t.disc > 1 ? " · DISC " + t.disc : "") + "  ·  " : "") + fmt((long) (t.duration * 1000));
            bp.setColor(th.accent);
            float ks = kick.getTextSize() * 1.3f;
            Icons.draw(c, Icons.INFO, x + ks / 2, y + ks / 2, ks, bp);
            CharSequence kt = TextUtils.ellipsize("SONG INFO  ·  " + pos, kick, w - ks - th.px(6), TextUtils.TruncateAt.END);
            c.drawText(kt, 0, kt.length(), x + ks + th.px(6), y + ks / 2 - (kick.ascent() + kick.descent()) / 2, kick);
            y += ks + th.px(8) * k;
            // title (two lines at most), artist
            StaticLayout tl = StaticLayout.Builder.obtain(t.title, 0, t.title.length(), ttl, Math.round(w)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.95f).build();
            c.save(); c.translate(x, y); tl.draw(c); c.restore();
            y += tl.getHeight() + th.px(2);
            CharSequence ar = TextUtils.ellipsize(t.artist.isEmpty() ? "Unknown artist" : t.artist, art2, w, TextUtils.TruncateAt.END);
            c.drawText(ar, 0, ar.length(), x, y - art2.ascent(), art2);
            y += art2.descent() - art2.ascent() + th.px(4) * k;
            int rating = engine.rating(t);
            if (rating != 0) {
                String rs = rating < 0 ? "Disliked" : stage.ratingMode() == Stage.RATE_STARS ? new String(new char[rating]).replace("\0", "★") + new String(new char[5 - rating]).replace("\0", "☆") : "Liked";
                foot.setColor(th.accent2);
                c.drawText(rs, x, y - foot.ascent(), foot);
                foot.setColor(th.textDim);
                y += foot.descent() - foot.ascent() + th.px(4) * k;
            }
            // the bottom block, from the foot up: buttons, file, plays and size, format chips
            float bottom = r.bottom - pad;
            actions.clear(); actionR.clear();
            float bh = th.px(30) * k, bx = x;
            String[][] acts = { { "album", "Album" }, { "artist", "Artist" }, { "folder", "Folder" } };
            int[] icons = { Icons.ALBUM, Icons.ARTIST, Icons.FOLDER };
            for (int i = 0; i < acts.length; i++) {
                if (i == 0 && t.album.isEmpty()) continue;
                if (i == 1 && t.artist.isEmpty()) continue;
                float bw = btn.measureText(acts[i][1]) + bh + th.px(14) * k;
                q.set(bx, bottom - bh, bx + bw, bottom);
                th.drawChip(c, q, bh / 2, false, false);
                bp.setColor(th.text);
                Icons.draw(c, icons[i], q.left + bh * 0.55f, q.centerY(), bh * 0.5f, bp);
                c.drawText(acts[i][1], q.left + bh * 0.95f, q.centerY() - (btn.ascent() + btn.descent()) / 2, btn);
                actions.add(acts[i][0]); actionR.add(new RectF(q));
                bx += bw + th.px(6);
            }
            float by = bottom - bh - th.px(8) * k;
            float lineH = foot.descent() - foot.ascent();
            boolean room = S > th.px(240);
            if (room && S > th.px(300)) {
                foot.setTypeface(th.numFace); foot.setTextSize(th.px(10.5f) * k);
                String file = t.path.substring(t.path.lastIndexOf('/') + 1);
                CharSequence f = TextUtils.ellipsize(file, foot, w, TextUtils.TruncateAt.MIDDLE);
                c.drawText(f, 0, f.length(), x, by - foot.descent(), foot);
                by -= foot.descent() - foot.ascent() + th.px(4);
                foot.setTypeface(th.uiMedium); foot.setTextSize(th.px(12) * k);
            }
            List<String> fl = new ArrayList<>();
            int plays = engine.plays(t);
            long last = engine.lastPlayed(t);
            if (plays > 0) fl.add("Played " + plays + "×");
            if (last > 0) fl.add("last " + ago(last));
            if (t.size > 0) fl.add(String.format(Locale.ROOT, "%.1f MB", t.size / 1048576f));
            if (room && !fl.isEmpty()) {
                String fs = android.text.TextUtils.join("  ·  ", fl);
                CharSequence f = TextUtils.ellipsize(fs, foot, w, TextUtils.TruncateAt.END);
                c.drawText(f, 0, f.length(), x, by - foot.descent(), foot);
                by -= lineH + th.px(6) * k;
            }
            // format chips (gold outlines)
            List<String> chips = new ArrayList<>();
            String codec = t.codec == null || t.codec.isEmpty() ? t.container : t.codec;
            if (codec != null && !codec.isEmpty()) chips.add(codec.toUpperCase(Locale.ROOT));
            if (t.bits > 0) chips.add(t.bits + "-bit");
            if (t.sampleRate > 0) { float kh = t.sampleRate / 1000f; chips.add((kh == Math.round(kh) ? String.valueOf(Math.round(kh)) : String.format(Locale.ROOT, "%.1f", kh)) + " kHz"); }
            if (t.bitrate > 0) chips.add(t.bitrate + " kbps");
            if (t.channels > 0) chips.add(t.channels == 1 ? "Mono" : t.channels == 2 ? "Stereo" : t.channels + " ch");
            float ch = chip.descent() - chip.ascent() + th.px(6) * k, cx2 = x;
            float cy = by - ch;
            bp.setStyle(Paint.Style.STROKE); bp.setStrokeWidth(th.px(1)); bp.setColor(Theme.alpha(th.accentHi, 0.5f));
            for (String s : chips) {
                float cw = chip.measureText(s) + th.px(16) * k;
                if (cx2 + cw > x + w) break;
                q.set(cx2, cy, cx2 + cw, cy + ch);
                c.drawRoundRect(q, ch / 2, ch / 2, bp);
                c.drawText(s, q.left + th.px(8) * k, q.centerY() - (chip.ascent() + chip.descent()) / 2, chip);
                cx2 += cw + th.px(5);
            }
            bp.setStyle(Paint.Style.FILL);
            float gridBottom = cy - th.px(10) * k;
            // the grid: album, album artist, year, genre (as many as fit)
            String[][] rows = {
                    { "Album", t.album },
                    { "Album artist", !t.albumArtist.isEmpty() && !t.albumArtist.equals(t.artist) ? t.albumArtist : "" },
                    { "Year", t.year > 0 ? String.valueOf(t.year) : "" },
                    { "Genre", t.genre } };
            float labelW = 0;
            for (String[] row : rows) if (!row[1].isEmpty()) labelW = Math.max(labelW, dt.measureText(row[0]));
            float rowH = dd.descent() - dd.ascent() + th.px(4) * k;
            y += th.px(6) * k;
            int shown = 0;
            for (String[] row : rows) {
                if (row[1].isEmpty() || shown >= 4) continue;
                if (y + rowH > gridBottom) break;
                float base = y - dd.ascent();
                c.drawText(row[0], x, base, dt);
                CharSequence v = TextUtils.ellipsize(row[1], dd, w - labelW - th.px(14), TextUtils.TruncateAt.END);
                c.drawText(v, 0, v.length(), x + labelW + th.px(14), base, dd);
                y += rowH;
                shown++;
            }
        }
    }

    static String ago(long ms) {
        long s = (System.currentTimeMillis() - ms) / 1000;
        long[] n = { 31536000, 2592000, 86400, 3600, 60 };
        String[] u = { "year", "month", "day", "hour", "minute" };
        for (int i = 0; i < n.length; i++) if (s >= n[i]) { long v = s / n[i]; return v + " " + u[i] + (v > 1 ? "s" : "") + " ago"; }
        return "just now";
    }

    // =====================================================================================================
    /** The seek bar between the two times. Redrawn only when the playhead has moved a pixel; drag to seek. */
    final class SeekView extends View implements Choreographer.FrameCallback {
        private final float[] peaks = new float[64];
        private float progress, dragProgress = -1;
        private final TextPaint times = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF(), pill = new RectF();
        private boolean ticking, remaining;
        private long lastDrawnPx = -1;
        private String el = "0:00", tot = "0:00";

        SeekView(Context c) {
            super(c);
            times.setTypeface(th.stampFace); times.setTextSize(th.px(th.timeSize + 1)); times.setColor(th.stamp);
            times.setTextAlign(Paint.Align.CENTER);
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
            if (ticking && isAttachedToWindow() && PlayerScreen.this.getVisibility() == VISIBLE) Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); ticking(engine.isPlaying()); }
        @Override protected void onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow(); }

        private float pillW() { return th.px(58); }

        /** Redraws only when something visible moved: the playhead by a pixel, or the time by a second. */
        void update(boolean force) {
            long dur = engine.durationMs(), pos = engine.positionMs();
            progress = dur > 0 ? Math.min(1f, pos / (float) dur) : 0;
            long px = Math.round(progress * (getWidth() - 2 * pillW()));
            String e = fmt(pos), t = remaining ? "-" + fmt(Math.max(0, dur - pos)) : fmt(dur);
            if (force || px != lastDrawnPx || !e.equals(el) || !t.equals(tot)) {
                lastDrawnPx = px; el = e; tot = t;
                invalidate();
                play.setProgress(progress);
            }
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), pw = pillW();
            bar.set(pw + th.px(8), th.px(2), getWidth() - pw - th.px(8), h - th.px(2));
            float p = dragProgress >= 0 ? dragProgress : progress;
            th.drawSeek(c, bar, peaks, p, dragProgress >= 0);
            float ph = th.px(26), y = h / 2;
            pill.set(0, y - ph / 2, pw, y + ph / 2);
            th.drawChip(c, pill, ph / 2, false, false);
            c.drawText(dragProgress >= 0 ? fmt((long) (dragProgress * engine.durationMs())) : el, pill.centerX(), y - (times.ascent() + times.descent()) / 2, times);
            pill.set(getWidth() - pw, y - ph / 2, getWidth(), y + ph / 2);
            th.drawChip(c, pill, ph / 2, false, false);
            c.drawText(tot, pill.centerX(), y - (times.ascent() + times.descent()) / 2, times);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            float pw = pillW();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getX() > getWidth() - pw) {
                // the total time: tap for the time left
                remaining = !remaining;
                update(true);
                return false;
            }
            float l = pw + th.px(8), r = getWidth() - pw - th.px(8);
            float p = Math.max(0, Math.min(1, (e.getX() - l) / (r - l)));
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
        if (s >= 3600) return s / 3600 + ":" + ((s / 60) % 60 < 10 ? "0" : "") + (s / 60) % 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    // =====================================================================================================
    /** The format line: tap for the album, the list, the file; long press for the audio info. */
    final class MetaView extends View {
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private int mode;
        private CharSequence text = "";

        MetaView(Context c) {
            super(c);
            setClickable(true); setLongClickable(true);
            tp.setTypeface(th.uiMedium); tp.setTextSize(th.px(11.5f)); tp.setLetterSpacing(0.12f); tp.setColor(th.textDim);
            tp.setTextAlign(Paint.Align.CENTER);
        }

        void cycle() {
            mode = (mode + 1) % 4;
            update();
            setAlpha(0.2f);
            animate().alpha(1).setDuration(Motion.ms(Motion.STATE)).start();
        }

        void update() {
            Lib.Track t = engine.track();
            String s;
            if (t == null) s = "";
            else switch (mode) {
                case 1: s = (t.album.isEmpty() ? "No album" : t.album) + (t.year > 0 ? "  ·  " + t.year : ""); break;
                case 2: s = (engine.source().isEmpty() ? "Library" : engine.source()) + "  ·  " + (engine.index() + 1) + " / " + engine.size(); break;
                case 3: s = t.path.substring(t.path.lastIndexOf('/') + 1); break;
                default: s = format(t);
            }
            text = s;
            requestLayout();
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            String s = mode == 0 ? text.toString().toUpperCase(Locale.ROOT) : text.toString();
            CharSequence e = TextUtils.ellipsize(s, tp, getWidth() - th.px(8), mode == 3 ? TextUtils.TruncateAt.MIDDLE : TextUtils.TruncateAt.END);
            c.drawText(e, 0, e.length(), getWidth() / 2f, getHeight() / 2f - (tp.ascent() + tp.descent()) / 2, tp);
        }
    }

    /** "24 bit · 96 kHz · 2304 kbps · FLAC" */
    static String format(Lib.Track t) {
        StringBuilder b = new StringBuilder();
        if (t.bits > 0) b.append(t.bits).append(" bit");
        if (t.sampleRate > 0) {
            if (b.length() > 0) b.append("  ·  ");
            float k = t.sampleRate / 1000f;
            b.append(k == Math.round(k) ? String.valueOf(Math.round(k)) : String.format(Locale.ROOT, "%.1f", k)).append(" kHz");
        }
        if (t.bitrate > 0) { if (b.length() > 0) b.append("  ·  "); b.append(t.bitrate).append(" kbps"); }
        String codec = t.codec == null || t.codec.isEmpty() ? t.container : t.codec;
        if (codec != null && !codec.isEmpty()) { if (b.length() > 0) b.append("  ·  "); b.append(codec); }
        return b.toString();
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
            float size = th.px(70) * Math.min(1f, getWidth() / th.px(100));
            float press = isPressed() ? th.px(1) : 0;
            th.drawPlay(c, getWidth() / 2f, getHeight() / 2f + press, size, morph, playing, progress);
        }

        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    // =====================================================================================================
    /** The visualizer behind the transport: the spectrum, eased towards each capture every frame. */
    final class VisView extends View implements Choreographer.FrameCallback {
        private final float[] v = new float[Vis.N];
        private final RectF r = new RectF();
        private boolean running;
        private float alpha;

        VisView(Context c) { super(c); }

        void playing(boolean p) { kick(); }

        void kick() {
            if (!running && isAttachedToWindow()) { running = true; Choreographer.getInstance().postFrameCallback(this); }
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); kick(); }
        @Override protected void onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); running = false; super.onDetachedFromWindow(); }

        @Override public void doFrame(long now) {
            Vis src = stage.vis;
            boolean on = stage.visOn() && src.running();
            float[] t = src.target;
            boolean moving = false;
            float targetAlpha = on && engine.isPlaying() ? 1 : 0;
            alpha += (targetAlpha - alpha) * 0.12f;
            for (int i = 0; i < v.length; i++) {
                float want = on && engine.isPlaying() ? t[i] : 0;
                v[i] += (want - v[i]) * (want > v[i] ? 0.45f : 0.12f);
                if (Math.abs(want - v[i]) > 0.002f) moving = true;
            }
            if (Math.abs(targetAlpha - alpha) > 0.01f) moving = true;
            invalidate();
            if ((moving || (on && engine.isPlaying())) && isAttachedToWindow() && PlayerScreen.this.getVisibility() == VISIBLE)
                Choreographer.getInstance().postFrameCallback(this);
            else running = false;
        }

        @Override protected void onDraw(Canvas c) {
            if (alpha < 0.01f) return;
            r.set(0, 0, getWidth(), getHeight());
            th.drawVis(c, r, v, v.length, alpha);
        }
    }
}
