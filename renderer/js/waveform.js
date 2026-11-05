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
  // themes: cyberpunk draws hard-edged cyan bars with a magenta block cursor; y2k subway draws
  // thin sharp pale-teal bars with a mint played side
  setTheme(name) {
    this.theme = name;
    this.cyber = name === 'cyber';
    // the Y2K theme paints with its design tokens
    if (name === 'subway') {
      const cs = getComputedStyle(document.documentElement);
      const v = (k) => cs.getPropertyValue(k).trim();
      this.tok = { teal: v('--teal'), lilac: v('--lilac'), pink: v('--pink'), lo: v('--chrome-lo'), mid: v('--chrome-mid'), hi: v('--chrome-hi') };
    }
    // so does the Vector Surrealism theme
    if (name === 'vector-surrealism') {
      const cs = getComputedStyle(document.documentElement);
      const v = (k) => cs.getPropertyValue(k).trim();
      this.vs = { ember: v('--vs-ember'), blood: v('--vs-blood'), mist: v('--vs-mist'), bone: v('--vs-bone'), gold: v('--vs-gold'), ink: v('--vs-ink'), shade: v('--vs-shade') };
    }
    // and the Liminal theme
    if (name === 'liminal') {
      const cs = getComputedStyle(document.documentElement);
      const v = (k) => cs.getPropertyValue(k).trim();
      this.lm = { rose: v('--lm-rose'), peach: v('--lm-peach'), gold: v('--lm-gold'), cream: v('--lm-cream'), haze: v('--lm-haze') };
    }
    // and the Midnight theme
    if (name === 'midnight') {
      const cs = getComputedStyle(document.documentElement);
      const v = (k) => cs.getPropertyValue(k).trim();
      this.mn = { cyan: v('--mn-cyan'), deep: v('--mn-cyan-deep'), ice: v('--mn-ice'), steel: v('--mn-steel-3'), chrome: v('--mn-chrome'), void: v('--mn-void') };
    }
    // and the Metal theme
    if (name === 'metal') {
      const cs = getComputedStyle(document.documentElement);
      const v = (k) => cs.getPropertyValue(k).trim();
      this.mt = { cu0: v('--mt-cu-0'), cu1: v('--mt-cu-1'), cu2: v('--mt-cu-2'), cu3: v('--mt-cu-3'), cu4: v('--mt-cu-4'), ink: v('--mt-ink'),
        white: v('--mt-white'), alu0: v('--mt-alu-0'), alu3: v('--mt-alu-3'), alu6: v('--mt-alu-6'), void: v('--mt-void') };
    }
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

  // Y2K theme: played bars in a teal-lilac-pink gradient with a glow, unplayed bars in dim chrome,
  // and a small chrome orb with a teal ring for the playhead.
  _drawY2k(g, w, h, prog) {
    const c = this.tok;
    const step = w / BARS, bw = Math.max(1.4, step * 0.55), mid = h * 0.64;
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D();
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2;
      const v = this.cur[i];
      const top = Math.max(1.5, v * (mid - 4)), bot = Math.max(1, v * (h - mid - 3) * 0.6);
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).roundRect(x, mid - top, bw, top, bw / 2);
      (played ? pB : uB).roundRect(x, mid + 2, bw, bot, bw / 2);
    }
    g.fillStyle = c.lo;
    g.globalAlpha = 0.45; g.fill(uT);
    g.globalAlpha = 0.18; g.fill(uB);
    g.globalAlpha = 1;
    const pg = g.createLinearGradient(0, 4, 0, mid);
    pg.addColorStop(0, c.teal); pg.addColorStop(0.5, c.lilac); pg.addColorStop(1, c.pink);
    g.save();
    g.shadowColor = c.teal; g.shadowBlur = 6;
    g.fillStyle = pg; g.fill(pT);
    g.restore();
    g.globalAlpha = 0.3; g.fillStyle = c.pink; g.fill(pB); g.globalAlpha = 1;
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = c.mid; g.globalAlpha = 0.6; g.fillRect(this.hover * w - 0.5, 4, 1, h - 8); g.globalAlpha = 1; }
    // playhead: a 10px chrome orb in a teal ring
    const px = prog * w, r = this.dragging ? 6 : 5;
    g.save();
    g.shadowColor = c.teal; g.shadowBlur = 8;
    g.beginPath(); g.arc(px, mid, r + 1.5, 0, Math.PI * 2); g.strokeStyle = c.teal; g.lineWidth = 1.5; g.stroke();
    g.restore();
    const og = g.createRadialGradient(px - r * 0.35, mid - r * 0.4, 0.5, px, mid, r);
    og.addColorStop(0, c.hi); og.addColorStop(0.55, c.mid); og.addColorStop(1, c.lo);
    g.beginPath(); g.arc(px, mid, r, 0, Math.PI * 2); g.fillStyle = og; g.fill();
  }

  // Vector Surrealism theme: the waveform as a river. A flat ribbon meanders along the bar; its width
  // follows the loudness. The played stretch is ember-to-blood, the rest mist; a hard offset
  // "long shadow" sits under it; the playhead is a small floating dragon's eye.
  _drawVs(g, w, h, prog) {
    const c = this.vs;
    // a river has smooth banks: average the bars down to a few dozen points
    const N = 36, per = BARS / N, step = w / (N - 1), mid = h * 0.5, amp = h * 0.07;
    const cy = (f) => mid + Math.sin(f * Math.PI * 3) * amp; // f: 0..1 along the bar
    const top = [], bot = [];
    for (let i = 0; i < N; i++) {
      let v = 0;
      for (let k = Math.floor(i * per); k < Math.min(BARS, Math.floor((i + 1) * per)); k++) v = Math.max(v, this.cur[k]);
      const t = 2 + v * h * 0.34, y = cy(i / (N - 1));
      top.push([i * step, y - t]);
      bot.push([i * step, y + t * 0.6]);
    }
    // one smooth closed outline: midpoint quadratics along the top edge, back along the bottom
    const river = new Path2D();
    const run = (pts, first) => {
      if (first) river.moveTo(pts[0][0], pts[0][1]); else river.lineTo(pts[0][0], pts[0][1]);
      for (let i = 1; i < pts.length - 1; i++) river.quadraticCurveTo(pts[i][0], pts[i][1], (pts[i][0] + pts[i + 1][0]) / 2, (pts[i][1] + pts[i + 1][1]) / 2);
      const l = pts[pts.length - 1];
      river.lineTo(l[0], l[1]);
    };
    run(top, true);
    run(bot.reverse(), false);
    river.closePath();
    const px = prog * w;
    g.save(); g.translate(3, 3); g.fillStyle = c.shade; g.fill(river); g.restore();
    g.fillStyle = c.mist; g.globalAlpha = 0.42; g.fill(river); g.globalAlpha = 1;
    g.save();
    g.beginPath(); g.rect(0, 0, px, h); g.clip();
    const pg = g.createLinearGradient(0, 0, Math.max(px, 1), 0);
    pg.addColorStop(0, c.ember); pg.addColorStop(1, c.blood);
    g.fillStyle = pg; g.fill(river);
    g.restore();
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = c.bone; g.globalAlpha = 0.55; g.fillRect(this.hover * w - 0.5, 2, 1, h - 4); g.globalAlpha = 1; }
    // the playhead: a gold almond eye with an ember iris and a slit pupil, floating on the river
    const ex = Math.min(Math.max(px, 11), w - 11), ey = cy(prog), r = this.dragging ? 12 : 10;
    const eye = new Path2D();
    eye.moveTo(ex - r, ey); eye.quadraticCurveTo(ex, ey - r * 1.15, ex + r, ey); eye.quadraticCurveTo(ex, ey + r * 1.15, ex - r, ey); eye.closePath();
    g.save(); g.translate(2, 2); g.fillStyle = c.shade; g.fill(eye); g.restore();
    g.fillStyle = c.gold; g.fill(eye);
    g.beginPath(); g.arc(ex, ey, r * 0.44, 0, Math.PI * 2); g.fillStyle = c.ember; g.fill();
    g.beginPath(); g.ellipse(ex, ey, r * 0.11, r * 0.4, 0, 0, Math.PI * 2); g.fillStyle = c.ink; g.fill();
  }

  // Liminal theme: the waveform as late sun glittering on water. Soft bars rise from a waterline and are
  // reflected, fainter, beneath it; the played stretch glows sunset (rose to gold) with a halo and a few
  // fixed glints on its peaks; the rest is lavender haze. The playhead is a small sun with a star glint.
  _drawLm(g, w, h, prog) {
    const c = this.lm;
    const step = w / BARS, bw = Math.max(1.2, step * 0.5), line = Math.round(h * 0.6) + 0.5;
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D();
    const glints = [];
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2, v = this.cur[i];
      const up = Math.max(1.5, v * (line - 4)), down = Math.max(1, v * (h - line - 2) * 0.7);
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).roundRect(x, line - up - 1, bw, up, bw / 2);
      (played ? pB : uB).roundRect(x, line + 1.5, bw, down, bw / 2);
      if (played && v > 0.62 && i % 7 === 3) glints.push([x + bw / 2, line - up - 3, 2 + v * 2.5]);
    }
    g.fillStyle = c.haze;
    g.globalAlpha = 0.34; g.fill(uT);
    g.globalAlpha = 0.12; g.fill(uB);
    g.globalAlpha = 1;
    const px = prog * w;
    const pg = g.createLinearGradient(0, 0, Math.max(px, 1), 0);
    pg.addColorStop(0, c.rose); pg.addColorStop(0.6, c.peach); pg.addColorStop(1, c.gold);
    g.save();
    g.shadowColor = c.peach; g.shadowBlur = 9;
    g.fillStyle = pg; g.fill(pT);
    g.restore();
    g.globalAlpha = 0.3; g.fillStyle = pg; g.fill(pB); g.globalAlpha = 1;
    // a four-pointed glint, like sun catching a wave
    const star = (x, y, r) => {
      g.beginPath();
      g.moveTo(x, y - r * 2); g.quadraticCurveTo(x, y, x + r * 2, y); g.quadraticCurveTo(x, y, x, y + r * 2);
      g.quadraticCurveTo(x, y, x - r * 2, y); g.quadraticCurveTo(x, y, x, y - r * 2); g.fill();
    };
    g.save();
    g.fillStyle = c.cream; g.shadowColor = c.gold; g.shadowBlur = 8;
    for (const [x, y, r] of glints) star(x, y, r);
    g.restore();
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = c.cream; g.globalAlpha = 0.45; g.fillRect(this.hover * w - 0.5, 2, 1, h - 4); g.globalAlpha = 1; }
    // the playhead: a small sun on the waterline, haloed, with a glint across it
    const r = this.dragging ? 6 : 4.5;
    g.save();
    g.shadowColor = c.gold; g.shadowBlur = 16;
    const sg = g.createRadialGradient(px, line, 0, px, line, r);
    sg.addColorStop(0, c.cream); sg.addColorStop(0.6, c.gold); sg.addColorStop(1, c.peach);
    g.fillStyle = sg; g.beginPath(); g.arc(px, line, r, 0, Math.PI * 2); g.fill();
    g.fillStyle = c.cream; star(px, line, r * 0.9);
    g.restore();
  }

  // Midnight theme: the waveform as city lights on a wet street. Thin bars stand on a street line and are
  // reflected beneath it, faint and broken into streaks; the played stretch is lit cyan, the rest is dull
  // steel. The playhead is a thin ice-white light with a chrome stud on the street line.
  _drawMn(g, w, h, prog) {
    const c = this.mn;
    const step = w / BARS, bw = Math.max(1, step * 0.42), line = Math.round(h * 0.62) + 0.5;
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D();
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2, v = this.cur[i];
      const up = Math.max(1.5, v * (line - 4)), down = Math.max(1, v * (h - line - 2) * 0.8);
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).rect(x, line - up - 1, bw, up);
      // the reflection breaks into two streaks, like light on wet asphalt
      (played ? pB : uB).rect(x, line + 2, bw, down * 0.55);
      (played ? pB : uB).rect(x, line + 3 + down * 0.65, bw, down * 0.25);
    }
    g.fillStyle = c.steel;
    g.globalAlpha = 0.42; g.fill(uT);
    g.globalAlpha = 0.12; g.fill(uB);
    g.globalAlpha = 1;
    const px = prog * w;
    const pg = g.createLinearGradient(0, line, 0, 0);
    pg.addColorStop(0, c.deep); pg.addColorStop(0.35, c.cyan); pg.addColorStop(1, c.ice);
    g.save();
    g.shadowColor = c.cyan; g.shadowBlur = 8;
    g.fillStyle = pg; g.fill(pT);
    g.restore();
    g.globalAlpha = 0.28; g.fillStyle = c.cyan; g.fill(pB); g.globalAlpha = 1;
    // the street line
    g.fillStyle = c.chrome; g.globalAlpha = 0.14; g.fillRect(0, line, w, 1); g.globalAlpha = 1;
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = c.chrome; g.globalAlpha = 0.4; g.fillRect(this.hover * w - 0.5, 2, 1, h - 4); g.globalAlpha = 1; }
    // the playhead
    g.save();
    g.shadowColor = c.cyan; g.shadowBlur = 10;
    g.fillStyle = c.ice; g.fillRect(Math.round(px) - 1, 1, 2, h - 2);
    g.restore();
    const r = this.dragging ? 6 : 4.5;
    const sg = g.createLinearGradient(0, line - r, 0, line + r);
    sg.addColorStop(0, c.chrome); sg.addColorStop(0.5, c.steel); sg.addColorStop(1, c.void);
    g.beginPath(); g.arc(px, line, r, 0, Math.PI * 2); g.fillStyle = sg; g.fill();
    g.lineWidth = 1.5; g.strokeStyle = c.cyan; g.stroke();
  }

  // Metal theme: the waveform cut into the aluminium. Unplayed bars are grooves (dark, a light lip under
  // each); played ones are filled with polished copper. A faint reflection lies below the line. The
  // playhead is a hairline of steel with a turned steel stud on the line.
  _drawMt(g, w, h, prog) {
    const c = this.mt;
    const step = w / BARS, bw = Math.max(1.2, step * 0.56), line = Math.round(h * 0.64) + 0.5;
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D(), lip = new Path2D();
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2, v = this.cur[i];
      const up = Math.max(1.5, v * (line - 4)), down = Math.max(1, v * (h - line - 2) * 0.6);
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).rect(x, line - up - 1, bw, up);
      (played ? pB : uB).rect(x, line + 2, bw, down);
      if (!played) lip.rect(x, line - 1, bw, 1);
    }
    // the grooves: dark, with the light catching their lower lip
    g.fillStyle = c.ink; g.globalAlpha = 0.34; g.fill(uT);
    g.globalAlpha = 0.12; g.fill(uB);
    g.fillStyle = c.white; g.globalAlpha = 0.55; g.fill(lip); g.globalAlpha = 1;
    // the copper
    const pg = g.createLinearGradient(0, 2, 0, line);
    pg.addColorStop(0, c.cu0); pg.addColorStop(0.45, c.cu2); pg.addColorStop(1, c.cu3);
    g.fillStyle = pg; g.fill(pT);
    g.fillStyle = c.cu4; g.globalAlpha = 0.5; g.lineWidth = 0.6; g.strokeStyle = c.cu4; g.stroke(pT); g.globalAlpha = 1;
    const rg = g.createLinearGradient(0, line, 0, h);
    rg.addColorStop(0, c.cu2); rg.addColorStop(1, c.alu3);
    g.globalAlpha = 0.35; g.fillStyle = rg; g.fill(pB); g.globalAlpha = 1;
    // the line itself: a fine cut
    g.fillStyle = c.ink; g.globalAlpha = 0.22; g.fillRect(0, line, w, 1);
    g.fillStyle = c.white; g.globalAlpha = 0.6; g.fillRect(0, line + 1, w, 1); g.globalAlpha = 1;
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = c.ink; g.globalAlpha = 0.35; g.fillRect(this.hover * w - 0.5, 2, 1, h - 4); g.globalAlpha = 1; }
    const px = prog * w;
    g.fillStyle = c.ink; g.globalAlpha = 0.75; g.fillRect(Math.round(px) - 0.5, 1, 1, h - 2); g.globalAlpha = 1;
    const r = this.dragging ? 7 : 5.5;
    const sg = g.createLinearGradient(0, line - r, 0, line + r);
    sg.addColorStop(0, c.white); sg.addColorStop(0.5, c.alu0); sg.addColorStop(1, c.alu6);
    g.beginPath(); g.arc(px, line, r, 0, Math.PI * 2); g.fillStyle = sg; g.fill();
    g.lineWidth = 1; g.strokeStyle = c.void; g.globalAlpha = 0.55; g.stroke(); g.globalAlpha = 1;
    g.beginPath(); g.arc(px, line, r * 0.38, 0, Math.PI * 2); g.fillStyle = c.cu3; g.fill();
  }

  // Pixel theme: the waveform as a grid of square cells, like an old LED/pixel equalizer.
  _drawPixel(g, w, h, prog) {
    const cell = 6, gap = 1, pitch = cell + gap;
    const cols = Math.max(8, Math.floor((w + gap) / pitch));
    const rows = Math.max(4, Math.floor((h + gap) / pitch));
    const left = Math.floor((w - (cols * pitch - gap)) / 2);
    const top = Math.floor((h - (rows * pitch - gap)) / 2);
    for (let c = 0; c < cols; c++) {
      const v = this.cur[Math.min(BARS - 1, Math.floor(((c + 0.5) / cols) * BARS))];
      const n = Math.max(1, Math.round(v * rows));
      const played = (c + 0.5) / cols <= prog;
      const x = left + c * pitch;
      for (let r = 0; r < rows; r++) {
        const y = top + (rows - 1 - r) * pitch;
        g.fillStyle = r >= n ? 'rgba(255,255,255,0.07)' : played ? (r === n - 1 ? '#ff2bd6' : '#ffd23f') : 'rgba(236,224,255,0.55)';
        g.fillRect(x, y, cell, cell);
      }
    }
    if (this.hover >= 0 && !this.dragging) { g.fillStyle = 'rgba(255,255,255,0.5)'; g.fillRect(Math.round(this.hover * w), top, 1, rows * pitch - gap); }
    // the playhead: a white bar edged in black, like a pixel cursor
    const px = Math.round(prog * w);
    g.fillStyle = '#120a1f';
    g.fillRect(px - 2, top - 3, 5, rows * pitch - gap + 6);
    g.fillStyle = '#ffffff';
    g.fillRect(px - 1, top - 2, 3, rows * pitch - gap + 4);
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
    if (this.theme === 'pixel') { this._drawPixel(g, w, h, prog); return moving; }
    if (this.theme === 'subway' && this.tok) { this._drawY2k(g, w, h, prog); return moving; }
    if (this.theme === 'vector-surrealism' && this.vs) { this._drawVs(g, w, h, prog); return moving; }
    if (this.theme === 'liminal' && this.lm) { this._drawLm(g, w, h, prog); return moving; }
    if (this.theme === 'midnight' && this.mn) { this._drawMn(g, w, h, prog); return moving; }
    if (this.theme === 'metal' && this.mt) { this._drawMt(g, w, h, prog); return moving; }
    const step = w / BARS;
    const cy = this.cyber;
    const bw = Math.max(1.2, step * (cy ? 0.62 : 0.56));
    const rad = cy ? 0 : bw / 2;
    const mid = h * 0.64;
    const [r, gg, b] = this.accent;
    const light = (k) => `rgb(${Math.round(r + (255 - r) * k)},${Math.round(gg + (255 - gg) * k)},${Math.round(b + (255 - b) * k)})`;
    const dark = (k) => `rgb(${Math.round(r * (1 - k))},${Math.round(gg * (1 - k))},${Math.round(b * (1 - k))})`;
    // batch bars into four paths: played/unplayed x top/reflection
    const pT = new Path2D(), pB = new Path2D(), uT = new Path2D(), uB = new Path2D();
    for (let i = 0; i < BARS; i++) {
      const x = i * step + (step - bw) / 2;
      const v = this.cur[i];
      const top = Math.max(1.5, v * (mid - 4));
      const bot = Math.max(1, v * (h - mid - 3) * 0.6);
      const played = (i + 0.5) / BARS <= prog;
      (played ? pT : uT).roundRect(x, mid - top, bw, top, rad);
      (played ? pB : uB).roundRect(x, mid + 2, bw, bot, rad);
    }
    const px = prog * w;
    // unplayed
    const base = cy ? '0,240,255' : '255,255,255';
    const ug = g.createLinearGradient(0, 0, 0, mid);
    ug.addColorStop(0, `rgba(${base},${cy ? 0.55 : 0.4})`);
    ug.addColorStop(1, `rgba(${base},${cy ? 0.22 : 0.18})`);
    g.fillStyle = ug;
    g.fill(uT);
    g.fillStyle = `rgba(${base},${cy ? 0.1 : 0.08})`;
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
    // hover guide
    if (this.hover >= 0 && !this.dragging) {
      g.fillStyle = 'rgba(255,255,255,0.35)';
      g.fillRect(this.hover * w - 0.5, 4, 1, h - 8);
    }
    // playhead: hairline + glowing knob
    const lg = g.createLinearGradient(0, 0, 0, h);
    lg.addColorStop(0, 'rgba(255,255,255,0)');
    lg.addColorStop(0.5, cy ? 'rgba(255,42,109,1)' : 'rgba(255,255,255,0.95)');
    lg.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = lg;
    g.fillRect(px - 0.75, 0, 1.5, h);
    if (cy) {
      // a square magenta cursor block
      const k = this.dragging ? 7 : 5;
      g.save();
      g.shadowColor = 'rgba(255,42,109,0.9)';
      g.shadowBlur = 12;
      g.fillStyle = '#ff2a6d';
      g.fillRect(px - k, mid - k, k * 2, k * 2);
      g.restore();
      g.fillStyle = '#0a0a12';
      g.fillRect(px - k / 2.5, mid - k / 2.5, k / 1.25, k / 1.25);
      return moving;
    }
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
