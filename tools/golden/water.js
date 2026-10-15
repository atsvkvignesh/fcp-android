// Golden data for the Water Load screen: runs the web page's own trip logic
// (copied from water-load-ui Index.html, onData + render) on a made-up week of taps
// and writes what it found. WaterModelTest checks the Kotlin code gives the same.
//   node tools/golden/water.js > app/src/test/resources/golden/water.json
var TEST_TAP_MIN = 1, NEW_DAY_GAP_H = 6, PARKED_MIN = 180, TZ = 'Asia/Kolkata';
var F = { day: new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }) };
function dayKey(t) { var p = {}; F.day.formatToParts(new Date(t)).forEach(function (x) { p[x.type] = x.value; }); return p.year + '-' + p.month + '-' + p.day; }
function avg(a) { return a.length ? a.reduce(function (x, y) { return x + y; }, 0) / a.length : 0; }

// a seeded random so the file is the same every run
var seed = 12345; function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
var events = [], start = Date.UTC(2026, 9, 1, 0, 30); // 06:00 IST, 1 Oct 2026
var cards = [['11CE2D07', 'Tuck Tanker'], ['A1B2C3D4', 'Big Blue'], ['FFEE0011', '']];
cards.forEach(function (c, ci) {
  for (var d = 0; d < 7; d++) {
    var t = start + d * 864e5 + ci * 17 * 60000, n = 3 + Math.floor(rnd() * 7);
    for (var i = 0; i < n; i++) {
      t += (10 + rnd() * 70) * 60000;
      var r = rnd(), mins = r < 0.12 ? rnd() * 0.9 : r < 0.18 ? 200 + rnd() * 400 : 4 + rnd() * 30;
      events.push({ t: Math.round(t), uid: c[0], name: c[1] || c[0], place: 'FCP', ev: 'IN' });
      if (rnd() < 0.05) events.push({ t: Math.round(t + 30000), uid: c[0], name: c[1] || c[0], place: 'FCP', ev: 'IN' }); // double IN
      t += mins * 60000;
      events.push({ t: Math.round(t), uid: c[0], name: c[1] || c[0], place: 'FCP', ev: 'OUT' });
      if (rnd() < 0.05) events.push({ t: Math.round(t + 45000), uid: c[0], name: c[1] || c[0], place: 'FCP', ev: 'OUT' }); // stray OUT
    }
  }
});
events.push({ t: start + 7 * 864e5 + 3600e3, uid: '11CE2D07', name: 'Tuck Tanker', place: 'FCP', ev: 'IN' }); // still inside
events.sort(function (a, b) { return a.t - b.t; });

// ---- from onData()
var open = {}, trips = [];
events.forEach(function (e) {
  if (e.ev === 'IN') open[e.uid] = e;
  else if (open[e.uid]) {
    var i = open[e.uid];
    trips.push({ uid: e.uid, name: i.name || e.name, tin: i.t, tout: e.t, mins: (e.t - i.t) / 60000 });
    delete open[e.uid];
  }
});
var prevOut = {};
trips.forEach(function (t) {
  t.day = dayKey(t.tout);
  t.test = t.mins < TEST_TAP_MIN;
  t.parked = t.mins >= PARKED_MIN;
  if (t.test) return;
  var p = prevOut[t.uid];
  t.back = (p && (t.tin - p) / 3600e3 <= NEW_DAY_GAP_H) ? (t.tin - p) / 60000 : null;
  prevOut[t.uid] = t.tout;
});
// ---- from render(), per day
var days = {};
trips.forEach(function (t) { (days[t.day] = days[t.day] || []).push(t); });
var summary = {};
Object.keys(days).forEach(function (k) {
  var day = days[k], real = day.filter(function (t) { return !t.test; });
  var fills = real.filter(function (t) { return !t.parked; }).map(function (t) { return t.mins; });
  var backs = real.filter(function (t) { return t.back != null; }).map(function (t) { return t.back; });
  summary[k] = { loads: real.length, tests: day.length - real.length,
    avgFill: fills.length ? avg(fills) : null, avgBack: backs.length ? avg(backs) : null,
    fastest: fills.length ? Math.min.apply(null, fills) : null, slowest: fills.length ? Math.max.apply(null, fills) : null,
    quickestBack: backs.length ? Math.min.apply(null, backs) : null };
});
process.stdout.write(JSON.stringify({ tz: TZ, events: events, trips: trips.map(function (t) {
  return { uid: t.uid, name: t.name, tin: t.tin, tout: t.tout, mins: t.mins, day: t.day, test: t.test, parked: t.parked, back: t.back === undefined ? null : t.back };
}), summary: summary }, null, 1));
