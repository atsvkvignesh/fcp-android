// Golden data for Fleet: runs the real Fleet Code.gs (path in argv[2]) on a made-up fleet with
// the clock fixed at 15 Oct 2026 10:30 IST, and records what it returns and every sheet change.
//   TZ=Asia/Kolkata node tools/golden/fleet.js <path to Code.gs> > app/src/test/resources/golden/fleet.json
const vm = require('vm'), fs = require('fs');
if (process.env.TZ !== 'Asia/Kolkata') throw new Error('run with TZ=Asia/Kolkata');
const NOW = new Date(2026, 9, 15, 10, 30, 0).getTime();
class FakeDate extends Date {
  constructor(...a) { if (a.length) super(...a); else super(NOW); }
  static now() { return NOW; }
}
let seed = 42;
const rand = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
const FakeMath = Object.create(Math); FakeMath.random = rand;

const D = (y, m, d, h = 12) => new FakeDate(y, m - 1, d, h, 0, 0);
const tabs = {
  Vehicles: [['VehicleID','RegNo','Name','Type','FuelType','TankCapacity','Driver','Status','CurrentOdo','LastOdoDate','Notes','UpdatedAt'],
    ['VEH-A', 'TN41AB1234', 'Coco peat lorry', 'Lorry', 'Diesel', 200, 'Ravi', 'Active', 45210, D(2026, 10, 14), '', D(2026, 9, 1)],
    ['VEH-B', 'TN41CD5678', 'Tractor', 'Tractor', 'Diesel', 60, '', 'Active', 1200, D(2026, 10, 5), '', D(2026, 9, 1)],
    ['VEH-C', 'TN41EF9999', 'Old van', 'Van', 'Petrol', 40, '', 'Retired', 98000, D(2025, 1, 1), '', D(2026, 9, 1)],
    ['VEH-D', 'TN41GH0001', 'New car', 'Car', 'Petrol', 35, '', 'Active', '', '', 'just bought', D(2026, 10, 1)]],
  Odometer: [['LogID','Timestamp','VehicleID','RegNo','Date','Reading','KmSinceLast','EnteredBy','Notes'],
    ['ODO-1', D(2026, 10, 1, 9), 'VEH-A', 'TN41AB1234', D(2026, 10, 1), 44800, 300, 'a@x', ''],
    ['ODO-2', D(2026, 10, 14, 9), 'VEH-A', 'TN41AB1234', D(2026, 10, 14), 45210, 410, 'a@x', 'trip'],
    ['ODO-3', D(2026, 10, 5, 9), 'VEH-B', 'TN41CD5678', D(2026, 10, 5), 1200, '', 'a@x', '']],
  Fuel: [['LogID','Timestamp','VehicleID','RegNo','Date','Odometer','FuelType','Litres','RatePerLitre','TotalCost','Station','Mileage','BillURL','EnteredBy','Notes'],
    ['FUE-1', D(2026, 9, 20, 9), 'VEH-A', 'TN41AB1234', D(2026, 9, 20), 44000, 'Diesel', 120, 92.5, 11100, 'HP', '', '', 'a@x', ''],
    ['FUE-2', D(2026, 10, 2, 9), 'VEH-A', 'TN41AB1234', D(2026, 10, 2), 44800, 'Diesel', 100, 93, 9300, 'IOC', 8, '', 'a@x', ''],
    ['FUE-3', D(2026, 10, 6, 9), 'VEH-B', 'TN41CD5678', D(2026, 10, 6), '', 'Diesel', 30, 93, 2790, '', '', '', 'a@x', '']],
  Service: [['ServiceID','Timestamp','VehicleID','RegNo','Date','Odometer','Category','Summary','PartsSummary','Cost','Vendor','NextServiceDate','NextServiceOdo','BillURL','EnteredBy','Notes'],
    ['SVC-1', D(2026, 10, 3, 9), 'VEH-A', 'TN41AB1234', D(2026, 10, 3), 44900, 'Routine service', 'Oil change', 'Oil filter [OF-12]', 4500, 'Sri Garage', D(2027, 1, 3), 54900, '', 'a@x', ''],
    ['SVC-2', D(2026, 8, 3, 9), 'VEH-B', 'TN41CD5678', D(2026, 8, 3), '', 'Tyres', 'Rear tyre', '', 9000, '', '', '', '', 'a@x', '']],
  Parts: [['PartRowID','ServiceID','VehicleID','RegNo','Date','PartName','PartNumber','Qty','Cost'],
    ['PRT-1', 'SVC-1', 'VEH-A', 'TN41AB1234', D(2026, 10, 3), 'Oil filter', 'OF-12', 1, 450],
    ['PRT-2', 'SVC-2', 'VEH-B', 'TN41CD5678', D(2026, 8, 3), 'Tyre', '', 1, 9000]],
  Documents: [['DocID','Timestamp','VehicleID','RegNo','DocType','DocNumber','IssueDate','ExpiryDate','Provider','Cost','FileURL','Notes'],
    ['DOC-1', D(2025, 1, 1), 'VEH-A', 'TN41AB1234', 'Insurance', 'P1', D(2025, 1, 1), D(2026, 1, 1), 'LIC', 9000, '', ''],
    ['DOC-2', D(2026, 1, 1), 'VEH-A', 'TN41AB1234', 'Insurance', 'P2', D(2026, 1, 1), D(2026, 12, 31), 'LIC', 9500, 'https://drive/x', ''],
    ['DOC-3', D(2026, 1, 1), 'VEH-A', 'TN41AB1234', 'FC', 'F1', '', D(2026, 10, 25), 'RTO', 0, '', ''],
    ['DOC-4', D(2026, 1, 1), 'VEH-A', 'TN41AB1234', 'Tax', 'T1', '', D(2026, 10, 10), 'RTO', 0, '', ''],
    ['DOC-5', D(2026, 1, 1), 'VEH-B', 'TN41CD5678', 'Permit', 'PM1', '', D(2026, 11, 20, 0), 'RTO', 0, '', ''],
    ['DOC-6', D(2026, 1, 1), 'VEH-B', 'TN41CD5678', 'Pollution', 'PU', '', '2026-10-15', '', '', '', ''],
    ['DOC-7', D(2026, 1, 1), 'VEH-C', 'TN41EF9999', 'FC', 'old', '', D(2020, 1, 1), '', '', '', '']],
  ServiceReminders: [['ReminderID','Timestamp','VehicleID','RegNo','Name','DueDate','DueOdo','RepeatDays','RepeatKm','Status','CompletedDate','Notes'],
    ['REM-1', D(2026, 9, 1), 'VEH-A', 'TN41AB1234', 'Grease', D(2026, 10, 20), '', 30, '', 'Open', '', ''],
    ['REM-2', D(2026, 9, 1), 'VEH-A', 'TN41AB1234', 'Oil change', '', 45500, '', 5000, 'Open', '', 'every 5000'],
    ['REM-3', D(2026, 9, 1), 'VEH-B', 'TN41CD5678', 'Belt check', D(2026, 10, 1), '', '', '', 'Open', '', ''],
    ['REM-4', D(2026, 9, 1), 'VEH-B', 'TN41CD5678', 'Wash', D(2026, 12, 1), 3000, '', '', 'open', '', ''],
    ['REM-5', D(2026, 8, 1), 'VEH-B', 'TN41CD5678', 'Old one', D(2026, 8, 1), '', '', '', 'Done', D(2026, 8, 2), '']],
  ReminderLog: [['Timestamp','Key','RegNo','DocType','ExpiryDate','DaysLeft','SentTo']],
  Settings: [['Key','Value','Description'], ['recipients', 'a@x.com', ''], ['reminderDays', '60,30,15,7,3,1,0', ''], ['reminderHour', '7', ''],
    ['expiredRepeat', 'DAILY', ''], ['odoNudge', 'WEEKLY', ''], ['odoStaleDays', '3', ''], ['serviceKmWarn', '1000,500,200,0', ''],
    ['currency', 'Rs.', ''], ['companyName', 'Fibro Coir Products', ''], ['driveFolderId', 'FLEETROOT', '']],
};
const ops = [];
const mark = v => v instanceof Date ? { d: fmt(v, '', 'yyyy-MM-dd HH:mm:ss') } : v;
function fmt(d, tz, f) {
  const p = n => String(n).padStart(2, '0'), M = ['January','February','March','April','May','June','July','August','September','October','November','December'];
  return f.replace(/yyyy|MMMM|MMM|MM|dd|HH|mm|ss/g, k => ({ yyyy: d.getFullYear(), MMMM: M[d.getMonth()], MMM: M[d.getMonth()].slice(0, 3), MM: p(d.getMonth() + 1), dd: p(d.getDate()), HH: p(d.getHours()), mm: p(d.getMinutes()), ss: p(d.getSeconds()) })[k]);
}
function sheet(name) {
  const rows = tabs[name];
  const sh = {
    getName: () => name, getLastRow: () => rows.length, getMaxColumns: () => rows[0].length,
    appendRow: v => { ops.push({ op: 'set', tab: name, row: rows.length + 1, values: v.map(mark) }); rows.push(v.slice()); return sh; },
    deleteRow: r => { ops.push({ op: 'del', tab: name, row: r }); rows.splice(r - 1, 1); return sh; },
    getRange: (r, c, nr = 1, nc = 1) => {
      const rng = {
        getValues: () => { const o = []; for (let i = 0; i < nr; i++) { const row = rows[r - 1 + i] || []; const x = []; for (let j = 0; j < nc; j++) x.push(row[c - 1 + j] === undefined ? '' : row[c - 1 + j]); o.push(x); } return o; },
        setValues: v => { v.forEach((x, i) => { ops.push({ op: 'set', tab: name, row: r + i, col: c, values: x.map(mark) }); rows[r - 1 + i] = rows[r - 1 + i] || []; x.forEach((y, j) => rows[r - 1 + i][c - 1 + j] = y); }); return rng; },
        setValue: v => rng.setValues([[v]]),
        setFontWeight: () => rng, setBackground: () => rng, setFontColor: () => rng,
      };
      return rng;
    },
  };
  return sh;
}
const book = { getSheetByName: n => tabs[n] ? sheet(n) : null, toast() {} };
const files = [];
const folder = id => ({ getId: () => id, getFoldersByName: n => { let done = false; return { hasNext: () => !done, next: () => { done = true; return folder(id + '/' + n); } }; },
  createFile: b => { files.push({ folder: id, name: b.name, mime: b.mime }); return { setSharing() {}, getUrl: () => 'https://drive.google.com/file/d/' + encodeURIComponent(b.name) + '/view' }; }, createFolder: n => folder(id + '/' + n) });
