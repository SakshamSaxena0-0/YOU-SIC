import { $, esc } from './util.js';
import { icon, hydrateIcons } from './icons.js';
import { chooser, toast } from './ui.js';

function loadScript(src) {
  return new Promise((res, rej) => {
    if (document.querySelector(`script[src="${src}"]`)) return res();
    const s = document.createElement('script');
    s.src = src; s.onload = res; s.onerror = rej;
    document.head.appendChild(s);
  });
}

// MilkDrop visualization (butterchurn) rendered over the album art area.
export class VisView {
  constructor(app) {
    this.app = app;
    this.engine = app.engine;
    this.s = app.store.settings.vis;
    this.host = $('#visHost');
    this.open = false;
    this.viz = null;
    this.names = [];
    this.host.innerHTML = `<canvas class="vis-canvas"></canvas>
      <div class="vis-ui">
        <div class="vis-top"><span class="vis-name"></span></div>
        <div class="vis-bottom">
          <button class="icon-btn" data-v="prev" title="Previous preset (←)">${icon('skip_previous')}</button>
          <button class="icon-btn" data-v="list" title="Preset list">${icon('list')}</button>
          <button class="icon-btn" data-v="mode" title="Preset mode">${icon('shuffle')}</button>
          <button class="icon-btn" data-v="full" title="Fullscreen (F)">${icon('fullscreen')}</button>
          <button class="icon-btn" data-v="next" title="Next preset (→)">${icon('skip_next')}</button>
          <button class="icon-btn" data-v="close" title="Close (V)">${icon('close')}</button>
        </div>
      </div>`;
    this.canvas = this.host.querySelector('canvas');
    this.nameEl = this.host.querySelector('.vis-name');
    this.host.querySelector('.vis-bottom').onclick = (e) => {
      const b = e.target.closest('[data-v]');
      if (!b) return;
      e.stopPropagation();
      ({ prev: () => this.step(-1), next: () => this.step(1), list: () => this.chooseList(), mode: () => this.toggleMode(),
        full: () => this.toggleFull(), close: () => this.app.toggleVis(false) })[b.dataset.v]();
    };
    this._bindSwipe();
    new ResizeObserver(() => this._resize()).observe(this.host);
  }

  async ensure() {
    if (this.viz || this.fallback) return;
    try {
      await loadScript('app://local/node_modules/butterchurn/lib/butterchurn.min.js');
      await loadScript('app://local/node_modules/butterchurn-presets/lib/butterchurnPresets.min.js');
      const bc = window.butterchurn.default || window.butterchurn;
      const bp = window.butterchurnPresets.default || window.butterchurnPresets;
      this.presets = bp.getPresets();
      this.names = Object.keys(this.presets).sort((a, b) => a.localeCompare(b));
      const r = this.host.getBoundingClientRect();
      this.viz = bc.createVisualizer(this.engine.ctx, this.canvas, {
        width: Math.max(64, r.width), height: Math.max(64, r.height), pixelRatio: Math.min(2, devicePixelRatio || 1), textureRatio: 1,
      });
      this.viz.connectAudio(this.engine.visTap);
      const start = this.s.preset && this.presets[this.s.preset] ? this.s.preset : this.names[Math.floor(Math.random() * this.names.length)];
      this.load(start, 0);
    } catch (e) {
      console.warn('butterchurn unavailable, using spectrum fallback', e);
      this.fallback = true;
      this.g2 = this.canvas.getContext('2d');
      this.nameEl.textContent = 'Spectrum';
    }
  }

  async show() {
    this.open = true;
    this.host.classList.add('open');
    await this.ensure();
    this._resize();
    this._showUi();
    this._cycle();
    const loop = () => {
      if (!this.open) return;
      if (this.viz) this.viz.render(); else this._spectrum();
      this.raf = requestAnimationFrame(loop);
    };
    cancelAnimationFrame(this.raf);
    this.raf = requestAnimationFrame(loop);
  }

  hide() {
    this.open = false;
    this.host.classList.remove('open', 'full');
    $('#player').classList.remove('vis-full');
    cancelAnimationFrame(this.raf);
    clearInterval(this.cycleT);
  }

