// Generates build/icon.png (512x512) with no dependencies: the YouSic mark in the Liquid Glass Brutal
// look (Settings > Theme: Liquid Glass, Mood: Brutal; css/brutal.css), drawn from that theme's own parts:
// a squarer slab of charcoal glass under concrete grain, with the hard white rim the theme gives its slabs
// (brightest along the top) and a little light refracted just inside it; and in it the theme's primary key,
// play: a dome of solid white glass (white where the light hits, bone, then a shade of grey round its
// edge) in a dark well, with a heavy black play glyph. Rendered from signed distance fields, 4x4 samples
// a pixel. Run: node build/make-icon.js [out.png]
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const S = 512, SS = 4;
const px = Buffer.alloc(S * S * 4);

const clamp = (v, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const mix = (a, b, t) => a + (b - a) * t;
const smooth = (a, b, x) => { const t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); };

// the slab: a rounded square, squarer than an app tile (Brutal's slabs)
const M = 16, R = 64;
const sdSlab = (x, y) => {
  const qx = Math.abs(x - S / 2) - (S / 2 - M - R), qy = Math.abs(y - S / 2) - (S / 2 - M - R);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - R;
};
// the play key: a dome in a dark well with a fine light edge
const C = S / 2, DOME = 148, WELL = 170;
// its glyph: a heavy triangle with rounded corners, a little right of centre (optical centre)
const GR = 16;
const tri = [[214, 178], [214, 334], [344, 256]];
const sdTri = (T, x, y) => {
  let d = 1e9, inside = true;
  for (let i = 0; i < 3; i++) {
    const [ax, ay] = T[i], [bx, by] = T[(i + 1) % 3];
    const ex = bx - ax, ey = by - ay, wx = x - ax, wy = y - ay;
    const t = clamp((wx * ex + wy * ey) / (ex * ex + ey * ey));
    d = Math.min(d, Math.hypot(wx - ex * t, wy - ey * t));
    if (ex * wy - ey * wx > 0) inside = false;
  }
  return inside ? -d : d;
};
// shrink the triangle by GR, then round it back out: rounded corners, same size
const cx0 = (tri[0][0] + tri[1][0] + tri[2][0]) / 3, cy0 = (tri[0][1] + tri[1][1] + tri[2][1]) / 3;
const inr = (() => { // the triangle's inradius, to scale it about its centre
  const a = Math.hypot(tri[1][0] - tri[2][0], tri[1][1] - tri[2][1]), b = Math.hypot(tri[0][0] - tri[2][0], tri[0][1] - tri[2][1]), c = Math.hypot(tri[0][0] - tri[1][0], tri[0][1] - tri[1][1]);
  const s = (a + b + c) / 2; return Math.sqrt(s * (s - a) * (s - b) * (s - c)) / s;
})();
const k = (inr - GR) / inr;
const triS = tri.map(([x, y]) => [cx0 + (x - cx0) * k, cy0 + (y - cy0) * k]);
const sdGlyph = (x, y) => sdTri(triS, x, y) - GR;

// fixed grain (deterministic hash), like the theme's concrete grain
const hash = (x, y) => { let h = (x * 374761393 + y * 668265263) >>> 0; h = ((h ^ (h >>> 13)) * 1274126177) >>> 0; return ((h ^ (h >>> 16)) >>> 0) / 4294967295; };

for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
  let R_ = 0, G_ = 0, B_ = 0, A_ = 0;
  const grain = (hash(x, y) - 0.5) * 0.075;
  for (let sy = 0; sy < SS; sy++) for (let sx = 0; sx < SS; sx++) {
    const X = x + (sx + 0.5) / SS, Y = y + (sy + 0.5) / SS;
    const d = sdSlab(X, Y);
    if (d > 0) continue;
    const v = (Y - M) / (S - 2 * M); // 0 top .. 1 bottom
    // charcoal glass: a little lighter at the top, darker below
    let c = mix(0.15, 0.06, smooth(0, 1, v));
    // light inside the glass from the top left
    c += 0.05 * clamp(1 - Math.hypot(X - 140, Y - 110) / 360);
    // light refracted just inside the edges
    c += 0.09 * Math.exp(d / 16);
    // the hard white rim, brightest along the top
    const rimW = 6.5;
    const rimProfile = v < 0.5 ? mix(0.98, 0.78, smooth(0, 0.5, v)) : mix(0.78, 0.86, smooth(0.5, 1, v));
    c = mix(c, 1, smooth(-rimW, -rimW + 1.4, d) * rimProfile);
    // a fine dark line just inside the rim, so it reads as a cut edge
    c *= 1 - 0.4 * Math.exp(-((d + rimW + 1.6) ** 2) / 1.4);
    c += grain;
    // the play key's well: dark, with a fine light edge round it
    const rr = Math.hypot(X - C, Y - C);
    if (rr < WELL + 2) {
      const well = smooth(WELL + 1.5, WELL - 1, rr);
      const edge = Math.exp(-((rr - WELL) ** 2) / 3) * 0.35;
      c = mix(c, 0.025 + grain * 0.5, well) + edge;
    }
    let r = c, g = c, b = c;
    // the dome: solid white glass, lit from the top left, falling to bone and a shade of grey at its edge
    if (rr < DOME + 1) {
      const a = smooth(DOME + 0.8, DOME - 0.8, rr);
      const t = Math.hypot(X - (C - 52), Y - (C - 62)) / (DOME * 1.62);
      let w = t < 0.5 ? mix(1, 0.93, smooth(0, 0.5, t)) : mix(0.93, 0.7, smooth(0.5, 1, t));
      w -= 0.12 * smooth(DOME - 26, DOME, rr) * smooth(-0.2, 0.6, (Y - C) / DOME + 0.3); // its rim turns away, darker below
      w += 0.05 * Math.exp(-((rr - (DOME - 3)) ** 2) / 4) * (1 - smooth(-0.2, 0.4, (Y - C) / DOME)); // a fine lit lip on top
      w += grain * 0.35;
      // the glyph, heavy and black
      const gd = sdGlyph(X, Y);
      const ga = smooth(0.7, -0.7, gd);
      const gc = mix(0.05, 0.11, smooth(-30, 0, gd) * smooth(0.3, -0.4, (Y - C) / 80)) ; // a hint of the dome's light on its top edge
      w = mix(w, gc, ga);
      r = mix(r, w, a); g = mix(g, w, a); b = mix(b, w * 0.99, a);
    }
    R_ += clamp(r); G_ += clamp(g); B_ += clamp(b); A_ += 1;
  }
  const n = SS * SS, i = (y * S + x) * 4;
  if (!A_) { px[i + 3] = 0; continue; }
  px[i] = Math.round((R_ / A_) * 255); px[i + 1] = Math.round((G_ / A_) * 255); px[i + 2] = Math.round((B_ / A_) * 255);
  px[i + 3] = Math.round((A_ / n) * 255);
}

// PNG
const crcT = new Int32Array(256).map((_, n) => { let c = n; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc = (buf) => { let c = -1; for (const v of buf) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
const raw = Buffer.alloc(S * (S * 4 + 1));
for (let y = 0; y < S; y++) { raw[y * (S * 4 + 1)] = 0; px.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4); ihdr[8] = 8; ihdr[9] = 6;
const out = process.argv[2] || path.join(__dirname, 'icon.png');
fs.writeFileSync(out, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
console.log('written', out);
