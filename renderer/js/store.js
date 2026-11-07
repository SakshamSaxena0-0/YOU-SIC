import { debounce, sortKey } from './util.js';

// Themes (Settings > Theme). Every theme but glass is solid and runs on a fixed palette.
export const THEMES = ['glass', 'cyber', 'subway', 'pixel', 'vector-surrealism', 'liminal', 'midnight', 'metal'];
export const THEME_COLORS = {
  cyber: { accent: [252, 238, 10], dark: [7, 7, 13] },
  subway: { accent: [111, 227, 200], dark: [7, 18, 20] },
  pixel: { accent: [255, 43, 214], dark: [42, 10, 84] },
  'vector-surrealism': { accent: [239, 125, 69], dark: [10, 6, 16] },
  liminal: { accent: [247, 197, 159], dark: [36, 26, 38] },
  midnight: { accent: [70, 227, 242], dark: [6, 8, 12] },
  metal: { accent: [207, 151, 127], dark: [48, 47, 46] },
};
// Liquid Glass in its Brutal mood (settings.glassMood) is black and white whatever the cover looks like
export const GLASS_BRUTAL = { accent: [236, 236, 232], dark: [9, 9, 9] };

export const DEFAULT_SETTINGS = {
  volume: 0.8,
  muted: false,
  shuffle: false,
  repeat: 'all', // off | all | one
  crossfade: 0, // seconds, 0 = gapless
  dynamicAccent: true,
  accent: '#ff9f1c',
  blurBg: true,
  theme: 'glass', // glass (liquid glass over the wallpaper) | cyber (solid neon cyberpunk) | subway (y2k: dim teal, chrome, glass) | pixel (retro 90s desktop windows, pixel art) | vector-surrealism (dark fantasy as a flat surreal vector poster) | liminal (a vintage dream: sunset over a sea of clouds, on old film) | midnight (a city at 3 a.m.: dark polished gunmetal, chrome and cold cyan light) | metal (a hi-fi faceplate: satin aluminium, graphite tiles and keys, copper)
  metalMode: 'light', // Metal only: light (brushed aluminium) | dark (the same faceplate in dark brushed steel)
  glassMood: 'brutal', // Liquid Glass only: brutal (black and white, hard light, heavy type) | clear (clear glass tinted by the album)
  refraction: true,
  windowBg: 'wallpaper', // wallpaper | acrylic | solid
  buttonStyle: 'ios', // ios (clear glass) | bubble | aqua (liquid-filled)
  liquid: 'aqua', // aqua | lime | accent
  reduceMotion: false,
  reduceEffects: null, // null = follow Windows' "show animations" setting; true/false once the user chooses
  resume: true,
  waveform: true,
  rating: true,
  libZoom: 1,
  sidePanel: true, // wide layout: the right-hand lyrics / visualization panel is open
  sorts: {},
  views: {}, // library: 'grid' or 'list' per kind of view (the list menu's View); unset = the view's own default
  eq: {
    enabled: true, tone: true, limiter: true, reverb: false,
    bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], preamp: 0, bass: 0, treble: 0, balance: 0, tempo: 1,
    preset: 'Flat', userPresets: [],
    rev: { mix: 0.3, size: 0.5, damp: 0.5, filter: 0.1, fade: 0.5, predelay: 0.2, predelayMix: 0.1 }, revPreset: 'Hall',
  },
  vis: { preset: null, mode: 'shuffle', cycle: 25 },
  session: null,
};

const cmpStr = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const byTitle = (a, b) => cmpStr(sortKey(a.title), sortKey(b.title));

export const SORTS = {
  default: { label: 'Default', fn: null },
  title: { label: 'Title', fn: byTitle },
  artist: { label: 'Artist', fn: (a, b) => cmpStr(sortKey(a.artist), sortKey(b.artist)) || byTitle(a, b) },
  album: { label: 'Album', fn: (a, b) => cmpStr(sortKey(a.album), sortKey(b.album)) || (a.disc - b.disc) || (a.track - b.track) },
  year: { label: 'Year', fn: (a, b) => (b.year - a.year) || cmpStr(sortKey(a.album), sortKey(b.album)) || (a.track - b.track) },
  duration: { label: 'Duration', fn: (a, b) => b.duration - a.duration },
  added: { label: 'Date Added', fn: (a, b) => b.added - a.added },
  filename: { label: 'File Name', fn: (a, b) => cmpStr(a.path.toLowerCase(), b.path.toLowerCase()) },
};

const albumOrder = (a, b) => (a.disc - b.disc) || (a.track - b.track) || cmpStr(a.path.toLowerCase(), b.path.toLowerCase());
const folderOrder = (a, b) => (a.track && b.track ? a.track - b.track : 0) || cmpStr(a.path.toLowerCase(), b.path.toLowerCase());

