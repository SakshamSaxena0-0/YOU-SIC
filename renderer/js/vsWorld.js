// Mouse parallax for the themed backdrops: Vector Surrealism (#vsWorld, see css/vector-surrealism.css)
// and Liminal (#lmWorld, see css/liminal.css).
// No per-frame work: pointer moves are sampled at most every 90ms and written as two CSS variables
// (--px, --py, each -0.5..0.5) on the active theme's backdrop; its layers ease to them with a CSS
// transition on `translate`. Liminal also lines its corridor up with the album cover (alignHall).
// The parallax stays still unless one of those themes is on, the window is focused and motion is allowed (Reduce effects / Windows' reduced motion / the Reduce motion switch).
const WORLDS = [['vsWorld', 'theme-vector-surrealism'], ['lmWorld', 'theme-liminal']];

export function initVsWorld() {
  const html = document.documentElement;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const active = () => {
    if (html.classList.contains('fx-lite') || html.classList.contains('idle') || reduced.matches || document.querySelector('.app.reduce-motion')) return null;
    const w = WORLDS.find(([, cls]) => html.classList.contains(cls));
    return w ? document.getElementById(w[0]) : null;
  };
  let x = 0, y = 0, timer = 0, world = null;
  const write = () => {
    timer = 0;
    if (!world) return;
    world.style.setProperty('--px', x.toFixed(3));
    world.style.setProperty('--py', y.toFixed(3));
  };
  addEventListener('pointermove', (e) => {
    world = active();
    if (!world) return;
    x = e.clientX / innerWidth - 0.5;
    y = e.clientY / innerHeight - 0.5;
    if (!timer) timer = setTimeout(write, 90);
  }, { passive: true });
  alignHall(html);
}

// Liminal: the corridor's vanishing point sits behind the album cover. Measure the cover whenever the
// layout changes (or the theme is switched on) and write its centre as --vx/--vy on #lmWorld.
function alignHall(html) {
  const world = document.getElementById('lmWorld'), wrap = document.getElementById('aaWrap');
  if (!world || !wrap) return;
  let queued = 0;
  const measure = () => {
    queued = 0;
    if (!html.classList.contains('theme-liminal')) return;
    const card = wrap.querySelector('.aa-card[data-slot="cur"]') || wrap;
    const r = card.getBoundingClientRect();
    if (!r.width) return;
    world.style.setProperty('--vx', `${Math.round(r.left + r.width / 2)}px`);
    world.style.setProperty('--vy', `${Math.round(r.top + r.height / 2)}px`);
  };
  const soon = () => { if (!queued) queued = requestAnimationFrame(measure); };
  new ResizeObserver(soon).observe(wrap);
  let was = html.classList.contains('theme-liminal');
  new MutationObserver(() => {
    const on = html.classList.contains('theme-liminal');
    if (on && !was) setTimeout(soon, 50); // after the theme's styles have laid the page out
    was = on;
  }).observe(html, { attributes: true, attributeFilter: ['class'] });
  soon();
}
