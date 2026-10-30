package local.yousic.n;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Every icon in the app, drawn as vector strokes and fills (our own drawings; no icon font or image). Each is
 * drawn centred at (cx, cy) in a box `s` wide, in the paint's colour.
 */
public final class Icons {
    private Icons() {}

    public static final int GRID = 1, EQ = 2, SEARCH = 3, MENU = 4, THUMB_UP = 5, THUMB_DOWN = 6, LIST = 7, MORE = 8,
            VIS = 9, TIMER = 10, REPEAT = 11, REPEAT_ONE = 12, REPEAT_ADVANCE = 13, SHUFFLE = 14, PREV = 15, NEXT = 16,
            PREV_CAT = 17, NEXT_CAT = 18, PLAY = 19, PAUSE = 20, BACK = 21, CLOSE = 22, CHECK = 23, PLUS = 24, SORT = 25,
            SELECT = 26, FOLDER = 27, NOTE = 28, ALBUM = 29, ARTIST = 30, GENRE = 31, HEART = 32, LYRICS = 33, INFO = 34,
            STAR = 35, RECENT = 36, ADDED = 37, PLAY_NEXT = 38, THEME = 39, TUNE = 40, SCAN = 41, SPEED = 42, STAR_FILLED = 43,
            QUEUE = 44, ZOOM = 45, STORAGE = 46, SHUFFLE_OFF = 47, EXPAND = 48;

    private static final Path p = new Path(), q = new Path();
    private static final RectF r = new RectF();
    private static final Matrix m = new Matrix();
    private static Path thumb;

