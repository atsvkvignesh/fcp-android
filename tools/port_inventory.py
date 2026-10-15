"""Fibro Coir Stores Inventory (Apps Script project "Inventory", Sep 2026) -> assets/inventory/index.html
Usage: python3 tools/port_inventory.py <path to the project's Index.html>

- ?id=<item> opens that item, like the web page did (the web page got it through a template tag).
- Bootstrap, search results and item details show from the saved copy at once, then refresh.
- Photos are sent through the app (POST), so their size is no problem.
- Back steps through the page's own screens before leaving it.
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('inventory', 'Fibro Coir Stores')

p.swap("var START_ID = '<?= startId ?>';",
       "var START_ID = (function(){ try { return new URLSearchParams(location.search).get('id') || ''; } catch(e){ return ''; } })();")

p.swap('''google.script.run.withSuccessHandler(function(b){
  B=b; buildChips(); buildMachines(); buildKeys();
  if(START_ID) openItem(START_ID); else runSearch('');
}).withFailureHandler(fatal).getBootstrap();''', '''var BOOTED=false;
FCP.cached('getBootstrap',[],function(b){
  B=b; buildChips(); buildMachines(); buildKeys();
  if(BOOTED)return; BOOTED=true;
  if(START_ID) openItem(START_ID); else runSearch('');
}).catch(function(e){ if(!BOOTED) fatal(e); });''')

p.swap('''function runSearch(q){
  el('results').innerHTML='<div class="loading">Looking…</div>';
  google.script.run.withSuccessHandler(showResults).withFailureHandler(fatal).searchItems(q,REG,CAT);
}''', '''function runSearch(q){
  var shown=false;
  el('results').innerHTML='<div class="loading">Looking…</div>';
  FCP.cached('searchItems',[q,REG,CAT],function(res){ shown=true; showResults(res); },{slot:'search'})
    .catch(function(e){ if(!shown && !e.stale) fatal(e); });
}''')

p.swap('''  el('i-name').textContent='Loading…';el('i-tamil').textContent='';el('i-photo').removeAttribute('src');
  google.script.run.withSuccessHandler(function(it){''', '''  el('i-name').textContent='Loading…';el('i-tamil').textContent='';el('i-photo').removeAttribute('src');
  var shownItem=false;
  FCP.cached('getItem',[id],function(it){ shownItem=true;''')
p.swap('''    el('lbl-take').textContent=it.cat==='TOOL'?'borrow it — bring it back':'out of the store';
  }).withFailureHandler(fatal).getItem(id);
}''', '''    el('lbl-take').textContent=it.cat==='TOOL'?'borrow it — bring it back':'out of the store';
  },{slot:'item'}).catch(function(e){ if(!shownItem && !e.stale) fatal(e); });
}''')

# After a stock movement, item details and lists change: drop the saved copies
p.swap("google.script.run.withSuccessHandler(showDone).withFailureHandler(showFail).submitMovement({",
       "FCP.forget('getItem');FCP.forget('searchItems');\n  google.script.run.withSuccessHandler(showDone).withFailureHandler(showFail).submitMovement({")

p.swap("function go(n){", '''window.onAppBack=function(){
  var cur=document.querySelector('.screen.on');
  if(!cur||cur.id==='s-search')return false;
  var b=cur.querySelector('.bar .back');
  if(b){b.click();return true;}
  go('search');return true;
};
function go(n){''')
p.write('inventory')
