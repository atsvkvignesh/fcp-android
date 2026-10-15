"""Writes apps-script/<tool>/Api.gs for every tool from apps-script/json-api/Api.gs.
The key comes from app/src/main/assets/lib/config.js so app and scripts always match.
Usage: python3 tools/make_api.py"""
import os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATE = open(os.path.join(ROOT, 'apps-script/json-api/Api.gs'), encoding='utf-8').read()
CONFIG = open(os.path.join(ROOT, 'app/src/main/assets/lib/config.js'), encoding='utf-8').read()
KEY = re.search(r"key:\s*'([^']+)'", CONFIG).group(1)

HUB_EXTRA = r'''
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
'''

TOOLS = {
    # app id      Apps Script project       functions the screen calls
    'hub':        ('FCP App Hub',   ['app_login', 'app_state', 'app_logout',
                                     'api_addLink', 'api_saveLink', 'api_deleteLink', 'api_moveLink',
                                     'api_addUser', 'api_deleteUser', 'api_setPassword', 'api_setUserApps',
                                     'api_changeMyPassword'], HUB_EXTRA),
    'ec-log':     ('EC Reading',    ['api_boot', 'api_add', 'api_update', 'api_remove'], ''),
    'ec-viewer':  ('EC view',       ['api_summary', 'api_records', 'api_export', 'api_refresh', 'api_spots',
                                     'api_setLimits', 'api_clearLimits'], ''),
    'husk-check': ('Husk Compare',  ['api_load', 'api_save', 'api_undo', 'api_export'], ''),
    'inventory':  ('Inventory',     ['getBootstrap', 'getNewBootstrap', 'getPeopleAdmin', 'searchItems', 'getItem',
                                     'findSimilar', 'submitMovement', 'createItem', 'updateItem', 'addPerson'], ''),
    'fleet':      ('Fleet Manager', ['getBootstrap', 'getLogs', 'getServiceReminderHistory',
                                     'saveVehicle', 'deleteVehicle', 'saveOdometer', 'saveFuel', 'saveService',
                                     'saveDocument', 'deleteLog', 'saveServiceReminder', 'deleteServiceReminder',
                                     'completeServiceReminder', 'saveSettings', 'sendTestReminder',
                                     'dailyReminderCheck'], ''),
    'water-load': ('Water Load UI', ['getData'], ''),
}

for app, (project, allow, extra) in TOOLS.items():
    body = (TEMPLATE
            .replace('__APP__', app)
            .replace('__KEY__', KEY)
            .replace('[__ALLOW__]', '[\n    ' + ',\n    '.join(repr(a) for a in allow) + '\n  ]')
            .replace('__EXTRA__', extra))
    body = body.replace(' * Api.gs — lets the Fibro Coir app', f' * Api.gs for "{project}" — lets the Fibro Coir app', 1)
    out = os.path.join(ROOT, 'apps-script', app, 'Api.gs')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8').write(body)
    print(f'{os.path.relpath(out, ROOT):34s} for "{project}" ({len(allow)} functions)')
