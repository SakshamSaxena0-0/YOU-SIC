import { $, esc, fmtTime, fmtDuration, coverUrl, clamp, debounce, ANDROID } from './util.js';
import { icon, hydrateIcons, setIcon } from './icons.js';
import { SORTS } from './store.js';
import { menu, menuAt, prompt, chooser, toast, confirmSheet } from './ui.js';

const ROW_H = 64;
const TILE_H = 108; // a tile's height in grid view, as the library's own (css/app.css .tile)

// Root categories.
const ROOT = [
  { type: 'folders', title: 'Folders', icon: 'folder' },
  { type: 'hier', title: 'Folders Hierarchy', icon: 'folder_tree' },
  { type: 'allSongs', title: 'All Songs', icon: 'music_note' },
  { type: 'albums', title: 'Albums', icon: 'album' },
  { type: 'artists', title: 'Artists', icon: 'person' },
  { type: 'albumArtists', title: 'Album Artists', icon: 'people' },
  { type: 'genres', title: 'Genres', icon: 'genre' },
  { type: 'years', title: 'Years', icon: 'calendar' },
  { type: 'composers', title: 'Composers', icon: 'composer' },
  { type: 'playlists', title: 'Playlists', icon: 'playlist_play' },
  { type: 'queue', title: 'Queue', icon: 'queue' },
  { type: 'recentlyAdded', title: 'Recently Added', icon: 'new_box' },
  { type: 'recentlyPlayed', title: 'Recently Played', icon: 'history' },
  { type: 'mostPlayed', title: 'Most Played', icon: 'trending_up' },
  { type: 'topRated', title: 'Top Rated', icon: 'star' },
];
// Wide windows show the root as a navigation list (css/fluent.css .nav-list): the most used first, the rest
// under "More". Narrow windows (and phones) keep the tiles above.
const NAV_MAIN = ['allSongs', 'albums', 'artists', 'playlists', 'queue', 'topRated', 'folders', 'genres', 'years', 'recentlyPlayed'];

export class LibraryView {
  constructor(app) {
    this.app = app;
    this.store = app.store;
    this.player = app.player;
    this.stack = [];
    this.pagesEl = $('#libPages');
    this.searching = false;

    $('#libBack').onclick = () => this.back();
    $('#libSearch').onclick = () => this.toggleSearch(true);
    $('#searchClose').onclick = () => this.toggleSearch(false);
    $('#searchInput').oninput = debounce(() => this._renderSearch(), 120);
    $('#searchInput').onkeydown = (e) => { if (e.key === 'Escape') this.toggleSearch(false); };
    $('#libShuffle').onclick = () => this.shuffleCurrent();
    $('#libPlay').onclick = () => this.playCurrent();
    $('#libMenu').onclick = (e) => this.listOptions(e.currentTarget);

    this.player.addEventListener('track', () => this._markPlaying());
    this.player.addEventListener('queue', () => { if (this.top?.node.type === 'queue') this.refresh(); });
    this.store.addEventListener('library', () => this.refresh(true));
    this._bindNavbar();
    this._bindSwipeBack();
    this.push({ type: 'root' }, false);
  }

  get top() { return this.stack[this.stack.length - 1]; }

