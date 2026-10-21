// Builds the Android app, dist-android/YouSic.apk, with the Android SDK's own tools (no Gradle):
//   the page (renderer/, butterchurn, android/web) -> APK assets
//   aapt2 compile + link (resources, manifest) -> javac -> d8 (dex) -> zipalign -> apksigner
// Needs the Android SDK (build-tools 35.0.0, platforms;android-34) and a JDK. Run: npm run android
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const A = __dirname;
const OUT = path.join(A, 'build');
const DIST = path.join(ROOT, 'dist-android');
const SDK = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk');
const BT = path.join(SDK, 'build-tools', '35.0.0');
const JAR = path.join(SDK, 'platforms', 'android-34', 'android.jar');
const JDK = process.env.JAVA_HOME || 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot';
const exe = (dir, name) => path.join(dir, process.platform === 'win32' ? name + '.exe' : name);
const JAVA = exe(path.join(JDK, 'bin'), 'java'), JAVAC = exe(path.join(JDK, 'bin'), 'javac'), KEYTOOL = exe(path.join(JDK, 'bin'), 'keytool');
const VERSION = require(path.join(ROOT, 'package.json')).version;

const step = (s) => console.log('\n== ' + s);
const run = (cmd, args) => execFileSync(cmd, args, { stdio: 'inherit', env: { ...process.env, JAVA_HOME: JDK } });

function copyDir(src, dst, filter = () => true) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (!filter(s)) continue;
    if (e.isDirectory()) copyDir(s, d, filter);
    else fs.copyFileSync(s, d);
  }
}

function files(dir, ext, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) files(p, ext, out);
    else if (p.endsWith(ext)) out.push(p);
  }
  return out;
}

for (const [what, p] of [['build-tools 35.0.0', BT], ['platforms;android-34', JAR], ['a JDK', JAVAC]]) {
  if (!fs.existsSync(p)) { console.error(`Missing ${what}: ${p}`); process.exit(1); }
}

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

step('assets: the page');
const www = path.join(OUT, 'assets', 'www');
copyDir(path.join(ROOT, 'renderer'), path.join(www, 'renderer'));
copyDir(path.join(ROOT, 'node_modules', 'butterchurn', 'lib'), path.join(www, 'node_modules', 'butterchurn', 'lib'));
copyDir(path.join(ROOT, 'node_modules', 'butterchurn-presets', 'lib'), path.join(www, 'node_modules', 'butterchurn-presets', 'lib'));
copyDir(path.join(A, 'web'), path.join(www, 'android'));
fs.mkdirSync(path.join(www, 'build'), { recursive: true });
fs.copyFileSync(path.join(ROOT, 'build', 'icon.png'), path.join(www, 'build', 'icon.png'));
// the Android copy of index.html: a phone viewport, then window.pa (pa-android.js) and android.css before anything else
const indexFile = path.join(www, 'renderer', 'index.html');
let html = fs.readFileSync(indexFile, 'utf8');
const boot = '<script src="js/boot.js"></script>';
if (!html.includes(boot)) throw new Error('index.html: boot.js tag not found');
html = html.replace('<meta charset="utf-8">', '<meta charset="utf-8">\n  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">')
  .replace(boot, '<script src="../android/pa-android.js"></script>\n  <link rel="stylesheet" href="../android/android.css">\n  ' + boot);
fs.writeFileSync(indexFile, html);

step('aapt2: resources and manifest');
const resZip = path.join(OUT, 'res.zip');
run(exe(BT, 'aapt2'), ['compile', '--dir', path.join(A, 'res'), '-o', resZip]);
const unsigned = path.join(OUT, 'app.unaligned.apk');
// versionCode: minutes since 2024, so every build installs over the last one
const versionCode = Math.floor((Date.now() - Date.UTC(2024, 0, 1)) / 60000);
run(exe(BT, 'aapt2'), ['link', '-o', unsigned, '-I', JAR, '--manifest', path.join(A, 'AndroidManifest.xml'),
  '--java', path.join(OUT, 'gen'), '-A', path.join(OUT, 'assets'), '--min-sdk-version', '26', '--target-sdk-version', '34',
  '--version-code', String(versionCode), '--version-name', VERSION, resZip]);

step('javac');
const classes = path.join(OUT, 'classes');
fs.mkdirSync(classes, { recursive: true });
const sources = [...files(path.join(A, 'src'), '.java'), ...files(path.join(OUT, 'gen'), '.java')];
// android.jar has no java.lang.invoke, which lambdas compile against: the SDK's lambda stubs fill it in (d8 desugars them)
const bootcp = [JAR, path.join(BT, 'core-lambda-stubs.jar')].join(path.delimiter);
run(JAVAC, ['-source', '8', '-target', '8', '-bootclasspath', bootcp, '-encoding', 'UTF-8', '-Xlint:-options', '-Xlint:unchecked',
  '-d', classes, ...sources]);

step('d8: dex');
const dex = path.join(OUT, 'dex');
fs.mkdirSync(dex, { recursive: true });
run(JAVA, ['-cp', path.join(BT, 'lib', 'd8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '26',
  '--lib', JAR, '--output', dex, ...files(classes, '.class')]);
// add classes.dex at the root of the APK
execFileSync(exe(BT, 'aapt'), ['add', unsigned, 'classes.dex'], { cwd: dex, stdio: 'inherit' });

step('zipalign + sign');
const aligned = path.join(OUT, 'app.aligned.apk');
run(exe(BT, 'zipalign'), ['-p', '-f', '4', unsigned, aligned]);
// the signing key: made once and kept (android/yousic.keystore, not in git). Updates must be signed with
// the same key, or Android refuses to install them over the old app.
const ks = path.join(A, 'yousic.keystore'), ksProps = path.join(A, 'keystore.properties');
if (!fs.existsSync(ks)) {
  const pass = crypto.randomBytes(18).toString('base64url');
  run(KEYTOOL, ['-genkeypair', '-keystore', ks, '-storetype', 'PKCS12', '-alias', 'yousic', '-keyalg', 'RSA', '-keysize', '2048',
    '-validity', '10000', '-storepass', pass, '-keypass', pass, '-dname', 'CN=YouSic, O=YouSic']);
  fs.writeFileSync(ksProps, `storePassword=${pass}\n`);
}
const pass = /storePassword=(.*)/.exec(fs.readFileSync(ksProps, 'utf8'))[1].trim();
fs.mkdirSync(DIST, { recursive: true });
const apk = path.join(DIST, 'YouSic.apk');
const signer = path.join(BT, 'lib', 'apksigner.jar');
run(JAVA, ['-jar', signer, 'sign', '--ks', ks, '--ks-key-alias', 'yousic', '--ks-pass', 'pass:' + pass, '--key-pass', 'pass:' + pass,
  '--out', apk, aligned]);
run(JAVA, ['-jar', signer, 'verify', '--verbose', apk]);
console.log(`\nBuilt ${apk} (${(fs.statSync(apk).size / 1048576).toFixed(1)} MB, version ${VERSION} / ${versionCode})`);
