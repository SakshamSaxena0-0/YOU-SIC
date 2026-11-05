// Metal theme: the cover etched into brushed aluminium (js/etch.js asks; this worker does the work, so
// the window never stalls). The cover becomes line art cut into the metal:
//  - contour lines where the picture has edges: fine ones from a difference of two small blurs (taken
//    on its dark side, so each edge gives one clean line), and the outlines of the big shapes where a
//    difference of two large blurs changes sign (thin, right on the edge),
//  - and where the picture is dark, horizontal engraving lines that thicken with the darkness (like an
//    engraving on a banknote; they run the same way as the brushing),
// each cut drawn as a groove: dark, with a thin bright lip along its lower edge where it catches the
// light. Everything else is transparent, so the metal under the cover shows through untouched.
// One pass per cover, made once and cached by js/etch.js. Pixel work only: no filters, no canvas
// effects.

// ---- blur: three box blurs approximate a gaussian, each O(n) whatever the radius ----
function boxSizes(sigma, n = 3) {
  const ideal = Math.sqrt((12 * sigma * sigma) / n + 1);
  let wl = Math.floor(ideal); if (wl % 2 === 0) wl--;
  const wu = wl + 2;
  const m = Math.round((12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4));
  return Array.from({ length: n }, (_, i) => (i < m ? wl : wu));
}
function boxH(src, dst, w, h, r) {
  const k = 1 / (r + r + 1);
  for (let y = 0; y < h; y++) {
    const o = y * w;
    const first = src[o], last = src[o + w - 1];
    let acc = (r + 1) * first;
    for (let j = 0; j < r; j++) acc += src[o + Math.min(j, w - 1)];
    for (let x = 0; x < w; x++) {
      acc += src[o + Math.min(x + r, w - 1)] - (x - r - 1 >= 0 ? src[o + x - r - 1] : first);
      dst[o + x] = acc * k;
    }
    void last;
  }
}
function boxV(src, dst, w, h, r) {
  const k = 1 / (r + r + 1);
  for (let x = 0; x < w; x++) {
    const first = src[x];
    let acc = (r + 1) * first;
    for (let j = 0; j < r; j++) acc += src[Math.min(j, h - 1) * w + x];
    for (let y = 0; y < h; y++) {
      acc += src[Math.min(y + r, h - 1) * w + x] - (y - r - 1 >= 0 ? src[(y - r - 1) * w + x] : first);
      dst[y * w + x] = acc * k;
    }
  }
}
function blur(src, w, h, sigma) {
  const a = Float32Array.from(src), b = new Float32Array(src.length);
  for (const s of boxSizes(sigma)) {
    const r = (s - 1) >> 1;
    if (r < 1) continue;
    boxH(a, b, w, h, r); boxV(b, a, w, h, r);
  }
  return a;
}
const smooth = (e0, e1, x) => { const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };

