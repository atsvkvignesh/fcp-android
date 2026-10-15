"""
Shared helpers for turning an Apps Script page (Index.html) into a screen bundled in the app.

Each tools/port_<tool>.py reads the page exported from Apps Script, applies the common changes
below plus its own small patches, and writes app/src/main/assets/<app>/index.html.
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')


class Page:
    def __init__(self, src_path):
        self.html = open(src_path, encoding='utf-8').read()

    def swap(self, old, new, count=1):
        """Replace text that must appear exactly `count` times (guards against silent misses)."""
        n = self.html.count(old)
        if n != count:
            sys.exit(f'patch failed: expected {count} match(es), found {n}:\n{old[:200]}')
        self.html = self.html.replace(old, new)

    def sub(self, pattern, repl, count=1, flags=0):
        new, n = re.subn(pattern, repl, self.html, flags=flags)
        if n != count:
            sys.exit(f'patch failed: expected {count} regex match(es), found {n}: {pattern}')
        self.html = new

    def common(self, app, title):
        """Local fonts, app-screen CSS, viewport + title (Apps Script used to add these),
        and the scripts that stand in for google.script.run."""
        h = self.html
        # Google Fonts -> bundled fonts
        h = re.sub(r'\s*<link[^>]+fonts\.(googleapis|gstatic)\.com[^>]*>', '', h)
        head_add = (
            '\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
            f'\n<title>{title}</title>'
            '\n<link rel="stylesheet" href="../lib/fonts.css">'
            '\n<link rel="stylesheet" href="../lib/app.css">'
            f'\n<script>window.FCP_PAGE = {{ app: {app!r} }};</script>'
            '\n<script src="../lib/config.js"></script>'
            '\n<script src="../lib/fcp.js"></script>'
            '\n<script src="../lib/gas-shim.js"></script>'
        )
        if '<meta charset="utf-8">' in h:
            h = h.replace('<meta charset="utf-8">', '<meta charset="utf-8">' + head_add, 1)
        else:
            h = h.replace('<head>', '<head>\n<meta charset="utf-8">' + head_add, 1)
        if re.search(r'fonts\.googleapis', h):
            sys.exit('a Google Fonts link is left')
        self.html = h

    def check(self):
        left = re.findall(r'<\?[^?]*\?>', self.html)
        if left:
            sys.exit('Apps Script template tags left: ' + ', '.join(x[:40] for x in left[:5]))

    def write(self, app):
        self.check()
        out_dir = os.path.join(ASSETS, app)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, 'index.html')
        open(out, 'w', encoding='utf-8').write(self.html)
        print(f'wrote {os.path.relpath(out, ROOT)} ({len(self.html.encode("utf-8")):,} bytes)')
