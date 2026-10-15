/**
 * Api.gs — lets a page bundled in the Fibro Coir app get its data from this script as JSON.
 *
 * The app keeps the page itself (HTML/CSS/JS) on the phone, so it opens instantly; this file
 * only answers data requests. The normal web page keeps working in a browser as before.
 *
 * Add to an existing web app project:
 *   1. File > New > Script, name it  Api , paste this file.
 *   2. Make the first line of doGet(e):
 *        if (isApiCall_(e)) return apiResponse_(e, API_ROUTES);
 *      and make sure doGet takes the  e  argument:  function doGet(e) { ... }
 *   3. Below, list which functions the app may call (API_ROUTES).
 *   4. Project Settings (gear) > Script properties > Add:  APP_KEY = <same key as in the app>
 *   5. Deploy > Manage deployments > pencil > Version: New version > Deploy.
 *      Access must stay "Anyone" (not "Anyone with Google account").
 *
 * Request:  GET <exec URL>?action=summary&args=["week","","",""]&key=...
 * Answer:   {"ok":true,"data":...}   or   {"ok":false,"error":"..."}
 * The args array is passed to the function as its arguments, in order, exactly like
 * google.script.run.withSuccessHandler(...).api_summary("week", "", "", "").
 */

function isApiCall_(e) {
  return !!(e && e.parameter && e.parameter.action);
}

function apiResponse_(e, routes) {
  var started = Date.now();
  var out;
  try {
    var p = e.parameter;
    var want = PropertiesService.getScriptProperties().getProperty('APP_KEY');
    if (want && p.key !== want) throw new Error('Not allowed. Update the app.');

    var fn = Object.prototype.hasOwnProperty.call(routes, p.action) ? routes[p.action] : null;
    if (typeof fn !== 'function') throw new Error('Unknown request: ' + p.action);

    var args = p.args ? JSON.parse(p.args) : [];
    if (!Array.isArray(args)) args = [args];

    out = { ok: true, data: fn.apply(null, args) };
  } catch (err) {
    out = { ok: false, error: String((err && err.message) || err) };
  }
  out.ms = Date.now() - started; // server time, handy when checking speed
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}
