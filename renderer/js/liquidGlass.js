// Liquid glass: real edge refraction for buttons and bars.
// Each element gets an SVG displacement filter whose map is generated for its exact size and corner
// radius, so the backdrop bends inward near the rim like a thick glass lens (used via backdrop-filter).
const NS = 'http://www.w3.org/2000/svg';
const filters = new Map();
let defs = null;
let seq = 0;

function ensureDefs() {
  if (defs) return defs;
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('width', '0');
  svg.setAttribute('height', '0');
  svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden';
  defs = document.createElementNS(NS, 'defs');
  svg.appendChild(defs);
  document.body.appendChild(svg);
  return defs;
}

// Signed distance to a rounded rectangle centered in w x h (negative inside).
function sdf(x, y, w, h, r) {
  const qx = Math.abs(x - w / 2) - (w / 2 - r);
  const qy = Math.abs(y - h / 2) - (h / 2 - r);
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
}

// Displacement map: R/G encode a vector pointing inward, strongest at the rim and fading over the bezel.
function makeMap(w, h, r, bezel) {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  const g = c.getContext('2d');
  const img = g.createImageData(w, h);
  const d = img.data;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const px = x + 0.5, py = y + 0.5;
      const s = sdf(px, py, w, h, r);
      let R = 128, G = 128;
      const inside = -s;
      if (inside > 0 && inside < bezel) {
        const nx = sdf(px + 0.5, py, w, h, r) - sdf(px - 0.5, py, w, h, r);
        const ny = sdf(px, py + 0.5, w, h, r) - sdf(px, py - 0.5, w, h, r);
        const len = Math.hypot(nx, ny) || 1;
        const t = 1 - inside / bezel;
        const k = t * t * (3 - 2 * t); // smoothstep: lens-like falloff
        R = 128 - (nx / len) * k * 127;
        G = 128 - (ny / len) * k * 127;
      }
      const i = (y * w + x) * 4;
      d[i] = R; d[i + 1] = G; d[i + 2] = 128; d[i + 3] = 255;
    }
  }
  g.putImageData(img, 0, 0);
  return c.toDataURL();
}

function filterFor(w, h, r, opts) {
  const bezel = Math.max(4, Math.min(opts.bezel ?? Math.min(w, h) * 0.28, Math.min(w, h) / 2));
  const scale = Math.round(Math.min(opts.scale ?? Math.min(w, h) * 0.42, 70));
  const key = `${w}x${h}r${r}b${bezel | 0}s${scale}`;
  if (filters.has(key)) return filters.get(key);
  const id = `lg${++seq}`;
  const f = document.createElementNS(NS, 'filter');
  f.setAttribute('id', id);
  f.setAttribute('x', '0'); f.setAttribute('y', '0');
  f.setAttribute('width', String(w)); f.setAttribute('height', String(h));
  f.setAttribute('filterUnits', 'userSpaceOnUse');
  f.setAttribute('primitiveUnits', 'userSpaceOnUse');
  f.setAttribute('color-interpolation-filters', 'sRGB');
  f.innerHTML = `
    <feImage href="${makeMap(w, h, r, bezel)}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="raw"/>
    <feGaussianBlur in="raw" stdDeviation="1.2" result="map"/>
    <feDisplacementMap in="SourceGraphic" in2="map" scale="${scale}" xChannelSelector="R" yChannelSelector="G"/>`;
  ensureDefs().appendChild(f);
  filters.set(key, id);
  return id;
}

const applied = new WeakMap();
const live = new Set();
const ro = new ResizeObserver((entries) => entries.forEach((e) => update(e.target)));

function update(el) {
  // solid themes (cyberpunk, last of us) have no glass: no refraction filter at all (and no GPU cost)
  if (document.documentElement.classList.contains('theme-solid')) { el.style.backdropFilter = ''; return; }
  const w = el.offsetWidth, h = el.offsetHeight;
  if (!w || !h) return;
  const cs = getComputedStyle(el);
  const r = Math.min(parseFloat(cs.borderTopLeftRadius) || 0, Math.min(w, h) / 2);
  let opts = applied.get(el) || {};
  // bubble buttons are solid lenses: the whole face magnifies, not just the rim
  const html = document.documentElement.classList;
  if (el.classList.contains('aq-btn') && html.contains('btn-bubble')) {
    opts = { ...opts, bezel: Math.min(w, h) / 2, scale: Math.min(w, h) * 0.55 };
  } else if (html.contains('btn-ios')) {
    // iOS glass: a wide, strong lens band around the rim and a flat clear centre
    opts = { ...opts, bezel: Math.min(w, h) * 0.32, scale: Math.min(w, h) * 0.6 };
  }
  const id = filterFor(w, h, r, opts);
  const blur = opts.blur ?? 0.8;
  el.style.backdropFilter = `blur(${blur}px) url(#${id}) saturate(1.5) brightness(1.1)`;
}

// Aero fill: buttons get a pool of liquid (two drifting wave layers, rising bubbles) under the icon
// and a glossy cap over it; bars and cards only get the gloss.
const BUTTONS = 'button, .btn, .chip, .fchip';
function fill(el) {
  if (el.querySelector(':scope > .aq-gloss')) return;
  const isBtn = el.matches(BUTTONS) && !el.matches('.tile');
  if (getComputedStyle(el).position === 'static') el.style.position = 'relative';
  el.classList.add('aq-host');
  if (isBtn) {
    el.classList.add('aq-btn');
    const bubbles = el.matches('.ctl-play, .nb-play, .btn:not(.ghost)') ? '<b></b><b></b><b></b><b></b>' : '';
    el.insertAdjacentHTML('afterbegin', `<span class="aq" aria-hidden="true"><span class="aq-pool"><i class="aq-w1"></i><i class="aq-w2"></i></span>${bubbles ? `<span class="aq-bub">${bubbles}</span>` : ''}</span>`);
  }
  el.insertAdjacentHTML('beforeend', '<span class="aq-gloss" aria-hidden="true"></span>');
}

export function liquid(el, opts = {}) {
  fill(el);
  // data-lg="static": glass look only, no refraction (for items over flat backgrounds; saves GPU)
  if (applied.has(el) || el.dataset.lg === 'static') return;
  applied.set(el, opts);
  live.add(el);
  ro.observe(el);
  update(el);
}

// Rebuild every filter (after the button style changes).
export function refreshLiquid() {
  for (const el of live) {
    if (!el.isConnected) { live.delete(el); ro.unobserve(el); continue; }
    update(el);
  }
}

export function liquidAll(root = document) {
  root.querySelectorAll('.lg').forEach((el) => liquid(el, {
    blur: el.dataset.lgBlur ? +el.dataset.lgBlur : undefined,
    scale: el.dataset.lgScale ? +el.dataset.lgScale : undefined,
  }));
}

// Elements added later (library tiles, dialogs) pick up the effect automatically.
export function watchLiquid() {
  new MutationObserver((muts) => {
    for (const m of muts) {
      // a button whose content was replaced (innerHTML/textContent) gets its liquid back
      if (m.target.classList?.contains('lg') && m.removedNodes.length) fill(m.target);
      for (const n of m.addedNodes) {
        if (n.nodeType !== 1) continue;
      if (n.classList.contains('lg')) liquid(n);
        if (n.querySelector) n.querySelectorAll('.lg').forEach((el) => liquid(el));
      }
    }
  }).observe(document.body, { childList: true, subtree: true });
}
