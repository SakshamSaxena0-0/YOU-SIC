export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
export const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
export const lerp = (a, b, t) => a + (b - a) * t;

export function fmtTime(sec) {
  if (!isFinite(sec) || sec < 0) sec = 0;
  sec = Math.floor(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

export function fmtDuration(sec) {
  sec = Math.round(sec || 0);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  return h ? `${h} h ${m} min` : `${m} min`;
}

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// Where the app's own files, songs and covers are served from: app://local/ on Windows (main.js). The
// Android app sets PA_BASE to its in-app origin before anything runs (android/web/pa-android.js).
export const BASE = window.PA_BASE || 'app://local/';
export const ANDROID = window.pa?.platform === 'android';

export function mediaUrl(path) {
  const b = btoa(String.fromCharCode(...new TextEncoder().encode(path)));
  return BASE + 'media/' + b.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function coverUrl(key, small = false) {
  return key ? `${BASE}cover/${key}${small ? '_s' : ''}.jpg` : '';
}

export const easeOutCubic = (t) => 1 - Math.pow(1 - t, 3);
export const easeInOutCubic = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

// rAF tween; returns a cancel function.
export function tween(from, to, ms, onUpdate, ease = easeOutCubic, onDone) {
  const t0 = performance.now();
  let raf;
  const step = (now) => {
    const t = clamp((now - t0) / ms, 0, 1);
    const e = ease(t);
    onUpdate(Array.isArray(from) ? from.map((f, i) => lerp(f, to[i], e)) : lerp(from, to, e));
    if (t < 1) raf = requestAnimationFrame(step);
    else onDone && onDone();
  };
  raf = requestAnimationFrame(step);
  return () => cancelAnimationFrame(raf);
}

// Critically-damped spring toward a target, used for drag release animations.
export function spring(el, { from, to, stiffness = 260, damping = 28, onUpdate, onDone }) {
  let x = from, v = 0, last = performance.now(), raf;
  const step = (now) => {
    const dt = Math.min(0.032, (now - last) / 1000);
    last = now;
    const f = -stiffness * (x - to) - damping * v;
    v += f * dt;
    x += v * dt;
    onUpdate(x);
    if (Math.abs(x - to) < 0.3 && Math.abs(v) < 0.3) { onUpdate(to); onDone && onDone(); return; }
    raf = requestAnimationFrame(step);
  };
  raf = requestAnimationFrame(step);
  return () => cancelAnimationFrame(raf);
}

export function hashStr(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

export function rng(seed) {
  let s = seed || 1;
  return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return ((s >>> 0) % 100000) / 100000; };
}

export function sortKey(s) {
  return String(s || '').toLowerCase().replace(/^(the|a|an)\s+/, '');
}

// Ripple feedback on pointer down for any element with .rip
export function installRipples(root = document) {
  root.addEventListener('pointerdown', (e) => {
    const el = e.target.closest('.rip, .icon-btn, .ctl, .nav-btn, .chip, .row, .tile, .menu-item');
    if (!el || el.disabled) return;
    const r = el.getBoundingClientRect();
    const d = Math.max(r.width, r.height) * 2;
    const s = document.createElement('span');
    s.className = 'ripple';
    s.style.cssText = `width:${d}px;height:${d}px;left:${e.clientX - r.left - d / 2}px;top:${e.clientY - r.top - d / 2}px`;
    el.appendChild(s);
    s.addEventListener('animationend', () => s.remove());
  });
}