  // ---------- content resolution ----------
  resolve(node) {
    const s = this.store;
    const items = (arr, map) => ({ kind: 'items', items: arr.map(map) });
    switch (node.type) {
      case 'root': {
        const counts = {
          folders: s.folderList().length, hier: s.folders.length, allSongs: s.tracks.length, albums: s.albums().length,
          artists: s.artists().length, albumArtists: s.albumArtists().length, genres: s.genres().length, years: s.years().length,
          composers: s.composers().length, playlists: s.playlists.length, queue: this.player.userQueue.length,
          recentlyAdded: Math.min(300, s.tracks.length), recentlyPlayed: s.recentlyPlayed().length,
          mostPlayed: s.mostPlayed().length, topRated: s.topRated().length,
        };
        return { kind: 'root', title: 'Library', items: ROOT.map((r) => ({ ...r, count: counts[r.type], node: { type: r.type, title: r.title } })) };
      }
      case 'folders':
        return { title: 'Folders', ...items(s.folderList(), (f) => ({ title: f.title, sub: `${f.tracks.length} tracks · ${f.sub}`, cover: f.cover, icon: 'folder', node: { type: 'folder', key: f.key, title: f.title } })) };
      case 'folder': {
        const f = s.folderList().find((x) => x.key === node.key);
        return { kind: 'tracks', title: node.title, tracks: f ? f.tracks : [], sub: node.key };
      }
      case 'hier': {
        const h = s.hierarchy(node.path || null);
        const dirs = h.dirs.map((d) => ({ title: d.title, sub: d.count ? `${d.count} tracks` : d.path, icon: 'folder', cover: d.cover, node: { type: 'hier', path: d.path, title: d.title } }));
        return { kind: 'mixed', title: node.path ? node.title : 'Folders Hierarchy', items: dirs, tracks: h.tracks, sub: node.path || '' };
      }
      case 'allSongs': return { kind: 'tracks', title: 'All Songs', tracks: s.allSongs() };
      case 'albums':
        return { title: 'Albums', layout: 'grid', ...items(s.albums(), (a) => ({ title: a.title, sub: a.artist, cover: a.cover, icon: 'album', node: { type: 'album', key: a.key, title: a.title } })) };
      case 'album': {
        const a = s.albumByKey(node.key);
        return { kind: 'tracks', title: node.title, tracks: a ? a.tracks : [], header: a, sub: a ? `${a.artist}${a.year ? ' · ' + a.year : ''}` : '' };
      }
      case 'artists': case 'albumArtists': case 'composers': {
        const list = node.type === 'artists' ? s.artists() : node.type === 'albumArtists' ? s.albumArtists() : s.composers();
        const field = node.type === 'artists' ? 'artist' : node.type === 'albumArtists' ? 'albumArtist' : 'composer';
        return { title: node.title, ...items(list, (a) => ({ title: a.title, sub: `${a.albums} album${a.albums === 1 ? '' : 's'} · ${a.tracks.length} tracks`, cover: a.cover, icon: 'person', round: true, node: { type: 'person', field, key: a.key, title: a.title } })) };
      }
      case 'person': {
        const tracks = s.tracks.filter((t) => t[node.field] === node.key);
        const albums = s.albums().filter((a) => a.tracks.some((t) => t[node.field] === node.key));
        const albumItems = albums.map((a) => ({ title: a.title, sub: `${a.year || ''} · ${a.tracks.length} tracks`, cover: a.cover, icon: 'album', node: { type: 'album', key: a.key, title: a.title } }));
        if (albums.length <= 1) return { kind: 'tracks', title: node.title, tracks: s.sortTracks(tracks, 'album') };
        return { kind: 'mixed', title: node.title, items: [{ title: 'All Songs', sub: `${tracks.length} tracks`, icon: 'music_note', node: { type: 'personAll', field: node.field, key: node.key, title: node.title } }, ...albumItems], tracks: [], layout: 'grid' };
      }
      case 'personAll':
        return { kind: 'tracks', title: node.title, tracks: s.sortTracks(s.tracks.filter((t) => t[node.field] === node.key), 'album') };
      case 'genres':
        return { title: 'Genres', ...items(s.genres(), (g) => ({ title: g.title, sub: `${g.tracks.length} tracks`, cover: g.cover, icon: 'genre', node: { type: 'genre', key: g.key, title: g.title } })) };
      case 'genre': {
        const g = s.genres().find((x) => x.key === node.key);
        return { kind: 'tracks', title: node.title, tracks: g ? s.sortTracks(g.tracks, 'artist') : [] };
      }
      case 'years':
        return { title: 'Years', ...items(s.years(), (y) => ({ title: y.title, sub: `${y.tracks.length} tracks`, cover: y.cover, icon: 'calendar', node: { type: 'year', key: y.key, title: y.title } })) };
      case 'year': {
        const y = s.years().find((x) => x.key === node.key);
        return { kind: 'tracks', title: node.title, tracks: y ? s.sortTracks(y.tracks, 'album') : [] };
      }
      case 'playlists':
        return {
          title: 'Playlists', kind: 'items',
          items: [{ title: 'New Playlist', sub: 'Create an empty playlist', icon: 'add', action: () => this.newPlaylist() },
            ...s.playlists.map((p) => ({ title: p.name, sub: `${p.ids.length} tracks`, cover: (s.playlistTracks(p).find((t) => t.cover) || {}).cover, icon: 'playlist_play', node: { type: 'playlist', key: p.id, title: p.name }, playlist: p }))],
        };
      case 'playlist': {
        const p = s.playlist(node.key);
        return { kind: 'tracks', title: p ? p.name : node.title, tracks: p ? s.playlistTracks(p) : [], playlist: p };
      }
      case 'queue':
        return { kind: 'tracks', title: 'Queue', tracks: this.player.userQueue.slice(), queue: true, empty: 'Queue is empty. Use "Add to Queue" or "Play Next" from any track menu.' };
      case 'recentlyAdded': return { kind: 'tracks', title: 'Recently Added', tracks: s.recentlyAdded() };
      case 'recentlyPlayed': return { kind: 'tracks', title: 'Recently Played', tracks: s.recentlyPlayed() };
      case 'mostPlayed': return { kind: 'tracks', title: 'Most Played', tracks: s.mostPlayed(), stat: 'plays' };
      case 'topRated': return { kind: 'tracks', title: 'Top Rated', tracks: s.topRated(), stat: 'rating' };
      case 'search': return { kind: 'search', title: 'Search' };
      default: return { kind: 'items', title: '?', items: [] };
    }
  }

  // ---------- navigation ----------
  push(node, animate = true) {
    if (this.searching && node.type !== 'search') this.toggleSearch(false, true);
    const page = this._buildPage(node);
    const prev = this.top;
    this.stack.push(page);
    this.pagesEl.appendChild(page.el);
    this._header();
    if (prev) this._transition(prev.el, page.el, 1, animate);
  }

  back() {
    if (this.searching) { this.toggleSearch(false); return true; }
    if (this.stack.length <= 1) { this.app.showPlayer(); return false; }
    const cur = this.stack.pop();
    const prev = this.top;
    prev.el.style.display = '';
    if (prev.dirty) this._rerender(prev);
    this._header();
    this._transition(cur.el, prev.el, -1, true, () => cur.el.remove());
    return true;
  }

  // Jump to a node (e.g. the currently playing category) with root underneath.
  openNode(node) {
    if (!node) return;
    const t = this.top?.node;
    if (t && JSON.stringify(t) === JSON.stringify(node)) { this._scrollToPlaying(this.top, true); return; }
    while (this.stack.length > 1) this.stack.pop().el.remove();
    this.stack[0].el.style.display = '';
    this.push(node, true);
    requestAnimationFrame(() => this._scrollToPlaying(this.top, true));
  }

  _transition(from, to, dir, animate, done) {
    const reduce = this.store.settings.reduceMotion || !animate;
    if (reduce) { from.style.display = 'none'; done && done(); return; }
    const ease = 'cubic-bezier(.22,.9,.25,1)';
    from.animate([{ transform: 'translateX(0)', opacity: 1 }, { transform: `translateX(${-dir * 28}%)`, opacity: 0 }], { duration: 340, easing: ease })
      .finished.then(() => { if (dir > 0) from.style.display = 'none'; done && done(); });
    to.animate([{ transform: `translateX(${dir * 40}%)`, opacity: 0 }, { transform: 'translateX(0)', opacity: 1 }], { duration: 380, easing: ease });
  }

