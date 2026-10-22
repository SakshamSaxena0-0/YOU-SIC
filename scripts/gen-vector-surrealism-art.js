// Generates the original SVG art for the Vector Surrealism theme's dark fantasy look (node scripts/gen-vector-surrealism-art.js) into
// renderer/assets/vector-surrealism: flat vector shapes, hard offset long shadows, no blur.
// A melting blood moon with ravens, crags with a castle and dead trees, a gothic doorway with a wisp in it,
// a storm cloud with a dragon's eye (and its lid), a skeletal hand holding up a crescent, melting candles on
// a ledge, a sky serpent, floating relics, a little star (for dividers). drip.svg and grain.svg are kept.
const fs = require('fs');
const path = require('path');
const out = process.argv[2] || path.join(__dirname, '..', 'renderer', 'assets', 'vector-surrealism');
fs.mkdirSync(out, { recursive: true });
const f = (n) => (Math.round(n * 10) / 10).toString();

// the theme's palette (renderer/css/vector-surrealism.tokens.css) plus a few shades for the art only
const C = {
  ink: '#0A0610', night: '#160E20', dusk: '#241933', duskHi: '#33234A', shade: '#040208',
  ember: '#EF7D45', mist: '#A79BC2', mistDk: '#7D6F9C', wisp: '#5ED6A8', wispDk: '#38A27E', wispLt: '#B5F2D8',
  gold: '#E8BF5F', goldDk: '#B98F3A', blood: '#EE5D72', crimson: '#B8394F', crimsonDk: '#8E2740', wine: '#5A1E33',
  bone: '#EEE2CB', boneDk: '#CDBE9F', stone: '#2C2140', stoneHi: '#3D2E57', storm: '#3A2B52',
};
const svg = (vb, body, extra = '') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vb}"${extra}>\n${body.trim()}\n</svg>\n`;
const write = (name, s) => { fs.writeFileSync(path.join(out, name), s); console.log(name, s.length); };
const rot = (x, y, a) => [x * Math.cos(a) - y * Math.sin(a), x * Math.sin(a) + y * Math.cos(a)];

// a bird in flight, wings up, ragged primaries (a raven against the moon)
const RAVEN = 'M0 0C10-10 22-14 34-8C40-4 44 2 46 6C48 2 52-4 58-8C70-14 82-10 92 0C84-4 76-4 70-2L74 4L66 2L64 8L58 4C54 8 50 12 46 15C42 12 38 8 34 4L28 8L26 2L18 4L22-2C16-4 8-4 0 0Z';
// a four-point star
const star = (x, y, r) => `M${f(x)} ${f(y - r)}L${f(x + r * 0.28)} ${f(y - r * 0.28)}L${f(x + r)} ${f(y)}L${f(x + r * 0.28)} ${f(y + r * 0.28)}L${f(x)} ${f(y + r)}L${f(x - r * 0.28)} ${f(y + r * 0.28)}L${f(x - r)} ${f(y)}L${f(x - r * 0.28)} ${f(y - r * 0.28)}Z`;

