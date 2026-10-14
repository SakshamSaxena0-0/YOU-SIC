import { $, $$, clamp, tween, esc } from './util.js';
import { EQ_FREQS } from './audio.js';
import { chooser, prompt, menuAt, toast } from './ui.js';

export const EQ_PRESETS = {
  'Flat': [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  'Bass Boost': [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
  'Bass Extreme': [9, 8, 6, 3, 0, -1, -1, 0, 0, 0],
  'Treble Boost': [0, 0, 0, 0, 0, 1, 3, 5, 6, 7],
  'Bass & Treble': [6, 5, 3, 0, -1, -1, 0, 3, 5, 6],
  'Classical': [4, 3, 2, 1, -1, -1, 0, 2, 3, 4],
  'Dance': [5, 6, 3, 0, 1, 2, 4, 4, 3, 0],
  'Pop': [-1, 0, 2, 4, 5, 4, 2, 0, -1, -1],
  'Rock': [5, 4, 3, 1, -1, -1, 1, 3, 4, 5],
  'Jazz': [3, 2, 1, 2, -2, -2, 0, 1, 3, 4],
  'Electronic': [5, 4, 1, 0, -2, 2, 1, 2, 4, 5],
  'Hip-Hop': [5, 4, 1, 3, -1, -1, 1, -1, 2, 3],
  'Vocal': [-2, -3, -2, 1, 4, 4, 3, 1, 0, -1],
  'Acoustic': [4, 4, 3, 1, 2, 1, 3, 3, 3, 2],
  'Loudness': [6, 4, 0, 0, -2, 0, -1, -5, 5, 1],
  'Headphones': [3, 5, 3, -2, -1, 1, 3, 5, 6, 4],
  'Small Speakers': [7, 6, 5, 3, 1, 0, -1, -2, -2, -3],
  'Lounge': [-3, -1, 0, 2, 4, 3, 0, -2, 2, 1],
  'Piano': [3, 2, 0, 2, 3, 1, 4, 4, 3, 4],
  'Deep': [4, 3, 2, 1, 3, 2, 1, -2, -4, -5],
  'Spoken Word': [-4, -1, 0, 1, 4, 5, 5, 4, 2, -3],
};

export const REVERB_PRESETS = {
  'Small Room': { mix: 0.18, size: 0.12, damp: 0.6, filter: 0.15, fade: 0.3, predelay: 0.04, predelayMix: 0.05 },
  'Medium Room': { mix: 0.24, size: 0.28, damp: 0.5, filter: 0.12, fade: 0.4, predelay: 0.08, predelayMix: 0.08 },
  'Large Room': { mix: 0.28, size: 0.42, damp: 0.45, filter: 0.1, fade: 0.5, predelay: 0.12, predelayMix: 0.1 },
  'Hall': { mix: 0.3, size: 0.55, damp: 0.5, filter: 0.1, fade: 0.55, predelay: 0.2, predelayMix: 0.1 },
  'Large Hall': { mix: 0.34, size: 0.75, damp: 0.4, filter: 0.08, fade: 0.65, predelay: 0.28, predelayMix: 0.12 },
  'Plate': { mix: 0.26, size: 0.35, damp: 0.15, filter: 0.25, fade: 0.45, predelay: 0.02, predelayMix: 0.0 },
  'Cathedral': { mix: 0.42, size: 1.0, damp: 0.35, filter: 0.1, fade: 0.8, predelay: 0.35, predelayMix: 0.15 },
  'Arena': { mix: 0.36, size: 0.85, damp: 0.55, filter: 0.2, fade: 0.6, predelay: 0.6, predelayMix: 0.3 },
};

const RANGE = 15;

// Rotary knob with a 270° arc.
class Knob {
  static TICKS = 31;
  constructor({ label, min, max, def, step = 0.1, bipolar = false, fmt, onChange }) {
    Object.assign(this, { label, min, max, def, step, bipolar, fmt, onChange });
    this.value = def;
    const el = (this.el = document.createElement('div'));
    el.className = 'knob';
    // LED ladder: 31 ticks over a 270° sweep, lit up to the current value
    const ticks = [];
    for (let i = 0; i < Knob.TICKS; i++) {
      const a = (-135 + (i / (Knob.TICKS - 1)) * 270 - 90) * Math.PI / 180;
      const p = (r) => `${(50 + Math.cos(a) * r).toFixed(2)} ${(50 + Math.sin(a) * r).toFixed(2)}`;
      ticks.push(`<path class="k-tick" d="M${p(41)} L${p(48)}"/>`);
    }
    el.innerHTML = `<div class="k-body"><svg viewBox="0 0 100 100">${ticks.join('')}</svg><div class="k-cap"></div><div class="k-shine"></div></div>
      <div class="k-value"></div><div class="k-label">${esc(label)}</div>`;
    this.body = el.querySelector('.k-body');
    this.cap = el.querySelector('.k-cap');
    this.ticks = [...el.querySelectorAll('.k-tick')];
    this.vEl = el.querySelector('.k-value');
    this._bind();
    this.set(def, false);
  }

  _bind() {
    let sy, sv, id = null;
    const svg = this.body;
    svg.addEventListener('pointerdown', (e) => { id = e.pointerId; sy = e.clientY; sv = this.value; svg.setPointerCapture(id); this.el.classList.add('active'); });
    svg.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      const k = (this.max - this.min) / (e.shiftKey ? 600 : 180);
      this.set(sv + (sy - e.clientY) * k);
    });
    const up = () => { id = null; this.el.classList.remove('active'); };
    svg.addEventListener('pointerup', up);
    svg.addEventListener('pointercancel', up);
    svg.addEventListener('dblclick', () => this.animateTo(this.def));
    svg.addEventListener('wheel', (e) => { e.preventDefault(); this.set(this.value + (e.deltaY < 0 ? 1 : -1) * this.step * (e.shiftKey ? 1 : 5)); }, { passive: false });
    this.vEl.addEventListener('dblclick', async () => {
      const v = await prompt(this.label, String(Math.round(this.value * 100) / 100));
      if (v != null && !isNaN(+v)) this.set(+v);
    });
    this.vEl.title = 'Double-click to enter a value';
  }

  set(v, fire = true) {
    v = clamp(Math.round(v / this.step) * this.step, this.min, this.max);
    this.value = v;
    const t = (v - this.min) / (this.max - this.min);
    const ang = -135 + t * 270;
    const n = Knob.TICKS - 1;
    const cur = Math.round(t * n);
    const origin = this.bipolar ? Math.round(((this.def - this.min) / (this.max - this.min)) * n) : 0;
    const lo = Math.min(origin, cur), hi = Math.max(origin, cur);
    this.ticks.forEach((tk, i) => {
      tk.classList.toggle('on', this.bipolar ? cur !== origin && i >= lo && i <= hi && i !== origin : i <= cur);
      tk.classList.toggle('zero', this.bipolar && i === origin);
    });
    this.cap.style.transform = `rotate(${ang}deg)`;
    this.vEl.textContent = this.fmt ? this.fmt(v) : v.toFixed(1);
    this.el.classList.toggle('nonzero', Math.abs(v - this.def) > 1e-6);
    if (fire) this.onChange(v);
  }

  animateTo(v) {
    if (this._cancel) this._cancel();
    this._cancel = tween(this.value, v, 380, (x) => this.set(x));
  }
}