function group(tracks, keyFn) {
  const m = new Map();
  for (const t of tracks) {
    const k = keyFn(t);
    if (k == null || k === '') continue;
    let g = m.get(k);
    if (!g) m.set(k, (g = []));
    g.push(t);
  }
  return m;
}

export class Store extends EventTarget {
  constructor() {
    super();
    this.tracks = [];
    this.byId = new Map();
    this.folders = [];
    this.stats = {};
    this.playlists = [];
    this.settings = structuredClone(DEFAULT_SETTINGS);
    this.saveSettings = debounce(() => pa.save('settings', this.settings), 400);
    this.saveStats = debounce(() => pa.save('stats', this.stats), 1500);
    this.savePlaylists = debounce(() => pa.save('playlists', this.playlists), 300);
  }

  async init() {
    const [lib, stats, playlists, settings] = await Promise.all([
      pa.getLibrary(), pa.load('stats'), pa.load('playlists'), pa.load('settings'),
    ]);
    this.stats = stats || {};
    this.playlists = playlists || [];
    const s = settings || {};
    this.settings = { ...structuredClone(DEFAULT_SETTINGS), ...s, eq: { ...DEFAULT_SETTINGS.eq, ...(s.eq || {}) }, vis: { ...DEFAULT_SETTINGS.vis, ...(s.vis || {}) } };
    // v2: clear iOS glass buttons over the real wallpaper replace the earlier looks
    if (this.settings.theme === 'tlou') this.settings.theme = 'subway'; // the last of us theme was replaced
    if (this.settings.theme === 'console') this.settings.theme = 'pixel'; // so was the console theme
    if (this.settings.theme === 'chrome') this.settings.theme = 'metal'; // and the chrome theme, by metal
    if ((s.v || 0) < 2) Object.assign(this.settings, { v: 2, buttonStyle: 'ios', windowBg: 'wallpaper' });
    // Metal's old Cover setting (colour | etched) is gone: the cover is always shown as it is
    delete this.settings.metalCover;
    // phones: the glass refraction is heavy on a mobile GPU, so it starts off there (it can be turned on)
    if (!settings && pa.platform === 'android') this.settings.refraction = false;
    this.setLibrary(lib);
  }

  setLibrary(lib) {
    this.folders = lib.folders || [];
    this.tracks = lib.tracks || [];
    this.byId = new Map(this.tracks.map((t) => [t.id, t]));
    for (const t of this.tracks) {
      t.albumKey = t.album ? (t.albumArtist + '|' + t.album).toLowerCase() : null;
    }
    this._cache = {};
    this.dispatchEvent(new Event('library'));
  }

  // ---------- indexes (lazily built, invalidated on library change) ----------
  _memo(name, fn) { return this._cache[name] || (this._cache[name] = fn()); }

  albums() {
    return this._memo('albums', () => {
      const m = group(this.tracks, (t) => t.albumKey);
      return [...m.entries()].map(([key, ts]) => {
        ts.sort(albumOrder);
        const first = ts.find((t) => t.cover) || ts[0];
        return {
          key, title: ts[0].album, artist: ts[0].albumArtist || ts[0].artist,
          year: Math.max(...ts.map((t) => t.year || 0)), cover: first.cover, tracks: ts,
        };
      }).sort((a, b) => cmpStr(sortKey(a.title), sortKey(b.title)));
    });
  }
  albumByKey(key) { return this.albums().find((a) => a.key === key); }

  _people(field, name) {
    return this._memo(name, () => {
      const m = group(this.tracks, (t) => t[field]);
      return [...m.entries()].map(([title, ts]) => ({
        key: title, title, tracks: ts,
        cover: (ts.find((t) => t.cover) || {}).cover,
        albums: new Set(ts.map((t) => t.albumKey).filter(Boolean)).size,
      })).sort((a, b) => cmpStr(sortKey(a.title), sortKey(b.title)));
    });
  }
  artists() { return this._people('artist', 'artists'); }
  albumArtists() { return this._people('albumArtist', 'albumArtists'); }
  genres() { return this._people('genre', 'genres'); }
  composers() { return this._people('composer', 'composers'); }
  years() {
    return this._memo('years', () => {
      const m = group(this.tracks, (t) => t.year || null);
      return [...m.entries()].map(([y, ts]) => ({ key: y, title: String(y), tracks: ts, cover: (ts.find((t) => t.cover) || {}).cover }))
        .sort((a, b) => b.key - a.key);
    });
  }
  folderList() {
    return this._memo('folders', () => {
      const m = group(this.tracks, (t) => t.folder);
      return [...m.entries()].map(([p, ts]) => ({
        key: p, title: p.split(/[\\/]/).pop() || p, sub: p, tracks: ts.sort(folderOrder),
        cover: (ts.find((t) => t.cover) || {}).cover,
      })).sort((a, b) => cmpStr(a.title.toLowerCase(), b.title.toLowerCase()));
    });
  }

