"""EC Log (Apps Script project "EC Reading") -> assets/ec-log/index.html
Usage: python3 tools/port_ec_log.py <path to the project's Index.html>

The page already keeps readings on the phone when there is no signal and sends them later;
that is unchanged. Added: today's readings and the name chips appear at once from the saved copy.
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('ec-log', 'FCP EC Log')

p.swap('''function boot(){
  busy(true);
  return call('api_boot', OP).then(function(s){
    busy(false); ST = s; chips(); paint();
  }).catch(function(e){ busy(false); say(e, true); });
}''', '''function boot(){
  var shown = false; busy(true);
  return FCP.cached('api_boot', [OP], function(s){
    if (!shown){ shown = true; busy(false); }
    ST = s; chips(); paint();
  }, { slot: 'boot' }).then(function(){ if (!shown) busy(false); }, function(e){
    if (!shown) busy(false);
    if (!e.hadSaved && !e.stale) say(e.message, true);
  });
}''')

# Start: saved copy at once (for the saved operator, so today's readings show), fresh one after.
p.swap('''  var saved = get('ec_op');
  call('api_boot', OP).then(function(s){
    ST = s; chips();
    var html = '';
    (ST.operators || []).forEach(function(o){ html += '<button data-op="' + esc(o) + '">' + esc(o) + '</button>'; });
    $('opChips').innerHTML = html;
    if (saved){ OP = saved; show('logView'); paint(); flush().catch(function(){}); }
    else { show('opView'); }
  }).catch(function(e){
    say('Cannot reach the sheet: ' + e, true);
    if (saved){ OP = saved; show('logView'); paint(); } else { show('opView'); }
  });
})();''', '''  var saved = get('ec_op');
  var first = true;
  FCP.cached('api_boot', [saved || ''], function(s){
    ST = s; chips();
    var html = '';
    (ST.operators || []).forEach(function(o){ html += '<button data-op="' + esc(o) + '">' + esc(o) + '</button>'; });
    $('opChips').innerHTML = html;
    if (!first){ paint(); return; }          // fresh copy: just repaint, don't move the person around
    first = false;
    if (saved){ OP = saved; show('logView'); paint(); flush().catch(function(){}); }
    else { show('opView'); }
  }, { slot: 'boot' }).catch(function(e){
    if (!first) return;                       // the saved copy is on screen; the note says it is old
    first = false;
    say('Cannot reach the sheet: ' + e.message, true);
    if (saved){ OP = saved; show('logView'); paint(); } else { show('opView'); }
  });
})();''')
p.write('ec-log')