// ---------- the blood moon: big, low, melting, with two ravens crossing it ----------
write('blood-moon.svg', svg('0 0 400 440', `
  <!-- Vector Surrealism (dark fantasy): a giant blood moon that is slowly melting, two ravens crossing it (original artwork) -->
  <defs>
    <mask id="lit"><circle cx="200" cy="200" r="168" fill="#fff"/><circle cx="226" cy="222" r="166" fill="#000"/></mask>
  </defs>
  <circle cx="200" cy="200" r="198" fill="${C.blood}" opacity="0.07"/>
  <circle cx="200" cy="200" r="184" fill="${C.blood}" opacity="0.1"/>
  <!-- hard offset long shadow -->
  <circle cx="214" cy="214" r="168" fill="${C.shade}" opacity="0.5"/>
  <!-- the moon and its drips -->
  <path fill="${C.crimson}" d="M200 32a168 168 0 1 1-0.1 0Z
    M262 340h26v52a13 13 0 0 1-26 0Z
    M300 312h18v34a9 9 0 0 1-18 0Z
    M150 356h20v62a10 10 0 0 1-20 0Z"/>
  <!-- the lit rim, a flat crescent on the upper left -->
  <circle cx="200" cy="200" r="168" fill="${C.blood}" mask="url(#lit)"/>
  <!-- craters: flat discs, darker, with a lit lip -->
  <g fill="${C.crimsonDk}">
    <circle cx="140" cy="140" r="34"/>
    <circle cx="258" cy="118" r="20"/>
    <circle cx="236" cy="236" r="44"/>
    <circle cx="120" cy="262" r="16"/>
    <circle cx="302" cy="214" r="12"/>
  </g>
  <g fill="${C.crimson}">
    <circle cx="146" cy="146" r="24"/>
    <circle cx="244" cy="244" r="32"/>
  </g>
  <!-- ravens -->
  <g fill="${C.ink}">
    <path transform="translate(96 168) scale(1.15)" d="${RAVEN}"/>
    <path transform="translate(236 92) scale(0.62) rotate(-8)" d="${RAVEN}"/>
  </g>
`));

