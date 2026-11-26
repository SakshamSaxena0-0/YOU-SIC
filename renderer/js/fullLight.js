// Full screen, the whole-cover way (app.css .app.full.fs-fit): the cover fits the screen whole, and the room it leaves (beside it, or
// above and below it) is lit by the cover's own edges, stretched out towards the screen's edges and fading
// into the ground there, so the cover sits in its own light rather than between black bars. The light is a
// tiny canvas the browser stretches over the screen: soft, with no blur filter. A new song's light fades in
// over the old one; when the space changes size (the lyrics opening or closing) the light is painted again
// in place in the same frame, so it always fits the cover and never dips to dark. Whenever full screen is on
// (Fill screen keeps it out of sight, ready to fade in). The cover's shape goes to #aaWrap as --fs-ar (width over height), which
// sizes the cover's box for Whole cover.
const LONG = 64; // the canvas's longer side, in pixels
// how much of the ground lies over the light: a little at the cover's edge, nearly all at the screen's
const FADE = [[0, 0.35], [0.3, 0.62], [0.65, 0.86], [1, 0.98]];

// the cover as a small copy (the light is 64 px across), so painting it again on every frame of a resize is cheap
function small(im) {
  const iw = im.naturalWidth, ih = im.naturalHeight;
  if (!iw || !ih) return null;
  const k = Math.min(1, 256 / Math.max(iw, ih));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(iw * k)); c.height = Math.max(1, Math.round(ih * k));
  const x = c.getContext('2d');
  x.imageSmoothingQuality = 'high';
  x.drawImage(im, 0, 0, c.width, c.height);
  return c;
}

export class FullLight {
  // host: the box the cover fits into (#fsLight, the same box as the cover's track); isOn: full screen?
  constructor(host, isOn) {
    this.host = host;
    this.isOn = isOn;
    this.url = '';
    this.img = null;
    this.seq = 0;
    this.key = '';
    this.cur = null; // the canvas on show
    // before the frame is painted: the light changes with the space, never a frame behind it
    new ResizeObserver(() => this.draw()).observe(host);
  }

  set(url) {
    url = url || '';
    if (url === this.url) return;
    this.url = url;
    // out of full screen the old light would only flash up on the way back in: drop it now
    if (!this.isOn()) { this.host.replaceChildren(); this.key = ''; this.cur = null; }
    const seq = ++this.seq;
    const done = (im) => {
      if (seq !== this.seq) return;
      this.img = im && small(im);
      const ar = im && im.naturalWidth && im.naturalHeight ? im.naturalWidth / im.naturalHeight : 1;
      this.host.parentElement.style.setProperty('--fs-ar', ar.toFixed(4));
      this.draw(true, true);
    };
    if (!url) return done(null);
    const im = new Image();
    im.onload = () => done(im);
    im.onerror = () => done(null);
    im.src = url;
  }

  // force: draw even at the same size; fresh: a new song, whose light fades in over the old one
  draw(force = false, fresh = false) {
    const w = this.host.clientWidth, h = this.host.clientHeight;
    if (!this.isOn() || !w || !h) return;
    const key = `${this.url}|${w}x${h}`;
    if (key === this.key && !force) return;
    this.key = key;
    const im = this.img;
    if (!im) { this._swap(null); return; }
    const cw = w >= h ? LONG : Math.max(2, Math.round((LONG * w) / h));
    const ch = w >= h ? Math.max(2, Math.round((LONG * h) / w)) : LONG;
    const c = document.createElement('canvas');
    c.width = cw; c.height = ch;
    const x = c.getContext('2d');
    x.imageSmoothingQuality = 'high';
    // the cover where it sits on screen (css: background-size: contain, centred)
    const iw = im.width, ih = im.height;
    const s = Math.min(cw / iw, ch / ih);
    const bw = iw * s, bh = ih * s, bx = (cw - bw) / 2, by = (ch - bh) / 2;
    x.drawImage(im, bx, by, bw, bh);
    // its edges, stretched out over the room beside it (only one pair of sides has any). Each band starts a
    // little inside the edge, so a cover's thin frame or scan line doesn't become a bright stripe
    const bandX = Math.max(1, iw * 0.03), bandY = Math.max(1, ih * 0.03);
    const inX = Math.min(iw * 0.015, iw - bandX), inY = Math.min(ih * 0.015, ih - bandY);
    const ground = this._ground();
    const fade = (x0, y0, x1, y1, rx, ry, rw, rh) => {
      const g = x.createLinearGradient(x0, y0, x1, y1);
      for (const [p, a] of FADE) g.addColorStop(p, `rgba(${ground},${a})`);
      x.fillStyle = g;
      x.fillRect(rx, ry, rw, rh);
    };
    if (bx > 0.01) {
      const rw = cw - bx - bw;
      x.drawImage(im, inX, 0, bandX, ih, 0, by, bx + 0.5, bh);
      x.drawImage(im, iw - inX - bandX, 0, bandX, ih, bx + bw - 0.5, by, rw + 0.5, bh);
      fade(bx, 0, 0, 0, 0, 0, bx, ch);
      fade(bx + bw, 0, cw, 0, bx + bw, 0, rw, ch);
    }
    if (by > 0.01) {
      const rh = ch - by - bh;
      x.drawImage(im, 0, inY, iw, bandY, bx, 0, bw, by + 0.5);
      x.drawImage(im, 0, ih - inY - bandY, iw, bandY, bx, by + bh - 0.5, bw, rh + 0.5);
      fade(0, by, 0, 0, 0, 0, cw, by);
      fade(0, by + bh, 0, ch, 0, by + bh, cw, rh);
    }
    // stretched out, each row (or column) of the edge became a hard stripe: soften the tiny canvas once,
    // over the ground colour so its own borders don't darken
    // the same size: painted again in place (no fade, nothing to see but the light following the cover)
    const inPlace = !fresh && this.cur?.isConnected;
    const out = inPlace ? this.cur : document.createElement('canvas');
    out.width = cw; out.height = ch;
    const o = out.getContext('2d');
    o.fillStyle = `rgb(${ground})`;
    o.fillRect(0, 0, cw, ch);
    o.filter = 'blur(1.6px)';
    o.drawImage(c, 0, 0);
    if (!inPlace) this._swap(out);
  }

  // a new light fades in on top of the one on show, which stays whole underneath until it has (so the
  // room beside the cover never dims on the way), then goes
  _swap(next) {
    const prev = this.cur;
    this.cur = next;
    if (next) {
      this.host.append(next);
      void next.offsetWidth; // start from transparent, so it fades in
      next.classList.add('show');
    }
    if (prev) {
      if (!next) prev.classList.remove('show');
      setTimeout(() => prev.remove(), 1000);
    }
  }

  // the player's ground colour ("r,g,b"), which the light fades into
  _ground() {
    const bg = document.querySelector('#bg');
    const m = bg && getComputedStyle(bg).backgroundColor.match(/[\d.]+/g);
    return m && m.length >= 3 ? m.slice(0, 3).map((v) => Math.round(+v)).join(',') : '7,7,7';
  }
}
