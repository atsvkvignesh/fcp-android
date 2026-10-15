// 1) every project's Code.gs + its Api.gs loads together and answers the hub's probe
// 2) every bundled screen opens without page errors, both when the script answers with an error
//    and when there is no connection.  Usage: node tools/test/test-load-all.js <dir with exported projects>
const path = require('path'), fs = require('fs');
const { chromium } = require(require.resolve('playwright', { paths: ['/opt/npm-tools/node_modules'] }));
const { makeRuntime, post } = require('./gas-mock');
const ROOT = path.resolve(__dirname, '../..'), ASSETS = ROOT + '/app/src/main/assets', SRC = process.argv[2];
const KEY = /key:\s*'([^']+)'/.exec(fs.readFileSync(ASSETS + '/lib/config.js', 'utf8'))[1];
const MAP = { 'hub': 'fcp-app-hub', 'ec-log': 'ec-reading', 'ec-viewer': 'ec-view', 'husk-check': 'husk-compare',
              'inventory': 'inventory__1BAFsp', 'fleet': 'fleet-manager', 'water-load': 'water-load-ui' };
const dirOf = k => fs.readdirSync(SRC).find(d => d.startsWith(MAP[k]));
const stub = new Proxy(function () {}, { get: (t, k) => k === Symbol.toPrimitive ? () => '' : stub, apply: () => stub });

let bad = 0;
for (const app of Object.keys(MAP)) {
  const dir = path.join(SRC, dirOf(app));
  const gs = fs.readdirSync(dir).filter(f => f.endsWith('.gs')).map(f => path.join(dir, f));
  try {
    const rt = makeRuntime([...gs, ROOT + '/apps-script/' + app + '/Api.gs'],
      { SpreadsheetApp: stub, DriveApp: stub, HtmlService: stub, Session: stub, MailApp: stub, GmailApp: stub, ScriptApp: stub, Logger: stub, UrlFetchApp: stub, CalendarApp: stub });
    const probe = JSON.parse(post(rt, { action: '__app', args: [], key: KEY }));
    const allow = rt.FCP_API.allow, missing = allow.filter(fn => typeof rt[fn] !== 'function');
    console.log(`${app.padEnd(11)} probe=${JSON.stringify(probe.data)} functions ${allow.length - missing.length}/${allow.length}${missing.length ? ' MISSING ' + missing : ''}`);
    if (!probe.ok || missing.length) bad++;
  } catch (e) { console.log(app, 'FAILED TO LOAD:', e.message); bad++; }
}

(async () => {
  const browser = await chromium.launch();
  for (const mode of ['error', 'offline']) {
    const ctx = await browser.newContext({ viewport: { width: 400, height: 820 }, isMobile: true, hasTouch: true });
    await ctx.addInitScript(m => {
      window.FCPApp = {
        request(id, url, body) {
          const a = JSON.parse(body).action;
          setTimeout(() => window.FCP._done(id, m === 'offline' ? 0 : 200,
            m === 'offline' ? '{"ok":false,"error":"No connection to the server.","offline":true}'
                            : JSON.stringify({ ok: false, error: 'test error from ' + a })), 50);
        },
        hasLocal: () => true, openLocal: () => true, openUrl() {}, close() {}, version: () => 't',
      };
    }, mode);
    await ctx.route('https://appassets.androidplatform.net/**', r => {
      const u = new URL(r.request().url());
      const f = path.join(ASSETS, decodeURIComponent(u.pathname.replace(/^\/assets\//, '')));
      if (!fs.existsSync(f)) return r.fulfill({ status: 404, body: '' });
      const type = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2' }[path.extname(f)];
      r.fulfill({ status: 200, contentType: type, body: fs.readFileSync(f) });
    });
    let external = 0;
    await ctx.route(/^https:\/\/(?!appassets)/, r => { external++; r.abort(); });
    for (const app of Object.keys(MAP)) {
      const page = await ctx.newPage(), errs = [];
      page.on('pageerror', e => errs.push(e.message));
      await page.goto(`https://appassets.androidplatform.net/assets/${app}/index.html?api=${encodeURIComponent('https://script.google.com/macros/s/X/exec')}`);
      await page.waitForTimeout(1200);
      const shown = (await page.evaluate(() => document.body.innerText)).replace(/\s+/g, ' ').slice(0, 90);
      console.log(`[${mode}] ${app.padEnd(11)} ${errs.length ? 'ERRORS: ' + errs.join(' | ') : 'ok'} — "${shown}"`);
      if (errs.length) bad++;
      await page.screenshot({ path: `${process.env.SHOTS}/${app}-${mode}.png` });
      await page.close();
    }
    if (external) console.log(`[${mode}] blocked ${external} requests to outside sites (images in data etc.)`);
    await ctx.close();
  }
  await browser.close();
  console.log(bad ? `${bad} PROBLEM(S)` : 'ALL SCREENS OK');
  process.exit(bad ? 1 : 0);
})();
