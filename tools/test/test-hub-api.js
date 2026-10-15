// Runs the real hub Code.gs + apps-script/hub/Api.gs: sign-in, 60-day sessions, admin calls.
// Usage: node tools/test/test-hub-api.js <hub Code.gs>
const { makeRuntime, post } = require('./gas-mock');
const path = require('path'), assert = require('assert');
const ROOT = path.resolve(__dirname, '../..');
const KEY = /key:\s*'([^']+)'/.exec(require('fs').readFileSync(ROOT + '/app/src/main/assets/lib/config.js', 'utf8'))[1];
const rt = makeRuntime([process.argv[2], ROOT + '/apps-script/hub/Api.gs']);
const call = (action, ...args) => JSON.parse(post(rt, { action, args, key: KEY }));

// seed: one admin + users, like the real store; add a known test user
rt.readStore_();
let r = call('app_login', 'admin@fibrocoirproducts.com', 'wrong');
assert.equal(r.ok, false); console.log('wrong password  ->', r.error);
// make our own admin with a known password (never the real ones)
rt.addUserRecord_; const data = rt.readStore_();
rt.addUserRecord_(data, 'tester', 'Tester', 'admin', 'secret123'); rt.addUserRecord_(data, 'worker', 'Worker', 'user', 'secret456');
rt.writeStore_(data);

r = call('app_login', 'tester', 'secret123'); assert.equal(r.ok, true);
const tok = r.data.token; console.log('login ok        -> token', tok.slice(0, 8) + '…', 'links', r.data.links.length, 'ms', r.ms);
assert.ok(rt.__props['ses_' + tok], 'session saved for 60 days');

r = call('app_state', tok); assert.equal(r.ok, true); console.log('state ok        -> me', r.data.me.name);

// 6-hour cache session expires -> still signed in through the app session
delete rt.__cache['tok_' + tok];
r = call('app_state', tok); assert.equal(r.ok, true); console.log('after 6h expiry -> still signed in:', r.data.me.name);
assert.ok(rt.__cache['tok_' + tok], 'cache session renewed');

// admin call after expiry also works (token renewed first)
delete rt.__cache['tok_' + tok];
r = call('api_addLink', tok, 'EC Viewer', 'https://script.google.com/macros/s/ECV/exec', '📈'); assert.equal(r.ok, true);
console.log('admin add link  -> links now', r.data.links.length);

// a data entry user, then removed by the admin -> their app session ends
r = call('app_login', 'worker', 'secret456'); const wtok = r.data.token;
const wid = r.data.me.id;
r = call('api_deleteUser', tok, wid); assert.equal(r.ok, true);
delete rt.__cache['tok_' + wtok];
r = call('app_state', wtok); assert.equal(r.ok, false); console.log('removed person  ->', r.error);
assert.ok(!rt.__props['ses_' + wtok], 'session dropped');

// expired app session
const s = JSON.parse(rt.__props['ses_' + tok]); s.exp = Date.now() - 1; rt.__props['ses_' + tok] = JSON.stringify(s);
delete rt.__cache['tok_' + tok];
r = call('app_state', tok); assert.equal(r.ok, false); console.log('after 60 days   ->', r.error);

// logout
r = call('app_login', 'tester', 'secret123'); const t2 = r.data.token;
r = call('app_logout', t2); assert.equal(r.ok, true); assert.ok(!rt.__props['ses_' + t2]);
r = call('app_state', t2); assert.equal(r.ok, false); console.log('after sign out  ->', r.error);

// probe, wrong key, unknown function, a function not on the list
console.log('probe           ->', JSON.stringify(call('__app').data));
console.log('wrong key       ->', JSON.parse(post(rt, { action: 'app_state', args: [], key: 'x' })).error);
console.log('not allowed     ->', call('resetToDefaults').error);
console.log('bad body        ->', JSON.parse(post(rt, 'not json')).error);
console.log('ALL HUB API CHECKS PASSED');
