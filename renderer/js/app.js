import { $, $$, coverUrl, installRipples, esc, clamp, ANDROID } from './util.js';
import { hydrateIcons, setIcon, iconPng, icon } from './icons.js';
import { AudioEngine } from './audio.js';
import { Store, THEMES } from './store.js';
import { Player } from './player.js';
import { PlayerView, hexToRgb } from './playerView.js';
import { LibraryView } from './libraryView.js';
import { EqView } from './eqView.js';
import { VisView } from './visView.js';
import { LyricsView } from './lyricsView.js';
import { toast, menuAt, openSheet, closeSheet, chooser, closeMenu } from './ui.js';
import { liquidAll, watchLiquid, refreshLiquid } from './liquidGlass.js';
import { MiniLink } from './miniLink.js';
import { Spotlight } from './spotlight.js';

// A soft spring (damping 0.92, 0.7 s): it sets off quickly and settles without a bounce. Used for the moves of
// the view transitions (App._vtMoves).
const SPRING = 'linear(0, 0.0194, 0.0683, 0.1355, 0.2127, 0.2938, 0.3747, 0.4524, 0.5252, 0.5919, 0.6522, 0.7058, 0.7529, 0.794, 0.8294, 0.8596, 0.8853, 0.907, 0.925, 0.9401, 0.9525, 0.9626, 0.9709, 0.9776, 0.9829, 0.9872, 0.9905, 0.9932, 0.9952, 0.9968, 0.9979, 0.9988, 0.9994, 0.9999, 1.0002, 1.0004, 1.0005, 1.0006, 1.0006, 1.0006, 1)';
const SPRING_MS = 700;
// wide windows narrower than this fold the left-hand pane down to its rail by themselves (Snap layouts)
const NAV_AUTO = 1200;

class App {
  constructor() {
    this.store = new Store();
    this.root = $('#app');
    // Themes style things outside #app by the app's state (wide layout, side panel, reduced motion, the
    // screen). Matched with html:has(.app...), every change on the page would re-check every style (about
    // 50 ms on a phone, once a second as the time ticks), so the state is mirrored onto <html> instead.
    const mirror = () => {
      const a = this.root, h = document.documentElement;
      h.classList.toggle('app-wide', a.classList.contains('wide'));
      h.classList.toggle('app-side-on', a.classList.contains('side-on'));
      h.classList.toggle('app-reduce-motion', a.classList.contains('reduce-motion'));
      if (a.dataset.screen) h.dataset.screen = a.dataset.screen; else delete h.dataset.screen;
    };
    new MutationObserver(mirror).observe(this.root, { attributes: true, attributeFilter: ['class', 'data-screen'] });
    mirror();
    this.screen = 'player';
    this.visOpen = false;
  }

  async init() {
    hydrateIcons();
    installRipples();
    this._bindWindow();
    this._bindTooltips();
    matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change', () => this._applyLook());
    await this.store.init();
    const s = this.store.settings;
    this.engine = new AudioEngine();
    this.engine.setVolume(s.muted ? 0 : s.volume);
    this.player = new Player(this.engine, this.store);
    this.player.addEventListener('toast', (e) => toast(e.detail));
    this.playerView = new PlayerView(this);
    this.library = new LibraryView(this);
    this.eq = new EqView(this);
    this.vis = new VisView(this);
    this.lyrics = new LyricsView(this);
    this.mini = new MiniLink(this);
    this._applyLook();
    this._bindNav();
    this._bindKeys();
    this._bindVolume();
    this._mediaSession();
    this._thumbIcons = { prev: iconPng('skip_previous'), next: iconPng('skip_next'), play: iconPng('play'), pause: iconPng('pause') };
    this.updateThumbar(false);
    pa.onCommand((c) => {
      // Android's notification, lock screen and widget can also play, pause and seek ("seek:<seconds>"). A
      // widget seek can arrive while the app is starting, before the song has loaded: it waits for it.
      if (typeof c === 'string' && c.startsWith('seek:')) {
        const t = Number(c.slice(5)) || 0;
        if (this.engine.duration) return this.engine.seek(t);
        return this.engine.addEventListener('meta', () => this.engine.seek(t), { once: true });
      }
      ({ prev: () => this.player.prev(), next: () => this.player.next(), toggle: () => this.player.toggle(),
        play: () => { if (!this.engine.playing) this.player.toggle(); }, pause: () => { if (this.engine.playing) this.player.toggle(); },
        prevcat: () => this.library.stepCategory(-1), nextcat: () => this.library.stepCategory(1),
        // the mini player's buttons
        shuffle: () => $('#btnShuffle').click(), repeat: () => $('#btnRepeat').click(), mute: () => this.toggleMute(),
        'vol+': () => this.setVolume(this.store.settings.volume + 0.05), 'vol-': () => this.setVolume(this.store.settings.volume - 0.05) })[c]?.();
    });
    pa.onProgress((m) => this._scanProgress(m));

    $$('#side .eq-tab').forEach((t) => (t.onclick = () => this.showSide(t.dataset.side)));
    $('#sideClose').onclick = () => this._paneMorph(() => this.showSide(null), 'vt-fold-r');
    this._bindRail();
    $('#plAdmire').onclick = () => this.toggleArt();
    $('#plFull').onclick = $('#fullBtn').onclick = () => this.toggleFull(true);
    $('#fsExit').onclick = () => this.toggleFull(false);
    $$('#fsPanel [data-fit]').forEach((b) => (b.onclick = () => this.setFullFit(b.dataset.fit)));
    this._fsFitUi();
    // full screen needs the window's help (preload.js); the Android build has none
    if (!pa.setFullScreen) $('#plFull').style.display = $('#fullBtn').style.display = 'none';
    pa.onFullScreen?.((on, size) => this._onWinFull(on, size));
    this.player.addEventListener('track', () => this._fsText());
    this._bindFull();
    this._layout();
    new ResizeObserver(() => { this._layout(); this._navInk(); if (!this.narrow) this._sideInk(); }).observe(document.body);
    // while the window itself is being resized the columns follow it at once rather than gliding after it
    addEventListener('resize', () => {
      this.root.classList.add('no-anim');
      clearTimeout(this._rzT);
      this._rzT = setTimeout(() => this._animateAgain(), 160);
    });
    document.fonts.ready.then(() => { if (!this.narrow) this._sideInk(); });
    this._bindPremium();
    // liquid glass refraction for every .lg element, now and as screens build new ones
    document.fonts.ready.then(() => { liquidAll(); watchLiquid(); });
    this.glassSupported = await pa.glassSupported();
    this._applyGlassWindow();

    if (s.resume) this.player.restoreSession();
    if (!this.player.track) this.playerView.refreshColors();
    // refresh the library in the background (incremental: only changed files are re-read)
    if (this.store.folders.length) this.rescan(false, !this.store.tracks.length);
    requestAnimationFrame(() => this.root.classList.add('ready'));
    // background window: pause looping decorations (see .idle in app.css)
    const idle = () => document.documentElement.classList.toggle('idle', !document.hasFocus());
    addEventListener('blur', idle);
    addEventListener('focus', idle);
    idle();
    addEventListener('beforeunload', () => {
      if (this.player.track) this.player._saveSession();
      pa.saveSync('settings', this.store.settings);
      pa.saveSync('stats', this.store.stats);
      pa.saveSync('playlists', this.store.playlists);
    });
  }

