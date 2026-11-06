import { $, $$, esc, fmtTime, coverUrl, clamp, spring, ANDROID } from './util.js';
import { setIcon, icon } from './icons.js';
import { extractColors } from './palette.js';
import { Waveform } from './waveform.js';
import { THEME_COLORS, GLASS_BRUTAL } from './store.js';
import { Ambient } from './ambient.js';

const PLACEHOLDER = 'placeholder';

export class PlayerView {
  constructor(app) {
    this.app = app;
    const { player, engine, store } = app;
    this.player = player; this.engine = engine; this.store = store;
    this.metaMode = 0;
    this.showRemain = true;
    this.bgFlip = 0;
    this.ambient = new Ambient([[$('#library'), 'l'], [$('#eq'), 'l'], [$('#side'), 'r']], $('#aaWrap'), $('#aaTrack'));
    this.lastPrev = null; this.lastNext = null;
    this.cards = {
      prev: $('[data-slot=prev]'), cur: $('[data-slot=cur]'), next: $('[data-slot=next]'),
    };
    this.wave = new Waveform($('#wave'), $('#seekBubble'), engine, { onSeek: (t) => engine.seek(t) });
    this.wave.enabled = store.settings.waveform;

    player.addEventListener('track', (e) => this.setTrack(e.detail));
    player.addEventListener('state', () => this.syncState());
    player.addEventListener('modes', () => this.syncModes());
    player.addEventListener('queue', () => this.syncNeighbors());
    store.addEventListener('stats', (e) => { if (player.track && e.detail === player.track.id) this.renderRating(); });

    $('#btnPlay').onclick = () => player.toggle();
    $('#btnNext').onclick = () => player.next();
    $('#btnPrev').onclick = () => player.prev();
    $('#btnShuffle').onclick = () => { player.setShuffle(!player.shuffle); app.toast(player.shuffle ? 'Shuffle Songs' : 'Shuffle Off'); };
    $('#btnRepeat').onclick = () => {
      player.cycleRepeat();
      app.toast({ off: 'Repeat Off', all: 'Repeat Category', one: 'Repeat Song' }[player.repeat]);
    };
    $('#plMeta').onclick = () => { this.metaMode = (this.metaMode + 1) % 4; this.renderMeta(); };
    $('#tRemain').onclick = () => { this.showRemain = !this.showRemain; this.tick(); };
    $('#plCat').onclick = () => app.openLibrary(player.source?.node);

    this._bindArt();
    this.syncModes();
    this.syncState();
    this._loop();
  }

  // ---------- track ----------
  setTrack(t) {
    const dir = this.lastNext && t === this.lastNext ? 1 : this.lastPrev && t === this.lastPrev && t !== this.lastNext ? -1 : 0;
    $('#plTitle').textContent = t.title;
    $('#plArtist').textContent = t.artist || 'Unknown artist';
    $('#plAlbum').textContent = [t.album, t.year || ''].filter(Boolean).join(' • ');
    this._marquee();
    this.renderMeta();
    this.renderRating();
    this.wave.setTrack(t);
    this.syncHeader();
    const url = coverUrl(t.cover);
    // the card slides to the next cover, so turn it face-up first (instantly)
    if (this.flipped) this.flipArt(false, false);
    if (this._skipArt) {
      this._skipArt = false;
      this._setImg(this.cards.cur, url);
      this.syncNeighbors();
    } else {
      this._animateArt(dir, url, () => this.syncNeighbors());
    }
    this._setBackground(url);
    this._applyColors(url);
    this._setGlow(url);
    this._renderBadge(t);
    const img = this.cards.cur.querySelector('.aa-img');
    img.classList.remove('sheen'); void img.offsetWidth; img.classList.add('sheen');
    // stagger in the text block
    const info = $('.pl-info');
    info.classList.remove('enter-l', 'enter-r', 'enter');
    void info.offsetWidth;
    info.classList.add(dir > 0 ? 'enter-r' : dir < 0 ? 'enter-l' : 'enter');
    pa.setTitle(`${t.title}${t.artist ? ' — ' + t.artist : ''} · YouSic`);
  }