    public static void draw(Canvas c, int icon, float cx, float cy, float s, Paint paint) {
        Paint.Style style0 = paint.getStyle();
        float sw0 = paint.getStrokeWidth();
        Paint.Cap cap0 = paint.getStrokeCap();
        float w = s * 0.085f;
        paint.setStrokeWidth(w);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        stroke(paint);
        p.reset();
        float a = s / 2;
        switch (icon) {
            case GRID: {
                fill(paint);
                float k = s * 0.36f, g = s * 0.1f, rr = s * 0.09f;
                for (int i = 0; i < 4; i++) {
                    float x = cx - g / 2 - k + (i % 2) * (k + g), y = cy - g / 2 - k + (i / 2) * (k + g);
                    r.set(x, y, x + k, y + k);
                    c.drawRoundRect(r, rr, rr, paint);
                }
                break;
            }
            case EQ: {
                fill(paint);
                float bw = s * 0.2f, g = s * 0.1f;
                float[] h = { 0.46f, 0.84f, 0.64f };
                for (int i = 0; i < 3; i++) {
                    float x = cx - bw * 1.5f - g + i * (bw + g), bot = cy + s * 0.4f;
                    r.set(x, bot - h[i] * s, x + bw, bot);
                    c.drawRoundRect(r, bw / 2, bw / 2, paint);
                }
                break;
            }
            case SEARCH: {
                paint.setStrokeWidth(s * 0.11f);
                float rr = s * 0.26f, ox = cx - s * 0.07f, oy = cy - s * 0.07f;
                c.drawCircle(ox, oy, rr, paint);
                paint.setStrokeWidth(s * 0.14f);
                c.drawLine(ox + rr * 0.78f, oy + rr * 0.78f, cx + s * 0.36f, cy + s * 0.36f, paint);
                break;
            }
            case MENU: {
                fill(paint);
                float bh = s * 0.15f, bw = s * 0.8f;
                for (int i = -1; i <= 1; i++) {
                    r.set(cx - bw / 2, cy + i * s * 0.27f - bh / 2, cx + bw / 2, cy + i * s * 0.27f + bh / 2);
                    c.drawRoundRect(r, bh / 2, bh / 2, paint);
                }
                break;
            }
            case THUMB_UP: case THUMB_DOWN: {
                if (thumb == null) thumb = thumbPath();
                m.reset();
                m.setScale(s, icon == THUMB_UP ? s : -s);
                m.postTranslate(cx, cy);
                thumb.transform(m, p);
                paint.setStrokeWidth(s * 0.075f);
                c.drawPath(p, paint);
                break;
            }
            case LIST: {
                float[] len = { 0.72f, 0.72f, 0.46f };
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * s * 0.24f;
                    c.drawLine(cx - s * 0.36f, y, cx - s * 0.36f + len[i] * s, y, paint);
                }
                break;
            }
            case MORE: {
                fill(paint);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * s * 0.3f, s * 0.085f, paint);
                break;
            }
            case VIS: {
                paint.setStrokeWidth(s * 0.1f);
                float[] h = { 0.22f, 0.46f, 0.7f, 0.46f, 0.3f, 0.56f, 0.22f };
                for (int i = 0; i < h.length; i++) {
                    float x = cx + (i - 3) * s * 0.13f;
                    c.drawLine(x, cy - h[i] * a, x, cy + h[i] * a, paint);
                }
                break;
            }
            case TIMER: case RECENT: {
                c.drawCircle(cx, cy, s * 0.38f, paint);
                c.drawLine(cx, cy, cx, cy - s * 0.22f, paint);
                c.drawLine(cx, cy, cx + s * 0.15f, cy + s * 0.09f, paint);
                if (icon == RECENT) {
                    fill(paint);
                    p.moveTo(cx - s * 0.46f, cy - s * 0.12f); p.lineTo(cx - s * 0.3f, cy - s * 0.12f); p.lineTo(cx - s * 0.38f, cy + s * 0.02f); p.close();
                    c.drawPath(p, paint);
                }
                break;
            }
            case REPEAT: case REPEAT_ONE: case REPEAT_ADVANCE: {
                float x0 = cx - s * 0.36f, x1 = cx + s * 0.36f, y0 = cy - s * 0.2f, y1 = cy + s * 0.2f, k = s * 0.14f;
                p.moveTo(x0, cy + s * 0.04f); p.lineTo(x0, y0 + k); p.quadTo(x0, y0, x0 + k, y0); p.lineTo(x1 - s * 0.06f, y0);
                p.moveTo(x1 - s * 0.16f, y0 - s * 0.1f); p.lineTo(x1 - s * 0.06f, y0); p.lineTo(x1 - s * 0.16f, y0 + s * 0.1f);
                p.moveTo(x1, cy - s * 0.04f); p.lineTo(x1, y1 - k); p.quadTo(x1, y1, x1 - k, y1); p.lineTo(x0 + s * 0.06f, y1);
                p.moveTo(x0 + s * 0.16f, y1 - s * 0.1f); p.lineTo(x0 + s * 0.06f, y1); p.lineTo(x0 + s * 0.16f, y1 + s * 0.1f);
                c.drawPath(p, paint);
                if (icon == REPEAT_ONE) {
                    fill(paint);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(s * 0.3f);
                    paint.setFakeBoldText(true);
                    c.drawText("1", cx, cy + s * 0.11f, paint);
                    paint.setFakeBoldText(false);
                } else if (icon == REPEAT_ADVANCE) {
                    p.reset();
                    p.moveTo(cx - s * 0.06f, cy - s * 0.08f); p.lineTo(cx + s * 0.04f, cy); p.lineTo(cx - s * 0.06f, cy + s * 0.08f);
                    c.drawPath(p, paint);
                }
                break;
            }
            case SHUFFLE: case SHUFFLE_OFF: {
                float x0 = cx - s * 0.38f, x1 = cx + s * 0.34f, y0 = cy - s * 0.2f, y1 = cy + s * 0.2f;
                p.moveTo(x0, y0); p.cubicTo(cx - s * 0.05f, y0, cx + s * 0.02f, y1, x1, y1);
                p.moveTo(x0, y1); p.cubicTo(cx - s * 0.05f, y1, cx + s * 0.02f, y0, x1, y0);
                p.moveTo(x1 - s * 0.11f, y0 - s * 0.1f); p.lineTo(x1, y0); p.lineTo(x1 - s * 0.11f, y0 + s * 0.1f);
                p.moveTo(x1 - s * 0.11f, y1 - s * 0.1f); p.lineTo(x1, y1); p.lineTo(x1 - s * 0.11f, y1 + s * 0.1f);
                c.drawPath(p, paint);
                if (icon == SHUFFLE_OFF) c.drawLine(cx - s * 0.4f, cy + s * 0.4f, cx + s * 0.4f, cy - s * 0.4f, paint);
                break;
            }
            case PREV: case NEXT: {
                fill(paint);
                float d = icon == NEXT ? 1 : -1, h = s * 0.5f, tw = s * 0.34f;
                for (int i = 0; i < 2; i++) {
                    float bx = cx + d * (i == 0 ? -tw : 0) - d * s * 0.02f;
                    p.moveTo(bx, cy - h / 2); p.lineTo(bx + d * tw, cy); p.lineTo(bx, cy + h / 2); p.close();
                }
                c.drawPath(p, paint);
                break;
            }
            case PREV_CAT: case NEXT_CAT: {
                fill(paint);
                float d = icon == NEXT_CAT ? 1 : -1, h = s * 0.4f, tw = s * 0.26f;
                for (int i = 0; i < 2; i++) {
                    float bx = cx + d * (i == 0 ? -tw - s * 0.08f : -s * 0.08f);
                    p.moveTo(bx, cy - h / 2); p.lineTo(bx + d * tw, cy); p.lineTo(bx, cy + h / 2); p.close();
                }
                c.drawPath(p, paint);
                float bx = cx + d * (tw - s * 0.08f) + d * s * 0.04f;
                r.set(Math.min(bx, bx + d * s * 0.07f), cy - h / 2, Math.max(bx, bx + d * s * 0.07f), cy + h / 2);
                c.drawRoundRect(r, s * 0.03f, s * 0.03f, paint);
                break;
            }
            case PLAY: {
                fill(paint);
                p.moveTo(cx - s * 0.2f, cy - s * 0.3f); p.lineTo(cx + s * 0.32f, cy); p.lineTo(cx - s * 0.2f, cy + s * 0.3f); p.close();
                c.drawPath(p, paint);
                break;
            }
            case PAUSE: {
                fill(paint);
                float bw = s * 0.17f;
                r.set(cx - s * 0.06f - bw, cy - s * 0.3f, cx - s * 0.06f, cy + s * 0.3f);
                c.drawRoundRect(r, bw * 0.3f, bw * 0.3f, paint);
                r.set(cx + s * 0.06f, cy - s * 0.3f, cx + s * 0.06f + bw, cy + s * 0.3f);
                c.drawRoundRect(r, bw * 0.3f, bw * 0.3f, paint);
                break;
            }
            case BACK: {
                paint.setStrokeWidth(s * 0.1f);
                p.moveTo(cx + s * 0.1f, cy - s * 0.26f); p.lineTo(cx - s * 0.16f, cy); p.lineTo(cx + s * 0.1f, cy + s * 0.26f);
                c.drawPath(p, paint);
                break;
            }
            case CLOSE: {
                paint.setStrokeWidth(s * 0.1f);
                c.drawLine(cx - s * 0.26f, cy - s * 0.26f, cx + s * 0.26f, cy + s * 0.26f, paint);
                c.drawLine(cx + s * 0.26f, cy - s * 0.26f, cx - s * 0.26f, cy + s * 0.26f, paint);
                break;
            }
            case CHECK: {
                paint.setStrokeWidth(s * 0.11f);
                p.moveTo(cx - s * 0.3f, cy + s * 0.02f); p.lineTo(cx - s * 0.08f, cy + s * 0.24f); p.lineTo(cx + s * 0.32f, cy - s * 0.22f);
                c.drawPath(p, paint);
                break;
            }
            case PLUS: {
                paint.setStrokeWidth(s * 0.1f);
                c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, paint);
                c.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.3f, paint);
                break;
            }
            case SORT: {
                float[] len = { 0.56f, 0.4f, 0.24f };
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * s * 0.24f;
                    c.drawLine(cx - s * 0.38f, y, cx - s * 0.38f + len[i] * s, y, paint);
                }
                c.drawLine(cx + s * 0.28f, cy - s * 0.3f, cx + s * 0.28f, cy + s * 0.3f, paint);
                p.moveTo(cx + s * 0.16f, cy + s * 0.18f); p.lineTo(cx + s * 0.28f, cy + s * 0.3f); p.lineTo(cx + s * 0.4f, cy + s * 0.18f);
                c.drawPath(p, paint);
                break;
            }
            case SELECT: {
                r.set(cx - s * 0.34f, cy - s * 0.34f, cx + s * 0.34f, cy + s * 0.34f);
                c.drawRoundRect(r, s * 0.12f, s * 0.12f, paint);
                p.moveTo(cx - s * 0.17f, cy + s * 0.01f); p.lineTo(cx - s * 0.04f, cy + s * 0.14f); p.lineTo(cx + s * 0.18f, cy - s * 0.12f);
                c.drawPath(p, paint);
                break;
            }
            case FOLDER: case STORAGE: {
                p.moveTo(cx - s * 0.4f, cy - s * 0.24f); p.lineTo(cx - s * 0.12f, cy - s * 0.24f); p.lineTo(cx - s * 0.04f, cy - s * 0.15f);
                p.lineTo(cx + s * 0.4f, cy - s * 0.15f); p.lineTo(cx + s * 0.4f, cy + s * 0.3f); p.lineTo(cx - s * 0.4f, cy + s * 0.3f); p.close();
                if (icon == STORAGE) fill(paint);
                c.drawPath(p, paint);
                break;
            }
            case NOTE: {
                c.drawLine(cx + s * 0.12f, cy - s * 0.34f, cx + s * 0.12f, cy + s * 0.2f, paint);
                c.drawLine(cx + s * 0.12f, cy - s * 0.34f, cx + s * 0.3f, cy - s * 0.22f, paint);
                fill(paint);
                c.drawCircle(cx - s * 0.02f, cy + s * 0.22f, s * 0.15f, paint);
                break;
            }
            case ALBUM: {
                c.drawCircle(cx, cy, s * 0.38f, paint);
                fill(paint);
                c.drawCircle(cx, cy, s * 0.09f, paint);
                break;
            }
            case ARTIST: {
                c.drawCircle(cx, cy - s * 0.14f, s * 0.17f, paint);
                r.set(cx - s * 0.33f, cy + s * 0.1f, cx + s * 0.33f, cy + s * 0.62f);
                c.drawArc(r, 200, 140, false, paint);
                break;
            }
            case GENRE: {
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * s * 0.22f;
                    p.moveTo(cx - s * 0.36f, y);
                    p.cubicTo(cx - s * 0.18f, y - s * 0.12f, cx - s * 0.02f, y + s * 0.12f, cx + s * 0.14f, y);
                    p.cubicTo(cx + s * 0.24f, y - s * 0.08f, cx + s * 0.3f, y - s * 0.04f, cx + s * 0.36f, y);
                }
                c.drawPath(p, paint);
                break;
            }
            case HEART: {
                p.moveTo(cx, cy + s * 0.32f);
                p.cubicTo(cx - s * 0.5f, cy, cx - s * 0.36f, cy - s * 0.42f, cx, cy - s * 0.18f);
                p.cubicTo(cx + s * 0.36f, cy - s * 0.42f, cx + s * 0.5f, cy, cx, cy + s * 0.32f);
                fill(paint);
                c.drawPath(p, paint);
                break;
            }
            case LYRICS: {
                float[] len = { 0.6f, 0.44f, 0.6f };
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * s * 0.22f;
                    c.drawLine(cx - s * 0.38f, y, cx - s * 0.38f + len[i] * s, y, paint);
                }
                fill(paint);
                c.drawCircle(cx + s * 0.28f, cy + s * 0.24f, s * 0.1f, paint);
                stroke(paint);
                c.drawLine(cx + s * 0.37f, cy + s * 0.24f, cx + s * 0.37f, cy - s * 0.12f, paint);
                break;
            }
            case INFO: {
                c.drawCircle(cx, cy, s * 0.38f, paint);
                c.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.2f, paint);
                fill(paint);
                c.drawCircle(cx, cy - s * 0.17f, s * 0.05f, paint);
                break;
            }
            case STAR: case STAR_FILLED: {
                for (int i = 0; i < 10; i++) {
                    double ang = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = (i % 2 == 0 ? 0.4f : 0.17f) * s;
                    float x = cx + (float) Math.cos(ang) * rr, y = cy + s * 0.03f + (float) Math.sin(ang) * rr;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                if (icon == STAR_FILLED) fill(paint);
                paint.setStrokeWidth(s * 0.07f);
                c.drawPath(p, paint);
                break;
            }
            case ADDED: {
                c.drawCircle(cx, cy, s * 0.38f, paint);
                c.drawLine(cx - s * 0.18f, cy, cx + s * 0.18f, cy, paint);
                c.drawLine(cx, cy - s * 0.18f, cx, cy + s * 0.18f, paint);
                break;
            }
            case PLAY_NEXT: case QUEUE: {
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * s * 0.24f;
                    c.drawLine(cx - s * 0.38f, y, cx + (i == 0 ? s * 0.38f : s * 0.06f), y, paint);
                }
                if (icon == PLAY_NEXT) {
                    fill(paint);
                    p.moveTo(cx + s * 0.16f, cy + s * 0.02f); p.lineTo(cx + s * 0.4f, cy + s * 0.14f); p.lineTo(cx + s * 0.16f, cy + s * 0.3f); p.close();
                    c.drawPath(p, paint);
                } else {
                    c.drawLine(cx + s * 0.28f, cy + s * 0.02f, cx + s * 0.28f, cy + s * 0.34f, paint);
                    c.drawLine(cx + s * 0.12f, cy + s * 0.18f, cx + s * 0.44f, cy + s * 0.18f, paint);
                }
                break;
            }
            case THEME: {
                p.addCircle(cx, cy, s * 0.38f, Path.Direction.CW);
                c.drawPath(p, paint);
                fill(paint);
                c.drawCircle(cx - s * 0.14f, cy - s * 0.12f, s * 0.06f, paint);
                c.drawCircle(cx + s * 0.1f, cy - s * 0.18f, s * 0.06f, paint);
                c.drawCircle(cx + s * 0.18f, cy + s * 0.04f, s * 0.06f, paint);
                c.drawCircle(cx - s * 0.06f, cy + s * 0.18f, s * 0.08f, paint);
                break;
            }
            case TUNE: {
                float[] at = { -0.14f, 0.18f, -0.04f };
                for (int i = 0; i < 3; i++) {
                    float y = cy + (i - 1) * s * 0.26f;
                    c.drawLine(cx - s * 0.38f, y, cx + s * 0.38f, y, paint);
                    fill(paint);
                    c.drawCircle(cx + at[i] * s * 2, y, s * 0.09f, paint);
                    stroke(paint);
                }
                break;
            }
            case SCAN: {
                r.set(cx - s * 0.32f, cy - s * 0.32f, cx + s * 0.32f, cy + s * 0.32f);
                c.drawArc(r, -60, 270, false, paint);
                fill(paint);
                float ex = cx + (float) Math.cos(Math.toRadians(-60)) * s * 0.32f, ey = cy + (float) Math.sin(Math.toRadians(-60)) * s * 0.32f;
                p.moveTo(ex + s * 0.14f, ey - s * 0.02f); p.lineTo(ex - s * 0.04f, ey - s * 0.14f); p.lineTo(ex - s * 0.02f, ey + s * 0.1f); p.close();
                c.drawPath(p, paint);
                break;
            }
            case SPEED: {
                r.set(cx - s * 0.38f, cy - s * 0.3f, cx + s * 0.38f, cy + s * 0.46f);
                c.drawArc(r, 180, 180, false, paint);
                c.drawLine(cx, cy + s * 0.08f, cx + s * 0.2f, cy - s * 0.14f, paint);
                fill(paint);
                c.drawCircle(cx, cy + s * 0.08f, s * 0.06f, paint);
                break;
            }
            case EXPAND: {
                // four corners opening out
                float o = s * 0.36f, k = s * 0.16f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                    p.moveTo(cx + sx * o, cy + sy * (o - k)); p.lineTo(cx + sx * o, cy + sy * o); p.lineTo(cx + sx * (o - k), cy + sy * o);
                }
                c.drawPath(p, paint);
                break;
            }
            case ZOOM: {
                r.set(cx - s * 0.36f, cy - s * 0.36f, cx - s * 0.04f, cy - s * 0.04f); c.drawRoundRect(r, s * 0.06f, s * 0.06f, paint);
                r.set(cx + s * 0.04f, cy - s * 0.36f, cx + s * 0.36f, cy - s * 0.04f); c.drawRoundRect(r, s * 0.06f, s * 0.06f, paint);
                r.set(cx - s * 0.36f, cy + s * 0.04f, cx + s * 0.36f, cy + s * 0.36f); c.drawRoundRect(r, s * 0.06f, s * 0.06f, paint);
                break;
            }
            default: break;
        }
        paint.setStyle(style0);
        paint.setStrokeWidth(sw0);
        paint.setStrokeCap(cap0);
    }

    private static void fill(Paint paint) { paint.setStyle(Paint.Style.FILL); }
    private static void stroke(Paint paint) { paint.setStyle(Paint.Style.STROKE); }

    /** A thumb up, in a unit box centred on 0 (y down). */
    private static Path thumbPath() {
        Path t = new Path();
        // the cuff
        t.addRoundRect(new RectF(-0.42f, -0.04f, -0.24f, 0.4f), 0.05f, 0.05f, Path.Direction.CW);
        // the hand: a fist with the thumb up
        t.moveTo(-0.16f, 0.4f);
        t.lineTo(-0.16f, -0.02f);
        t.lineTo(-0.02f, -0.24f);
        t.quadTo(0.04f, -0.34f, 0.04f, -0.4f);
        t.quadTo(0.16f, -0.42f, 0.16f, -0.28f);
        t.lineTo(0.1f, -0.08f);
        t.lineTo(0.34f, -0.08f);
        t.quadTo(0.44f, -0.06f, 0.42f, 0.04f);
        t.lineTo(0.34f, 0.34f);
        t.quadTo(0.32f, 0.4f, 0.24f, 0.4f);
        t.close();
        return t;
    }
}
