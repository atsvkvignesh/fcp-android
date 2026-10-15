/*
 * fcp.js — shared helpers for pages bundled in the Fibro Coir app.
 *
 * The page's HTML/CSS/JS ships inside the APK, so it opens instantly. Data comes from the
 * Apps Script web app as JSON (see apps-script/json-api/Api.gs). The last answer for every
 * request is kept on the phone, so a screen shows its previous data straight away and then
 * refreshes in the background — the "native app" feel.
 *
 *   var api = FCP.client({ url: 'https://script.google.com/macros/s/.../exec', key: '...', app: 'ec' });
 *
 *   // Show saved data now, fresh data when it arrives. onData runs up to twice.
 *   api.load('summary', ['week', '', '', ''], function (data, info) {
 *     render(data);              // info.fresh: false = saved copy, true = just fetched
 *   }, { slot: 'summary' })      // a newer load in the same slot cancels this one's updates
 *   .catch(function (err) { ... });   // fetch failed (offline, script error)
 *
 *   api.call('export', [...])    // plain call, nothing saved (for writes)
 *   api.prefetch('summary', ['month', '', '', ''])   // warm the saved copy quietly
 */
(function (w) {
  'use strict';

  var PREFIX = 'fcp:';
  var INDEX = PREFIX + '__index';
  var MAX_ENTRIES = 80;

  // ------------------------------------------------------------------ saved copies (localStorage)

  function store() {
    try { return w.localStorage || null; } catch (e) { return null; }
  }

  function readIndex(s) {
    try { return JSON.parse(s.getItem(INDEX) || '{}') || {}; } catch (e) { return {}; }
  }

  function writeIndex(s, idx) {
    try { s.setItem(INDEX, JSON.stringify(idx)); } catch (e) { /* full: ignore */ }
  }

  /** Drop the oldest saved copies, keeping `keep` of them. */
  function prune(s, idx, keep) {
    var keys = Object.keys(idx).sort(function (a, b) { return idx[b] - idx[a]; });
    keys.slice(keep).forEach(function (k) {
      try { s.removeItem(PREFIX + k); } catch (e) {}
      delete idx[k];
    });
  }

  function cacheGet(key) {
    var s = store();
    if (!s) return null;
    try {
      var raw = s.getItem(PREFIX + key);
      return raw ? JSON.parse(raw) : null; // { at, data }
    } catch (e) { return null; }
  }

  function cachePut(key, data) {
    var s = store();
    if (!s) return;
    var now = Date.now();
    var entry;
    try { entry = JSON.stringify({ at: now, data: data }); } catch (e) { return; }
    var idx = readIndex(s);
    for (var attempt = 0; attempt < 3; attempt++) {
      try {
        s.setItem(PREFIX + key, entry);
        idx[key] = now;
        if (Object.keys(idx).length > MAX_ENTRIES) prune(s, idx, MAX_ENTRIES);
        writeIndex(s, idx);
        return;
      } catch (e) {
        // Storage full: forget the older half and try again
        prune(s, idx, Math.floor(Object.keys(idx).length / 2));
        writeIndex(s, idx);
      }
    }
  }

  function cacheForget(prefix) {
    var s = store();
    if (!s) return;
    var idx = readIndex(s);
    Object.keys(idx).forEach(function (k) {
      if (!prefix || k.indexOf(prefix) === 0) {
        try { s.removeItem(PREFIX + k); } catch (e) {}
        delete idx[k];
      }
    });
    writeIndex(s, idx);
  }

  // ------------------------------------------------------------------ client

  function Client(opts) {
    if (!opts || !opts.url) throw new Error('FCP.client needs a url');
    this.url = opts.url;
    this.key = opts.key || '';
    this.app = opts.app || 'app';
    this.timeout = opts.timeout || 45000; // Apps Script can take a while on a cold start
    this._inflight = {};
    this._slots = {};
  }

  Client.prototype.cacheKey = function (action, args) {
    return this.app + ':' + action + ':' + JSON.stringify(args || []);
  };

  /**
   * Inside the app, requests go through the app's own proxy (same origin as the page), so the
   * phone fetches from Apps Script natively and browser CORS rules never come into it.
   * In a normal browser (testing on a PC) the page fetches the script directly.
   */
  function requestUrl(full) {
    if (w.location && w.location.host === 'appassets.androidplatform.net') {
      return w.location.origin + '/proxy?url=' + encodeURIComponent(full);
    }
    return full;
  }

  function fail(msg, offline) {
    var e = new Error(msg);
    e.offline = !!offline;
    return e;
  }

  /** One request to Apps Script. Resolves with `data`, rejects with an Error (err.offline if no connection). */
  Client.prototype.call = function (action, args) {
    var self = this;
    var q = 'action=' + encodeURIComponent(action) +
      '&args=' + encodeURIComponent(JSON.stringify(args || [])) +
      (self.key ? '&key=' + encodeURIComponent(self.key) : '') +
      '&_=' + Date.now();
    var full = self.url + (self.url.indexOf('?') >= 0 ? '&' : '?') + q;

    var ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, self.timeout);

    return fetch(requestUrl(full), { method: 'GET', redirect: 'follow', cache: 'no-store', signal: ctrl ? ctrl.signal : undefined })
      .then(function (res) {
        return res.text().then(function (text) { return { status: res.status, ok: res.ok, text: text }; });
      }, function (err) {
        if (err && err.name === 'AbortError') throw fail('Timed out. Check the internet connection.', true);
        throw fail('No connection to the server.', true);
      })
      .then(function (r) {
        var j = null;
        try { j = JSON.parse(r.text); } catch (e) { /* not JSON */ }
        if (j && typeof j === 'object' && 'ok' in j) {
          if (j.ok === true) return j.data;
          throw fail(j.error || 'The script reported an error', j.offline);
        }
        if (/<html/i.test(r.text)) {
          // Apps Script sends a web page when the deployment or its access setting is wrong
          throw fail('The script sent a web page, not data. Check it is deployed with access "Anyone" and has the new version.');
        }
        throw fail(r.ok ? 'Bad answer from the script' : 'Server answered ' + r.status);
      })
      .then(function (data) { clearTimeout(timer); return data; },
            function (err) { clearTimeout(timer); throw err; });
  };

  /** Like call(), but shares one request between identical calls made at the same time. */
  Client.prototype.fetchShared = function (action, args) {
    var self = this;
    var key = self.cacheKey(action, args);
    if (!self._inflight[key]) {
      self._inflight[key] = self.call(action, args).then(function (data) {
        delete self._inflight[key];
        cachePut(key, data);
        return data;
      }, function (err) {
        delete self._inflight[key];
        throw err;
      });
    }
    return self._inflight[key];
  };

  /** The saved copy for this request, or null: { at: ms, data }. */
  Client.prototype.saved = function (action, args) {
    return cacheGet(this.cacheKey(action, args));
  };

  /**
   * Saved copy first (synchronously, if there is one), then fresh data.
   * opts.slot: loads sharing a slot cancel each other — only the newest one updates the screen.
   * opts.maxAge (ms): if the saved copy is younger than this, skip the network.
   */
  Client.prototype.load = function (action, args, onData, opts) {
    opts = opts || {};
    var self = this;
    var slot = opts.slot || null;
    var seq = 0;
    if (slot) seq = self._slots[slot] = (self._slots[slot] || 0) + 1;
    function current() { return !slot || self._slots[slot] === seq; }

    var saved = self.saved(action, args);
    if (saved) {
      try { onData(saved.data, { fresh: false, at: saved.at }); } catch (e) { setTimeout(function () { throw e; }); }
      if (opts.maxAge && Date.now() - saved.at < opts.maxAge) {
        return Promise.resolve(saved.data);
      }
    }

    return self.fetchShared(action, args).then(function (data) {
      if (current()) onData(data, { fresh: true, at: Date.now() });
      return data;
    }, function (err) {
      err.hadSaved = !!saved;
      err.savedAt = saved ? saved.at : 0;
      err.stale = !current();
      throw err;
    });
  };

  /** Fetch and save quietly, so the next load() of it is instant. Never throws. */
  Client.prototype.prefetch = function (action, args) {
    return this.fetchShared(action, args).catch(function () { return null; });
  };

  /** Forget saved copies for this app (all, or those of one action). */
  Client.prototype.forget = function (action) {
    cacheForget(this.app + ':' + (action ? action + ':' : ''));
  };

  // ------------------------------------------------------------------ small helpers

  /** "just now", "5 min ago", "2 h ago", "yesterday 18:40", "12 Oct 09:15" */
  function ago(ms) {
    if (!ms) return '';
    var d = Date.now() - ms;
    if (d < 45 * 1000) return 'just now';
    if (d < 60 * 60 * 1000) return Math.round(d / 60000) + ' min ago';
    var then = new Date(ms);
    var hm = ('0' + then.getHours()).slice(-2) + ':' + ('0' + then.getMinutes()).slice(-2);
    var today = new Date(); today.setHours(0, 0, 0, 0);
    if (then >= today) return Math.round(d / 3600000) + ' h ago';
    var yest = new Date(today.getTime() - 86400000);
    if (then >= yest) return 'yesterday ' + hm;
    var mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][then.getMonth()];
    return then.getDate() + ' ' + mon + ' ' + hm;
  }

  var inApp = !!w.FCPApp || /FibroCoirApp/.test(navigator.userAgent || '');

  /** Close this screen (inside the app) or go back (in a browser). */
  function close() {
    if (w.FCPApp && w.FCPApp.close) w.FCPApp.close();
    else if (w.history.length > 1) w.history.back();
  }

  w.FCP = {
    client: function (opts) { return new Client(opts); },
    ago: ago,
    inApp: inApp,
    close: close,
    online: function () { return navigator.onLine !== false; },
    _cache: { get: cacheGet, put: cachePut, forget: cacheForget } // for tests
  };
})(window);