const dbFmt = (v) => (v > 0 ? '+' : '') + v.toFixed(1) + ' dB';

export class EqView {
  constructor(app) {
    this.app = app;
    this.engine = app.engine;
    this.store = app.store;
    this.s = this.store.settings.eq;
    this.cv = $('#eqCanvas');
    this.g = this.cv.getContext('2d');
    // shadow filters compute the target response instantly (the live chain ramps smoothly)
    this.shadowCtx = new OfflineAudioContext(1, 1, 48000);
    this.shadow = EQ_FREQS.map((f, i) => {
      const b = this.shadowCtx.createBiquadFilter();
      b.type = this.engine.bands[i].type; b.frequency.value = f; b.Q.value = this.engine.bands[i].Q.value;
      return b;
    });
    this.shadowBass = this.shadowCtx.createBiquadFilter(); this.shadowBass.type = 'lowshelf'; this.shadowBass.frequency.value = this.engine.bass.frequency.value;
    this.shadowTreble = this.shadowCtx.createBiquadFilter(); this.shadowTreble.type = 'highshelf'; this.shadowTreble.frequency.value = this.engine.treble.frequency.value;
    // x axis is octave-spaced so band i sits at the center of column i (31 Hz ... 16 kHz)
    this.freqs = new Float32Array(256).map((_, i) => 31.25 * Math.pow(2, (i / 255) * 10 - 0.5));

    this._buildBands();
    this._buildKnobs();
    this._buildReverb();
    this._bindHeader();
    new ResizeObserver(() => this._resize()).observe($('#eqGraph'));
    this.applyAll();
  }

