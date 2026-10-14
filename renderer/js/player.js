import { mediaUrl, debounce } from './util.js';

// Playback controller: the "category" list being played, shuffle order, user queue, repeat modes.
export class Player extends EventTarget {
  constructor(engine, store) {
    super();
    this.engine = engine;
    this.store = store;
    this.list = [];
    this.order = [];
    this.pos = -1;
    this.source = { title: 'Library', node: null };
    this.userQueue = [];
    this.track = null;
    this.counted = false;
    this.xfading = false;
    this.errors = 0;
    this.saveSession = debounce(() => this._saveSession(), 800);

    engine.addEventListener('ended', () => this.next(true));
    engine.addEventListener('state', () => this.dispatchEvent(new Event('state')));
    engine.addEventListener('error', () => this._onError());
    setInterval(() => this._tick(), 250);
  }

  get shuffle() { return this.store.settings.shuffle; }
  get repeat() { return this.store.settings.repeat; }

  _buildOrder(keepIndex) {
    const n = this.list.length;
    this.order = [...Array(n).keys()];
    if (this.shuffle) {
      for (let i = n - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [this.order[i], this.order[j]] = [this.order[j], this.order[i]];
      }
      if (keepIndex != null && keepIndex >= 0) {
        const at = this.order.indexOf(keepIndex);
        [this.order[0], this.order[at]] = [this.order[at], this.order[0]];
        this.pos = 0;
      }
    } else if (keepIndex != null) {
      this.pos = keepIndex;
    }
  }

  playList(tracks, index = 0, source = null, { shuffle = null, at = 0, play = true } = {}) {
    if (!tracks.length) return;
    if (shuffle != null && shuffle !== this.shuffle) {
      this.store.settings.shuffle = shuffle;
      this.store.saveSettings();
    }
    this.list = tracks.slice();
    if (source) this.source = source;
    if (shuffle && index < 0) index = Math.floor(Math.random() * tracks.length);
    this._buildOrder(Math.max(0, index));
    this._play(this.list[this.order[this.pos]], { at, play });
  }

  async _play(track, { at = 0, play = true, xfade = false } = {}) {
    if (!track) return;
    this.track = track;
    this.counted = false;
    this.xfading = false;
    const url = mediaUrl(track.path);
    this.dispatchEvent(new CustomEvent('track', { detail: track }));
    try {
      if (xfade) this.engine.crossfadeTo(url, this.store.settings.crossfade);
      else await this.engine.load(url, { play, at });
      this.errors = 0;
    } catch (e) {
      console.warn('play failed', e);
      this._onError();
    }
    this.saveSession();
  }

  _onError() {
    this.errors++;
    this.dispatchEvent(new CustomEvent('toast', { detail: `Can't play "${this.track?.title}" — skipping` }));
    if (this.errors < 5) setTimeout(() => this.next(true), 800);
  }

  peekNext(auto = true) {
    if (this.userQueue.length) return this.userQueue[0];
    if (!this.list.length) return null;
    if (auto && this.repeat === 'one') return this.track;
    let p = this.pos + 1;
    if (p >= this.order.length) { if (this.repeat === 'off' && auto) return null; p = 0; }
    return this.list[this.order[p]];
  }

  peekPrev() {
    if (!this.list.length) return null;
    let p = this.pos - 1;
    if (p < 0) p = this.order.length - 1;
    return this.list[this.order[p]];
  }

  next(auto = false, opts = {}) {
    this._countPlay(auto);
    if (auto && this.stopAfter) {
      this.stopAfter = false;
      this.engine.pause();
      this.dispatchEvent(new CustomEvent('toast', { detail: 'Sleep timer: playback stopped' }));
      return;
    }
    if (this.userQueue.length) {
      const t = this.userQueue.shift();
      this.dispatchEvent(new Event('queue'));
      return this._play(t, opts);
    }
    if (!this.list.length) return;
    if (auto && this.repeat === 'one') return this._play(this.track, opts);
    let p = this.pos + 1;
    if (p >= this.order.length) {
      if (this.repeat === 'off' && auto) {
        this.engine.pause();
        this.engine.seek(0);
        return;
      }
      if (this.shuffle) this._buildOrder(null);
      p = 0;
    }
    this.pos = p;
    return this._play(this.list[this.order[p]], opts);
  }

