// Generates build/icon.png (256x256) with no dependencies: rounded square, orange gradient, play glyph.
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const S = 256, px = Buffer.alloc(S * S * 4);
const crcT = new Int32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c; });
const crc = (b) => { let c = -1; for (const x of b) c = crcT[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; };
const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
const sdRound = (x, y, r) => { const qx = Math.abs(x - S / 2) - (S / 2 - 8 - r), qy = Math.abs(y - S / 2) - (S / 2 - 8 - r); return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r; };
const tri = [[100, 74], [100, 182], [190, 128]];
const sdTri = (x, y) => { let d = 1e9, inside = true; for (let i = 0; i < 3; i++) { const [ax, ay] = tri[i], [bx, by] = tri[(i + 1) % 3]; const ex = bx - ax, ey = by - ay, wx = x - ax, wy = y - ay; const t = Math.max(0, Math.min(1, (wx * ex + wy * ey) / (ex * ex + ey * ey))); d = Math.min(d, Math.hypot(wx - ex * t, wy - ey * t)); if (ex * wy - ey * wx > 0) inside = false; } return inside ? -d : d; };
for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
  const i = (y * S + x) * 4;
  const a = Math.max(0, Math.min(1, 0.5 - sdRound(x + 0.5, y + 0.5, 56)));
  const t = (x + y) / (2 * S);
  let r = 255, g = 190 - 110 * t, b = 40 - 20 * t;
  const glow = Math.max(0, 1 - Math.hypot(x - 80, y - 60) / 200) * 40;
  r = Math.min(255, r); g = Math.min(255, g + glow); b = Math.min(255, b + glow);
  const ta = Math.max(0, Math.min(1, 0.5 - (sdTri(x + 0.5, y + 0.5) - 10)));
  r = r * (1 - ta) + 20 * ta; g = g * (1 - ta) + 18 * ta; b = b * (1 - ta) + 22 * ta;
  px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = a * 255;
}
const raw = Buffer.alloc(S * (S * 4 + 1));
for (let y = 0; y < S; y++) { raw[y * (S * 4 + 1)] = 0; px.copy(raw, y * (S * 4 + 1) + 1, y * S * 4, (y + 1) * S * 4); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(S, 0); ihdr.writeUInt32BE(S, 4); ihdr[8] = 8; ihdr[9] = 6;
fs.writeFileSync(path.join(__dirname, 'icon.png'), Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
console.log('icon.png written');
