# Fibro Coir — Android app

The Fibro Coir works app: the hub and its tools as built-in screens, with push notifications.

- Home: the built-in hub (`assets/hub`); its script is `https://script.google.com/macros/s/AKfycbwds8w…/exec`
  (`assets/lib/config.js` → `hubUrl`; `MainActivity.kt` → `HOME_URL` is the fallback)
- Package: `com.fibrocoir.app` · Firebase project: `fibro-coir`

## Download / install

Latest APK (log in to GitHub on the phone first, the repo is private):
**https://github.com/atsvkvignesh/fcp-android/releases/latest/download/FibroCoir.apk**

On the phone: open the link → download → open the file → allow "Install unknown apps" for the
browser when Android asks → Install. New versions install over the old one; logins stay.

## How updates work

| You change… | What to do |
|---|---|
| A script's data or logic | Nothing. Redeploy the script as usual. |
| A built-in screen's page | See "Built-in screens" below — needs a new APK. |
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
  FCPApp.hasLocal('ec-viewer');    // built-in screen in this APK?
  FCPApp.openLocal('ec-viewer?api=' + encodeURIComponent(execUrl));  // open it over the current screen
  FCPApp.openUrl(execUrl);         // open an Apps Script page as a screen
  FCPApp.close();                  // close the top screen
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

## Built-in screens (fast, app-like)

The hub and the tools ship **inside the APK**: they open instantly, show the last data they had
straight away and refresh it in the background. Apps Script only sends data (JSON).

| Screen | Apps Script project | Folder |
|---|---|---|
| Hub (home screen) | FCP App Hub | `assets/hub` |
| EC Log | EC Reading | `assets/ec-log` |
| EC Viewer | EC view | `assets/ec-viewer` |
| Raw material check | Husk Compare | `assets/husk-check` |
| Stores inventory | Inventory | `assets/inventory` |
| Fleet manager | Fleet Manager | `assets/fleet` |
| Water record | Water Load UI | `assets/water-load` |

```
app/src/main/assets/
  lib/config.js     app key + the hub's /exec link
  lib/fcp.js        data client: saved copy first, fresh data after
  lib/gas-shim.js   google.script.run for the bundled pages (their code runs unchanged)
  lib/app.css, lib/fonts.css + fonts/   app-screen behaviour, bundled fonts
  <screen>/index.html
apps-script/<screen>/Api.gs   the file to add to that Apps Script project
tools/port_<screen>.py        rebuilds a screen from the page exported from Apps Script
tools/make_api.py             rebuilds every Api.gs (allowed functions per tool)
tools/test/                   checks against the real scripts in a mock Apps Script runtime
```

**How a tool becomes a built-in screen:** add its `apps-script/<screen>/Api.gs` to the Apps Script
project (Files + > Script > name it `Api`, paste) and deploy a new version of the existing deployment.
Nothing else in the project changes and the web page keeps working in a browser. The hub asks each
tile's script whether it has `Api.gs`; when it does, that tile says "Opens in the app" and opens the
built-in screen. Tiles whose script has no `Api.gs` open the web page as a screen over the hub, and
Google Forms open outside the app, as before. If the hub's own script has no `Api.gs`, the app shows the
Apps Script hub page instead.

**Sign-in:** the hub's `Api.gs` adds `app_login` / `app_state` / `app_logout`. App sessions last 60 days
(renewed while used) instead of 6 hours. Removing a person ends their sessions.

**Data requests** go through the app (`FCPApp.request`, a native POST to Apps Script addresses only), so
photo and PDF uploads work and browser CORS rules don't apply.

| You change… | What to do |
|---|---|
| What a script returns, or its logic | Redeploy the script as usual — no new APK |
| A screen's look or behaviour | Change the page in Apps Script, export it, run `tools/port_<screen>.py`, push — needs a new APK |
| A screen calls a new server function | Add it to `tools/make_api.py`, run it, paste the new `Api.gs`, redeploy |

The bundled pages are copies: an edit made only in the Apps Script `Index.html` does not reach the app.

Test builds: push to any branch other than `main`. GitHub builds a **pre-release** (Releases page,
marked TEST) without changing the "latest" link workers install from.

## Build locally (optional)

Needs JDK 17 + Android SDK: `gradle :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