  prev(force = false) {
    if (!force && this.engine.currentTime > 4) { this.engine.seek(0); return; }
    if (!this.list.length) return;
    let p = this.pos - 1;
    if (p < 0) p = this.order.length - 1;
    this.pos = p;
    this._play(this.list[this.order[p]]);
  }

  playAt(listIndex) {
    const p = this.order.indexOf(listIndex);
    if (p < 0) return;
    this.pos = p;
    this._play(this.list[listIndex]);
  }

  toggle() {
    if (!this.track) {
      const all = this.store.allSongs();
      if (all.length) this.playList(all, 0, { title: 'All Songs', node: { type: 'allSongs' } });
      return;
    }
    this.engine.toggle();
  }

  setShuffle(on) {
    this.store.settings.shuffle = on;
    this.store.saveSettings();
    const curIdx = this.order[this.pos];
    this._buildOrder(curIdx);
    if (!on) this.pos = curIdx;
    this.engine.preloaded = null;
    this.dispatchEvent(new Event('modes'));
    this.saveSession();
  }

  cycleRepeat() {
    const seq = ['off', 'all', 'one'];
    this.store.settings.repeat = seq[(seq.indexOf(this.repeat) + 1) % 3];
    this.store.saveSettings();
    this.engine.preloaded = null;
    this.dispatchEvent(new Event('modes'));
  }

  enqueue(tracks, playNext = false) {
    if (playNext) this.userQueue.unshift(...tracks);
    else this.userQueue.push(...tracks);
    this.engine.preloaded = null;
    this.dispatchEvent(new Event('queue'));
  }

  _countPlay(auto) {
    if (this.track && !this.counted && (auto || this.engine.currentTime > Math.min(30, this.engine.duration * 0.5))) {
      this.store.markPlayed(this.track.id);
      this.counted = true;
    }
  }

  _tick() {
    const e = this.engine;
    if (!this.track || !e.playing) return;
    const remain = e.duration - e.currentTime;
    if (!e.duration) return;
    const xf = this.store.settings.crossfade;
    if (xf > 0 && !this.xfading && remain <= xf && remain > 0.3 && this.repeat !== 'one') {
      const nt = this.peekNext(true);
      if (nt) { this.xfading = true; this._countPlay(true); this.counted = true; this.next(true, { xfade: true }); return; }
    }
    if (xf === 0 && remain < 20) {
      const nt = this.peekNext(true);
      if (nt && nt !== this.track && !e.preloaded) e.preload(mediaUrl(nt.path));
    }
    if (Math.floor(e.currentTime) % 5 === 0) this.saveSession();
  }

  _saveSession() {
    if (!this.track) return;
    this.store.settings.session = {
      ids: this.list.map((t) => t.id),
      order: this.order,
      pos: this.pos,
      time: this.engine.currentTime,
      source: this.source,
      queue: this.userQueue.map((t) => t.id),
    };
    this.store.saveSettings();
  }

  restoreSession() {
    const s = this.store.settings.session;
    if (!s || !s.ids) return false;
    const list = s.ids.map((id) => this.store.byId.get(id));
    if (list.some((t) => !t)) {
      // library changed; drop missing tracks and fall back to linear order
      this.list = list.filter(Boolean);
      if (!this.list.length) return false;
      this.order = [...this.list.keys()];
      this.pos = 0;
    } else {
      this.list = list;
      this.order = s.order && s.order.length === list.length ? s.order : [...list.keys()];
      this.pos = Math.min(s.pos || 0, list.length - 1);
    }
    this.source = s.source || this.source;
    this.userQueue = (s.queue || []).map((id) => this.store.byId.get(id)).filter(Boolean);
    this._play(this.list[this.order[this.pos]], { at: s.time || 0, play: false });
    return true;
  }
}
