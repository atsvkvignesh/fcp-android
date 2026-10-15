/**
 * FCP Uploads — stores photos and bills from the Fibro Coir app in Google Drive.
 *
 * The app reads and writes the sheets itself, but a Google service account has no Drive
 * space of its own, so files go through this one small script, running as you.
 *
 * Setup (once):
 *   1. script.google.com > New project, name it "FCP Uploads", paste this file.
 *   2. Run  setup  once (allow access). View > Logs shows the upload key.
 *   3. Deploy > New deployment > Web app: Execute as Me, Who has access: Anyone.
 *   4. Put the /exec link into the GitHub secret FCP_UPLOAD_URL and the key into
 *      FCP_UPLOAD_TOKEN. The next app build picks them up.
 *
 * Use the Google account that owns the Fleet and Stores photo folders (or can edit them).
 */

function setup() {
  var props = PropertiesService.getScriptProperties();
  var key = props.getProperty('UPLOAD_TOKEN');
  if (!key) {
    key = Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, '');
    props.setProperty('UPLOAD_TOKEN', key);
  }
  DriveApp.getRootFolder().getName();   // asks for Drive access now, not on the first upload
  Logger.log('Upload key: ' + key);
}

/**
 * POST JSON: { token, data (base64), mime, name, folderId?, subfolder?, newFolderName? }
 * No folderId: a new folder called newFolderName is made (its id comes back as createdFolderId,
 * for the app to keep). subfolder: a folder by that name inside it, made when missing.
 */
function doPost(e) {
  try {
    var p = JSON.parse(e.postData.contents);
    var want = PropertiesService.getScriptProperties().getProperty('UPLOAD_TOKEN');
    if (!want || p.token !== want) throw new Error('Not allowed.');
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
