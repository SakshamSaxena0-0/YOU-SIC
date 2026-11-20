import { hashStr, rng, fmtTime, clamp, mediaUrl } from './util.js';

const BARS = 140;
const peakCache = new Map();
let decodeCtx = null;
let decodeQueue = Promise.resolve();

function fakePeaks(id) {
  const r = rng(hashStr(id));
  const out = new Float32Array(BARS);
  let v = 0.5;
  for (let i = 0; i < BARS; i++) {
    v = clamp(v + (r() - 0.5) * 0.35, 0.15, 0.95);
    out[i] = v * 0.35 + 0.08;
  }
  return out;
}

// Decode the track at a low sample rate and compute normalized RMS peaks per bar.
async function computePeaks(track) {
  if (peakCache.has(track.id)) return peakCache.get(track.id);
  const job = decodeQueue.then(async () => {
    const res = await fetch(mediaUrl(track.path));
    const buf = await res.arrayBuffer();
    decodeCtx = decodeCtx || new OfflineAudioContext(1, 1, 8000);
    const audio = await decodeCtx.decodeAudioData(buf);
    const ch = audio.numberOfChannels;
    const len = audio.length;
    const data = [];
    for (let c = 0; c < ch; c++) data.push(audio.getChannelData(c));
    const per = Math.max(1, Math.floor(len / BARS));
    const out = new Float32Array(BARS);
    let max = 0;
    for (let b = 0; b < BARS; b++) {
      let sum = 0, pk = 0;
      const start = b * per;
      for (let i = start; i < start + per && i < len; i += 2) {
        let s = 0;
        for (let c = 0; c < ch; c++) s += Math.abs(data[c][i]);
        s /= ch;
        sum += s * s;
        if (s > pk) pk = s;
      }
      const rms = Math.sqrt(sum / (per / 2));
      out[b] = rms * 0.7 + pk * 0.3;
      if (out[b] > max) max = out[b];
    }
    for (let b = 0; b < BARS; b++) out[b] = Math.pow(out[b] / (max || 1), 0.8) * 0.92 + 0.06;
    if (peakCache.size > 60) peakCache.delete(peakCache.keys().next().value);
    peakCache.set(track.id, out);
    return out;
  });
  decodeQueue = job.catch(() => {});
  return job;
}

export class Waveform {
  constructor(canvas, bubble, engine, { onSeek }) {
    this.cv = canvas;
    this.g = canvas.getContext('2d');
    this.bubble = bubble;
    this.engine = engine;
    this.onSeek = onSeek;
    this.cur = new Float32Array(BARS).fill(0.05);
    this.target = new Float32Array(BARS).fill(0.05);
    this.accent = [255, 159, 28];
    this.dragging = false;
    this.dragX = 0;
    this.hover = -1;
    this.enabled = true;
    this.trackId = null;
    this._raf = 0;
    this._px = -1;
    new ResizeObserver(() => this._resize()).observe(canvas);
    this._bind();
    document.addEventListener('visibilitychange', () => this.invalidate());
  }

  // Draw on the next frame. Frames are only requested while something visibly changes
  // (bars morphing, dragging, hover, the playhead crossing a pixel), so an idle player costs nothing.
  invalidate() {
    if (this._raf) return;
    this._raf = requestAnimationFrame(() => { this._raf = 0; if (this._draw()) this.invalidate(); });
  }

  // Called by the player's progress ticker: redraw only when the playhead has moved a whole device pixel.
  sync() {
    if (this.dragging || !this.w) return;
    const d = this.engine.duration;
    const px = Math.round((d ? this.engine.currentTime / d : 0) * this.w * this.dpr);
    if (px !== this._px) this.invalidate();
  }

  _resize() {
    const r = this.cv.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.cv.width = Math.round(r.width * dpr);
    this.cv.height = Math.round(r.height * dpr);
    this.w = r.width; this.h = r.height; this.dpr = dpr;
    this.invalidate();
  }

  setTrack(track) {
    this.trackId = track.id;
    this.target = fakePeaks(track.id).map((v) => v * 0.6);
    this.invalidate();
    if (!this.enabled) { this.target = new Float32Array(BARS).fill(0.18); return; }
    const id = track.id;
    setTimeout(() => {
      if (this.trackId !== id) return;
      computePeaks(track).then((p) => { if (this.trackId === id) { this.target = p; this.invalidate(); } }).catch(() => {
        if (this.trackId === id) { this.target = fakePeaks(id); this.invalidate(); }
      });
    }, 350);
  }

  setAccent(rgb) { this.accent = rgb; this.invalidate(); }
  setTheme(name) {
    this.theme = name;
    this.invalidate();
  }

  _bind() {
    const pos = (e) => clamp((e.clientX - this.cv.getBoundingClientRect().left) / this.w, 0, 1);
    this.cv.addEventListener('pointerdown', (e) => {
      if (!this.engine.duration) return;
      this.dragging = true;
      this.cv.setPointerCapture(e.pointerId);
      this.dragX = pos(e);
      this._bubble(e, this.dragX);
      this.invalidate();
    });
    this.cv.addEventListener('pointermove', (e) => {
      this.hover = pos(e);
      this.invalidate();
      // hovering shows the time under the pointer; dragging shows the time you will land on
      if (!this.dragging) { if (this.engine.duration) this._bubble(e, this.hover); return; }
      this.dragX = pos(e);
      this._bubble(e, this.dragX);
    });
    this.cv.addEventListener('pointerleave', () => {
      this.hover = -1;
      if (!this.dragging) this.bubble.classList.remove('show');
      this.invalidate();
    });
    const up = (e) => {
      if (!this.dragging) return;
      this.dragging = false;
      this.bubble.classList.remove('show');
      this.onSeek(pos(e) * this.engine.duration);
      this.invalidate();
    };
    this.cv.addEventListener('pointerup', up);
    this.cv.addEventListener('pointercancel', () => { this.dragging = false; this.bubble.classList.remove('show'); this.invalidate(); });
    this.cv.addEventListener('wheel', (e) => {
      e.preventDefault();
      this.onSeek(this.engine.currentTime + (e.deltaY > 0 ? -5 : 5));
      this.invalidate();
    }, { passive: false });
  }

