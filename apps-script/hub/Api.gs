/**
 * Api.gs for "FCP App Hub" — lets the Fibro Coir app's built-in screen get its data from this script.
 *
 * The app keeps the screen itself (HTML/CSS/JS) on the phone, so it opens instantly; this file
 * only answers its data requests. The normal /exec web page keeps working in a browser.
 *
 * Install (once per project):
 *   1. In the Apps Script editor: Files "+" > Script > name it  Api  > paste this file.
 *   2. Deploy > Manage deployments > pencil > Version: New version > Deploy.
 *      Access must stay "Anyone". The /exec link does not change.
 *   Nothing else in the project needs editing. The project must not already have a doPost.
 *
 * Each FCP project gets its own copy of this file (apps-script/<tool>/Api.gs in the app repo),
 * with FCP_API filled in: which screen it serves and which functions that screen may call.
 *
 * Request:  POST <exec URL>, body {"action":"api_summary","args":["week","","",""],"key":"..."}
 * Answer:   {"ok":true,"data":...}   or   {"ok":false,"error":"..."}
 * args are passed to the function in order, exactly like google.script.run would.
 */

var FCP_API = {
  app: 'hub',     // the bundled screen this script serves, e.g. 'ec-viewer'
  key: 'hxOi7cSYfGBZxDLzN6nmMNzoHGuZH5lm',     // same key as in the app (assets/lib/config.js)
  allow: [
    'app_login',
    'app_state',
    'app_logout',
    'api_addLink',
    'api_saveLink',
    'api_deleteLink',
    'api_moveLink',
    'api_addUser',
    'api_deleteUser',
    'api_setPassword',
    'api_setUserApps',
    'api_changeMyPassword'
  ], // functions the screen may call
  before: null        // optional function(action, args) run before each call
};

/* ---------- hub only: app sign-ins that last 60 days ----------
 * The hub's own sessions live 6 hours in CacheService. The app signs in with app_login, which
 * also keeps the session in Script Properties for 60 days (renewed while in use). Whenever the
 * 6-hour session has expired, it is put back from there, so people don't sign in every day.
 * Removing a person ends their app sessions. Code.gs is not changed.
 */
var FCP_SESSION_DAYS = 60;

function app_login(login, password) {
  var out = api_login(login, password);              // checks the password, makes the 6-hour token
  fcpSaveSession_(out.token, { id: out.me.id, name: out.me.name, role: out.me.role });
  return out;
}

function app_state(token) {
  return api_state(token);                            // fcpPrime_ already ran (FCP_API.before)
}

function app_logout(token) {
  fcpDropSession_(token);
  return api_logout(token);
}

function fcpSaveSession_(token, session) {
  var props = PropertiesService.getScriptProperties();
  var now = Date.now();
  // tidy up expired sessions
  Object.keys(props.getProperties()).forEach(function (k) {
    if (k.indexOf('ses_') !== 0) return;
    try { if (JSON.parse(props.getProperty(k)).exp < now) props.deleteProperty(k); } catch (e) { props.deleteProperty(k); }
  });
  props.setProperty('ses_' + token, JSON.stringify({ session: session, exp: now + FCP_SESSION_DAYS * 86400000 }));
}

function fcpDropSession_(token) {
  if (token) PropertiesService.getScriptProperties().deleteProperty('ses_' + token);
}

/** If the 6-hour session for this token has ended but the app session is still valid, renew it. */
function fcpPrime_(token) {
  if (!token || typeof token !== 'string') return;
  var cache = CacheService.getScriptCache();
  if (cache.get('tok_' + token)) return;
  var props = PropertiesService.getScriptProperties();
  var raw = props.getProperty('ses_' + token);
  if (!raw) return;
  var s = JSON.parse(raw);
  if (s.exp < Date.now() || !findUser_(readStore_(), s.session.id)) { fcpDropSession_(token); return; }
  cache.put('tok_' + token, JSON.stringify(s.session), TOKEN_TTL);
  s.exp = Date.now() + FCP_SESSION_DAYS * 86400000;
  props.setProperty('ses_' + token, JSON.stringify(s));
}

FCP_API.before = function (action, args) {
  if (action !== 'app_login') fcpPrime_(args[0]);     // every other call starts with the token
};

var FCP_GLOBAL_ = this;

function doPost(e) {
  var started = Date.now();
  var out;
  try {
    var req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (req.key !== FCP_API.key) throw new Error('Not allowed. Update the app.');

    if (req.action === '__app') {
      // The hub asks this once to learn that this tool has a built-in screen in the app
      out = { ok: true, data: { app: FCP_API.app, api: 1 } };
    } else {
      if (FCP_API.allow.indexOf(req.action) < 0) throw new Error('Unknown request: ' + req.action);
      var g = (typeof globalThis !== 'undefined') ? globalThis : FCP_GLOBAL_;
      var fn = g[req.action];
      if (typeof fn !== 'function') throw new Error('Missing function: ' + req.action);
      var args = Array.isArray(req.args) ? req.args : [];
      if (typeof FCP_API.before === 'function') FCP_API.before(req.action, args);
      out = { ok: true, data: fn.apply(null, args) };
    }
  } catch (err) {
    out = { ok: false, error: String((err && err.message) || err) };
  }
  out.ms = Date.now() - started; // server time, handy when checking speed
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}
