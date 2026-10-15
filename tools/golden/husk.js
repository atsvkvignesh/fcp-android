// Golden data for Raw Material Check: runs the real husk-compare Code.gs (path in argv[2])
// against two made-up form sheets and writes the sheet values (as the Sheets API returns
// them) plus what api_load / api_save worked out. HuskModelTest checks the Kotlin port.
//   node tools/golden/husk.js <path to Code.gs> > app/src/test/resources/golden/husk.json
const vm = require('vm'), fs = require('fs');
const OFF = 330 * 60000;   // Asia/Kolkata, no daylight saving
const MON = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
function fmt(d, tz, f) {
  const t = new Date(d.getTime() + OFF), p = n => String(n).padStart(2, '0');
  const H = t.getUTCHours(), h12 = H % 12 || 12;
  return f.replace(/yyyy|yy|MMM|MM|dd|d|HH|h|mm|ss|a/g, k => ({
    yyyy: t.getUTCFullYear(), yy: String(t.getUTCFullYear()).slice(2), MMM: MON[t.getUTCMonth()], MM: p(t.getUTCMonth() + 1),
    dd: p(t.getUTCDate()), d: t.getUTCDate(), HH: p(H), h: h12, mm: p(t.getUTCMinutes()), ss: p(t.getUTCSeconds()), a: H < 12 ? 'AM' : 'PM',
  })[k]);
}
// cells: Date objects for date cells, like getValues()
const at = (y, mo, d, h = 0, mi = 0, s = 0, ms = 0) => new Date(Date.UTC(y, mo - 1, d, h, mi, s, ms) - OFF);
const tod = (h, mi, s = 0) => new Date(Date.UTC(1899, 11, 30, h, mi, s) - OFF);
// the same cell as the Sheets API sends it (UNFORMATTED_VALUE, SERIAL_NUMBER)
const api = v => v instanceof Date ? (v.getTime() + OFF) / 864e5 + 25569 : v;

let seed = 99; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
const pick = a => a[Math.floor(rnd() * a.length)];

const CH = ['Timestamp', 'Truck Number', 'Driver Name', 'Time', 'Place', 'No. Husk in Box', 'Number of Green Husk', 'Number of Brown Husk ',
  'Green  Husk Price', 'Brown Husk Price', 'Vendor Name', 'Note', 'Date', 'UN-LOADING POINT ', 'Number of Husk'];
const company = [CH];
for (let i = 0; i < 46; i++) {
  const day = 1 + Math.floor(i / 2.3), ts = at(2026, 9, day + 1, 9 + (i % 8), (i * 7) % 60, (i * 13) % 60, (i * 37) % 1000);
  const green = rnd() < 0.2 ? 'No' : Math.round(rnd() * 120) * 100, brown = rnd() < 0.5 ? 'No' : Math.round(rnd() * 80) * 100;
  const cft = rnd() < 0.15 ? '' : rnd() < 0.05 ? '#VALUE!' : Math.round((rnd() * 15000 + 2000) * 100) / 100;
  company.push([ts, pick(['TN 57 K 8013', '9280', 'TN 76 X 5790']), pick(['Velingiri', 'Murugan', '']), rnd() < 0.8 ? tod(8 + (i % 10), (i * 11) % 60) : '10:45 am',
    pick(['Toppampatti', 'Kalipalayam', 'Nagoor']), 800, green, brown, pick([3.3, 3.4, '3.5']), pick([2.6, 'No', 2.8]),
    pick(['Kajamoideen', 'kajamoideen ', 'Selvam  Traders', '']), rnd() < 0.2 ? 'check box' : '', at(2026, 9, day), pick(['VCP', 'Chips', '']), cft]);
}
company.push(['', '', '', '', '', '', '', '', '', '', '', '', '', '', '']);                      // blank row
company.push(['', '', 'Ravi', '', 'Nagoor', '', 5000, '', 3.3, '', 'Kajamoideen', '', '9/7/2026', '', 4800]); // text date, no timestamp
company.push([company[5][0], 'TN 1', 'Twin', tod(9, 0), 'Nagoor', '', 1000, 0, 3, 0, 'Kajamoideen', '', at(2026, 9, 3), '', 900]); // same timestamp as row 5

const VH = ['Timestamp', 'Date', 'Place - IF delivery enter Vehicle Number', 'Husk Type', 'Number of Husk', 'Cost ', 'Vendor Name'];
const vendor = [VH];
for (let i = 0; i < 38; i++) {
  const day = 1 + Math.floor(i / 2), ts = at(2026, 9, 24 + Math.floor(i / 15), 9 + (i % 9), (i * 5) % 60, (i * 17) % 60, (i * 53) % 1000);
  const count = rnd() < 0.05 ? '' : Math.round(rnd() * 150) * 100;
  const cost = rnd() < 0.7 ? pick([2.45, 3.2, 3.7, '3.1']) : Math.round(rnd() * 40000);
  vendor.push([ts, at(2026, 9, day), pick(['Kalipalayam', 'TN 57 K 8013', 'Nagoor']), pick(['Green', 'Brown', 'Kasangal', 'green husk']), count, cost,
    pick(['kajamoideen', 'Kajamoideen', 'Selvam Traders', 'selvam traders'])]);
}

const MH = ['Group ID', 'Status', 'Load date', 'Vendor', 'Our loads', 'Vendor entries', 'Driver count', 'CFT count', 'Vendor count', 'Our cost', 'Vendor cost',
  'Driver − Vendor', 'Company − Vendor cost', 'Note', 'Company keys', 'Vendor keys', 'Created', 'By'];
