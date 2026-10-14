// Album-art color extraction (vibrant swatch), similar in spirit to Android's Palette.
const cache = new Map();

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  let h = 0, s = 0;
  const l = (max + min) / 2;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h /= 6;
  }
  return [h, s, l];
}

function hslToRgb(h, s, l) {
  if (!s) return [l * 255, l * 255, l * 255];
  const hue = (p, q, t) => {
    if (t < 0) t += 1; if (t > 1) t -= 1;
    if (t < 1 / 6) return p + (q - p) * 6 * t;
    if (t < 1 / 2) return q;
    if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
    return p;
  };
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  return [hue(p, q, h + 1 / 3) * 255, hue(p, q, h) * 255, hue(p, q, h - 1 / 3) * 255];
}

export async function extractColors(url) {
  if (!url) return null;
  if (cache.has(url)) return cache.get(url);
  const img = new Image();
  img.crossOrigin = 'anonymous';
  img.src = url;
  try { await img.decode(); } catch { return null; }
  const c = document.createElement('canvas');
  c.width = c.height = 40;
  const g = c.getContext('2d', { willReadFrequently: true });
  g.drawImage(img, 0, 0, 40, 40);
  const px = g.getImageData(0, 0, 40, 40).data;
  const buckets = new Map();
  let avg = [0, 0, 0];
  for (let i = 0; i < px.length; i += 4) {
    const r = px[i], gg = px[i + 1], b = px[i + 2];
    avg[0] += r; avg[1] += gg; avg[2] += b;
    const [h, s, l] = rgbToHsl(r, gg, b);
    if (s < 0.28 || l < 0.18 || l > 0.85) continue;
    const key = Math.round(h * 24) % 24;
    const e = buckets.get(key) || { w: 0, h: 0, s: 0, l: 0, n: 0 };
    const w = s * s * (1 - Math.abs(l - 0.55));
    e.w += w; e.h += h * w; e.s += s * w; e.l += l * w; e.n++;
    buckets.set(key, e);
  }
  const n = px.length / 4;
  avg = avg.map((v) => v / n);
  let best = null;
  for (const e of buckets.values()) if (!best || e.w > best.w) best = e;
  let accent;
  if (best && best.n > 6) {
    const h = best.h / best.w;
    const s = Math.min(1, (best.s / best.w) * 1.1 + 0.1);
    const l = Math.min(0.68, Math.max(0.55, best.l / best.w));
    accent = hslToRgb(h, s, l);
  } else {
    accent = null; // monochrome cover: keep the theme accent
  }
  const [ah, as, al] = rgbToHsl(...avg);
  const dark = hslToRgb(ah, Math.min(as, 0.5), Math.min(al, 0.16));
  const res = { accent: accent && accent.map(Math.round), dark: dark.map(Math.round) };
  cache.set(url, res);
  return res;
}
