'use strict';
const { app, BrowserWindow, ipcMain, protocol, dialog, nativeImage, shell, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const fsp = fs.promises;
const crypto = require('crypto');
const { Readable } = require('stream');
const { execFile } = require('child_process');

const ROOT = __dirname;
const AUDIO_EXT = new Set(['.mp3', '.flac', '.m4a', '.aac', '.ogg', '.oga', '.opus', '.wav', '.webm', '.mp4', '.weba']);
const COVER_NAMES = ['cover', 'folder', 'front', 'album', 'albumart', 'albumartsmall'];
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.json': 'application/json', '.woff2': 'font/woff2', '.ico': 'image/x-icon',
  '.mp3': 'audio/mpeg', '.flac': 'audio/flac', '.m4a': 'audio/mp4', '.mp4': 'audio/mp4', '.aac': 'audio/aac',
  '.ogg': 'audio/ogg', '.oga': 'audio/ogg', '.opus': 'audio/ogg', '.wav': 'audio/wav', '.webm': 'audio/webm', '.weba': 'audio/webm',
};

protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } },
]);

let win = null;
let dataDir, coverDir;
let library = { version: 2, folders: [], tracks: [] };
let trackPaths = new Set();
let scanning = false;

const sha1 = (s) => crypto.createHash('sha1').update(s).digest('hex');
const b64urlDecode = (s) => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');

// ---------- JSON stores ----------
async function readJson(name, fallback) {
  try { return JSON.parse(await fsp.readFile(path.join(dataDir, name), 'utf8')); } catch { return fallback; }
}
const writeQueues = new Map();
function writeJson(name, data) {
  // Serialize writes per file and write atomically so a crash never leaves half a file.
  const prev = writeQueues.get(name) || Promise.resolve();
  const next = prev.then(async () => {
    const file = path.join(dataDir, name);
    const tmp = file + '.tmp';
    await fsp.writeFile(tmp, JSON.stringify(data));
    await fsp.rename(tmp, file);
  }).catch((e) => console.error('write failed', name, e));
  writeQueues.set(name, next);
  return next;
}

// ---------- protocol ----------
async function serveFile(req, file) {
  let st;
  try { st = await fsp.stat(file); } catch { return new Response('Not found', { status: 404 }); }
  if (!st.isFile()) return new Response('Not found', { status: 404 });
  const type = MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
  const size = st.size;
  let start = 0, end = size - 1, status = 200;
  const range = req.headers.get('range');
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m) {
      if (m[1] === '' && m[2] !== '') { start = Math.max(0, size - Number(m[2])); }
      else {
        start = Number(m[1] || 0);
        if (m[2] !== '') end = Math.min(Number(m[2]), size - 1);
      }
      if (start > end || start >= size) {
        return new Response('', { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
      }
      status = 206;
    }
  }
  const headers = {
    'Content-Type': type,
    'Content-Length': String(end - start + 1),
    'Accept-Ranges': 'bytes',
    'Cache-Control': type.startsWith('image/') ? 'max-age=31536000' : 'no-cache',
  };
  if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  if (req.method === 'HEAD') return new Response(null, { status, headers });
  const stream = fs.createReadStream(file, { start, end });
  return new Response(Readable.toWeb(stream), { status, headers });
}

function isAllowedMedia(file) {
  if (trackPaths.has(file)) return true;
  const norm = path.resolve(file).toLowerCase();
  return library.folders.some((f) => norm.startsWith(path.resolve(f).toLowerCase() + path.sep));
}