  _bubble(e, x) {
    this.bubble.textContent = fmtTime(x * this.engine.duration);
    this.bubble.style.left = `${x * this.w}px`;
    this.bubble.classList.add('show');
  }

  // Returns true while the bars are still morphing (needs another frame).
  _draw() {
    const { g, w, h, dpr } = this;
    if (!w || document.hidden) return false;
    // morph bars toward target
    let moving = false;
    for (let i = 0; i < BARS; i++) {
      const dv = this.target[i] - this.cur[i];
      if (Math.abs(dv) > 0.002) { this.cur[i] += dv * 0.12; moving = true; } else this.cur[i] = this.target[i];
    }
    const dur = this.engine.duration;
    const prog = this.dragging ? this.dragX : dur ? this.engine.currentTime / dur : 0;
    this._px = Math.round(prog * w * dpr);
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const step = w / BARS;
    const bw = Math.max(1.2, step * 0.56);
    const rad = bw / 2;
    const mid = h * 0.64;
    const [r, gg, b] = this.accent;
    const light = (k) => `rgb(${Math.round(r + (255 - r) * k)},${Math.round(gg + (255 - gg) * k)},${Math.round(b + (255 - b) * k)})`;
    const dark = (k) => `rgb(${Math.round(r * (1 - k))},${Math.round(gg * (1 - k))},${Math.round(b * (1 - k))})`;
    // batch bars into four paths: played/unplayed x top/reflection
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D();
    // the bar under the pointer is drawn on its own, lit (with the time over it: .seek-bubble)
    const hi = this.hover >= 0 && !this.dragging ? Math.min(BARS - 1, Math.floor(this.hover * BARS)) : -1;
    let hiBar = null;
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2;
      const v = this.cur[i];
      const top = Math.max(1.5, v * (mid - 4));
      const bot = Math.max(1, v * (h - mid - 3) * 0.6);
      if (i === hi) { hiBar = [x, top, bot]; continue; }
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).roundRect(x, mid - top, bw, top, rad);
      (played ? pB : uB).roundRect(x, mid + 2, bw, bot, rad);
    }
    const px = prog * w;
    // unplayed
    const ug = g.createLinearGradient(0, 0, 0, mid);
    ug.addColorStop(0, 'rgba(255,255,255,0.4)');
    ug.addColorStop(1, 'rgba(255,255,255,0.18)');
    g.fillStyle = ug;
    g.fill(uT);
    g.fillStyle = 'rgba(255,255,255,0.08)';
    g.fill(uB);
    // played: horizontal accent gradient with glow
    const pg = g.createLinearGradient(0, 0, Math.max(px, 1), 0);
    pg.addColorStop(0, dark(0.35));
    pg.addColorStop(0.6, `rgb(${r},${gg},${b})`);
    pg.addColorStop(1, light(0.45));
    g.save();
    g.shadowColor = `rgba(${r},${gg},${b},0.75)`;
    g.shadowBlur = 10;
    g.fillStyle = pg;
    g.fill(pT);
    g.restore();
    g.globalAlpha = 0.32;
    g.fillStyle = pg;
    g.fill(pB);
    g.globalAlpha = 1;
    // hover: the guide, and the bar under it lit white
    if (this.hover >= 0 && !this.dragging) {
      g.fillStyle = 'rgba(255,255,255,0.35)';
      g.fillRect(this.hover * w - 0.5, 4, 1, h - 8);
    }
    if (hiBar) {
      const [x, top, bot] = hiBar;
      g.save();
      g.shadowColor = 'rgba(255,255,255,0.7)';
      g.shadowBlur = 8;
      g.fillStyle = '#fff';
      g.beginPath(); g.roundRect(x - 0.5, mid - top - 1, bw + 1, top + 1, rad); g.fill();
      g.restore();
      g.fillStyle = 'rgba(255,255,255,0.4)';
      g.beginPath(); g.roundRect(x, mid + 2, bw, bot, rad); g.fill();
    }
    // playhead: hairline + glowing knob
    const lg = g.createLinearGradient(0, 0, 0, h);
    lg.addColorStop(0, 'rgba(255,255,255,0)');
    lg.addColorStop(0.5, 'rgba(255,255,255,0.95)');
    lg.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = lg;
    g.fillRect(px - 0.75, 0, 1.5, h);
    g.save();
    g.shadowColor = `rgba(${r},${gg},${b},1)`;
    g.shadowBlur = 14;
    g.beginPath();
    g.arc(px, mid, this.dragging ? 7 : 5.5, 0, Math.PI * 2);
    g.fillStyle = '#fff';
    g.fill();
    g.restore();
    g.beginPath();
    g.arc(px, mid, this.dragging ? 3 : 2.3, 0, Math.PI * 2);
    g.fillStyle = `rgb(${r},${gg},${b})`;
    g.fill();
    return moving;
  }
}