// ---------- crags: far peaks, a castle on a cliff, dead trees, fog, a melting ledge ----------
{
  const W = 1600, H = 360;
  // a jagged ridge through the given points, closed along the bottom
  const ridge = (pts) => `M0 ${H}L${pts.map(([x, y]) => `${f(x)} ${f(y)}`).join('L')}L${W} ${H}Z`;
  const far = ridge([[0, 196], [60, 170], [96, 186], [150, 132], [190, 168], [236, 150], [290, 196], [350, 172], [402, 120], [440, 158],
    [500, 176], [560, 150], [606, 168], [660, 128], [700, 160], [742, 116], [790, 150], [850, 176], [910, 140], [964, 172], [1020, 152],
    [1072, 184], [1130, 146], [1180, 170], [1216, 136], [1262, 160], [1320, 150], [1384, 170], [1440, 124], [1490, 162], [1546, 140], [1600, 166]]);
  // the mid ridge: a plateau on the right carries the castle; a ledge on the left melts over its edge
  const mid = `M0 ${H}L0 250L40 238L70 244L110 226L140 240L190 216L220 230L262 222L300 240L344 226L380 236L430 230L470 250C520 256 560 250 600 252L600 288a10 10 0 0 0 20 0V256
    L660 240L700 248L740 232L790 252L840 230L880 238L924 222L960 246L1000 234L1040 240L1086 226L1120 246L1170 232L1196 218L1214 214L1236 212L1452 212L1466 222L1494 218L1520 236L1556 226L1600 234L${W} ${H}Z`;
  const near = `M0 ${H}L0 318C220 300 420 326 640 316C860 306 1040 330 1260 314C1400 304 1520 318 1600 310L${W} ${H}Z`;
  // dead trees: a trunk and forked branches, drawn as tapering strokes
  let trees = '';
  const tree = (x, y, h, s = 1) => {
    const seg = [];
    const branch = (x0, y0, a, len, w, depth) => {
      const x1 = x0 + Math.sin(a) * len, y1 = y0 - Math.cos(a) * len;
      seg.push(`<path d="M${f(x0)} ${f(y0)}L${f(x1)} ${f(y1)}" stroke-width="${f(w)}"/>`);
      if (depth <= 0) return;
      branch(x1, y1, a - 0.5 * s, len * 0.66, w * 0.62, depth - 1);
      branch(x1, y1, a + 0.42 * s, len * 0.58, w * 0.6, depth - 1);
    };
    branch(x, y, 0.06 * s, h * 0.42, h * 0.07, 3);
    trees += seg.join('');
  };
  tree(318, 242, 76, 1); tree(520, 254, 58, -1); tree(1010, 246, 64, 1); tree(1556, 240, 70, -1);
  // the castle: x0 is its middle, y0 the cliff top
  const x0 = 1344, y0 = 214;
  const rect = (x, y, w, h) => `M${f(x)} ${f(y)}h${f(w)}v${f(h)}h${f(-w)}Z`;
  const roof = (x, y, w, h) => `M${f(x)} ${f(y)}l${f(w / 2)} ${f(-h)}l${f(w / 2)} ${f(h)}Z`;
  let merlons = '';
  for (let x = x0 - 74; x < x0 + 70; x += 16) merlons += rect(x, y0 - 50, 9, 8);
  const castle = [
    rect(x0 - 74, y0 - 42, 148, 42), merlons,
    rect(x0 - 96, y0 - 96, 28, 96), roof(x0 - 102, y0 - 96, 40, 42),
    rect(x0 + 60, y0 - 84, 26, 84), roof(x0 + 55, y0 - 84, 36, 36),
    rect(x0 - 22, y0 - 128, 44, 128), roof(x0 - 30, y0 - 128, 60, 56),
    rect(x0 + 22, y0 - 108, 12, 26), roof(x0 + 20, y0 - 108, 16, 18),
    `M${x0} ${y0 - 184}v-22h2v22Z`,
  ].join('');
  const flag = `M${x0 + 2} ${y0 - 206}l18 5-18 5Z`;
  const gate = `M${x0 - 9} ${y0}v-16a9 9 0 0 1 18 0v16Z`;
  const lit = [rect(x0 - 2, y0 - 112, 4, 9), rect(x0 - 2, y0 - 88, 4, 9), rect(x0 - 84, y0 - 76, 4, 8), rect(x0 + 71, y0 - 60, 4, 8)].join('');
  // a far, smaller castle on the left peaks (seen in narrow windows)
  const fx = 742, fy = 118;
  const farCastle = [rect(fx - 9, fy - 30, 18, 30), roof(fx - 12, fy - 30, 24, 26), rect(fx - 26, fy - 16, 12, 18), roof(fx - 28, fy - 16, 16, 14), rect(fx + 13, fy - 12, 10, 14), roof(fx + 11, fy - 12, 14, 12)].join('');
  // fog: flat, low, translucent bands
  const pill = (x, y, w, h) => `M${f(x + h / 2)} ${f(y)}h${f(w - h)}a${f(h / 2)} ${f(h / 2)} 0 0 1 0 ${f(h)}h${f(h - w)}a${f(h / 2)} ${f(h / 2)} 0 0 1 0-${f(h)}Z`;
  const fog = (rows) => rows.map(([x, y, w, h]) => pill(x, y, w, h)).join('');
  write('crags.svg', svg(`0 0 ${W} ${H}`, `
  <!-- Vector Surrealism (dark fantasy): jagged crags at night, a castle on a cliff with a few lit windows, dead trees,
       low fog and a ridge that melts over its edge (original artwork) -->
  <path fill="${C.stone}" d="${far}"/>
  <path fill="#1D1429" d="${farCastle}"/>
  <path fill="${C.mist}" opacity="0.06" d="${fog([[40, 198, 260, 9], [220, 208, 340, 7], [640, 192, 300, 9], [880, 204, 420, 7], [1300, 198, 260, 9]])}"/>
  <path fill="#221830" d="${mid}"/>
  <path fill="#1C1328" d="${castle}"/>
  <path fill="${C.crimson}" d="${flag}"/>
  <path fill="${C.ink}" d="${gate}"/>
  <path fill="${C.gold}" d="${lit}"/>
  <g fill="none" stroke="#120C1A" stroke-linecap="round">${trees}</g>
  <path fill="${C.ink}" d="${near}"/>
`, ' preserveAspectRatio="xMidYMax slice"'));
}