  // ---------- layout & navigation ----------
  get narrow() { return !this.root.classList.contains('wide'); }

  // Wide windows get three columns: the library (or the equalizer) on the left, the player in the middle
  // and lyrics or the visualization on the right (#side). Narrow windows keep one screen at a time.
  _layout() {
    // full screen always uses the wide layout: it is what lets the cover fill the window and lyrics take a half
    const wide = innerWidth >= 980 || this.root.classList.contains('full');
    if (wide !== this.root.classList.contains('wide')) {
      this.root.classList.toggle('wide', wide);
      if (wide && this.screen === 'player') this.screen = 'library';
      else if (!wide && this.screen === 'library') this.screen = 'player';
      this._placePanes();
      if (!wide) this.toggleArt(false);
      this._applyScreen(false);
    }
    if (wide) {
      // crossing NAV_AUTO forgets a choice made with ☰ below it, and the pane follows the window again
      const small = innerWidth < NAV_AUTO;
      if (small !== this._navSmall) { this._navSmall = small; this._navOverride = undefined; }
      const min = this._navOverride ?? (!!this.store.settings.navMin || small);
      if (min !== this.navMin) this._navSet(min);
    }
  }

  // ---------- the left-hand pane (wide): a NavigationView that folds down to its rail ----------
  _bindRail() {
    $('#railToggle').onclick = () => this.toggleNav();
    $$('#rail [data-rail]').forEach((b) => (b.onclick = () => this.show(b.dataset.rail)));
  }

  get navMin() { return this.root.classList.contains('nav-min'); }

  // fold the pane (true) or open it (false); `also` runs in the same change (show() opening it on a view).
  // Chosen where the window is wide enough to have room for it, it is remembered.
  toggleNav(force, also) {
    const min = force ?? !this.navMin;
    if (min === this.navMin) return also?.();
    if (innerWidth >= NAV_AUTO) {
      this._navOverride = undefined;
      if (this.store.settings.navMin !== min) { this.store.settings.navMin = min; this.store.saveSettings(); }
    } else this._navOverride = min;
    this._paneMorph(() => { this._navSet(min); also?.(); }, min ? 'vt-fold-l' : 'vt-open-l');
  }

  _navSet(min) {
    this.root.classList.toggle('nav-min', min);
    const t = $('#railToggle');
    t.setAttribute('aria-expanded', String(!min));
    t.title = min ? 'Expand the panel' : 'Collapse the panel';
  }

  // A pane folding or opening (either side) is a view transition, like full screen: the pieces glide to
  // their new places on the graphics card instead of the columns being laid out again on every frame.
  // dir names the pane and the way it goes (vt-fold-l, vt-open-l, vt-fold-r, vt-open-r: css/fluent.css).
  _paneMorph(change, dir) {
    if (this.narrow || this.full) return change();
    const html = document.documentElement;
    const done = () => { html.classList.remove(dir); this._animateAgain(); };
    this.root.classList.add('no-anim');
    html.classList.add(dir);
    this._viewMorph('vt-pane', change);
    if (this._vt && this._vtCls === 'vt-pane') this._vt.finished.finally(done); else done();
  }

  // the dock's lyrics and visualization keys: a morph only when the right-hand panel comes or goes
  _sideAct(act) {
    const run = act === 'vis' ? () => this.toggleVis() : () => this.toggleLyrics();
    if (this.narrow || this.full) return run();
    const on = this.root.classList.contains('side-on');
    const closes = act === 'vis' ? this.visOpen && !this._lyricsUnderVis : this.lyrics.open && !this.visOpen;
    if (on && !closes) return run();
    this._paneMorph(run, on ? 'vt-fold-r' : 'vt-open-r');
  }

  // where lyrics and the visualization show: the right-hand panel when wide, over the cover when narrow
  paneHost() { return this.narrow ? $('#aaWrap') : $('#side'); }

  _placePanes() {
    const ly = $('#lyricsPanel'), vis = $('#visHost');
    if (this.narrow) {
      // the panel is gone: close what it showed, so the cover isn't hidden
      $('#side').classList.remove('lyrics-open', 'vis-on');
      if (this.visOpen) { this.visOpen = false; this.vis.hide(); $('.nav-btn[data-act=vis]').classList.remove('on'); }
      if (this.lyrics.open) this.lyrics.toggle(false);
      $('#swipeHint').before(ly, vis);
    } else {
      $('#aaWrap').classList.remove('lyrics-open', 'vis-on');
      $('#sideBody').append(ly, vis);
      if (this.visOpen) this.paneHost().classList.add('vis-on');
      else if (this.lyrics.open) this.paneHost().classList.add('lyrics-open');
      else if (this.store.settings.sidePanel !== false && !this.root.classList.contains('full')) this.lyrics.toggle(true);
    }
    this._syncSide();
  }

  // Admire art (wide layout): show only the album art, with nothing over it (css: .art-only). The picture
  // key in the title bar turns it on; A, Esc and a tap on the cover bring the controls back.
  toggleArt(force) {
    const on = !this.narrow && (force ?? !this.root.classList.contains('art-only'));
    if (on && this.playerView.flipped) this.playerView.flipArt(false);
    this.root.classList.toggle('art-only', on);
    $('#plAdmire').setAttribute('aria-pressed', String(on));
  }

  // Full screen (the key beside the mini player, F11 or F): the window takes the whole screen and the album
  // art fills it, with nothing over it (css: .app.full). Moving the pointer brings up a control panel at the
  // bottom centre (#fsPanel, which the seekbar and the transport move into); it goes again once the pointer
  // rests. Lyrics (Y, or the panel's key) take the right half of the screen, beside the art. Esc leaves.
  get full() { return this.root.classList.contains('full'); }

  toggleFull(force) {
    const on = force ?? !(this._fullWant ?? this.full);
    this._fullWant = on;
    if (!pa.setFullScreen) return this._fullGo(on);
    this._fullWait(on);
    pa.setFullScreen(on);
  }

  // the window went in or out of full screen (after toggleFull asked, or by itself: F11), with its new size
  // (main.js fullSay): { w, h, resized }
  _onWinFull(on, size) {
    this._fullWant = on;
    this._fullWait(on, size || { resized: false });
  }

  // The page changes once the window has its new size, so the move from one layout to the other plays on a
  // window that keeps its size: a resize under a view transition cuts it short (the page jumps to its new
  // layout) or has it capture everything again (a stall at its start). A maximized window over an
  // auto-hiding taskbar is 2px short of the screen, so its size can't tell whether it is in full screen: the
  // window says when its change is made, and whether its size changed; that new size reaches the page as
  // a resize. (No word from the window within half a second, and the move goes ahead anyway.)
  _fullWait(on, said) {
    let p = this._fullP;
    if (!p || p.on !== on) {
      if (p) clearTimeout(p.t);
      p = this._fullP = { on, w: innerWidth, h: innerHeight, said: null };
      p.t = setTimeout(() => this._fullGo(on, p), 500);
      if (!this._fullR) addEventListener('resize', (this._fullR = () => this._fullP && this._fullCheck(this._fullP)));
    }
    if (said) p.said = said;
    this._fullCheck(p);
  }

