import { $, $$, coverUrl, installRipples, esc, clamp } from './util.js';
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

class App {
  constructor() {
    this.store = new Store();
    this.root = $('#app');
    this.screen = 'player';
    this.visOpen = false;
  }

  async init() {
    hydrateIcons();
    installRipples();
    this._bindWindow();
    this._bindTooltips();
    this._buildBubbles();
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
    this._applyLook();
    this._bindNav();
    this._bindKeys();
    this._bindVolume();
    this._mediaSession();
    this._thumbIcons = { prev: iconPng('skip_previous'), next: iconPng('skip_next'), play: iconPng('play'), pause: iconPng('pause') };
    this.updateThumbar(false);
    pa.onCommand((c) => ({ prev: () => this.player.prev(), next: () => this.player.next(), toggle: () => this.player.toggle() })[c]?.());
    pa.onProgress((m) => this._scanProgress(m));

    this._layout();
    new ResizeObserver(() => { this._layout(); this._navInk(); }).observe(document.body);
    this._bindPremium();
    // liquid glass refraction for every .lg element, now and as screens build new ones
    document.fonts.ready.then(() => { liquidAll(); watchLiquid(); });
    this.glassSupported = await pa.glassSupported();
    this._applyGlassWindow();

    if (s.resume) this.player.restoreSession();
    if (!this.player.track) this.playerView.refreshColors();
    // refresh the library in the background (incremental: only changed files are re-read)
    if (this.store.folders.length) this.rescan(false, !this.store.tracks.length);
    requestAnimationFrame(() => { this.root.classList.add('ready'); document.documentElement.classList.remove('boot-y2k'); });
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

  _layout() {
    const wide = innerWidth >= 980;
    if (wide !== this.root.classList.contains('wide')) {
      this.root.classList.toggle('wide', wide);
      if (wide && this.screen === 'player') this.screen = 'library';
      else if (!wide && this.screen === 'library') this.screen = 'player';
      this._applyScreen(false);
    }
  }

  _applyScreen(animate = true) {
    this.root.classList.toggle('no-anim', !animate || this.store.settings.reduceMotion);
    this.root.dataset.screen = this.screen;
    $$('.nav-btn[data-act=library]').forEach((b) => b.classList.toggle('on', this.screen === 'library' && !this.narrow));
    $$('.nav-btn[data-act=eq]').forEach((b) => b.classList.toggle('on', this.screen === 'eq'));
    if (animate) requestAnimationFrame(() => this.root.classList.remove('no-anim'));
  }

  show(screen) {
    closeMenu();
    if (this.root.classList.contains('wide') && screen === 'player') screen = 'library';
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
    $('#aaWrap').classList.toggle('vis-on', this.visOpen);
    if (this.visOpen) { if (this.lyrics.open) this.lyrics.toggle(false); this.vis.show(); }
    else this.vis.hide();
  }

  toggleLyrics() {
    if (this.visOpen) this.toggleVis(false);
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
        vis: () => this.toggleVis(),
        lyrics: () => this.toggleLyrics(),
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

  // Y2K theme: glass bubbles behind every pane. Sizes, spots and drift are fixed per bubble (seeded), and
  // the pointer moves them a little (bigger bubbles more) for depth. Drawn and animated in subway.css §13.
  _buildBubbles() {
    const host = $('#fxBubbles');
    const spots = [ // [x%, y%, size px]: a few at the left edge, a cluster bottom-right, a couple elsewhere
      [3, 34, 64], [6, 58, 28], [2, 80, 104], [86, 72, 120], [93, 60, 40], [80, 90, 56], [95, 88, 24], [48, 94, 34], [60, 8, 20],
    ];
    host.innerHTML = spots.map(([x, y, d], i) => {
      const dur = 12 + ((i * 7) % 9);           // 12-20s
      const phase = -Math.round(((i * 5.3) % dur) * 2) / 2; // randomised start, on a half-second
      const depth = Math.round(6 + ((d - 20) / 100) * 8); // 6-14px of parallax
      // two steps a second, on the same beat as the other decorations (see the note in subway.css §3)
      return `<i style="--d:${d}px;--x:${x}%;--y:${y}%;--depth:${depth};--dir:${i % 2 ? 1 : -1};animation-duration:${dur}s;animation-delay:${phase}s;animation-timing-function:steps(${dur * 2})"></i>`;
    }).join('');
    const fx = $('#fx');
    let raf = 0, mx = 0, my = 0;
    addEventListener('pointermove', (e) => {
      if (!document.documentElement.classList.contains('theme-subway')) return;
      mx = (e.clientX / innerWidth - 0.5) * 2;
      my = (e.clientY / innerHeight - 0.5) * 2;
      if (!raf) raf = requestAnimationFrame(() => { raf = 0; fx.style.setProperty('--mx', mx.toFixed(3)); fx.style.setProperty('--my', my.toFixed(3)); });
    }, { passive: true });
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
      else if (k === 'Backspace' || (k === 'ArrowLeft' && e.altKey) || k === 'BrowserBack') { if (this.screen === 'library') this.library.back(); else this.showPlayer(); }
      else if (k === 'Escape') { if (this.vis.host.classList.contains('full')) this.vis.toggleFull(); else if (this.visOpen) this.toggleVis(false); else if (this.screen !== 'player') this.showPlayer(); }
      else if (k === 'e' || k === 'E') this.openEq();
      else if (k === 'v' || k === 'V') this.toggleVis();
      else if ((k === 'f' || k === 'F') && this.visOpen) this.vis.toggleFull();
      else if (k === 'y' || k === 'Y') this.toggleLyrics();
      else if (k === 'i' || k === 'I') this.playerView.flipArt();
      else if (k === 's' || k === 'S') $('#btnShuffle').click();
      else if (k === 'r' || k === 'R') $('#btnRepeat').click();
      else if (k === 'm' || k === 'M') this.toggleMute();
      else if (/^[0-5]$/.test(k) && e.altKey && p.track) this.store.setRating(p.track.id, +k);
    });
    // mouse back button
    addEventListener('mouseup', (e) => { if (e.button === 3) { if (this.screen === 'library') this.library.back(); else this.showPlayer(); } });
  }

