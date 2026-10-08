# Fibro Coir — Android app

A full-screen Android app for the Fibro Coir Apps Script web app, with push notifications.

- Opens: `https://script.google.com/macros/s/AKfycbwds8w…/exec` (set in `MainActivity.kt` → `HOME_URL`)
- Package: `com.fibrocoir.app` · Firebase project: `fibro-coir`

## Download / install

Latest APK (log in to GitHub on the phone first, the repo is private):
**https://github.com/atsvkvignesh/fcp-android/releases/latest/download/FibroCoir.apk**

On the phone: open the link → download → open the file → allow "Install unknown apps" for the
browser when Android asks → Install. New versions install over the old one; logins stay.

## How updates work

| You change… | What to do |
|---|---|
| Anything in the Apps Script web app | Nothing. Redeploy the script as usual; the app shows it next time it opens. |
| App name, icon, notification code, permissions | Edit this repo and push to `main`. GitHub builds a new APK (~5 min) under Releases. |

The build also runs on demand: **Actions → Build APK → Run workflow**.

> **Keep `app/release.keystore` safe and the repo private.** Every update must be signed with
> this key, or phones refuse to install it as an update.

## What the app does

- Saved logins (cookies + local storage kept between launches)
- Photo / file upload, with a camera option
- File downloads (to the phone's Downloads folder)
- Location, if the page asks for it
- Phone, WhatsApp, email, UPI and outside links open in the right app
- No-internet screen with *Try again*
- **Back** goes to the previous page; on the home page it opens a menu:
  *Reload page · Go to home page · Notification groups · Exit app*

## Notifications

Every phone is always in the `all` group. Optional groups (Back on the home page →
*Notification groups*): `admin`, `tanker`, `stock`, `payments`. To add a group, add it to
`Topics.OPTIONAL` in `Topics.kt` and push.

The web page can also manage groups when it runs inside the app:

```js
if (window.FCPApp) {
  FCPApp.subscribe('tanker');      // true/false
  FCPApp.unsubscribe('tanker');
  JSON.parse(FCPApp.getTopics());  // ["all","tanker"]
  FCPApp.version();                // "1.0.5"
}
```

`navigator.userAgent` contains `FibroCoirApp` inside the app.

### Sending notifications from Apps Script

One central script, **FCP Notify** (`apps-script/fcp-notify/`), talks to Firebase.
All other scripts call it with the small `notify()` function in `apps-script/notify.gs`.

**Set up FCP Notify (once):**
1. Go to <https://script.new> (same Google account that owns the Firebase project), name it **FCP Notify**.
2. Paste `apps-script/fcp-notify/Code.gs` into `Code.gs`.
3. Project Settings (gear) → tick **Show "appsscript.json" manifest file**. Replace its content with
   `apps-script/fcp-notify/appsscript.json`.
4. Select `testPush` → **Run** → allow the permissions. Phones with the app get a test notification.
5. **Deploy → New deployment → Web app** → Execute as **Me**, Who has access **Anyone** → Deploy.
   Copy the Web app URL (ends in `/exec`).

**In any other script:** add a file `notify.gs` with `apps-script/notify.gs`, paste the FCP Notify
URL into `FCP_NOTIFY_URL`, then call e.g. `notify('admin', 'Payment received', 'FibreDust LLC paid $4,200')`.

**If `testPush` fails** with *"Firebase Cloud Messaging API has not been used in project…"* or a
`PERMISSION_DENIED` about the user project: in FCP Notify go to Project Settings →
*Google Cloud Platform (GCP) Project* → **Change project** → enter project number **763602141191**
(the Firebase project), then run `testPush` again.

## Build locally (optional)

Needs JDK 17 + Android SDK: `gradle :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
