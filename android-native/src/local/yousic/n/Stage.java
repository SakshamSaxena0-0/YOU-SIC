package local.yousic.n;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The window's content, back to front: the theme's backdrop (painted once into a bitmap), the player, the
 * library, the lyrics and equalizer when open, the cover in flight between player and library, the bar along
 * the bottom, popups, toasts, and the theme's overlay (film edge, grain). Every screen asks the stage to open
 * the others and to show menus.
 */
@SuppressLint("ViewConstructor")
public final class Stage extends FrameLayout {
    public static final int RATE_THUMBS = 0, RATE_STARS = 1, RATE_OFF = 2;
    static final int REQ_VIS = 7;

    final Theme th;
    final App app;
    final Engine engine;
    final Effects fx;
    final Vis vis;
    final Backdrop backdrop;
    final PlayerScreen player;
    final LibraryScreen library;
    final FlyCover fly;
    final NavBar nav;
    final ToastView toastView;
    final Overlay overlay;
    LyricsScreen lyrics;
    EqScreen eq;
    private Sheet sheet;
    private float libT;           // 0 = player, 1 = library
    private ValueAnimator anim;
    private final RectF from = new RectF(), to = new RectF();
    private final SharedPreferences prefs;
    private int insetTop, insetBottom, ime;

    Stage(Context c, App app) {
        super(c);
        th = Theme.get(c);
        this.app = app;
        engine = app.engine;
        fx = app.fx;
        vis = app.vis;
        prefs = app.prefs;
        backdrop = new Backdrop(c);
        player = new PlayerScreen(c, engine, this);
        library = new LibraryScreen(c, engine, app.lib, this);
        fly = new FlyCover(c);
        nav = new NavBar(c);
        toastView = new ToastView(c);
        overlay = new Overlay(c);
        addView(backdrop);
        addView(player);
        addView(library);
        addView(fly, new LayoutParams(1, 1));
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM));
        addView(toastView);
        addView(overlay);
        library.setVisibility(INVISIBLE); // laid out (the mini player's cover is where the art flies) but not drawn
        fly.setVisibility(GONE);
        nav.library.click(this::navLibrary);
        nav.eq.click(this::toggleEq).longClick(this::eqMenu);
        nav.search.click(this::openSearch);
        nav.menu.click(this::mainMenu).longClick(this::themeMenu);
        if (visOn() && hasMic()) vis.start();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insetTop = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top;
        insetBottom = in.getInsets(WindowInsets.Type.systemBars()).bottom;
        ime = in.getInsets(WindowInsets.Type.ime()).bottom;
        nav.insetBottom = insetBottom;
        int reserve = nav.heightPx();
        player.insetTop = insetTop; player.reserveBottom = reserve;
        library.insetTop = insetTop; library.reserveBottom = ime > 0 ? ime : reserve;
        nav.setVisibility(ime > 0 ? INVISIBLE : VISIBLE);
        if (lyrics != null) { lyrics.insetTop = insetTop; lyrics.reserveBottom = reserve; }
        if (eq != null) { eq.insetTop = insetTop; eq.reserveBottom = reserve; }
        player.requestLayout(); library.requestLayout(); nav.requestLayout();
        return in;
    }

    public boolean libraryOpen() { return libT > 0.5f; }

    /** Back: a popup, the lyrics, the equalizer, a page back in the library, the library; false: nowhere to go. */
    public boolean back() {
        if (sheet != null) { sheet.dismiss(); return true; }
        if (lyrics != null) { closeLyrics(); return true; }
        if (eq != null) { closeEq(); return true; }
        if (libraryOpen()) {
            if (!library.back()) closeLibrary();
            return true;
        }
        return false;
    }

    // ---------- the bottom bar ----------

    private void navLibrary() {
        if (lyrics != null) closeLyrics();
        if (eq != null) { closeEq(); if (libraryOpen()) return; }
        if (!libraryOpen()) openLibrary();
        else if (library.atRoot()) closeLibrary();
        else library.toRoot();
    }

    void openSearch() {
        if (lyrics != null) closeLyrics();
        if (eq != null) closeEq();
        if (!libraryOpen()) openLibrary();
        library.openSearch();
    }

    // ---------- player <-> library ----------

    void openLibrary() { animateLib(1, Motion.AA_TO_LIB); }
    void closeLibrary() { library.hideKeyboard(); animateLib(0, Motion.LIB_TO_AA); }

    /** the list the song plays from, scrolled to it */
    void openSource() {
        if (lyrics != null) closeLyrics();
        library.showSource();
        if (!libraryOpen()) openLibrary();
    }

    void openArtist(Lib.Track t) {
        library.showArtist(t);
        if (!libraryOpen()) openLibrary();
    }

    void openGroup(String kind, Lib.Group g) {
        if (g == null) return;
        library.showGroup(kind, g);
        if (!libraryOpen()) openLibrary();
    }

    void openQueue() {
        library.toRoot();
        library.open(library.stack.get(0).items.get(library.stack.get(0).items.size() - 1), library.stack.get(0), 0);
        if (!libraryOpen()) openLibrary();
    }

    private void animateLib(float target, int duration) {
        if (anim != null) anim.cancel();
        // the cover flies from the player's art to the mini player's
        Lib.Track t = engine.track();
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
        player.onShown();
        // both screens fade: as GPU layers that is a cheap blend, not two full-screen offscreen passes a frame
        player.setLayerType(LAYER_TYPE_HARDWARE, null);
        library.setLayerType(LAYER_TYPE_HARDWARE, null);
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
                player.pager.cards[1].setVisibility(VISIBLE);
                player.setLayerType(LAYER_TYPE_NONE, null);
                library.setLayerType(LAYER_TYPE_NONE, null);
                if (libT >= 1) player.setVisibility(INVISIBLE); // covered: not drawn at all
                if (libT <= 0) { library.setVisibility(INVISIBLE); player.onShown(); }
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

    // ---------- lyrics (250 ms in, 150 ms out) and the equalizer ----------

    void openLyrics() {
        if (lyrics != null) return;
        if (libraryOpen()) closeLibrary();
        lyrics = new LyricsScreen(getContext(), engine, this);
        lyrics.insetTop = insetTop; lyrics.reserveBottom = nav.heightPx();
        addView(lyrics, indexOfChild(fly));
        lyrics.open();
        lyrics.setAlpha(0);
        lyrics.setTranslationY(getHeight() * 0.12f);
        lyrics.animate().alpha(1).translationY(0).setDuration(Motion.ms(Motion.AA_TO_LYRICS)).setInterpolator(Motion.EASE_OUT).start();
    }

    void closeLyrics() {
        final LyricsScreen l = lyrics;
        if (l == null) return;
        lyrics = null;
        l.close();
        l.animate().alpha(0).translationY(getHeight() * 0.12f).setDuration(Motion.ms(Motion.LYRICS_TO_AA)).setInterpolator(Motion.EASE_IN)
                .withEndAction(() -> removeView(l)).start();
    }

    void toggleEq() { if (eq != null) closeEq(); else openEq(); }

    void openEq() {
        if (eq != null) return;
        if (lyrics != null) closeLyrics();
        eq = new EqScreen(getContext(), fx, this);
        eq.insetTop = insetTop; eq.reserveBottom = nav.heightPx();
        addView(eq, indexOfChild(fly));
        eq.setAlpha(0);
        eq.setTranslationY(th.px(40));
        eq.animate().alpha(1).translationY(0).setDuration(Motion.ms(Motion.STATE)).setInterpolator(Motion.EASE_OUT).start();
        nav.eq.set(true);
        player.refreshSlots();
    }

    void closeEq() {
        final EqScreen e = eq;
        if (e == null) return;
        eq = null;
        nav.eq.set(false);
        player.refreshSlots();
        e.animate().alpha(0).translationY(th.px(40)).setDuration(Motion.ms(Motion.POPUP_EXIT)).setInterpolator(Motion.EASE_IN)
                .withEndAction(() -> removeView(e)).start();
    }

    // ---------- popups ----------

    void showSheet(Sheet s) {
        if (sheet != null) { final Sheet old = sheet; sheet = null; old.remover = () -> removeView(old); old.dismiss(); }
        sheet = s;
        s.topInset = insetTop;
        s.bottomInset = nav.heightPx() - Math.round(th.px(8));
        s.remover = () -> { removeView(s); if (sheet == s) sheet = null; };
        addView(s, indexOfChild(toastView));
        s.show();
    }

    void toast(String text) { toastView.show(text); }

    // ---------- settings ----------

    int ratingMode() { return prefs.getInt("rating", RATE_THUMBS); }

    SharedPreferences prefs() { return prefs; }

    boolean visOn() { return prefs.getBoolean("vis", false); }

    private boolean hasMic() { return getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    void toggleVis() { setVis(!visOn()); }

    void setVis(boolean on) {
        if (on && !hasMic()) {
            toast("Android asks for the microphone to show the music's visualizer");
            ((Activity) getContext()).requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_VIS);
            return;
        }
        prefs.edit().putBoolean("vis", on).apply();
        if (on) { if (!vis.start()) toast("The visualizer is not available"); } else vis.stop();
        player.onExtras();
        player.vis.kick();
    }

    void onPermission(int req, boolean granted) {
        if (req == REQ_VIS && granted) setVis(true);
    }

    // ---------- menus ----------

    void shuffleMenu() {
        Sheet s = new Sheet(getContext(), "Shuffle");
        s.add(new Sheet.Item(Icons.SHUFFLE_OFF, "Shuffle off", () -> engine.setShuffle(false)).radio(!engine.shuffle));
        s.add(new Sheet.Item(Icons.SHUFFLE, "Shuffle songs in this list", () -> engine.setShuffle(true)).radio(engine.shuffle));
        s.add(new Sheet.Item(Icons.NOTE, "Shuffle all songs", engine::shuffleAll));
        showSheet(s);
    }

    void repeatMenu() {
        Sheet s = new Sheet(getContext(), "Repeat");
        s.add(new Sheet.Item(Icons.REPEAT, "No repeat", () -> engine.setRepeat(Engine.REPEAT_OFF)).radio(engine.repeat == Engine.REPEAT_OFF));
        s.add(new Sheet.Item(Icons.REPEAT, "Repeat list", () -> engine.setRepeat(Engine.REPEAT_ALL)).radio(engine.repeat == Engine.REPEAT_ALL));
        s.add(new Sheet.Item(Icons.REPEAT_ADVANCE, "Advance list (then the next " + engine.categoryKind() + ")", () -> engine.setRepeat(Engine.REPEAT_ADVANCE)).radio(engine.repeat == Engine.REPEAT_ADVANCE));
        s.add(new Sheet.Item(Icons.REPEAT_ONE, "Repeat song", () -> engine.setRepeat(Engine.REPEAT_ONE)).radio(engine.repeat == Engine.REPEAT_ONE));
        showSheet(s);
    }

    void visMenu() {
        Sheet s = new Sheet(getContext(), "Visualization");
        s.add(new Sheet.Item(Icons.VIS, "Spectrum behind the controls", null).toggle(visOn()).stay()).action = () -> { setVis(!visOn()); if (sheet != null) sheet.dismiss(); };
        showSheet(s);
    }

    void sleepTimer() {
        Sheet s = new Sheet(getContext(), "Sleep timer");
        long left = engine.sleepRemainingMs();
        if (engine.sleepOn()) s.add(Sheet.Item.header(engine.sleepingAfterSong() ? "Stops when this song ends" : "Stops in " + PlayerScreen.fmt(left)));
        s.add(new Sheet.Item(Icons.CLOSE, "Off", () -> { engine.setSleep(0); }).radio(!engine.sleepOn()));
        for (int m : new int[]{ 15, 30, 45, 60, 90, 120 }) {
            final int mm = m;
            s.add(new Sheet.Item(Icons.TIMER, m < 60 ? m + " minutes" : m == 60 ? "1 hour" : m / 60f == m / 60 ? m / 60 + " hours" : (m / 60f) + " hours",
                    () -> { engine.setSleep(mm); toast("Music stops in " + mm + " min"); }));
        }
        s.add(new Sheet.Item(Icons.NOTE, "When this song ends", () -> { engine.sleepAfterSong(); toast("Music stops after this song"); }).radio(engine.sleepingAfterSong()));
        showSheet(s);
    }

    void trackMenu(Lib.Track t) {
        if (t == null) return;
        Sheet s = new Sheet(getContext(), t.title);
        List<Lib.Track> one = new ArrayList<>();
        one.add(t);
        s.add(new Sheet.Item(Icons.PLAY_NEXT, "Play next", () -> { engine.playNext(one); toast("Plays next: " + t.title); }));
        s.add(new Sheet.Item(Icons.QUEUE, "Add to queue", () -> { engine.enqueue(one); toast("Queued: " + t.title); }));
        int r = engine.rating(t);
        if (ratingMode() == RATE_STARS) s.add(new Sheet.Item(Icons.STAR, "Rate", () -> starsSheet(t)).value(r > 0 ? r + " ★" : ""));
        else s.add(new Sheet.Item(Icons.THUMB_UP, r > 0 ? "Unlike" : "Like", () -> engine.setRating(t, r > 0 ? 0 : 5)));
        s.add(new Sheet.Item(Icons.ALBUM, "Go to album", () -> openGroup("album", find(app.lib.albums, t.albumKey))).value(t.album));
        s.add(new Sheet.Item(Icons.ARTIST, "Go to artist", () -> openArtist(t)).value(t.artist));
        s.add(new Sheet.Item(Icons.FOLDER, "Go to folder", () -> openGroup("folder", find(app.lib.folders, t.folder))).value(new File(t.folder).getName()));
        if (t == engine.track()) s.add(new Sheet.Item(Icons.LYRICS, "Lyrics", this::openLyrics));
        s.add(new Sheet.Item(Icons.INFO, "Song info", () -> audioInfo(t)));
        showSheet(s);
    }

    private static Lib.Group find(List<Lib.Group> gs, String key) {
        for (Lib.Group g : gs) if (g.key.equals(key)) return g;
        return null;
    }

    void starsSheet(Lib.Track t) {
        Sheet s = new Sheet(getContext(), "Rate: " + t.title);
        int r = engine.rating(t);
        for (int i = 5; i >= 1; i--) {
            final int k = i;
            s.add(new Sheet.Item(Icons.STAR_FILLED, new String(new char[i]).replace("\0", "★"), () -> engine.setRating(t, k)).radio(r == i));
        }
        s.add(new Sheet.Item(Icons.CLOSE, "No rating", () -> engine.setRating(t, 0)).radio(r <= 0));
        showSheet(s);
    }

    void audioInfo(Lib.Track t) {
        if (t == null) return;
        Sheet s = new Sheet(getContext(), "Audio info");
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Title", t.title); m.put("Artist", t.artist); m.put("Album", t.album); m.put("Album artist", t.albumArtist);
        m.put("Genre", t.genre); m.put("Year", t.year > 0 ? String.valueOf(t.year) : "");
        m.put("Track", t.track > 0 ? t.track + (t.disc > 0 ? "  (disc " + t.disc + ")" : "") : "");
        m.put("Length", PlayerScreen.fmt((long) (t.duration * 1000)));
        String codec = t.codec == null || t.codec.isEmpty() ? t.container : t.codec;
        m.put("Format", codec + (t.lossless ? "  (lossless)" : ""));
        m.put("Sample rate", t.sampleRate > 0 ? String.format(Locale.ROOT, "%.1f kHz", t.sampleRate / 1000f) : "");
        m.put("Bit depth", t.bits > 0 ? t.bits + " bit" : "");
        m.put("Bitrate", t.bitrate > 0 ? t.bitrate + " kbps" : "");
        m.put("Channels", t.channels == 1 ? "Mono" : t.channels == 2 ? "Stereo" : t.channels > 0 ? String.valueOf(t.channels) : "");
        m.put("Size", t.size > 0 ? String.format(Locale.ROOT, "%.1f MB", t.size / 1048576f) : "");
        m.put("File", t.path.substring(t.path.lastIndexOf('/') + 1));
        m.put("Folder", t.folder);
        for (Map.Entry<String, String> e : m.entrySet()) if (e.getValue() != null && !e.getValue().isEmpty()) s.add(new Sheet.Item(0, e.getKey(), null).value(e.getValue()).stay());
        showSheet(s);
    }

    void eqMenu() {
        Sheet s = new Sheet(getContext(), "Equalizer & effects");
        s.add(new Sheet.Item(Icons.EQ, "Equalizer", null).toggle(fx.eqOn)).action = () -> { fx.eqOn = !fx.eqOn; fx.apply(); };
        s.add(new Sheet.Item(Icons.TUNE, "Tone (bass and treble)", null).toggle(fx.toneOn)).action = () -> { fx.toneOn = !fx.toneOn; fx.apply(); };
        s.add(new Sheet.Item(Icons.SPEED, "Limiter", null).toggle(fx.limiterOn)).action = () -> { fx.limiterOn = !fx.limiterOn; fx.apply(); };
        s.add(Sheet.Item.header("Presets"));
        for (int i = 0; i < Effects.PRESETS.length; i++) {
            final int k = i;
            s.add(new Sheet.Item(0, Effects.PRESETS[i], () -> { fx.usePreset(k); if (eq != null) eq.invalidate(); }).radio(fx.eqOn && fx.preset == i));
        }
        s.button("Open the equalizer", this::openEq);
        s.onDismiss = player::refreshSlots;
        showSheet(s);
    }

    void mainMenu() {
        Sheet s = new Sheet(getContext(), "YouSic");
        long left = engine.sleepRemainingMs();
        s.add(new Sheet.Item(Icons.TIMER, "Sleep timer", this::sleepTimer).value(engine.sleepingAfterSong() ? "After this song" : left > 0 ? PlayerScreen.fmt(left) : "Off"));
        s.add(new Sheet.Item(Icons.FOLDER, "Music folders", this::foldersDialog));
        s.add(new Sheet.Item(Icons.SCAN, "Rescan the library", () -> { app.lib.rescan(); toast("Scanning your music..."); }));
        s.add(Sheet.Item.header("Look and feel"));
        s.add(new Sheet.Item(Icons.THEME, "Skin", this::themeMenu).value(th.name()));
        String[] rm = { "Thumbs", "Stars", "Off" };
        s.add(new Sheet.Item(Icons.THUMB_UP, "Rating buttons", this::ratingMenu).value(rm[ratingMode()]));
        String[] sp = { "Off", "Normal", "Fast" };
        s.add(new Sheet.Item(Icons.SPEED, "Animations", this::speedMenu).value(sp[Motion.speed]));
        s.add(new Sheet.Item(Icons.VIS, "Visualization", this::visMenu).value(visOn() ? "On" : "Off"));
        s.add(Sheet.Item.header("About"));
        s.add(new Sheet.Item(Icons.INFO, "About YouSic N", this::about));
        showSheet(s);
    }

    void ratingMenu() {
        Sheet s = new Sheet(getContext(), "Rating buttons");
        String[] names = { "Like and dislike", "Stars", "Off" };
        int[] icons = { Icons.THUMB_UP, Icons.STAR, Icons.CLOSE };
        for (int i = 0; i < 3; i++) {
            final int k = i;
            s.add(new Sheet.Item(icons[i], names[i], () -> { prefs.edit().putInt("rating", k).apply(); player.onExtras(); }).radio(ratingMode() == i));
        }
        showSheet(s);
    }

    void speedMenu() {
        Sheet s = new Sheet(getContext(), "Animations");
        String[] names = { "Off", "Normal", "Fast" };
        for (int i : new int[]{ Motion.SPEED_NORMAL, Motion.SPEED_FAST, Motion.SPEED_OFF }) {
            final int k = i;
            s.add(new Sheet.Item(Icons.SPEED, names[i], () -> { Motion.speed = k; prefs.edit().putInt("animSpeed", k).apply(); }).radio(Motion.speed == i));
        }
        showSheet(s);
    }

    void themeMenu() {
        Sheet s = new Sheet(getContext(), "Skin");
        for (String name : Theme.NAMES) {
            boolean ready = Theme.ready(name);
            Sheet.Item it = s.add(new Sheet.Item(Icons.THEME, name, () -> { }).radio(name.equals(th.name())));
            if (!ready) it.value("next update").disabled();
        }
        showSheet(s);
    }

    void about() {
        Sheet s = new Sheet(getContext(), "YouSic N");
        s.add(new Sheet.Item(0, "Version", null).value(app.version()).stay());
        s.add(new Sheet.Item(0, "Songs", null).value(String.valueOf(app.lib.tracks.size())).stay());
        s.add(Sheet.Item.header("Fonts (SIL Open Font License)"));
        for (String f : new String[]{ "Cormorant Garamond", "IBM Plex Sans", "JetBrains Mono", "DSEG7 Classic" }) s.add(new Sheet.Item(0, f, null).stay());
        showSheet(s);
    }

    // ---------- music folders ----------

    void foldersDialog() {
        toast("Looking for music folders...");
        new Thread(() -> {
            final List<String> roots = app.lib.scanner.volumeRoots();
            final List<String> enabled = app.lib.scanner.folders();
            final Map<String, Integer> folders = app.lib.scanner.musicFolders();
            post(() -> showFolders(roots, enabled, folders));
        }, "folders").start();
    }

    private void showFolders(List<String> roots, List<String> enabled, Map<String, Integer> folders) {
        final List<String> on = new ArrayList<>(enabled);
        Sheet s = new Sheet(getContext(), "Folders Selection");
        for (String root : roots) {
            String name = root.endsWith("/emulated/0") ? Build.MODEL : "SD card";
            final Sheet.Item rootItem = s.add(new Sheet.Item(Icons.STORAGE, name, null).toggle(on.contains(root)).value(""));
            final List<Sheet.Item> kids = new ArrayList<>();
            final List<String> kidPaths = new ArrayList<>();
            for (Map.Entry<String, Integer> f : folders.entrySet()) {
                if (!f.getKey().startsWith(root + "/")) continue;
                String rel = f.getKey().substring(root.length() + 1);
                final String path = f.getKey();
                Sheet.Item k = s.add(new Sheet.Item(Icons.FOLDER, rel, null).toggle(on.contains(root) || covered(on, path)).value(String.valueOf(f.getValue())));
                kids.add(k); kidPaths.add(path);
                k.action = () -> {
                    if (on.contains(root)) {
                        // the whole storage was on: now every music folder in it but this one
                        on.remove(root);
                        rootItem.checked = false;
                        for (int i = 0; i < kidPaths.size(); i++) if (!kidPaths.get(i).equals(path) && !on.contains(kidPaths.get(i))) on.add(kidPaths.get(i));
                    } else if (k.checked) on.add(path);
                    else on.remove(path);
                };
            }
            rootItem.action = () -> {
                on.removeIf(p -> p.equals(root) || p.startsWith(root + "/"));
                if (rootItem.checked) on.add(root);
                for (Sheet.Item k : kids) k.checked = rootItem.checked;
            };
        }
        s.button("Save and Scan", () -> {
            app.lib.scanner.setFolders(on);
            app.lib.rescan();
            toast("Scanning the chosen folders...");
        });
        showSheet(s);
    }

    private static boolean covered(List<String> on, String path) {
        for (String p : on) if (path.equals(p) || path.startsWith(p + "/")) return true;
        return false;
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

    /** A short message over the bottom bar (it stays 2 s). */
    final class ToastView extends View {
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private String text;
        private final Runnable hide = () -> animate().alpha(0).setDuration(Motion.ms(Motion.POPUP_EXIT)).start();

        ToastView(Context c) {
            super(c);
            tp.setTypeface(th.uiMedium); tp.setTextSize(th.px(14)); tp.setColor(th.text); tp.setTextAlign(Paint.Align.CENTER);
            setAlpha(0);
        }

        void show(String s) {
            text = s;
            invalidate();
            removeCallbacks(hide);
            animate().alpha(1).setDuration(Motion.ms(Motion.STATE)).start();
            postDelayed(hide, Motion.TOAST);
        }

        @Override protected void onDraw(Canvas c) {
            if (text == null) return;
            CharSequence s = TextUtils.ellipsize(text, tp, getWidth() - th.px(72), TextUtils.TruncateAt.END);
            float w = tp.measureText(s, 0, s.length()) + th.px(40), h = th.px(44);
            float bottom = getHeight() - nav.heightPx() - th.px(96);
            r.set((getWidth() - w) / 2, bottom - h, (getWidth() + w) / 2, bottom);
            th.drawSheet(c, r, h / 2);
            c.drawText(s, 0, s.length(), r.centerX(), r.centerY() - (tp.ascent() + tp.descent()) / 2, tp);
        }
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