  applyAll() {
    const s = this.s, e = this.engine;
    s.bands.forEach((v, i) => e.setBand(i, v));
    e.setPreamp(s.preamp); e.setBass(s.bass); e.setTreble(s.treble); e.setBalance(s.balance); e.setTempo(s.tempo);
    e.setOption('eq', s.enabled); e.setOption('tone', s.tone); e.setOption('limiter', s.limiter); e.setOption('reverb', s.reverb);
    e.setReverb(s.rev);
    this._syncToggles();
    this._syncBands();
    $('#presetName').textContent = s.preset || 'Custom';
  }

  save() { this.store.saveSettings(); }

  // ---------- bands ----------
  _buildBands() {
    const host = $('#eqBands');
    host.innerHTML = EQ_FREQS.map((f, i) => `<div class="band" data-i="${i}">
      <div class="b-val"></div><div class="b-track"><div class="b-fill"></div><div class="b-thumb"></div></div>
      <div class="b-freq">${f >= 1000 ? f / 1000 + 'k' : f}</div></div>`).join('');
    this.bandEls = $$('.band', host);
    this.bandEls.forEach((el, i) => {
      const tr = el.querySelector('.b-track');
      let id = null;
      const setFrom = (e) => {
        const r = tr.getBoundingClientRect();
        const t = clamp((e.clientY - r.top) / r.height, 0, 1);
        const v = Math.round((RANGE - t * RANGE * 2) * 2) / 2;
        this.setBand(i, Math.abs(v) < 0.6 ? 0 : v);
      };
      el.addEventListener('pointerdown', (e) => { id = e.pointerId; el.setPointerCapture(id); el.classList.add('active'); setFrom(e); });
      el.addEventListener('pointermove', (e) => { if (e.pointerId === id) setFrom(e); });
      const up = () => { id = null; el.classList.remove('active'); this.save(); };
      el.addEventListener('pointerup', up);
      el.addEventListener('pointercancel', up);
      el.addEventListener('dblclick', () => this.animateBands(this.s.bands.map((v, j) => (j === i ? 0 : v))));
      el.addEventListener('wheel', (e) => { e.preventDefault(); this.setBand(i, clamp(this.s.bands[i] + (e.deltaY < 0 ? 0.5 : -0.5), -RANGE, RANGE)); this.save(); }, { passive: false });
    });
  }

  setBand(i, v, markCustom = true) {
    this.s.bands[i] = v;
    this.engine.setBand(i, v);
    if (markCustom && this.s.preset !== 'Custom') { this.s.preset = 'Custom'; $('#presetName').textContent = 'Custom'; }
    if (markCustom && !this.s.enabled) { this.s.enabled = true; this.engine.setOption('eq', true); this._syncToggles(); }
    this._syncBand(i);
    this._redraw();
  }

  _syncBand(i) {
    const v = this.s.bands[i];
    const el = this.bandEls[i];
    const t = (RANGE - v) / (RANGE * 2);
    el.querySelector('.b-thumb').style.top = `${t * 100}%`;
    const fill = el.querySelector('.b-fill');
    fill.style.top = `${Math.min(t, 0.5) * 100}%`;
    fill.style.height = `${Math.abs(t - 0.5) * 100}%`;
    el.querySelector('.b-val').textContent = (v > 0 ? '+' : '') + (Math.round(v * 10) / 10);
    el.classList.toggle('nonzero', v !== 0);
  }

  _syncBands() { EQ_FREQS.forEach((_, i) => this._syncBand(i)); this._redraw(); }

