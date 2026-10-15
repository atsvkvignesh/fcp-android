// Golden data for Stores inventory: runs the real Inventory Code.gs (path in argv[2]) on a
// made-up store and records what it returns and every cell it writes.
//   node tools/golden/inventory.js <path to Code.gs> > app/src/test/resources/golden/inventory.json
const vm = require('vm'), fs = require('fs');

const tabs = {
  Items: [['ItemID','ItemName','ItemNameTamil','Category','StdDesignation','Unit','Region','Bin',
    'MachineIDs','PhotoURL','Photo2URL','Dim1','Dim2','Dim3','Material','InterchangeableWith',
    'MinLevel','ReorderQty','LeadTimeDays','OnHand','Status','LastPrice','QR','CreatedOn','CreatedBy']],
  Movements: [['Timestamp','MovementID','Type','ItemID','Qty','SignedQty','PersonID','MachineID','Reason',
    'VendorID','InvoiceNo','UnitPrice','Note','Device','Cat','ToolOutQty']],
  People: [['PersonID','Name','NameTamil','Section','PhotoURL','BadgeCode','CanReceive','CanCreateItem','PIN','Active','SortOrder'],
    ['EMP-01', 'Murugan', 'முருகன்', 'MILL', 'https://drive.google.com/file/d/1AbCdEfGhIjKlMnOpQrStUvWxYz012345/view', '', 'Y', 'Y', '4321', 'Y', 2],
    ['EMP-02', 'Selvi', '', 'YARD', '', '', 'N', 'N', '', 'Y', 1],
    ['EMP-03', 'Arun', '', 'PRESS', '', '', 'Y', 'N', 1234, '', ''],
    ['EMP-04', 'Bala', '', 'OFFICE', '', '', 'N', 'Y', '7777', 'N', 3],
    ['EMP-05', 'anand', '', 'MILL', '', '', 'N', 'N', '', 'y', ''],
    ['EMP-07', 'Kavi', '', 'ELECTRICAL', '', '', 'Y', 'Y', '5555', 'Y', 2]],
  Machines: [['MachineID','MachineName','NameTamil','Section','PhotoURL','Active'],
    ['M1', 'Fibre mill 1', '', 'MILL', '', 'Y'], ['M2', 'Baler', '', 'PRESS', 'https://drive.google.com/open?id=1ZyXwVuTsRqPoNmLkJiHgFeDcBa98765', ''],
    ['M3', 'Old sieve', '', 'YARD', '', 'N'], ['', 'blank', '', '', '', '']],
  Regions: [['Region','Description','Colour','BoardLocation'], ['A', 'Bearings', '#d32f2f', 'Left wall'], ['B', '', '#f57c00', ''],
    ['c', 'Belts', '#fbc02d', 'Rack 2'], ['D', '', '', ''], ['E', '', '#0288d1', ''], ['', 'x', '', '']],
  Config: [['Key','Value','Note'], ['COMPANY_NAME', 'Fibro Coir Products', ''], ['WEBAPP_URL', 'https://script.google.com/macros/s/TEST/exec', ''],
    ['PHOTO_FOLDER_ID', 'FOLDER123', ''], ['SUPERVISOR_PIN', 9999, ''], ['ITEM_PREFIX', 'itm', ''],
    ['ALLOW_NEGATIVE_STOCK', 'N', ''], ['DEFAULT_REASON', 'GENERAL', '']],
  Vendors: [['VendorID','VendorName','Phone','City','GSTIN','Notes'], ['V1', 'Pollachi Bearings', '', '', '', ''], ['V2', 'Coimbatore Tools', '', '', '', ''], ['', '', '', '', '', '']],
  ItemVendors: [['ItemID','VendorID','Brand','VendorPartNo','LastPrice','LastPurchaseDate','LeadTimeDays','Preferred','QualityNote']],
};
const names = ['Bearing 6205-2RS', 'Bearing 6308', 'V belt B-64', 'V belt B-66', 'Contactor 32A', 'Spanner 17mm', 'Grease EP2', 'Cutting blade',
  'Oil seal 35x52x7', 'Chain 3/4 duplex', 'Hammer 1kg', 'Fuse 63A', 'Bolt M12x50', 'Welding rod 3.15', 'Belt B-64 (old)', 'Sieve mesh 4mm',
  'Pulley 8 inch', 'Allen key set', 'Bearing housing UCP 205', 'Motor 5HP', 'Coupling L-100', 'Cable 4 sq mm', 'Drill bit 10mm', 'Gloves'];
