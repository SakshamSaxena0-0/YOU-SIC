// Runs before the app (a plain script in <head>): if the Y2K theme was last used, show its boot splash
// straight away. It stays at most 600ms; the app removes it sooner if it is ready first.
(function () {
  try { if (localStorage.getItem('pr-theme') !== 'subway') return; } catch (e) { return; }
  var html = document.documentElement;
  html.classList.add('boot-y2k');
  setTimeout(function () { html.classList.remove('boot-y2k'); }, 600);
})();
