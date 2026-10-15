/*
 * gas-shim.js — google.script.run for screens bundled in the app.
 *
 * Each tool's original Apps Script page runs (almost) unchanged inside the app. Its
 * google.script.run calls are sent to the script's Api.gs as JSON instead.
 *
 * Load order in a bundled page:
 *   <script>window.FCP_PAGE = { app: 'ec-viewer' };</script>
 *   <script src="../lib/config.js"></script>
 *   <script src="../lib/fcp.js"></script>
 *   <script src="../lib/gas-shim.js"></script>
 *
 * Where the data comes from: ?api=<the tool's /exec URL> (passed by the hub), else the last one
 * the hub passed for this tool, else FCP_CONFIG.apps[app].
 *
 * Cache-first reads: FCP.cached(fn, args, render) shows the saved answer straight away and calls
 * render again only if the fresh answer is different. Returns a promise for the fresh data.
 */
(function (w) {
  'use strict';

  var cfg = w.FCP_CONFIG || {};
  var page = w.FCP_PAGE || {};
  var app = page.app || 'app';
  var URL_KEY = 'fcp:url:' + app;

  function apiUrl() {
    if (app === 'hub') return cfg.hubUrl;   // the hub itself
    var fromHub = null;
    try { fromHub = new URLSearchParams(w.location.search).get('api'); } catch (e) {}
    if (fromHub) {
      try { w.localStorage.setItem(URL_KEY, fromHub); } catch (e) {}
      return fromHub;
    }
    var saved = null;
    try { saved = w.localStorage.getItem(URL_KEY); } catch (e) {}
    return page.url || saved || (cfg.apps || {})[app] || '';
  }

  var url = apiUrl();
  var api = url ? FCP.client({ url: url, key: cfg.key, app: app }) : null;

  function noAddress() {
    return Promise.reject(new Error('Open this screen from the hub.'));
  }

  /*
   * google.script.run hands null object values to the page as undefined. Some pages rely on
   * that, so do the same here.
   */
  function likeGas(v) {
    if (v === null) return undefined;
    if (Array.isArray(v)) { for (var i = 0; i < v.length; i++) if (v[i] && typeof v[i] === 'object') likeGas(v[i]); return v; }
    if (v && typeof v === 'object') {
      Object.keys(v).forEach(function (k) {
        if (v[k] === null) v[k] = undefined;
        else if (typeof v[k] === 'object') likeGas(v[k]);
      });
    }
    return v;
  }

  function call(fn, args) {
    return api ? api.call(fn, args).then(likeGas) : noAddress();
  }

  function runner(success, failure, userObject) {
    var base = {
      withSuccessHandler: function (f) { return runner(f, failure, userObject); },
      withFailureHandler: function (f) { return runner(success, f, userObject); },
      withUserObject: function (o) { return runner(success, failure, o); }
    };
    return new Proxy(base, {
      get: function (target, name) {
        if (name in target) return target[name];
        if (typeof name !== 'string' || name === 'then') return undefined;
        return function () {
          var args = Array.prototype.slice.call(arguments);
          call(name, args).then(function (data) {
            if (success) success(data, userObject);
          }, function (err) {
            if (failure) failure(err, userObject);
            else if (w.console) console.error(name + ': ' + err.message);
          });
        };
      }
    });
  }

  w.google = w.google || {};
  w.google.script = w.google.script || {};
  w.google.script.run = runner(null, null, undefined);
  w.google.script.host = {
    close: function () { FCP.close(); },
    setHeight: function () {}, setWidth: function () {},
    editor: { focus: function () {} }
  };
  w.google.script.url = {
    getLocation: function (cb) {
      var q = {}, qs = {};
      try {
        new URLSearchParams(w.location.search).forEach(function (v, k) {
          if (k === 'api') return;
          q[k] = v; (qs[k] = qs[k] || []).push(v);
        });
      } catch (e) {}
      cb({ hash: (w.location.hash || '').replace(/^#/, ''), parameter: q, parameters: qs });
    }
  };

  // ------------------------------------------------------------------ cache-first reads

  var lastShown = {};

  /**
   * Show the saved answer for fn(args) now (if any), then the fresh one if it differs.
   * render(data, info) — info.fresh is false for the saved copy.
   * opts.slot: a newer FCP.cached() in the same slot stops older ones updating the screen.
   */
  FCP.cached = function (fn, args, render, opts) {
    opts = opts || {};
    if (!api) return noAddress();
    var slot = opts.slot || fn;
    var showKey = slot + ':' + JSON.stringify(args || []);
    return api.load(fn, args || [], function (data, info) {
      var text;
      try { text = JSON.stringify(data); } catch (e) { text = String(Math.random()); }
      if (info.fresh && lastShown[showKey] === text) { FCP.note(null); return; }
      lastShown[showKey] = text;
      render(likeGas(data), info);
      if (info.fresh) FCP.note(null);
    }, { slot: slot }).then(likeGas, function (err) {
      if (err.hadSaved && !err.stale) {
        FCP.note((err.offline ? 'Offline' : 'Could not refresh') + ' · showing data from ' + FCP.ago(err.savedAt), true);
      }
      throw err;
    });
  };

  /** Keep `data` as the saved answer for fn(args), e.g. after a save returned the new state. */
  FCP.save = function (fn, args, data) {
    if (api) FCP._cache.put(api.cacheKey(fn, args || []), data);
  };

  /** Forget this screen's saved answers (all, or one function's). */
  FCP.forget = function (fn) { if (api) api.forget(fn); };

  FCP.apiUrl = url;
  FCP.app = app;

  // ------------------------------------------------------------------ small status note

  var noteEl = null;
  /** A small note at the bottom of the screen; FCP.note(null) hides it. */
  FCP.note = function (text, sticky) {
    if (!w.document || !w.document.body) return;
    if (!noteEl) {
      noteEl = w.document.createElement('div');
      noteEl.setAttribute('role', 'status');
      noteEl.style.cssText = 'position:fixed;left:50%;bottom:calc(14px + env(safe-area-inset-bottom));' +
        'transform:translateX(-50%);z-index:2147483000;max-width:88vw;padding:7px 14px;border-radius:999px;' +
        'background:rgba(20,20,20,.86);color:#fff;font:500 13px/1.3 system-ui,sans-serif;' +
        'box-shadow:0 4px 16px rgba(0,0,0,.25);pointer-events:none;transition:opacity .2s;opacity:0';
      w.document.body.appendChild(noteEl);
    }
    clearTimeout(FCP.note._t);
    if (!text) { noteEl.style.opacity = '0'; return; }
    noteEl.textContent = text;
    noteEl.style.opacity = '1';
    if (!sticky) FCP.note._t = setTimeout(function () { noteEl.style.opacity = '0'; }, 2600);
  };
})(window);