function registerProtocol() {
  protocol.handle('app', async (req) => {
    const u = new URL(req.url);
    let p = decodeURIComponent(u.pathname);
    if (p.startsWith('/media/')) {
      const file = b64urlDecode(p.slice(7));
      if (!isAllowedMedia(file) || !AUDIO_EXT.has(path.extname(file).toLowerCase())) {
        return new Response('Forbidden', { status: 403 });
      }
      return serveFile(req, file);
    }
    if (p.startsWith('/cover/')) {
      const name = p.slice(7);
      if (!/^[a-f0-9]{40}(_s)?\.jpg$/.test(name)) return new Response('Bad', { status: 400 });
      return serveFile(req, path.join(coverDir, name));
    }
    if (p.startsWith('/wallpaper/')) {
      if (!wall.buf) return new Response('None', { status: 404 });
      return new Response(wall.buf, { headers: { 'content-type': 'image/jpeg', 'cache-control': 'no-store' } });
    }
    if (p === '/' || p === '') p = '/renderer/index.html';
    const allowed = ['/renderer/', '/node_modules/butterchurn/lib/', '/node_modules/butterchurn-presets/lib/', '/build/'];
    if (!allowed.some((a) => p.startsWith(a))) return new Response('Forbidden', { status: 403 });
    const file = path.normalize(path.join(ROOT, p));
    if (!file.startsWith(ROOT)) return new Response('Forbidden', { status: 403 });
    return serveFile(req, file);
  });
}

// ---------- library scanning ----------
async function walk(dir, out) {
  let entries;
  try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    if (e.name.startsWith('.') || e.name.startsWith('$')) continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) await walk(full, out);
    else if (e.isFile() && AUDIO_EXT.has(path.extname(e.name).toLowerCase())) out.push(full);
  }
}

const folderCoverCache = new Map();
async function findFolderCover(dir) {
  if (folderCoverCache.has(dir)) return folderCoverCache.get(dir);
  let found = null;
  try {
    const files = await fsp.readdir(dir);
    const imgs = files.filter((f) => /\.(jpe?g|png)$/i.test(f));
    for (const want of COVER_NAMES) {
      const hit = imgs.find((f) => path.parse(f).name.toLowerCase() === want);
      if (hit) { found = path.join(dir, hit); break; }
    }
    if (!found && imgs.length) found = path.join(dir, imgs[0]);
  } catch {}
  folderCoverCache.set(dir, found);
  return found;
}

async function saveCover(key, buf) {
  const full = path.join(coverDir, key + '.jpg');
  const small = path.join(coverDir, key + '_s.jpg');
  try {
    let img = nativeImage.createFromBuffer(buf);
    if (img.isEmpty()) return false;
    const { width } = img.getSize();
    const big = width > 1000 ? img.resize({ width: 1000, quality: 'best' }) : img;
    await fsp.writeFile(full, big.toJPEG(92));
    await fsp.writeFile(small, img.resize({ width: 220, quality: 'best' }).toJPEG(85));
    return true;
  } catch { return false; }
}

function str(v) { return v == null ? '' : String(v).trim(); }

function parseLyricsTag(common) {
  const l = common.lyrics;
  if (!l || !l.length) return null;
  const first = l[0];
  if (typeof first === 'string') return { text: first };
  if (first.syncText && first.syncText.length) {
    return { synced: first.syncText.map((s) => ({ t: (s.timestamp || 0) / 1000, text: s.text })) };
  }
  return first.text ? { text: first.text } : null;
}