  animateBands(to, name) {
    if (this._cancelTween) this._cancelTween();
    const from = this.s.bands.slice();
    this._cancelTween = tween(from, to, 480, (vals) => vals.forEach((v, i) => this.setBand(i, Math.round(v * 10) / 10, false)), undefined, () => this.save());
    if (name) { this.s.preset = name; $('#presetName').textContent = name; }
    if (!this.s.enabled) { this.s.enabled = true; this.engine.setOption('eq', true); this._syncToggles(); }
  }

  // ---------- knobs ----------
  _buildKnobs() {
    const s = this.s, e = this.engine;
    const host = $('#eqKnobs');
    this.knobs = {
      preamp: new Knob({ label: 'Preamp', min: -15, max: 15, def: 0, step: 0.5, bipolar: true, fmt: dbFmt, onChange: (v) => { s.preamp = v; e.setPreamp(v); this.save(); } }),
      bass: new Knob({ label: 'Bass', min: -12, max: 12, def: 0, step: 0.5, bipolar: true, fmt: dbFmt, onChange: (v) => { s.bass = v; e.setBass(v); this._toneOn(); this._redraw(); this.save(); } }),
      treble: new Knob({ label: 'Treble', min: -12, max: 12, def: 0, step: 0.5, bipolar: true, fmt: dbFmt, onChange: (v) => { s.treble = v; e.setTreble(v); this._toneOn(); this._redraw(); this.save(); } }),
      balance: new Knob({ label: 'Balance', min: -1, max: 1, def: 0, step: 0.02, bipolar: true, fmt: (v) => (Math.abs(v) < 0.01 ? 'C' : (v < 0 ? 'L ' : 'R ') + Math.round(Math.abs(v) * 100)), onChange: (v) => { s.balance = v; e.setBalance(v); this.save(); } }),
      tempo: new Knob({ label: 'Tempo', min: 0.5, max: 3, def: 1, step: 0.05, fmt: (v) => v.toFixed(2) + 'x', onChange: (v) => { s.tempo = v; e.setTempo(v); this.save(); } }),
    };
    for (const [k, kn] of Object.entries(this.knobs)) { kn.set(s[k], false); host.appendChild(kn.el); }
  }

  _toneOn() { if (!this.s.tone) { this.s.tone = true; this.engine.setOption('tone', true); this._syncToggles(); } }

  _buildReverb() {
    const s = this.s, e = this.engine;
    const pct = (v) => Math.round(v * 100) + '%';
    const defs = [['mix', 'Mix'], ['size', 'Size'], ['damp', 'Damp'], ['filter', 'Filter'], ['fade', 'Fade'], ['predelay', 'Pre-Delay'], ['predelayMix', 'Pre-Delay Mix']];
    const host = $('#reverbKnobs');
    this.revKnobs = {};
    for (const [k, label] of defs) {
      const kn = new Knob({
        label, min: 0, max: 1, def: s.rev[k], step: 0.01,
        fmt: k === 'predelay' ? (v) => Math.round(v * 250) + ' ms' : pct,
        onChange: (v) => {
          s.rev[k] = v; e.setReverb({ [k]: v }); s.revPreset = 'Custom'; this._syncRevPresets();
          if (!s.reverb) { s.reverb = true; e.setOption('reverb', true); this._syncToggles(); }
          this.save();
        },
      });
      kn.set(s.rev[k], false);
      this.revKnobs[k] = kn;
      host.appendChild(kn.el);
    }
    const ph = $('#reverbPresets');
    ph.innerHTML = Object.keys(REVERB_PRESETS).map((n) => `<button class="chip rp lg" data-lg="static" data-n="${esc(n)}">${esc(n)}</button>`).join('');
    ph.onclick = (ev) => {
      const b = ev.target.closest('.rp');
      if (!b) return;
      const p = REVERB_PRESETS[b.dataset.n];
      for (const [k, v] of Object.entries(p)) this.revKnobs[k].animateTo(v);
      setTimeout(() => { s.revPreset = b.dataset.n; this._syncRevPresets(); this.save(); }, 400);
      if (!s.reverb) { s.reverb = true; e.setOption('reverb', true); this._syncToggles(); }
    };
    this._syncRevPresets();
  }

