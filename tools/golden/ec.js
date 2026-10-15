// Golden data for EC Viewer: runs the real ec-view Code.gs (path in argv[2]) on made-up day tabs
// and records api_summary for a few ranges, with and without viewer limits.
//   TZ=Asia/Kolkata node tools/golden/ec.js <path to Code.gs> > app/src/test/resources/golden/ec.json
const vm = require('vm'), fs = require('fs');
if (process.env.TZ !== 'Asia/Kolkata') throw new Error('run with TZ=Asia/Kolkata');
const NOW = new Date(2026, 9, 15, 17, 0).getTime();
class FakeDate extends Date { constructor(...a) { if (a.length) super(...a); else super(NOW); } static now() { return NOW; } }
function fmt(d, tz, f) {
  const p = n => String(n).padStart(2, '0');
  return f.replace(/yyyy|MM|dd|HH|mm|ss/g, k => ({ yyyy: d.getFullYear(), MM: p(d.getMonth() + 1), dd: p(d.getDate()), HH: p(d.getHours()), mm: p(d.getMinutes()), ss: p(d.getSeconds()) })[k]);
}
let seed = 5; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
const pick = a => a[Math.floor(rnd() * a.length)];
const YARDS = ['Balan Kulivaial Yard 1', 'Balan Kulivaial Yard 2', 'Balan L Bed'];
const CONDS = ['Dry', 'Half dry', 'Wet', ''];
const tabs = {};
const people = ['Selvarani', 'Arun', 'Muthu'];
for (let d = 8; d <= 15; d++) {
  people.forEach((who, wi) => {
    if ((d + wi) % 3 === 0) return;
    const name = '2026-10-' + String(d).padStart(2, '0') + ' ' + who;
    const head = new Array(28).fill('');
    head[0] = 'Date time'; head[1] = 'Sample no'; head[2] = 'Operator'; head[3] = 'Point'; head[4] = 'Spot'; head[5] = 'EC'; head[6] = 'Condition';
    if (wi !== 1) head[27] = 0.72;                         // AB1 Belt limit (one person keeps the default)
    const rows = [head];
    const n = 4 + Math.floor(rnd() * 8);
    for (let i = 0; i < n; i++) {
      const point = pick(['Belt', 'Belt', 'Block', 'Yard', 'Yard']);
      const r = new Array(28).fill('');
      r[0] = new FakeDate(2026, 9, d, 8 + Math.floor(i * 1.3), Math.floor(rnd() * 60), Math.floor(rnd() * 60));
      r[1] = i % 4 === 0 ? 'S' + (100 + i) : 100 + i;
      r[2] = i === 2 ? '' : who;
      r[3] = point;
      r[4] = point === 'Yard' ? pick(YARDS) : (i % 5 === 0 ? '' : point);
      r[5] = i === 6 ? String(Math.round(rnd() * 150) / 100) : Math.round(rnd() * 150) / 100;
      r[6] = point === 'Yard' ? pick(CONDS) : '';
      if (i === 1) r[27] = 1.05;                            // AB2 Block limit
      if (i === 2) r[27] = 0.35;                            // AB3 Yard limit
      rows.push(r);
    }
    if (d === 12) { const bad = new Array(28).fill(''); bad[3] = 'Belt'; bad[5] = 'n/a'; rows.push(bad); }  // not a number: skipped
    if (d === 13) { const bad = new Array(28).fill(''); bad[5] = 0.5; rows.push(bad); }                   // no point: skipped
    // two readings in the same minute, to check which one counts as "last"
    if (d === 15 && wi === 0) { const a = rows[rows.length - 1].slice(); a[1] = 999; a[5] = 0.11; rows.push(a); }
    tabs[name] = rows;
  });
}
tabs['Limits'] = [['Point', 'Limit'], ['Belt', 0.7]];
let props = {};
const sheet = name => ({ getName: () => name, getLastRow: () => tabs[name].length,
  getRange: (r, c, nr, nc) => ({ getValues: () => tabs[name].slice(r - 1, r - 1 + nr).map(row => { const o = []; for (let j = 0; j < nc; j++) o.push(row[c - 1 + j] === undefined ? '' : row[c - 1 + j]); return o; }) }) });
const book = { getSheets: () => Object.keys(tabs).map(sheet) };
const ctx = { console, JSON, Date: FakeDate, Math, Object, Array, String, Number, Boolean, Error, RegExp, isNaN, isFinite, parseFloat,
  SpreadsheetApp: { openById: () => book }, CacheService: { getScriptCache: () => ({ get: () => null, put() {} }) },
  PropertiesService: { getScriptProperties: () => ({ getProperty: k => props[k] || null, setProperty: (k, v) => props[k] = v, deleteProperty: k => delete props[k] }) },
  Utilities: { formatDate: fmt }, HtmlService: {} };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx);
const plain = o => JSON.parse(JSON.stringify(o));
const snap = Object.fromEntries(Object.entries(tabs).map(([k, rows]) => [k, rows.map(r => { const o = r.map(v => v instanceof Date ? (v.getTime() + 330 * 60000) / 864e5 + 25569 : v); while (o.length && o[o.length - 1] === '') o.pop(); return o; })]));
const out = { now: NOW, tabs: snap, cases: [] };
const run = (args, saved) => { props = saved ? { VIEW_LIMITS: JSON.stringify(saved) } : {}; out.cases.push({ args, saved: saved || null, out: plain(ctx.api_summary(...args)) }); };
run(['today', '', '', 'all', 'all']);
run(['week', '', '', 'all', 'all']);
run(['custom', '2026-10-12', '2026-10-09', 'Arun', 'all']);
run(['week', '', '', 'all', 'Balan L Bed']);
run(['month', '', '', 'all', 'all'], { Belt: 0.5, Yard: 0.4 });
process.stdout.write(JSON.stringify(out));