  // ---------- premium touches ----------
  _bindPremium() {
    // dock indicator slides to the active nav button
    new MutationObserver(() => this._navInk()).observe($('#plNav'), { subtree: true, attributes: true, attributeFilter: ['class'] });
    requestAnimationFrame(() => this._navInk());
    // cursor-following spotlight on cards, rows and glass buttons
    addEventListener('pointermove', (e) => {
      const el = e.target.closest && e.target.closest('.tile, .row, .gcell, .lg');
      if (!el) return;
      const r = el.getBoundingClientRect();
      el.style.setProperty('--mx', `${e.clientX - r.left}px`);
      el.style.setProperty('--my', `${e.clientY - r.top}px`);
    }, { passive: true });
  }

  _applyGlassWindow() {
    // wallpaper: the real desktop wallpaper painted behind the app (sharp); acrylic: Windows blur
    const mode = this.solidTheme ? 'solid' : this.store.settings.windowBg || 'wallpaper';
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
  }

  _bindVolume() {
    const r = $('#volRange');
    r.oninput = () => this.setVolume(r.value / 100);
    $('#volIco').onclick = () => this.toggleMute();
    const wheel = (e) => { e.preventDefault(); this.setVolume(this.store.settings.volume + (e.deltaY < 0 ? 0.03 : -0.03)); };
    $('#volBtn').addEventListener('wheel', wheel, { passive: false });
    $('#volPop').addEventListener('wheel', wheel, { passive: false });
    addEventListener('pointerdown', (e) => { if (!e.target.closest('#volPop, #volBtn')) $('#volPop').classList.remove('open'); });
    this._syncVol();
  }

  _toggleVolPop() {
    const pop = $('#volPop');
    const b = $('#volBtn').getBoundingClientRect();
    pop.style.left = `${Math.max(8, Math.min(innerWidth - 260, b.right - 240))}px`;
    pop.style.top = `${b.bottom + 6}px`;
    pop.classList.toggle('open');
  }