let seed = 7; const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
const pick = a => a[Math.floor(rnd() * a.length)];
names.forEach((n, i) => {
  const cat = /Spanner|Hammer|Allen|Drill/.test(n) ? 'TOOL' : /Grease|rod|Gloves|Bolt|Fuse/.test(n) ? 'consumable' : 'SPARE';
  const onhand = i % 7 === 3 ? 0 : i % 11 === 5 ? '' : Math.round(rnd() * 20 * 2) / 2;
  tabs.Items.push(['ITM-' + String(i + 1).padStart(4, '0'), n, i % 3 ? '' : 'பேரிங் ' + i, cat, /6205/.test(n) ? '6205-2RS' : /B-64/.test(n) ? 'B-64' : '',
    pick(['PCS', 'pcs', 'SET', 'KG', 'M', '']), pick(['A', 'B', 'c', 'D', '']), i % 4 ? 'A-' + i : '', i % 5 ? 'M1' : 'M1,M2',
    i % 3 === 0 ? 'https://drive.google.com/file/d/1QwErTyUiOpAsDfGhJkLzXcVbNm' + String(i).padStart(4, '0') + '/view' : '', '',
    i % 2 ? 52 : '', i % 2 ? 25 : '', i % 6 === 0 ? 'long' : '', pick(['MS', '', 'SS304']), i === 2 ? 'ITM-0004' : '',
    i % 4 === 1 ? '' : Math.round(rnd() * 6), i % 3 ? 4 : '', i % 5 ? 3 : '', onhand, i === 9 ? 'INACTIVE' : i === 10 ? '' : 'ACTIVE', 120, '', '', 'EMP-01']);
});
tabs.Items.push(['', 'no id row', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '']);
for (let i = 0; i < 9; i++) tabs.Movements.push([new Date(Date.UTC(2026, 9, 1 + i)), 'MOV-' + String(i + 1).padStart(6, '0'), 'TAKE', 'ITM-0006', 1, -1, 'EMP-02', '', 'GENERAL', '', '', '', '', '', 'TOOL', 1]);

const writes = [];
const mark = v => v instanceof Date ? 'DATE' : v;
function sheet(name) {
  const rows = tabs[name];
  const sh = {
    getName: () => name, getSheetId: () => 1, getMaxRows: () => 1000, getLastRow: () => rows.length,
    getLastColumn: () => rows[0].length, getMaxColumns: () => rows[0].length,
    getDataRange: () => sh.getRange(1, 1, rows.length, rows[0].length),
    getRange: (r, c, nr = 1, nc = 1) => {
      const rng = {
        getValues: () => { const o = []; for (let i = 0; i < nr; i++) { const row = rows[r - 1 + i] || []; const x = []; for (let j = 0; j < nc; j++) x.push(row[c - 1 + j] === undefined ? '' : row[c - 1 + j]); o.push(x); } return o; },
        getValue: () => rng.getValues()[0][0],
        setValues: v => { writes.push({ tab: name, row: r, col: c, values: v.map(x => x.map(mark)) }); v.forEach((x, i) => { rows[r - 1 + i] = rows[r - 1 + i] || []; x.forEach((y, j) => rows[r - 1 + i][c - 1 + j] = y); }); return rng; },
        setValue: v => rng.setValues([[v]]),
        setFormula: f => { writes.push({ tab: name, row: r, col: c, formula: f }); return rng; },
        setNumberFormat: () => rng,
      };
      return rng;
    },
  };
  return sh;
}
const book = { getSheetByName: n => tabs[n] ? sheet(n) : null, getName: () => 'Stores' };
const ctx = { console, JSON, Date, Math, Object, Array, String, Number, Boolean, Error, RegExp, isNaN, isFinite, parseFloat, parseInt, encodeURIComponent,
  SpreadsheetApp: { getActiveSpreadsheet: () => book, flush() {} },
  LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
  Session: { getScriptTimeZone: () => 'Asia/Kolkata' },
  Utilities: { formatDate: () => '20261015-101500', newBlob: (b, m, n) => ({ n }), base64Decode: s => s },
  DriveApp: { Access: { ANYONE_WITH_LINK: 1 }, Permission: { VIEW: 1 },
    getFolderById: id => ({ createFile: b => ({ getId: () => 'FILE-' + b.n, setSharing() {} }) }) },
  HtmlService: {},
};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx);
const plain = o => JSON.parse(JSON.stringify(o));
const tryIt = f => { try { return { out: plain(f()) }; } catch (e) { return { error: e.message }; } };
const snapshot = plain(Object.fromEntries(Object.entries(tabs).map(([k, rows]) => [k, rows.map(r => r.map(v => v instanceof Date ? (v.getTime() + 330 * 60000) / 864e5 + 25569 : v))])));