  _fullCheck(p) {
    const s = p.said;
    if (!s) return;
    // its new size has reached the page (or the page already was that size)
    const near = (a, b) => Math.abs(a - b) <= 1;
    const there = !s.resized || innerWidth !== p.w || innerHeight !== p.h || (near(innerWidth, s.w) && near(innerHeight, s.h));
    // (on the next frame: the page has laid itself out at the new size by then, so the move captures that)
    if (there) requestAnimationFrame(() => this._fullGo(p.on, p));
  }

  _fullGo(on, p) {
    if (p) { if (p !== this._fullP) return; clearTimeout(p.t); this._fullP = null; }
    // (asked twice the same way, the move starts once)
    if (this._fullWant !== on || on === (this._fullTo ?? this.full)) return;
    this._fullTo = on;
    // which way it goes (css: html.vt-out, the light beside the cover fades out on the way out)
    document.documentElement.classList.toggle('vt-out', !on);
    this._viewMorph('vt-full', () => { this._fullTo = undefined; this._setFull(on); });
  }

  // A change shown as a view transition (css: html.vt-full): the cover moves and resizes from
  // where it was to where it goes while the rest cross-fades, and the panels stay above the cover the whole
  // way. The browser does the moving (no script per frame). Reduce animations, or no support, just switches.
  _viewMorph(cls, change) {
    const html = document.documentElement;
    if (!document.startViewTransition || this.store.settings.reduceMotion || document.hidden) return change();
    if (this._vt) { this._vt.skipTransition(); html.classList.remove(this._vtCls); }
    html.classList.add(cls);
    const vt = document.startViewTransition(change);
    this._vt = vt;
    this._vtCls = cls;
    vt.ready.then(() => this._vtMoves()).catch(() => {});
    vt.finished.finally(() => {
      if (this._vt !== vt) return;
      html.classList.remove(cls);
      this._vt = null;
      this._vtDrop();
    });
  }

  // The moves below are the page's own animations on the transition's pieces, held at their end (fill), so
  // they outlive it: the next transition's piece of the same name would take their end as its own (and
  // stay where it was). They go when their transition does.
  _vtDrop() {
    for (const a of this._vtAnims || []) a.cancel();
    this._vtAnims = [];
  }

  // The browser moves a box whose size changes by animating its width and height, which the page has to
  // work out again on every frame, so anything else the page does meanwhile shows as a stutter. Each such
  // move is played instead as a move and scale only, from where and how big the box was to where it is now,
  // which the graphics card plays by itself, at the screen's full rate, on a soft spring. (The pictures
  // inside stretch to the box, so the scale gives back each one's own shape at its own end.)
  _vtMoves() {
    const html = document.documentElement, moves = [];
    this._vtDrop();
    for (const a of document.getAnimations()) {
      const pe = a.effect?.pseudoElement || '';
      if (!pe.startsWith('::view-transition-group(') || pe === '::view-transition-group(root)') continue;
      const from = a.effect.getKeyframes?.()[0];
      if (!from?.width || !from?.height) continue;
      a.cancel();
      moves.push([pe, from]);
    }
    for (const [pe, from] of moves) {
      const cs = getComputedStyle(html, pe);
      const w = parseFloat(cs.width), h = parseFloat(cs.height);
      if (!w || !h) continue;
      const m0 = new DOMMatrixReadOnly(from.transform && from.transform !== 'none' ? from.transform : undefined);
      const m1 = new DOMMatrixReadOnly(cs.transform && cs.transform !== 'none' ? cs.transform : undefined);
      this._vtAnims.push(html.animate([
        { transform: `translate(${m0.e}px, ${m0.f}px) scale(${parseFloat(from.width) / w}, ${parseFloat(from.height) / h})` },
        { transform: `translate(${m1.e}px, ${m1.f}px)` },
      ], { pseudoElement: pe, duration: SPRING_MS, easing: SPRING, fill: 'both' }));
    }
  }

  // the page's side of it
  _setFull(on) {
    if (on === this.full) return;
    // the layout switches at once (the view transition does the moving); animations come back after
    this.root.classList.add('no-anim');
    this._animateAgain();
    closeMenu();
    $('#volPop').classList.remove('open');
    if (on) {
      this.toggleArt(false);
      if (this.playerView.flipped) this.playerView.flipArt(false, false);
      // the art starts on its own; whatever the right-hand panel showed comes back on the way out
      this._fsSide = this.visOpen ? 'vis' : this.lyrics.open ? 'lyrics' : null;
      $('#fsPanel').append($('.seek'), $('.controls'));
      this.root.classList.add('full');
      this._layout();
      this.showSide(null);
      this._fsText();
      this._fsShow();
      this.playerView._bleed();
      this.playerView.fsLight.draw();
    } else {
      clearTimeout(this._fsTimer);
      this.root.classList.remove('full', 'fs-ui');
      $('#plNav').before($('.seek'), $('.controls'));
      // the title comes back as it is (the move fades it in); its own entrance would only play under it
      $('.pl-info').classList.remove('enter', 'enter-r', 'enter-l');
      $('#plMeta')?.classList.remove('flash');
      this._layout();
      if (!this.narrow) this.showSide(this._fsSide);
      this.playerView._bleed();
    }
  }

  // how the cover shows in full screen (the panel's Fill screen / Whole cover choice, or Settings; remembered):
  // stretched over the whole screen, so the screen is all cover and none of it is cut off (the default), or
  // whole at its own shape (.fs-fit), the room beside it lit by its own edges (fullLight.js)
  // In full screen the change is shown: the cover narrows to its own shape (or widens to the screen's) and its
  // light fades in beside it (or out).
  setFullFit(mode) {
    this.store.settings.fullFit = mode === 'fit' ? 'fit' : 'fill';
    this.store.saveSettings();
    const fit = this.store.settings.fullFit === 'fit';
    $$('#fsPanel [data-fit]').forEach((b) => b.classList.toggle('on', (b.dataset.fit === 'fit') === fit));
    if (this.full && fit !== this.root.classList.contains('fs-fit') && !this.store.settings.reduceMotion) this._fitMorph();
    else this._fsFitUi();
  }

  // The cover's box changes at once (the picture is stretched to its box in both ways), and is then moved and
  // scaled from where it was to where it is: the graphics card plays that by itself, at the screen's full
  // rate. A second switch on the way picks up from wherever the cover is.
  _fitMorph() {
    const img = $('#aaTrack .aa-card[data-slot="cur"] .aa-img');
    const r0 = img.getBoundingClientRect();
    this._fitAnim?.cancel();
    this._fsFitUi();
    const r1 = img.getBoundingClientRect();
    if (!r0.width || !r0.height || !r1.width || !r1.height) return;
    this._fitAnim = img.animate([
      { transformOrigin: '0 0', transform: `translate(${r0.left - r1.left}px, ${r0.top - r1.top}px) scale(${r0.width / r1.width}, ${r0.height / r1.height})` },
      { transformOrigin: '0 0', transform: 'none' },
    ], { duration: SPRING_MS, easing: SPRING });
  }

  _fsFitUi() {
    const fit = this.store.settings.fullFit === 'fit';
    this.root.classList.toggle('fs-fit', fit);
    $$('#fsPanel [data-fit]').forEach((b) => b.classList.toggle('on', (b.dataset.fit === 'fit') === fit));
    // the light is drawn for the room as it is now (nothing to do if it already was)
    if (fit && this.full) this.playerView.fsLight.draw();
  }

