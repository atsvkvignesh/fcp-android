"""FCP Water Record (Apps Script project "Water Load UI") -> assets/water-load/index.html
Usage: python3 tools/port_water_load.py <path to the project's Index.html>

First load of a session shows the saved copy at once; the page's own auto-refresh then
works as before.
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('water-load', 'FCP Water Record')

p.swap('''  if (HAS_GAS) google.script.run.withSuccessHandler(onData).withFailureHandler(onErr).getData();
  else setTimeout(function () { onData(mockData()); }, 400);''', '''  if (HAS_GAS && !S.data) {
    // first load: saved copy at once, fresh one after
    FCP.cached('getData', [], function (d, info) {
      onData(d);
      if (!info.fresh) { S.loading = true; $('#refreshBtn').innerHTML = '<span class="spin">🔄</span>'; }
    }).then(function () {
      S.loading = false; $('#refreshBtn').textContent = '🔄';
      S.lastLoad = Date.now(); S.nextLoad = S.lastLoad + REFRESH_MS;
    }, function (e) {
      if (S.data) { S.loading = false; $('#refreshBtn').textContent = '🔄'; S.nextLoad = Date.now() + 60000; }
      else onErr(e);
    });
    return;
  }
  if (HAS_GAS) google.script.run.withSuccessHandler(onData).withFailureHandler(onErr).getData();
  else setTimeout(function () { onData(mockData()); }, 400);''')
p.write('water-load')
