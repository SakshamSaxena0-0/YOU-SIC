// Builds YouSic N, the native Android player (dist-android/YouSicN.apk), with the Android SDK's own
// tools (no Gradle): aapt2 compile + link -> javac -> d8 -> zipalign -> apksigner. Signed with the same key as
// the WebView YouSic (android/yousic.keystore). Run: npm run android-native
'use strict';
const fs = require('fs');
const path = require('path');
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
const JAVA = exe(path.join(JDK, 'bin'), 'java'), JAVAC = exe(path.join(JDK, 'bin'), 'javac'), JARTOOL = exe(path.join(JDK, 'bin'), 'jar');
const VERSION = require(path.join(ROOT, 'package.json')).version;

const step = (s) => console.log('\n== ' + s);
const run = (cmd, args) => execFileSync(cmd, args, { stdio: 'inherit', env: { ...process.env, JAVA_HOME: JDK } });
const files = (dir, ext, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) files(p, ext, out); else if (p.endsWith(ext)) out.push(p);
  }
  return out;
};

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

step('aapt2: resources and manifest');
const resZip = path.join(OUT, 'res.zip');
run(exe(BT, 'aapt2'), ['compile', '--dir', path.join(A, 'res'), '-o', resZip]);
const unsigned = path.join(OUT, 'app.unaligned.apk');
const versionCode = Math.floor((Date.now() - Date.UTC(2024, 0, 1)) / 60000);
run(exe(BT, 'aapt2'), ['link', '-o', unsigned, '-I', JAR, '--manifest', path.join(A, 'AndroidManifest.xml'),
  '--java', path.join(OUT, 'gen'), '--min-sdk-version', '29', '--target-sdk-version', '34',
  '--version-code', String(versionCode), '--version-name', VERSION, resZip]);

step('javac');
const classes = path.join(OUT, 'classes');
fs.mkdirSync(classes, { recursive: true });
const sources = [...files(path.join(A, 'src'), '.java'), ...files(path.join(OUT, 'gen'), '.java')];
const bootcp = [JAR, path.join(BT, 'core-lambda-stubs.jar')].join(path.delimiter);
run(JAVAC, ['-source', '8', '-target', '8', '-bootclasspath', bootcp, '-encoding', 'UTF-8', '-Xlint:-options', '-Xlint:unchecked',
  '-d', classes, ...sources]);

step('d8: dex');
const dex = path.join(OUT, 'dex');
fs.mkdirSync(dex, { recursive: true });
run(JAVA, ['-cp', path.join(BT, 'lib', 'd8.jar'), 'com.android.tools.r8.D8', '--release', '--min-api', '29',
  '--lib', JAR, '--output', dex, ...files(classes, '.class')]);
// the fonts' OFL licences travel with the app
const assets = path.join(OUT, 'apk', 'assets', 'licenses');
fs.mkdirSync(assets, { recursive: true });
for (const f of fs.readdirSync(path.join(A, 'licenses'))) fs.copyFileSync(path.join(A, 'licenses', f), path.join(assets, f));
// the JDK's jar tool, not aapt2 -A: on Windows aapt2 stores asset names with backslashes
run(JARTOOL, ['uMf', unsigned, '-C', dex, 'classes.dex', '-C', path.join(OUT, 'apk'), 'assets']);

step('zipalign + sign');
const aligned = path.join(OUT, 'app.aligned.apk');
run(exe(BT, 'zipalign'), ['-p', '-f', '4', unsigned, aligned]);
const ks = path.join(ROOT, 'android', 'yousic.keystore'), ksProps = path.join(ROOT, 'android', 'keystore.properties');
const pass = /storePassword=(.*)/.exec(fs.readFileSync(ksProps, 'utf8'))[1].trim();
fs.mkdirSync(DIST, { recursive: true });
const apk = path.join(DIST, 'YouSicN.apk');
run(JAVA, ['-jar', path.join(BT, 'lib', 'apksigner.jar'), 'sign', '--v4-signing-enabled', 'false', '--ks', ks, '--ks-key-alias', 'yousic',
  '--ks-pass', 'pass:' + pass, '--key-pass', 'pass:' + pass, '--out', apk, aligned]);
console.log(`\nBuilt ${apk} (${(fs.statSync(apk).size / 1048576).toFixed(1)} MB, version ${VERSION} / ${versionCode})`);