// ---------- the gothic doorway: a lancet arch standing alone, another sky inside, a wisp waiting in it ----------
write('gothic-door.svg', svg('0 0 300 280', `
  <!-- Vector Surrealism (dark fantasy): a pointed gothic doorway to nowhere, standing alone on the horizon, with a
       will-o'-wisp waiting inside (original artwork) -->
  <defs>
    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${C.ink}"/>
      <stop offset="0.62" stop-color="${C.wine}"/>
      <stop offset="1" stop-color="${C.crimson}"/>
    </linearGradient>
  </defs>
  <!-- its long shadow, thrown far across the ground -->
  <path fill="${C.shade}" opacity="0.55" d="M150 262L290 262L276 276L150 276Z"/>
  <!-- three steps up to it -->
  <path fill="${C.dusk}" d="M36 262h128v14H36Z M48 250h104v12H48Z"/>
  <!-- the frame: an equilateral pointed arch -->
  <path fill="${C.mistDk}" d="M56 250V120A88 88 0 0 1 100 43.8A88 88 0 0 1 144 120V250Z"/>
  <path fill="${C.mist}" d="M56 250V120A88 88 0 0 1 100 43.8L100 52A80 80 0 0 0 63 120V250Z"/>
  <!-- the opening: another sky, with its own little crescent -->
  <path fill="url(#sky)" d="M70 250V124A60 60 0 0 1 100 72A60 60 0 0 1 130 124V250Z"/>
  <circle cx="112" cy="104" r="9" fill="${C.bone}"/>
  <circle cx="116" cy="101" r="8" fill="${C.ink}"/>
  <!-- the wisp -->
  <circle cx="100" cy="206" r="22" fill="${C.wisp}" opacity="0.14"/>
  <path fill="${C.wisp}" d="M100 180c7 9 11 16 11 22a11 11 0 0 1-22 0c0-6 4-13 11-22Z"/>
  <path fill="${C.wispLt}" d="M100 194c3 4 5 7 5 10a5 5 0 0 1-10 0c0-3 2-6 5-10Z"/>
  <!-- keystone -->
  <path fill="${C.gold}" d="M100 28l8 11-8 11-8-11Z"/>
`));

// ---------- the storm cloud with a dragon's eye, and its lid ----------
write('storm-eye.svg', svg('0 0 400 260', `
  <!-- Vector Surrealism (dark fantasy): a flat storm cloud with a dragon's eye open in it, and a bolt falling from
       it (original artwork). The eye spans x 140-260, y 108-172; eyelid.svg is laid over exactly that box to blink. -->
  <path fill="${C.shade}" opacity="0.5" transform="translate(12 12)" d="M70 200a50 50 0 0 1 20-96a66 66 0 0 1 120-38a58 58 0 0 1 104 34a48 48 0 0 1 16 100Z"/>
  <path fill="${C.storm}" d="M70 200a50 50 0 0 1 20-96a66 66 0 0 1 120-38a58 58 0 0 1 104 34a48 48 0 0 1 16 100Z"/>
  <!-- the lit tops of the billows -->
  <path fill="#4A3866" d="M90 104a66 66 0 0 1 120-38a58 58 0 0 1 104 34a50 50 0 0 0-104-20a62 62 0 0 0-120 24Z"/>
  <!-- a bolt -->
  <path fill="${C.gold}" d="M292 196l-16 30h13l-11 28 28-38h-13l11-20Z"/>
  <!-- the eye: amber, with a slit pupil -->
  <path fill="${C.gold}" d="M140 140Q200 92 260 140Q200 188 140 140Z"/>
  <circle cx="200" cy="140" r="23" fill="${C.ember}"/>
  <ellipse cx="200" cy="140" rx="5" ry="21" fill="${C.ink}"/>
  <circle cx="209" cy="130" r="3.5" fill="${C.bone}"/>
`));
write('eyelid.svg', svg('0 0 120 64', `
  <!-- Vector Surrealism (dark fantasy): the storm cloud's eyelid (original artwork). It covers the almond of
       storm-eye.svg; CSS scales it from 0 to 1 on Y, from the top, to make the eye blink. -->
  <path fill="${C.storm}" d="M-2 32Q60 -18 122 32Q60 82 -2 32Z"/>
  <!-- lashes as a flat crescent along the closed lid -->
  <path fill="${C.ink}" d="M8 34Q60 66 112 34Q60 58 8 34Z"/>
`, ' preserveAspectRatio="none"'));