async function scanLibrary(force = false) {
  if (scanning) return library;
  scanning = true;
  const mm = await import('music-metadata');
  const send = (msg) => { if (win && !win.isDestroyed()) win.webContents.send('lib:progress', msg); };
  try {
    const files = [];
    for (const f of library.folders) await walk(f, files);
    const old = new Map(library.tracks.map((t) => [t.path, t]));
    const coverKeys = new Set();
    try { for (const f of await fsp.readdir(coverDir)) if (f.endsWith('_s.jpg')) coverKeys.add(f.slice(0, 40)); } catch {}
    const result = new Array(files.length);
    let done = 0, idx = 0;
    send({ phase: 'scan', done: 0, total: files.length });

    async function worker() {
      while (idx < files.length) {
        const i = idx++;
        const file = files[i];
        let st;
        try { st = await fsp.stat(file); } catch { done++; continue; }
        const prev = old.get(file);
        if (!force && prev && prev.mtime === st.mtimeMs && prev.size === st.size) {
          result[i] = prev; done++;
          if (done % 50 === 0) send({ phase: 'scan', done, total: files.length });
          continue;
        }
        const dir = path.dirname(file);
        let t = {
          id: sha1(file), path: file, folder: dir, mtime: st.mtimeMs, size: st.size,
          added: prev ? prev.added : Date.now(),
          title: path.parse(file).name, artist: '', albumArtist: '', album: '', genre: '', composer: '',
          year: 0, track: 0, disc: 0, duration: 0, cover: null, fmt: null,
        };
        try {
          const albumGuess = sha1(dir.toLowerCase());
          const meta = await mm.parseFile(file, { skipCovers: false, duration: false, skipPostHeaders: true });
          const c = meta.common, f = meta.format;
          t.title = str(c.title) || t.title;
          t.artist = str(c.artist) || str(c.albumartist);
          t.albumArtist = str(c.albumartist) || t.artist;
          t.album = str(c.album);
          t.genre = str(c.genre && c.genre[0]);
          t.composer = str(c.composer && c.composer[0]);
          t.year = Number(c.year) || 0;
          t.track = (c.track && c.track.no) || 0;
          t.disc = (c.disk && c.disk.no) || 0;
          t.duration = f.duration || 0;
          t.fmt = {
            codec: str(f.codec || f.container), container: str(f.container), sampleRate: f.sampleRate || 0,
            bits: f.bitsPerSample || 0, bitrate: Math.round((f.bitrate || 0) / 1000), channels: f.numberOfChannels || 0,
            lossless: !!f.lossless,
          };
          t.hasLyrics = !!(c.lyrics && c.lyrics.length);
          const albumKey = t.album ? sha1((t.albumArtist + '|' + t.album).toLowerCase()) : albumGuess;
          if (coverKeys.has(albumKey)) t.cover = albumKey;
          else if (c.picture && c.picture.length) {
            const pic = c.picture.find((p) => /front/i.test(p.type || '')) || c.picture[0];
            if (await saveCover(albumKey, Buffer.from(pic.data))) { coverKeys.add(albumKey); t.cover = albumKey; }
          }
          if (!t.cover) {
            const fc = await findFolderCover(dir);
            if (fc) {
              const fk = sha1(fc.toLowerCase());
              if (coverKeys.has(fk) || await saveCover(fk, await fsp.readFile(fc))) { coverKeys.add(fk); t.cover = fk; }
            }
          }
        } catch (e) {
          // Unreadable tags: keep the file playable with filename-derived metadata.
        }
        result[i] = t;
        done++;
        if (done % 10 === 0) send({ phase: 'scan', done, total: files.length });
      }
    }
    await Promise.all(Array.from({ length: 6 }, worker));
    library.tracks = result.filter(Boolean);
    trackPaths = new Set(library.tracks.map((t) => t.path));
    await writeJson('library.json', library);
    send({ phase: 'done', done: files.length, total: files.length });
    return library;
  } finally {
    scanning = false;
  }
}

// ---------- lyrics ----------
function parseLrc(text) {
  const lines = [];
  let offset = 0;
  for (const raw of text.split(/\r?\n/)) {
    const off = /^\[offset:\s*([+-]?\d+)\]/i.exec(raw);
    if (off) { offset = Number(off[1]) / 1000; continue; }
    const tags = [...raw.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
    if (!tags.length) continue;
    const content = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const m of tags) lines.push({ t: Number(m[1]) * 60 + Number(m[2]) - offset, text: content });
  }
  lines.sort((a, b) => a.t - b.t);
  return lines;
}

