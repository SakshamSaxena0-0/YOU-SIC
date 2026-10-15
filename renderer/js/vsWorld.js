// Vector Surrealism theme: mouse parallax for the backdrop layers (#vsWorld, see css/vector-surrealism.css).
// No per-frame work: pointer moves are sampled at most every 90ms and written as two CSS variables
// (--px, --py, each -0.5..0.5); the layers ease to them with a CSS transition on `translate`.
// It stays still unless the theme is on, the window is focused and motion is allowed
// (Reduce effects / Windows' reduced motion / the Reduce motion switch).
export function initVsWorld() {
  const world = document.getElementById('vsWorld');
  if (!world) return;
  const html = document.documentElement;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const still = () =>
    !html.classList.contains('theme-vector-surrealism') || html.classList.contains('fx-lite') || html.classList.contains('idle') ||
    reduced.matches || !!document.querySelector('.app.reduce-motion');
  let x = 0, y = 0, timer = 0;
  const write = () => {
    timer = 0;
    world.style.setProperty('--px', x.toFixed(3));
    world.style.setProperty('--py', y.toFixed(3));
  };
  addEventListener('pointermove', (e) => {
    if (still()) return;
    x = e.clientX / innerWidth - 0.5;
    y = e.clientY / innerHeight - 0.5;
    if (!timer) timer = setTimeout(write, 90);
  }, { passive: true });
}
