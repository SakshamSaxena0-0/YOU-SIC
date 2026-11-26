// Ambient light (Liquid Glass's Brutal mood and Metal, wide windows): no border between the cover and the panels
// beside it (the library on the left, lyrics on the right). The cover's left and right edges fade out
// (brutal.css masks them); under that fade, and on into each panel, lies the light of those edges: the
// colours along the edge, averaged into one soft column, fading away from the cover. The piece under the
// cover and the piece in the panel are cut from the same light, so where they meet they are one and the
// partition doesn't show. The light follows the cover's own darkening (its scrim at the top and behind
// the controls, app.css .aa-scrim), so it comes from the part of the cover you see. Each piece is a tiny
// canvas the browser stretches over its area: soft light without a blur filter. Drawn once per song and
// again when the layout changes size; the old light cross-fades to the new one (brutal.css .amb).
//
// All of it is drawn from a small copy of the cover (shrink), on canvases kept in memory rather than on the
// graphics card (ctx): the light's colours are read back and toned pixel by pixel (tone), and reading back
// a canvas on the card makes the page wait for the card, which mid-animation (a view transition, a swipe)
// is a visible stall of tens of milliseconds. In memory, a whole redraw costs well under a millisecond.
const W = 32, H = 48;
const SMALL = 256; // the cover's copy: this wide (its height in proportion)
const ctx = (c) => c.getContext('2d', { willReadFrequently: true });

// The cover as a small bitmap, decoded and scaled in the background where the browser can (from the
// file's bytes); else from the picture itself. Null when there is no picture.
async function shrink(url) {
  const opts = { resizeWidth: SMALL, resizeQuality: 'medium' };
  try {
    const r = await fetch(url);
    if (r.ok) return await createImageBitmap(await r.blob(), opts);
  } catch {}
  const im = new Image();
  im.src = url;
  try { await im.decode(); } catch { return null; }
  try { return await createImageBitmap(im, opts); } catch { return im; }
}
// a picture's own size (a bitmap's or an image's)
const dims = (img) => [img.naturalWidth || img.width || 0, img.naturalHeight || img.height || 0];
const FLOOR = 0.22; // the light left where the scrim is darkest, behind the controls
// the light on the panes (and the wash under the window) is kept deep, so their text keeps its contrast
// over any cover: saturation up, luminance (0-255) capped
const PANE_SAT = 1.4, PANE_MAXY = 96, WASH_MAXY = 84;

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
// how much light there is at y. Metal has no scrim: the cover starts at the band's edge and fades into the
// plate behind the title and the controls (its mask, metal.css .aa-track --mv), and the light does the same.
function light(y, g) {
  if (!g.band) return FLOOR + (1 - FLOOR) * (1 - scrim(y, g));
  if (y < g.band) return 0;
  return lerp(climb([[g.h - g.bb - 150, 1], [g.h - g.bb - 50, 0.55], [g.h - g.bb + 30, 0.16], [g.h, 0.05]]), y);
}

// the cover's visible edge on one side, averaged down to one column of H colours (a little brighter)
function column(img, side, g) {
  const [iw, ih] = dims(img);
  if (!iw || !ih || !g.w || !g.h) return null;
  // the cover fills its box (background-size: cover, background-position: 50% 38%, app.css .aa-img)
  const s = Math.max(g.w / iw, g.h / ih);
  const ox = (g.w - iw * s) / 2, oy = (g.h - ih * s) * 0.38;
  const band = Math.min(iw, Math.max(1, (g.f * 1.5) / s));
  const sx = side === 'l' ? -ox / s : (g.w - ox) / s - band;
  const col = document.createElement('canvas');
  col.width = 1; col.height = H;
  const cg = ctx(col);
  cg.imageSmoothingQuality = 'high';
  cg.filter = 'saturate(1.35) brightness(1.15)';
  cg.drawImage(img, Math.max(0, sx), Math.max(0, -oy / s), band, Math.min(ih, g.h / s), 0, 0, 1, H);
  soften(cg);
  return col;
}

// Stretched down a tall pane, each of the column's H colours is a band ~20 px high, and a cover with stripes
// or hard lines at its edge turns into hard stripes across the pane: run the colours together (two passes
// of a 5-row average, the ends held), so the light changes gradually down the window.
function soften(cg) {
  let d;
  try { d = cg.getImageData(0, 0, 1, H); } catch { return; }
  const p = d.data;
  for (let pass = 0; pass < 2; pass++) {
    const src = p.slice();
    for (let y = 0; y < H; y++) {
      for (let j = 0; j < 4; j++) {
        let sum = 0;
        for (let k = -2; k <= 2; k++) sum += src[Math.min(H - 1, Math.max(0, y + k)) * 4 + j];
        p[y * 4 + j] = sum / 5;
      }
    }
  }
  cg.putImageData(d, 0, 0);
}

// Light that falls on a pane has to stay colour, never a pale wash over its text: every pixel keeps its
// hue, gains saturation (sat), and is brought down to a luminance of at most maxY (0-255). A pale or white
// cover then tints the panes instead of fogging them.
function tone(x, w, h, sat, maxY) {
  let d;
  try { d = x.getImageData(0, 0, w, h); } catch { return; }
  const p = d.data;
  for (let i = 0; i < p.length; i += 4) {
    const y = 0.2126 * p[i] + 0.7152 * p[i + 1] + 0.0722 * p[i + 2];
    const k = y > maxY ? maxY / y : 1;
    for (let j = 0; j < 3; j++) p[i + j] = Math.max(0, Math.min(255, (y + (p[i + j] - y) * sat) * k));
  }
  x.putImageData(d, 0, 0);
}

