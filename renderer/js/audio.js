// Audio engine: two decks (for gapless preloading and crossfade) feeding a Web Audio DSP chain:
// preamp -> 10-band EQ -> tone (bass/treble) -> balance -> reverb -> limiter -> volume -> output
export const EQ_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const FADE = 0.14;

export class AudioEngine extends EventTarget {
  constructor() {
    super();
    const ctx = (this.ctx = new AudioContext({ latencyHint: 'playback' }));
    this.input = ctx.createGain();
    this.preamp = ctx.createGain();
    this.bands = EQ_FREQS.map((f, i) => {
      const b = ctx.createBiquadFilter();
      b.type = i === 0 ? 'lowshelf' : i === EQ_FREQS.length - 1 ? 'highshelf' : 'peaking';
      b.frequency.value = f;
      b.Q.value = 1.3;
      b.gain.value = 0;
      return b;
    });
    this.bass = ctx.createBiquadFilter(); this.bass.type = 'lowshelf'; this.bass.frequency.value = 110;
    this.treble = ctx.createBiquadFilter(); this.treble.type = 'highshelf'; this.treble.frequency.value = 7000;
    this.panner = ctx.createStereoPanner();

    // reverb: send/return
    this.revIn = ctx.createGain();
    this.revDry = ctx.createGain();
    this.revPre = ctx.createDelay(1);
    this.revConv = ctx.createConvolver();
    this.revHP = ctx.createBiquadFilter(); this.revHP.type = 'highpass'; this.revHP.frequency.value = 80;
    this.revLP = ctx.createBiquadFilter(); this.revLP.type = 'lowpass'; this.revLP.frequency.value = 9000;
    this.revWet = ctx.createGain();
    this.revEcho = ctx.createGain();
    this.revOut = ctx.createGain();
    this.revIn.connect(this.revDry).connect(this.revOut);
    this.revIn.connect(this.revPre);
    this.revPre.connect(this.revConv).connect(this.revHP).connect(this.revLP).connect(this.revWet).connect(this.revOut);
    this.revPre.connect(this.revEcho).connect(this.revOut);

    this.limiter = ctx.createDynamicsCompressor();
    Object.assign(this.limiter, {});
    this.limiter.threshold.value = -1.5;
    this.limiter.knee.value = 0;
    this.limiter.ratio.value = 20;
    this.limiter.attack.value = 0.002;
    this.limiter.release.value = 0.12;

    this.visTap = ctx.createGain();
    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 2048;
    this.analyser.smoothingTimeConstant = 0.78;
    this.volume = ctx.createGain();
    this.master = ctx.createGain(); // used for sleep-timer fades
    this.visTap.connect(this.analyser);
    this.volume.connect(this.master).connect(ctx.destination);

    this.opts = { eq: true, tone: true, limiter: true, reverb: false };
    this.tempo = 1;
    this.decks = [this._deck(0), this._deck(1)];
    this.cur = 0;
    this.preloaded = null;
    this.reverbParams = { mix: 0.3, size: 0.5, damp: 0.5, filter: 0.1, fade: 0.5, predelay: 0.2, predelayMix: 0.1 };
    this._buildImpulse();
    this._route();
  }

  _deck(i) {
    const el = new Audio();
    el.crossOrigin = 'anonymous';
    el.preload = 'auto';
    el.preservesPitch = true;
    const src = this.ctx.createMediaElementSource(el);
    const gain = this.ctx.createGain();
    src.connect(gain).connect(this.input);
    const d = { el, gain, i, url: '' };
    const fire = (type, detail) => { if (this.decks[this.cur] === d) this.dispatchEvent(new CustomEvent(type, { detail })); };
    el.addEventListener('ended', () => fire('ended'));
    el.addEventListener('error', () => fire('error', el.error));
    el.addEventListener('playing', () => fire('state'));
    el.addEventListener('pause', () => fire('state'));
    el.addEventListener('loadedmetadata', () => fire('meta'));
    el.addEventListener('seeked', () => fire('seek'));
    return d;
  }

  get deck() { return this.decks[this.cur]; }
  get el() { return this.deck.el; }
  get currentTime() { return this.el.currentTime || 0; }
  get duration() { return isFinite(this.el.duration) ? this.el.duration : 0; }
  get playing() { return !this.el.paused && !this.el.ended; }

