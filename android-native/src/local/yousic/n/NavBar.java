package local.yousic.n;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.ViewGroup;

/**
 * The bar along the bottom of every screen: Library, Equalizer / effects (long press: its menu), Search, and
 * Menu (long press: the skins).
 */
@SuppressLint("ViewConstructor")
public final class NavBar extends ViewGroup {
    final Theme th;
    final Ui.Btn library, eq, search, menu;
    int insetBottom;
    private final RectF bar = new RectF();

    NavBar(Context c) {
        super(c);
        th = Theme.get(c);
        setWillNotDraw(false);
        library = new Ui.Btn(c, Icons.GRID, Ui.Btn.PLAIN);
        eq = new Ui.Btn(c, Icons.EQ, Ui.Btn.PLAIN);
        search = new Ui.Btn(c, Icons.SEARCH, Ui.Btn.PLAIN);
        menu = new Ui.Btn(c, Icons.MENU, Ui.Btn.PLAIN);
        for (Ui.Btn b : new Ui.Btn[]{ library, eq, search, menu }) { b.iconDp = th.compact ? 22 : 26; addView(b); }
    }

    /** The height this bar takes from the bottom of the screen. */
    int heightPx() { return Math.round(th.px(th.compact ? 66 : 74)) + insetBottom; }

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), heightPx()); }

    @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
        int w = r - l;
        float m = th.px(10);
        bar.set(m, th.px(4), w - m, th.px(th.compact ? 60 : 68));
        int n = 4;
        float cell = bar.width() / n;
        int bh = Math.round(th.px(th.compact ? 48 : 56)), bw = Math.round(Math.min(cell - th.px(8), th.px(th.compact ? 70 : 78)));
        Ui.Btn[] bs = { library, eq, search, menu };
        for (int i = 0; i < n; i++) {
            int cx = Math.round(bar.left + cell * (i + 0.5f));
            Ui.place(bs[i], cx - bw / 2, Math.round(bar.centerY() - bh / 2f), bw, bh);
        }
    }

    @Override protected void onDraw(Canvas c) { th.drawNavBar(c, bar, bar.height() / 2); }
}
