import { $, esc, ANDROID } from './util.js';
import { icon } from './icons.js';

// Lyrics panel shown over the album art (swipe up). Supports synced .lrc / tag lyrics and plain text.
export class LyricsView {
  constructor(app) {
    this.app = app;
    this.player = app.player;
    this.engine = app.engine;
    this.panel = $('#lyricsPanel');
    this.scroll = $('#lyricsScroll');
    this.open = false;
    this.cache = new Map();
    this.cur = -1;
    this.userScrollUntil = 0;
    this.player.addEventListener('track', () => { if (this.open) this.load(); });
    this.scroll.addEventListener('wheel', () => (this.userScrollUntil = performance.now() + 3500), { passive: true });
    this.scroll.addEventListener('click', (e) => {
      const l = e.target.closest('.ly-line');
      if (l && l.dataset.t) this.engine.seek(+l.dataset.t);
      const a = e.target.closest('[data-q]');
      if (a) window.open(a.dataset.q);
      // Android: open the system page that lets the app read .lrc files; look again when the user comes back
      if (e.target.closest('[data-fa]')) pa.requestFileAccess().then((ok) => { if (ok) { this.cache.clear(); this.load(); } });
    });
  }

  toggle(force) {
    const was = this.open;
    this.open = force ?? !this.open;
    this.app.paneHost().classList.toggle('lyrics-open', this.open);
    document.querySelector('.nav-btn[data-act=lyrics]').classList.toggle('on', this.open);
    if (this.open && !was) { this.load(); this._loop(); }
    this.app._syncSide();
  }

  async load() {
    const t = this.player.track;
    this.lines = null;
    this.cur = -1;
    if (!t) { this.scroll.innerHTML = '<div class="ly-empty">Nothing playing</div>'; return; }
    this.scroll.innerHTML = '<div class="ly-empty">Searching lyrics…</div>';
    let data = this.cache.get(t.id);
    if (data === undefined) {
      data = await pa.lyrics(t.path).catch(() => null);
      this.cache.set(t.id, data);
    }
    if (this.player.track !== t) return;
    if (!data) {
      const q = encodeURIComponent(`${t.artist} ${t.title} lyrics`);
      this.scroll.innerHTML = `<div class="ly-empty">${icon('lyrics')}<div>No lyrics found</div>
        <small>Put a <b>.lrc</b> file with the same name next to the song, or embed lyrics in the tags.</small>
        <div class="ly-actions"><button class="btn ghost" data-q="https://genius.com/search?q=${q}">Genius</button>
        <button class="btn ghost" data-q="https://www.google.com/search?q=${q}">Google</button></div>
        ${ANDROID && !pa.fileAccess() ? `<small>Android only lets YouSic read .lrc files once it may open all your files.</small>
        <div class="ly-actions"><button class="btn" data-fa="1">Allow .lrc files</button></div>` : ''}</div>`;
      return;
    }
    if (data.synced) {
      this.lines = data.synced;
      this.scroll.innerHTML = `<div class="ly-pad"></div>${data.synced.map((l, i) => `<div class="ly-line" data-i="${i}" data-t="${l.t}">${esc(l.text) || '♪'}</div>`).join('')}<div class="ly-pad"></div>`;
      this.lineEls = [...this.scroll.querySelectorAll('.ly-line')];
    } else {
      this.scroll.innerHTML = `<div class="ly-plain">${esc(data.text).replace(/\n/g, '<br>')}</div>`;
    }
    this.scroll.scrollTop = 0;
  }

  _loop() {
    if (!this.open) return;
    if (this.lines) {
      const t = this.engine.currentTime + 0.15;
      let lo = 0, hi = this.lines.length - 1, idx = -1;
      while (lo <= hi) { const m = (lo + hi) >> 1; if (this.lines[m].t <= t) { idx = m; lo = m + 1; } else hi = m - 1; }
      if (idx !== this.cur) {
        if (this.cur >= 0) this.lineEls[this.cur]?.classList.remove('active');
        this.cur = idx;
        const el = this.lineEls[idx];
        if (el) {
          el.classList.add('active');
          if (performance.now() > this.userScrollUntil) {
            this.scroll.scrollTo({ top: el.offsetTop - this.scroll.clientHeight / 2 + el.offsetHeight / 2, behavior: 'smooth' });
          }
        }
      }
    }
    clearTimeout(this._loopT); // one loop at a time, however often the panel is reopened
    this._loopT = setTimeout(() => this._loop(), 100); // 10 Hz is plenty for line changes, and far cheaper than every frame
  }
}
