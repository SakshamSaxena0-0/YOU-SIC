import { $, fmtTime } from './util.js';
import { hydrateIcons, setIcon } from './icons.js';
import { etch } from './etch.js';

// The mini player window (mini.html). It plays nothing itself: the main window's renderer sends the state
// (miniLink.js, through main.js) and its buttons go back there as commands. It keeps its own clock
// between updates for the ring and the lyrics, and wakes only when a line or the ring needs to change.

const html = document.documentElement;
const mini = $('#mini'), box = $('#box'), art = $('#art'), img = $('#cover'), ctl = $('#ctl');
const main = $('#main'), panel = $('#panel'), panelIn = $('#panelIn'), lyr = $('#lyr'), l1 = $('#l1'), l2 = $('#l2');
const ring = $('#playRing'), hud = $('#hud');
const name = $('#name'), group = $('#ctlGroup'), seek = $('#seek'), track = $('#track'), range = $('#seekRange'), tPos = $('#tPos'), tDur = $('#tDur');

const S = { track: null, time: null, shuffle: false, repeat: 'off', volume: 0.8, muted: false, lyrics: null };
let recvAt = 0;
let lyricsOn = localStorage.getItem('pr-mini-lyrics') === '1';
const cmd = (c) => pa.miniCmd(c);

// where the song is now, by our own clock since the last update
function pos() {
  const t = S.time;
  if (!t) return 0;
  return t.playing ? t.pos + ((performance.now() - recvAt) / 1000) * (t.rate || 1) : t.pos;
}

function apply(part) {
  if (!part) return;
  if ('time' in part) recvAt = performance.now();
  Object.assign(S, part);
  if (part.look) applyLook(part.look);
  if ('track' in part) renderTrack();
  if ('time' in part) { mini.classList.toggle('playing', !!S.time?.playing); tickTime(); }
  if ('shuffle' in part || 'repeat' in part) renderModes();
  if ('volume' in part || 'muted' in part) renderVol();
  if ('lyrics' in part || 'time' in part || 'track' in part) lyricsTick();
}

// ---------- theme ----------
let lookVars = [];
function applyLook({ cls = '', vars = {} }) {
  html.className = `${cls} mini-doc${document.hasFocus() ? '' : ' idle'}`;
  for (const n of lookVars) if (!(n in vars)) html.style.removeProperty(n);
  for (const [n, v] of Object.entries(vars)) html.style.setProperty(n, v);
  lookVars = Object.keys(vars);
  requestAnimationFrame(fit);
  if (S.track) renderTrack(); // Metal's etched cover comes and goes with the look
}
// looping decorations rest while the window isn't focused, as in the main window
const idle = () => html.classList.toggle('idle', !document.hasFocus());
addEventListener('focus', idle);
addEventListener('blur', idle);

// each theme sizes its buttons its own way: zoom the row to fit the box (or the panel), above the progress
function fit() {
  ctl.style.zoom = '1';
  const host = group.parentElement, cs = getComputedStyle(host);
  const w = ctl.offsetWidth, h = ctl.offsetHeight;
  if (!w || !h) return;
  // room for the glows and the 'on' dots some themes draw just outside their buttons
  const W = host.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight) - 8;
  const H = host.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom) - name.offsetHeight - seek.offsetHeight - 2 * 2 - 4;
  ctl.style.zoom = String(Math.min(1, W / w, H / h));
}

// ---------- song, state, modes, volume ----------
function renderTrack() {
  const t = S.track;
  art.title = name.title = t ? (t.artist ? `${t.title} — ${t.artist}` : t.title) : 'Nothing playing';
  $('#plTitle').textContent = t ? t.title : 'Nothing playing';
  $('#plArtist').textContent = (t && t.artist) || '';
  if (!t || !t.cover) { art.classList.add('ph'); img.removeAttribute('src'); img.dataset.src = ''; return; }
  // Metal: the cover etched into the aluminium, as in the main window (js/etch.js)
  const etched = html.classList.contains('metal-etched');
  const key = t.cover + (etched ? '#etched' : '');
  if (img.dataset.src === key) return;
  img.dataset.src = key;
  art.classList.remove('ph');
  img.classList.remove('in');
  img.onload = () => img.classList.add('in');
  img.onerror = () => art.classList.add('ph');
  if (!etched) { img.src = t.cover; return; }
  const cover = t.cover;
  etch(cover, 480).then((u) => { if (img.dataset.src === key) img.src = u; }, () => { if (img.dataset.src === key) img.src = cover; });
}

