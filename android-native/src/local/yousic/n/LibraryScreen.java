package local.yousic.n;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EdgeEffect;
import android.widget.EditText;
import android.widget.OverScroller;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The library: a stack of lists (categories, albums, songs) under a header; swipe either way for
 * the parent list; pinch to zoom (small, medium and large rows, or a grid); the A-Z strip at the side; floating
 * buttons above the mini player (shuffle, play, search, select, list options); the mini player (tap: the player,
 * swipe: the previous / next song, long press: the song's list).
 */
@SuppressLint("ViewConstructor")
public final class LibraryScreen extends ViewGroup implements Engine.Listener, Lib.Listener {
    static final class Item {
        String title = "", sub = "", meta = "", right = "", cover;
        int icon;           // a category glyph (the root), else 0
        boolean header;     // a section label (search results)
        Object data;        // what tapping it opens or plays
        char letter = '#';
        CharSequence eTitle, eSub, eMeta;
        float eWidth = -1, eSize = -1;
    }

    static final class Page {
        String title = "", sub = "", kind = "tracks", key = "";
        final List<Item> items = new ArrayList<>();
        List<Lib.Track> trackList = new ArrayList<>();
        List<Lib.Group> groups;
        ListPage view;
        int zoom;
        int sort;
        boolean desc;
        boolean selecting;
        final Set<Integer> selected = new HashSet<>();
        String query = "";
        boolean letters;

        String zoomKey() { return kind.equals("groups") ? "groups:" + key : kind; }
        String sortKey() { return kind.equals("groups") ? "groups:" + key : kind + ":" + base(key); }
        static String base(String k) { int i = k.indexOf(':'); return i > 0 ? k.substring(0, i) : k; }
    }

    static final String[] TRACK_SORTS = { "Title", "Artist", "Album", "Duration", "Date added", "File name", "Track number" };
    static final String[] GROUP_SORTS = { "Name", "Number of songs" };
    static final String[] ZOOMS = { "Small", "Medium", "Large", "Grid" };

    final Theme th;
    final Engine engine;
    final Lib lib;
    final Stage stage;
    final Header header;
    final Fab fab;
    final MiniPlayer mini;
    final EditText searchField;
    final List<Page> stack = new ArrayList<>();
    int insetTop, reserveBottom;
    private float transition = -1; // 0..1 while pages change
    private int transDir = 1;
    private ValueAnimator anim;
    private Choreographer.FrameCallback linear;
    private final int slop;
    private VelocityTracker vt;
    private float downX, downY;
    private boolean swiping, decided;
    private final SharedPreferences prefs;
    private final Collator col = Collator.getInstance();

    LibraryScreen(Context c, Engine e, Lib l, Stage s) {
        super(c);
        th = Theme.get(c);
        engine = e; lib = l; stage = s;
        prefs = c.getSharedPreferences("lists", Context.MODE_PRIVATE);
        col.setStrength(Collator.PRIMARY);
        header = new Header(c);
        fab = new Fab(c);
        mini = new MiniPlayer(c);
        searchField = new EditText(c);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setHint("Search songs, albums, artists");
        searchField.setHintTextColor(Theme.alpha(th.text, 0.45f));
        searchField.setTextColor(th.text);
        searchField.setTypeface(th.uiMedium);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_PX, th.px(17));
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setGravity(Gravity.CENTER_VERTICAL);
        searchField.setPadding(Math.round(th.px(16)), 0, Math.round(th.px(16)), 0);
        searchField.setVisibility(GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s1, int a, int b, int c1) { }
            @Override public void onTextChanged(CharSequence s1, int a, int b, int c1) { }
            @Override public void afterTextChanged(Editable ed) {
                removeCallbacks(runSearch);
                postDelayed(runSearch, 120);
            }
        });
        searchField.setOnEditorActionListener((v, id, ev) -> { hideKeyboard(); return true; });
        addView(header);
        addView(header.back);
        addView(searchField);
        addView(fab);
        addView(mini);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setWillNotDraw(false);
        setClipChildren(true);
        header.back.click(this::back);
        engine.listen(this);
        lib.listen(this);
        stack.add(root());
        addPage(stack.get(0));
        fab.update();
    }

    // ---------- pages ----------

    private Page root() {
        Page p = new Page();
        p.kind = "root";
        p.title = "Library"; p.sub = Lib.count(lib.tracks.size());
        p.items.add(cat("All songs", Lib.count(lib.tracks.size()), Icons.NOTE, "all"));
        p.items.add(cat("Albums", lib.albums.size() + " albums", Icons.ALBUM, "albums"));
        p.items.add(cat("Artists", lib.artists.size() + " artists", Icons.ARTIST, "artists"));
        p.items.add(cat("Folders", lib.folders.size() + " folders", Icons.FOLDER, "folders"));
        p.items.add(cat("Genres", lib.genres.size() + " genres", Icons.GENRE, "genres"));
        p.items.add(cat("Liked", Lib.count(engine.liked().size()), Icons.THUMB_UP, "liked"));
        p.items.add(cat("Recently added", "The newest 200", Icons.ADDED, "added"));
        p.items.add(cat("Recently played", Lib.count(engine.recent().size()), Icons.RECENT, "recent"));
        p.items.add(cat("Queue", engine.queued() > 0 ? engine.queued() + " queued, then the list" : "What plays next", Icons.QUEUE, "queue"));
        p.zoom = 1;
        return p;
    }

    private static Item cat(String t, String sub, int icon, String data) {
        Item i = new Item(); i.title = t; i.sub = sub; i.icon = icon; i.data = data; return i;
    }

    private Page groupsPage(String title, List<Lib.Group> groups, String kind) {
        Page p = new Page();
        p.kind = "groups"; p.key = kind;
        p.title = title;
        p.groups = new ArrayList<>(groups);
        p.zoom = prefs.getInt("zoom_" + p.zoomKey(), kind.equals("album") ? 3 : 1);
        p.sort = prefs.getInt("sort_" + p.sortKey(), 0);
        p.desc = prefs.getBoolean("desc_" + p.sortKey(), false);
        fillGroups(p);
        return p;
    }

    private void fillGroups(Page p) {
        List<Lib.Group> gs = p.groups;
        Comparator<Lib.Group> cmp = p.sort == 1 ? (a, b) -> b.tracks.size() - a.tracks.size() : (a, b) -> col.compare(a.title, b.title);
        Collections.sort(gs, p.desc ? Collections.reverseOrder(cmp) : cmp);
        p.items.clear();
        for (Lib.Group g : gs) {
            Item i = new Item();
            i.title = g.title;
            i.sub = p.key.equals("album") ? g.sub : p.key.equals("folder") ? g.sub : Lib.count(g.tracks.size());
            i.meta = Lib.count(g.tracks.size());
            i.cover = g.cover; i.data = g;
            i.letter = letter(g.title);
            p.items.add(i);
        }
        p.letters = p.sort == 0;
        p.sub = gs.size() + " " + p.title.toLowerCase(Locale.ROOT);
        if (p.view != null) p.view.changed();
    }

    Page tracksPage(String title, List<Lib.Track> list, String key, int defaultSort) {
        Page p = new Page();
        p.kind = "tracks"; p.key = key;
        p.title = title;
        p.trackList = new ArrayList<>(list);
        p.zoom = prefs.getInt("zoom_" + p.zoomKey(), 2);
        p.sort = prefs.getInt("sort_" + p.sortKey(), defaultSort);
        p.desc = prefs.getBoolean("desc_" + p.sortKey(), false);
        if (defaultSort < 0) p.sort = -1; // as given (the queue, recent...)
        fillTracks(p);
        return p;
    }

    private void fillTracks(Page p) {
        if (p.sort >= 0) {
            Comparator<Lib.Track> cmp;
            switch (p.sort) {
                case 1: cmp = (a, b) -> col.compare(a.artist, b.artist) != 0 ? col.compare(a.artist, b.artist) : col.compare(a.title, b.title); break;
                case 2: cmp = (a, b) -> col.compare(a.album, b.album) != 0 ? col.compare(a.album, b.album) : a.track - b.track; break;
                case 3: cmp = (a, b) -> Double.compare(a.duration, b.duration); break;
                case 4: cmp = (a, b) -> Long.compare(b.added, a.added); break;
                case 5: cmp = (a, b) -> a.path.substring(a.path.lastIndexOf('/') + 1).compareToIgnoreCase(b.path.substring(b.path.lastIndexOf('/') + 1)); break;
                case 6: cmp = (a, b) -> a.disc != b.disc ? a.disc - b.disc : a.track != b.track ? a.track - b.track : col.compare(a.title, b.title); break;
                default: cmp = (a, b) -> col.compare(a.title, b.title);
            }
            Collections.sort(p.trackList, p.desc ? Collections.reverseOrder(cmp) : cmp);
        }
        p.items.clear();
        for (Lib.Track t : p.trackList) p.items.add(trackItem(t, p.sort));
        p.letters = p.sort == 0 || p.sort == 1 || p.sort == 2 || p.sort == 5;
        p.sub = Lib.count(p.trackList.size()) + "  ·  " + total(p.trackList);
        p.selected.clear();
        if (p.view != null) p.view.changed();
    }

    private Item trackItem(Lib.Track t, int sort) {
        Item i = new Item();
        i.title = t.title;
        i.sub = t.artist + (t.album.isEmpty() ? "" : " - " + t.album);
        StringBuilder m = new StringBuilder(PlayerScreen.fmt((long) (t.duration * 1000)));
        String codec = t.codec == null || t.codec.isEmpty() ? t.container : t.codec;
        if (codec != null && !codec.isEmpty()) m.append(" | ").append(codec.toLowerCase(Locale.ROOT));
        if (t.sampleRate > 0 && t.sampleRate != 44100 && t.sampleRate != 48000 || t.sampleRate > 48000)
            m.append(" | ").append(t.sampleRate % 1000 == 0 ? String.valueOf(t.sampleRate / 1000) : String.format(Locale.ROOT, "%.1f", t.sampleRate / 1000f)).append(" kHz");
        if (t.bits > 16) m.append(" | ").append(t.bits).append(" bit");
        i.meta = m.toString();
        i.right = PlayerScreen.fmt((long) (t.duration * 1000));
        i.cover = t.cover; i.data = t;
        i.letter = letter(sort == 1 ? t.artist : sort == 2 ? t.album : sort == 5 ? t.path.substring(t.path.lastIndexOf('/') + 1) : t.title);
        return i;
    }

    private static String total(List<Lib.Track> ts) {
        double s = 0;
        for (Lib.Track t : ts) s += t.duration;
        long m = Math.round(s / 60);
        return m >= 60 ? m / 60 + " h " + m % 60 + " min" : m + " min";
    }

    static char letter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = Character.toUpperCase(s.charAt(i));
            if (ch >= 'A' && ch <= 'Z') return i == 0 ? ch : '#';
            if (Character.isLetterOrDigit(ch)) return '#';
        }
        return '#';
    }

    /** Tap on a row. */
    void open(Item it, Page from, int index) {
        Object d = it.data;
        if (from.selecting && d instanceof Lib.Track) {
            if (!from.selected.remove(index)) from.selected.add(index);
            from.view.invalidate();
            fab.update();
            return;
        }
        if (d instanceof Lib.Track) {
            if (from.key.equals("queue")) { engine.jump((Lib.Track) d); return; }
            engine.playList(from.trackList, from.trackList.indexOf(d), from.title, from.key);
            return;
        }
        Page p = pageFor(d, from);
        if (p != null) push(p);
    }

    private Page pageFor(Object d, Page from) {
        if ("all".equals(d)) return tracksPage("All songs", lib.tracks, "all", 0);
        if ("albums".equals(d)) return groupsPage("Albums", lib.albums, "album");
        if ("artists".equals(d)) return groupsPage("Artists", lib.artists, "artist");
        if ("folders".equals(d)) return groupsPage("Folders", lib.folders, "folder");
        if ("genres".equals(d)) return groupsPage("Genres", lib.genres, "genre");
        if ("liked".equals(d)) return tracksPage("Liked", engine.liked(), "liked", 0);
        if ("added".equals(d)) {
            List<Lib.Track> l = new ArrayList<>(lib.tracks);
            Collections.sort(l, (a, b) -> Long.compare(b.added, a.added));
            return tracksPage("Recently added", l.subList(0, Math.min(200, l.size())), "added", -1);
        }
        if ("recent".equals(d)) return tracksPage("Recently played", engine.recent(), "recent", -1);
        if ("queue".equals(d)) return tracksPage("Queue", engine.upcoming(), "queue", -1);
        if (d instanceof Lib.Group) {
            Lib.Group g = (Lib.Group) d;
            String kind = from.kind.equals("groups") ? from.key : groupKindOf(g);
            return tracksPage(g.title, g.tracks, kind + ":" + g.key, kind.equals("album") ? 6 : kind.equals("folder") ? 5 : 0);
        }
        return null;
    }

    /** which category list a group (found by search) belongs to */
    private String groupKindOf(Lib.Group g) {
        if (lib.albums.contains(g)) return "album";
        if (lib.artists.contains(g)) return "artist";
        if (lib.folders.contains(g)) return "folder";
        return "genre";
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
        transDir = 1;
        p.view.setTranslationX(getWidth()); // no frame of it in place before the slide starts
        apply(0);
        runTransition(0, 1, 0);
        topChanged();
    }

    /** One page back; false at the root. */
    boolean back() {
        Page top = stack.get(stack.size() - 1);
        if (top.selecting) { setSelecting(top, false); return true; }
        if (stack.size() < 2) return false;
        if (transition >= 0) return true;
        header.set(stack.get(stack.size() - 2), top);
        transDir = 1;
        runTransition(1, 0, 0);
        return true;
    }

    /** Straight back to the categories. */
    void toRoot() {
        stopTransition();
        while (stack.size() > 1) removeView(stack.remove(stack.size() - 1).view);
        Page r = stack.get(0);
        r.view.setTranslationX(0); r.view.setAlpha(1); r.view.setVisibility(VISIBLE);
        header.set(r, r);
        topChanged();
    }

    boolean atRoot() { return stack.size() == 1; }

    /** Opens the list a song plays from, scrolled to the song: the categories, the category, the list. */
    void showSource() {
        stopTransition();
        while (stack.size() > 1) removeView(stack.remove(stack.size() - 1).view);
        String key = engine.sourceKey();
        String kind = Page.base(key);
        Lib.Track t = engine.track();
        Page list = null;
        switch (kind) {
            case "album": case "artist": case "folder": case "genre": {
                List<Lib.Group> gs = engine.groupsOf(kind);
                String gk = key.substring(kind.length() + 1);
                Lib.Group g = null;
                for (Lib.Group x : gs) if (x.key.equals(gk)) { g = x; break; }
                Page groups = pageFor(kind.equals("album") ? "albums" : kind + "s", stack.get(0));
                if (groups != null) { stack.add(groups); addPage(groups); groups.view.scrollTo(g); }
                if (g != null && groups != null) { list = pageFor(g, groups); }
                break;
            }
            case "liked": case "added": case "recent": case "queue":
                list = pageFor(kind, stack.get(0));
                break;
            default:
                list = tracksPage(engine.source().isEmpty() ? "All songs" : engine.source(), engine.list(), key.isEmpty() ? "all" : key, -1);
        }
        if (list != null) { stack.add(list); addPage(list); }
        for (int i = 0; i < stack.size(); i++) {
            Page p = stack.get(i);
            p.view.setTranslationX(0); p.view.setAlpha(1);
            p.view.setVisibility(i == stack.size() - 1 ? VISIBLE : INVISIBLE);
        }
        Page top = stack.get(stack.size() - 1);
        header.set(top, top);
        if (t != null) top.view.scrollToTrack(t);
        topChanged();
    }

    /** The artist's songs. */
    void showArtist(Lib.Track t) {
        if (t == null) return;
        String key = t.artist.toLowerCase(Locale.ROOT);
        for (Lib.Group g : lib.artists) if (g.key.equals(key)) { showGroup("artist", g); return; }
    }

    void showGroup(String kind, Lib.Group g) {
        stopTransition();
        while (stack.size() > 1) removeView(stack.remove(stack.size() - 1).view);
        Page groups = pageFor(kind.equals("album") ? "albums" : kind + "s", stack.get(0));
        stack.add(groups); addPage(groups); groups.view.scrollTo(g);
        Page list = pageFor(g, groups);
        stack.add(list); addPage(list);
        for (int i = 0; i < stack.size(); i++) {
            Page p = stack.get(i);
            p.view.setTranslationX(0); p.view.setAlpha(1);
            p.view.setVisibility(i == stack.size() - 1 ? VISIBLE : INVISIBLE);
        }
        header.set(list, list);
        Lib.Track cur = engine.track();
        if (cur != null) list.view.scrollToTrack(cur);
        topChanged();
    }

    // ---------- search: a page whose header is the search field ----------

    void openSearch() {
        Page top = stack.get(stack.size() - 1);
        if (!top.kind.equals("search")) {
            Page p = new Page();
            p.kind = "search"; p.key = "search";
            p.title = "Search"; p.sub = "";
            p.zoom = 1;
            p.sort = -1;
            if (stack.size() == 1 || transition >= 0) {
                stopTransition();
                stack.add(p); addPage(p);
                for (int i = 0; i < stack.size(); i++) stack.get(i).view.setVisibility(i == stack.size() - 1 ? VISIBLE : INVISIBLE);
                header.set(p, p);
                topChanged();
            } else push(p);
        }
        searchField.setText("");
        searchField.requestFocus();
        post(() -> {
            InputMethodManager im = getContext().getSystemService(InputMethodManager.class);
            if (im != null) im.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    void hideKeyboard() {
        InputMethodManager im = getContext().getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        searchField.clearFocus();
    }

    private final Runnable runSearch = this::doSearch;

    private void doSearch() {
        Page p = stack.get(stack.size() - 1);
        if (!p.kind.equals("search")) return;
        String q = searchField.getText().toString().trim().toLowerCase(Locale.ROOT);
        p.query = q;
        p.items.clear();
        p.trackList = new ArrayList<>();
        if (!q.isEmpty()) {
            for (Lib.Track t : lib.tracks) {
                if (t.title.toLowerCase(Locale.ROOT).contains(q) || t.artist.toLowerCase(Locale.ROOT).contains(q) || t.album.toLowerCase(Locale.ROOT).contains(q)) p.trackList.add(t);
            }
            List<Item> albums = new ArrayList<>(), artists = new ArrayList<>();
            for (Lib.Group g : lib.albums) if (g.title.toLowerCase(Locale.ROOT).contains(q) && albums.size() < 30) albums.add(groupItem(g));
            for (Lib.Group g : lib.artists) if (g.title.toLowerCase(Locale.ROOT).contains(q) && artists.size() < 30) artists.add(groupItem(g));
            if (!p.trackList.isEmpty()) {
                p.items.add(section("Songs  ·  " + p.trackList.size()));
                for (Lib.Track t : p.trackList.subList(0, Math.min(100, p.trackList.size()))) p.items.add(trackItem(t, 0));
            }
            if (!albums.isEmpty()) { p.items.add(section("Albums  ·  " + albums.size())); p.items.addAll(albums); }
            if (!artists.isEmpty()) { p.items.add(section("Artists  ·  " + artists.size())); p.items.addAll(artists); }
            if (p.items.isEmpty()) p.items.add(section("Nothing found"));
        }
        p.sub = q.isEmpty() ? "" : Lib.count(p.trackList.size());
        p.view.changed();
        header.invalidate();
        fab.update();
    }

    private Item groupItem(Lib.Group g) {
        Item i = new Item();
        i.title = g.title; i.sub = g.sub; i.meta = Lib.count(g.tracks.size()); i.cover = g.cover; i.data = g;
        return i;
    }

    private static Item section(String s) { Item i = new Item(); i.header = true; i.title = s; return i; }

    // ---------- the page transition (250 ms, accelerate-decelerate; swiped: settles at the finger's speed) ----------

    /** progress 1 = the top page fully in; 0 = the page under it. dir: the side the top page comes from / leaves to */
    private void apply(float t) {
        transition = t;
        int n = stack.size();
        int w = getWidth();
        Page top = stack.get(n - 1), under = n > 1 ? stack.get(n - 2) : null;
        top.view.setTranslationX(transDir * (1 - t) * w);
        if (under != null) {
            under.view.setVisibility(VISIBLE);
            under.view.setTranslationX(-transDir * 0.3f * w * t);
            under.view.setAlpha(1 - 0.5f * t);
        }
        header.progress(t, transDir);
    }

    private void runTransition(float from, float to, float velocityPerSec) {
        stopTransition();
        final boolean pop = to == 0;
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
                    if (done) { linear = null; endTransition(pop); }
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
            @Override public void onAnimationEnd(Animator a) { if (anim == a) { anim = null; endTransition(pop); } }
        });
        anim.start();
    }

    private void stopTransition() {
        if (anim != null) { ValueAnimator a = anim; anim = null; a.cancel(); }
        if (linear != null) { Choreographer.getInstance().removeFrameCallback(linear); linear = null; }
        if (transition >= 0) { transition = -1; }
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
        header.progress(1, 1);
        topChanged();
    }

    /** the top page changed: the search field, the floating buttons */
    private void topChanged() {
        Page top = stack.get(stack.size() - 1);
        boolean search = top.kind.equals("search");
        searchField.setVisibility(search ? VISIBLE : GONE);
        if (!search) hideKeyboard();
        fab.update();
        header.invalidate();
        requestLayout();
    }

    // ---------- swipe either way for the parent list ----------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (stack.size() < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); decided = false; swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (decided) return swiping;
                if (e.getPointerCount() > 1) { decided = true; return false; }
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    decided = true;
                    Page top = stack.get(stack.size() - 1);
                    boolean onStrip = top.view.stripShown() && downX > getWidth() - th.px(36);
                    swiping = !onStrip && Math.abs(dx) > Math.abs(dy) * 1.4f && transition < 0 && downY < mini.getTop() - th.px(4);
                    if (swiping) {
                        transDir = dx > 0 ? 1 : -1;
                        header.set(stack.get(stack.size() - 2), top);
                        vt = VelocityTracker.obtain();
                        downX = e.getX() - (dx > 0 ? slop : -slop);
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
                float p = Math.max(0, Math.min(1, transDir * (e.getX() - downX) / w));
                apply(1 - p);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity() * transDir, vdp = vx / th.dp;
                float progress = Math.max(0, Math.min(1, transDir * (e.getX() - downX) / w)); // how far back
                boolean commit = Math.abs(vdp) >= Motion.FLING_DP_S ? vx > 0 : progress > Motion.COMMIT_FRACTION;
                boolean nearEnd = commit ? progress > 1 - Motion.SETTLE_NEAR_END : progress < Motion.SETTLE_NEAR_END;
                float perSec = nearEnd || Math.abs(vdp) < Motion.FLING_DP_S ? 0 : vx / w;
                float t = 1 - progress;
                transition = t;
                runTransition(t, commit ? 0 : 1, perSec);
                vt.recycle(); vt = null;
                swiping = false;
                return true;
            }
            default: return true;
        }
    }

    // ---------- selection ----------

    void setSelecting(Page p, boolean on) {
        p.selecting = on;
        p.selected.clear();
        p.view.invalidate();
        fab.update();
    }

    private List<Lib.Track> selectedTracks(Page p) {
        List<Integer> idx = new ArrayList<>(p.selected);
        Collections.sort(idx);
        List<Lib.Track> out = new ArrayList<>();
        for (int i : idx) if (p.items.get(i).data instanceof Lib.Track) out.add((Lib.Track) p.items.get(i).data);
        return out;
    }

    /** every song a page holds (a list of albums: all their songs) */
    private List<Lib.Track> allTracks(Page p) {
        if (!p.kind.equals("groups")) return p.trackList;
        List<Lib.Track> out = new ArrayList<>();
        for (Lib.Group g : p.groups) out.addAll(g.tracks);
        return out;
    }

    // ---------- list options: sort, zoom ----------

    void listOptions() {
        final Page p = stack.get(stack.size() - 1);
        Sheet s = new Sheet(getContext(), "List options");
        s.add(Sheet.Item.header(p.title));
        if (p.kind.equals("tracks") || p.kind.equals("groups")) {
            s.add(Sheet.Item.header("Sort by"));
            String[] sorts = p.kind.equals("groups") ? GROUP_SORTS : TRACK_SORTS;
            for (int i = 0; i < sorts.length; i++) {
                final int k = i;
                s.add(new Sheet.Item(0, sorts[i], () -> setSort(p, k, p.desc)).radio(p.sort == i));
            }
            s.add(new Sheet.Item(Icons.SORT, "Descending", () -> setSort(p, p.sort < 0 ? 0 : p.sort, !p.desc)).tick(p.desc));
        }
        if (!p.kind.equals("root")) {
            s.add(Sheet.Item.header("Zoom (or pinch the list)"));
            for (int i = 0; i < ZOOMS.length; i++) {
                final int k = i;
                s.add(new Sheet.Item(0, ZOOMS[i], () -> p.view.zoomTo(k, -1)).radio(p.zoom == i));
            }
        }
        if (!allTracks(p).isEmpty()) {
            s.add(Sheet.Item.header("This list"));
            s.add(new Sheet.Item(Icons.PLAY_NEXT, "Play next", () -> { engine.playNext(allTracks(p)); stage.toast("Plays next: " + Lib.count(allTracks(p).size())); }));
            s.add(new Sheet.Item(Icons.QUEUE, "Add to queue", () -> { engine.enqueue(allTracks(p)); stage.toast("Queued: " + Lib.count(allTracks(p).size())); }));
        }
        stage.showSheet(s);
    }

    private void setSort(Page p, int sort, boolean desc) {
        p.sort = sort; p.desc = desc;
        prefs.edit().putInt("sort_" + p.sortKey(), sort).putBoolean("desc_" + p.sortKey(), desc).apply();
        if (p.kind.equals("groups")) fillGroups(p); else fillTracks(p);
    }

    void groupMenu(Lib.Group g, String kind) {
        Sheet s = new Sheet(getContext(), g.title);
        s.add(new Sheet.Item(Icons.PLAY, "Play", () -> engine.playList(g.tracks, 0, g.title, kind + ":" + g.key)));
        s.add(new Sheet.Item(Icons.SHUFFLE, "Shuffle", () -> { engine.setShuffle(true); engine.playList(g.tracks, -1, g.title, kind + ":" + g.key); }));
        s.add(new Sheet.Item(Icons.PLAY_NEXT, "Play next", () -> { engine.playNext(g.tracks); stage.toast("Plays next: " + g.title); }));
        s.add(new Sheet.Item(Icons.QUEUE, "Add to queue", () -> { engine.enqueue(g.tracks); stage.toast("Queued: " + g.title); }));
        stage.showSheet(s);
    }

    // ---------- updates ----------

    @Override public void onTrack(Lib.Track t) { mini.trackChanged(); for (Page p : stack) p.view.invalidate(); }
    @Override public void onState(boolean playing) { mini.invalidate(); }
    @Override public void onExtras() {
        Page r = stack.get(0), fresh = root();
        r.items.clear(); r.items.addAll(fresh.items);
        r.view.changed();
    }

    @Override public void onLibrary() {
        // a new scan: rebuild the root (deeper pages keep what they show until reopened)
        Page r = stack.get(0), fresh = root();
        r.items.clear(); r.items.addAll(fresh.items); r.sub = fresh.sub;
        r.view.changed();
        header.invalidate();
    }

    // ---------- layout ----------

    @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), MeasureSpec.getSize(hs)); }

    private int dp(float v) { return Math.round(v * th.dp); }

    @Override
    protected void onLayout(boolean ch, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int hh = insetTop + dp(72);
        int mh = dp(72);
        int miniTop = h - reserveBottom - mh;
        Ui.place(header, 0, 0, w, hh);
        int bs = dp(48), by = insetTop + dp(12);
        Ui.place(header.back, dp(6), by, bs, bs);
        Ui.place(searchField, dp(58), insetTop + dp(12), w - dp(58) - dp(16), dp(48));
        Ui.place(mini, 0, miniTop, w, mh);
        int fh = dp(52);
        fab.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
        int fw = fab.getMeasuredWidth();
        fab.layout((w - fw) / 2, miniTop - dp(10) - fh, (w + fw) / 2, miniTop - dp(10));
        for (Page p : stack) {
            Ui.place(p.view, 0, 0, w, miniTop);
            p.view.padTop = hh;
            p.view.padBottom = (fab.getVisibility() == VISIBLE ? fh + dp(22) : dp(12));
            p.view.fabTop = fab.getTop();
        }
        if (transition < 0) header.set(stack.get(stack.size() - 1), stack.get(stack.size() - 1));
    }

    @Override protected void onDraw(Canvas c) { th.drawPanel(c, getWidth(), getHeight()); }

    /** The mini player's cover, in this view's coordinates (for the album art transition). */
    RectF miniCoverRect() { return new RectF(mini.cover.left, mini.getTop() + mini.cover.top, mini.cover.right, mini.getTop() + mini.cover.bottom); }

    // =====================================================================================================
    private static final WeakHashMap<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
    private final Matrix shaderM = new Matrix();
    private final Paint coverP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** A cover with rounded corners, through a shader (much cheaper than clipping to a path for every row). */
    void drawRounded(Canvas c, Bitmap b, RectF r, float radius) {
        BitmapShader s = shaders.get(b);
        if (s == null) { s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaders.put(b, s); }
        float side = Math.min(b.getWidth(), b.getHeight()), k = r.width() / side;
        shaderM.setScale(k, k);
        shaderM.postTranslate(r.left - (b.getWidth() - side) / 2 * k, r.top - (b.getHeight() - side) / 2 * k);
        s.setLocalMatrix(shaderM);
        coverP.setShader(s);
        c.drawRoundRect(r, radius, radius, coverP);
        coverP.setShader(null);
    }

    // =====================================================================================================
    /** A list of rows (or a grid), drawn straight on the canvas (only what is visible), scrolled and flung with
     *  the platform's stretch at the ends; pinch to zoom; the A-Z strip down the right. */
    final class ListPage extends View {
        final Page page;
        float scroll;
        int padTop, padBottom, fabTop;
        private final OverScroller scroller;
        private final EdgeEffect edgeTop, edgeBottom;
        private final ScaleGestureDetector scale;
        private VelocityTracker v;
        private float lastY, startY, startX;
        private boolean dragging, pinching, stripTouch;
        private int pressed = -1;
        private float pinchAcc = 1;
        /** zoom shown (animates between list sizes) */
        private float z;
        private ValueAnimator zoomAnim;
        private char stripLetter;
        private float stripY;
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), sub = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                meta = new TextPaint(Paint.ANTI_ALIAS_FLAG), right = new TextPaint(Paint.ANTI_ALIAS_FLAG), strip = new TextPaint(Paint.ANTI_ALIAS_FLAG),
                bubble = new TextPaint(Paint.ANTI_ALIAS_FLAG), section = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int touchSlop;
        private final Runnable longPress = this::onLongPress;
        private int longPressRow = -1;

        ListPage(Context c, Page p) {
            super(c);
            page = p;
            z = p.zoom == 3 ? 3 : p.zoom;
            scroller = new OverScroller(c);
            edgeTop = new EdgeEffect(c); edgeBottom = new EdgeEffect(c);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            title.setColor(th.text);
            sub.setTypeface(th.uiFace); sub.setColor(th.textDim);
            meta.setTypeface(th.uiFace); meta.setColor(Theme.alpha(th.textDim, 0.8f));
            right.setTypeface(th.numFace); right.setTextSize(th.px(11.5f)); right.setColor(th.textDim); right.setTextAlign(Paint.Align.RIGHT);
            strip.setTypeface(th.uiBold); strip.setTextSize(th.px(10.5f)); strip.setColor(th.textDim); strip.setTextAlign(Paint.Align.CENTER);
            bubble.setTypeface(th.titleFace); bubble.setTextSize(th.px(38)); bubble.setColor(th.text); bubble.setTextAlign(Paint.Align.CENTER);
            section.setTypeface(th.uiMedium); section.setTextSize(th.px(11.5f)); section.setLetterSpacing(0.16f); section.setColor(th.accent);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                    if (page.kind.equals("root")) return false;
                    pinching = true; pinchAcc = 1;
                    removeCallbacks(longPress); pressed = -1;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                @Override public boolean onScale(ScaleGestureDetector d) {
                    pinchAcc *= d.getScaleFactor();
                    if (pinchAcc > 1.28f && page.zoom < 3) { zoomTo(page.zoom + 1, d.getFocusY()); pinchAcc = 1; }
                    else if (pinchAcc < 0.78f && page.zoom > 0) { zoomTo(page.zoom - 1, d.getFocusY()); pinchAcc = 1; }
                    return true;
                }
            });
        }

        void changed() {
            for (Item i : page.items) i.eWidth = -1;
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { edgeTop.setSize(w, h); edgeBottom.setSize(w, h); }

        // ---- geometry: rows (zoom 0..2, animating between) or a grid (3) ----

        boolean grid() { return z > 2.5f; }
        int cols() { return 2; }
        float rowH() {
            if (page.kind.equals("root")) return th.px(72);
            return th.px(z <= 1 ? 60 + 16 * z : 76 + 26 * (z - 1));
        }
        float coverS() {
            if (page.kind.equals("root")) return th.px(48);
            return th.px(z <= 1 ? 44 + 14 * z : 58 + 24 * (z - 1));
        }
        float tileW() { return (getWidth() - th.px(16) * 2 - th.px(12)) / 2f; }
        float tileH() { return tileW() + th.px(56); }
        float sectionH() { return th.px(40); }

        float itemTop(int i) {
            if (grid()) return padTop + (i / cols()) * tileH();
            float y = padTop;
            if (hasSections()) { for (int k = 0; k < i; k++) y += page.items.get(k).header ? sectionH() : rowH(); return y; }
            return padTop + i * rowH();
        }

        boolean hasSections() { return page.kind.equals("search"); }

        float contentH() {
            int n = page.items.size();
            if (grid()) return padTop + ((n + cols() - 1) / cols()) * tileH() + padBottom;
            return itemTop(n) + padBottom;
        }

        float maxScroll() { return Math.max(0, contentH() - getHeight()); }

        int itemAt(float x, float y) {
            float cy = y + scroll;
            if (y < padTop) return -1;
            int n = page.items.size();
            if (grid()) {
                int row = (int) ((cy - padTop) / tileH());
                int col = x < getWidth() / 2f ? 0 : 1;
                int i = row * cols() + col;
                return i >= 0 && i < n ? i : -1;
            }
            if (hasSections()) {
                float yy = padTop;
                for (int k = 0; k < n; k++) { float h = page.items.get(k).header ? sectionH() : rowH(); if (cy >= yy && cy < yy + h) return page.items.get(k).header ? -1 : k; yy += h; }
                return -1;
            }
            int i = (int) ((cy - padTop) / rowH());
            return i >= 0 && i < n ? i : -1;
        }

        boolean stripShown() { return page.letters && page.items.size() > 25 && !grid(); }

        void scrollTo(Lib.Group g) {
            int i = -1;
            for (int k = 0; k < page.items.size(); k++) if (page.items.get(k).data == g) { i = k; break; }
            final int at = i;
            if (at >= 0) post(() -> { scroll = Math.max(0, Math.min(maxScroll(), itemTop(at) - getHeight() * 0.35f)); invalidate(); });
        }

        void scrollToTrack(Lib.Track t) {
            int i = -1;
            for (int k = 0; k < page.items.size(); k++) if (page.items.get(k).data == t) { i = k; break; }
            if (i >= 0) {
                final int at = i;
                post(() -> { scroll = Math.max(0, Math.min(maxScroll(), itemTop(at) - (getHeight() - rowH()) * 0.4f)); invalidate(); });
            }
        }

        /** Pinch / list options: another size, the row under the fingers staying put. */
        void zoomTo(int zi, float focusY) {
            if (zi == page.zoom || zi < 0 || zi > 3) return;
            Ui.tick(this);
            page.zoom = zi;
            prefs.edit().putInt("zoom_" + page.zoomKey(), zi).apply();
            for (Item i : page.items) i.eWidth = -1;
            float fy = focusY < 0 ? getHeight() / 2f : focusY;
            if ((zi == 3) != grid() || Motion.speed == Motion.SPEED_OFF) {
                // to or from the grid: cross-fade
                final int anchor = itemAt(getWidth() / 4f, fy);
                setAlpha(0.2f);
                z = zi;
                if (anchor >= 0) scroll = itemTop(anchor) - fy + padTop * 0;
                scroll = Math.max(0, Math.min(maxScroll(), scroll));
                animate().alpha(1).setDuration(Motion.ms(Motion.STATE)).start();
                invalidate();
                return;
            }
            final int anchor = Math.max(0, itemAt(getWidth() / 2f, fy));
            final float frac = (fy + scroll - itemTop(anchor)) / rowH();
            if (zoomAnim != null) zoomAnim.cancel();
            zoomAnim = ValueAnimator.ofFloat(z, zi);
            zoomAnim.setDuration(Motion.ms(Motion.PAGE));
            zoomAnim.setInterpolator(Motion.PAGE_EASE);
            zoomAnim.addUpdateListener(a -> {
                z = (float) a.getAnimatedValue();
                scroll = Math.max(0, Math.min(maxScroll(), itemTop(anchor) + frac * rowH() - fy));
                invalidate();
            });
            zoomAnim.start();
        }

        // ---- drawing ----

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int n = page.items.size();
            Lib.Track playing = engine.track();
            c.save();
            c.clipRect(0, padTop, w, h);
            boolean stripOn = stripShown();
            if (n == 0) {
                section.setTextAlign(Paint.Align.CENTER);
                c.drawText(page.kind.equals("search") ? "TYPE TO SEARCH" : "NOTHING HERE YET", w / 2f, padTop + th.px(60), section);
                section.setTextAlign(Paint.Align.LEFT);
            }
            if (grid()) {
                float tw = tileW(), tH = tileH();
                int firstRow = Math.max(0, (int) ((scroll - padTop) / tH)), lastRow = (int) ((scroll + h - padTop) / tH);
                for (int row = firstRow; row <= lastRow; row++) for (int col = 0; col < 2; col++) {
                    int i = row * 2 + col;
                    if (i >= n) break;
                    float x = th.px(16) + col * (tw + th.px(12)), y = padTop + row * tH - scroll;
                    drawTile(c, page.items.get(i), i, x, y, tw, playing);
                }
            } else {
                int first = 0;
                if (!hasSections()) first = Math.max(0, (int) ((scroll - padTop) / rowH()));
                float y = itemTop(first) - scroll;
                for (int i = first; i < n && y < h; i++) {
                    Item it = page.items.get(i);
                    float rh = it.header ? sectionH() : rowH();
                    if (y + rh > padTop) {
                        if (it.header) c.drawText(it.title.toUpperCase(Locale.ROOT), th.px(20), y + rh - th.px(12), section);
                        else drawRow(c, it, i, y, rh, w - (stripOn ? th.px(26) : 0), playing);
                    }
                    y += rh;
                }
            }
            c.restore();
            if (stripOn) drawStrip(c);
            boolean more = false;
            if (!edgeTop.isFinished()) { more |= edgeTop.draw(c); }
            if (!edgeBottom.isFinished()) {
                c.save(); c.rotate(180, w / 2f, h / 2f);
                more |= edgeBottom.draw(c);
                c.restore();
            }
            if (more) postInvalidateOnAnimation();
        }

        private void drawRow(Canvas c, Item it, int i, float top, float rh, float w, Lib.Track playing) {
            float pad = th.px(16), cy = top + rh / 2;
            boolean isPlaying = playing != null && it.data == playing;
            boolean sel = page.selecting && page.selected.contains(i);
            if (i == pressed || isPlaying || sel) {
                r.set(th.px(6), top + th.px(3), getWidth() - th.px(6), top + rh - th.px(3));
                th.drawChip(c, r, th.px(16), i == pressed, sel);
            }
            float cs = coverS(), rad = Math.min(th.px(16), cs * 0.2f);
            float x = pad;
            if (it.icon != 0) {
                r.set(x, cy - cs / 2, x + cs, cy + cs / 2);
                fill.setColor(Theme.alpha(th.accent, 0.16f));
                c.drawRoundRect(r, th.px(12), th.px(12), fill);
                fill.setColor(th.accent);
                Icons.draw(c, it.icon, r.centerX(), cy, cs * 0.5f, fill);
                x += cs + th.px(16);
            } else {
                r.set(x, cy - cs / 2, x + cs, cy + cs / 2);
                Bitmap b = it.cover == null ? null : Covers.get(it.cover, true, bm -> invalidate());
                if (b != null) drawRounded(c, b, r, rad);
                else {
                    fill.setColor(th.panelHi); c.drawRoundRect(r, rad, rad, fill);
                    fill.setColor(Theme.alpha(th.textDim, 0.5f));
                    Icons.draw(c, it.data instanceof Lib.Group && page.key.equals("artist") ? Icons.ARTIST : it.data instanceof Lib.Group && page.key.equals("folder") ? Icons.FOLDER : Icons.NOTE, r.centerX(), cy, cs * 0.42f, fill);
                }
                if (page.selecting) {
                    // the selection tick on the cover
                    fill.setColor(sel ? th.accentHi : Theme.alpha(th.ink, 0.55f));
                    c.drawCircle(r.right - th.px(4), r.top + th.px(4), th.px(10), fill);
                    if (sel) { fill.setColor(th.ink); Icons.draw(c, Icons.CHECK, r.right - th.px(4), r.top + th.px(4), th.px(14), fill); }
                }
                x += cs + th.px(14);
            }
            // sizes follow the zoom
            float zz = page.kind.equals("root") ? 1 : z;
            float ts = th.px(zz <= 1 ? 15 + zz : 16 + 2.5f * (zz - 1)), ss = th.px(zz <= 1 ? 12.5f + 0.5f * zz : 13 + (zz - 1));
            title.setTextSize(ts); title.setTypeface(zz > 1.5f ? th.uiBold : th.uiMedium);
            sub.setTextSize(ss);
            meta.setTextSize(th.px(12.5f));
            boolean showMeta = zz > 1.5f && !it.meta.isEmpty();
            boolean showRight = !showMeta && !it.right.isEmpty() && !(it.data instanceof Lib.Group);
            float rightW = showRight ? right.measureText(it.right) + th.px(12) : 0;
            float avail = w - x - pad - rightW;
            if (it.eWidth != avail || it.eSize != ts) {
                it.eTitle = TextUtils.ellipsize(it.title, title, avail, TextUtils.TruncateAt.END);
                it.eSub = TextUtils.ellipsize(it.sub, sub, avail, TextUtils.TruncateAt.END);
                it.eMeta = TextUtils.ellipsize(it.meta, meta, avail - th.px(18), TextUtils.TruncateAt.END);
                it.eWidth = avail; it.eSize = ts;
            }
            title.setColor(isPlaying ? th.accentHi : th.text);
            float gap = th.px(showMeta ? 6 : 3);
            float blockH = -title.ascent() + title.descent() + gap - sub.ascent() + sub.descent() + (showMeta ? gap - meta.ascent() + meta.descent() : 0);
            float ty = cy - blockH / 2 - title.ascent();
            c.drawText(it.eTitle, 0, it.eTitle.length(), x, ty, title);
            float sy = ty + title.descent() + gap - sub.ascent();
            c.drawText(it.eSub, 0, it.eSub.length(), x, sy, sub);
            if (showMeta) {
                float my = sy + sub.descent() + gap - meta.ascent();
                fill.setColor(meta.getColor());
                Icons.draw(c, Icons.NOTE, x + th.px(6), my + (meta.ascent() + meta.descent()) / 2, th.px(14), fill);
                c.drawText(it.eMeta, 0, it.eMeta.length(), x + th.px(18), my, meta);
            }
            if (showRight) c.drawText(it.right, w - pad, cy + th.px(4), right);
        }

        private void drawTile(Canvas c, Item it, int i, float x, float y, float tw, Lib.Track playing) {
            r.set(x, y + th.px(6), x + tw, y + th.px(6) + tw);
            float rad = th.px(18);
            if (i == pressed) { c.save(); c.scale(0.97f, 0.97f, r.centerX(), r.centerY()); }
            Bitmap b = it.cover == null ? null : Covers.get(it.cover, Covers.MEDIUM, bm -> invalidate());
            if (b == null && it.cover != null) b = Covers.get(it.cover, Covers.SMALL, bm -> invalidate());
            if (b != null) drawRounded(c, b, r, rad);
            else {
                fill.setColor(th.panelHi); c.drawRoundRect(r, rad, rad, fill);
                fill.setColor(Theme.alpha(th.textDim, 0.5f));
                Icons.draw(c, Icons.ALBUM, r.centerX(), r.centerY(), tw * 0.3f, fill);
            }
            if (i == pressed) c.restore();
            title.setTextSize(th.px(14.5f)); title.setTypeface(th.uiBold);
            sub.setTextSize(th.px(12.5f));
            if (it.eWidth != -tw) {
                it.eTitle = TextUtils.ellipsize(it.title, title, tw - th.px(4), TextUtils.TruncateAt.END);
                it.eSub = TextUtils.ellipsize(it.sub, sub, tw - th.px(4), TextUtils.TruncateAt.END);
                it.eWidth = -tw;
            }
            boolean isPlaying = playing != null && it.data instanceof Lib.Group && ((Lib.Group) it.data).tracks.contains(playing);
            title.setColor(isPlaying ? th.accentHi : th.text);
            float ty = r.bottom + th.px(8) - title.ascent();
            c.drawText(it.eTitle, 0, it.eTitle.length(), x + th.px(2), ty, title);
            c.drawText(it.eSub, 0, it.eSub.length(), x + th.px(2), ty + title.descent() + th.px(2) - sub.ascent(), sub);
        }

        private static final String LETTERS = "^ABCDEFGHIJKLMNOPQRSTUVWXYZ#";

        private float stripTop() { return padTop + th.px(8); }
        private float stripBottom() { return fabTop - th.px(10); }

        private void drawStrip(Canvas c) {
            float x = getWidth() - th.px(14), t = stripTop(), b = stripBottom();
            float step = (b - t) / LETTERS.length();
            for (int i = 0; i < LETTERS.length(); i++) {
                char ch = LETTERS.charAt(i);
                float cy = t + step * (i + 0.5f);
                strip.setColor(stripTouch && ch == stripLetter ? th.accentHi : th.textDim);
                if (ch == '^') {
                    fill.setColor(strip.getColor());
                    fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(th.px(1.6f));
                    c.drawLine(x - th.px(4), cy + th.px(2), x, cy - th.px(2), fill);
                    c.drawLine(x, cy - th.px(2), x + th.px(4), cy + th.px(2), fill);
                    fill.setStyle(Paint.Style.FILL);
                } else c.drawText(String.valueOf(ch), x, cy - (strip.ascent() + strip.descent()) / 2, strip);
            }
            if (stripTouch && stripLetter != 0 && stripLetter != '^') {
                // the letter, big, beside the finger
                float by = Math.max(t + th.px(36), Math.min(b - th.px(36), stripY));
                r.set(getWidth() - th.px(110), by - th.px(34), getWidth() - th.px(42), by + th.px(34));
                th.drawSheet(c, r, th.px(22));
                c.drawText(String.valueOf(stripLetter), r.centerX(), r.centerY() - (bubble.ascent() + bubble.descent()) / 2, bubble);
            }
        }

        private void stripJump(float y) {
            float t = stripTop(), b = stripBottom();
            int i = (int) ((y - t) / ((b - t) / LETTERS.length()));
            i = Math.max(0, Math.min(LETTERS.length() - 1, i));
            char ch = LETTERS.charAt(i);
            stripY = y;
            if (ch != stripLetter) {
                stripLetter = ch;
                Ui.tick(this);
                int target = 0;
                if (ch != '^') {
                    target = -1;
                    for (int k = 0; k < page.items.size(); k++) {
                        char l = page.items.get(k).letter;
                        boolean hit = page.desc ? (ch == '#' ? l == '#' : l != '#' && l <= ch) : (ch == '#' ? l == '#' : l != '#' && l >= ch);
                        if (hit) { target = k; break; }
                    }
                    if (target < 0) target = page.items.size() - 1;
                }
                scroller.abortAnimation();
                scroll = Math.max(0, Math.min(maxScroll(), itemTop(target) - padTop));
            }
            invalidate();
        }

        // ---- touch: scroll, fling, tap, long press, pinch, the strip ----

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

        private void onLongPress() {
            int row = longPressRow;
            if (row < 0 || dragging || pinching || row >= page.items.size()) return;
            pressed = -1;
            invalidate();
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            Item it = page.items.get(row);
            longPressRow = -2; // consumed: no tap on release
            if (it.data instanceof Lib.Track) stage.trackMenu((Lib.Track) it.data);
            else if (it.data instanceof Lib.Group) groupMenu((Lib.Group) it.data, page.kind.equals("groups") ? page.key : groupKindOf((Lib.Group) it.data));
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            if (v == null) v = VelocityTracker.obtain();
            v.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!scroller.isFinished()) { scroller.abortAnimation(); dragging = true; }
                    lastY = startY = e.getY(); startX = e.getX();
                    pinching = false;
                    stripTouch = stripShown() && e.getX() > getWidth() - th.px(32) && e.getY() > stripTop() && e.getY() < stripBottom();
                    if (stripTouch) { getParent().requestDisallowInterceptTouchEvent(true); stripLetter = 0; stripJump(e.getY()); return true; }
                    if (!dragging) {
                        pressed = itemAt(e.getX(), e.getY());
                        longPressRow = pressed;
                        postDelayed(pressShow, 60);
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    removeCallbacks(longPress);
                    pressed = -1;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (stripTouch) { stripJump(e.getY()); return true; }
                    if (pinching || e.getPointerCount() > 1) { lastY = e.getY(); return true; }
                    float y = e.getY();
                    if (!dragging && (Math.abs(y - startY) > touchSlop || Math.abs(e.getX() - startX) > touchSlop)) {
                        removeCallbacks(longPress);
                        if (Math.abs(y - startY) > touchSlop) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
                        pressed = -1; invalidate();
                    }
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
                    removeCallbacks(longPress);
                    if (stripTouch) { stripTouch = false; invalidate(); recycle(); return true; }
                    if (dragging) {
                        v.computeCurrentVelocity(1000);
                        float vy = -v.getYVelocity();
                        edgeTop.onRelease(); edgeBottom.onRelease();
                        scroller.fling(0, (int) scroll, 0, (int) vy, 0, 0, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
                        postInvalidateOnAnimation();
                    } else if (!pinching && longPressRow != -2) {
                        int row = itemAt(e.getX(), e.getY());
                        if (row >= 0) {
                            pressed = row; invalidate();
                            final Item it = page.items.get(row);
                            postDelayed(() -> { pressed = -1; invalidate(); }, Motion.SELECTOR_FADE);
                            open(it, page, row);
                        }
                    }
                    dragging = false; pinching = false;
                    recycle();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(pressShow);
                    removeCallbacks(longPress);
                    pressed = -1; dragging = false; pinching = false; stripTouch = false;
                    edgeTop.onRelease(); edgeBottom.onRelease();
                    recycle();
                    invalidate();
                    return true;
                default: return true;
            }
        }

        private void recycle() { if (v != null) { v.recycle(); v = null; } }

        private final Runnable pressShow = this::invalidate;
    }

    // =====================================================================================================
    /** The header: back, the page's title and what it holds. During a page change the two titles cross. */
    final class Header extends View {
        final Ui.Btn back;
        private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), subP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Page from, to;
        private float t = 1;
        private int dir = 1;
        private final RectF fieldR = new RectF();

        Header(Context c) {
            super(c);
            back = new Ui.Btn(c, Icons.BACK, Ui.Btn.PLAIN);
            back.iconDp = 26;
            titleP.setTypeface(th.titleFace); titleP.setTextSize(th.px(th.headerSize)); titleP.setColor(th.text);
            subP.setTypeface(th.uiFace); subP.setTextSize(th.px(12)); subP.setColor(th.textDim);
        }

        /** a = the page underneath, b = the top page (progress 1 shows b) */
        void set(Page a, Page b) { from = a; to = b; invalidate(); }
        void progress(float p, int d) { t = p; dir = d; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            th.drawPanel(c, getWidth(), getHeight());
            boolean showBack = stack.size() > 1;
            back.setAlpha(showBack ? 1 : 0);
            back.setClickable(showBack);
            float x = th.px(showBack ? 58 : 20), base = insetTop + th.px(42);
            Page top = stack.get(stack.size() - 1);
            if (top.kind.equals("search") && t >= 1) {
                fieldR.set(th.px(58), insetTop + th.px(12), getWidth() - th.px(16), insetTop + th.px(60));
                th.drawChip(c, fieldR, fieldR.height() / 2, false, false);
                return;
            }
            if (from != null && from != to && t < 1 && t >= 0) {
                // the old title leaves one way, the new one arrives from the other
                drawTitle(c, from, x - dir * th.px(40) * t, base, 1 - t);
                drawTitle(c, to, x + dir * th.px(40) * (1 - t), base, t);
            } else if (to != null) {
                drawTitle(c, to, x, base, 1);
            }
        }

        private void drawTitle(Canvas c, Page p, float x, float y, float a) {
            if (p.kind.equals("search")) return;
            int al = Math.round(255 * Math.max(0, Math.min(1, a)));
            titleP.setAlpha(al); subP.setAlpha(al);
            CharSequence t1 = TextUtils.ellipsize(p.title, titleP, getWidth() - x - th.px(20), TextUtils.TruncateAt.END);
            c.drawText(t1, 0, t1.length(), x, y, titleP);
            c.drawText(p.sub == null ? "" : p.sub, x, y + th.px(20), subP);
        }
    }

    // =====================================================================================================
    /** The floating buttons over the list: shuffle, play, search, select, list options; while selecting: play,
     *  play next, add to queue, done. */
    final class Fab extends ViewGroup {
        final Ui.Btn shuffle, play, search, select, more, sPlay, sNext, sQueue, sDone;

        Fab(Context c) {
            super(c);
            shuffle = new Ui.Btn(c, Icons.SHUFFLE, Ui.Btn.ROUND);
            play = new Ui.Btn(c, Icons.PLAY, Ui.Btn.ROUND);
            search = new Ui.Btn(c, Icons.SEARCH, Ui.Btn.ROUND);
            select = new Ui.Btn(c, 0, Ui.Btn.CHIP).label("Select");
            more = new Ui.Btn(c, Icons.MORE, Ui.Btn.ROUND);
            sPlay = new Ui.Btn(c, Icons.PLAY, Ui.Btn.CHIP).label("Play");
            sNext = new Ui.Btn(c, Icons.PLAY_NEXT, Ui.Btn.CHIP).label("Next");
            sQueue = new Ui.Btn(c, Icons.QUEUE, Ui.Btn.CHIP).label("Queue");
            sDone = new Ui.Btn(c, Icons.CLOSE, Ui.Btn.ROUND);
            for (Ui.Btn b : new Ui.Btn[]{ shuffle, play, search, select, more, sPlay, sNext, sQueue, sDone }) { b.iconDp = 20; addView(b); }
            shuffle.click(() -> {
                Page p = top();
                List<Lib.Track> all = allTracks(p);
                if (all.isEmpty()) return;
                engine.setShuffle(true);
                engine.playList(all, -1, p.title, p.kind.equals("tracks") ? p.key : "all");
                stage.toast("Shuffle: " + p.title);
            });
            play.click(() -> {
                Page p = top();
                List<Lib.Track> all = allTracks(p);
                if (all.isEmpty()) return;
                if (engine.shuffle) engine.setShuffle(false);
                engine.playList(all, 0, p.title, p.kind.equals("tracks") ? p.key : "all");
            });
            search.click(LibraryScreen.this::openSearch);
            select.click(() -> setSelecting(top(), true));
            more.click(LibraryScreen.this::listOptions);
            sPlay.click(() -> { Page p = top(); List<Lib.Track> s = selectedTracks(p); if (!s.isEmpty()) engine.playList(s, 0, p.title + " (selection)", "selection"); setSelecting(p, false); });
            sNext.click(() -> { Page p = top(); List<Lib.Track> s = selectedTracks(p); if (!s.isEmpty()) { engine.playNext(s); stage.toast("Plays next: " + Lib.count(s.size())); } setSelecting(p, false); });
            sQueue.click(() -> { Page p = top(); List<Lib.Track> s = selectedTracks(p); if (!s.isEmpty()) { engine.enqueue(s); stage.toast("Queued: " + Lib.count(s.size())); } setSelecting(p, false); });
            sDone.click(() -> setSelecting(top(), false));
        }

        Page top() { return stack.get(stack.size() - 1); }

        void update() {
            Page p = top();
            boolean show = !p.kind.equals("root") && !(p.kind.equals("search") && p.trackList.isEmpty());
            setVisibility(show ? VISIBLE : GONE);
            boolean sel = p.selecting;
            for (Ui.Btn b : new Ui.Btn[]{ shuffle, play, search, more }) b.setVisibility(sel ? GONE : VISIBLE);
            select.setVisibility(sel || !p.kind.equals("tracks") ? GONE : VISIBLE);
            for (Ui.Btn b : new Ui.Btn[]{ sPlay, sNext, sQueue, sDone }) b.setVisibility(sel ? VISIBLE : GONE);
            sPlay.label(sel && !p.selected.isEmpty() ? "Play " + p.selected.size() : "Play");
            requestLayout();
        }

        @Override protected void onMeasure(int ws, int hs) {
            int h = MeasureSpec.getSize(hs), w = 0, gap = dp(8);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                w += width((Ui.Btn) v, h) + gap;
            }
            setMeasuredDimension(Math.max(0, w - gap), h);
        }

        private int width(Ui.Btn b, int h) { return b.style == Ui.Btn.CHIP ? Math.round(b.label == null ? h : Math.max(h * 1.6f, dp(b.icon != 0 ? 40 : 28) + b.label.length() * dp(8.6f))) : h; }

        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int h = b - t, x = 0, gap = dp(8);
            for (int i = 0; i < getChildCount(); i++) {
                Ui.Btn v = (Ui.Btn) getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = width(v, h);
                Ui.place(v, x, 0, w, h);
                x += w + gap;
            }
        }
    }

    // =====================================================================================================
    /** The mini player: the song playing, play / pause, its progress (drag to seek). Tap: the player; swipe:
     *  the previous / next song; long press: the list it plays from. */
    final class MiniPlayer extends View implements Choreographer.FrameCallback {
        final RectF cover = new RectF();
        private final TextPaint t1 = new TextPaint(Paint.ANTI_ALIAS_FLAG), t2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF playR = new RectF(), barR = new RectF(), r = new RectF();
        boolean coverHidden;
        private long lastPx = -1;
        private float offset;          // the content dragged sideways
        private float downX, downY;
        private boolean dragging, seeking, longDone, playDown;
        private final int slop2;
        private VelocityTracker vt;
        private ValueAnimator slide;
        private final Runnable longPress = () -> {
            if (dragging || seeking) return;
            longDone = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            stage.openSource();
        };

        MiniPlayer(Context c) {
            super(c);
            slop2 = ViewConfiguration.get(c).getScaledTouchSlop();
            t1.setTypeface(th.titleFace); t1.setTextSize(th.px(18)); t1.setColor(th.text);
            t2.setTypeface(th.uiMedium); t2.setTextSize(th.px(12.5f)); t2.setColor(th.accent);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = th.px(46), y = th.px(8);
            cover.set(th.px(16), y, th.px(16) + s, y + s);
            playR.set(w - th.px(64), y - th.px(2), w - th.px(12), y + s + th.px(2));
            barR.set(th.px(16), h - th.px(10), w - th.px(16), h - th.px(4));
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); Choreographer.getInstance().postFrameCallback(this); }
        @Override protected void onDetachedFromWindow() { Choreographer.getInstance().removeFrameCallback(this); super.onDetachedFromWindow(); }

        @Override public void doFrame(long now) {
            if (engine.isPlaying() && getWidth() > 0 && LibraryScreen.this.getVisibility() == VISIBLE) {
                long d = engine.durationMs();
                long px = d > 0 ? engine.positionMs() * Math.round(barR.width()) / d : 0;
                if (px != lastPx) { lastPx = px; invalidate(); }
            }
            if (isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this);
        }

        /** a new song: it slides in from the side it was swiped to (or just appears) */
        void trackChanged() {
            if (slide == null && offset == 0) { invalidate(); return; }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            th.drawPanel(c, w, getHeight());
            fill.setColor(th.line);
            c.drawRect(0, 0, w, Math.max(1, th.px(0.8f)), fill);
            Lib.Track t = engine.track();
            int a = Math.round(255 * Math.max(0, 1 - Math.abs(offset) / (w * 0.5f)));
            c.save();
            c.translate(offset, 0);
            if (!coverHidden) {
                Bitmap b = t == null ? null : Covers.get(t.cover, true, bm -> invalidate());
                th.drawCover(c, cover, b, 0, null);
            }
            float x = cover.right + th.px(14), avail = playR.left - x - th.px(8);
            CharSequence s1 = TextUtils.ellipsize(t == null ? "Nothing playing" : t.title, t1, avail, TextUtils.TruncateAt.END);
            CharSequence s2 = TextUtils.ellipsize(t == null ? "" : t.artist, t2, avail, TextUtils.TruncateAt.END);
            t1.setAlpha(a); t2.setAlpha(a);
            c.drawText(s1, 0, s1.length(), x, cover.centerY() - th.px(3), t1);
            c.drawText(s2, 0, s2.length(), x, cover.centerY() + th.px(15), t2);
            c.restore();
            fill.setColor(playDown ? th.accentHi : th.text);
            Icons.draw(c, engine.isPlaying() ? Icons.PAUSE : Icons.PLAY, playR.centerX(), playR.centerY(), th.px(30), fill);
            // the progress: a track with a pill on it (drag to seek)
            long d = engine.durationMs();
            float p = d > 0 ? Math.min(1, engine.positionMs() / (float) d) : 0;
            fill.setColor(Theme.alpha(th.text, 0.14f));
            c.drawRoundRect(barR, barR.height() / 2, barR.height() / 2, fill);
            float px = barR.left + p * barR.width();
            fill.setColor(Theme.alpha(th.accent, 0.55f));
            r.set(barR.left, barR.top, Math.max(barR.left + barR.height(), px), barR.bottom);
            c.drawRoundRect(r, barR.height() / 2, barR.height() / 2, fill);
            fill.setColor(th.accentHi);
            r.set(px - th.px(12), barR.top - th.px(1.5f), px + th.px(12), barR.bottom + th.px(1.5f));
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); dragging = false; longDone = false;
                    seeking = e.getY() > barR.top - th.px(10);
                    playDown = !seeking && playR.contains(e.getX(), e.getY());
                    if (seeking) { getParent().requestDisallowInterceptTouchEvent(true); seekTo(e.getX()); }
                    else postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (seeking) { seekTo(e.getX()); return true; }
                    if (!dragging && Math.abs(e.getX() - downX) > slop2 && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                        dragging = true; playDown = false;
                        removeCallbacks(longPress);
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) { offset = e.getX() - downX; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(longPress);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    vt.recycle(); vt = null;
                    if (seeking) { seeking = false; return true; }
                    if (dragging) {
                        dragging = false;
                        float w = getWidth();
                        boolean commit = Math.abs(vx / th.dp) >= Motion.FLING_DP_S ? Math.signum(vx) == Math.signum(offset) : Math.abs(offset) > w * Motion.COMMIT_FRACTION;
                        if (commit && up) {
                            final int dir = offset < 0 ? 1 : -1;
                            animateOffset(-dir * w, () -> {
                                engine.step(dir, true);
                                offset = dir * w * 0.35f;
                                animateOffset(0, null);
                            });
                        } else animateOffset(0, null);
                        return true;
                    }
                    if (up && !longDone) {
                        if (playDown && playR.contains(e.getX(), e.getY())) engine.toggle();
                        else if (!playDown) stage.closeLibrary();
                    }
                    playDown = false;
                    invalidate();
                    return true;
                }
                default: return true;
            }
        }

        private void seekTo(float x) {
            long d = engine.durationMs();
            if (d <= 0) return;
            float p = Math.max(0, Math.min(1, (x - barR.left) / barR.width()));
            engine.seekTo((long) (p * d));
            invalidate();
        }

        private void animateOffset(float to, Runnable end) {
            if (slide != null) slide.cancel();
            slide = ValueAnimator.ofFloat(offset, to);
            slide.setDuration(Motion.ms(Motion.PAGE));
            slide.setInterpolator(Motion.PAGE_EASE);
            slide.addUpdateListener(a -> { offset = (float) a.getAnimatedValue(); invalidate(); });
            slide.addListener(new AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) { if (slide == a) slide = null; if (!cancelled && end != null) end.run(); }
            });
            slide.start();
        }
    }
}
