/**
 * FCP Notify — the one script that sends push notifications to the Fibro Coir app.
 *
 * Set up once (see README in the repo):
 *   1. New Apps Script project named "FCP Notify", paste this file + appsscript.json
 *   2. Run testPush() once and allow the permissions → your phone gets a test notification
 *   3. Deploy → New deployment → Web app → Execute as: Me, Who has access: Anyone
 *   4. Copy the /exec URL into notify.gs in your other scripts
 *
 * Other scripts call it with notify(topic, title, body, url) — see notify.gs.
 */

const FCM_PROJECT_ID = 'fibro-coir';
const NOTIFY_KEY = 'I6b8kl8SkHJ6O2ShLNe9qkOXfbxTt94L'; // must match notify.gs in your other scripts
const TOPIC_RE = /^[a-zA-Z0-9\-_.~%]{1,900}$/;

/** Called by other scripts (POST JSON: {key, topic, title, body, url}). */
function doPost(e) {
  let out;
  try {
    const p = JSON.parse(e.postData.contents);
    if (p.key !== NOTIFY_KEY) throw new Error('wrong key');
    out = { ok: true, id: sendPush(p.topic, p.title, p.body, p.url) };
  } catch (err) {
    out = { ok: false, error: String(err && err.message || err) };
  }
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}

/** A plain browser visit just shows that the notifier is alive. */
function doGet() {
  return ContentService.createTextOutput('FCP Notify is running.');
}

/**
 * Sends one notification to every phone in a group.
 * @param {string} topic  group: 'all', 'admin', 'tanker', 'stock', 'payments' (or any you add)
 * @param {string} title  bold first line
 * @param {string} body   message text
 * @param {string=} url   optional: page to open when tapped (your /exec URL, e.g. ...exec?page=tanker)
 */
function sendPush(topic, title, body, url) {
  topic = String(topic || 'all').trim();
  if (!TOPIC_RE.test(topic)) throw new Error('bad topic: ' + topic);
  if (!body) throw new Error('body is empty');

  const message = {
    message: {
      topic: topic,
      notification: { title: String(title || 'Fibro Coir'), body: String(body) },
      data: url ? { url: String(url) } : {},
      android: {
        priority: 'HIGH',
        notification: { channel_id: 'alerts', sound: 'default' }
      }
    }
  };

  const res = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + FCM_PROJECT_ID + '/messages:send',
    {
      method: 'post',
      contentType: 'application/json',
      headers: {
        Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
        // bill the request to the Firebase project, not Apps Script's hidden one
        'x-goog-user-project': FCM_PROJECT_ID
      },
      payload: JSON.stringify(message),
      muteHttpExceptions: true
    }
  );

  const code = res.getResponseCode();
  if (code !== 200) throw new Error('FCM ' + code + ': ' + res.getContentText());
  return JSON.parse(res.getContentText()).name;
}

/** Run this from the editor to test: every phone with the app gets a notification. */
function testPush() {
  Logger.log(sendPush('all', 'Fibro Coir', 'Test notification — it works ✅'));
}

/** Test only the admin group. */
function testAdmin() {
  Logger.log(sendPush('admin', 'Admin test', 'Only phones in the Admin group get this'));
}