// the ring round the play button and the progress bar, redrawn as the song's clock passes each second
let timeT = 0, seekDrag = null;
function tickTime() {
  clearTimeout(timeT);
  const t = S.time, dur = t && isFinite(t.dur) && t.dur > 0 ? t.dur : 0, p = Math.min(dur, pos());
  ring.style.strokeDashoffset = String(100 - (dur ? (p / dur) * 100 : 0));
  seek.classList.toggle('off', !dur);
  tDur.textContent = fmtTime(dur);
  track.setAttribute('aria-valuemax', String(Math.floor(dur)));
  if (!seekDrag) showPos(dur ? p / dur : 0, p);
  if (t && t.playing && dur) timeT = setTimeout(tickTime, Math.max(50, ((1 - (p % 1)) / (t.rate || 1)) * 1000 + 15));
}
function showPos(frac, sec) {
  range.value = String(Math.round(frac * 1000));
  range.style.setProperty('--p', `${frac * 100}%`);
  tPos.textContent = fmtTime(sec);
  track.setAttribute('aria-valuenow', String(Math.floor(sec)));
}

function renderModes() {
  $('#btnShuffle').classList.toggle('on', !!S.shuffle);
  const rb = $('#btnRepeat');
  setIcon(rb, S.repeat === 'one' ? 'repeat_one' : 'repeat');
  rb.classList.toggle('on', S.repeat !== 'off');
  rb.title = { off: 'Repeat: off (R)', all: 'Repeat: category (R)', one: 'Repeat: song (R)' }[S.repeat] || 'Repeat (R)';
}

let hudWanted = 0, hudT = 0;
function renderVol() {
  const v = S.muted ? 0 : S.volume;
  const ic = v === 0 ? 'volume_off' : v < 0.5 ? 'volume_down' : 'volume_up';
  setIcon($('#bVol'), ic);
  $('#bVol').title = S.muted ? 'Unmute (M)' : `Volume ${Math.round(v * 100)}% · click to mute (M), scroll to change`;
  // show the level over the cover when the change came from here
  if (performance.now() > hudWanted) return;
  setIcon($('#hudIco'), ic);
  $('#hudVal').textContent = S.muted ? 'Muted' : `${Math.round(v * 100)}%`;
  hud.classList.add('show');
  clearTimeout(hudT);
  hudT = setTimeout(() => hud.classList.remove('show'), 900);
}
function volCmd(c) { hudWanted = performance.now() + 1500; cmd(c); }

// ---------- lyrics: the line playing and the next ----------
let lyT = 0, lyKey = '';
function lyricsTick() {
  clearTimeout(lyT);
  if (!lyricsOn) return;
  const t = S.track, L = S.lyrics, time = S.time;
  const rate = (time && time.rate) || 1, playing = !!(time && time.playing);
  let a = '', b = '', wait = 0;
  if (!t) a = 'Nothing playing';
  else if (!L || L.id !== t.id || L.loading) { a = t.title; b = 'Looking for lyrics…'; }
  else if (L.synced && L.synced.length) {
    const lines = L.synced, now = pos() + 0.15;
    let lo = 0, hi = lines.length - 1, i = -1;
    while (lo <= hi) { const m = (lo + hi) >> 1; if (lines[m].t <= now) { i = m; lo = m + 1; } else hi = m - 1; }
    a = i >= 0 ? lines[i].text || '♪' : '♪';
    b = lines[i + 1] ? lines[i + 1].text || '♪' : '';
    if (playing && lines[i + 1]) wait = (lines[i + 1].t - now) / rate;
  } else if (L.text) {
    // plain lyrics have no times: step through them evenly over the song
    const rows = L.text.split(/\r?\n/).map((r) => r.trim()).filter(Boolean);
    const d = (time && time.dur) || 0, p = pos();
    const i = d ? Math.min(rows.length - 1, Math.floor((p / d) * rows.length)) : 0;
    a = rows[i] || '';
    b = rows[i + 1] || '';
    if (playing && d && i < rows.length - 1) wait = (((i + 1) * d) / rows.length - p) / rate;
  } else { a = t.title; b = 'No lyrics for this song'; }
  setLines(a, b);
  if (wait > 0) lyT = setTimeout(lyricsTick, Math.min(30000, Math.max(40, wait * 1000 + 20)));
}
function setLines(a, b) {
  const key = a + '\n' + b;
  if (key === lyKey) return;
  lyKey = key;
  l1.textContent = a;
  l2.textContent = b;
  lyr.classList.remove('roll');
  void lyr.offsetWidth; // restart the animation
  lyr.classList.add('roll');
}