  // Folder hierarchy: immediate subfolders + direct tracks of a path (null = library roots).
  hierarchy(dir) {
    const sep = dir && !dir.includes('\\') && dir.includes('/') ? '/' : '\\'; // Windows paths, or Android's

    const norm = (p) => p.replace(/[\\/]+$/, '');
    if (!dir) {
      return { dirs: this.folders.map((f) => ({ path: norm(f), title: norm(f) })), tracks: [] };
    }
    const base = norm(dir);
    const prefix = base.toLowerCase() + sep;
    const subs = new Map();
    const direct = [];
    for (const t of this.tracks) {
      const f = t.folder;
      if (f.toLowerCase() === base.toLowerCase()) { direct.push(t); continue; }
      if (!f.toLowerCase().startsWith(prefix)) continue;
      const rest = f.slice(prefix.length);
      const name = rest.split(/[\\/]/)[0];
      const full = base + sep + name;
      const e = subs.get(full) || { path: full, title: name, count: 0, cover: null };
      e.count++;
      if (!e.cover && t.cover) e.cover = t.cover;
      subs.set(full, e);
    }
    return {
      dirs: [...subs.values()].sort((a, b) => cmpStr(a.title.toLowerCase(), b.title.toLowerCase())),
      tracks: direct.sort(folderOrder),
    };
  }

  recentlyAdded() { return [...this.tracks].sort((a, b) => b.added - a.added || b.mtime - a.mtime).slice(0, 300); }
  recentlyPlayed() {
    return this.tracks.filter((t) => this.stats[t.id]?.last).sort((a, b) => this.stats[b.id].last - this.stats[a.id].last).slice(0, 300);
  }
  mostPlayed() {
    return this.tracks.filter((t) => this.stats[t.id]?.plays).sort((a, b) => this.stats[b.id].plays - this.stats[a.id].plays).slice(0, 300);
  }
  topRated() {
    return this.tracks.filter((t) => this.stats[t.id]?.rating).sort((a, b) => this.stats[b.id].rating - this.stats[a.id].rating || byTitle(a, b));
  }
  allSongs() { return this._memo('all', () => [...this.tracks].sort(byTitle)); }

  // ---------- stats ----------
  rating(id) { return this.stats[id]?.rating || 0; }
  setRating(id, r) {
    const s = (this.stats[id] = this.stats[id] || {});
    s.rating = r;
    this.saveStats();
    this.dispatchEvent(new CustomEvent('stats', { detail: id }));
  }
  markPlayed(id) {
    const s = (this.stats[id] = this.stats[id] || {});
    s.plays = (s.plays || 0) + 1;
    s.last = Date.now();
    this.saveStats();
  }

  // ---------- playlists ----------
  playlist(id) { return this.playlists.find((p) => p.id === id); }
  playlistTracks(p) { return p.ids.map((id) => this.byId.get(id)).filter(Boolean); }
  createPlaylist(name, ids = []) {
    const p = { id: 'pl' + Date.now().toString(36), name, ids: [...ids], created: Date.now() };
    this.playlists.push(p);
    this.savePlaylists();
    return p;
  }
  addToPlaylist(p, ids) { p.ids.push(...ids.filter((id) => !p.ids.includes(id))); this.savePlaylists(); }
  removeFromPlaylist(p, idx) { p.ids.splice(idx, 1); this.savePlaylists(); }
  deletePlaylist(p) { this.playlists = this.playlists.filter((x) => x !== p); this.savePlaylists(); }

  sortTracks(list, sortName) {
    const s = SORTS[sortName];
    return s && s.fn ? [...list].sort(s.fn) : list;
  }

  search(q) {
    q = q.trim().toLowerCase();
    if (!q) return null;
    const words = q.split(/\s+/);
    const match = (s) => { s = (s || '').toLowerCase(); return words.every((w) => s.includes(w)); };
    const songs = this.tracks.filter((t) => match(`${t.title} ${t.artist} ${t.album}`)).sort(byTitle).slice(0, 200);
    const albums = this.albums().filter((a) => match(`${a.title} ${a.artist}`)).slice(0, 40);
    const artists = this.artists().filter((a) => match(a.title)).slice(0, 40);
    const playlists = this.playlists.filter((p) => match(p.name));
    const genres = this.genres().filter((g) => match(g.title)).slice(0, 20);
    return { songs, albums, artists, playlists, genres };
  }
}
