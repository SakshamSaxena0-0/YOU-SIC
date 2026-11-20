// Full screen (app.css .app.full): the cover fits the screen whole, and the room it leaves (beside it, or
// above and below it) is lit by the cover's own edges, stretched out towards the screen's edges and fading
// into the ground there, so the cover sits in its own light rather than between black bars. The light is a
// tiny canvas the browser stretches over the screen: soft, with no blur filter. It is drawn once per song
// and again when the space changes size (after it settles), and only while full screen is on.
const LONG = 64; // the canvas's longer side, in pixels
// how much of the ground lies over the light: a little at the cover's edge, nearly all at the screen's
const FADE = [[0, 0.35], [0.3, 0.62], [0.65, 0.86], [1, 0.98]];

export class FullLight {
  // host: the box the cover fits into (#fsLight, the same box as the cover's track); isOn: full screen?
  constructor(host, isOn) {
    this.host = host;
    this.isOn = isOn;
    this.url = '';
    this.img = null;
    this.seq = 0;
    this.key = '';
    new ResizeObserver(() => { clearTimeout(this._t); this._t = setTimeout(() => this.draw(), 150); }).observe(host);
  }

  set(url) {
    url = url || '';
    if (url === this.url) return;
    this.url = url;
    // out of full screen the old light would only flash up on the way back in: drop it now
    if (!this.isOn()) { this.host.replaceChildren(); this.key = ''; }
    const seq = ++this.seq;
    const done = (im) => { if (seq !== this.seq) return; this.img = im; this.draw(true); };
    if (!url) return done(null);
    const im = new Image();
    im.onload = () => done(im);
    im.onerror = () => done(null);
    im.src = url;
  }

  draw(force = false) {
    const w = this.host.clientWidth, h = this.host.clientHeight;
    if (!this.isOn() || !w || !h) return;
    const key = `${this.url}|${w}x${h}`;
    if (key === this.key && !force) return;
    this.key = key;
    for (const old of this.host.querySelectorAll('canvas.show')) {
      old.classList.remove('show');
      setTimeout(() => old.remove(), 1000);
    }
    const im = this.img;
    if (!im || !im.naturalWidth || !im.naturalHeight) return;
    const cw = w >= h ? LONG : Math.max(2, Math.round((LONG * w) / h));
    const ch = w >= h ? Math.max(2, Math.round((LONG * h) / w)) : LONG;
    const c = document.createElement('canvas');
    c.width = cw; c.height = ch;
    const x = c.getContext('2d');
    x.imageSmoothingQuality = 'high';
    // the cover where it sits on screen (css: background-size: contain, centred)
    const iw = im.naturalWidth, ih = im.naturalHeight;
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
    this.host.append(c);
    void c.offsetWidth; // start from transparent, so it fades in
    c.classList.add('show');
  }

  // the player's ground colour ("r,g,b"), which the light fades into
  _ground() {
    const bg = document.querySelector('#bg');
    const m = bg && getComputedStyle(bg).backgroundColor.match(/[\d.]+/g);
    return m && m.length >= 3 ? m.slice(0, 3).map((v) => Math.round(+v)).join(',') : '7,7,7';
  }
}
