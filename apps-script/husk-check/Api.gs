/**
 * Api.gs for "Husk Compare" — lets the Fibro Coir app's built-in screen get its data from this script.
 *
 * The app keeps the screen itself (HTML/CSS/JS) on the phone, so it opens instantly; this file
 * only answers its data requests. The normal /exec web page keeps working in a browser.
 *
 * Install (once per project):
 *   1. In the Apps Script editor: Files "+" > Script > name it  Api  > paste this file.
 *   2. Deploy > Manage deployments > pencil > Version: New version > Deploy.
 *      Access must stay "Anyone". The /exec link does not change.
 *   Nothing else in the project needs editing. The project must not already have a doPost.
 *
 * Each FCP project gets its own copy of this file (apps-script/<tool>/Api.gs in the app repo),
 * with FCP_API filled in: which screen it serves and which functions that screen may call.
 *
 * Request:  POST <exec URL>, body {"action":"api_summary","args":["week","","",""],"key":"..."}
 * Answer:   {"ok":true,"data":...}   or   {"ok":false,"error":"..."}
 * args are passed to the function in order, exactly like google.script.run would.
 */

var FCP_API = {
  app: 'husk-check',     // the bundled screen this script serves, e.g. 'ec-viewer'
  key: 'hxOi7cSYfGBZxDLzN6nmMNzoHGuZH5lm',     // same key as in the app (assets/lib/config.js)
  allow: [
    'api_load',
    'api_save',
    'api_undo',
    'api_export'
  ], // functions the screen may call
  before: null        // optional function(action, args) run before each call
};

var FCP_GLOBAL_ = this;

function doPost(e) {
  var started = Date.now();
  var out;
  try {
    var req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    if (req.key !== FCP_API.key) throw new Error('Not allowed. Update the app.');

    if (req.action === '__app') {
      // The hub asks this once to learn that this tool has a built-in screen in the app
      out = { ok: true, data: { app: FCP_API.app, api: 1 } };
    } else {
      if (FCP_API.allow.indexOf(req.action) < 0) throw new Error('Unknown request: ' + req.action);
      var g = (typeof globalThis !== 'undefined') ? globalThis : FCP_GLOBAL_;
      var fn = g[req.action];
      if (typeof fn !== 'function') throw new Error('Missing function: ' + req.action);
      var args = Array.isArray(req.args) ? req.args : [];
      if (typeof FCP_API.before === 'function') FCP_API.before(req.action, args);
      out = { ok: true, data: fn.apply(null, args) };
    }
  } catch (err) {
    out = { ok: false, error: String((err && err.message) || err) };
  }
  out.ms = Date.now() - started; // server time, handy when checking speed
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}
