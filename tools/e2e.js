#!/usr/bin/env node
// End-to-end check of the streaming path without a phone:
//   simulator (real :core server + live ffmpeg H.264) -> WebSocket -> browser receiver in headless Chromium.
// Prints what the receiver measured each second and fails if no video was rendered.
//
//   node tools/e2e.js [--mode webcodecs|mse] [--size 1920x1080] [--fps 60] [--bitrate 8000] [--seconds 12] [--https]
//
// Build the simulator first: (cd android && ./gradlew :simulator:installDist)
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { chromium } = require('playwright');

const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const k = process.argv[i].replace(/^--/, '');
  args[k] = process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[++i] : true;
}
const mode = args.mode || 'webcodecs';
const size = args.size || '1920x1080';
const fps = args.fps || '60';
const bitrate = args.bitrate || '8000';
const seconds = Number(args.seconds || 12);
const port = Number(args.port || 47900);
const pin = '424242';
const sim = path.join(__dirname, '../android/simulator/build/install/simulator/bin/simulator');

(async () => {
  const simArgs = ['--size', size, '--fps', fps, '--bitrate', bitrate, '--port', String(port), '--pin', pin];
  if (args.https) {
    const ks = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'omm-')), 'test.p12');
    execFileSync('keytool', ['-genkeypair', '-alias', 'omm', '-keyalg', 'EC', '-groupname', 'secp256r1', '-dname', 'CN=OmniMirror',
      '-validity', '30', '-storetype', 'PKCS12', '-keystore', ks, '-storepass', 'omnimirror', '-keypass', 'omnimirror'], { stdio: 'ignore' });
    simArgs.push('--keystore', ks);
  }
  const proc = spawn(sim, simArgs, { stdio: ['ignore', 'pipe', 'inherit'] });
  const simLog = [];
  await new Promise((resolve, reject) => {
    proc.stdout.on('data', (d) => { simLog.push(String(d)); if (String(d).includes('serving')) resolve(); });
    proc.on('exit', () => reject(new Error('simulator exited')));
    setTimeout(() => reject(new Error('simulator did not start')), 20000);
  });

  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1180, height: 820 } });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') console.log('  [page]', m.text()); });
  const base = args.https ? `https://localhost:${port + 1}` : `http://localhost:${port}`;
  await page.goto(`${base}/?mode=${mode}#${pin}`);

  console.log(`${size} @ ${fps} fps, ${bitrate} kbps, decoder=${mode}, ${base}`);
  console.log('  sec   state       fps  latency(ms)  Mbps   size        rtt(ms)  drops');
  const rows = [];
  for (let s = 1; s <= seconds; s++) {
    await page.waitForTimeout(1000);
    const st = await page.evaluate(() => window.__omm.stats);
    rows.push(st);
    console.log(`  ${String(s).padStart(3)}   ${st.state.padEnd(10)} ${String(st.fps).padStart(4)}  ${st.latencyMs.toFixed(1).padStart(10)}  ` +
      `${st.mbps.toFixed(1).padStart(5)}  ${(st.width + 'x' + st.height).padEnd(10)}  ${st.rttMs.toFixed(2).padStart(6)}  ${st.drops}`);
  }
  if (args.screenshot) await page.screenshot({ path: args.screenshot });
  await browser.close();
  proc.kill();

  const steady = rows.slice(3).filter((r) => r.fps > 0);
  if (!steady.length) { console.log(simLog.join('')); console.error('FAIL: no video rendered'); process.exit(1); }
  const avg = (f) => steady.reduce((a, r) => a + f(r), 0) / steady.length;
  console.log(`steady state (after 3 s warm-up): ${avg((r) => r.fps).toFixed(1)} fps, ` +
    `median latency ${avg((r) => r.latencyMs).toFixed(1)} ms, decoder ${steady[0].decoder}`);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
