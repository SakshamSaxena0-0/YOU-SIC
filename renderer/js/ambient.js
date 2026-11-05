// Ambient light (Liquid Glass's Brutal mood, wide windows): no border between the cover and the panels
// beside it (the library on the left, lyrics on the right). The cover's left and right edges fade out
// (brutal.css masks them); under that fade, and on into each panel, lies the light of those edges: the
// colours along the edge, averaged into one soft column, fading away from the cover. The piece under the
// cover and the piece in the panel are cut from the same light, so where they meet they are one and the
// partition doesn't show. The light follows the cover's own darkening (its scrim at the top and behind
// the controls, app.css .aa-scrim), so it comes from the part of the cover you see. Each piece is a tiny
// canvas the browser stretches over its area: soft light without a blur filter. Drawn once per song and
// again when the layout changes size; the old light cross-fades to the new one (brutal.css .amb).
const W = 32, H = 48;
const FLOOR = 0.22; // the light left where the scrim is darkest, behind the controls

// the scrim's two gradients (app.css .app.wide #aaWrap .aa-scrim): [distance from that end, darkness]
const lerp = (stops, d) => {
  let prev = stops[0];
  if (d <= prev[0]) return prev[1];
  for (const s of stops) {
    if (d <= s[0]) return prev[1] + (s[1] - prev[1]) * ((d - prev[0]) / Math.max(1e-6, s[0] - prev[0]));
    prev = s;
  }
  return prev[1];
};
const climb = (stops) => { let m = 0; return stops.map(([p, a]) => [(m = Math.max(m, p)), a]); };
function scrim(y, g) {
  const top = climb([[0, 0.9], [g.tb + 58, 0.8], [g.tb + 120, 0.36], [g.tb + 200, 0]]);
  const bot = climb([[0, 0.94], [g.bb - 30, 0.82], [g.bb + 50, 0.45], [g.bb + 150, 0]]);
  return 1 - (1 - lerp(top, y)) * (1 - lerp(bot, g.h - y));
}

// the cover's visible edge on one side, averaged down to one column of H colours (a little brighter)
function column(img, side, g) {
  const iw = img.naturalWidth, ih = img.naturalHeight;
  if (!iw || !ih || !g.w || !g.h) return null;
  // the cover fills its box (background-size: cover, background-position: 50% 38%, app.css .aa-img)
  const s = Math.max(g.w / iw, g.h / ih);
  const ox = (g.w - iw * s) / 2, oy = (g.h - ih * s) * 0.38;
  const band = Math.min(iw, Math.max(1, (g.f * 1.5) / s));
  const sx = side === 'l' ? -ox / s : (g.w - ox) / s - band;
  const col = document.createElement('canvas');
  col.width = 1; col.height = H;
  const cg = col.getContext('2d');
  cg.imageSmoothingQuality = 'high';
  cg.filter = 'saturate(1.35) brightness(1.15)';
  cg.drawImage(img, Math.max(0, sx), Math.max(0, -oy / s), band, Math.min(ih, g.h / s), 0, 0, 1, H);
  return col;
}

// paint one piece: the column stretched across it, fading away from the cover (panels only), and
// following the scrim down the window
function paint(c, col, side, kind, g) {
  const w = kind === 'panel' ? W : 2;
  c.width = w; c.height = H;
  const x = c.getContext('2d');
  x.drawImage(col, 0, 0, 1, H, 0, 0, w, H);
  x.globalCompositeOperation = 'destination-in';
  if (kind === 'panel') {
    // full strength where the panel meets the cover, gone by its far side
    const fromCover = side === 'l' ? [w, 0] : [0, w];
    const fade = x.createLinearGradient(fromCover[0], 0, fromCover[1], 0);
    for (const [p, a] of [[0, 1], [0.12, 0.72], [0.3, 0.45], [0.6, 0.16], [1, 0]]) fade.addColorStop(p, `rgba(0,0,0,${a})`);
    x.fillStyle = fade;
    x.fillRect(0, 0, w, H);
  }
  const down = x.createLinearGradient(0, 0, 0, H);
  for (let i = 0; i <= 24; i++) {
    const t = i / 24;
    down.addColorStop(t, `rgba(0,0,0,${(FLOOR + (1 - FLOOR) * (1 - scrim(t * g.h, g))).toFixed(3)})`);
  }
  x.fillStyle = down;
  x.fillRect(0, 0, w, H);
}

export class Ambient {
  // panels: [[element, 'l' | 'r']]: 'l' sits left of the cover, 'r' right of it. wrap and track: the
  // cover's box (#aaWrap) and the cover itself (#aaTrack), whose edges get a piece each, under the cover.
  constructor(panels, wrap, track) {
    this.track = track; this.wrap = wrap;
    const piece = (host, side, kind, cls) => {
      const box = document.createElement('div');
      box.className = `amb ${cls}`;
      box.setAttribute('aria-hidden', 'true');
      host.prepend(box);
      return { box, side, kind, cur: null };
    };
    this.pieces = panels.filter(([el]) => el).map(([el, side]) => piece(el, side, 'panel', `amb-${side}`));
    if (wrap && track) this.pieces.push(piece(wrap, 'l', 'edge', 'amb-edge amb-el'), piece(wrap, 'r', 'edge', 'amb-edge amb-er'));
    this.url = null;
    this.img = null;
    this.seq = 0;
    this.key = '';
  }

  _geom() {
    const t = this.track, edge = this.pieces.find((p) => p.kind === 'edge');
    const css = (el, k) => parseFloat(getComputedStyle(el).getPropertyValue(k)) || 0;
    const g = {
      w: t ? t.offsetWidth : 0, h: t ? t.offsetHeight : 0, f: edge ? edge.box.offsetWidth : 0,
      tb: css(document.documentElement, '--tb'), bb: this.wrap ? css(this.wrap, '--bb') : 0,
    };
    g.key = [g.w, g.h, g.f, g.tb, g.bb].map(Math.round).join();
    return g;
  }

  set(url) {
    url = url || '';
    if (url === this.url) return;
    this.url = url;
    const seq = ++this.seq;
    if (!url) return this._show(seq, null);
    const im = new Image();
    im.onload = () => this._show(seq, im);
    im.onerror = () => this._show(seq, null);
    im.src = url;
  }

  // the layout changed size (PlayerView._bleed): redraw the light in place, once things settle
  refit() {
    clearTimeout(this._refit);
    this._refit = setTimeout(() => {
      if (!this.img) return;
      const g = this._geom();
      if (g.key === this.key || !g.w) return;
      this.key = g.key;
      const cols = { l: column(this.img, 'l', g), r: column(this.img, 'r', g) };
      for (const p of this.pieces) if (p.cur && cols[p.side]) paint(p.cur, cols[p.side], p.side, p.kind, g);
    }, 150);
  }

  _show(seq, im) {
    if (seq !== this.seq) return;
    this.img = im;
    const g = this._geom();
    this.key = g.key;
    const cols = im ? { l: column(im, 'l', g), r: column(im, 'r', g) } : {};
    for (const p of this.pieces) {
      for (const old of p.box.querySelectorAll('canvas.show')) {
        old.classList.remove('show');
        setTimeout(() => old.remove(), 1000);
      }
      p.cur = null;
      const col = cols[p.side];
      if (!col) continue;
      const c = document.createElement('canvas');
      paint(c, col, p.side, p.kind, g);
      p.box.append(c);
      p.cur = c;
      void c.offsetWidth; // start from transparent, so it fades in
      c.classList.add('show');
    }
  }
}