const out = { tabs: snapshot, cases: [] };
const add = (kind, args, f) => { const n = writes.length; const r = tryIt(f); out.cases.push({ kind, args, ...r, writes: writes.slice(n) }); };

[['', '', ''], ['bearing', '', ''], ['6205', '', ''], ['belt b', '', ''], ['itm-0003', '', ''], ['', 'A', ''], ['', '', 'TOOL'],
 ['', 'c', 'spare'], ['b-64', '', ''], ['  MS  ', 'B', ''], ['zzz', '', '']].forEach(a => add('search', a, () => ctx.searchItems(a[0], a[1], a[2])));
['itm-0005', 'ITM-0012', 'nope'].forEach(id => add('item', [id], () => ctx.getItem(id)));
[['Bearing 6205 sealed', '6205-2RS', 'A'], ['V belt', '', ''], ['xy', 'B-64', 'c'], ['Grease gun', '', 'D']].forEach(a => add('similar', a, () => ctx.findSimilar(a[0], a[1], a[2])));
add('nextItem', [], () => ctx.peekNextItemId());
add('nextPerson', [], () => ctx.peekNextPersonId());
add('boot', [], () => ctx.getBootstrap());
add('newBoot', [], () => ctx.getNewBootstrap());
add('peopleAdmin', [], () => ctx.getPeopleAdmin());
[['EMP-01', '4321', 'canreceive'], ['EMP-01', '1111', 'canreceive'], ['EMP-02', '9999', 'canreceive'], ['EMP-02', '', 'canreceive'],
 ['EMP-03', '1234', 'canreceive'], ['emp-04', '7777', 'cancreateitem'], ['EMP-99', '9999', 'canreceive'], ['EMP-07', '5555', 'cancreateitem']]
  .forEach(a => add('pin', a, () => ctx.requirePin(a[0], a[1], a[2]).personid));

const mv = p => add('move', [p], () => ctx.submitMovement(p));
mv({ type: 'TAKE', itemId: 'ITM-0001', qty: 1, personId: 'EMP-02', machineId: 'M1', reason: 'GENERAL', pin: '', device: 'phone' });
mv({ type: 'take', itemId: 'itm-0002', qty: 0.5, personId: 'EMP-05', machineId: '', reason: '', pin: '', device: '' });
mv({ type: 'TAKE', itemId: 'ITM-0001', qty: 999, personId: 'EMP-02', pin: '' });
mv({ type: 'TAKE', itemId: 'ITM-0004', qty: 1, personId: 'EMP-02', pin: '' });
mv({ type: 'RECEIVE', itemId: 'ITM-0003', qty: 10, personId: 'EMP-02', pin: '' });
mv({ type: 'RECEIVE', itemId: 'ITM-0003', qty: 10, personId: 'EMP-01', pin: '4321', note: 'bill 55', vendorId: 'V1', invoiceNo: 'INV-9', unitPrice: 85 });
mv({ type: 'RETURN', itemId: 'ITM-0006', qty: 2, personId: 'EMP-03', pin: '' });
mv({ type: 'OPENING', itemId: 'ITM-0006', qty: 2, personId: 'EMP-03', pin: '' });
mv({ type: 'TAKE', itemId: 'ITM-0006', qty: 0, personId: 'EMP-03', pin: '' });
mv({ type: 'TAKE', itemId: '', qty: 1, personId: 'EMP-03', pin: '' });
mv({ type: 'TAKE', itemId: 'ITM-0006', qty: 1, personId: '', pin: '' });
mv({ type: 'TAKE', itemId: 'ITM-0404', qty: 1, personId: 'EMP-03', pin: '' });
mv({ type: 'TAKE', itemId: 'ITM-0006', qty: 1, personId: 'EMP-404', pin: '' });

