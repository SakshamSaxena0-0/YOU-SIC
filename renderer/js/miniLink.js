import { coverUrl } from './util.js';

// Feeds the mini player window (renderer/mini.html, opened by main.js) while it is up: the theme, the song,
// the play state and position, shuffle / repeat, the volume and the song's lyrics. Its buttons come back
// as commands through pa.onCommand (App). Nothing runs while it is closed.
export class MiniLink {
  constructor(app) {
    this.app = app;
    this.on = false;
    const p = app.player, e = app.engine;
    p.addEventListener('track', () => { if (this.on) { this.send(this._song()); this._lyrics(); } });
    p.addEventListener('modes', () => this.on && this.send(this._modes()));
    for (const ev of ['state', 'seek', 'meta']) e.addEventListener(ev, () => this.on && this.send(this._time()));
    pa.onMiniClosed?.(() => { this.on = false; clearInterval(this.beat); });
  }

  get supported() { return !!pa.miniOpen; }

  open() {
    if (!this.supported || this.on) return;
    this.on = true;
    pa.miniOpen({ look: this._look(), ...this._song(), ...this._time(), ...this._modes(), ...this._vol(), lyrics: { loading: true } });
    this._lyrics();
    // the mini player keeps its own clock between these; this only corrects drift
    clearInterval(this.beat);
    this.beat = setInterval(() => this.send(this._time()), 10000);
  }

  close() { if (this.on) pa.miniClose(); }

  send(part) { if (this.on) pa.miniState(part); }

  // the theme: the <html> classes and the colours set on it (accent, album tint)
  look() { this.send({ look: this._look() }); }
  volume() { this.send(this._vol()); }

  _look() {
    const h = document.documentElement, vars = {};
    for (const n of h.style) if (n.startsWith('--')) vars[n] = h.style.getPropertyValue(n);
    const cls = [...h.classList].filter((c) => !['idle', 'app-wide', 'app-side-on', 'glass-window'].includes(c)).join(' ');
    return { cls, vars };
  }

  _song() {
    const t = this.app.player.track;
    return { track: t ? { id: t.id, title: t.title || '', artist: t.artist || '', cover: coverUrl(t.cover) } : null };
  }

  _time() {
    const e = this.app.engine;
    return { time: { pos: e.currentTime, dur: e.duration, playing: e.isPlayingUi, rate: e.tempo || 1 } };
  }

  _modes() { const p = this.app.player; return { shuffle: p.shuffle, repeat: p.repeat }; }

  _vol() { const s = this.app.store.settings; return { volume: s.volume, muted: !!s.muted }; }

  // the same lyrics the lyrics panel shows (and the same cache)
  async _lyrics() {
    const t = this.app.player.track;
    if (!t) { this.send({ lyrics: null }); return; }
    this.send({ lyrics: { id: t.id, loading: true } });
    const cache = this.app.lyrics.cache;
    let d = cache.get(t.id);
    if (d === undefined) {
      d = await pa.lyrics(t.path).catch(() => null);
      cache.set(t.id, d);
    }
    if (this.app.player.track !== t) return;
    this.send({ lyrics: d ? { id: t.id, synced: d.synced || null, text: d.synced ? null : d.text || null } : { id: t.id } });
  }
}
