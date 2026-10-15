"""EC Viewer (Apps Script project "EC view") -> assets/ec-viewer/index.html
Usage: python3 tools/port_ec_viewer.py <path to the project's Index.html>"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('ec-viewer', 'FCP EC Viewer')

# The logo came from a template tag; the project has no Logo file, so nothing was shown.
p.swap('<? if (logo) { ?><img src="<?!= logo ?>" alt="Fibro Coir Products"><? } ?>', '')

# Summary: saved copy first, fresh one replaces it. Dim the screen only when there is nothing to show.
p.swap('''function loadSummary(){
  var my = ++sumSeq; busy(true);
  return call.apply(null, ['api_summary'].concat(base(), [summarySpot()])).then(function(s){
    busy(false); if (my !== sumSeq) return;
    S = s; drawHeader(); drawSelects(); drawTab();
  }).catch(function(e){ busy(false); say(e, true); });
}''', '''function loadSummary(){
  var my = ++sumSeq, shown = false; busy(true);
  return FCP.cached('api_summary', base().concat([summarySpot()]), function(s){
    if (my !== sumSeq) return;
    if (!shown){ shown = true; busy(false); }
    S = s; drawHeader(); drawSelects(); drawTab();
  }).then(function(){ if (!shown) busy(false); }, function(e){
    if (!shown) busy(false);
    if (!e.hadSaved && !e.stale) say(e.message, true);
  });
}''')

p.swap('''function loadRecords(){
  var my = ++recSeq; busy(true);
  return call.apply(null, ['api_records'].concat(base(), [recFilters()])).then(function(r){
    busy(false); if (my !== recSeq) return;
    REC = r; drawRecords();
  }).catch(function(e){ busy(false); say(e, true); });
}''', '''function loadRecords(){
  var my = ++recSeq, shown = false; busy(true);
  return FCP.cached('api_records', base().concat([recFilters()]), function(r){
    if (my !== recSeq) return;
    if (!shown){ shown = true; busy(false); }
    REC = r; drawRecords();
  }).then(function(){ if (!shown) busy(false); }, function(e){
    if (!shown) busy(false);
    if (!e.hadSaved && !e.stale) say(e.message, true);
  });
}''')

p.swap("call('api_spots').then(function(list){ SPOTS_KNOWN = list || []; if (!S) drawSelects(); }).catch(function(){});",
       "FCP.cached('api_spots', [], function(list){ SPOTS_KNOWN = list || []; if (!S) drawSelects(); }).catch(function(){});")

# After changing limits or re-reading the sheet, saved copies are out of date
p.swap("call('api_refresh').then(function(){ busy(false);", "call('api_refresh').then(function(){ FCP.forget(); busy(false);")

p.write('ec-viewer')
