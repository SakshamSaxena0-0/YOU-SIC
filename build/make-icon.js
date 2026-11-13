// Generates the YouSic logo with no dependencies: build/icon.png (512x512, the window icon) and
// build/icon.ico (the exe, the shortcut and the taskbar). The mark is the Liquid Glass Brutal look
// (Settings > Theme: Liquid Glass, Mood: Brutal; css/brutal.css) drawn as pixel art (the user's choice:
// "digital pixelated", and big): a slab of charcoal glass filling the icon, with the hard white rim the
// theme gives its slabs (brightest along the top) and an ordered-dither grain; and filling the slab, the
// theme's primary key, play: a dome of solid white glass (white where the light hits, bone, then grey round
// its lower edge) in a dark well, with a big black play glyph.
// Each size is drawn on its own pixel grid (32, 24, 20 and 16 cells) with hard square pixels; the larger
// sizes are those grids scaled up by whole numbers, so every pixel stays square and sharp.
// Run: node build/make-icon.js [preview.png]
const fs = require('fs'), zlib = require('zlib'), path = require('path');

const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const mix = (a, b, t) => a + (b - a) * t;

// ---- the scene, in units of the icon's width (0..1) ----
const SLAB_R = 0.17;                 // the slab's corner radius (squarer than an app tile: Brutal's slabs)
const DOME = 0.395, CX = 0.5, CY = 0.5; // the play key's dome
const TRI = [[0.405, 0.305], [0.405, 0.695], [0.725, 0.5]]; // the play glyph, a little right of centre
const sdSlab = (x, y) => {
  const h = 0.5 - SLAB_R;
  const qx = Math.abs(x - 0.5) - h, qy = Math.abs(y - 0.5) - h;
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - SLAB_R;
};
const inTri = (x, y) => {
  for (let i = 0; i < 3; i++) {
    const [ax, ay] = TRI[i], [bx, by] = TRI[(i + 1) % 3];
    if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) > 0) return false;
  }
  return true;
};

// ---- the palette: Brutal's black, coal, charcoals, greys and bone ----
const GREYS = [0.0, 0.03, 0.07, 0.11, 0.16, 0.22, 0.55, 0.7, 0.8, 0.9, 1.0];
const BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];
// a grey made of the palette's greys: the two nearest, mixed by ordered dither (the digital grain)
const dither = (v, i, j) => {
  v = clamp(v);
  let k = 0; while (k < GREYS.length - 2 && GREYS[k + 1] < v) k++;
  const lo = GREYS[k], hi = GREYS[k + 1], f = (v - lo) / (hi - lo);
  return f > (BAYER[j & 3][i & 3] + 0.5) / 16 ? hi : lo;
};

// one cell of an N-cell grid: [grey, alpha]
const cell = (i, j, N) => {
  const x = (i + 0.5) / N, y = (j + 0.5) / N, one = 1 / N;
  const d = sdSlab(x, y);
  if (d > 0) return [0, 0];
  // the hard white rim: the outermost cells; brightest along the top
  if (d > -one) return [y < 0.35 ? 1 : y < 0.75 ? 0.8 : 0.9, 1];
  const r = Math.hypot(x - CX, y - CY);
  // the play key's dome: white where the light hits (top left), bone, grey round its lower edge
  if (r < DOME) {
    if (inTri(x, y)) return [0, 1]; // the glyph: solid black
    const t = Math.hypot(x - (CX - 0.13), y - (CY - 0.15)) / (DOME * 1.75);
    const lower = (x - CX) + (y - CY) > 0 && r > DOME - 1.6 * one; // the edge that turns away from the light
    if (lower) return [0.7, 1];
    return [t < 0.28 ? 1 : t < 0.62 ? 0.9 : 0.8, 1];
  }
  // its well: a dark ring round the dome
  if (r < DOME + one * (N >= 24 ? 1.15 : 0.9)) return [0.03, 1];
  // charcoal glass: lighter at the top, darker below, with the dithered grain
  const v = mix(0.2, 0.08, clamp((y - 0.1) / 0.8)) + 0.04 * clamp(1 - Math.hypot(x - 0.25, y - 0.2) / 0.7);
  return [dither(v, i, j), 1];
};

// an N-cell grid scaled up f times: an RGBA buffer of N*f pixels square
const render = (N, f) => {
  const n = N * f, buf = Buffer.alloc(n * n * 4);
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const [g, a] = cell(i, j, N);
    const r = Math.round(g * 255), b = Math.round(g * (g > 0.5 ? 0.985 : 1) * 255); // bone is a little warm
    for (let y = j * f; y < (j + 1) * f; y++) for (let x = i * f; x < (i + 1) * f; x++) {
      const o = (y * n + x) * 4; buf[o] = r; buf[o + 1] = r; buf[o + 2] = b; buf[o + 3] = a * 255;
    }
  }
  return buf;
};

// ---- PNG and ICO ----
const crcT = new Int32Array(256).map((_, n) => { let c = n; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc = (buf) => { let c = -1; for (const v of buf) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
const png = (buf, n) => {
  const raw = Buffer.alloc(n * (n * 4 + 1));
  for (let y = 0; y < n; y++) { raw[y * (n * 4 + 1)] = 0; buf.copy(raw, y * (n * 4 + 1) + 1, y * n * 4, (y + 1) * n * 4); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(n, 0); ihdr.writeUInt32BE(n, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
};

// each size from the grid that divides it: [size, cells]
const SIZES = [[256, 32], [128, 32], [64, 32], [48, 24], [40, 20], [32, 32], [24, 24], [20, 20], [16, 16]];
const preview = process.argv[2];
fs.writeFileSync(preview || path.join(__dirname, 'icon.png'), png(render(32, 16), 512));
console.log('written', preview || path.join(__dirname, 'icon.png'));
if (!preview) {
  const imgs = SIZES.map(([n, N]) => png(render(N, n / N), n));
  const head = Buffer.alloc(6); head.writeUInt16LE(0, 0); head.writeUInt16LE(1, 2); head.writeUInt16LE(SIZES.length, 4);
  let off = 6 + 16 * SIZES.length;
  const dir = SIZES.map(([n], i) => {
    const e = Buffer.alloc(16);
    e[0] = n >= 256 ? 0 : n; e[1] = n >= 256 ? 0 : n; e.writeUInt16LE(1, 4); e.writeUInt16LE(32, 6);
    e.writeUInt32LE(imgs[i].length, 8); e.writeUInt32LE(off, 12); off += imgs[i].length;
    return e;
  });
  const ico = path.join(__dirname, 'icon.ico');
  fs.writeFileSync(ico, Buffer.concat([head, ...dir, ...imgs]));
  console.log('written', ico);
}