// ---------- the skeletal hand holding up a crescent, rising out of a tattered sleeve ----------
{
  const bones = [];
  const seg = (x0, y0, x1, y1, w) => bones.push([x0, y0, x1, y1, w]);
  // forearm (into the sleeve), wrist bones, palm bones
  seg(130, 300, 128, 360, 15); seg(168, 300, 172, 360, 15);
  // fingers: knuckle, angle (from vertical), three phalanx lengths
  const fingers = [[112, 222, -0.2, [30, 21, 15]], [136, 214, -0.06, [34, 24, 16]], [160, 214, 0.06, [32, 23, 16]], [184, 224, 0.2, [25, 18, 13]]];
  const tips = [];
  for (const [kx, ky, a, ls] of fingers) {
    // the palm bone from the wrist to the knuckle
    seg(128 + (kx - 148) * 0.35 + 20, 282, kx, ky + 6, 10);
    let x = kx, y = ky;
    for (const l of ls) {
      const x1 = x + Math.sin(a) * l, y1 = y - Math.cos(a) * l;
      seg(x, y, x1, y1, 11);
      x = x1 + Math.sin(a) * 5; y = y1 - Math.cos(a) * 5;
    }
    tips.push([x, y]);
  }
  // the thumb
  seg(126, 280, 98, 254, 11); seg(94, 248, 82, 226, 11); seg(80, 220, 76, 204, 10);
  const knuckles = [];
  for (const [a, b, c, d, w] of bones) if (w <= 11) knuckles.push([c, d, w * 0.62]);
  const knobs = knuckles.map(([x, y, r]) => `<circle cx="${f(x)}" cy="${f(y)}" r="${f(r)}"/>`).join('');
  const lines = bones.map(([a, b, c, d, w]) => `<path d="M${f(a)} ${f(b)}L${f(c)} ${f(d)}" stroke-width="${w}"/>`).join('');
  const carpals = [[124, 288, 8], [140, 284, 8], [156, 284, 8], [172, 290, 8], [136, 298, 7], [156, 298, 7]].map(([x, y, r]) => `<circle cx="${x}" cy="${y}" r="${r}"/>`).join('');
  const top = Math.min(...tips.map((t) => t[1]));
  const cy = top - 52;
  const sleeve = 'M88 380V332l10-16 8 12 10-18 10 15 9-13 11 17 10-15 9 13 11-16 10 14 12-12V380Z';
  write('bone-hand.svg', svg('0 0 300 380', `
  <!-- Vector Surrealism (dark fantasy): a skeletal hand rising out of a tattered sleeve, holding up a crescent moon
       (original artwork) -->
  <defs>
    <mask id="crescent"><rect width="300" height="380" fill="#fff"/><circle cx="174" cy="${f(cy - 16)}" r="50" fill="#000"/></mask>
  </defs>
  <!-- long shadow -->
  <g transform="translate(12 10)" opacity="0.5">
    <g fill="none" stroke="${C.shade}" stroke-linecap="round">${lines}</g>
    <g fill="${C.shade}">${carpals}${knobs}<path d="${sleeve}"/></g>
  </g>
  <g fill="none" stroke="${C.bone}" stroke-linecap="round">${lines}</g>
  <g fill="${C.bone}">${knobs}</g>
  <g fill="${C.boneDk}">${carpals}</g>
  <!-- the sleeve, ragged at the hem -->
  <path fill="${C.wine}" d="${sleeve}"/>
  <path fill="${C.crimsonDk}" d="M88 352h124v8H88Z"/>
  <!-- the crescent, balanced on the fingertips -->
  <circle cx="160" cy="${f(cy + 8)}" r="58" fill="${C.shade}" opacity="0.5" mask="url(#crescent)" transform="translate(10 8)"/>
  <circle cx="150" cy="${f(cy)}" r="58" fill="${C.gold}" mask="url(#crescent)"/>
`));
}