  syncHeader() {
    const p = this.player;
    $('#plCatName').textContent = p.source?.title || 'Library';
    $('#plCatPos').textContent = p.list.length ? `${p.pos + 1} / ${p.list.length}${p.userQueue.length ? ` · Queue ${p.userQueue.length}` : ''}` : '';
  }

  syncNeighbors() {
    this.lastPrev = this.player.peekPrev();
    this.lastNext = this.player.peekNext(false);
    this._setImg(this.cards.prev, this.lastPrev ? coverUrl(this.lastPrev.cover) : '');
    this._setImg(this.cards.next, this.lastNext ? coverUrl(this.lastNext.cover) : '');
    this.syncHeader();
  }

  _setImg(card, url) {
    const img = card.querySelector('.aa-img');
    if (img.dataset.url === (url || PLACEHOLDER)) return;
    img.dataset.url = url || PLACEHOLDER;
    if (url) { img.style.backgroundImage = `url("${url}")`; img.classList.remove('ph'); img.innerHTML = ''; }
    else { img.style.backgroundImage = ''; img.classList.add('ph'); img.innerHTML = icon('music_note'); }
  }


  _animateArt(dir, url, done) {
    const w = $('#aaWrap').clientWidth;
    // Y2K theme: the cover crossfades (400ms) instead of sliding
    if (document.documentElement.classList.contains('theme-subway') && !this.store.settings.reduceMotion) {
      const img = this.cards.cur.querySelector('.aa-img');
      const tok = (this._artTok = (this._artTok || 0) + 1);
      this._setX(0);
      img.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 160, easing: 'ease-in' }).finished.then(() => {
        if (tok !== this._artTok) return;
        this._setImg(this.cards.cur, url);
        img.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 240, easing: 'ease-out' });
        done();
      }, () => {});
      return;
    }
    if (dir !== 0 && !this.store.settings.reduceMotion && w > 0) {
      // slide the carousel one card over, then snap back with swapped images
      const target = dir > 0 ? this.cards.next : this.cards.prev;
      this._setImg(target, url);
      this._springTo(-dir * this._step(w), () => {
        this._setImg(this.cards.cur, url);
        this._setX(0);
        done();
      });
    } else {
      const img = this.cards.cur.querySelector('.aa-img');
      this._setImg(this.cards.cur, url);
      this._setX(0);
      img.animate([{ opacity: 0.2, transform: 'scale(.92)' }, { opacity: 1, transform: 'scale(1)' }],
        { duration: 420, easing: 'cubic-bezier(.2,.8,.2,1)' });
      done();
    }
  }

  _step() { return (this.cards.cur.offsetWidth || 300) + 28; }

  _setX(x) {
    this.x = x;
    const w = $('#aaWrap').clientWidth;
    const step = this._step(w);
    for (const [slot, off] of [['prev', -1], ['cur', 0], ['next', 1]]) {
      const pos = off * step + x;
      const d = clamp(Math.abs(pos) / step, 0, 1);
      const el = this.cards[slot];
      el.style.transform = `translateX(${pos}px) scale(${1 - d * 0.12}) rotateY(${(pos / step) * -14}deg)`;
      el.style.opacity = String(slot === 'cur' ? 1 - d * 0.55 : Math.min(1, (1 - d) * 1.6));
    }
  }

  _springTo(to, done) {
    if (this._stopSpring) this._stopSpring();
    this._stopSpring = spring(null, { from: this.x || 0, to, stiffness: 320, damping: 32, onUpdate: (v) => this._setX(v), onDone: done });
  }

  // Wide layout: the cover fills the whole middle column, not just the space between the header and the
  // title block that #aaWrap takes up (css: .app.wide .aa-track). Measure how far the column reaches past
  // #aaWrap on each side; its right edge is the side panel's left edge while that panel is open.
  _bleed() {
    if (this.app.narrow) return;
    const wrap = $('#aaWrap'), w = wrap.getBoundingClientRect(), p = $('#player').getBoundingClientRect();
    const side = $('#side').getBoundingClientRect();
    const right = Math.min(p.right, side.width ? side.left : p.right);
    const set = (k, v) => wrap.style.setProperty(k, `${Math.max(0, Math.round(v))}px`);
    set('--bt', w.top - p.top); set('--bb', p.bottom - w.bottom);
    set('--bl', w.left - p.left); set('--br', right - w.right);
    this.ambient.refit();
  }

  _bindArt() {
    const wrap = $('#aaWrap');
    this._setX(0);
    new ResizeObserver(() => { this._bleed(); this._setX(this.x || 0); }).observe(wrap);
    $('#side').addEventListener('transitionend', (e) => { if (e.target.id === 'side') { this._bleed(); this._setX(this.x || 0); } });
    let sx, sy, st, axis = null, id = null, lastX, lastT, vx = 0;
    wrap.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || (this.app.visOpen && this.app.narrow) || wrap.classList.contains('lyrics-open') && e.target.closest('.lyrics-scroll')) return;
      id = e.pointerId; sx = lastX = e.clientX; sy = e.clientY; st = lastT = performance.now(); axis = null; vx = 0;
      if (this._stopSpring) this._stopSpring();
    });
    wrap.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      const dx = e.clientX - sx, dy = e.clientY - sy;
      if (!axis && Math.hypot(dx, dy) > 8) {
        axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
        wrap.setPointerCapture(id);
        this._swiping = axis === 'x';
        if (this.flipped) this.flipArt(false, false);
      }
      const now = performance.now();
      vx = (e.clientX - lastX) / Math.max(1, now - lastT);
      lastX = e.clientX; lastT = now;
      if (axis === 'x') this._setX(dx * (this.player.list.length ? 1 : 0.3));
      if (axis === 'y') {
        const k = clamp(dy, -120, 120);
        this.cards.cur.style.transform = `translateY(${k * 0.35}px) scale(${1 - Math.abs(k) / 1600})`;
        this._hint(dy < -40 ? 'Lyrics' : dy > 40 ? 'Library' : '');
      }
    });
    const end = (e) => {
      if (e.pointerId !== id) return;
      id = null;
      const dx = e.clientX - sx, dy = e.clientY - sy;
      const w = wrap.clientWidth;
      this._hint('');
      if (!axis) {
        if (performance.now() - st > 400) return;
        // a tap on the cover flips it over (buttons on the back do their own thing)
        if (e.target.closest('.aa-back button')) return;
        // showing only the art: a tap brings the controls back
        if (this.app.root.classList.contains('art-only')) { this.app.toggleArt(false); return; }
        if (e.target.closest('[data-slot=cur], #aaInfo') && !wrap.classList.contains('lyrics-open')) this.flipArt();
        else this.app.openLibrary(this.player.source?.node);
        return;
      }
      if (axis === 'y') {
        this.cards.cur.animate([{ transform: this.cards.cur.style.transform }, { transform: 'none' }], { duration: 300, easing: 'cubic-bezier(.2,.8,.2,1)' });
        this._setX(0);
        if (dy < -60) this.app.toggleLyrics();
        else if (dy > 60) this.app.openLibrary(this.player.source?.node);
        return;
      }
      const step = this._step(w);
      const commit = (Math.abs(dx) > w * 0.22 || Math.abs(vx) > 0.6) && this.player.list.length;
      if (commit) {
        const dir = dx < 0 ? 1 : -1;
        const target = dir > 0 ? this.lastNext : this.lastPrev;
        this._springTo(-dir * step, () => {
          this._swiping = false;
          this._setImg(this.cards.cur, target ? coverUrl(target.cover) : '');
          this._setX(0);
          this._skipArt = true;
          dir > 0 ? this.player.next() : this.player.prev(true);
          this._skipArt = false;
        });
      } else {
        this._swiping = false;
        this._springTo(0);
      }
    };
    wrap.addEventListener('pointerup', end);
    wrap.addEventListener('pointercancel', end);
    wrap.addEventListener('wheel', (e) => {
      if ((this.app.visOpen && this.app.narrow) || e.target.closest('.lyrics-scroll')) return;
      // horizontal wheel/touchpad swipe changes tracks
      if (Math.abs(e.deltaX) > 30 && Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        const now = performance.now();
        if (now - (this._wheelT || 0) < 600) return;
        this._wheelT = now;
        e.deltaX > 0 ? this.player.next() : this.player.prev(true);
      }
    });
  }

  // ---------- flip card: song info on the back of the cover ----------
  flipArt(on = !this.flipped, animate = true) {
    const t = this.player.track;
    if (on && !t) return;
    if (on === !!this.flipped) return;
    this.flipped = on;
    if (on) this._renderBack(t);
    const wrap = $('#aaWrap');
    const card = this.cards.cur;
    const flip = card.querySelector('.aa-flip');
    flip.getAnimations().forEach((x) => x.cancel());
    card.classList.toggle('flipped', on);
    // At rest the info is shown on #aaInfo, a flat panel outside the 3D card: Chromium lowers the
    // resolution of anything that has been through a 3D turn, so the turning face is only used in motion.
    if (!on) wrap.classList.remove('info-on');
    // wide layout: the cover fills the column, so it doesn't turn over; the info card fades in over it
    if (!animate || this.store.settings.reduceMotion || !this.app.narrow) { wrap.classList.toggle('info-on', on); return; }
    const [a, b] = on ? [0, 180] : [180, 360];
    // lifts off the page mid-turn, overshoots slightly and settles, like a real card
    const anim = flip.animate([
      { transform: `rotateY(${a}deg) scale(1)` },
      { transform: `rotateY(${(a + b) / 2}deg) scale(0.9)`, offset: 0.45 },
      { transform: `rotateY(${b}deg) scale(1)` },
    ], { duration: 820, easing: 'cubic-bezier(.3,1.18,.45,1)' });
    if (on) {
      anim.finished.then(() => {
        if (!this.flipped) return;
        wrap.classList.add('info-on');
        // a glint sweeps across the card as it lands
        const info = $('#aaInfo');
        info.classList.remove('flip-glint'); void info.offsetWidth; info.classList.add('flip-glint');
      }, () => {});
    } else {
      const img = card.querySelector('.aa-img');
      img.classList.remove('flip-glint'); void img.offsetWidth; img.classList.add('flip-glint');
    }
  }

  _renderBack(t) {
    const f = t.fmt || {};
    const st = this.store.stats[t.id] || {};
    const url = coverUrl(t.cover);
    const ago = (ms) => {
      const s = (Date.now() - ms) / 1000;
      for (const [n, u] of [[31536000, 'year'], [2592000, 'month'], [86400, 'day'], [3600, 'hour'], [60, 'minute']]) {
        if (s >= n) { const v = Math.floor(s / n); return `${v} ${u}${v > 1 ? 's' : ''} ago`; }
      }
      return 'just now';
    };
    const pos = [t.track ? `Track ${t.track}${t.disc > 1 ? ` · Disc ${t.disc}` : ''}` : '', fmtTime(t.duration)].filter(Boolean).join(' · ');
    const rows = [
      ['Album', t.album], ['Album artist', t.albumArtist !== t.artist ? t.albumArtist : ''], ['Year', t.year || ''],
      ['Genre', t.genre], ['Composer', t.composer],
    ].filter((r) => r[1]).slice(0, 4);
    const ch = f.channels === 1 ? 'Mono' : f.channels === 2 ? 'Stereo' : f.channels ? `${f.channels} ch` : '';
    const fmt = [(f.codec || '').toUpperCase(), f.bits ? `${f.bits}-bit` : '', f.sampleRate ? `${+(f.sampleRate / 1000).toFixed(1)} kHz` : '',
      f.bitrate ? `${f.bitrate} kbps` : '', ch].filter(Boolean);
    const stars = st.rating ? '★'.repeat(st.rating) + '☆'.repeat(5 - st.rating) : '';
    const foot = [st.plays ? `Played ${st.plays}×` : '', st.last ? `last ${ago(st.last)}` : '', t.size ? `${(t.size / 1048576).toFixed(1)} MB` : ''].filter(Boolean);
    const html = `
      <div class="ab-art" style="${url ? `background-image:url('${url}')` : ''}"></div>
      <div class="ab-body">
        <div class="ab-kicker">${icon('info')}<span>Song info${pos ? ` · ${esc(pos)}` : ''}</span></div>
        <div class="ab-title">${esc(t.title)}</div>
        <div class="ab-artist">${esc(t.artist || 'Unknown artist')}</div>
        ${stars ? `<div class="ab-stars">${stars}</div>` : ''}
        <dl class="ab-grid">${rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('')}</dl>
        ${fmt.length ? `<div class="ab-fmt">${fmt.map((x) => `<span>${esc(x)}</span>`).join('')}</div>` : ''}
        <div class="ab-foot">${esc(foot.join(' · '))}</div>
        <div class="ab-file" title="${esc(t.path)}">${esc(t.path.split(/[\\/]/).pop())}</div>
        <div class="ab-actions">
          ${t.album ? `<button class="ab-btn" data-ab="album" title="Go to album">${icon('album')}<span>Album</span></button>` : ''}
          ${t.artist ? `<button class="ab-btn" data-ab="artist" title="Go to artist">${icon('person')}<span>Artist</span></button>` : ''}
          ${ANDROID ? '' : `<button class="ab-btn" data-ab="folder" title="Show in folder">${icon('folder')}<span>Show file</span></button>`}
        </div>
      </div>`;
    $('#aaBack').innerHTML = html;
    $('#aaInfo').innerHTML = html;
    $('#aaInfo').querySelectorAll('[data-ab]').forEach((b) => {
      b.onclick = () => {
        const a = b.dataset.ab;
        if (a === 'album') this.app.openLibrary({ type: 'album', key: t.albumKey, title: t.album });
        if (a === 'artist') this.app.openLibrary({ type: 'person', field: 'artist', key: t.artist, title: t.artist });
        if (a === 'folder') pa.showInFolder(t.path);
      };
    });
  }

  _setGlow(url) {
    const g = $('#aaGlow');
    g.animate([{ opacity: 0 }, { opacity: getComputedStyle(g).opacity }], { duration: 900, easing: 'ease-out' });
    g.style.backgroundImage = url ? `url("${url}")` : '';
  }

  // Gold "HI-RES" for >16-bit or >48 kHz lossless, silver "LOSSLESS" for CD-quality lossless.
  _renderBadge(t) {
    const b = $('#plBadge');
    const f = t.fmt || {};
    const lossless = f.lossless || /flac|alac|pcm|wav/i.test(f.codec || '');
    const hires = lossless && ((f.bits || 0) > 16 || (f.sampleRate || 0) > 48000);
    b.className = 'badge' + (hires ? ' hires' : lossless ? ' lossless' : '');
    b.textContent = hires ? 'HI-RES' : lossless ? 'LOSSLESS' : '';
    b.title = hires ? `${f.bits || '?'}-bit / ${(f.sampleRate / 1000) || '?'} kHz lossless` : '';
  }

  _hint(text) {
    const h = $('#swipeHint');
    h.textContent = text;
    h.classList.toggle('show', !!text);
  }

  // Y2K theme backdrop: a pre-blurred, saturated copy of the cover (made once per track in a small
  // canvas, so the window never runs a live 80px blur), crossfaded between two layers.
  _setFxArt(url) {
    if (!document.documentElement.classList.contains('theme-subway')) return;
    const layers = $$('#fx .fx-art i');
    const swap = (src) => {
      const next = layers[this.fxFlip ^ 1], cur = layers[this.fxFlip];
      next.style.backgroundImage = src ? `url("${src}")` : 'none';
      next.classList.add('show');
      cur.classList.remove('show');
      this.fxFlip ^= 1;
    };
    this.fxFlip = this.fxFlip || 0;
    if (!url) return swap(null);
    const im = new Image();
    im.onload = () => {
      const c = document.createElement('canvas');
      c.width = c.height = 96;
      const g = c.getContext('2d');
      g.filter = 'blur(6px) saturate(1.6)';
      g.drawImage(im, -12, -12, 120, 120);
      swap(c.toDataURL('image/jpeg', 0.85));
    };
    im.onerror = () => swap(null);
    im.src = url;
  }

  refreshFx() { this._setFxArt(this.player.track ? coverUrl(this.player.track.cover) : ''); }

  _setBackground(url) {
    this._setFxArt(url);
    const layers = $$('#bg .bg-layer');
    const next = layers[this.bgFlip ^ 1], cur = layers[this.bgFlip];
    const apply = () => {
      next.style.backgroundImage = url ? `url("${url}")` : '';
      next.classList.add('show');
      cur.classList.remove('show');
      this.bgFlip ^= 1;
    };
    if (!url) return apply();
    const im = new Image();
    im.src = url;
    im.decode().then(apply, apply);
  }

  async _applyColors(url) {
    const s = this.store.settings;
    const root = document.documentElement;
    // the Brutal mood and Metal keep their palettes but let the cover's own colours glow into the panels beside it
    this.ambient.set(this.app.seamless ? url : '');
    let accent = hexToRgb(s.accent), dark = [16, 16, 20];
    // solid themes (and Liquid Glass's Brutal mood) run on a fixed palette, whatever the cover looks like
    const fixed = THEME_COLORS[s.theme] || (this.app.brutal ? GLASS_BRUTAL : null);
    if (fixed) { accent = fixed.accent; dark = fixed.dark; url = ''; }
    if (s.dynamicAccent && url) {
      const c = await extractColors(url);
      if (c) { if (c.accent) accent = c.accent; dark = c.dark; }
    }
    root.style.setProperty('--accent', `rgb(${accent.join(',')})`);
    root.style.setProperty('--accent-rgb', accent.join(','));
    root.style.setProperty('--tint', `rgb(${dark.join(',')})`);
    this.wave.setAccent(accent);
    this.app.onAccent && this.app.onAccent(accent);
  }

  refreshColors() { this._applyColors(this.player.track ? coverUrl(this.player.track.cover) : ''); }

  renderMeta() {
    const t = this.player.track;
    const el = $('#plMeta');
    if (!t) { el.textContent = ''; return; }
    const f = t.fmt || {};
    let text;
    switch (this.metaMode) {
      case 0: {
        const ext = (t.path.split('.').pop() || '').toUpperCase();
        text = [f.codec && f.codec.length < 14 ? f.codec.replace(/MPEG 1 Layer 3/i, 'MP3') : ext,
          f.sampleRate ? `${(f.sampleRate / 1000).toFixed(1).replace(/\.0$/, '')} kHz` : '',
          f.bits ? `${f.bits} bit` : '', f.bitrate ? `${f.bitrate} kbps` : '',
          f.channels === 1 ? 'Mono' : ''].filter(Boolean).join(' • ');
        break;
      }
      case 1:
        text = [t.track ? `Track ${t.track}` : '', t.disc > 1 ? `Disc ${t.disc}` : '', t.genre, t.year || ''].filter(Boolean).join(' • ') || 'No tag info';
        break;
      case 2: {
        const st = this.store.stats[t.id] || {};
        text = `Played ${st.plays || 0} time${st.plays === 1 ? '' : 's'}${st.last ? ' • last ' + new Date(st.last).toLocaleDateString() : ''}`;
        break;
      }
      default: text = t.path;
    }
    el.textContent = text;
    el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash');
  }

  renderRating() {
    const el = $('#rating');
    const t = this.player.track;
    el.style.display = this.store.settings.rating && t ? '' : 'none';
    if (!t) return;
    const r = this.store.rating(t.id);
    el.innerHTML = [1, 2, 3, 4, 5].map((i) => `<button class="star ${i <= r ? 'on' : ''}" data-r="${i}">${icon(i <= r ? 'star' : 'star_border')}</button>`).join('');
    el.onclick = (e) => {
      const b = e.target.closest('.star');
      if (!b) return;
      const v = +b.dataset.r;
      this.store.setRating(t.id, v === r ? 0 : v);
      const nb = el.querySelector(`[data-r="${v}"]`);
      nb && nb.animate([{ transform: 'scale(1.5)' }, { transform: 'scale(1)' }], { duration: parseFloat(getComputedStyle(el).getPropertyValue('--star-pop')) || 300, easing: 'cubic-bezier(.3,1.6,.5,1)' });
    };
  }

  _marquee() {
    const wrap = $('#plTitleWrap'), span = $('#plTitle');
    wrap.classList.remove('scroll');
    requestAnimationFrame(() => {
      const over = span.scrollWidth - wrap.clientWidth;
      if (over > 4) {
        wrap.style.setProperty('--dist', `${-over - 24}px`);
        wrap.style.setProperty('--dur', `${Math.max(6, over / 25)}s`);
        wrap.classList.add('scroll');
      }
    });
  }

  syncState() {
    const playing = this.engine.isPlayingUi;
    document.getElementById('app').classList.toggle('playing', playing);
    setIcon($('#nbPlay'), playing ? 'pause' : 'play');
    this.app.updateThumbar && this.app.updateThumbar(playing);
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
  }

  syncModes() {
    const p = this.player;
    $('#btnShuffle').classList.toggle('on', p.shuffle);
    const rb = $('#btnRepeat');
    setIcon(rb, p.repeat === 'one' ? 'repeat_one' : 'repeat');
    rb.classList.toggle('on', p.repeat !== 'off');
    this.syncNeighbors();
  }

  // Progress ticker. Instead of running every display frame (144+ wakeups a second, each forcing the
  // compositor to redraw the glass), it wakes about when the fastest indicator has moved a pixel:
  // a few times a second while playing, once a second when paused, never while minimized.
  _loop() {
    const el = $('#tElapsed'), rm = $('#tRemain'), nb = $('#nbProgress'), ring = $('#playRing');
    let lastSec = -1, lastProg = -1, timer = 0;
    const tick = () => {
      clearTimeout(timer);
      const e = this.engine;
      const t = e.currentTime, d = e.duration;
      const sec = Math.floor(t);
      if (sec !== lastSec) {
        lastSec = sec;
        el.textContent = fmtTime(t);
        rm.textContent = this.showRemain ? '-' + fmtTime(Math.max(0, d - t)) : fmtTime(d);
      }
      const prog = d ? t / d : 0;
      if (Math.abs(prog - lastProg) > 0.0005) {
        lastProg = prog;
        nb.style.transform = `scaleX(${prog})`;
        ring.style.strokeDashoffset = String(100 - prog * 100);
      }
      this.wave.sync();
      if (document.hidden) return; // resumed by visibilitychange
      let ms = 1000;
      if (d && e.isPlayingUi) {
        const px = Math.max(this.wave.w || 0, nb.offsetWidth || 0, 200) * (devicePixelRatio || 1);
        ms = Math.min(1000 - (t % 1) * 1000 + 5, Math.max(40, (d * 1000) / px));
      }
      timer = setTimeout(tick, ms);
    };
    this.tick = () => { lastSec = -1; tick(); };
    for (const ev of ['state', 'seek', 'meta']) this.engine.addEventListener(ev, this.tick);
    document.addEventListener('visibilitychange', this.tick);
    tick();
  }
}

export function hexToRgb(hex) {
  const m = /^#?([\da-f]{2})([\da-f]{2})([\da-f]{2})$/i.exec(hex || '');
  return m ? [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)] : [255, 159, 28];
}

export { esc };