  _header() {
    const p = this.top;
    $('#libTitle').textContent = p.content.title;
    const sub = p.content.kind === 'tracks' || p.content.kind === 'mixed'
      ? [p.content.sub, p.content.tracks?.length ? `${p.content.tracks.length} tracks · ${fmtDuration(p.content.tracks.reduce((a, t) => a + (t.duration || 0), 0))}` : ''].filter(Boolean).join(' · ')
      : p.content.items ? `${p.content.items.length} items` : '';
    $('#libSub').textContent = sub;
    $('#libBack').classList.toggle('dim', this.stack.length <= 1);
    $('#library').classList.toggle('at-root', this.stack.length <= 1);
    $('#libShuffle').style.visibility = this._tracksOf(p).length ? '' : 'hidden';
    $('#libPlay').style.visibility = this._tracksOf(p).length ? '' : 'hidden';
    const t = $('.lib-titles');
    t.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'none' }], { duration: 260, easing: 'ease-out' });
  }

  refresh(all = false) {
    if (all) this.stack.forEach((p) => (p.dirty = true));
    if (this.top) this._rerender(this.top);
    this._header();
  }

  _rerender(page) {
    page.dirty = false;
    const scroll = page.scroller ? page.scroller.scrollTop : 0;
    const fresh = this._buildPage(page.node);
    page.el.replaceWith(fresh.el);
    Object.assign(page, fresh);
    if (page.scroller) page.scroller.scrollTop = scroll;
  }

  // ---------- page building ----------
  _buildPage(node) {
    const content = this.resolve(node);
    const el = document.createElement('div');
    el.className = 'lib-page';
    el.dataset.node = node.type; // which view this is, for the themes (Metal draws the Folders list as cards)
    const page = { node, content, el };
    if (content.kind === 'root') this._buildRoot(page);
    else if (content.kind === 'search') this._buildSearch(page);
    else this._buildList(page);
    hydrateIcons(el);
    return page;
  }

  _buildRoot(page) {
    const { el, content } = page;
    const empty = !this.store.tracks.length;
    const at = new Map(content.items.map((it, i) => [it.type, i]));
    const row = (i) => {
      const it = content.items[i];
      return `<button class="nav-row${it.type === this.navSel ? ' on' : ''}" data-i="${i}"><span class="nav-ico">${icon(it.icon)}</span><span class="nav-label">${esc(it.title)}</span><span class="nav-count">${it.count ?? ''}</span></button>`;
    };
    const more = content.items.map((it, i) => i).filter((i) => !NAV_MAIN.includes(content.items[i].type));
    const nav = `<div class="nav-list" role="list">${NAV_MAIN.filter((t) => at.has(t)).map((t) => row(at.get(t))).join('')}
      <div class="nav-sep">More</div>${more.map(row).join('')}</div>`;
    el.innerHTML = `<div class="scroller">${nav}<div class="root-grid root-tiles">${content.items.map((it, i) => `
      <button class="tile lg" data-lg="static" data-i="${i}" style="--d:${i * 22}ms">
        <span class="tile-ico">${icon(it.icon)}</span>
        <span class="tile-title">${esc(it.title)}</span>
        <span class="tile-count">${it.count ?? ''}</span>
      </button>`).join('')}</div>
      ${empty ? `<div class="empty-lib">${icon('library')}<h3>Your library is empty</h3><p>Add a folder with music to get started.</p><button class="btn lg" data-lg="static" id="emptyAdd">Add Music Folder</button></div>` : ''}
      <div class="folder-strip">${this.store.folders.map((f) => `<span class="fchip" title="${esc(f)}">${icon('folder')}${esc(f)}</span>`).join('')}
        <button class="fchip add lg" data-lg="static" id="rootAdd">${icon('add')}Add folder</button><button class="fchip add lg" data-lg="static" id="rootRescan">${icon('refresh')}Rescan</button></div>
    </div>`;
    page.scroller = el.firstElementChild;
    el.onclick = (e) => {
      const b = e.target.closest('.tile, .nav-row');
      if (b) {
        // the row stays marked as the view you chose, for when you come back to the list
        this.navSel = content.items[+b.dataset.i].type;
        el.querySelectorAll('.nav-row').forEach((r) => r.classList.toggle('on', r === b));
        this.push(content.items[+b.dataset.i].node);
      }
      if (e.target.closest('#emptyAdd, #rootAdd')) this.app.addFolder();
      if (e.target.closest('#rootRescan')) this.app.rescan(true);
    };
  }

  _tracksOf(page) { return page.content.tracks || []; }

  _sorted(page) {
    const c = page.content;
    const sort = this.store.settings.sorts[page.node.type] || 'default';
    page.sort = sort;
    return c.tracks ? this.store.sortTracks(c.tracks, sort) : [];
  }

  _buildList(page) {
    const { el, content } = page;
    const zoom = this.store.settings.libZoom || 1;
    const tracks = (page.tracks = this._sorted(page));
    const items = content.items || [];
    // grid view or list view (the list menu's View): a view built as a grid of covers (albums) keeps that
    // grid; any other shows its items and songs as tiles like the library's own
    const view = (page.view = this._view(page));
    const grid = view === 'grid' && content.layout === 'grid';
    const tiles = view === 'grid' && !grid;
    el.innerHTML = `<div class="scroller">
      ${content.header ? this._albumHeader(content.header) : ''}
      ${items.length ? (tiles
        ? `<div class="root-grid item-tiles">${items.map((it, i) => this._tileHtml(it, i)).join('')}</div>`
        : `<div class="${grid ? 'item-grid' : 'item-list'}" style="--zoom:${zoom}">${items.map((it, i) => this._itemHtml(it, i, grid)).join('')}</div>`) : ''}
      ${tracks.length ? (view === 'grid' ? '<div class="root-grid vgrid"></div>' : `<div class="vlist" style="height:${tracks.length * ROW_H}px"></div>`) : ''}
      ${!items.length && !tracks.length ? `<div class="empty">${esc(content.empty || 'Nothing here yet')}</div>` : ''}
    </div>`;
    const scroller = (page.scroller = el.firstElementChild);
    if (tracks.length) {
      if (view === 'grid') this._virtualGrid(page, scroller.querySelector('.vgrid'), tracks);
      else this._virtual(page, scroller.querySelector('.vlist'), tracks);
    }
    el.onclick = (e) => {
      const it = e.target.closest('[data-item]');
      if (it) {
        const item = items[+it.dataset.item];
        if (item.action) item.action(); else this.push(item.node);
        return;
      }
      const hb = e.target.closest('[data-hact]');
      if (hb) {
        if (hb.dataset.hact === 'play') this.playFrom(page, 0);
        if (hb.dataset.hact === 'shuffle') this.shuffleCurrent();
      }
    };
    el.oncontextmenu = (e) => {
      const it = e.target.closest('[data-item]');
      if (!it) return;
      e.preventDefault();
      this._itemMenu(items[+it.dataset.item], e.clientX, e.clientY);
    };
    // Ctrl+wheel zooms grids/lists (pinch-to-zoom equivalent)
    el.addEventListener('wheel', (e) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const z = clamp((this.store.settings.libZoom || 1) * (e.deltaY < 0 ? 1.08 : 0.93), 0.6, 1.8);
      this.store.settings.libZoom = z;
      this.store.saveSettings();
      el.querySelectorAll('.item-grid, .item-list').forEach((g) => g.style.setProperty('--zoom', z));
    }, { passive: false });
  }

  _albumHeader(a) {
    const total = a.tracks.reduce((s, t) => s + (t.duration || 0), 0);
    return `<div class="album-head">
      <div class="ah-art ${a.cover ? '' : 'ph'}" style="${a.cover ? `background-image:url('${coverUrl(a.cover)}')` : ''}">${a.cover ? '' : icon('album')}</div>
      <div class="ah-info">
        <div class="ah-title">${esc(a.title)}</div>
        <div class="ah-artist">${esc(a.artist)}</div>
        <div class="ah-meta">${[a.year || '', `${a.tracks.length} tracks`, fmtDuration(total)].filter(Boolean).join(' · ')}</div>
        <div class="ah-actions"><button class="btn lg" data-lg="static" data-hact="play">${icon('play')}Play</button><button class="btn ghost lg" data-lg="static" data-hact="shuffle">${icon('shuffle')}Shuffle</button></div>
      </div></div>`;
  }

  _itemHtml(it, i, grid) {
    const art = it.cover
      ? `<div class="it-art ${it.round ? 'round' : ''}" style="background-image:url('${coverUrl(it.cover, true)}')"></div>`
      : `<div class="it-art ph ${it.round ? 'round' : ''}">${icon(it.icon || 'music_note')}</div>`;
    const delay = `style="--d:${Math.min(i, 24) * 18}ms"`;
    if (grid) {
      return `<button class="gcell" data-item="${i}" ${delay}>${art}<div class="g-title">${esc(it.title)}</div><div class="g-sub">${esc(it.sub || '')}</div></button>`;
    }
    return `<button class="row item" data-item="${i}" ${delay}>${art}<div class="r-text"><div class="r-title">${esc(it.title)}</div><div class="r-sub">${esc(it.sub || '')}</div></div><span class="r-chev">${icon('chevron_right')}</span></button>`;
  }

  // An item as a tile like the library's own (grid view): its cover or icon top left, its track count top
  // right, its name and the rest of its details at the bottom.
  _tileHtml(it, i) {
    const m = /^(\d+) tracks?(?: · )?/.exec(it.sub || '');
    const count = m ? m[1] : '';
    const sub = m ? it.sub.slice(m[0].length) : it.sub || '';
    const art = it.cover
      ? `<span class="tile-ico has-art${it.round ? ' round' : ''}"><span class="it-art" style="background-image:url('${coverUrl(it.cover, true)}')"></span></span>`
      : `<span class="tile-ico">${icon(it.icon || 'music_note')}</span>`;
    return `<button class="tile lg item-tile" data-lg="static" data-item="${i}" style="--d:${Math.min(i, 24) * 18}ms">${art}<span class="tile-title">${esc(it.title)}</span>${sub ? `<span class="tile-sub">${esc(sub)}</span>` : ''}<span class="tile-count">${count}</span></button>`;
  }

  // grid or list, chosen per kind of view (like the sort); a view built as a grid (albums) starts as one
  _view(page) {
    const v = this.store.settings.views?.[page.node.type];
    return v === 'grid' || v === 'list' ? v : page.content.layout === 'grid' ? 'grid' : 'list';
  }

  _setView(page, v) {
    this.store.settings.views = { ...(this.store.settings.views || {}), [page.node.type]: v };
    this.store.saveSettings();
    if (page.scroller) page.scroller.scrollTop = 0;
    this.refresh();
  }

  // Clicks and right-clicks on a list's songs (rows or tiles).
  _trackHandlers(page, host) {
    host.addEventListener('click', (e) => {
      const row = e.target.closest('.track');
      if (!row) return;
      const i = +row.dataset.i;
      if (e.target.closest('.r-more')) { const r = e.target.getBoundingClientRect(); this._trackMenu(page, i, r.left - 180, r.bottom); return; }
      if (page.content.queue) {
        // play from queue: remove it from the queue and play now
        const t = this.player.userQueue.splice(i, 1)[0];
        this.player.dispatchEvent(new Event('queue'));
        if (t) this.player._play(t);
        return;
      }
      this.playFrom(page, i);
    });
    host.addEventListener('contextmenu', (e) => {
      const row = e.target.closest('.track');
      if (!row) return;
      e.preventDefault();
      this._trackMenu(page, +row.dataset.i, e.clientX, e.clientY);
    });
  }

  _trackRight(page, t) {
    const stat = page.content.stat;
    return stat === 'plays' ? `${this.store.stats[t.id]?.plays || 0}×` : stat === 'rating' ? '★'.repeat(this.store.rating(t.id)) : fmtTime(t.duration);
  }

  // Virtualized grid of song tiles (grid view). Like the list, only the tiles in view (+overscan) exist in
  // the DOM; they are placed here, in the grid's own padding and gap (the theme's .root-grid), so a tile's
  // place is known without laying out the rest.
  _virtualGrid(page, host, tracks) {
    const scroller = page.scroller;
    const pool = new Map();
    const g = (page.grid = { cols: 0, w: 0, h: TILE_H, gap: 12, padT: 0, padL: 0 });
    const layout = () => {
      const cs = getComputedStyle(host);
      const padL = parseFloat(cs.paddingLeft) || 0, padR = parseFloat(cs.paddingRight) || 0;
      const padT = parseFloat(cs.paddingTop) || 0, padB = parseFloat(cs.paddingBottom) || 0;
      const gap = parseFloat(cs.columnGap) || 12;
      const inner = host.clientWidth - padL - padR;
      const min = document.querySelector('.app.wide') ? 126 : 156; // the library grid's smallest tile (css/app.css)
      const cols = Math.max(1, Math.floor((inner + gap) / (min + gap)));
      const w = (inner - (cols - 1) * gap) / cols;
      const changed = cols !== g.cols || Math.abs(w - g.w) > 0.5 || gap !== g.gap;
      Object.assign(g, { cols, w, gap, padL, padT });
      const rows = Math.ceil(tracks.length / cols);
      const content = rows * (g.h + gap) - gap;
      host.style.height = `${cs.boxSizing === 'border-box' ? padT + content + padB : content}px`;
      return changed;
    };
    const render = () => {
      if (!host.isConnected || !host.clientWidth) return;
      if (layout()) { pool.forEach((el) => el.remove()); pool.clear(); }
      const rowH = g.h + g.gap;
      const top = scroller.scrollTop - host.offsetTop - g.padT;
      const vh = scroller.clientHeight;
      const first = Math.max(0, Math.floor(top / rowH) - 2) * g.cols;
      const last = Math.min(tracks.length - 1, (Math.ceil((top + vh) / rowH) + 2) * g.cols + g.cols - 1);
      for (const [i, el] of pool) if (i < first || i > last) { el.remove(); pool.delete(i); }
      const cur = this.player.track;
      for (let i = first; i <= last; i++) {
        if (pool.has(i)) continue;
        const t = tracks[i];
        const el = document.createElement('div');
        const url = t.cover ? coverUrl(t.cover, true) : '';
        el.className = 'tile lg track' + (url ? ' has-art' : '') + (cur && cur.id === t.id ? ' playing' : '');
        el.dataset.lg = 'static';
        el.dataset.i = i;
        el.style.cssText = `position:absolute;left:${g.padL + (i % g.cols) * (g.w + g.gap)}px;top:${g.padT + Math.floor(i / g.cols) * rowH}px;width:${g.w}px;height:${g.h}px`;
        // a song with a cover: the tile is filled with it (the user's choice), the playing bars in a corner
        const eq = '<span class="eqbars"><i></i><i></i><i></i></span>';
        el.innerHTML = `${url ? `<span class="tile-art" style="background-image:url('${url}')"></span><span class="tile-eq">${eq}</span>` : `<span class="tile-ico">${icon('music_note')}${eq}</span>`}
          <span class="tile-title">${esc(t.title)}</span><span class="tile-sub">${esc(t.artist || t.album || '')}</span>
          <span class="tile-count">${this._trackRight(page, t)}</span><button class="icon-btn small r-more">${icon('more_vert')}</button>`;
        host.appendChild(el);
        pool.set(i, el);
      }
    };
    page.renderRows = () => { pool.forEach((el) => el.remove()); pool.clear(); render(); };
    scroller.addEventListener('scroll', render, { passive: true });
    new ResizeObserver(render).observe(scroller);
    requestAnimationFrame(render);
    this._trackHandlers(page, host);
  }

  // Virtualized track list: only rows in view (+overscan) exist in the DOM.
  _virtual(page, host, tracks) {
    const scroller = page.scroller;
    const pool = new Map();
    const render = () => {
      if (!host.isConnected) return;
      const top = scroller.scrollTop - host.offsetTop;
      const vh = scroller.clientHeight;
      const first = Math.max(0, Math.floor(top / ROW_H) - 6);
      const last = Math.min(tracks.length - 1, Math.ceil((top + vh) / ROW_H) + 6);
      for (const [i, el] of pool) if (i < first || i > last) { el.remove(); pool.delete(i); }
      const cur = this.player.track;
      for (let i = first; i <= last; i++) {
        if (pool.has(i)) continue;
        const t = tracks[i];
        const el = document.createElement('div');
        el.className = 'row track' + (i % 2 ? ' alt' : '') + (cur && cur.id === t.id ? ' playing' : ''); // .alt: every other row (DOM order is not list order)
        el.style.translate = `0 ${i * ROW_H}px`; // translate, not transform: the hover scale would multiply a transform offset
        el.dataset.i = i;
        const right = this._trackRight(page, t);
        const num = page.node.type === 'album' && t.track ? `<span class="t-num">${t.track}</span>` : '';
        el.innerHTML = `${num}<div class="it-art sm ${t.cover ? '' : 'ph'}" ${t.cover ? `style="background-image:url('${coverUrl(t.cover, true)}')"` : ''}>${t.cover ? '' : icon('music_note')}<div class="eqbars"><i></i><i></i><i></i></div></div>
          <div class="r-text"><div class="r-title">${esc(t.title)}</div><div class="r-sub">${esc([t.artist, t.album].filter(Boolean).join(' · '))}</div></div>
          <span class="r-dur">${right}</span><button class="icon-btn small r-more">${icon('more_vert')}</button>`;
        host.appendChild(el);
        pool.set(i, el);
      }
    };
    page.renderRows = () => { pool.forEach((el) => el.remove()); pool.clear(); render(); };
    scroller.addEventListener('scroll', render, { passive: true });
    new ResizeObserver(render).observe(scroller);
    requestAnimationFrame(render);
    this._trackHandlers(page, host);
  }

  playFrom(page, i) {
    const tracks = page.tracks || this._sorted(page);
    const cur = this.player.track;
    if (cur && tracks[i] && cur.id === tracks[i].id && this.player.list === page._playedList) {
      this.app.showPlayer();
      return;
    }
    this.player.playList(tracks, i, { title: page.content.title, node: page.node }, { shuffle: this.store.settings.shuffle });
    page._playedList = this.player.list;
    if (this.app.narrow) this.app.showPlayer();
  }

  // the open folder's (or album's, playlist's...) songs from the first, in the list's order
  playCurrent() {
    const page = this.top;
    const tracks = page.tracks || this._sorted(page);
    if (!tracks.length) return;
    this.player.playList(tracks, 0, { title: page.content.title, node: page.node }, { shuffle: false });
    page._playedList = this.player.list;
    this.app.toast(`Playing ${page.content.title}`);
    if (this.app.narrow) this.app.showPlayer();
  }

  shuffleCurrent() {
    const page = this.top;
    const tracks = page.tracks || this._sorted(page);
    if (!tracks.length) return;
    this.player.playList(tracks, -1, { title: page.content.title, node: page.node }, { shuffle: true });
    this.app.toast('Shuffle Songs');
    if (this.app.narrow) this.app.showPlayer();
  }

  _markPlaying() {
    const cur = this.player.track;
    for (const p of this.stack) {
      p.el.querySelectorAll('.track').forEach((row) => {
        const t = p.tracks?.[+row.dataset.i];
        row.classList.toggle('playing', !!(t && cur && t.id === cur.id));
      });
    }
  }

  _scrollToPlaying(page, smooth) {
    const cur = this.player.track;
    if (!cur || !page.tracks || !page.scroller) return;
    const i = page.tracks.findIndex((t) => t.id === cur.id);
    if (i < 0) return;
    const host = page.el.querySelector('.vlist, .vgrid');
    if (!host) return;
    const g = page.view === 'grid' ? page.grid : null;
    if (g && !g.cols) return; // the grid isn't laid out yet
    const y = g
      ? host.offsetTop + g.padT + Math.floor(i / g.cols) * (g.h + g.gap) - page.scroller.clientHeight / 2 + g.h / 2
      : host.offsetTop + i * ROW_H - page.scroller.clientHeight / 2 + ROW_H / 2;
    page.scroller.scrollTo({ top: Math.max(0, y), behavior: smooth ? 'smooth' : 'auto' });
  }

  // ---------- menus ----------
  _trackMenu(page, i, x, y) {
    const t = page.tracks[i];
    const p = this.player;
    const items = [
      { header: t.title },
      { label: 'Play', icon: 'play', action: () => this.playFrom(page, i) },
      { label: 'Play Next', icon: 'play_next', action: () => { p.enqueue([t], true); toast('Will play next'); } },
      { label: 'Add to Queue', icon: 'queue', action: () => { p.enqueue([t]); toast('Added to Queue'); } },
      { label: 'Add to Playlist…', icon: 'playlist_add', action: () => this.addToPlaylist([t]) },
      '-',
    ];
    if (t.albumKey) items.push({ label: 'Go to Album', icon: 'album', action: () => this.push({ type: 'album', key: t.albumKey, title: t.album }) });
    if (t.artist) items.push({ label: 'Go to Artist', icon: 'person', action: () => this.push({ type: 'person', field: 'artist', key: t.artist, title: t.artist }) });
    if (!ANDROID) items.push({ label: 'Show in Explorer', icon: 'open_in_new', action: () => pa.showInFolder(t.path) });
    if (page.content.playlist) items.push('-', { label: 'Remove from Playlist', icon: 'delete', danger: true, action: () => { this.store.removeFromPlaylist(page.content.playlist, page.content.tracks.indexOf(t)); this.refresh(); } });
    if (page.content.queue) items.push('-', { label: 'Remove from Queue', icon: 'delete', danger: true, action: () => { p.userQueue.splice(i, 1); p.dispatchEvent(new Event('queue')); } });
    menu(x, y, items);
  }

  _itemMenu(item, x, y) {
    if (!item.node) return;
    const tracks = this._tracksForNode(item.node);
    const items = [
      { header: item.title },
      { label: 'Play', icon: 'play', action: () => this.player.playList(tracks, 0, { title: item.title, node: item.node }, { shuffle: false }) },
      { label: 'Shuffle', icon: 'shuffle', action: () => this.player.playList(tracks, -1, { title: item.title, node: item.node }, { shuffle: true }) },
      { label: 'Play Next', icon: 'play_next', action: () => { this.player.enqueue(tracks, true); toast(`${tracks.length} tracks will play next`); } },
      { label: 'Add to Queue', icon: 'queue', action: () => { this.player.enqueue(tracks); toast(`${tracks.length} tracks added to Queue`); } },
      { label: 'Add to Playlist…', icon: 'playlist_add', action: () => this.addToPlaylist(tracks) },
    ];
    if (item.playlist) {
      items.push('-',
        { label: 'Rename', icon: 'lyrics', action: async () => { const n = await prompt('Rename playlist', item.playlist.name); if (n) { item.playlist.name = n; this.store.savePlaylists(); this.refresh(); } } },
        { label: 'Delete', icon: 'delete', danger: true, action: async () => { if (await confirmSheet('Delete playlist', `Delete "${item.playlist.name}"?`, 'Delete')) { this.store.deletePlaylist(item.playlist); this.refresh(); } } });
    }
    menu(x, y, items);
  }

  _tracksForNode(node) {
    const c = this.resolve(node);
    if (c.tracks && c.tracks.length) return c.tracks;
    // container nodes: gather all tracks underneath
    if (node.type === 'person') return this.store.sortTracks(this.store.tracks.filter((t) => t[node.field] === node.key), 'album');
    if (node.type === 'hier') {
      const pre = node.path.toLowerCase();
      return this.store.tracks.filter((t) => { const f = t.folder.toLowerCase(); return f === pre || f.startsWith(pre + '\\') || f.startsWith(pre + '/'); })
        .sort((a, b) => a.path.localeCompare(b.path));
    }
    return [];
  }

  // ---------- previous / next category ----------

  // The categories beside one, in its parent's list as the library shows it: albums for an album, the folders
  // in the same parent folder for a folder, and so on. Null for lists that have none (All Songs, search...).
  _siblings(node) {
    let parent = { folder: 'folders', album: 'albums', genre: 'genres', year: 'years', playlist: 'playlists' }[node.type];
    if (node.type === 'person' || node.type === 'personAll') parent = { artist: 'artists', albumArtist: 'albumArtists', composer: 'composers' }[node.field];
    if (parent) return (this.resolve({ type: parent, title: '' }).items || []).filter((it) => it.node);
    if (node.type !== 'hier' || !node.path) return null;
    const cut = Math.max(node.path.lastIndexOf('\\'), node.path.lastIndexOf('/'));
    const inParent = cut > 0 ? this.resolve({ type: 'hier', path: node.path.slice(0, cut), title: '' }).items : [];
    return inParent.some((it) => it.node.path === node.path) ? inParent : this.resolve({ type: 'hier', path: null }).items; // a top folder
  }

  // Plays the category before (dir -1) or after (dir 1) the one playing, skipping empty ones and wrapping around.
  stepCategory(dir) {
    const node = this.player.source?.node;
    const sibs = node && this._siblings(node);
    if (!sibs || sibs.length < 2) { toast(`No other categories beside ${node?.title || 'this list'}`); return; }
    const type = node.type === 'personAll' ? 'person' : node.type;
    let i = sibs.findIndex((it) => it.node.type === type && it.node.key === node.key && it.node.path === node.path && it.node.field === node.field);
    if (i < 0) i = dir > 0 ? -1 : 0;
    for (let n = 0; n < sibs.length; n++) {
      i = (i + dir + sibs.length) % sibs.length;
      const it = sibs[i], tracks = this._tracksForNode(it.node);
      if (!tracks.length) continue;
      const shuffle = !!this.store.settings.shuffle;
      this.player.playList(this.store.sortTracks(tracks, this.store.settings.sorts[it.node.type] || 'default'), shuffle ? -1 : 0, { title: it.title, node: it.node }, { shuffle });
      toast(`${dir > 0 ? 'Next' : 'Previous'} category: ${it.title}`);
      return;
    }
  }

  async addToPlaylist(tracks) {
    const opts = [{ label: 'New Playlist…', value: '__new', icon: 'add' }, ...this.store.playlists.map((p) => ({ label: p.name, value: p.id, icon: 'playlist_play', hint: `${p.ids.length}` }))];
    const v = await chooser('Add to Playlist', opts);
    if (!v) return;
    if (v === '__new') {
      const name = await prompt('New Playlist', '', 'Playlist name');
      if (!name) return;
      this.store.createPlaylist(name, tracks.map((t) => t.id));
      toast(`Created "${name}"`);
    } else {
      const p = this.store.playlist(v);
      this.store.addToPlaylist(p, tracks.map((t) => t.id));
      toast(`Added to "${p.name}"`);
    }
    this.stack.forEach((p) => { if (['playlists', 'playlist', 'root'].includes(p.node.type)) p.dirty = true; });
    if (this.top.dirty) this.refresh();
  }

  async newPlaylist() {
    const name = await prompt('New Playlist', '', 'Playlist name');
    if (!name) return;
    this.store.createPlaylist(name);
    this.refresh();
  }

  listOptions(btn) {
    const page = this.top;
    const items = [];
    if (page.content.kind !== 'root' && page.content.kind !== 'search') {
      const view = this._view(page);
      items.push({ header: 'View' },
        { label: 'List view', checked: view === 'list', action: () => this._setView(page, 'list') },
        { label: 'Grid view', checked: view === 'grid', action: () => this._setView(page, 'grid') }, '-');
    }
    if (page.content.kind === 'tracks' || page.tracks?.length) {
      const cur = this.store.settings.sorts[page.node.type] || 'default';
      items.push({ header: 'Sort by' });
      for (const [k, s] of Object.entries(SORTS)) {
        items.push({ label: s.label, checked: cur === k, action: () => { this.store.settings.sorts[page.node.type] = k; this.store.saveSettings(); this.refresh(); } });
      }
      items.push('-', { label: 'Add All to Queue', icon: 'queue', action: () => { this.player.enqueue(page.tracks); toast(`${page.tracks.length} tracks added to Queue`); } });
      items.push({ label: 'Save as Playlist…', icon: 'playlist_add', action: () => this.addToPlaylist(page.tracks) });
      if (page.content.queue) items.push({ label: 'Clear Queue', icon: 'delete', danger: true, action: () => { this.player.userQueue = []; this.player.dispatchEvent(new Event('queue')); } });
      items.push('-');
    }
    items.push({ label: 'Zoom In', icon: 'add', hint: 'Ctrl+Wheel', action: () => this._zoom(1.15) });
    items.push({ label: 'Zoom Out', icon: 'remove', action: () => this._zoom(0.87) });
    items.push('-', { label: 'Rescan Library', icon: 'refresh', action: () => this.app.rescan(true) });
    items.push({ label: 'Add Music Folder…', icon: 'folder', action: () => this.app.addFolder() });
    menuAt(btn, items);
  }

  _zoom(k) {
    const z = clamp((this.store.settings.libZoom || 1) * k, 0.6, 1.8);
    this.store.settings.libZoom = z;
    this.store.saveSettings();
    this.top.el.querySelectorAll('.item-grid, .item-list').forEach((g) => g.style.setProperty('--zoom', z));
  }

  // ---------- search ----------
  toggleSearch(on, silent) {
    this.searching = on;
    $('#library').classList.toggle('searching', on);
    if (on) {
      const inp = $('#searchInput');
      inp.value = '';
      setTimeout(() => inp.focus(), 60);
      this.push({ type: 'search' });
    } else if (!silent) {
      if (this.top?.node.type === 'search') {
        const cur = this.stack.pop();
        this.top.el.style.display = '';
        this._header();
        this._transition(cur.el, this.top.el, -1, true, () => cur.el.remove());
      }
    } else if (this.top?.node.type === 'search') {
      this.stack.pop().el.remove();
    }
  }

  _buildSearch(page) {
    page.el.innerHTML = `<div class="scroller"><div class="search-results"><div class="empty">Type to search your library</div></div></div>`;
    page.scroller = page.el.firstElementChild;
  }

  _renderSearch() {
    const page = this.top;
    if (page?.node.type !== 'search') return;
    const r = this.store.search($('#searchInput').value);
    const host = page.el.querySelector('.search-results');
    if (!r) { host.innerHTML = '<div class="empty">Type to search your library</div>'; return; }
    const sec = (title, html) => html ? `<div class="sec-title">${title}</div>${html}` : '';
    const items = [];
    const it = (o) => { items.push(o); return this._itemHtml(o, items.length - 1, false); };
    host.innerHTML =
      sec('Artists', r.artists.map((a) => it({ title: a.title, sub: `${a.tracks.length} tracks`, cover: a.cover, icon: 'person', round: true, node: { type: 'person', field: 'artist', key: a.key, title: a.title } })).join('')) +
      sec('Albums', r.albums.map((a) => it({ title: a.title, sub: a.artist, cover: a.cover, icon: 'album', node: { type: 'album', key: a.key, title: a.title } })).join('')) +
      sec('Playlists', r.playlists.map((p) => it({ title: p.name, sub: `${p.ids.length} tracks`, icon: 'playlist_play', node: { type: 'playlist', key: p.id, title: p.name } })).join('')) +
      sec('Genres', r.genres.map((g) => it({ title: g.title, sub: `${g.tracks.length} tracks`, icon: 'genre', node: { type: 'genre', key: g.key, title: g.title } })).join('')) +
      sec('Songs', r.songs.map((t, i) => `<button class="row track static" data-song="${i}">
        <div class="it-art sm ${t.cover ? '' : 'ph'}" ${t.cover ? `style="background-image:url('${coverUrl(t.cover, true)}')"` : ''}>${t.cover ? '' : icon('music_note')}</div>
        <div class="r-text"><div class="r-title">${esc(t.title)}</div><div class="r-sub">${esc([t.artist, t.album].filter(Boolean).join(' · '))}</div></div><span class="r-dur">${fmtTime(t.duration)}</span></button>`).join(''))
      || '<div class="empty">No results</div>';
    host.onclick = (e) => {
      const s = e.target.closest('[data-song]');
      if (s) {
        this.player.playList(r.songs, +s.dataset.song, { title: 'Search', node: null });
        if (this.app.narrow) this.app.showPlayer();
        return;
      }
      const i = e.target.closest('[data-item]');
      if (i) { const item = items[+i.dataset.item]; this.toggleSearch(false, true); this.push(item.node); }
    };
  }

  // ---------- navbar (mini player) ----------
  _bindNavbar() {
    const nb = $('#navbar'), inner = $('#nbInner');
    $('#nbPlay').onclick = (e) => { e.stopPropagation(); this.player.toggle(); };
    $('#nbPrev').onclick = (e) => { e.stopPropagation(); this.player.prev(); };
    $('#nbNext').onclick = (e) => { e.stopPropagation(); this.player.next(); };
    this.player.addEventListener('track', (e) => {
      const t = e.detail;
      inner.animate([{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }], { duration: 300, easing: 'ease-out' });
      $('#nbTitle').textContent = t.title;
      $('#nbArtist').textContent = [t.artist, t.album].filter(Boolean).join(' · ');
      const art = $('#nbArt');
      art.style.backgroundImage = t.cover ? `url('${coverUrl(t.cover, true)}')` : '';
      art.innerHTML = t.cover ? '' : icon('music_note');
    });
    let sx = null, id = null, moved = false, st = 0;
    inner.addEventListener('pointerdown', (e) => { sx = e.clientX; id = e.pointerId; moved = false; st = performance.now(); });
    inner.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      const dx = e.clientX - sx;
      if (Math.abs(dx) > 6) { moved = true; inner.setPointerCapture(id); }
      if (moved) inner.style.transform = `translateX(${dx * 0.6}px)`;
    });
    inner.addEventListener('pointerup', (e) => {
      if (e.pointerId !== id) return;
      id = null;
      const dx = e.clientX - sx;
      inner.animate([{ transform: inner.style.transform }, { transform: 'none' }], { duration: 260, easing: 'cubic-bezier(.2,.8,.2,1)' });
      inner.style.transform = '';
      if (!moved) {
        if (performance.now() - st > 550 && this.player.source?.node) this.openNode(this.player.source.node); // long press -> track category
        else this.app.showPlayer();
      } else if (Math.abs(dx) > 70) dx < 0 ? this.player.next() : this.player.prev(true);
    });
  }

  // Swipe right on a list to go to the parent category.
  _bindSwipeBack() {
    let sx, sy, id = null, active = false, pageEl;
    this.pagesEl.addEventListener('pointerdown', (e) => {
      if (e.button !== 0 || e.pointerType === 'mouse' && !e.altKey && e.clientX - this.pagesEl.getBoundingClientRect().left > 30) return;
      sx = e.clientX; sy = e.clientY; id = e.pointerId; active = false; pageEl = this.top.el;
    });
    this.pagesEl.addEventListener('pointermove', (e) => {
      if (e.pointerId !== id) return;
      const dx = e.clientX - sx, dy = e.clientY - sy;
      if (!active && dx > 12 && Math.abs(dx) > Math.abs(dy) * 1.5 && this.stack.length > 1) { active = true; this.pagesEl.setPointerCapture(id); }
      if (active) { pageEl.style.transform = `translateX(${Math.max(0, dx)}px)`; pageEl.style.opacity = String(1 - Math.max(0, dx) / 600); }
    });
    const end = (e) => {
      if (e.pointerId !== id) return;
      id = null;
      if (!active) return;
      const dx = e.clientX - sx;
      pageEl.style.transform = ''; pageEl.style.opacity = '';
      if (dx > 90) this.back();
      else pageEl.animate([{ transform: `translateX(${dx}px)` }, { transform: 'none' }], { duration: 220, easing: 'ease-out' });
    };
    this.pagesEl.addEventListener('pointerup', end);
    this.pagesEl.addEventListener('pointercancel', end);
  }
}

export { setIcon };