// ---------- candles melting on a ledge that sticks out of the left edge ----------
write('candles.svg', svg('0 0 320 280', `
  <!-- Vector Surrealism (dark fantasy): three candles on a stone ledge, their wax pooling and running over the edge in
       long soft drips (original artwork) -->
  <!-- the ledge and its long shadow -->
  <path fill="${C.shade}" opacity="0.5" d="M178 150l40 26v16h-40Z"/>
  <path fill="${C.stoneHi}" d="M0 144h182v10H0Z"/>
  <path fill="${C.stone}" d="M0 154h182v24H0Z"/>
  <!-- long shadows of the candles -->
  <g fill="${C.shade}" opacity="0.45">
    <path d="M40 54h28v90h-28Z M84 92h22v52h-22Z M120 72h26v72h-26Z" transform="translate(9 0)"/>
  </g>
  <!-- the candles -->
  <g fill="${C.bone}">
    <path d="M30 56a4 4 0 0 1 4-4h20a4 4 0 0 1 4 4v88H30Z"/>
    <path d="M74 94a4 4 0 0 1 4-4h14a4 4 0 0 1 4 4v50H74Z"/>
    <path d="M110 74a4 4 0 0 1 4-4h18a4 4 0 0 1 4 4v70H110Z"/>
    <!-- wax pooled along the ledge, running over its end in long drips -->
    <path d="M22 144C22 138 30 138 40 140H150C176 140 184 142 186 150C188 160 188 170 188 196a7 7 0 0 1-14 0V178C174 170 170 168 166 172V226a8 8 0 0 1-16 0V160H22Z"/>
    <path d="M58 64h6v24a3 3 0 0 1-6 0Z M30 70h5v40a2.5 2.5 0 0 1-5 0Z M130 84h6v30a3 3 0 0 1-6 0Z M92 100h4v16a2 2 0 0 1-4 0Z"/>
  </g>
  <!-- the shaded side of each candle -->
  <g fill="${C.boneDk}">
    <path d="M50 52h4a4 4 0 0 1 4 4v88h-8Z M90 90h2a4 4 0 0 1 4 4v50h-6Z M128 70h4a4 4 0 0 1 4 4v70h-8Z M174 178v18a7 7 0 0 0 14 0V170c-2 4-8 6-14 8Z"/>
  </g>
  <!-- wicks and flames -->
  <g fill="${C.ink}"><path d="M43 42h2v10h-2Z M84 80h2v10h-2Z M122 60h2v10h-2Z"/></g>
  <g fill="${C.ember}">
    <path d="M44 14c6 8 9 14 9 19a9 9 0 0 1-18 0c0-5 3-11 9-19Z"/>
    <path d="M85 54c5 6 7 11 7 15a7 7 0 0 1-14 0c0-4 2-9 7-15Z"/>
    <path d="M123 32c6 7 8 12 8 17a8 8 0 0 1-16 0c0-5 2-10 8-17Z"/>
  </g>
  <g fill="${C.gold}">
    <path d="M44 26c3 4 4 7 4 9a4 4 0 0 1-8 0c0-2 1-5 4-9Z"/>
    <path d="M85 63c2 3 3 5 3 7a3 3 0 0 1-6 0c0-2 1-4 3-7Z"/>
    <path d="M123 42c3 3 4 6 4 8a4 4 0 0 1-8 0c0-2 1-5 4-8Z"/>
  </g>
`));