const ctx = { console, JSON, Date: FakeDate, Math: FakeMath, Object, Array, String, Number, Boolean, Error, RegExp, isNaN, isFinite, parseFloat, parseInt,
  SpreadsheetApp: { openById: () => book, getActive: () => book },
  Session: { getScriptTimeZone: () => 'Asia/Kolkata', getActiveUser: () => ({ getEmail: () => 'me@fcp' }) },
  Utilities: { formatDate: fmt, newBlob: (data, mime, name) => ({ name, mime }), base64Decode: s => s },
  DriveApp: { getFolderById: folder, createFolder: n => folder('NEW:' + n), Access: {}, Permission: {} },
  ScriptApp: { getProjectTriggers: () => [], newTrigger: () => ({ timeBased: () => ({ atHour: () => ({ everyDays: () => ({ create() {} }) }) }) }) },
  HtmlService: {},
};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx);
const plain = o => JSON.parse(JSON.stringify(o, (k, v) => v));
const snap = () => JSON.parse(JSON.stringify(Object.fromEntries(Object.entries(tabs).map(([k, rows]) => [k, rows.map(r => r.map(v => v instanceof Date ? (v.getTime() + 330 * 60000) / 864e5 + 25569 : v))]))));
const out = { now: NOW, tabs: snap(), cases: [] };
const add = (kind, args, f) => { const n = ops.length, nf = files.length, s0 = seed; let r;
  try { r = { out: plain(f()) }; } catch (e) { r = { error: e.message }; }
  out.cases.push({ kind, args, seed: s0, ...r, ops: ops.slice(n), files: files.slice(nf) }); };