const ck = r => 'C' + company[r][0].getTime(), vk = r => 'V' + vendor[r][0].getTime();
const g = (id, st, dk, ven, cks, vks, note) => [id, st, dk, ven, cks.length, vks.length, 0, 0, 0, 0, 0, 0, 0, note || '', cks.join(','), vks.join(','), at(2026, 10, 2, 11, 30), ''];
const matches = [MH,
  g('G1', 'MATCHED', at(2026, 9, 1), 'Kajamoideen', [ck(1), ck(2)], [vk(1)]),
  g('G2', 'MATCHED', at(2026, 9, 3), 'Selvam Traders', [ck(5)], [vk(4), vk(5)], 'short by a bit'),
  g('G3', 'MISSING_AT_VENDOR', at(2026, 9, 4), 'Kajamoideen', [ck(7)], []),
  g('G4', 'MISSING_AT_FCP', '2026-09-06', 'kajamoideen', [], [vk(9)]),
  g('G5', 'IGNORED', at(2026, 9, 8), '', [ck(12)], [vk(12)]),
  g('G6', 'MATCHED', at(2026, 9, 10), 'Kajamoideen', [ck(20), 'C123'], [vk(18)]),             // one key no longer in the sheet
  g('G7', 'MATCHED', at(2026, 9, 12), 'Ghost Vendor', ['C999'], ['V999']),                      // every row gone
  ['', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', ''],
];

const books = { company: { 'Form Responses 1': company }, vendor: { 'Form Responses 1': vendor, 'RM Matches': matches } };
function book(tabs) {
  const sheets = Object.keys(tabs).map((name, i) => {
    const rows = tabs[name];
    const lc = () => Math.max(0, ...rows.map(r => r.length));
    const sh = {
      getName: () => name, getSheetId: () => 100 + i, getLastRow: () => rows.length, getLastColumn: lc,
      getDataRange: () => sh.getRange(1, 1, rows.length, lc()),
      getRange: (r, c, nr = 1, nc = 1) => ({
        getValues: () => rows.slice(r - 1, r - 1 + nr).map(row => { const o = []; for (let k = 0; k < nc; k++) o.push(row[c - 1 + k] === undefined ? '' : row[c - 1 + k]); return o; }),
        setValues: v => { v.forEach((row, k) => rows[r - 1 + k] = row.slice()); return {}; },
        setFontWeight() { return this; },
      }),
      setFrozenRows() {},
    };
    return sh;
  });
  return { getSheets: () => sheets, getSheetByName: n => sheets.find(s => s.getName() === n) || null,
    getSpreadsheetTimeZone: () => 'Asia/Kolkata', getSpreadsheetLocale: () => 'en_US', getName: () => 'x' };
}
const B = { '1jKjsqbMxlpPFfQ5_z2N74RqzbOOA5cnSYEPo36o3zvk': book(books.company), '1kNlaqWPhxasycg_QPkkBkshdYJ_Cvcnc0zqiUH0jHnA': book(books.vendor) };
const ctx = { console, JSON, Date, Math, Object, Array, String, Number, Boolean, Error, RegExp, isNaN, isFinite, parseFloat, parseInt,
  SpreadsheetApp: { openById: id => B[id], flush() {} },
  LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
  Session: { getActiveUser: () => ({ getEmail: () => '' }) },
  Utilities: { formatDate: fmt },
  HtmlService: {},
};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx);

const plain = o => JSON.parse(JSON.stringify(o));
const filters = [{}, { from: '2026-09-05', to: '2026-09-14' }, { vendor: 'Kajamoideen' }, { from: '2026-09-01', to: '2026-09-09', vendor: 'selvam traders' }];
const loads = filters.map(f => ({ filter: f, out: plain(ctx.api_load(f)) }));

// saving: the rows api_save adds to RM Matches (id and time left out)
const before = matches.length;
const pend = loads[0].out;
const freeC = pend.loads.filter(x => !x.gid).map(x => x.k), freeV = pend.entries.filter(x => !x.gid).map(x => x.k);
const saves = [
  { status: 'MATCHED', cks: freeC.slice(0, 2), vks: freeV.slice(0, 1), note: 'two loads, one slip' },
  { status: 'MISSING_AT_VENDOR', cks: freeC.slice(2, 4), vks: [] },
  { status: 'MISSING_AT_FCP', cks: [], vks: freeV.slice(1, 2) },
  { status: 'IGNORED', cks: freeC.slice(4, 5), vks: freeV.slice(2, 3) },
];
const saved = saves.map(p => {
  const n = matches.length;
  ctx.api_save(Object.assign({ filter: {} }, p));
  return { p, rows: matches.slice(n).map(r => r.slice(1, 16).map(v => v instanceof Date ? fmt(v, '', 'yyyy-MM-dd') : v)) };
});
const errors = [
  { status: 'MATCHED', cks: freeC.slice(5, 6), vks: [] },
  { status: 'MISSING_AT_VENDOR', cks: freeC.slice(5, 6), vks: freeV.slice(3, 4) },
  { status: 'NOPE', cks: freeC.slice(5, 6), vks: [] },
  { status: 'IGNORED', cks: [], vks: [] },
  { status: 'IGNORED', cks: [freeC[0]], vks: [] },
].map(p => { try { ctx.api_save(Object.assign({ filter: {} }, p)); return { p, error: null }; } catch (e) { return { p, error: e.message }; } });
matches.length = before;

const sheetValues = tabs => Object.fromEntries(Object.entries(tabs).map(([k, rows]) => [k, rows.map(r => {
  const o = r.map(api); while (o.length && o[o.length - 1] === '') o.pop(); return o; })]));
process.stdout.write(JSON.stringify({
  company: sheetValues(books.company), vendor: sheetValues({ 'Form Responses 1': vendor, 'RM Matches': matches }),
  loads, saved, errors,
}));