  _fsText() {
    if (!this.full) return;
    $('#fsTitle').textContent = $('#plTitle').textContent;
    $('#fsArtist').textContent = $('#plArtist').textContent;
  }

  // the panel comes up while the pointer moves (or on a tap) and goes 2.6 s after it stops, but not while
  // the pointer is on the panel or a menu is open
  _bindFull() {
    const wake = () => { if (this.full) this._fsShow(); };
    addEventListener('pointermove', wake, { passive: true });
    addEventListener('pointerdown', wake, { passive: true });
  }

  _fsShow() {
    if (!this.root.classList.contains('fs-ui')) this.root.classList.add('fs-ui');
    clearTimeout(this._fsTimer);
    this._fsTimer = setTimeout(() => this._fsHide(), 2600);
  }

  _fsHide() {
    if ($('#fsPanel').matches(':hover') || $('#ctx').classList.contains('open') || $('#volPop').classList.contains('open')) {
      this._fsTimer = setTimeout(() => this._fsHide(), 1000);
      return;
    }
    this.root.classList.remove('fs-ui');
  }

  // the panel's tabs: 'lyrics', 'vis', or null to hide the panel
  showSide(which) {
    if (which === 'vis') { if (!this.visOpen) this.toggleVis(true); return; }
    if (this.visOpen) { this._lyricsUnderVis = false; this.toggleVis(false); }
    if (this.lyrics.open !== (which === 'lyrics')) this.lyrics.toggle(which === 'lyrics');
  }

  _syncSide() {
    if (this.narrow) { this.root.classList.remove('side-on'); return; }
    const on = this.visOpen || this.lyrics.open;
    this.root.classList.toggle('side-on', on);
    $('#fsLyrics').classList.toggle('on', this.lyrics.open);
    if (this.store.settings.sidePanel !== on) { this.store.settings.sidePanel = on; this.store.saveSettings(); }
    this._sideInk();
  }

  _sideInk() {
    const tabs = $$('#side .eq-tab'), act = tabs.find((t) => t.dataset.side === (this.visOpen ? 'vis' : 'lyrics'));
    tabs.forEach((t) => t.classList.toggle('active', t === act));
    const ink = $('#side .eq-tab-ink');
    requestAnimationFrame(() => { ink.style.width = `${act.offsetWidth}px`; ink.style.transform = `translateX(${act.offsetLeft}px)`; });
  }

  _applyScreen(animate = true) {
    this.root.classList.toggle('no-anim', !animate || this.store.settings.reduceMotion);
    this.root.dataset.screen = this.screen;
    $$('.nav-btn[data-act=library]').forEach((b) => b.classList.toggle('on', this.screen === 'library' && !this.narrow));
    $$('.nav-btn[data-act=eq]').forEach((b) => b.classList.toggle('on', this.screen === 'eq'));
    $$('#rail [data-rail]').forEach((b) => b.classList.toggle('on', !this.narrow && b.dataset.rail === (this.screen === 'eq' ? 'eq' : 'library')));
    this._animateAgain();
  }

  // A change made without animating (a layout switch) must not leave the animations off for what follows:
  // they come back once that change has been drawn. Reduce animations keeps them off.
  _animateAgain() {
    if (this.store.settings.reduceMotion) return;
    requestAnimationFrame(() => requestAnimationFrame(() => { if (!this.store.settings.reduceMotion) this.root.classList.remove('no-anim'); }));
  }

  show(screen) {
    closeMenu();
    // the library and the equalizer are out of sight in full screen: going to them leaves it
    if (screen !== 'player' && this.full) this.toggleFull(false);
    if (this.root.classList.contains('wide') && screen === 'player') screen = 'library';
    // the pane folded down to its rail opens to show the view asked for
    if (!this.narrow && this.navMin) return this.toggleNav(false, () => { this.screen = screen; this._applyScreen(false); });
    this.screen = screen;
    this._applyScreen(true);
  }

  showPlayer() { if (this.narrow) this.show('player'); }
  openLibrary(node) {
    this.show('library');
    if (node) this.library.openNode(node);
  }
  openEq() { this.show(this.screen === 'eq' && !this.narrow ? 'library' : 'eq'); }

  toggleVis(force) {
    this.visOpen = force ?? !this.visOpen;
    $('.nav-btn[data-act=vis]').classList.toggle('on', this.visOpen);
    this.paneHost().classList.toggle('vis-on', this.visOpen);
    if (this.visOpen) { this._lyricsUnderVis = this.lyrics.open; if (this.lyrics.open) this.lyrics.toggle(false); this.vis.show(); }
    else {
      this.vis.hide();
      // in the side panel, closing the visualization goes back to the lyrics it replaced
      if (!this.narrow && this._lyricsUnderVis) this.lyrics.toggle(true);
    }
    this._syncSide();
  }

  toggleLyrics() {
    if (this.visOpen) { this._lyricsUnderVis = false; this.toggleVis(false); }
    this.lyrics.toggle();
  }

  _bindNav() {
    this.root.addEventListener('click', (e) => {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const act = b.dataset.act;
      ({
        library: () => (this.narrow ? this.openLibrary(this.player.source?.node && this.library.stack.length <= 1 ? this.player.source.node : null) : this.show('library')),
        eq: () => this.openEq(),
        vis: () => this._sideAct('vis'),
        lyrics: () => this._sideAct('lyrics'),
        sleep: () => this.sleepTimer(),
        settings: () => this.settings(),
        playerMenu: () => this.playerMenu(b),
        volume: () => this._toggleVolPop(),
        back: () => (this.narrow ? this.show('player') : this.show('library')),
      })[act]?.();
    });
  }

  // Styled tooltips: an element's title moves to data-tip (and aria-label, so it keeps its accessible
  // name) the first time the pointer reaches it, and a single floating .tip shows it after a short pause.
  _bindTooltips() {
    const tip = document.createElement('div');
    tip.className = 'tip';
    tip.setAttribute('role', 'tooltip');
    document.body.appendChild(tip);
    let timer = 0, cur = null;
    const hide = () => { clearTimeout(timer); timer = 0; cur = null; tip.classList.remove('show'); };
    addEventListener('pointerover', (e) => {
      const el = e.target.closest && e.target.closest('[title], [data-tip]');
      if (el === cur) return;
      hide();
      if (!el) return;
      if (el.hasAttribute('title')) {
        el.dataset.tip = el.title;
        if (!el.hasAttribute('aria-label') && !el.textContent.trim()) el.setAttribute('aria-label', el.title);
        el.removeAttribute('title');
      }
      if (!el.dataset.tip) return;
      cur = el;
      timer = setTimeout(() => {
        if (cur !== el || !el.isConnected) return;
        tip.textContent = el.dataset.tip;
        const r = el.getBoundingClientRect(), w = tip.offsetWidth, h = tip.offsetHeight;
        const below = r.bottom + 8 + h < innerHeight;
        tip.style.left = `${Math.max(6, Math.min(innerWidth - w - 6, r.left + r.width / 2 - w / 2))}px`;
        tip.style.top = `${below ? r.bottom + 8 : r.top - h - 8}px`;
        tip.classList.add('show');
      }, 450);
    }, { passive: true });
    for (const ev of ['pointerdown', 'wheel', 'keydown', 'blur']) addEventListener(ev, hide, { passive: true, capture: true });
  }