// ---------- a serpent swimming through the sky ----------
{
  const W = 640, N = 64, endX = 520;
  const cy = (x) => 104 + 40 * Math.sin((x / W) * Math.PI * 2.2 + 0.5);
  const th = (x) => 2.5 + 21 * Math.min(1, Math.pow(x / 470, 0.85));
  const top = [], bot = [], belly = [];
  for (let i = 0; i <= N; i++) {
    const x = (i / N) * endX, y = cy(x), t = th(x);
    top.push([x, y - t]); bot.push([x, y + t]); belly.push([x, y + t * 0.3]);
  }
  const pl = (pts) => pts.map(([x, y], i) => `${i ? 'L' : 'M'}${f(x)} ${f(y)}`).join('');
  const body = `${pl(top)}${pl(bot.slice().reverse()).replace(/^M/, 'L')}Z`;
  const bellyD = `${pl(belly)}${pl(bot.slice().reverse()).replace(/^M/, 'L')}Z`;
  // spines along the back
  let spines = '';
  for (let x = 70; x < endX - 30; x += 34) {
    const y = cy(x) - th(x), h = 5 + th(x) * 0.7, s = 4 + th(x) * 0.3;
    spines += `M${f(x - s)} ${f(y + 1)}L${f(x + s * 0.4)} ${f(y - h)}L${f(x + s)} ${f(y + 1)}Z`;
  }
  // the head, drawn pointing along +x and turned to follow the body's end
  const a = Math.atan2(cy(endX + 1) - cy(endX - 1), 2), hx = endX, hy = cy(endX);
  const T = (pts) => pts.map(([x, y]) => { const [u, v] = rot(x * 1.3, y * 1.3, a); return [hx + u, hy + v]; });
  const head = T([[-4, -18], [16, -22], [38, -20], [56, -12], [82, -6], [84, 2], [62, 6], [64, 12], [48, 11], [36, 16], [16, 20], [-4, 18]]);
  const horn = T([[12, -18], [0, -42], [26, -20]]);
  const jaw = T([[44, 4], [62, 6], [60, 9], [46, 8]]);
  const [ex, ey] = T([[36, -9]])[0];
  const poly = (pts) => `M${pts.map(([x, y]) => `${f(x)} ${f(y)}`).join('L')}Z`;
  const shape = `${body}${poly(head)}`;
  write('serpent.svg', svg(`0 0 ${W} 200`, `
  <!-- Vector Surrealism (dark fantasy): a long serpent swimming through the sky like a ribbon, gold spines along its
       back (original artwork) -->
  <path fill="${C.shade}" opacity="0.4" transform="translate(10 10)" d="${shape}"/>
  <path fill="${C.goldDk}" d="${spines}"/>
  <path fill="${C.crimsonDk}" d="${shape}"/>
  <path fill="${C.boneDk}" d="${bellyD}"/>
  <path fill="${C.gold}" d="${poly(horn)}"/>
  <path fill="${C.wine}" d="${poly(jaw)}"/>
  <circle cx="${f(ex)}" cy="${f(ey)}" r="4.5" fill="${C.gold}"/>
  <ellipse cx="${f(ex)}" cy="${f(ey)}" rx="1.2" ry="3.6" fill="${C.ink}"/>
`));
}