  _route() {
    const nodes = [this.input, this.preamp];
    if (this.opts.eq) nodes.push(...this.bands);
    if (this.opts.tone) nodes.push(this.bass, this.treble);
    nodes.push(this.panner);
    if (this.opts.reverb) nodes.push(this.revIn, this.revOut);
    if (this.opts.limiter) nodes.push(this.limiter);
    const all = [this.input, this.preamp, ...this.bands, this.bass, this.treble, this.panner, this.revOut, this.limiter];
    for (const n of all) { try { n.disconnect(); } catch {} }
    // revIn's internal graph stays connected; only its outer edge is rewired.
    for (let i = 0; i < nodes.length - 1; i++) {
      if (nodes[i] === this.revIn) continue; // revIn -> revOut is internal
      nodes[i].connect(nodes[i + 1]);
    }
    const last = nodes[nodes.length - 1];
    last.connect(this.volume);
    last.connect(this.visTap);
  }

  setOption(name, on) { this.opts[name] = !!on; this._route(); }

  async load(url, { play = true, at = 0 } = {}) {
    this.ctx.resume();
    let deck = this.deck;
    if (this.preloaded && this.preloaded.url === url && this.preloaded.deck !== deck) {
      // Gapless path: the next track is already buffered on the other deck.
      const old = deck;
      old.el.pause();
      old.gain.gain.cancelScheduledValues(0);
      deck = this.preloaded.deck;
      this.cur = deck.i;
      this.preloaded = null;
    } else {
      this.decks.forEach((d) => { if (d !== deck) { d.el.pause(); } });
      this.preloaded = null;
      deck.el.src = url;
      deck.url = url;
    }
    deck.el.playbackRate = this.tempo;
    if (at > 0) {
      await new Promise((res) => {
        if (deck.el.readyState >= 1) res();
        else deck.el.addEventListener('loadedmetadata', res, { once: true });
      });
      try { deck.el.currentTime = at; } catch {}
    }
    const g = deck.gain.gain;
    g.cancelScheduledValues(this.ctx.currentTime);
    g.setValueAtTime(play ? 0 : 1, this.ctx.currentTime);
    if (play) {
      try { await deck.el.play(); } catch (e) { if (e.name !== 'AbortError') throw e; }
      g.linearRampToValueAtTime(1, this.ctx.currentTime + 0.03);
    }
    this.dispatchEvent(new CustomEvent('state'));
  }

  preload(url) {
    const other = this.decks[1 - this.cur];
    if (other.url === url && this.preloaded) return;
    other.el.pause();
    other.el.src = url;
    other.url = url;
    other.el.load();
    this.preloaded = { url, deck: other };
  }

  crossfadeTo(url, sec) {
    this.ctx.resume();
    const from = this.deck;
    const to = this.decks[1 - this.cur];
    if (to.url !== url) { to.el.src = url; to.url = url; }
    to.el.currentTime = 0;
    to.el.playbackRate = this.tempo;
    const now = this.ctx.currentTime;
    to.gain.gain.cancelScheduledValues(now);
    to.gain.gain.setValueAtTime(0, now);
    to.gain.gain.linearRampToValueAtTime(1, now + sec);
    from.gain.gain.cancelScheduledValues(now);
    from.gain.gain.setValueAtTime(from.gain.gain.value, now);
    from.gain.gain.linearRampToValueAtTime(0, now + sec);
    this.cur = to.i;
    this.preloaded = null;
    to.el.play().catch(() => {});
    setTimeout(() => { if (this.deck !== from) from.el.pause(); }, sec * 1000 + 60);
    this.dispatchEvent(new CustomEvent('state'));
  }

  async play() {
    if (!this.el.src) return;
    await this.ctx.resume();
    const g = this.deck.gain.gain, now = this.ctx.currentTime;
    g.cancelScheduledValues(now);
    g.setValueAtTime(0, now);
    try { await this.el.play(); } catch {}
    g.linearRampToValueAtTime(1, this.ctx.currentTime + FADE);
  }

  pause() {
    const deck = this.deck, g = deck.gain.gain, now = this.ctx.currentTime;
    g.cancelScheduledValues(now);
    g.setValueAtTime(g.value, now);
    g.linearRampToValueAtTime(0, now + FADE);
    clearTimeout(this._pauseT);
    this._pauseT = setTimeout(() => { if (this.deck === deck) deck.el.pause(); }, FADE * 1000);
    // reflect state immediately so the UI morphs without waiting for the fade
    this._pausing = true;
    setTimeout(() => (this._pausing = false), FADE * 1000 + 20);
    this.dispatchEvent(new CustomEvent('state', { detail: { pausing: true } }));
  }