  _bindWindow() {
    $$('[data-win]').forEach((b) => (b.onclick = () => ({ min: pa.minimize, max: pa.maximize, close: pa.close })[b.dataset.win]()));
    pa.onWinState((s) => this.root.classList.toggle('maximized', s.maximized));
    $('.tb-drag').addEventListener('dblclick', () => pa.maximize());
  }

  _bindKeys() {
    addEventListener('keydown', (e) => {
      const tag = e.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      const p = this.player, en = this.engine;
      const k = e.key;
      if (k === ' ' ) { e.preventDefault(); p.toggle(); }
      else if (k === ',' && e.ctrlKey) { e.preventDefault(); this.settings(); }
      else if (k === 'ArrowRight' && e.ctrlKey) p.next();
      else if (k === 'ArrowLeft' && e.ctrlKey) p.prev();
      else if (k === 'ArrowRight' && this.visOpen) this.vis.step(1);
      else if (k === 'ArrowLeft' && this.visOpen) this.vis.step(-1);
      else if (k === 'ArrowRight') en.seek(en.currentTime + (e.shiftKey ? 30 : 5));
      else if (k === 'ArrowLeft') en.seek(en.currentTime - (e.shiftKey ? 30 : 5));
      else if (k === 'ArrowUp') { e.preventDefault(); this.setVolume(this.store.settings.volume + 0.05); }
      else if (k === 'ArrowDown') { e.preventDefault(); this.setVolume(this.store.settings.volume - 0.05); }
      else if (k === 'f' && e.ctrlKey) { e.preventDefault(); this.show('library'); this.library.toggleSearch(true); }
      else if (k === 'l' && e.ctrlKey) { e.preventDefault(); this.openLibrary(); }
      else if (k === 'Backspace' || (k === 'ArrowLeft' && e.altKey) || k === 'BrowserBack') { if (this.full) return; if (this.screen === 'library') this.library.back(); else this.showPlayer(); }
      else if (k === 'Escape') { if (this.vis.host.classList.contains('full')) this.vis.toggleFull(); else if (this.full) this.toggleFull(false); else if (this.root.classList.contains('art-only')) this.toggleArt(false); else if (this.visOpen) this.toggleVis(false); else if (this.screen !== 'player') this.showPlayer(); }
      else if (k === 'e' || k === 'E') this.openEq();
      else if (k === 'v' || k === 'V') this.toggleVis();
      else if ((k === 'f' || k === 'F') && this.visOpen) this.vis.toggleFull();
      else if ((k === 'f' || k === 'F') && !e.ctrlKey && !e.altKey && pa.setFullScreen) this.toggleFull();
      else if (k === 'y' || k === 'Y') this.toggleLyrics();
      else if (k === 'i' || k === 'I') this.playerView.flipArt();
      else if (k === 'a' || k === 'A') this.toggleArt();
      else if (k === 's' || k === 'S') $('#btnShuffle').click();
      else if (k === 'r' || k === 'R') $('#btnRepeat').click();
      else if (k === 'm' || k === 'M') this.toggleMute();
      else if (/^[0-5]$/.test(k) && e.altKey && p.track) this.store.setRating(p.track.id, +k);
    });
    // mouse back button
    addEventListener('mouseup', (e) => { if (e.button === 3 && !this.full) { if (this.screen === 'library') this.library.back(); else this.showPlayer(); } });
  }

  // ---------- premium touches ----------
  _bindPremium() {
    // dock indicator slides to the active nav button
    new MutationObserver(() => this._navInk()).observe($('#plNav'), { subtree: true, attributes: true, attributeFilter: ['class'] });
    requestAnimationFrame(() => this._navInk());
    // cursor-following spotlight on cards, rows and glass buttons
    addEventListener('pointermove', (e) => {
      const el = e.target.closest && e.target.closest('.tile, .row, .gcell, .lg');
      // (the library's buttons in the Brutal mood have the spotlight below instead: restyling a button on
      // every move is what that avoids)
      if (!el || this.brutal && el.classList.contains('tile') && el.closest('#library')) return;
      const r = el.getBoundingClientRect();
      el.style.setProperty('--mx', `${e.clientX - r.left}px`);
      el.style.setProperty('--my', `${e.clientY - r.top}px`);
    }, { passive: true });
    // Brutal mood: a dot-matrix light round the pointer on the library's buttons, their rims catching it
    // (js/spotlight.js, css/spotlight.css)
    this.spotlight = new Spotlight($('#library'), { scope: '.lib-page', card: '.tile', reach: 130, enabled: () => this.brutal });
  }

  _applyGlassWindow() {
    // wallpaper: the real desktop wallpaper painted behind the app (sharp); acrylic: Windows blur.
    // Android has neither: the glass theme sits on the blurred album art there.
    const mode = this.solidTheme || ANDROID ? 'solid' : this.store.settings.windowBg || 'wallpaper';
    const acrylic = mode === 'acrylic' && this.glassSupported;
    const wallpaper = mode === 'wallpaper';
    const html = document.documentElement;
    html.classList.toggle('glass-window', acrylic || wallpaper);
    html.classList.toggle('wall-window', wallpaper);
    pa.setGlass(acrylic);
    if (!this._wallBound) {
      this._wallBound = true;
      const wall = $('#wall');
      // CSS background-size for each Windows "fit" style
      const SIZE = { 0: 'auto', 1: 'auto', 2: '100% 100%', 6: 'contain', 10: 'cover', 22: 'cover' };
      pa.onWallpaper((w) => {
        wall.style.backgroundColor = w.color || '#000';
        wall.style.backgroundImage = w.url ? `url("${w.url}")` : 'none';
        wall.style.backgroundSize = SIZE[w.style] || 'cover';
        wall.style.backgroundRepeat = w.style === 1 ? 'repeat' : 'no-repeat';
        wall.style.backgroundPosition = w.style === 1 ? '0 0' : 'center';
      });
      pa.onGeom((g) => {
        wall.style.width = `${g.w}px`;
        wall.style.height = `${g.h}px`;
        wall.style.transform = `translate(${-g.x}px, ${-g.y}px)`;
      });
    }
    pa.setWallpaperMode(wallpaper);
  }

  _navInk() {
    const ink = $('#navInk');
    const on = $('#plNav .nav-btn.on');
    if (!on) { ink.style.opacity = '0'; return; }
    ink.style.opacity = '1';
    ink.style.width = `${on.offsetWidth}px`;
    ink.style.transform = `translateX(${on.offsetLeft}px)`;
  }

  // ---------- volume ----------
  setVolume(v) {
    const s = this.store.settings;
    s.volume = clamp(Math.round(v * 100) / 100, 0, 1);
    s.muted = false;
    this.engine.setVolume(s.volume);
    this.store.saveSettings();
    this._syncVol();
    this._flashVol();
  }

  toggleMute() {
    const s = this.store.settings;
    s.muted = !s.muted;
    this.engine.setVolume(s.muted ? 0 : s.volume);
    this.store.saveSettings();
    this._syncVol();
    toast(s.muted ? 'Muted' : `Volume ${Math.round(s.volume * 100)}%`, 1000);
  }