  _syncRevPresets() { $$('#reverbPresets .rp').forEach((b) => b.classList.toggle('on', b.dataset.n === this.s.revPreset)); }

  // ---------- header / toggles / presets ----------
  _bindHeader() {
    const s = this.s, e = this.engine;
    const tg = (id, key, opt) => {
      $(id).onclick = () => { s[key] = !s[key]; e.setOption(opt, s[key]); this._syncToggles(); this._redraw(); this.save(); toast(`${$(id).textContent} ${s[key] ? 'On' : 'Off'}`, 1200); };
    };
    tg('#tglEq', 'enabled', 'eq'); tg('#tglTone', 'tone', 'tone'); tg('#tglLimiter', 'limiter', 'limiter'); tg('#tglReverb', 'reverb', 'reverb');
    $('#presetChip').onclick = () => this.choosePreset();
    $('#eqMenu').onclick = (ev) => menuAt(ev.currentTarget, [
      { label: 'Save Preset…', icon: 'save', action: () => this.savePreset() },
      { label: 'Delete Preset', icon: 'delete', disabled: !s.userPresets.some((p) => p.name === s.preset), action: () => { s.userPresets = s.userPresets.filter((p) => p.name !== s.preset); s.preset = 'Custom'; $('#presetName').textContent = 'Custom'; this.save(); } },
      '-',
      { label: 'Reset Equalizer', icon: 'refresh', action: () => { this.animateBands(EQ_PRESETS.Flat, 'Flat'); ['preamp', 'bass', 'treble', 'balance', 'tempo'].forEach((k) => this.knobs[k].animateTo(this.knobs[k].def)); } },
    ]);
    const tabs = $$('.eq-tab');
    tabs.forEach((t) => (t.onclick = () => this.tab(t.dataset.tab)));
    this.tab('equ', false);
    // tab widths change once the custom font has loaded
    document.fonts.ready.then(() => this.tab(this.curTab || 'equ', false));
    addEventListener('resize', () => this.tab(this.curTab || 'equ', false));
  }

  tab(name, animate = true) {
    this.curTab = name;
    const tabs = $$('.eq-tab');
    tabs.forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
    const act = tabs.find((t) => t.dataset.tab === name);
    const ink = $('.eq-tab-ink');
    requestAnimationFrame(() => { ink.style.width = `${act.offsetWidth}px`; ink.style.transform = `translateX(${act.offsetLeft}px)`; });
    $('#eqPages').style.setProperty('--page', name === 'equ' ? 0 : 1);
    $('#eqPages').classList.toggle('no-anim', !animate);
    $('#presetChip').style.visibility = name === 'equ' ? '' : 'hidden';
  }

  _syncToggles() {
    const s = this.s;
    $('#tglEq').classList.toggle('on', s.enabled);
    $('#tglTone').classList.toggle('on', s.tone);
    $('#tglLimiter').classList.toggle('on', s.limiter);
    $('#tglReverb').classList.toggle('on', s.reverb);
    $('#eqGraph').classList.toggle('off', !s.enabled);
  }

  async choosePreset() {
    const s = this.s;
    const opts = [
      ...s.userPresets.map((p) => ({ label: p.name, value: 'u:' + p.name, icon: 'star' })),
      ...Object.keys(EQ_PRESETS).map((n) => ({ label: n, value: n, icon: 'equalizer' })),
    ];
    const cur = s.userPresets.some((p) => p.name === s.preset) ? 'u:' + s.preset : s.preset;
    const v = await chooser('Equalizer presets', opts, cur);
    if (!v) return;
    if (v.startsWith('u:')) {
      const p = s.userPresets.find((x) => x.name === v.slice(2));
      this.animateBands(p.bands, p.name);
      if (p.preamp != null) this.knobs.preamp.animateTo(p.preamp);
      if (p.bass != null) this.knobs.bass.animateTo(p.bass);
      if (p.treble != null) this.knobs.treble.animateTo(p.treble);
    } else this.animateBands(EQ_PRESETS[v], v);
  }