// ---------- relics floating in the sky at mismatched scales ----------
{
  // the key: a ring bow, a shaft and a bit, laid along +x, then turned
  const keyAt = (x, y, s, a) => `<g transform="translate(${x} ${y}) rotate(${a}) scale(${s})">
    <path fill="${C.gold}" fill-rule="evenodd" d="M0-18a18 18 0 1 1-0.1 0Z M0-8a8 8 0 1 0 0.1 0Z M16-4h60v8H16Z M60 4h6v14h-6Z M70 4h6v10h-6Z"/>
    <path fill="${C.goldDk}" d="M16 1h60v3H16Z"/>
  </g>`;
  const swordAt = (x, y, s, a) => `<g transform="translate(${x} ${y}) rotate(${a}) scale(${s})">
    <path fill="${C.bone}" d="M0-5h90l14 5-14 5H0Z"/>
    <path fill="${C.boneDk}" d="M0 0h104l-14 5H0Z"/>
    <path fill="${C.gold}" d="M-6-16h6v32h-6Z M-34-3h28v6h-28Z"/>
    <circle cx="-38" cy="0" r="6" fill="${C.blood}"/>
  </g>`;
  write('floaters.svg', svg('0 0 800 400', `
  <!-- Vector Surrealism (dark fantasy): relics floating in the sky at mismatched scales (original artwork): a rock
       island with a tower, a key, crystal shards, a potion, a sword and a few stars -->
  <!-- the island and its tower, roots hanging below; its shadow floats under it -->
  <ellipse cx="96" cy="176" rx="40" ry="5" fill="${C.shade}" opacity="0.5"/>
  <path fill="${C.stone}" d="M34 110h124l-16 18-20 8-10 24-14-18-20-4-20 12-12-34Z"/>
  <path fill="${C.stoneHi}" d="M34 110h124v6H34Z"/>
  <path fill="${C.duskHi}" d="M76 110V54h22v56Z"/>
  <path fill="${C.crimsonDk}" d="M71 54l16-38 16 38Z"/>
  <path fill="${C.gold}" d="M84 76a3 3 0 0 1 6 0v10h-6Z"/>
  <path fill="${C.stone}" d="M104 154h3v14a1.5 1.5 0 0 1-3 0Z M70 146h3v10a1.5 1.5 0 0 1-3 0Z"/>
  <!-- the key -->
  <ellipse cx="336" cy="148" rx="30" ry="4" fill="${C.shade}" opacity="0.5"/>
  ${keyAt(300, 70, 0.9, 24)}
  <!-- crystal shards -->
  <ellipse cx="566" cy="126" rx="26" ry="4" fill="${C.shade}" opacity="0.5"/>
  <path fill="${C.wisp}" d="M560 32l14 22v40l-14 12-14-12V54Z"/>
  <path fill="${C.wispDk}" d="M560 32l14 22v40l-14 12Z"/>
  <path fill="${C.wisp}" d="M540 66l8 12v22l-8 6-8-6V78Z M586 74l7 10v18l-7 6-7-6V84Z"/>
  <path fill="${C.wispDk}" d="M540 66l8 12v22l-8 6Z M586 74l7 10v18l-7 6Z"/>
  <!-- a potion -->
  <ellipse cx="692" cy="214" rx="20" ry="4" fill="${C.shade}" opacity="0.5"/>
  <path fill="${C.mist}" d="M684 128h16v16a22 22 0 1 1-16 0Z"/>
  <path fill="${C.blood}" d="M670 166h44a22 22 0 1 1-44 0Z"/>
  <path fill="${C.goldDk}" d="M682 120h20v10h-20Z"/>
  <!-- a sword -->
  <ellipse cx="222" cy="330" rx="36" ry="4" fill="${C.shade}" opacity="0.5"/>
  ${swordAt(170, 290, 0.8, -28)}
  <!-- stars: four-point, flat -->
  <g fill="${C.bone}">
    <path d="${star(460, 216, 12)}"/>
    <path d="${star(150, 330, 8)}"/>
    <path d="${star(740, 300, 8)}"/>
    <circle cx="620" cy="250" r="3"/>
    <circle cx="40" cy="240" r="2.5"/>
    <circle cx="390" cy="330" r="2.5"/>
    <circle cx="250" cy="40" r="2"/>
  </g>
`));
}

// ---------- a little gold star, for section dividers ----------
write('star.svg', svg('0 0 24 24', `
  <!-- Vector Surrealism (dark fantasy): a four-point star (original artwork) -->
  <path fill="${C.gold}" d="${star(12, 12, 11)}"/>
`));