  _syncVol() {
    const s = this.store.settings;
    const v = s.muted ? 0 : s.volume;
    $('#volRange').value = Math.round(v * 100);
    $('#volVal').textContent = Math.round(v * 100);
    $('#volRange').style.setProperty('--p', `${v * 100}%`);
    const ic = v === 0 ? 'volume_off' : v < 0.5 ? 'volume_down' : 'volume_up';
    setIcon($('#volBtn'), ic);
    setIcon($('#volIco'), ic);
    $('#vbRange').value = Math.round(v * 100);
    $('#vbRange').style.setProperty('--p', `${v * 100}%`);
    $('#vbVal').textContent = Math.round(v * 100);
    setIcon($('#vbIco'), ic);
    $('#volBar').classList.toggle('muted', !!s.muted);
    this.mini?.volume();
  }

  _bindVolume() {
    const r = $('#volRange');
    r.oninput = () => this.setVolume(r.value / 100);
    $('#volIco').onclick = () => this.toggleMute();
    const wheel = (e) => { e.preventDefault(); this.setVolume(this.store.settings.volume + (e.deltaY < 0 ? 0.03 : -0.03)); };
    $('#volBtn').addEventListener('wheel', wheel, { passive: false });
    $('#volPop').addEventListener('wheel', wheel, { passive: false });
    addEventListener('pointerdown', (e) => { if (!e.target.closest('#volPop, #volBtn')) $('#volPop').classList.remove('open'); });
    // the bar beside the controls
    const vr = $('#vbRange');
    vr.oninput = () => this.setVolume(vr.value / 100);
    vr.onchange = () => vr.blur(); // so Space, the arrows and the other shortcuts reach the player again
    $('#vbIco').onclick = () => this.toggleMute();
    $('#volBar').addEventListener('wheel', wheel, { passive: false });
    $('#miniBtn').onclick = $('#plMini').onclick = () => this.mini.open();
    if (!this.mini.supported) $('#miniBtn').style.display = $('#plMini').style.display = 'none';
    new ResizeObserver(() => this._fitVolBar()).observe($('.controls'));
    this._syncVol();
  }

  // The volume bar goes right of the transport (which stays centred), its end in line with the seekbar's,
  // when the row leaves it room for the speaker and a 64 px slider (the number goes first when it's tight);
  // otherwise it takes a row of its own under the transport.
  _fitVolBar() {
    const row = $('.controls'), end = $('#btnRepeat'), bar = $('#volBar');
    const room = row.clientWidth - 24 - (end.offsetLeft + end.offsetWidth) - 18;
    const inline = room >= 110;
    this.root.classList.toggle('vol-inline', inline);
    this.root.classList.toggle('vol-row', !inline);
    bar.style.width = inline ? `${Math.min(room, 200)}px` : '';
    bar.classList.remove('no-val');
    if (inline && $('#vbRange').offsetWidth < 64) bar.classList.add('no-val');
  }

  // the bar shows the volume itself while it is on screen
  _volBarShown() {
    const r = this.root.classList;
    if (r.contains('full')) return r.contains('fs-ui');
    return !r.contains('art-only') && (!this.narrow || this.screen === 'player');
  }

  _toggleVolPop() {
    const pop = $('#volPop');
    const b = $('#volBtn').getBoundingClientRect();
    pop.style.left = `${Math.max(8, Math.min(innerWidth - 260, b.right - 240))}px`;
    pop.style.top = `${b.bottom + 6}px`;
    pop.classList.toggle('open');
  }

  _flashVol() {
    if ($('#volPop').classList.contains('open') || this._volBarShown()) return;
    toast(`Volume ${Math.round(this.store.settings.volume * 100)}%`, 700);
  }

  // ---------- library scanning ----------
  async addFolder() {
    const lib = await pa.addFolder();
    this.store.setLibrary(lib);
  }

  async rescan(force, showToast = true) {
    if (showToast) toast(force ? 'Rescanning library…' : 'Scanning library…');
    const lib = await pa.scan(force);
    this.store.setLibrary(lib);
    if (showToast) toast(`Library: ${lib.tracks.length} tracks`);
    if (!this.player.track && this.store.settings.resume) this.player.restoreSession();
  }

  _scanProgress(m) {
    const bar = $('#scanBar');
    if (m.phase === 'done' || !m.total) { bar.classList.remove('show'); return; }
    bar.classList.add('show');
    $('#scanFill').style.transform = `scaleX(${m.done / m.total})`;
    $('#scanText').textContent = `Scanning ${m.done} / ${m.total}`;
  }

  // ---------- look ----------
  // Liquid Glass is the only theme (store.js THEMES); solidTheme stays for a theme without glass
  get theme() { return THEMES.includes(this.store.settings.theme) ? this.store.settings.theme : 'glass'; }
  get solidTheme() { return this.theme !== 'glass'; }
  // Liquid Glass's Brutal mood: the same glass in black and white (brutal.css, html.glass-brutal)
  get brutal() { return this.theme === 'glass' && this.store.settings.glassMood !== 'clear'; }
  // no borders between the columns: the cover dissolves into its own light (css/seamless.css, ambient.js).
  // Liquid Glass's Brutal mood
  get seamless() { return this.brutal; }

  // Reduce effects is on when the user turned it on, or (until they choose) when Windows asks for less motion.
  get reduceEffects() {
    const v = this.store.settings.reduceEffects;
    return v == null ? matchMedia('(prefers-reduced-motion: reduce)').matches : !!v;
  }

  _applyLook() {
    const s = this.store.settings;
    const theme = this.theme, solid = this.solidTheme;
    const html0 = document.documentElement;
    if (!html0.classList.contains('theme-' + theme)) {
      THEMES.forEach((t) => html0.classList.toggle('theme-' + t, t === theme));
      html0.classList.toggle('theme-solid', solid);
      this.playerView.wave.setTheme(theme);
      this.playerView.ambient.refit(); // each theme shapes the cover's light its own way (ambient.js light)
      refreshLiquid();
      requestAnimationFrame(() => this._fitVolBar()); // each theme sizes and spaces the controls its own way
    }
    this.root.classList.toggle('no-blur', !s.blurBg);
    document.documentElement.classList.toggle('no-refract', !s.refraction);
    document.documentElement.classList.toggle('liq-lime', s.liquid === 'lime');
    document.documentElement.classList.toggle('liq-accent', s.liquid === 'accent');
    // solid themes have their own buttons: none of the glass button styles apply
    const style = solid ? 'none' : s.buttonStyle || 'ios';
    const html = document.documentElement;
    if (!html.classList.contains('btn-' + style)) {
      html.classList.remove('btn-ios', 'btn-bubble', 'btn-aqua', 'btn-none');
      html.classList.add('btn-' + style);
      refreshLiquid();
    }
    this.root.classList.toggle('reduce-motion', !!s.reduceMotion);
    // Reduce effects: no blur, no decorative motion, solid panels (html.fx-lite)
    document.documentElement.classList.toggle('fx-lite', this.reduceEffects);
    const brutal = this.brutal;
    if (html.classList.contains('glass-brutal') !== brutal) {
      html.classList.toggle('glass-brutal', brutal);
      this.playerView.refreshColors();
      requestAnimationFrame(() => { refreshLiquid(); this._navInk(); this._fitVolBar(); }); // its corners and type differ
    }
    if (html.classList.contains('seamless') !== this.seamless) {
      html.classList.toggle('seamless', this.seamless);
      this.playerView.refreshColors();
    }
    if (!s.dynamicAccent && !solid && !brutal) {
      const rgb = hexToRgb(s.accent);
      document.documentElement.style.setProperty('--accent', s.accent);
      document.documentElement.style.setProperty('--accent-rgb', rgb.join(','));
    }
    this.mini?.look();
  }