  async savePreset() {
    const s = this.s;
    const name = await prompt('Save Preset', s.preset !== 'Custom' && !EQ_PRESETS[s.preset] ? s.preset : '', 'Preset name');
    if (!name) return;
    s.userPresets = s.userPresets.filter((p) => p.name !== name);
    s.userPresets.unshift({ name, bands: s.bands.slice(), preamp: s.preamp, bass: s.bass, treble: s.treble });
    s.preset = name;
    $('#presetName').textContent = name;
    this.save();
    toast(`Preset "${name}" saved`);
  }

  // ---------- graph ----------
  _resize() {
    const r = this.cv.getBoundingClientRect();
    const dpr = devicePixelRatio || 1;
    this.cv.width = r.width * dpr; this.cv.height = r.height * dpr;
    this.w = r.width; this.h = r.height; this.dpr = dpr;
    this._redraw();
  }

  onAccent() { this._redraw(); }

  _redraw() {
    if (this._raf) return;
    this._raf = requestAnimationFrame(() => { this._raf = 0; this._draw(); });
  }

  _draw() {
    const { g, w, h, dpr } = this;
    if (!w) return;
    const s = this.s;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const padT = 26, padB = 26;
    const ih = h - padT - padB;
    const y = (db) => padT + ih / 2 - (db / (RANGE + 3)) * ih / 2;
    // grid: cyan HUD lines (cyberpunk), faint teal lines (subway)
    const cl = document.documentElement.classList;
    const cy = cl.contains('theme-cyber'), sw = cl.contains('theme-subway');
    g.strokeStyle = cy ? 'rgba(0,240,255,0.14)' : sw ? 'rgba(150,225,210,0.1)' : 'rgba(255,255,255,0.06)';
    g.lineWidth = 1;
    for (const db of [-12, -6, 0, 6, 12]) {
      g.beginPath(); g.moveTo(0, y(db)); g.lineTo(w, y(db)); g.stroke();
    }
    g.fillStyle = cy ? 'rgba(0,240,255,0.55)' : sw ? 'rgba(170,230,218,0.5)' : 'rgba(255,255,255,0.25)';
    g.font = cy ? '10px "JetBrains Mono", monospace' : sw ? 'bold 10px Arial, sans-serif' : '10px "Segoe UI", sans-serif';
    for (const db of [-12, 0, 12]) g.fillText((db > 0 ? '+' : '') + db, 4, y(db) - 3);
    // response
    const total = new Float32Array(this.freqs.length);
    const mag = new Float32Array(this.freqs.length), ph = new Float32Array(this.freqs.length);
    const stages = [];
    if (s.enabled) this.shadow.forEach((b, i) => { b.gain.value = s.bands[i]; stages.push(b); });
    if (s.tone) { this.shadowBass.gain.value = s.bass; this.shadowTreble.gain.value = s.treble; stages.push(this.shadowBass, this.shadowTreble); }
    for (const st of stages) {
      st.getFrequencyResponse(this.freqs, mag, ph);
      for (let i = 0; i < total.length; i++) total[i] += 20 * Math.log10(mag[i] || 1e-6);
    }
    const ac = getComputedStyle(document.documentElement).getPropertyValue('--accent-rgb').trim() || '255,159,28';
    const xs = (i) => (i / (total.length - 1)) * w;
    g.beginPath();
    g.moveTo(0, y(clamp(total[0], -18, 18)));
    for (let i = 1; i < total.length; i++) g.lineTo(xs(i), y(clamp(total[i], -18, 18)));
    const grad = g.createLinearGradient(0, padT, 0, h - padB);
    grad.addColorStop(0, `rgba(${ac},0.35)`);
    grad.addColorStop(0.5, `rgba(${ac},0.08)`);
    grad.addColorStop(1, `rgba(${ac},0.30)`);
    g.save();
    g.lineTo(w, y(0)); g.lineTo(0, y(0)); g.closePath();
    g.fillStyle = grad; g.fill();
    g.restore();
    g.beginPath();
    g.moveTo(0, y(clamp(total[0], -18, 18)));
    for (let i = 1; i < total.length; i++) g.lineTo(xs(i), y(clamp(total[i], -18, 18)));
    g.strokeStyle = `rgba(${ac},0.95)`;
    g.lineWidth = 2.2;
    g.shadowColor = `rgba(${ac},0.7)`;
    g.shadowBlur = 12;
    g.stroke();
    g.shadowBlur = 0;
  }
}
