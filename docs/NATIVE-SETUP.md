# Fibro Coir app — native version: setup

The app on the `native` branch is a real Android app (Kotlin, Jetpack Compose). It no longer
opens the Apps Script web pages. It reads and writes the Google Sheets directly, keeps a copy
of what it last saw (so screens open instantly and work with weak signal), and refreshes in
the background.

| Screen | Sheet | Notes |
|---|---|---|
| Hub (sign-in, tiles) | "FCP App Hub" sheet (new, made by `ExportHub.gs`) | Same user names and passwords as before |
| EC Log | EC sheet `1W0EoXjd…u5ew` | Readings typed with no signal wait on the phone and go up by themselves |
| EC Viewer | EC sheet | Viewer limits are now kept in the EC sheet's `Limits` tab (D1:E4). Enter them once in the app |
| Water record | Water Load sheet `18fF22Kb…8-t0` | Read only, refreshes every 5 minutes |
| Raw material check | Husk company + vendor sheets | Decisions still go to `RM Matches` |
| Stores inventory | Stores sheet (paste its link in the app once) | Photos go through the upload script |
| Fleet manager | Fleet DB `1M0ai13f…EEF8` | Reminder emails are still sent by the Fleet script's daily trigger |

The maths for Water, Raw material check, Stores, Fleet and EC Viewer is checked on every
build against the original Apps Script code (`tools/golden/*.js` runs the scripts; the unit
tests compare).

## 1. The app's Google key (service account)

The app signs in to Google as its own account,
`fcp-app@fcp-app-fibrocoir.iam.gserviceaccount.com` (Cloud project **FCP App**,
`fcp-app-fibrocoir`, owned by admin.fibrocoirproducts@gmail.com, Sheets API on).

Its JSON key is kept only in the GitHub secret `FCP_SA_JSON` (repo Settings → Secrets and
variables → Actions). The build copies it into the APK. Anyone who unpacks the APK can find this
key and use it to read and change every sheet shared with it, so keep the APK inside the
company. To replace the key: Cloud console → IAM → Service accounts → FCP App → Keys → Add key
(JSON), paste the file into the secret, delete the old key and the downloaded file.

## 2. Share the sheets

Every sheet the app uses is shared with the app's account as **Editor**. The "FCP Uploads"
script does this in one go: run `shareWithApp` there. It also finds the Stores sheet by its tabs
and logs its ID. A new sheet the app should use has to be shared the same way.

## 3. The hub sheet (sign-ins and tiles)

1. Open the hub Apps Script project.
2. Add a script file named `ExportHub` and paste in `apps-script/hub/ExportHub.gs`.
3. Run `exportHubToSheet`. It makes the "FCP App Hub" sheet and shares it with the app.

The log shows the new sheet's ID. It goes into `fcp.properties` (`hubSheetId`).

Only salted password hashes are copied, so everyone keeps their password. After the switch,
manage users and tiles in the app (Hub → Manage). The old web hub keeps its own copy.

## 4. Photo and bill uploads

A service account has no Drive space, so files go through one small Apps Script, "FCP Uploads"
(`apps-script/uploads/Uploads.gs`), running as admin.fibrocoirproducts@gmail.com and deployed
as a web app (Execute as **Me**, access **Anyone**). Its /exec link is `uploadUrl` in
`fcp.properties`.

Every upload carries a fresh Google token of the app's own account. The script asks Google
whose token it is and refuses anything else, so there is no separate upload key.

Sheet IDs and the upload link aren't secret, so they live in `fcp.properties` at the top of
the repo.

## 5. Build and install

Any push to `native` builds a test APK, published as a GitHub pre-release named
`test-native-<n>`. To rebuild without a code change, open the repo's Actions tab → Build APK →
Run workflow.

Install the test APK, sign in, and open each tile. The **Screens** workflow also opens every
screen in an emulator with test data and saves screenshots to the `ci-shots` branch.

## 6. Go live

When the test APK works, merge `native` into `main`. The `main` build is released as
`v2.0.<n>`, and phones update to the native app.