async function getLyrics(file) {
  if (!trackPaths.has(file)) return null;
  const base = file.slice(0, -path.extname(file).length);
  for (const ext of ['.lrc', '.LRC', '.txt']) {
    try {
      const txt = await fsp.readFile(base + ext, 'utf8');
      const synced = parseLrc(txt);
      return synced.length ? { synced, source: 'lrc' } : { text: txt, source: 'txt' };
    } catch {}
  }
  try {
    const mm = await import('music-metadata');
    const meta = await mm.parseFile(file, { skipCovers: true, duration: false });
    const l = parseLyricsTag(meta.common);
    if (l && l.text && /\[\d+:\d+/.test(l.text)) {
      const synced = parseLrc(l.text);
      if (synced.length) return { synced, source: 'tag' };
    }
    if (l) return { ...l, source: 'tag' };
  } catch {}
  return null;
}

// ---------- desktop wallpaper ----------
// The "Wallpaper" window background paints the user's real wallpaper inside the app, lined up with the
// desktop, so it shows through sharp (acrylic can only show it blurred) and the glass buttons refract it.
const wall = { on: false, path: null, mtime: 0, style: 10, buf: null, v: 0, color: null, timer: null, watcher: null, busy: false };
const DESKTOP_KEY = 'HKCU\\Control Panel\\Desktop';
const VD_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\VirtualDesktops';
const TRANSCODED = path.join(process.env.APPDATA || '', 'Microsoft', 'Windows', 'Themes', 'TranscodedWallpaper');

// Every value under a key (and its subkeys with /s) from one reg.exe run: { 'HKCU\\Key': { Name: value } }.
function regQueryAll(key, recursive = false) {
  return new Promise((resolve) => {
    execFile('reg', ['query', key, ...(recursive ? ['/s'] : [])], { windowsHide: true }, (err, out) => {
      const res = {};
      if (err) return resolve(res);
      let cur = null;
      for (const line of out.split(/\r?\n/)) {
        if (/^HKEY_/.test(line)) { cur = res[line.trim().replace(/^HKEY_CURRENT_USER/, 'HKCU')] = {}; continue; }
        const m = cur && line.match(/^\s+(.+?)\s+(REG_\w+)\s*(.*)$/);
        if (m) cur[m[1]] = m[3].trim();
      }
      resolve(res);
    });
  });
}

// GUID of the current virtual desktop (Windows 11 lets each one have its own wallpaper).
function guidFromHex(hex) {
  const b = Buffer.from(hex, 'hex');
  if (b.length !== 16) return null;
  const h = (buf) => buf.toString('hex').toUpperCase();
  const rev = (s, e) => h(Buffer.from(b.subarray(s, e)).reverse());
  return '{' + rev(0, 4) + '-' + rev(4, 6) + '-' + rev(6, 8) + '-' + h(b.subarray(8, 10)) + '-' + h(b.subarray(10, 16)) + '}';
}

// Two reg.exe runs per check (it used to be four, every 4 seconds).
async function currentWallpaper() {
  let file = null;
  const [vd, desk] = await Promise.all([regQueryAll(VD_KEY, true), regQueryAll(DESKTOP_KEY)]);
  const dv = desk[DESKTOP_KEY] || {};
  const guid = vd[VD_KEY]?.CurrentVirtualDesktop && guidFromHex(vd[VD_KEY].CurrentVirtualDesktop);
  if (guid) file = vd[VD_KEY + '\\Desktops\\' + guid]?.Wallpaper || null;
  const perDesktop = !!file;
  if (!file) file = dv.WallPaper || null;
  // slideshows and themes only update the transcoded copy
  try {
    const t = fs.statSync(TRANSCODED);
    const f = file && fs.existsSync(file) ? fs.statSync(file) : null;
    if (!f || (!perDesktop && t.mtimeMs > f.mtimeMs + 2000)) file = TRANSCODED;
  } catch {}
  const style = Number(dv.WallpaperStyle || 10);
  return { file, style: dv.TileWallpaper === '1' ? 1 : style };
}

async function refreshWallpaper() {
  if (!wall.on || !win || win.isDestroyed()) return;
  if (wall.busy) { wall.again = true; return; } // one check at a time; rerun once if asked meanwhile
  wall.busy = true;
  try { await loadWallpaper(); } finally { wall.busy = false; }
  if (wall.again) { wall.again = false; refreshWallpaper(); }
}

async function loadWallpaper() {
  const { file, style } = await currentWallpaper();
  let mtime = 0;
  try { mtime = file ? fs.statSync(file).mtimeMs : 0; } catch {}
  if (file === wall.path && mtime === wall.mtime && style === wall.style && wall.v) return;
  wall.path = file; wall.mtime = mtime; wall.style = style; wall.buf = null; wall.color = null;
  if (file) {
    try {
      let img = nativeImage.createFromBuffer(await fsp.readFile(file));
      if (!img.isEmpty()) {
        // downscale to what the display actually needs (4K wallpapers would waste memory)
        const d = screen.getDisplayMatching(win.getBounds());
        const { width, height } = img.getSize();
        const k = Math.max((d.size.width * d.scaleFactor) / width, (d.size.height * d.scaleFactor) / height);
        if (k < 1) img = img.resize({ width: Math.round(width * k), quality: 'best' });
        wall.buf = img.toJPEG(90);
      }
    } catch {}
  }
  if (!wall.buf) {
    const rgb = (await regQueryAll('HKCU\\Control Panel\\Colors'))['HKCU\\Control Panel\\Colors']?.Background || '0 0 0';
    wall.color = 'rgb(' + rgb.split(/\s+/).join(',') + ')';
  }
  wall.v++;
  win.webContents.send('wall', { url: wall.buf ? 'app://local/wallpaper/' + wall.v + '.jpg' : null, color: wall.color, style });
  sendGeom();
}

function sendGeom() {
  if (!win || win.isDestroyed()) return;
  const b = win.getContentBounds();
  const d = screen.getDisplayMatching(b).bounds;
  win.webContents.send('win:geom', { x: b.x - d.x, y: b.y - d.y, w: d.width, h: d.height });
}

// Wallpaper changes are picked up from the theme folder (Windows rewrites TranscodedWallpaper on every
// change, slideshows included), on focus/restore, and by a slow fallback poll only while the window is
// visible, instead of spawning reg.exe every few seconds in the background.
function setWallpaperMode(on) {
  wall.on = !!on;
  clearInterval(wall.timer);
  wall.watcher?.close(); wall.watcher = null;
  if (!wall.on) return;
  wall.v = 0;
  refreshWallpaper();
  let t = null;
  const soon = () => { clearTimeout(t); t = setTimeout(refreshWallpaper, 600); };
  try { wall.watcher = fs.watch(path.dirname(TRANSCODED), soon); wall.watcher.on('error', () => {}); } catch {}
  wall.timer = setInterval(() => { if (win && !win.isDestroyed() && win.isVisible() && !win.isMinimized()) refreshWallpaper(); }, 30000);
}

// ---------- window ----------
// Acrylic window material needs Windows 11 22H2 (build 22621) or newer.
const GLASS_SUPPORTED = process.platform === 'win32' && Number((require('os').release().split('.')[2]) || 0) >= 22621;

async function createWindow() {
  const b = await readJson('window.json', {});
  const settings = await readJson('settings.json', {});
  const glass = settings.windowBg === 'acrylic' && GLASS_SUPPORTED;
  win = new BrowserWindow({
    width: b.width || 1180, height: b.height || 820, x: b.x, y: b.y,
    minWidth: 380, minHeight: 600,
    frame: false, show: false,
    // Windows 11 acrylic: the desktop shows through the app, blurred, behind its translucent surfaces
    backgroundColor: glass ? '#00000000' : '#07070a',
    backgroundMaterial: glass ? 'acrylic' : 'none',
    icon: path.join(ROOT, 'build', 'icon.png'),
    title: 'YouSic',
    webPreferences: {
      preload: path.join(ROOT, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      backgroundThrottling: false,
    },
  });
  if (b.maximized) win.maximize();
  win.once('ready-to-show', () => win.show());
  win.loadURL('app://local/renderer/index.html');
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  const sendState = () => win.webContents.send('win:state', { maximized: win.isMaximized() });
  win.on('maximize', sendState);
  win.on('unmaximize', sendState);
  for (const ev of ['move', 'resize', 'maximize', 'unmaximize', 'restore']) win.on(ev, () => wall.on && sendGeom());
  for (const ev of ['focus', 'restore', 'show']) win.on(ev, () => wall.on && refreshWallpaper());
  screen.on('display-metrics-changed', () => { if (wall.on) { wall.v = 0; refreshWallpaper(); } });
  // back from the taskbar while the mini player is up: the full player takes over again (not a stray
  // restore around the moment the mini player appears and the main window minimises)
  win.on('restore', () => { if (mini && Date.now() - miniShownAt > 1000) closeMini(); });
  win.on('close', () => {
    if (mini) mini.destroy();
    try { fs.writeFileSync(path.join(dataDir, 'window.json'), JSON.stringify({ ...win.getNormalBounds(), maximized: win.isMaximized() })); } catch {}
  });
}

function thumbIcon(dataUrl) { return nativeImage.createFromDataURL(dataUrl); }

// ---------- mini player ----------
// A small always-on-top window (renderer/mini.html): the cover and the transport, or two lines of the
// lyrics. The main window minimises while it shows and the music keeps playing there; the main renderer
// sends it the state (mini:state) and it sends commands back (mini:cmd -> 'cmd'). The window is
// see-through around its box: the controls drop down into that room (up, in the lower half of the
// screen), and clicks there go through to whatever is below (setIgnoreMouseEvents).
const MINI = { w: 460, h: 120, gap: 8, panel: 120, m: 24 };
const MINI_W = MINI.w + 2 * MINI.m, MINI_H = 2 * MINI.m + MINI.h + MINI.gap + MINI.panel;
let mini = null, miniState = {}, miniDrop = 'down', miniDrag = null, miniShownAt = 0;

// the box's top left on screen <-> the window's
const miniWinPos = (box, drop) => ({ x: box.x - MINI.m, y: box.y - MINI.m - (drop === 'up' ? MINI.panel + MINI.gap : 0) });
function miniBox() {
  const b = mini.getBounds();
  return { x: b.x + MINI.m, y: b.y + MINI.m + (miniDrop === 'up' ? MINI.panel + MINI.gap : 0) };
}

// keep the box on its screen; the controls drop towards the roomier half
function miniPlace(box) {
  const wa = screen.getDisplayNearestPoint({ x: Math.round(box.x + MINI.w / 2), y: Math.round(box.y + MINI.h / 2) }).workArea;
  box.x = Math.round(Math.min(Math.max(box.x, wa.x + 4), wa.x + wa.width - MINI.w - 4));
  box.y = Math.round(Math.min(Math.max(box.y, wa.y + 4), wa.y + wa.height - MINI.h - 4));
  miniDrop = box.y + MINI.h / 2 > wa.y + wa.height / 2 ? 'up' : 'down';
  mini.setBounds({ ...miniWinPos(box, miniDrop), width: MINI_W, height: MINI_H });
  mini.webContents.send('mini:drop', miniDrop);
  return box;
}

async function openMini() {
  if (mini) { mini.show(); return; }
  const saved = await readJson('mini.json', null);
  const wa = screen.getDisplayMatching(win.getBounds()).workArea;
  const box = saved && Number.isFinite(saved.x) ? { x: saved.x, y: saved.y } : { x: wa.x + wa.width - MINI.w - 24, y: wa.y + 24 };
  mini = new BrowserWindow({
    width: MINI_W, height: MINI_H, frame: false, transparent: true, backgroundColor: '#00000000', hasShadow: false,
    resizable: false, maximizable: false, minimizable: false, fullscreenable: false, skipTaskbar: true, alwaysOnTop: true, show: false,
    title: 'YouSic mini player', icon: path.join(ROOT, 'build', 'icon.png'),
    webPreferences: { preload: path.join(ROOT, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, backgroundThrottling: false },
  });
  miniPlace(box);
  mini.setIgnoreMouseEvents(true, { forward: true }); // the page turns this off over its box
  mini.loadURL('app://local/renderer/mini.html');
  mini.once('ready-to-show', () => { if (!mini) return; mini.show(); miniShownAt = Date.now(); win.minimize(); });
  mini.on('closed', () => {
    mini = null;
    if (!win || win.isDestroyed()) return;
    win.webContents.send('mini:closed');
    if (win.isMinimized()) win.restore();
    win.focus();
  });
}

function closeMini() {
  if (!mini) return;
  try { writeJson('mini.json', miniBox()); } catch {}
  mini.close();
}

function setupIpc() {
  ipcMain.handle('lib:get', () => library);
  ipcMain.handle('lib:scan', (_e, force) => scanLibrary(!!force));
  ipcMain.handle('lib:addFolder', async () => {
    const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'multiSelections'], title: 'Add music folder' });
    if (r.canceled) return library;
    for (const f of r.filePaths) if (!library.folders.includes(f)) library.folders.push(f);
    await writeJson('library.json', library);
    return scanLibrary(false);
  });
  ipcMain.handle('lib:removeFolder', async (_e, folder) => {
    library.folders = library.folders.filter((f) => f !== folder);
    await writeJson('library.json', library);
    return scanLibrary(false);
  });
  ipcMain.handle('lyrics:get', (_e, file) => getLyrics(file));
  ipcMain.handle('store:get', (_e, name) => {
    if (!['settings', 'stats', 'playlists'].includes(name)) return null;
    return readJson(name + '.json', null);
  });
  ipcMain.handle('store:set', async (_e, name, data) => {
    if (!['settings', 'stats', 'playlists'].includes(name)) return false;
    await writeJson(name + '.json', data);
    return true;
  });
  // Used on window unload so the last session state is never lost.
  ipcMain.on('store:setSync', (e, name, data) => {
    if (['settings', 'stats', 'playlists'].includes(name)) {
      try { fs.writeFileSync(path.join(dataDir, name + '.json'), JSON.stringify(data)); } catch {}
    }
    e.returnValue = true;
  });
  ipcMain.on('win:min', () => win.minimize());
  // mini player
  ipcMain.on('mini:open', (_e, state) => { miniState = state || {}; openMini(); });
  ipcMain.on('mini:close', () => closeMini());
  ipcMain.on('mini:state', (_e, part) => {
    Object.assign(miniState, part);
    if (mini && !mini.isDestroyed()) mini.webContents.send('mini:state', part);
  });
  ipcMain.handle('mini:hello', () => ({ drop: miniDrop, state: miniState }));
  ipcMain.on('mini:cmd', (_e, c) => { if (win && !win.isDestroyed()) win.webContents.send('cmd', c); });
  ipcMain.on('mini:ignore', (_e, on) => { if (mini) mini.setIgnoreMouseEvents(!!on, { forward: true }); });
  ipcMain.on('mini:drag', (_e, phase) => {
    if (!mini) return;
    const c = screen.getCursorScreenPoint();
    if (phase === 'start') { const b = mini.getBounds(); miniDrag = { cx: c.x, cy: c.y, x: b.x, y: b.y }; return; }
    if (!miniDrag) return;
    if (phase === 'move') {
      mini.setBounds({ x: miniDrag.x + c.x - miniDrag.cx, y: miniDrag.y + c.y - miniDrag.cy, width: MINI_W, height: MINI_H });
    } else {
      miniDrag = null;
      writeJson('mini.json', miniPlace(miniBox()));
    }
  });
  ipcMain.on('win:max', () => (win.isMaximized() ? win.unmaximize() : win.maximize()));
  ipcMain.handle('win:glass', (_e, on) => {
    on = !!on && GLASS_SUPPORTED;
    try {
      win.setBackgroundMaterial(on ? 'acrylic' : 'none');
      win.setBackgroundColor(on ? '#00000000' : '#07070a');
    } catch {}
    return on;
  });
  ipcMain.handle('win:glassSupported', () => GLASS_SUPPORTED);
  ipcMain.on('win:wallpaper', (_e, on) => setWallpaperMode(on));
  ipcMain.on('win:close', () => win.close());
  ipcMain.on('win:title', (_e, t) => win.setTitle(t));
  ipcMain.on('win:progress', (_e, v) => win.setProgressBar(v));
  ipcMain.on('shell:show', (_e, file) => { if (trackPaths.has(file)) shell.showItemInFolder(file); });
  ipcMain.on('thumbar', (_e, icons) => {
    // Taskbar thumbnail toolbar (prev / play-pause / next) like a native Windows player.
    try {
      win.setThumbarButtons([
        { tooltip: 'Previous', icon: thumbIcon(icons.prev), click: () => win.webContents.send('cmd', 'prev') },
        { tooltip: icons.playing ? 'Pause' : 'Play', icon: thumbIcon(icons.playing ? icons.pause : icons.play), click: () => win.webContents.send('cmd', 'toggle') },
        { tooltip: 'Next', icon: thumbIcon(icons.next), click: () => win.webContents.send('cmd', 'next') },
      ]);
    } catch {}
  });
}

app.whenReady().then(async () => {
  dataDir = app.getPath('userData');
  coverDir = path.join(dataDir, 'covers');
  await fsp.mkdir(coverDir, { recursive: true });
  library = await readJson('library.json', library);
  if (!library.folders.length) {
    const music = app.getPath('music');
    if (fs.existsSync(music)) library.folders.push(music);
  }
  trackPaths = new Set(library.tracks.map((t) => t.path));
  registerProtocol();
  setupIpc();
  await createWindow();
});

app.on('window-all-closed', () => app.quit());
