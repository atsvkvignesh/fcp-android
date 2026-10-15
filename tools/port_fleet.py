"""Fleet Manager (Apps Script project "Fleet Manager") -> assets/fleet/index.html
Usage: python3 tools/port_fleet.py <path to the project's Index.html>

- The board and the lists show from the saved copy at once, then refresh.
- After any save or delete the saved copies are dropped, so nothing old flashes up.
- Bill photos / PDFs go through the app (POST), so their size is no problem.
- Back closes the menu, then returns to the board, then leaves.
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('fleet', 'Fleet Manager')

p.swap('''function run(name){
  var args = [].slice.call(arguments, 1);
  return new Promise(function(res, rej){
    google.script.run.withSuccessHandler(res).withFailureHandler(rej)[name].apply(null, args);
  });
}''', '''function run(name){
  var args = [].slice.call(arguments, 1);
  return new Promise(function(res, rej){
    google.script.run.withSuccessHandler(res).withFailureHandler(rej)[name].apply(null, args);
  }).then(function(r){
    if(/^(save|delete|complete)/.test(name)) FCP.forget();   // saved copies are out of date now
    return r;
  });
}''')

p.swap('''function refresh(){
  return run('getBootstrap').then(function(b){
    S.boot = b;
    $('loading').style.display = 'none';
    renderStats(); renderBoard(); renderPickers(); renderDocTypes(); fillSettings();
    if(!document.querySelector('.view.on')) go(b.vehicles.length ? 'board' : 'vehicles');
    if(!b.vehicles.length) toast('Add your first vehicle to get started.');
  }).catch(function(e){
    $('loading').innerHTML = 'Could not load the sheet.<br><br>' + esc(e && e.message || e);
  });
}''', '''function refresh(){
  var shown = false;
  return FCP.cached('getBootstrap', [], function(b){
    S.boot = b;
    $('loading').style.display = 'none';
    renderStats(); renderBoard(); renderPickers(); renderDocTypes(); fillSettings();
    if(!document.querySelector('.view.on')) go(b.vehicles.length ? 'board' : 'vehicles');
    if(!shown && !b.vehicles.length) toast('Add your first vehicle to get started.');
    shown = true;
  }).catch(function(e){
    if(!shown) $('loading').innerHTML = 'Could not load the sheet.<br><br>' + esc(e && e.message || e);
  });
}''')

p.swap('''  box.innerHTML = '<div class="empty">Loading\\u2026</div>';
  run('getLogs', kind, '', 60).then(function(rows){
    box.innerHTML = rows.length
      ? rows.map(function(r){ return entryHtml(kind, r); }).join('')
      : '<div class="empty"><b>Nothing here yet</b>The first entry you save shows up in this list.</div>';
  }).catch(err);''', '''  box.innerHTML = '<div class="empty">Loading\\u2026</div>';
  var shown = false;
  FCP.cached('getLogs', [kind, '', 60], function(rows){
    shown = true;
    box.innerHTML = rows.length
      ? rows.map(function(r){ return entryHtml(kind, r); }).join('')
      : '<div class="empty"><b>Nothing here yet</b>The first entry you save shows up in this list.</div>';
  }, { slot: 'list-' + kind }).catch(function(e){ if(!shown && !e.stale) err(e); });''')

p.swap("buildNav();\nrefresh();", '''window.onAppBack = function(){
  if($('sheet') && $('sheet').classList.contains('on')){ closeSheet(); return true; }
  if(S.view && S.view !== 'board' && S.boot && S.boot.vehicles.length){ go('board'); return true; }
  return false;
};
buildNav();
refresh();''')
p.write('fleet')