add('boot', [], () => ctx.getBootstrap());
['odo', 'fuel', 'service', 'docs'].forEach(k => add('logs', [k, '', 60], () => ctx.getLogs(k, '', 60)));
add('logs', ['fuel', 'VEH-A', 60], () => ctx.getLogs('fuel', 'VEH-A', 60));
add('history', [], () => ctx.getServiceReminderHistory());
const call = (name, arg) => add(name, [arg], () => ctx[name](arg));
call('saveOdometer', { vehicleId: 'VEH-A', date: '2026-10-15', reading: '45000', notes: '' });
call('saveOdometer', { vehicleId: 'VEH-A', date: '2026-10-15', reading: '45100', notes: 'corrected', allowLower: true });
call('saveOdometer', { vehicleId: 'VEH-A', date: '', reading: '45600', notes: 'evening' });
call('saveOdometer', { id: 'ODO-3', vehicleId: 'VEH-B', date: '2026-10-05', reading: '1250', notes: 'fixed' });
call('saveOdometer', { vehicleId: 'VEH-X', date: '', reading: '1' });
call('saveFuel', { vehicleId: 'VEH-A', date: '2026-10-15', odometer: '45900', fuelType: 'Diesel', litres: '110', rate: '', total: '10340', station: 'HP', notes: '', file: { name: 'bill.jpg', mimeType: 'image/jpeg', data: 'x' } });
call('saveFuel', { vehicleId: 'VEH-B', date: '', odometer: '', fuelType: '', litres: '20.5', rate: '94', total: '', station: '', notes: 'can' });
call('saveFuel', { id: 'FUE-2', vehicleId: 'VEH-A', date: '2026-10-02', odometer: '44800', fuelType: 'Diesel', litres: '100', rate: '93.5', total: '', station: 'IOC', notes: 'fixed', billUrl: 'https://old' });
call('saveService', { vehicleId: 'VEH-B', date: '2026-10-14', odometer: '1300', category: 'Breakdown repair', summary: 'Clutch plate', cost: '7,500', vendor: 'Local',
  nextDate: '', nextOdo: '', notes: '', parts: [{ name: 'Clutch plate', number: 'CP-9', qty: '1', cost: '5200' }, { name: '', number: '' }, { name: 'Bolts', number: '', qty: '6', cost: '60' }], file: { name: 'svc.pdf', mimeType: 'application/pdf', data: 'x' } });
