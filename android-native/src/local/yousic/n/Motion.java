package local.yousic.n;

import android.animation.TimeInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Animation timing for the whole app.
 *
 * Every duration goes through {@link #ms}, which applies the animation speed setting:
 * off = instant, fast = 0.6x, normal = 1x.
 */
public final class Motion {
    private Motion() {}

    public static final int SPEED_OFF = 0, SPEED_NORMAL = 1, SPEED_FAST = 2;
    public static int speed = SPEED_NORMAL;

    // ---- durations (ms) ----
    /** list page change (ListSubstyleBase2 pageAnimTime) */
    public static final int PAGE = 250;
    /** album art -> library and back */
    public static final int AA_TO_LIB = 250, LIB_TO_AA = 250;
    /** album art position, and its settle (aaPosAnimTime / aaPosSettleAnimTime) */
    public static final int AA_POS = 550, AA_POS_SETTLE = 650;
    /** album art <-> search, <-> lyrics */
    public static final int AA_TO_SEARCH = 150, SEARCH_TO_AA = 250, AA_TO_LYRICS = 250, LYRICS_TO_AA = 150;
    /** album art appearing (TopContainer aaEnterAnimTime), blurred backdrop updates */
    public static final int AA_ENTER = 250, AA_BLUR_UPDATE = 50, AA_BLUR_LONGER_UPDATE = 250, AA_BLUR_EXIT = 150;
    /** play / pause morph (playStateAnimTime), other state changes (stateAnimTime) */
    public static final int PLAY_STATE = 320, STATE = 250;
    /** visualizer */
    public static final int VIS = 320, VIS_ENTER = 500, VIS_EXIT = 100;
    /** popup menus */
    public static final int POPUP_ENTER = 350, POPUP_EXIT = 200;
    /** dialogs dragged away */
    public static final int DIALOG_DRAG = 1000;
    /** equalizer pages */
    public static final int EQ_POS_SCROLL = 250, EQ_SETTLE = 450;
    /** knob values */
    public static final int KNOB_VALUE = 300;
    /** toasts stay */
    public static final int TOAST = 2000;
    /** selector (pressed state) fade */
    public static final int SELECTOR_FADE = 150;

    // ---- gestures ----
    /** a swiped transition commits on a fling faster than this (dp/s) whatever its progress */
    public static final float FLING_DP_S = 500f;
    /** ...otherwise once it is past this far */
    public static final float COMMIT_FRACTION = 0.3f;
    /** near either end the settle ignores the finger's speed */
    public static final float SETTLE_NEAR_END = 0.2f;
    /** dialogs: dragged away faster than this (dp/s) */
    public static final float DIALOG_DRAG_DP_S = 1800f;
    /** lists stretch this far past their ends (maxStretchOvershoot) */
    public static final float STRETCH_DP = 50f;

    /** A duration under the animation speed setting. */
    public static long ms(int base) {
        if (speed == SPEED_OFF) return 1;
        if (speed == SPEED_FAST) return Math.round(base * 0.6f);
        return base;
    }

    // ---- curves ----
    /** page transitions: AccelerateDecelerateInterpolator */
    public static final TimeInterpolator PAGE_EASE = new android.view.animation.AccelerateDecelerateInterpolator();
    /** after a swipe the transition carries on at the finger's speed, in whole transitions a second, kept
     *  between 1/duration and 8 going forward and between -8 and -3.5 going back */
    public static final float SETTLE_MAX = 8f, SETTLE_BACK_MIN = 3.5f;
    /** the standard decelerating ease for things arriving */
    public static final TimeInterpolator EASE_OUT = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** ease in-out for things moving between two resting places */
    public static final TimeInterpolator EASE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** leaving: accelerate out */
    public static final TimeInterpolator EASE_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
}