// paint one piece: the column stretched across it, flowing away from the cover across the pane (panels
// only), and following the scrim down the window
function paint(c, col, side, kind, g) {
  const w = kind === 'panel' ? W : 2;
  c.width = w; c.height = H;
  const x = ctx(c);
  x.drawImage(col, 0, 0, 1, H, 0, 0, w, H);
  if (kind === 'panel') tone(x, w, H, PANE_SAT, PANE_MAXY);
  x.globalCompositeOperation = 'destination-in';
  if (kind === 'panel') {
    // full strength where the pane meets the cover, still there at its far side
    const fromCover = side === 'l' ? [w, 0] : [0, w];
    const fade = x.createLinearGradient(fromCover[0], 0, fromCover[1], 0);
    for (const [p, a] of [[0, 1], [0.18, 0.84], [0.42, 0.62], [0.72, 0.42], [1, 0.28]]) fade.addColorStop(p, `rgba(0,0,0,${a})`);
    x.fillStyle = fade;
    x.fillRect(0, 0, w, H);
  }
  // down the window: the panes take the cover's darkening only half way, so the light runs their whole height
  const down = x.createLinearGradient(0, 0, 0, H);
  for (let i = 0; i <= 24; i++) {
    const t = i / 24, v = light(t * g.h, g);
    down.addColorStop(t, `rgba(0,0,0,${(kind === 'panel' ? 0.45 + 0.55 * v : v).toFixed(3)})`);
  }
  x.fillStyle = down;
  x.fillRect(0, 0, w, H);
}

// the whole cover, blurred into a soft wash: the ground of the whole window in the wide layout (under the
// title bar, the panes and the cover's own column), so the cover's colours run through everything. Tiny
// and blurred once per song; the browser stretches it (no blur filter on screen). Drawn on a canvas with
// room round it, then cut to size, so the blur doesn't darken its edges.
function bleed(c, img) {
  const w = 72, h = 42, pad = 12;
  const [iw, ih] = dims(img);
  if (!iw || !ih) return false;
  const big = document.createElement('canvas');
  big.width = w + 2 * pad; big.height = h + 2 * pad;
  const b = ctx(big);
  const s = Math.max(big.width / iw, big.height / ih);
  b.filter = 'blur(4px)';
  b.drawImage(img, (big.width - iw * s) / 2, (big.height - ih * s) * 0.38, iw * s, ih * s);
  c.width = w; c.height = h;
  const x = ctx(c);
  x.drawImage(big, pad, pad, w, h, 0, 0, w, h);
  tone(x, w, h, PANE_SAT, WASH_MAXY);
  return true;
}

export class Ambient {
  // panels: [[element, 'l' | 'r']]: 'l' sits left of the cover, 'r' right of it. wrap and track: the
  // cover's box (#aaWrap) and the cover itself (#aaTrack), whose edges get a piece each, under the cover.
  // stage: the window's ground (wide layout), which gets the whole cover blurred (bleed)
  constructor(panels, wrap, track, stage) {
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
    if (stage) this.pieces.push(piece(stage, 'f', 'full', 'amb-full'));
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
      band: 0, // a band over the plate (no theme has one now)
    };
    g.key = [g.w, g.h, g.f, g.tb, g.bb, g.band].map(Math.round).join();
    return g;
  }

  set(url) {
    url = url || '';
    if (url === this.url) return;
    this.url = url;
    const seq = ++this.seq;
    if (!url) return this._show(seq, null);
    shrink(url).then((im) => this._show(seq, im), () => this._show(seq, null));
  }

  // the layout changed size (PlayerView._bleed): once things settle, the light for the new layout
  // cross-fades over the old one (as on a new song), so it never jumps when a panel opens or closes
  refit() {
    clearTimeout(this._refit);
    this._refit = setTimeout(() => {
      if (!this.img) return;
      // not during a view transition: it would have to be captured again on every frame of it
      if (/vt-(full|pane)/.test(document.documentElement.className)) return this.refit();
      const g = this._geom();
      if (g.key === this.key || !g.w) return;
      this._show(this.seq, this.img);
    }, 150);
  }

  _show(seq, im) {
    if (seq !== this.seq) return;
    this.img = im;
    const g = this._geom();
    this.key = g.key;
    const cols = im ? { l: column(im, 'l', g), r: column(im, 'r', g) } : {};
    const made = [];
    for (const p of this.pieces) {
      for (const old of p.box.querySelectorAll('canvas.show')) {
        old.classList.remove('show');
        setTimeout(() => old.remove(), 1000);
      }
      p.cur = null;
      const c = document.createElement('canvas');
      if (p.kind === 'full') { if (!im || !bleed(c, im)) continue; }
      else {
        const col = cols[p.side];
        if (!col) continue;
        paint(c, col, p.side, p.kind, g);
      }
      p.box.append(c);
      p.cur = c;
      made.push(c);
    }
    // they start from transparent (one style pass for all of them), so they fade in
    if (made.length) getComputedStyle(made[0]).opacity;
    for (const c of made) c.classList.add('show');
  }
}
