/**
 * FCP Uploads — stores photos and bills from the Fibro Coir app in Google Drive.
 *
 * The app reads and writes the sheets itself, but a Google service account has no Drive
 * space of its own, so files go through this one small script, running as you.
 *
 * Only the app can use it: every upload carries a fresh Google sign-in token of the app's own
 * service account (APP_ACCOUNT below), and the script asks Google whose token it is.
 *
 * Setup (once):
 *   1. script.google.com > New project, name it "FCP Uploads", paste this file.
 *   2. Run  setup  once (allow access).
 *   3. Deploy > New deployment > Web app: Execute as Me, Who has access: Anyone.
 *   4. The /exec link goes into fcp.properties (uploadUrl) in the app's code.
 *
 * Use the Google account that owns the Fleet and Stores photo folders (or can edit them).
 */

var APP_ACCOUNT = 'fcp-app@fcp-app-fibrocoir.iam.gserviceaccount.com';
var APP_ACCOUNT_ID = '101774931668383813479';

/** The sheets the app works on. shareWithApp() gives the app's account edit access to them. */
var APP_SHEETS = {
  'EC log': '1W0EoXjd-rn7-X68jqzMJ2aZPQmud_Nm0N1UcgTvu5ew',
  'Water load': '18fF22Kbsp1l9tMJafUdlfYPm0b4mDc5zVrraOI18-t0',
  'Husk (company)': '1jKjsqbMxlpPFfQ5_z2N74RqzbOOA5cnSYEPo36o3zvk',
  'Husk (vendor) + RM Matches': '1kNlaqWPhxasycg_QPkkBkshdYJ_Cvcnc0zqiUH0jHnA',
  'Fleet DB': '1M0ai13frJbB2VRI2MSIQvwRsc18431VCHfWxxw-EEF8'
};

function setup() {
  DriveApp.getRootFolder().getName();   // asks for Drive access now, not on the first upload
  UrlFetchApp.fetch('https://oauth2.googleapis.com/tokeninfo?access_token=x', { muteHttpExceptions: true });
  Logger.log('Ready. Now deploy it as a web app (Execute as Me, access Anyone).');
}

/**
 * Shares every app sheet (plus the Stores sheet, found by its tabs) with the app's account as
 * Editor, without sending any email. Safe to run again.
 */
function shareWithApp() {
  var sheets = {};
  Object.keys(APP_SHEETS).forEach(function (k) { sheets[k] = APP_SHEETS[k]; });
  var stores = findStoresSheet();
  if (stores) sheets['Stores'] = stores;
  Object.keys(sheets).forEach(function (name) {
    var id = sheets[name];
    try {
      var r = UrlFetchApp.fetch('https://www.googleapis.com/drive/v3/files/' + id +
          '/permissions?sendNotificationEmail=false&supportsAllDrives=true', {
        method: 'post', contentType: 'application/json', muteHttpExceptions: true,
        headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
        payload: JSON.stringify({ role: 'writer', type: 'user', emailAddress: APP_ACCOUNT })
      });
      Logger.log((r.getResponseCode() === 200 ? 'Shared   ' : 'FAILED   ') + name + '  ' + id +
          (r.getResponseCode() === 200 ? '' : '  ' + r.getContentText().slice(0, 200)));
    } catch (err) {
      Logger.log('FAILED   ' + name + '  ' + id + '  ' + err.message);
    }
  });
}

/** The Stores inventory sheet: the spreadsheet with Items, People, Movements and Config tabs. */
function findStoresSheet() {
  var want = ['Items', 'People', 'Movements', 'Config'];
  var found = [], seen = {}, n = 0;
  var base = "mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false";
  [base + " and (title contains 'Store' or title contains 'Inventory' or title contains 'Stock')", base].forEach(function (q) {
    if (found.length) return;
    var it = DriveApp.searchFiles(q);
    while (it.hasNext() && n < 200) {
      var f = it.next();
      if (seen[f.getId()]) continue;
      seen[f.getId()] = true; n++;
      try {
        var names = SpreadsheetApp.openById(f.getId()).getSheets().map(function (s) { return s.getName(); });
        if (want.every(function (w) { return names.indexOf(w) >= 0; })) {
          found.push(f);
          Logger.log('Stores sheet: ' + f.getName() + '  ' + f.getId() + '  (changed ' + f.getLastUpdated() + ')');
        }
      } catch (err) {}
    }
  });
  if (!found.length) { Logger.log('No Stores sheet found (looked at ' + n + ' spreadsheets).'); return ''; }
  found.sort(function (a, b) { return b.getLastUpdated() - a.getLastUpdated(); });
  return found[0].getId();
}

/** True when `token` is a live Google token of the app's own account. Answers are cached a few minutes. */
function isApp_(token) {
  if (!token) return false;
  var cache = CacheService.getScriptCache();
  var key = 'tok_' + Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, token));
  if (cache.get(key) === '1') return true;
  var r = UrlFetchApp.fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(token), { muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) return false;
  var info = JSON.parse(r.getContentText());
  var ok = info.email === APP_ACCOUNT || info.azp === APP_ACCOUNT_ID || info.aud === APP_ACCOUNT_ID;
  if (ok) cache.put(key, '1', Math.max(10, Math.min(300, Number(info.expires_in || 0) - 30)));
  return ok;
}

/**
 * POST JSON: { accessToken, data (base64), mime, name, folderId?, subfolder?, newFolderName? }
 * No folderId: a new folder called newFolderName is made (its id comes back as createdFolderId,
 * for the app to keep). subfolder: a folder by that name inside it, made when missing.
 */
function doPost(e) {
  try {
    var p = JSON.parse(e.postData.contents);
    if (!isApp_(p.accessToken)) throw new Error('Not allowed.');
    if (!p.data) throw new Error('No file was sent.');

    var folder, created = '';
    if (p.folderId) {
      folder = DriveApp.getFolderById(p.folderId);
    } else {
      folder = DriveApp.createFolder(String(p.newFolderName || 'Fibro Coir app files'));
      try { folder.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW); } catch (err) {}
      created = folder.getId();
    }
    if (p.subfolder) {
      var it = folder.getFoldersByName(p.subfolder);
      folder = it.hasNext() ? it.next() : folder.createFolder(p.subfolder);
    }

    var name = String(p.name || 'file').replace(/[\\\/:*?"<>|]/g, '-');
    var blob = Utilities.newBlob(Utilities.base64Decode(p.data), p.mime || 'application/octet-stream', name);
    var f = folder.createFile(blob);
    try { f.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW); } catch (err) {}
    return out_({ ok: true, id: f.getId(), url: f.getUrl(), createdFolderId: created });
  } catch (err) {
    return out_({ ok: false, error: err.message });
  }
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
