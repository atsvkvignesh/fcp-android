/**
 * Paste this file into ANY of your Apps Script projects to send app notifications.
 * Needs no extra permissions besides "connect to an external service".
 *
 * Examples:
 *   notify('admin',  'Payment received', 'FibreDust LLC paid $4,200');
 *   notify('tanker', 'Tanker OUT', 'TN-41 AB 1234 · load 3 today');
 *   notify('all',    'Factory notice', 'Power shutdown 2–4 PM');
 *   notify('stock',  'Low stock', 'Coco peat blocks below 500', 'https://script.google.com/macros/s/.../exec?page=stock');
 */

const FCP_NOTIFY_URL = 'PASTE_FCP_NOTIFY_WEB_APP_URL_HERE'; // the /exec URL of the FCP Notify script
const FCP_NOTIFY_KEY = 'I6b8kl8SkHJ6O2ShLNe9qkOXfbxTt94L';

function notify(topic, title, body, url) {
  try {
    const res = UrlFetchApp.fetch(FCP_NOTIFY_URL, {
      method: 'post',
      contentType: 'application/json',
      payload: JSON.stringify({ key: FCP_NOTIFY_KEY, topic: topic, title: title, body: body, url: url || '' }),
      muteHttpExceptions: true
    });
    const r = JSON.parse(res.getContentText());
    if (!r.ok) console.error('notify failed: ' + r.error);
    return r.ok;
  } catch (err) {
    // never let a notification problem break the main work
    console.error('notify error: ' + err);
    return false;
  }
}