  get isPlayingUi() { return this.playing && !this._pausing; }

  toggle() { this.isPlayingUi ? this.pause() : this.play(); }

  seek(t) {
    if (!this.duration) return;
    this.el.currentTime = Math.max(0, Math.min(this.duration - 0.05, t));
  }

  stop() {
    this.decks.forEach((d) => { d.el.pause(); d.el.removeAttribute('src'); d.el.load(); d.url = ''; });
    this.preloaded = null;
  }

  // ---- DSP params ----
  setBand(i, db) { this.bands[i].gain.setTargetAtTime(db, this.ctx.currentTime, 0.015); }
  setPreamp(db) { this.preamp.gain.setTargetAtTime(Math.pow(10, db / 20), this.ctx.currentTime, 0.015); }
  setBass(db) { this.bass.gain.setTargetAtTime(db, this.ctx.currentTime, 0.015); }
  setTreble(db) { this.treble.gain.setTargetAtTime(db, this.ctx.currentTime, 0.015); }
  setBalance(v) { this.panner.pan.setTargetAtTime(v, this.ctx.currentTime, 0.015); }
  setVolume(v) { this.volume.gain.setTargetAtTime(v * v, this.ctx.currentTime, 0.02); }
  setTempo(r) {
    this.tempo = r;
    this.decks.forEach((d) => (d.el.playbackRate = r));
  }

  fadeMaster(to, sec) {
    const g = this.master.gain, now = this.ctx.currentTime;
    g.cancelScheduledValues(now);
    g.setValueAtTime(g.value, now);
    g.linearRampToValueAtTime(to, now + sec);
  }

  setReverb(params) {
    const old = this.reverbParams;
    this.reverbParams = { ...old, ...params };
    const p = this.reverbParams, t = this.ctx.currentTime;
    this.revWet.gain.setTargetAtTime(p.mix * 1.4, t, 0.03);
    this.revDry.gain.setTargetAtTime(1 - p.mix * 0.45, t, 0.03);
    this.revPre.delayTime.setTargetAtTime(p.predelay * 0.25, t, 0.03);
    this.revEcho.gain.setTargetAtTime(p.predelayMix * 0.6, t, 0.03);
    this.revLP.frequency.setTargetAtTime(20000 * Math.pow(1 - p.damp, 2) + 900, t, 0.03);
    this.revHP.frequency.setTargetAtTime(20 + p.filter * p.filter * 1500, t, 0.03);
    if (old.size !== p.size || old.fade !== p.fade) {
      clearTimeout(this._irT);
      this._irT = setTimeout(() => this._buildImpulse(), 120);
    }
  }

  _buildImpulse() {
    const p = this.reverbParams;
    const rate = this.ctx.sampleRate;
    const seconds = 0.3 + p.size * 4.5;
    const decay = 1.2 + (1 - p.fade) * 6;
    const len = Math.floor(rate * seconds);
    const buf = this.ctx.createBuffer(2, len, rate);
    for (let ch = 0; ch < 2; ch++) {
      const data = buf.getChannelData(ch);
      let lp = 0;
      for (let i = 0; i < len; i++) {
        const n = Math.random() * 2 - 1;
        lp += (n - lp) * (0.6 - 0.4 * (i / len)); // gets darker as the tail decays
        data[i] = lp * Math.pow(1 - i / len, decay);
      }
    }
    this.revConv.buffer = buf;
  }

  // Combined frequency response of the EQ + tone stages (for the EQ graph).
  response(freqs) {
    const f = new Float32Array(freqs);
    const mag = new Float32Array(f.length), ph = new Float32Array(f.length);
    const total = new Float32Array(f.length).fill(0);
    const stages = [...(this.opts.eq ? this.bands : []), ...(this.opts.tone ? [this.bass, this.treble] : [])];
    for (const s of stages) {
      s.getFrequencyResponse(f, mag, ph);
      for (let i = 0; i < f.length; i++) total[i] += 20 * Math.log10(mag[i] || 1e-6);
    }
    return total;
  }
}
