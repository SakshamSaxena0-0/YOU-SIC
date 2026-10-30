// YouSic for Android: window.pa, the same interface the Windows app's preload.js gives the page, backed
// by the app's native side (PaNative, Bridge.java). Loaded before everything else in index.html (the build
// adds it to the Android copy only).
(function () {
  'use strict';
  var N = window.PaNative;
  // the app's files, songs and covers are served by Server.java on this origin (app://local/ on Windows)
  window.PA_BASE = location.origin + '/';
  document.documentElement.classList.add('android');

  // Diagnostics: if the app hasn't started a few seconds after loading, show what went wrong (files that
  // failed, script errors, the WebView and Android versions) instead of a blank screen.
  var problems = [];
  window.addEventListener('error', function (e) {
    var t = e.target;
    if (t && t !== window && (t.src || t.href)) problems.push('could not load ' + String(t.src || t.href).replace(location.origin, ''));
    else if (e.message) problems.push(e.message + (e.filename ? ' (' + e.filename.replace(location.origin, '') + ':' + e.lineno + ')' : ''));
  }, true);
  window.addEventListener('unhandledrejection', function (e) {
    var r = e.reason;
    problems.push('promise: ' + (r && (r.stack || r.message) || r));
  });
  setTimeout(function () {
    if (window.__app && window.__app.playerView) return; // started (playerView exists once the library and settings loaded)
    var d = document.createElement('pre');
    d.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:99999;margin:0;padding:16px;overflow:auto;background:#07070a;color:#ddd;'
      + 'font:12px/1.5 monospace;white-space:pre-wrap;-webkit-user-select:text;user-select:text';
    var diag = '';
    try { diag = N.diag(); } catch (e) { diag = 'diag: ' + e; }
    d.textContent = "YouSic didn't start. Please send a screenshot of this.\n\n" + (problems.join('\n') || 'No errors were reported.')
      + '\n\n' + diag + '\n' + navigator.userAgent;
    (document.body || document.documentElement).appendChild(d);
  }, 8000);

  var seq = 0, pending = {}, listeners = { progress: [], cmd: [] };
  window.__paResolve = function (id, json) {
    var cb = pending[id];
    delete pending[id];
    if (cb) cb(json);
  };
  window.__paEmit = function (type, json) {
    var data;
    try { data = JSON.parse(json); } catch (e) { return; }
    (listeners[type] || []).forEach(function (f) { try { f(data); } catch (e) { console.error(e); } });
  };
  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (resolve) {
      var id = ++seq;
      pending[id] = function (json) { resolve(json == null ? null : JSON.parse(json)); };
      N.call(id, method, JSON.stringify(args));
    });
  }
  // the library can be thousands of tracks: the native side writes library.json and the page fetches it
  function library(p) {
    return p.then(function () {
      return fetch(window.PA_BASE + '__pa/library.json?' + Date.now()).then(function (r) { return r.json(); });
    });
  }
  function noop() {}
  function no() { return Promise.resolve(false); }

  window.pa = {
    platform: 'android',
    getLibrary: function () { return library(call('getLibrary')); },
    scan: function (force) { return library(call('scan', !!force)); },
    addFolder: function () { return library(call('addFolder')); },
    removeFolder: function (f) { return library(call('removeFolder', f)); },
    lyrics: function (file) { return call('lyrics', file); },
    load: function (name) { return call('load', name); },
    save: function (name, data) { return call('save', name, JSON.stringify(data)); },
    saveSync: function (name, data) { return N.saveSync(name, JSON.stringify(data)); },
    // phones have no window to minimise or maximise; "close" sends the app to the background
    minimize: noop, maximize: noop, close: function () { N.moveToBack(); },
    setGlass: no, glassSupported: no,
    setTitle: noop, setProgress: noop, showInFolder: noop, setThumbar: noop, setWallpaperMode: noop,
    onProgress: function (cb) { listeners.progress.push(cb); },
    // the app is listening: buttons pressed while the page was starting (the widget, with the app closed) run now
    onCommand: function (cb) { listeners.cmd.push(cb); if (N.commandsReady) N.commandsReady(); },
    onWinState: noop, onWallpaper: noop, onGeom: noop,
    // the playback notification, lock screen and headset buttons (PlaybackService.java)
    nowPlaying: function (info) { N.nowPlaying(info ? JSON.stringify(info) : ''); },
    // .lrc files next to songs need "All files access" on Android
    fileAccess: function () { return N.fileAccess(); },
    requestFileAccess: function () { return call('requestFileAccess'); },
  };

  // Android's back button: one step back in the app (a menu or dialog, fullscreen, the library...).
  // Answers false when there is nowhere left to go back to; the app then goes to the background.
  window.__paBack = function () {
    var a = window.__app;
    if (!a) return false;
    var body = document.body;
    if (document.querySelector('.sheet.open')) {
      // ui.js closes the sheet on Escape and stops the key there, so the app doesn't act on it too
      body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return true;
    }
    if (document.querySelector('.ctxmenu.open')) {
      body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); // a tap outside closes a menu
      return true;
    }
    if (document.querySelector('.vol-pop.open')) { a._toggleVolPop(); return true; }
    if (a.vis && a.vis.host.classList.contains('full')) { a.vis.toggleFull(); return true; }
    if (a.root.classList.contains('art-only')) { a.toggleArt(false); return true; }
    if (a.library.searching) { a.library.toggleSearch(false); return true; }
    if (a.narrow && a.visOpen) { a.toggleVis(false); return true; }
    if (a.narrow && a.lyrics.open) { a.lyrics.toggle(false); return true; }
    if (a.playerView.flipped) { a.playerView.flipArt(false); return true; }
    if (a.screen === 'eq') { a.show(a.narrow ? 'player' : 'library'); return true; }
    if (a.screen === 'library') {
      if (a.library.stack.length > 1) { a.library.back(); return true; }
      if (a.narrow) { a.show('player'); return true; }
    }
    return false;
  };

  // Leaving the app: save now, as the Windows app does when its window closes (Android may close the app
  // without warning later).
  window.__paPause = function () { window.dispatchEvent(new Event('beforeunload')); };
  window.__paResume = noop;
})();