const base = { name: 'Bearing 6206-ZZ', tamil: 'பேரிங்', std: '6206-ZZ', category: 'spare', unit: 'pcs', region: 'a', bin: ' A-7 ', machines: ['M1', 'M2'],
  dim1: '62', dim2: '30', dim3: '', material: 'Steel', swap: '', minLevel: '2', reorderQty: '', leadTime: '3', openingQty: '4',
  vendorId: 'V1', brand: ' SKF ', price: '210', vendorPartNo: '6206ZZ', photo: 'B64', photoMime: 'image/jpeg', photo2: 'B64B', photo2Mime: 'image/jpeg',
  personId: 'EMP-01', pin: '4321' };
add('create', [base], () => ctx.createItem(base));
add('create', [{ ...base, name: 'bearing 6205-2rs' }], () => ctx.createItem({ ...base, name: 'bearing 6205-2rs' }));
add('create', [{ ...base, name: 'Thing', photo: '' }], () => ctx.createItem({ ...base, name: 'Thing', photo: '' }));
add('create', [{ ...base, name: 'Thing', pin: '0000' }], () => ctx.createItem({ ...base, name: 'Thing', pin: '0000' }));
const plainItem = { ...base, name: 'Plain thing', machines: [], openingQty: '', vendorId: '', brand: '', price: '', photo2: '', minLevel: '', leadTime: '', pin: '9999', personId: 'EMP-02' };
add('create', [plainItem], () => ctx.createItem(plainItem));

const ed = { itemId: 'ITM-0002', name: 'Bearing 6308 ZZ', tamil: '', std: '', category: 'spare', unit: 'PCS', region: 'b', bin: 'A-1', machines: ['M2'],
  dim1: '52', dim2: '25', dim3: '', material: 'MS', swap: '', minLevel: '3', reorderQty: '', leadTime: '3', status: 'active', countedQty: '7',
  photo: null, personId: 'EMP-07', pin: '5555' };
add('edit', [ed], () => ctx.updateItem(ed));
add('edit', [{ itemId: 'ITM-0008', personId: 'EMP-01', pin: '4321', photo: 'P' }], () => ctx.updateItem({ itemId: 'ITM-0008', personId: 'EMP-01', pin: '4321', photo: 'P' }));
add('edit', [{ itemId: 'ITM-0008', personId: 'EMP-02', pin: '' }], () => ctx.updateItem({ itemId: 'ITM-0008', personId: 'EMP-02', pin: '' }));
add('edit', [{ itemId: 'ITM-0011', personId: 'EMP-01', pin: '4321', countedQty: 'x' }], () => ctx.updateItem({ itemId: 'ITM-0011', personId: 'EMP-01', pin: '4321', countedQty: 'x' }));

const pp = { byPersonId: '', pin: '9999', name: 'Ravi', tamil: '', section: 'yard', canReceive: true, canCreate: false, newPin: ' 2468 ', photo: 'F', sortOrder: '' };
add('person', [pp], () => ctx.addPerson(pp));
add('person', [{ ...pp, name: 'selvi' }], () => ctx.addPerson({ ...pp, name: 'selvi' }));
add('person', [{ ...pp, name: 'New one', newPin: '' }], () => ctx.addPerson({ ...pp, name: 'New one', newPin: '' }));
add('person', [{ ...pp, name: 'Via admin', byPersonId: 'EMP-07', pin: '5555', photo: '', canReceive: false }], () => ctx.addPerson({ ...pp, name: 'Via admin', byPersonId: 'EMP-07', pin: '5555', photo: '', canReceive: false }));
add('person', [{ ...pp, name: 'Nope', pin: '1' }], () => ctx.addPerson({ ...pp, name: 'Nope', pin: '1' }));
add('active', [{ byPersonId: '', pin: '9999', personId: 'EMP-02', active: false }], () => ctx.setPersonActive({ byPersonId: '', pin: '9999', personId: 'EMP-02', active: false }));
process.stdout.write(JSON.stringify(out));