// ---------- moving through the song: click or drag the bar; the song jumps on release ----------
const seekFrac = (e) => { const r = track.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
function seekShow(e) { const d = S.time?.dur || 0; seekDrag.frac = seekFrac(e); showPos(seekDrag.frac, seekDrag.frac * d); }
track.addEventListener('pointerdown', (e) => {
  if (e.button !== 0 || !S.time?.dur) return;
  e.preventDefault();
  seekDrag = { frac: 0 };
  track.setPointerCapture(e.pointerId);
  track.classList.add('drag');
  seekShow(e);
});
track.addEventListener('pointermove', (e) => { if (seekDrag) seekShow(e); });
track.addEventListener('pointerup', () => {
  if (!seekDrag) return;
  const t = seekDrag.frac * (S.time?.dur || 0);
  cmd(`seek:${t.toFixed(2)}`);
  // carry on from there at once; the main window's own update follows
  S.time = { ...S.time, pos: t };
  recvAt = performance.now();
  seekEnd();
  lyricsTick();
});
const seekEnd = () => { if (!seekDrag) return; seekDrag = null; track.classList.remove('drag'); tickTime(); };
track.addEventListener('lostpointercapture', seekEnd);

function setLyricsMode(on) {
  lyricsOn = on;
  try { localStorage.setItem('pr-mini-lyrics', on ? '1' : '0'); } catch {}
  mini.classList.toggle('lyr', on);
  mini.classList.remove('open');
  $('#bLyrics').classList.toggle('on', on);
  (on ? panelIn : main).insertBefore(group, on ? null : lyr);
  lyKey = '';
  fit();
  lyricsTick();
}

// ---------- the drop-down panel (lyrics showing): open while the pointer is over the box or the panel ----------
let closeT = 0;
const openPanel = () => { if (!lyricsOn) return; clearTimeout(closeT); mini.classList.add('open'); };
const closeSoon = () => { clearTimeout(closeT); closeT = setTimeout(() => mini.classList.remove('open'), 280); };
for (const el of [box, panel]) { el.addEventListener('pointerenter', openPanel); el.addEventListener('pointerleave', closeSoon); }
html.addEventListener('mouseleave', closeSoon);

// clicks on the see-through part go to whatever is below; main.js forwards the moves so this still sees them
let ignoring = true, drag = null;
function setIgnore(on) { if (on !== ignoring) { ignoring = on; pa.miniIgnore(on); } }
document.addEventListener('mousemove', (e) => { if (!drag && !seekDrag) setIgnore(!e.target.closest('.mini-box, .mini-panel')); });

// ---------- moving the window: drag the box anywhere but its buttons ----------
box.addEventListener('pointerdown', (e) => {
  if (e.button !== 0 || e.target.closest('button, .mini-track')) return;
  drag = { x: e.screenX, y: e.screenY, moved: false };
  box.setPointerCapture(e.pointerId);
  pa.miniDrag('start');
});
box.addEventListener('pointermove', (e) => {
  if (!drag) return;
  if (!drag.moved && Math.hypot(e.screenX - drag.x, e.screenY - drag.y) < 4) return;
  drag.moved = true;
  mini.classList.add('dragging');
  pa.miniDrag('move');
});
const endDrag = () => {
  if (!drag) return;
  if (drag.moved) pa.miniDrag('end');
  drag = null;
  mini.classList.remove('dragging');
};
box.addEventListener('pointerup', endDrag);
box.addEventListener('lostpointercapture', endDrag);

// ---------- buttons, wheel, keys ----------
function full() {
  mini.classList.add('bye');
  setTimeout(() => pa.miniClose(), 150);
}
$('#full').onclick = full;
art.addEventListener('dblclick', full);
$('#btnPlay').onclick = () => cmd('toggle');
$('#btnPrev').onclick = () => cmd('prev');
$('#btnNext').onclick = () => cmd('next');
$('#btnShuffle').onclick = () => cmd('shuffle');
$('#btnRepeat').onclick = () => cmd('repeat');
$('#bVol').onclick = () => volCmd('mute');
$('#bLyrics').onclick = () => setLyricsMode(!lyricsOn);
for (const el of [box, panel]) el.addEventListener('wheel', (e) => { e.preventDefault(); volCmd(e.deltaY < 0 ? 'vol+' : 'vol-'); }, { passive: false });
addEventListener('keydown', (e) => {
  const k = e.key;
  if (k === ' ') { e.preventDefault(); cmd('toggle'); }
  else if (k === 'Escape') full();
  else if (k === 'ArrowRight') cmd('next');
  else if (k === 'ArrowLeft') cmd('prev');
  else if (k === 'ArrowUp') { e.preventDefault(); volCmd('vol+'); }
  else if (k === 'ArrowDown') { e.preventDefault(); volCmd('vol-'); }
  else if (k === 'l' || k === 'L') setLyricsMode(!lyricsOn);
  else if (k === 'm' || k === 'M') volCmd('mute');
  else if (k === 's' || k === 'S') cmd('shuffle');
  else if (k === 'r' || k === 'R') cmd('repeat');
});
// the lyrics' times are of the song: catch up when the window comes back from sleep or a long stall
document.addEventListener('visibilitychange', () => { if (!document.hidden) { tickTime(); lyricsTick(); } });

// ---------- start ----------
hydrateIcons();
pa.onMiniState(apply);
pa.onMiniDrop((d) => { html.dataset.drop = d; });
const hello = await pa.miniHello();
html.dataset.drop = hello.drop;
apply(hello.state);
setLyricsMode(lyricsOn);
document.fonts.ready.then(fit);
