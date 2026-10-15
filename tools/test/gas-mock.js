// A small stand-in for the Apps Script runtime, enough to run a project's Code.gs + Api.gs in node.
const vm = require('vm'), fs = require('fs'), crypto = require('crypto');

function makeRuntime(files, extra = {}) {
  const props = {}, cache = {};
  const ctx = {
    console, JSON, Date, Math, Object, Array, String, Number, Boolean, Error, RegExp, Set, Map, Promise,
    PropertiesService: { getScriptProperties: () => ({
      getProperty: k => (k in props ? props[k] : null),
      setProperty: (k, v) => { props[k] = String(v); },
      deleteProperty: k => { delete props[k]; },
      getProperties: () => Object.assign({}, props),
    }) },
    CacheService: { getScriptCache: () => ({
      get: k => (k in cache ? cache[k] : null),
      put: (k, v) => { cache[k] = String(v); },
      remove: k => { delete cache[k]; },
    }) },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {}, tryLock() { return true; } }) },
    Utilities: {
      getUuid: () => crypto.randomUUID(),
      DigestAlgorithm: { SHA_256: 'sha256' }, Charset: { UTF_8: 'utf8' },
      computeDigest: (alg, s) => [...crypto.createHash(alg).update(s, 'utf8').digest()].map(b => (b > 127 ? b - 256 : b)),
      sleep: () => {},
      formatDate: (d, tz, f) => d.toISOString(),
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: t => ({ setMimeType() { return this; }, getContent: () => t }),
    },
    ...extra,
  };
  vm.createContext(ctx);
  for (const f of files) vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: f });
  ctx.__props = props; ctx.__cache = cache;
  return ctx;
}

/** POST body -> the JSON text doPost answers with */
function post(rt, body) {
  return rt.doPost({ postData: { contents: typeof body === 'string' ? body : JSON.stringify(body) } }).getContent();
}

module.exports = { makeRuntime, post };

/** Utilities.formatDate for the patterns the FCP scripts use (yyyy MM dd HH mm ss), in a fixed offset. */
function formatDate(d, tz, f, offsetMin = 330) {
  const t = new Date(d.getTime() + offsetMin * 60000);
  const p = n => String(n).padStart(2, '0');
  return f.replace(/yyyy|MM|dd|HH|mm|ss/g, k => ({
    yyyy: t.getUTCFullYear(), MM: p(t.getUTCMonth() + 1), dd: p(t.getUTCDate()),
    HH: p(t.getUTCHours()), mm: p(t.getUTCMinutes()), ss: p(t.getUTCSeconds()),
  })[k]);
}

/** A fake spreadsheet: { 'tab name': [[row1...], [row2...]] } */
function fakeBook(tabs) {
  let gid = 100;
  const sheets = Object.keys(tabs).map(name => {
    const rows = tabs[name], id = gid++;
    const sh = {
      getName: () => name, getSheetId: () => id, getLastRow: () => rows.length,
      getRange: (r, c, nr = 1, nc = 1) => ({
        getValues: () => rows.slice(r - 1, r - 1 + nr).map(row => { const out = []; for (let i = 0; i < nc; i++) out.push(row[c - 1 + i] === undefined ? '' : row[c - 1 + i]); return out; }),
        setValues: v => { v.forEach((row, i) => { rows[r - 1 + i] = rows[r - 1 + i] || []; row.forEach((x, j) => rows[r - 1 + i][c - 1 + j] = x); }); return rng(); },
        setFontWeight: () => rng(), setBackground: () => rng(), createFilter: () => rng(),
      }),
      clear: () => { rows.length = 0; return sh; }, setFrozenRows: () => sh, getFilter: () => null,
      autoResizeColumns: () => sh,
    };
    const rng = () => ({ setFontWeight: rng, setBackground: rng, createFilter: rng });
    return sh;
  });
  const book = {
    getName: () => 'Fake book', getSheets: () => sheets,
    getSheetByName: n => sheets.find(s => s.getName() === n) || null,
    insertSheet: n => { tabs[n] = []; const b = fakeBook(tabs); const s = b.getSheetByName(n); sheets.push(s); return s; },
  };
  return book;
}

module.exports.formatDate = formatDate;
module.exports.fakeBook = fakeBook;
