// Ambient light (Liquid Glass's Brutal mood, wide windows): the colours along the cover's left and right
// edges glow into the panels beside it (the library on the left, lyrics on the right), fading away from
// the cover. Each glow is a tiny canvas, one column of the edge's colours faded out sideways, which the
// browser stretches across the panel: soft light without a blur filter. Drawn once per song; the old
// glow cross-fades to the new one (brutal.css .amb).
const W = 24, H = 12;

function glow(img, side) {
  const iw = img.naturalWidth, ih = img.naturalHeight;
  if (!iw || !ih) return null;
  // the outer sixth of the cover on that side, averaged down to one column of colours, a little brighter
  const col = document.createElement('canvas');
  col.width = 1; col.height = H;
  const cg = col.getContext('2d');
  cg.imageSmoothingQuality = 'high';
  cg.filter = 'saturate(1.35) brightness(1.15)';
  const sw = Math.max(1, Math.round(iw / 6));
  cg.drawImage(img, side === 'l' ? 0 : iw - sw, 0, sw, ih, 0, 0, 1, H);

  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const g = c.getContext('2d');
  g.drawImage(col, 0, 0, 1, H, 0, 0, W, H);
  g.globalCompositeOperation = 'destination-in';
  // strongest against the cover, gone by the far side of the panel
  const x = g.createLinearGradient(side === 'l' ? W : 0, 0, side === 'l' ? 0 : W, 0);
  x.addColorStop(0, 'rgba(0,0,0,1)');
  x.addColorStop(0.3, 'rgba(0,0,0,0.5)');
  x.addColorStop(0.65, 'rgba(0,0,0,0.12)');
  x.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = x;
  g.fillRect(0, 0, W, H);
  // and softer towards the top and bottom, where the cover fades into the dark too
  const y = g.createLinearGradient(0, 0, 0, H);
  y.addColorStop(0, 'rgba(0,0,0,0.3)');
  y.addColorStop(0.35, 'rgba(0,0,0,1)');
  y.addColorStop(0.7, 'rgba(0,0,0,1)');
  y.addColorStop(1, 'rgba(0,0,0,0.3)');
  g.fillStyle = y;
  g.fillRect(0, 0, W, H);
  return c;
}

export class Ambient {
  // hosts: [[element, 'l' | 'r']]: 'l' sits left of the cover, 'r' right of it
  constructor(hosts) {
    this.sides = hosts.filter(([el]) => el).map(([el, side]) => {
      const box = document.createElement('div');
      box.className = `amb amb-${side}`;
      box.setAttribute('aria-hidden', 'true');
      el.prepend(box);
      return { box, side };
    });
    this.url = null;
    this.seq = 0;
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

  _show(seq, im) {
    if (seq !== this.seq) return;
    for (const s of this.sides) {
      for (const old of s.box.querySelectorAll('canvas.show')) {
        old.classList.remove('show');
        setTimeout(() => old.remove(), 1000);
      }
      const c = im && glow(im, s.side);
      if (!c) continue;
      s.box.append(c);
      void c.offsetWidth; // start from transparent, so it fades in
      c.classList.add('show');
    }
  }
}
