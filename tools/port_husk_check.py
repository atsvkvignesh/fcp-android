"""Husk Compare / FCP Raw Material Check (Apps Script project "Husk Compare") -> assets/husk-check/index.html
Usage: python3 tools/port_husk_check.py <path to the project's Index.html>

Saved copy of the loads first, fresh after. Saving is safe on an old copy: the script refuses
rows that were already matched ("already saved ... Tap Refresh").
"""
import sys
from port_common import Page

p = Page(sys.argv[1])
p.common('husk-check', 'FCP Raw Material Check')

p.swap('''async function load() {
  busy(true);
  try { apply(await run('api_load', filt())); }
  catch (e) { toast(msg(e), null, true); }
  finally { busy(false); }
}''', '''async function load() {
  let shown = false;
  busy(true);
  try {
    await FCP.cached('api_load', [filt()], d => { if (!shown) { shown = true; busy(false); } apply(d); });
  } catch (e) {
    if (!e.hadSaved && !e.stale) toast(msg(e), null, true);
  } finally {
    if (!shown) busy(false);
  }
}''')
p.write('husk-check')
