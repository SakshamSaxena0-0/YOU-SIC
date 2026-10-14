import { $, esc } from './util.js';
import { icon, hydrateIcons } from './icons.js';

let toastT;
export function toast(msg, ms = 2400) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastT);
  toastT = setTimeout(() => el.classList.remove('show'), ms);
}

// ---------- context menu ----------
let menuClose = null;
export function menu(x, y, items) {
  closeMenu();
  const el = $('#ctx');
  el.innerHTML = items.map((it, i) => {
    if (it === '-') return '<div class="menu-sep"></div>';
    if (it.header) return `<div class="menu-head">${esc(it.header)}</div>`;
    return `<button class="menu-item ${it.checked ? 'checked' : ''} ${it.danger ? 'danger' : ''}" data-i="${i}" ${it.disabled ? 'disabled' : ''}>
      <span class="mi-ico">${it.icon ? icon(it.icon) : it.checked ? icon('check') : ''}</span>
      <span class="mi-label">${esc(it.label)}</span>${it.hint ? `<span class="mi-hint">${esc(it.hint)}</span>` : ''}</button>`;
  }).join('');
  el.style.left = '0px'; el.style.top = '0px';
  el.classList.add('open');
  const r = el.getBoundingClientRect();
  const vw = innerWidth, vh = innerHeight;
  const left = Math.min(x, vw - r.width - 8), top = Math.min(y, vh - r.height - 8);
  el.style.left = `${Math.max(8, left)}px`;
  el.style.top = `${Math.max(8, top)}px`;
  el.style.transformOrigin = `${x - left}px ${y - top}px`;
  const onClick = (e) => {
    const b = e.target.closest('.menu-item');
    if (!b) return;
    const it = items[+b.dataset.i];
    closeMenu();
    it.action && it.action();
  };
  const outside = (e) => { if (!el.contains(e.target)) closeMenu(); };
  const key = (e) => { if (e.key === 'Escape') closeMenu(); };
  el.addEventListener('click', onClick);
  setTimeout(() => { addEventListener('pointerdown', outside, true); addEventListener('keydown', key, true); });
  addEventListener('blur', closeMenu, { once: true });
  menuClose = () => {
    el.removeEventListener('click', onClick);
    removeEventListener('pointerdown', outside, true);
    removeEventListener('keydown', key, true);
    el.classList.remove('open');
  };
}
addEventListener('resize', () => closeMenu());
export function closeMenu() { if (menuClose) { menuClose(); menuClose = null; } }

export function menuAt(btn, items) {
  const r = btn.getBoundingClientRect();
  menu(r.right - 220, r.bottom + 4, items);
}

// ---------- sheets (modal panels) ----------
let sheetResolve = null;
export function openSheet({ title, body, wide = false, onClose }) {
  closeMenu();
  const sheet = $('#sheet');
  const scrim = $('#scrim');
  sheet.className = 'sheet' + (wide ? ' wide' : '');
  sheet.innerHTML = `<div class="sheet-grip"></div>${title ? `<div class="sheet-title">${esc(title)}</div>` : ''}<div class="sheet-body"></div>`;
  const b = sheet.querySelector('.sheet-body');
  if (typeof body === 'string') b.innerHTML = body; else if (body) b.appendChild(body);
  hydrateIcons(sheet);
  requestAnimationFrame(() => { sheet.classList.add('open'); scrim.classList.add('open'); });
  const close = () => closeSheet();
  scrim.onclick = close;
  sheet._onClose = onClose;
  const key = (e) => { if (e.key === 'Escape') { e.stopPropagation(); closeSheet(); } };
  addEventListener('keydown', key, true);
  sheet._key = key;
  return b;
}

export function closeSheet() {
  const sheet = $('#sheet');
  if (!sheet.classList.contains('open')) return;
  sheet.classList.remove('open');
  $('#scrim').classList.remove('open');
  removeEventListener('keydown', sheet._key, true);
  sheet._onClose && sheet._onClose();
  if (sheetResolve) { sheetResolve(null); sheetResolve = null; }
}

export function prompt(title, value = '', placeholder = '') {
  return new Promise((resolve) => {
    const body = openSheet({
      title,
      body: `<input class="text-input" value="${esc(value)}" placeholder="${esc(placeholder)}" spellcheck="false">
        <div class="sheet-actions"><button class="btn ghost" data-a="cancel">Cancel</button><button class="btn" data-a="ok">OK</button></div>`,
    });
    sheetResolve = resolve;
    const inp = body.querySelector('input');
    setTimeout(() => { inp.focus(); inp.select(); }, 50);
    const done = (v) => { sheetResolve = null; resolve(v); closeSheet(); };
    body.querySelector('[data-a=ok]').onclick = () => done(inp.value.trim() || null);
    body.querySelector('[data-a=cancel]').onclick = () => done(null);
    inp.onkeydown = (e) => { if (e.key === 'Enter') done(inp.value.trim() || null); };
  });
}

export function confirmSheet(title, text, okLabel = 'OK') {
  return new Promise((resolve) => {
    const body = openSheet({
      title,
      body: `<p class="sheet-text">${esc(text)}</p><div class="sheet-actions"><button class="btn ghost" data-a="cancel">Cancel</button><button class="btn" data-a="ok">${esc(okLabel)}</button></div>`,
    });
    sheetResolve = resolve;
    const done = (v) => { sheetResolve = null; resolve(v); closeSheet(); };
    body.querySelector('[data-a=ok]').onclick = () => done(true);
    body.querySelector('[data-a=cancel]').onclick = () => done(false);
  });
}

// Generic list chooser inside a sheet.
export function chooser(title, options, current) {
  return new Promise((resolve) => {
    const body = openSheet({
      title,
      body: `<div class="choice-list">${options.map((o, i) => `<button class="row choice ${o.value === current ? 'sel' : ''}" data-i="${i}">
        ${o.icon ? `<span class="choice-ico">${icon(o.icon)}</span>` : ''}<span class="choice-label">${esc(o.label)}</span>
        ${o.hint ? `<span class="choice-hint">${esc(o.hint)}</span>` : ''}${o.value === current ? `<span class="choice-check">${icon('check')}</span>` : ''}</button>`).join('')}</div>`,
    });
    sheetResolve = resolve;
    body.onclick = (e) => {
      const b = e.target.closest('.choice');
      if (!b) return;
      sheetResolve = null;
      resolve(options[+b.dataset.i].value);
      closeSheet();
    };
    const sel = body.querySelector('.sel');
    if (sel) sel.scrollIntoView({ block: 'center' });
  });
}