  load(name, blend = 2.7) {
    if (!this.viz || !this.presets[name]) return;
    this.viz.loadPreset(this.presets[name], blend);
    this.s.preset = name;
    this.app.store.saveSettings();
    this.nameEl.textContent = name;
    this._showUi();
  }

  step(dir) {
    if (!this.names.length) return;
    let i;
    if (this.s.mode === 'shuffle') i = Math.floor(Math.random() * this.names.length);
    else i = (this.names.indexOf(this.s.preset) + dir + this.names.length) % this.names.length;
    this.load(this.names[i]);
    this._cycle();
  }

  toggleMode() {
    this.s.mode = this.s.mode === 'shuffle' ? 'locked' : this.s.mode === 'locked' ? 'sequential' : 'shuffle';
    this.app.store.saveSettings();
    toast({ shuffle: 'Presets: Shuffle', locked: 'Presets: Locked', sequential: 'Presets: Sequential' }[this.s.mode]);
    this._cycle();
  }

  _cycle() {
    clearInterval(this.cycleT);
    if (this.s.mode === 'locked' || !this.open) return;
    this.cycleT = setInterval(() => this.step(1), this.s.cycle * 1000);
  }

  toggleFull() {
    const on = !this.host.classList.contains('full');
    this.host.classList.toggle('full', on);
    $('#player').classList.toggle('vis-full', on);
    setTimeout(() => this._resize(), 360);
  }

  async chooseList() {
    if (!this.names.length) return;
    const v = await chooser('Visualization presets', this.names.map((n) => ({ label: n, value: n })), this.s.preset);
    if (v) { this.load(v); this._cycle(); }
  }

  _showUi() {
    this.host.classList.add('ui');
    clearTimeout(this.uiT);
    this.uiT = setTimeout(() => this.host.classList.remove('ui'), 2600);
  }

  _resize() {
    const r = this.host.getBoundingClientRect();
    if (!r.width) return;
    const dpr = Math.min(2, devicePixelRatio || 1);
    this.canvas.width = Math.round(r.width * dpr);
    this.canvas.height = Math.round(r.height * dpr);
    if (this.viz) this.viz.setRendererSize(this.canvas.width, this.canvas.height);
  }

  _bindSwipe() {
    let sx, id = null, moved = false;
    this.host.addEventListener('pointermove', () => this._showUi());
    this.host.addEventListener('pointerdown', (e) => { if (e.target.closest('.vis-bottom')) return; sx = e.clientX; id = e.pointerId; moved = false; });
    this.host.addEventListener('pointerup', (e) => {
      if (e.pointerId !== id) return;
      id = null;
      const dx = e.clientX - sx;
      if (Math.abs(dx) > 60) { this.step(dx < 0 ? 1 : -1); moved = true; }
    });
    this.host.addEventListener('dblclick', (e) => { if (!e.target.closest('.vis-bottom')) this.toggleFull(); });
    this.host.addEventListener('click', (e) => { if (!moved && !e.target.closest('.vis-bottom')) this._showUi(); });
  }

  _spectrum() {
    const g = this.g2, cv = this.canvas, an = this.engine.analyser;
    const data = (this._fd = this._fd || new Uint8Array(an.frequencyBinCount));
    an.getByteFrequencyData(data);
    const w = cv.width, h = cv.height;
    g.fillStyle = 'rgba(0,0,0,0.25)';
    g.fillRect(0, 0, w, h);
    const n = 64;
    const ac = getComputedStyle(document.documentElement).getPropertyValue('--accent-rgb').trim();
    for (let i = 0; i < n; i++) {
      const idx = Math.floor(Math.pow(i / n, 2) * data.length * 0.7);
      const v = data[idx] / 255;
      const bw = w / n;
      g.fillStyle = `rgba(${ac},${0.4 + v * 0.6})`;
      g.fillRect(i * bw + 1, h - v * h * 0.9, bw - 2, v * h * 0.9);
    }
  }
}

export { esc, hydrateIcons };
