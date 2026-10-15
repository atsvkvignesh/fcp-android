"""FCP App Hub (Apps Script project "FCP App Hub") -> assets/hub/index.html, the app's home screen.
Usage: python3 tools/port_hub.py <path to the project's Index.html>

Differences from the web page:
- Signs in with app_login / app_state / app_logout (apps-script/hub/Api.gs): sessions last 60 days
  instead of 6 hours, so people don't sign in again every day.
- Shows the saved list of tools at once and refreshes it in the background.
- A tile opens the tool's built-in screen when its script has Api.gs, otherwise its web page,
  both as a screen over the hub. Google Forms and other links open outside the app as before.
- If the hub script has no Api.gs yet, the app falls back to the Apps Script hub page.
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('hub', 'Fibro Coir Products')

# ---------- sign in / out: long app sessions ----------
p.swap("call('api_login', u, p).then(function(state){\n    busy(false); keep(state.token); apply(state); show('hubView');",
       "call('app_login', u, p).then(function(state){\n    busy(false); keep(state.token); apply(state); show('hubView');\n    FCP.forget(); remember(state);")

# Keep the latest state as the saved copy, so the next start shows the tiles at once
p.swap("""function run(promise){
  busy(true);
  return promise.then(function(state){ busy(false); apply(state); return state; })""", """function remember(state){
  var copy = {}; Object.keys(state).forEach(function(k){ if (k !== 'token') copy[k] = state[k]; });
  FCP.save('app_state', [TOKEN], copy);
}
function run(promise){
  busy(true);
  return promise.then(function(state){ busy(false); apply(state); remember(state); return state; })""")
p.swap("if (t) call('api_logout', t).catch(function(){});",
       "FCP.forget();\n  if (t) call('app_logout', t).catch(function(){});")

# ---------- tiles: open built-in screens, or the web page as a screen ----------
p.swap('''  box.innerHTML = html;
  if ($('addTile')) $('addTile').onclick = function(){ show('manageView'); };
}''', '''  box.innerHTML = html;
  if ($('addTile')) $('addTile').onclick = function(){ show('manageView'); };
  markTiles();
}

/* ---------- tools with a built-in screen in the app ---------- */
var TOOLS_KEY = 'fcp:tools';
function toolMap(){ try{ return JSON.parse(localStorage.getItem(TOOLS_KEY) || '{}') || {}; }catch(e){ return {}; } }
function saveToolMap(m){ try{ localStorage.setItem(TOOLS_KEY, JSON.stringify(m)); }catch(e){} }
function isScript(url){ return /^https:\\/\\/script\\.google\\.com\\/(a\\/[^\\/]+\\/)?macros\\/s\\//.test(url || ''); }
function builtIn(url){
  var k = toolMap()[url];
  return !!(k && k.app && window.FCPApp && FCPApp.hasLocal && FCPApp.hasLocal(k.app)) ? k.app : '';
}
/** Ask each tool's script once in a while whether it has Api.gs (and so a built-in screen). */
function probeTools(){
  var m = toolMap(), now = Date.now();
  (ST.links || []).forEach(function(l){
    if (!isScript(l.url)) return;
    var k = m[l.url];
    if (k && now - k.at < (k.app ? 24 : 0.5) * 3600 * 1000) return;   // yes: re-check daily; no: every 30 min
    FCP.client({ url: l.url, key: FCP_CONFIG.key, app: 'probe', timeout: 30000 }).call('__app', []).then(function(d){
      var mm = toolMap(); mm[l.url] = { app: (d && d.app) || '', at: Date.now() }; saveToolMap(mm); markTiles();
    }, function(err){
      if (err.offline) return;                       // no signal: ask again next time
      var mm = toolMap(); mm[l.url] = { app: '', at: Date.now() }; saveToolMap(mm); markTiles();
    });
  });
}
function markTiles(){
  Array.prototype.forEach.call(document.querySelectorAll('#tiles a.tile'), function(a){
    var go = a.querySelector('.go'); if (!go) return;
    if (builtIn(a.getAttribute('href'))) go.textContent = 'Opens in the app';
  });
}
function openTile(url){
  if (!window.FCPApp) return false;                 // a normal browser: plain link
  var app = builtIn(url);
  if (app){ FCPApp.openLocal(app + '?api=' + encodeURIComponent(url)); return true; }
  if (isScript(url) && FCPApp.openUrl){ FCPApp.openUrl(url); return true; }
  return false;                                      // Google Forms etc. open outside the app
}
$('tiles').addEventListener('click', function(e){
  var a = e.target.closest('a.tile'); if (!a) return;
  if (openTile(a.getAttribute('href'))) e.preventDefault();
});''')

# Check tools for a built-in screen whenever the list of tiles arrives (sign-in, start, admin edits)
p.swap("  drawTiles(); drawLinks(); drawUsers();\n}", "  drawTiles(); drawLinks(); drawUsers(); probeTools();\n}")

# ---------- Back button: close dialogs, leave Manage ----------
p.swap("/* ---------- start ---------- */", '''window.onAppBack = function(){
  var m = document.querySelector('.modal');
  if (m){ var b = m.querySelector('.btn.ghost'); if (b) b.click(); else m.parentNode.removeChild(m); return true; }
  var mv = $('manageView');
  if (mv && !mv.hidden){ show('hubView'); return true; }
  return false;
};

/* ---------- start ---------- */''')

# ---------- start: saved tiles at once, then fresh; fall back to the web hub if needed ----------
p.swap('''  if (!saved){ show('loginView'); $('lu').focus(); return; }
  TOKEN = saved; busy(true);
  call('api_state', saved).then(function(state){
    busy(false); apply(state); show('hubView');
  }).catch(function(){ busy(false); drop(); show('loginView'); });
})();''', '''  if (!saved){ checkHub(); show('loginView'); return; }
  TOKEN = saved;
  var shown = false; busy(true);
  FCP.cached('app_state', [saved], function(state){
    if (!shown){ shown = true; busy(false); show('hubView'); }
    apply(state);
  }).then(function(){ if (!shown) busy(false); }, function(e){
    if (!shown) busy(false);
    var msg = e && e.message || '';
    if (/web page|Not allowed/.test(msg)) return webHub();
    if (/Session ended|Sign in again/i.test(msg)){ drop(); FCP.forget(); FCP.note(null); show('loginView'); say('Please sign in again.', true); return; }
    if (!shown){ show('loginView'); say(e.offline ? 'No internet connection.' : msg, true); }
  });
})();

/* The hub script has no Api.gs yet (or an old one): use the Apps Script hub page instead. */
function webHub(){ if (window.FCPApp) location.replace(FCP_CONFIG.hubUrl); }
function checkHub(){
  FCP.client({ url: FCP_CONFIG.hubUrl, key: FCP_CONFIG.key, app: 'probe' }).call('__app', [])
    .catch(function(e){ if (!e.offline) webHub(); });
}''')

# The web page focused the user name box on start; on a phone that pops the keyboard up at once.
p.write('hub')