call('saveService', { id: 'SVC-1', vehicleId: 'VEH-A', date: '2026-10-03', odometer: '44900', category: 'Routine service', summary: 'Oil change + filter', cost: '4700', vendor: 'Sri Garage',
  nextDate: '2027-01-03', nextOdo: '54900', notes: 'x', parts: [{ name: 'Oil filter', number: 'OF-12', qty: '1', cost: '450' }, { name: 'Oil', number: '', qty: '2', cost: '1200' }] });
call('saveDocument', { vehicleId: 'VEH-A', docType: 'FC', expiryDate: '' });
call('saveDocument', { vehicleId: 'VEH-A', docType: 'Other', customType: ' ', expiryDate: '2027-01-01' });
call('saveDocument', { vehicleId: 'VEH-D', docType: 'Other', customType: 'Green card', docNumber: 'G1', issueDate: '2026-10-01', expiryDate: '2027-10-01', provider: 'RTO', cost: '500', notes: '', file: { name: 'card.png', mimeType: 'image/png', data: 'x' } });
call('saveDocument', { id: 'DOC-4', vehicleId: 'VEH-A', docType: 'Tax', docNumber: 'T2', issueDate: '2026-10-11', expiryDate: '2027-10-10', provider: 'RTO', cost: '1200', notes: '', fileUrl: '' });
call('saveServiceReminder', { vehicleId: 'VEH-A', name: ' ', dueDate: '2026-11-01' });
call('saveServiceReminder', { vehicleId: 'VEH-A', name: 'Tyre rotation', dueDate: '', dueOdo: '' });
call('saveServiceReminder', { vehicleId: 'VEH-D', name: 'First service', dueDate: '2026-11-15', dueOdo: '1000', repeatDays: '', repeatKm: '', notes: '' });
call('saveServiceReminder', { id: 'REM-3', vehicleId: 'VEH-B', name: 'Belt check', dueDate: '2026-10-30', dueOdo: '', repeatDays: '90', repeatKm: '', notes: 'moved' });
call('completeServiceReminder', 'REM-1');
call('completeServiceReminder', 'REM-2');
call('completeServiceReminder', 'REM-4');
call('completeServiceReminder', 'REM-404');
call('deleteServiceReminder', 'REM-3');
call('saveVehicle', { regNo: ' tn41zz0007 ', name: 'Loader', type: 'Loader', fuelType: 'Diesel', tank: '80', driver: '', odo: '320', status: 'Active', notes: '' });
call('saveVehicle', { id: 'VEH-B', regNo: 'TN41CD5678', name: 'Tractor 1', type: 'Tractor', fuelType: 'Diesel', tank: '65', driver: 'Mani', odo: '', status: 'In workshop', notes: 'clutch' });
add('deleteLog', ['service', 'SVC-2'], () => ctx.deleteLog('service', 'SVC-2'));
add('deleteLog', ['odo', 'ODO-2'], () => ctx.deleteLog('odo', 'ODO-2'));
add('deleteLog', ['fuel', 'FUE-3'], () => ctx.deleteLog('fuel', 'FUE-3'));
add('deleteVehicle', ['VEH-C'], () => ctx.deleteVehicle('VEH-C'));
call('saveSettings', { recipients: 'a@x.com, b@y.com', reminderDays: '30,7,0', serviceKmWarn: '500', expiredRepeat: 'WEEKLY', odoNudge: 'OFF', odoStaleDays: '5', newKey: 'v' });
add('boot', [], () => ctx.getBootstrap());
process.stdout.write(JSON.stringify(out));