  onAccent() { this.eq && this.eq.onAccent(); this.mini?.look(); }

  updateThumbar(playing) {
    if (!this._thumbIcons) return;
    pa.setThumbar({ ...this._thumbIcons, playing });
    this._nowPlaying();
  }

  // Android: the playback notification, lock screen, headset buttons and home-screen widget are native, so
  // tell them what is playing (the track, whether it plays, and where it is). Windows has no pa.nowPlaying.
  _nowPlaying() {
    if (!pa.nowPlaying) return;
    const t = this.player?.track, en = this.engine;
    pa.nowPlaying(t ? {
      title: t.title, artist: t.artist || '', album: t.album || '', cover: t.cover || null,
      duration: en.duration || t.duration || 0, position: en.currentTime || 0, playing: !!en.playing, rate: en.tempo || 1,
    } : null);
  }

  _mediaSession() {
    const p = this.player, en = this.engine;
    if (pa.nowPlaying) {
      p.addEventListener('track', () => this._nowPlaying());
      en.addEventListener('seek', () => this._nowPlaying());
      en.addEventListener('meta', () => this._nowPlaying());
    }
    if (!('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    ms.setActionHandler('play', () => en.play());
    ms.setActionHandler('pause', () => en.pause());
    ms.setActionHandler('previoustrack', () => p.prev());
    ms.setActionHandler('nexttrack', () => p.next());
    ms.setActionHandler('seekto', (d) => en.seek(d.seekTime));
    ms.setActionHandler('seekbackward', () => en.seek(en.currentTime - 10));
    ms.setActionHandler('seekforward', () => en.seek(en.currentTime + 10));
    p.addEventListener('track', async (e) => {
      const t = e.detail;
      ms.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album });
      if (!t.cover) return;
      // Windows' media flyout only accepts http/data/blob artwork, so hand it a data URL.
      try {
        const blob = await (await fetch(coverUrl(t.cover))).blob();
        const src = await new Promise((res) => { const r = new FileReader(); r.onload = () => res(r.result); r.readAsDataURL(blob); });
        if (p.track === t) ms.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: [{ src, sizes: '1000x1000', type: 'image/jpeg' }] });
      } catch {}
    });
    setInterval(() => {
      if (en.duration && en.playing) {
        try { ms.setPositionState({ duration: en.duration, position: Math.min(en.currentTime, en.duration), playbackRate: en.tempo }); } catch {}
      }
    }, 1000);
  }

  // ---------- menus & dialogs ----------
  playerMenu(btn) {
    const p = this.player, t = p.track;
    const items = [];
    if (t) {
      items.push({ header: t.title },
        { label: 'Add to Playlist…', icon: 'playlist_add', action: () => this.library.addToPlaylist([t]) },
        { label: 'Add to Queue', icon: 'queue', action: () => { p.enqueue([t]); toast('Added to Queue'); } },
        { label: 'Go to Album', icon: 'album', disabled: !t.albumKey, action: () => this.openLibrary({ type: 'album', key: t.albumKey, title: t.album }) },
        { label: 'Go to Artist', icon: 'person', disabled: !t.artist, action: () => this.openLibrary({ type: 'person', field: 'artist', key: t.artist, title: t.artist }) },
        ...(ANDROID ? [] : [{ label: 'Show in Explorer', icon: 'open_in_new', action: () => pa.showInFolder(t.path) }]),
        { label: 'Track Info', icon: 'info', action: () => this.trackInfo(t) },
        '-');
    }
    items.push({ label: 'Queue', icon: 'queue', hint: `${p.userQueue.length}`, action: () => this.openLibrary({ type: 'queue', title: 'Queue' }) },
      { label: 'Sleep Timer', icon: 'timer', action: () => this.sleepTimer() },
      { label: 'Settings', icon: 'settings', action: () => this.settings() });
    menuAt(btn, items);
  }

  trackInfo(t) {
    const f = t.fmt || {};
    const st = this.store.stats[t.id] || {};
    const rows = [
      ['Title', t.title], ['Artist', t.artist], ['Album Artist', t.albumArtist], ['Album', t.album], ['Year', t.year || ''],
      ['Track', t.track || ''], ['Disc', t.disc || ''], ['Genre', t.genre], ['Composer', t.composer],
      ['Duration', new Date((t.duration || 0) * 1000).toISOString().substr(11, 8)],
      ['Codec', f.codec], ['Sample Rate', f.sampleRate ? f.sampleRate + ' Hz' : ''], ['Bits', f.bits || ''], ['Bitrate', f.bitrate ? f.bitrate + ' kbps' : ''],
      ['Channels', f.channels || ''], ['Lossless', f.lossless ? 'Yes' : 'No'], ['Size', (t.size / 1048576).toFixed(2) + ' MB'],
      ['Plays', st.plays || 0], ['Path', t.path],
    ].filter((r) => r[1] !== '' && r[1] != null);
    openSheet({ title: 'Track Info', body: `<table class="info-table">${rows.map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join('')}</table>` });
  }

  async sleepTimer() {
    const opts = [
      { label: 'Off', value: 'off', hint: this.sleepAt ? 'Cancel timer' : '' },
      { label: 'End of current track', value: 'track' },
      ...[5, 10, 15, 30, 45, 60, 90, 120].map((m) => ({ label: `${m} minutes`, value: m })),
    ];
    const v = await chooser('Sleep Timer' + (this.sleepAt ? ` — ${Math.ceil((this.sleepAt - Date.now()) / 60000)} min left` : ''), opts);
    if (v == null) return;
    clearTimeout(this.sleepT);
    clearTimeout(this.sleepFadeT);
    this.sleepAt = 0;
    this.player.stopAfter = false;
    this.engine.fadeMaster(1, 0.3);
    $('.nav-btn[data-act=sleep]').classList.remove('on');
    if (v === 'off') { toast('Sleep timer off'); return; }
    $('.nav-btn[data-act=sleep]').classList.add('on');
    if (v === 'track') { this.player.stopAfter = true; toast('Playback will stop after this track'); return; }
    const ms = v * 60000;
    this.sleepAt = Date.now() + ms;
    const fade = Math.min(30000, ms / 2);
    this.sleepFadeT = setTimeout(() => this.engine.fadeMaster(0, fade / 1000), ms - fade);
    this.sleepT = setTimeout(() => {
      this.engine.pause();
      setTimeout(() => this.engine.fadeMaster(1, 0.1), 400);
      this.sleepAt = 0;
      $('.nav-btn[data-act=sleep]').classList.remove('on');
      toast('Sleep timer: playback stopped');
    }, ms);
    toast(`Sleep timer: ${v} minutes`);
  }

  settings() {
    const s = this.store.settings;
    const tog = (key, label, hint) => `<label class="set-row"><div><div class="set-label">${label}</div>${hint ? `<div class="set-hint">${hint}</div>` : ''}</div>
      <input type="checkbox" class="switch" data-k="${key}" ${s[key] ? 'checked' : ''}></label>`;
    const pick = (key, label, hint, opts) => `<div class="set-row${key === 'theme' ? ' theme-row' : ''}"><div><div class="set-label">${label}</div><div class="set-hint">${hint}</div></div>
      <div class="liq-pick">${opts.map(([v, l]) => `<button class="chip lg${s[key] === v ? ' on' : ''}" data-pick="${key}" data-v="${v}">${l}</button>`).join('')}</div></div>`;
    const body = openSheet({
      title: 'Settings', wide: true,
      body: `
      <div class="set-sec">Library</div>
      <div class="folders">${this.store.folders.map((f) => `<div class="folder-row">${icon('folder')}<span>${esc(f)}</span><button class="icon-btn small" data-rm="${esc(f)}" title="Remove">${icon('close')}</button></div>`).join('') || '<div class="set-hint">No folders</div>'}</div>
      <div class="set-actions"><button class="btn" data-a="add">${icon('add')}Add Folder</button><button class="btn ghost" data-a="rescan">${icon('refresh')}Full Rescan</button></div>
      <div class="set-sec">Audio</div>
      <div class="set-row"><div><div class="set-label">Crossfade</div><div class="set-hint">0 = gapless (next track is preloaded)</div></div>
        <div class="range-wrap"><input type="range" min="0" max="12" step="1" value="${s.crossfade}" data-r="crossfade" style="--p:${(s.crossfade / 12) * 100}%"><span class="rv">${s.crossfade}s</span></div></div>
      ${tog('resume', 'Resume playback on start', 'Restore the last track, list and position')}
      <div class="set-sec">Look and Feel</div>
      <div class="glass-only">
      ${pick('glassMood', 'Mood', 'Brutal: black and white, deep darkness, hard white light and heavy type. Clear: clear glass tinted by the album art', [['brutal', 'Brutal'], ['clear', 'Clear']])}
      <div class="clear-only">
      ${tog('dynamicAccent', 'Album art colors', 'Accent color follows the current album art')}
      <div class="set-row"><div><div class="set-label">Accent color</div><div class="set-hint">Used when album art colors are off or the cover has none</div></div><input type="color" value="${s.accent}" data-c="accent"></div>
      </div>
      ${ANDROID ? '' : pick('windowBg', 'Window background', 'Wallpaper: your desktop wallpaper shows through, sharp. Blur: Windows acrylic', [['wallpaper', 'Wallpaper'], ...(this.glassSupported ? [['acrylic', 'Blur']] : []), ['solid', 'Solid']])}
      ${pick('buttonStyle', 'Button style', 'Clear iOS-style glass, soap bubbles, or glass filled with liquid', [['ios', 'Glass'], ['bubble', 'Bubble'], ['aqua', 'Liquid']])}
      ${pick('liquid', 'Bubble / liquid color', 'Tint of the Bubble and Liquid styles', [['aqua', 'Aqua'], ['lime', 'Lime'], ['accent', 'Album']])}
      ${tog('refraction', 'Liquid glass refraction', 'Buttons bend the background at their edges like thick glass (GPU)')}
      ${tog('blurBg', 'Blurred album art background')}
      </div>
      ${pa.setFullScreen ? pick('fullFit', 'Full screen album art', 'Fill screen: stretched over the whole screen, no empty space. Whole cover: the album art at its own shape, its colours glowing beside it', [['fill', 'Fill screen'], ['fit', 'Whole cover']]) : ''}
      ${tog('waveform', 'Waveform seekbar', 'Decode tracks to draw a real waveform')}
      ${tog('rating', 'Show rating stars')}
      ${tog('reduceMotion', 'Reduce animations')}
      <label class="set-row"><div><div class="set-label">Reduce effects</div><div class="set-hint">No blur, spinning CD, aurora, sparkles or grain; solid panels instead of glass. On automatically when Windows asks for less motion</div></div>
      <input type="checkbox" class="switch" data-k="reduceEffects" ${this.reduceEffects ? 'checked' : ''}></label>
      ${ANDROID ? '' : `<div class="set-sec">Shortcuts</div>
      <div class="shortcuts">
        <span>Space</span><span>Play / Pause</span><span>Ctrl + ← / →</span><span>Previous / Next</span>
        <span>← / →</span><span>Seek 5s (Shift: 30s)</span><span>↑ / ↓</span><span>Volume</span>
        <span>E</span><span>Equalizer</span><span>V</span><span>Visualization</span><span>Y</span><span>Lyrics</span>
        <span>S / R</span><span>Shuffle / Repeat</span><span>M</span><span>Mute</span><span>Ctrl + F</span><span>Search</span>
        <span>I</span><span>Flip album art (song info)</span><span>Backspace</span><span>Back</span><span>Alt + 0…5</span><span>Rate track</span>
      </div>`}
      <div class="about">YouSic 1.0 · A music player for ${ANDROID ? 'Android' : 'Windows'}</div>`,
    });
    body.addEventListener('change', (e) => {
      const k = e.target.dataset.k;
      if (k) {
        s[k] = e.target.checked;
        this.store.saveSettings();
        this._applyLook();
        if (k === 'dynamicAccent') this.playerView.refreshColors();
        if (k === 'rating') this.playerView.renderRating();
        if (k === 'waveform') { this.playerView.wave.enabled = s.waveform; if (this.player.track) this.playerView.wave.setTrack(this.player.track); }
      }
      if (e.target.dataset.c) { s.accent = e.target.value; this.store.saveSettings(); this._applyLook(); this.playerView.refreshColors(); }
    });
    body.addEventListener('input', (e) => {
      if (e.target.dataset.r === 'crossfade') {
        s.crossfade = +e.target.value;
        e.target.nextElementSibling.textContent = `${s.crossfade}s`;
        e.target.style.setProperty('--p', `${(s.crossfade / 12) * 100}%`);
        this.store.saveSettings();
      }
    });
    body.addEventListener('click', async (e) => {
      const rm = e.target.closest('[data-rm]');
      if (rm) { const lib = await pa.removeFolder(rm.dataset.rm); this.store.setLibrary(lib); closeSheet(); this.settings(); return; }
      const pk = e.target.closest('[data-pick]');
      if (pk) {
        const key = pk.dataset.pick;
        s[key] = pk.dataset.v;
        this.store.saveSettings();
        if (key === 'fullFit') this.setFullFit(s[key]);
        this._applyLook();
        if (key === 'windowBg' || key === 'theme') this._applyGlassWindow();
        if (key === 'theme') {
          // the theme changes fonts, so the sliding selection inks need re-measuring
          this.playerView.refreshColors();
          requestAnimationFrame(() => { this._navInk(); this.eq.tab(this.eq.curTab, false); });
        }
        body.querySelectorAll(`[data-pick="${key}"]`).forEach((b) => b.classList.toggle('on', b === pk));
        return;
      }
      const a = e.target.closest('[data-a]');
      if (!a) return;
      if (a.dataset.a === 'add') { closeSheet(); await this.addFolder(); this.settings(); }
      if (a.dataset.a === 'rescan') { closeSheet(); this.rescan(true); }
    });
  }

  toast(m) { toast(m); }
}

const app = new App();
window.__app = app;
app.init().catch((e) => {
  console.error(e);
  document.body.insertAdjacentHTML('beforeend', `<pre class="fatal">${String(e && e.stack || e)}</pre>`);
});
