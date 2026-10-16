/**
 * FCP App Hub → Google Sheet (run once)
 *
 * The native app keeps sign-ins and hub tiles in a Google Sheet instead of this script's
 * Script Properties. This copies them over without showing any password: only the salted
 * hashes move, so everyone keeps their current password.
 *
 * How to run:
 *   1. Open the "FCP App Hub" Apps Script project.
 *   2. File > New > Script file, name it ExportHub, paste this file.
 *   3. SERVICE_ACCOUNT below is already the app's account.
 *   4. Pick exportHubToSheet in the function list and press Run (allow access when asked).
 *   5. View > Logs shows the new sheet's ID. Send it over; it goes into the app.
 *
 * Running it again makes a fresh sheet; the old one is left alone.
 */

var SERVICE_ACCOUNT = 'fcp-app@fcp-app-fibrocoir.iam.gserviceaccount.com';   // the app's own Google account

function exportHubToSheet() {
  var raw = PropertiesService.getScriptProperties().getProperty('FCP_HUB_V1');
  if (!raw) throw new Error('No hub data found in this project (FCP_HUB_V1).');
  var data = JSON.parse(raw);

  var ss = SpreadsheetApp.create('FCP App Hub');
  var users = ss.getSheets()[0].setName('Users');
  var apps = ss.insertSheet('Apps');

  var userRows = [['id', 'login', 'name', 'role', 'salt', 'hash', 'apps (blank = all, - = none)']];
  (data.users || []).forEach(function (u) {
    var access = Array.isArray(u.apps) ? (u.apps.length ? u.apps.join(',') : '-') : '';
    userRows.push([u.id, u.login, u.name, u.role, u.salt, u.hash, access]);
  });

  var appRows = [['id', 'name', 'icon', 'url', 'screen (built-in screen, blank = open the link)']];
  (data.links || []).forEach(function (l) {
    appRows.push([l.id, l.name, l.icon || '', l.url, guessScreen_(l.name, l.url)]);
  });

  // plain text everywhere, so ids and hashes are never turned into numbers or dates
  users.getRange(1, 1, Math.max(userRows.length, 2), 7).setNumberFormat('@').offset(0, 0, userRows.length).setValues(userRows);
  apps.getRange(1, 1, Math.max(appRows.length, 2), 5).setNumberFormat('@').offset(0, 0, appRows.length).setValues(appRows);
  [users, apps].forEach(function (sh) {
    sh.setFrozenRows(1);
    sh.getRange(1, 1, 1, sh.getLastColumn()).setFontWeight('bold').setBackground('#F3E4C0');
    sh.autoResizeColumns(1, sh.getLastColumn());
  });
  users.hideColumns(5, 2);   // salt and hash: nobody needs to look at them

  if (SERVICE_ACCOUNT) {
    // share as Editor without sending an invitation email
    var r = UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/files/' + ss.getId() + '/permissions?sendNotificationEmail=false', {
      method: 'post', contentType: 'application/json', muteHttpExceptions: true,
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      payload: JSON.stringify({ role: 'writer', type: 'user', emailAddress: SERVICE_ACCOUNT })
    });
    if (r.getResponseCode() !== 200) DriveApp.getFileById(ss.getId()).addEditor(SERVICE_ACCOUNT);
  }
  Logger.log('Hub sheet ID: ' + ss.getId());
  Logger.log('Open it: ' + ss.getUrl());
  Logger.log('Users: ' + (userRows.length - 1) + ', apps: ' + (appRows.length - 1) +
             (SERVICE_ACCOUNT ? ', shared with ' + SERVICE_ACCOUNT : ', not shared yet (SERVICE_ACCOUNT is empty)'));
  return ss.getId();
}

/** Which built-in screen of the native app a tile most likely is. Fix any wrong ones in the app. */
function guessScreen_(name, url) {
  var n = String(name || '').toLowerCase();
  if (/water|tanker/.test(n)) return 'water-load';
  if (/\bec\b|conductiv/.test(n) && /view|record|report|chart|dashboard/.test(n)) return 'ec-viewer';
  if (/\bec\b|conductiv/.test(n)) return 'ec-log';
  if (/husk|raw material|\brm\b/.test(n)) return 'husk-check';
  if (/inventory|stores?\b|stock/.test(n)) return 'inventory';
  if (/fleet|vehicle|truck|lorry/.test(n)) return 'fleet';
  return '';
}