  _flashVol() {
    if ($('#volPop').classList.contains('open')) return;
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
  // glass is the default look; every other theme (cyber, subway, pixel) is solid: no wallpaper, glass or refraction
  get theme() { return THEMES.includes(this.store.settings.theme) ? this.store.settings.theme : 'glass'; }
  get solidTheme() { return this.theme !== 'glass'; }

  _applyLook() {
    const s = this.store.settings;
    const theme = this.theme, solid = this.solidTheme;
    const html0 = document.documentElement;
    try { localStorage.setItem('pr-theme', theme); } catch {} // read by boot.js for the splash
    if (!html0.classList.contains('theme-' + theme)) {
      THEMES.forEach((t) => html0.classList.toggle('theme-' + t, t === theme));
      html0.classList.toggle('theme-solid', solid);
      this.playerView.wave.setTheme(theme);
      this.playerView.refreshFx();
      refreshLiquid();
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
    if (!s.dynamicAccent && !solid) {
      const rgb = hexToRgb(s.accent);
      document.documentElement.style.setProperty('--accent', s.accent);
      document.documentElement.style.setProperty('--accent-rgb', rgb.join(','));
    }
  }

  onAccent() { this.eq && this.eq.onAccent(); }

  updateThumbar(playing) {
    if (!this._thumbIcons) return;
    pa.setThumbar({ ...this._thumbIcons, playing });
  }

  _mediaSession() {
    if (!('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    const p = this.player, en = this.engine;
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
        { label: 'Show in Explorer', icon: 'open_in_new', action: () => pa.showInFolder(t.path) },
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
      ${pick('theme', 'Theme', 'Liquid Glass: clear glass over your wallpaper. Cyberpunk: neon HUD. Y2K Subway: dim teal, chrome and glass. Pixel: a retro 90s desktop in pixel art', [['glass', 'Liquid Glass'], ['cyber', 'Cyberpunk'], ['subway', 'Y2K Subway'], ['pixel', 'Pixel']])}
      <div class="glass-only">
      ${tog('dynamicAccent', 'Album art colors', 'Accent color follows the current album art')}
      <div class="set-row"><div><div class="set-label">Accent color</div><div class="set-hint">Used when album art colors are off or the cover has none</div></div><input type="color" value="${s.accent}" data-c="accent"></div>
      ${pick('windowBg', 'Window background', 'Wallpaper: your desktop wallpaper shows through, sharp. Blur: Windows acrylic', [['wallpaper', 'Wallpaper'], ...(this.glassSupported ? [['acrylic', 'Blur']] : []), ['solid', 'Solid']])}
      ${pick('buttonStyle', 'Button style', 'Clear iOS-style glass, soap bubbles, or glass filled with liquid', [['ios', 'Glass'], ['bubble', 'Bubble'], ['aqua', 'Liquid']])}
      ${pick('liquid', 'Bubble / liquid color', 'Tint of the Bubble and Liquid styles', [['aqua', 'Aqua'], ['lime', 'Lime'], ['accent', 'Album']])}
      ${tog('refraction', 'Liquid glass refraction', 'Buttons bend the background at their edges like thick glass (GPU)')}
      ${tog('blurBg', 'Blurred album art background')}
      </div>
      ${tog('waveform', 'Waveform seekbar', 'Decode tracks to draw a real waveform')}
      ${tog('rating', 'Show rating stars')}
      ${tog('reduceMotion', 'Reduce animations')}
      <div class="set-sec">Shortcuts</div>
      <div class="shortcuts">
        <span>Space</span><span>Play / Pause</span><span>Ctrl + ← / →</span><span>Previous / Next</span>
        <span>← / →</span><span>Seek 5s (Shift: 30s)</span><span>↑ / ↓</span><span>Volume</span>
        <span>E</span><span>Equalizer</span><span>V</span><span>Visualization</span><span>Y</span><span>Lyrics</span>
        <span>S / R</span><span>Shuffle / Repeat</span><span>M</span><span>Mute</span><span>Ctrl + F</span><span>Search</span>
        <span>I</span><span>Flip album art (song info)</span><span>Backspace</span><span>Back</span><span>Alt + 0…5</span><span>Rate track</span>
      </div>
      <div class="about">YouSic 1.0 · A music player for Windows</div>`,
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