function etch(rgba, w, h) {
  const n = w * h, s = w / 1000; // sizes below are for a 1000px cover
  // luminance, then stretched so the darkest and brightest 2% reach the ends (dull covers still etch)
  let L = new Float32Array(n);
  const hist = new Uint32Array(256);
  for (let i = 0; i < n; i++) {
    const v = 0.2126 * rgba[i * 4] + 0.7152 * rgba[i * 4 + 1] + 0.0722 * rgba[i * 4 + 2];
    L[i] = v / 255; hist[Math.min(255, v | 0)]++;
  }
  let lo = 0, hi = 255, acc = 0;
  for (let i = 0; i < 256; i++) { acc += hist[i]; if (acc > n * 0.02) { lo = i; break; } }
  acc = 0;
  for (let i = 255; i >= 0; i--) { acc += hist[i]; if (acc > n * 0.02) { hi = i; break; } }
  const span = Math.max(24, hi - lo) / 255, base = lo / 255;
  for (let i = 0; i < n; i++) L[i] = Math.min(1, Math.max(0, (L[i] - base) / span));
  // JPEG noise, film grain and fine texture out first, so only real shapes make lines: the grainier
  // the cover, the more it is smoothed
  const fine = blur(L, w, h, 1 * s);
  let noise = 0;
  for (let i = 0; i < n; i += 7) noise += Math.abs(L[i] - fine[i]);
  noise /= Math.ceil(n / 7);
  L = blur(L, w, h, (1.1 + 1.6 * smooth(0.01, 0.035, noise)) * s);
  const g1 = blur(L, w, h, 1.8 * s), g2 = blur(L, w, h, 1.8 * 1.7 * s);
  // and a coarse pair for the big shapes (silhouettes, a face against the background)
  const c1 = blur(L, w, h, 4.5 * s), c2 = blur(L, w, h, 4.5 * 1.7 * s);
  const tone = blur(L, w, h, 5 * s);

  // every cover gets about the same amount of line: the contour threshold follows how strong this
  // cover's edges are (a soft, misty photo still draws its shapes; a sharp graphic doesn't turn black),
  // and only the darkest part of its tones is engraved
  const BINS = 2048, TOP = 0.25, ZTOP = 0.04;
  const dh = new Uint32Array(BINS), zh = new Uint32Array(BINS), th = new Uint32Array(256);
  // a big shape's edge is where the coarse difference changes sign (one thin line right on the edge);
  // how steeply it changes there says how strong the edge is
  const zc = new Float32Array(n);
  let pos = 0, zpos = 0;
  for (let y = 0; y < h - 1; y++) {
    for (let x = 0; x < w - 1; x++) {
      const i = y * w + x;
      const a = c2[i] - c1[i], b = c2[i + 1] - c1[i + 1], c = c2[i + w] - c1[i + w];
      let sl = 0;
      if (a > 0 !== b > 0) sl = Math.abs(a - b);
      if (a > 0 !== c > 0) sl = Math.max(sl, Math.abs(a - c));
      zc[i] = sl;
      if (sl > 0) { zh[Math.min(BINS - 1, ((sl / ZTOP) * BINS) | 0)]++; zpos++; }
    }
  }
  for (let i = 0; i < n; i += 3) {
    const d = g2[i] - g1[i];
    if (d > 0.002) { dh[Math.min(BINS - 1, ((d / TOP) * BINS) | 0)]++; pos++; }
    th[Math.min(255, (tone[i] * 255) | 0)]++;
  }
  const pct = (hst, total, q, scale) => { let a = 0; for (let b = 0; b < hst.length; b++) { a += hst[b]; if (a >= total * q) return ((b + 0.5) / hst.length) * scale; } return scale; };
  const q = Math.min(0.06, Math.max(0.018, pct(dh, pos, 0.8, TOP)));
  const t0 = q * 0.7, t1 = q * 1.9;
  const zt = Math.max(0.0008, pct(zh, zpos, 0.7, ZTOP));
  const shade = Math.min(0.34, Math.max(0.1, pct(th, Math.ceil(n / 3), 0.22, 1)));

  // the cut: contour lines, and engraving lines across the dark parts
  const cut = new Float32Array(n), hat = new Float32Array(n);
  const P = Math.max(3, 5.2 * s); // engraving line spacing
  for (let y = 0; y < h; y++) {
    const fy = (y % P) / P, dy = Math.abs(fy - 0.5) * P; // distance to the nearest engraving line
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      // contours: the dark side of a fine edge, and the line along a big one
      const line = Math.max(smooth(t0, t1, g2[i] - g1[i]), smooth(zt, zt * 2, zc[i]));
      // engraving: the line's half-width grows with the darkness of the surrounding tone
      const dark = smooth(shade + 0.06, shade * 0.25, tone[i]);
      const half = dark * P * 0.36;
      const hatch = dark > 0.02 ? smooth(half + 0.7, half - 0.7, dy) * Math.min(1, dark * 2) : 0;
      cut[i] = line; hat[i] = hatch * 0.55;
    }
  }
  // the contour lines a little bolder and smoother, like a cut made with a graver, then the engraving
  const soft = blur(cut, w, h, 0.9 * s);
  for (let i = 0; i < n; i++) cut[i] = Math.max(cut[i], smooth(0.16, 0.42, soft[i]), hat[i]);
  // speckle: lone cut pixels with few neighbours fade out (below)
  const out = new Uint8ClampedArray(n * 4);
  // groove colour: a dark, slightly warm graphite; its lip: light
  const DR = 30, DG = 31, DB = 33;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      let m = cut[i];
      if (m > 0 && x > 0 && x < w - 1 && y > 0 && y < h - 1) {
        const nb = cut[i - 1] + cut[i + 1] + cut[i - w] + cut[i + w];
        if (nb < 0.6) m *= nb / 0.6;
      }
      const above = y > 0 ? cut[i - w] : 0;
      const lip = Math.max(0, above - m) * (1 - m);
      const ad = 0.86 * m, ah = 0.5 * lip * (1 - ad);
      const a = ad + ah;
      const o = i * 4;
      if (a <= 0.004) continue;
      out[o] = (DR * ad + 255 * ah) / a;
      out[o + 1] = (DG * ad + 255 * ah) / a;
      out[o + 2] = (DB * ad + 252 * ah) / a;
      out[o + 3] = a * 255;
    }
  }
  return out;
}

self.onmessage = async (e) => {
  const { id, url, size } = e.data;
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error('cover ' + res.status);
    const src = await createImageBitmap(await res.blob());
    const k = size / Math.max(src.width, src.height);
    const w = Math.max(16, Math.round(src.width * k)), h = Math.max(16, Math.round(src.height * k));
    const c = new OffscreenCanvas(w, h);
    const g = c.getContext('2d', { willReadFrequently: true });
    g.imageSmoothingQuality = 'high';
    g.drawImage(src, 0, 0, w, h);
    src.close();
    const px = etch(g.getImageData(0, 0, w, h).data, w, h);
    g.putImageData(new ImageData(px, w, h), 0, 0);
    const blob = await c.convertToBlob({ type: 'image/png' });
    self.postMessage({ id, blob });
  } catch (err) {
    self.postMessage({ id, error: String(err && err.message || err) });
  }
};
