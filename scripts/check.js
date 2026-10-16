// Project checks (npm run check). There is no linter or test suite yet; this covers what can break silently:
//  1. every JavaScript file parses,
//  2. the Y2K, Vector Surrealism and Liminal component rules use only design tokens (no literal colours),
//  3. every bundled font ships with its licence.
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
let failed = 0;
const fail = (msg) => { failed++; console.error('  FAIL ' + msg); };

console.log('1. syntax');
const js = ['main.js', 'preload.js', ...fs.readdirSync(path.join(root, 'renderer/js')).filter((f) => f.endsWith('.js')).map((f) => 'renderer/js/' + f)];
for (const f of js) {
  const r = spawnSync(process.execPath, ['--check', path.join(root, f)], { encoding: 'utf8' });
  if (r.status !== 0) fail(`${f}\n${r.stderr}`);
}
console.log(`  ${js.length} files`);

console.log('2. Y2K theme tokens');
const css = fs.readFileSync(path.join(root, 'renderer/css/subway.css'), 'utf8');
const cut = css.indexOf('/* ---------- §0 materials');
if (cut < 0) fail('subway.css: token block marker not found');
const literals = css.slice(cut).match(/#[0-9a-fA-F]{3,8}\b|rgba?\(/g) || [];
if (literals.length) fail(`subway.css: ${literals.length} literal colour(s) in component rules: ${literals.slice(0, 5).join(', ')}`);
else console.log('  no literal colours in component rules');

console.log('2b. Vector Surrealism tokens');
const vs = fs.readFileSync(path.join(root, 'renderer/css/vector-surrealism.css'), 'utf8');
const vsLit = vs.match(/#[0-9a-fA-F]{3,8}|rgba?\(|hsla?\(/g) || [];
if (vsLit.length) fail(`vector-surrealism.css: ${vsLit.length} literal colour(s) (use the tokens in vector-surrealism.tokens.css): ${vsLit.slice(0, 5).join(', ')}`);
else console.log('  no literal colours in component rules');
for (const a of (fs.readFileSync(path.join(root, 'renderer/css/vector-surrealism.tokens.css'), 'utf8').match(/\/renderer\/assets\/[^')]+/g) || [])) {
  if (!fs.existsSync(path.join(root, a))) fail(`vector-surrealism.tokens.css: missing asset ${a}`);
}

console.log('2c. Liminal tokens');
const lm = fs.readFileSync(path.join(root, 'renderer/css/liminal.css'), 'utf8');
const lmLit = lm.match(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/g) || [];
if (lmLit.length) fail(`liminal.css: ${lmLit.length} literal colour(s) (use the tokens in liminal.tokens.css): ${lmLit.slice(0, 5).join(', ')}`);
else console.log('  no literal colours in component rules');
for (const a of (fs.readFileSync(path.join(root, 'renderer/css/liminal.tokens.css'), 'utf8').match(/\/renderer\/assets\/[^')]+/g) || [])) {
  if (!fs.existsSync(path.join(root, a))) fail(`liminal.tokens.css: missing asset ${a}`);
}

console.log('3. font licences');
const fonts = path.join(root, 'renderer/fonts');
const files = fs.readdirSync(fonts);
const need = { 'Michroma-Regular.ttf': 'OFL-Michroma.txt', 'ShareTechMono-Regular.ttf': 'OFL-ShareTechMono.txt', 'PressStart2P-Regular.ttf': 'OFL-PressStart2P.txt', 'VT323-Regular.ttf': 'OFL-VT323.txt', 'manrope-latin-wght-normal.woff2': 'OFL-Manrope.txt', 'manrope-latin-ext-wght-normal.woff2': 'OFL-Manrope.txt', 'jetbrains-mono-latin-wght-normal.woff2': 'OFL-JetBrainsMono.txt', 'fraunces-latin-full-normal.woff2': 'OFL-Fraunces.txt', 'space-grotesk-latin-wght-normal.woff2': 'OFL-SpaceGrotesk.txt', 'cormorant-garamond-latin-wght-normal.woff2': 'OFL-CormorantGaramond.txt', 'cormorant-garamond-latin-wght-italic.woff2': 'OFL-CormorantGaramond.txt', 'ibm-plex-sans-latin-wght-normal.woff2': 'OFL-IBMPlexSans.txt', 'DSEG7Classic-Italic.woff2': 'OFL-DSEG.txt' };
for (const f of files.filter((x) => /\.(ttf|otf|woff2?)$/.test(x))) {
  if (!need[f]) fail(`${f}: no licence mapping`);
  else if (!files.includes(need[f])) fail(`${f}: missing ${need[f]}`);
}
console.log(`  ${files.filter((x) => /\.(ttf|otf|woff2?)$/.test(x)).length} fonts`);

if (failed) { console.error(`\n${failed} check(s) failed`); process.exit(1); }
console.log('\nall checks passed');
