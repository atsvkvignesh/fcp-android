// End-to-end check of the bundled hub + EC Viewer in Chromium, against the real scripts running
// in the mock runtime. Usage: node tools/test/test-screens.js <hub Code.gs> <EC view Code.gs>
const path = require('path'), fs = require('fs'), assert = require('assert');
const { chromium } = require(require.resolve('playwright', { paths: ['/opt/npm-tools/node_modules'] }));
const { makeRuntime, post, formatDate, fakeBook } = require('./gas-mock');
const ROOT = path.resolve(__dirname, '../..'), ASSETS = ROOT + '/app/src/main/assets';
const CONFIG = fs.readFileSync(ASSETS + '/lib/config.js', 'utf8');
const KEY = /key:\s*'([^']+)'/.exec(CONFIG)[1], HUB = /hubUrl:\s*'([^']+)'/.exec(CONFIG)[1];
const ECV = 'https://script.google.com/macros/s/ECV/exec', OLD = 'https://script.google.com/macros/s/OLD/exec';

// ---------- backends ----------
const hub = makeRuntime([process.argv[2], ROOT + '/apps-script/hub/Api.gs']);
const d = hub.readStore_();
hub.addUserRecord_(d, 'tester', 'Tester', 'admin', 'secret123');
d.links.push({ id: 'ecv1', icon: '📈', name: 'EC Viewer', url: ECV }, { id: 'old1', icon: '🧰', name: 'Old tool', url: OLD });
hub.writeStore_(d);

const today = formatDate(new Date(), '', 'yyyy-MM-dd'), y = formatDate(new Date(Date.now() - 86400000), '', 'yyyy-MM-dd');
function tab(dateStr, who, n) {
  const rows = [['Timestamp', 'Sample no', 'Operator', 'Point', 'Spot', 'EC', 'Condition']];
  rows[0][27] = 0.7;
  for (let i = 0; i < n; i++) {
    const t = new Date(dateStr + 'T0' + (8 + (i % 2)) + ':' + String(10 + i).padStart(2, '0') + ':00+05:30');
    const point = ['Belt', 'Block', 'Yard'][i % 3];
    const r = [t, 'S' + (i + 1), who, point, point === 'Yard' ? ['North yard', 'South yard'][i % 2] : '', +(0.3 + i * 0.11).toFixed(2), point === 'Yard' ? 'Wet' : ''];
    rows.push(r);
  }
  rows[1][27] = 1.1; rows[2][27] = 0.3;
  return rows;
}
const book = fakeBook({ [today + ' Selvarani']: tab(today, 'Selvarani', 9), [y + ' Tamilarasi']: tab(y, 'Tamilarasi', 6) });
const ecv = makeRuntime([process.argv[3], ROOT + '/apps-script/ec-viewer/Api.gs'], {
  SpreadsheetApp: { openById: () => book },
  Logger: { log() {} },
  HtmlService: {},
});
ecv.Utilities.formatDate = formatDate;

let OFFLINE = false, DELAY = 0, calls = 0;
async function backend(url, body) {
  calls++;
  if (DELAY) await new Promise(r => setTimeout(r, DELAY));
  if (OFFLINE) return { status: 0, text: '{"ok":false,"error":"No connection to the server.","offline":true}' };
  if (url === HUB) return { status: 200, text: post(hub, body) };
  if (url === ECV) return { status: 200, text: post(ecv, body) };
  if (url === OLD) return { status: 200, text: '<html><body>Script function not found: doPost</body></html>' };
  return { status: 404, text: 'not found' };
}

// ---------- browser ----------
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 400, height: 820 }, isMobile: true, hasTouch: true });
  const opened = [];
  await ctx.exposeBinding('__backend', (src, url, body) => backend(url, body));
  await ctx.exposeBinding('__opened', (src, kind, arg) => { opened.push([kind, arg]); });
  await ctx.addInitScript(() => {
    const LOCAL = ['hub', 'ec-viewer', 'ec-log', 'husk-check', 'inventory', 'fleet', 'water-load'];
    window.FCPApp = {
      request(id, url, body) { window.__backend(url, body).then(r => window.FCP._done(id, r.status, r.text)); },
      hasLocal: n => LOCAL.includes(String(n).split('?')[0]),
      openLocal: t => { window.__opened('local', t); return true; },
      openUrl: u => window.__opened('url', u),
      close: () => window.__opened('close', ''),
      version: () => 'test', subscribe: () => true, unsubscribe: () => true, getTopics: () => '["all"]',
    };
  });
  await ctx.route('https://appassets.androidplatform.net/**', r => {
    const u = new URL(r.request().url());
    const f = path.join(ASSETS, decodeURIComponent(u.pathname.replace(/^\/assets\//, '')));
    if (!f.startsWith(ASSETS) || !fs.existsSync(f)) return r.fulfill({ status: 404, body: 'missing ' + u.pathname });
    const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2' }[path.extname(f)] || 'application/octet-stream';
    r.fulfill({ status: 200, contentType: type, body: fs.readFileSync(f) });
  });
  await ctx.route(/fonts\.googleapis|fonts\.gstatic/, r => { console.log('  !! Google Fonts requested'); r.abort(); });

  const errors = [];
  const watch = (page, label) => {
    page.on('pageerror', e => errors.push(label + ': ' + e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(label + ' console: ' + m.text()); });
  };

  // 1. hub: sign in
  const hubPage = await ctx.newPage(); watch(hubPage, 'hub');
  await hubPage.goto('https://appassets.androidplatform.net/assets/hub/index.html');
  await hubPage.waitForSelector('#loginView:not([hidden])');
  await hubPage.fill('#lu', 'tester'); await hubPage.fill('#lp', 'secret123');
  await hubPage.click('#loginBtn');
  await hubPage.waitForTimeout(1500);
  if (!(await hubPage.$('#hubView:not([hidden]) a.tile'))) {
    console.log('toast:', await hubPage.$eval('#toast', e => e.textContent + ' | ' + e.style.display));
    console.log('errors so far:', errors);
    await hubPage.screenshot({ path: process.env.SHOTS + '/hub-fail.png' });
  }
  await hubPage.waitForSelector('#hubView:not([hidden]) a.tile', { timeout: 5000 });
  console.log('1. signed in, tiles:', await hubPage.$$eval('a.tile .nm', a => a.map(x => x.textContent).join(', ')));

  // 2. probes mark the EC Viewer tile
  await hubPage.waitForFunction(() => [...document.querySelectorAll('a.tile .go')].some(g => g.textContent === 'Opens in the app'), null, { timeout: 5000 });
  const marks = await hubPage.$$eval('a.tile', a => a.map(x => x.querySelector('.nm').textContent + ': ' + x.querySelector('.go').textContent));
  console.log('2. tile labels:', marks.join(' | '));

  // 3. tapping tiles
  await hubPage.click('a.tile:has-text("EC Viewer")');
  await hubPage.click('a.tile:has-text("Old tool")');
  console.log('3. opened:', JSON.stringify(opened));
  assert.deepEqual(opened[0], ['local', 'ec-viewer?api=' + encodeURIComponent(ECV)]);
  assert.deepEqual(opened[1], ['url', OLD]);

  // 4. EC Viewer from the hub tile
  const ecPage = await ctx.newPage(); watch(ecPage, 'ec-viewer');
  await ecPage.goto('https://appassets.androidplatform.net/assets/ec-viewer/index.html?api=' + encodeURIComponent(ECV));
  await ecPage.waitForFunction(() => /,/.test(document.getElementById('rangeLabel').textContent), null, { timeout: 8000 });
  await ecPage.waitForTimeout(800);
  console.log('4. EC Viewer:', await ecPage.$eval('#rangeLabel', e => e.textContent), '| hall svg:', await ecPage.$$eval('#hChart svg', s => s.length),
    '| fonts:', await ecPage.evaluate(() => document.fonts.check('700 16px Fraunces') && document.fonts.check('16px Archivo')));
  await ecPage.screenshot({ path: process.env.SHOTS + '/ec-viewer.png' });

  // 5. offline: saved copy + note
  OFFLINE = true;
  const t0 = Date.now();
  await ecPage.reload();
  await ecPage.waitForFunction(() => /,/.test(document.getElementById('rangeLabel').textContent), null, { timeout: 4000 });
  console.log('5. offline reload showed saved data in', Date.now() - t0, 'ms');
  await ecPage.waitForTimeout(600);
  const note = await ecPage.evaluate(() => [...document.querySelectorAll('[role=status]')].map(e => e.style.opacity === '1' ? e.textContent : '').filter(Boolean).join(' / '));
  console.log('   note:', note);
  await ecPage.screenshot({ path: process.env.SHOTS + '/ec-viewer-offline.png' });
  OFFLINE = false;

  // 6. hub reopened with a slow network: tiles must show before the answer arrives
  DELAY = 2500;
  const t1 = Date.now();
  await hubPage.reload();
  await hubPage.waitForSelector('#hubView:not([hidden]) a.tile', { timeout: 2000 });
  console.log('6. hub tiles shown from saved copy in', Date.now() - t1, 'ms (network takes 2500 ms)');
  await hubPage.screenshot({ path: process.env.SHOTS + '/hub.png' });
  DELAY = 0;

  // 7. Back button handling in the hub: Manage -> hub
  await hubPage.click('#manageBtn');
  const handled = await hubPage.evaluate(() => window.onAppBack());
  console.log('7. back from Manage handled:', handled, '| hub visible:', await hubPage.$eval('#hubView', e => !e.hidden));

  console.log('backend calls:', calls);
  console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'no page errors');
  await browser.close();
  process.exit(errors.length ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
