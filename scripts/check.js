// Project checks (npm run check). There is no linter or test suite yet; this covers what can break silently:
//  1. every JavaScript file parses,
//  2. every bundled font ships with its licence.
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

console.log('2. font licences');
const fonts = path.join(root, 'renderer/fonts');
const files = fs.readdirSync(fonts);
const need = { 'manrope-latin-wght-normal.woff2': 'OFL-Manrope.txt', 'manrope-latin-ext-wght-normal.woff2': 'OFL-Manrope.txt', 'jetbrains-mono-latin-wght-normal.woff2': 'OFL-JetBrainsMono.txt' };
for (const f of files.filter((x) => /\.(ttf|otf|woff2?)$/.test(x))) {
  if (!need[f]) fail(`${f}: no licence mapping`);
  else if (!files.includes(need[f])) fail(`${f}: missing ${need[f]}`);
}
console.log(`  ${files.filter((x) => /\.(ttf|otf|woff2?)$/.test(x)).length} fonts`);

if (failed) { console.error(`\n${failed} check(s) failed`); process.exit(1); }
console.log('\nall checks passed');
