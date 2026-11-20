// Cursor spotlight for a grid of cards (the library's buttons; css/spotlight.css draws it).
//
// While a mouse moves over the host, every card within `reach` px of the pointer gets a light: a small
// element of its own (span.spot-light, made the first time the card is lit) with the class .on and the
// pointer's position in the card as --mouse-x / --mouse-y. The stylesheet turns those into a fine dot matrix
// and a soft white glow round the pointer (clipped to the card) and lights the rims of the cards near it.
// A card that falls out of reach loses .on (its light is already nothing there); when the pointer leaves
// the host, the lights get .fade too and fade out.
//
// Cost: nothing while the pointer rests or is elsewhere. While it moves (or the grid scrolls under it), one
// update per frame at most: every card rectangle is read first, then the styles are written, so an update
// never forces a second layout. Only the light elements are restyled, never the cards themselves (a card
// carries many rules and two pseudo-elements; restyling one costs ~30 times as much as its light). Touch is
// ignored (no pointer to follow).
//
//   new Spotlight(hostEl, { scope: '.lib-page', card: '.tile', reach: 130, enabled: () => true })
//     scope    the part of the host whose cards light together (the page under the pointer)
//     card     the cards
//     reach    how far from a card the pointer still lights its rim, px (≥ the stylesheet's light radius)
//     enabled  checked on every update; when it returns false the cards go dark
export class Spotlight {
  constructor(host, { scope = '.lib-page', card = '.tile', reach = 130, enabled = () => true } = {}) {
    this.host = host;
    this.scope = scope;
    this.card = card;
    this.reach = reach;
    this.enabled = enabled;
    this.lit = new Set(); // lights that are .on
    this.pt = null; // the pointer, while it is over the host
    this.raf = 0;
    const queue = () => { if (!this.raf) this.raf = requestAnimationFrame(() => { this.raf = 0; this.update(); }); };
    host.addEventListener('pointermove', (e) => {
      if (e.pointerType === 'touch') return;
      this.pt = { x: e.clientX, y: e.clientY };
      queue();
    }, { passive: true });
    host.addEventListener('pointerleave', () => { this.pt = null; queue(); });
    // the grid moves under a still pointer when it scrolls, and a click can open another page under it
    host.addEventListener('scroll', () => { if (this.pt) queue(); }, { capture: true, passive: true });
    host.addEventListener('click', () => { if (this.pt) setTimeout(queue, 60); });
    addEventListener('blur', () => { this.pt = null; queue(); });
  }

  // the card's light element (cards can be rebuilt, so it is looked for each time)
  static light(card) {
    const last = card.lastElementChild;
    if (last?.classList.contains('spot-light')) return last;
    let l = card.querySelector(':scope > .spot-light');
    if (!l) {
      l = document.createElement('span');
      l.className = 'spot-light';
      l.setAttribute('aria-hidden', 'true');
      card.append(l);
    }
    return l;
  }

  update() {
    const pt = this.pt;
    const near = [];
    // read: which page is under the pointer, and where its cards are
    if (pt && this.enabled()) {
      const el = document.elementFromPoint(pt.x, pt.y);
      const scope = el && this.host.contains(el) ? el.closest(this.scope) : null;
      if (scope) {
        const reach2 = this.reach * this.reach;
        for (const c of scope.querySelectorAll(this.card)) {
          const r = c.getBoundingClientRect();
          if (!r.width) continue;
          const dx = Math.max(r.left - pt.x, 0, pt.x - r.right);
          const dy = Math.max(r.top - pt.y, 0, pt.y - r.bottom);
          if (dx * dx + dy * dy < reach2) near.push([c, pt.x - r.left, pt.y - r.top]);
        }
      }
    }
    // write
    const lit = new Set();
    for (const [c, x, y] of near) {
      const l = Spotlight.light(c);
      l.style.setProperty('--mouse-x', `${x.toFixed(1)}px`);
      l.style.setProperty('--mouse-y', `${y.toFixed(1)}px`);
      l.classList.remove('fade');
      l.classList.add('on');
      lit.add(l);
    }
    for (const l of this.lit) if (!lit.has(l)) { if (!near.length) l.classList.add('fade'); l.classList.remove('on'); }
    this.lit = lit;
  }
}
