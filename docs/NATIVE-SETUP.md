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

In a terminal, signed in to Google Cloud with the account that should own it:

```
gcloud projects create fcp-app-2026
gcloud services enable sheets.googleapis.com --project fcp-app-2026
gcloud iam service-accounts create fcp-app --project fcp-app-2026
gcloud iam service-accounts keys create key.json --iam-account fcp-app@fcp-app-2026.iam.gserviceaccount.com
gh secret set FCP_SA_JSON -R atsvkvignesh/fcp-android < key.json
```

Delete `key.json` from the computer afterwards. Anyone who unpacks the APK can find this key
and use it to read and change every sheet shared with it, so keep the APK inside the company.

## 2. Share the sheets

Share each sheet with `fcp-app@fcp-app-2026.iam.gserviceaccount.com` as **Editor**. The
account that owns a sheet (several are owned by admin.fibrocoirproducts@gmail.com) has to do
the sharing.

- EC sheet, Water Load sheet, both Husk sheets, Fleet DB
- Stores inventory sheet
- FCP App Hub sheet (step 3 can share it for you)

## 3. The hub sheet (sign-ins and tiles)

1. Open the "FCP App Hub" Apps Script project.
2. Add a script file named `ExportHub` and paste in `apps-script/hub/ExportHub.gs`.
3. Put the service account address in `SERVICE_ACCOUNT`.
4. Run `exportHubToSheet`.

The log shows the new sheet's link. The first time the app opens, it asks for that link. To
build the link into the app instead, run
`gh variable set FCP_HUB_SHEET_ID -R atsvkvignesh/fcp-android -b <sheet id>`.

Only salted password hashes are copied, so everyone keeps their password. After the switch,
manage users and tiles in the app (Hub → Manage). The old web hub keeps its own copy.

## 4. Photo and bill uploads

A service account has no Drive space, so files go through one small Apps Script running as
you:

1. Create a new Apps Script project named "FCP Uploads" and paste in `apps-script/uploads/Uploads.gs`.
2. Run `setup`. The log shows the upload key.
3. Deploy it as a web app: Execute as **Me**, access **Anyone**.
4. Save the address and key as secrets:

```
gh secret set FCP_UPLOAD_URL -R atsvkvignesh/fcp-android -b "https://script.google.com/macros/s/…/exec"
gh secret set FCP_UPLOAD_TOKEN -R atsvkvignesh/fcp-android -b "<upload key>"
```

Use the Google account that owns the Stores and Fleet photo folders.

## 5. Build and install

Any push to `native` builds a test APK, published as a GitHub pre-release named
`test-native-<n>`. To rebuild after setting secrets:

```
gh workflow run "Build APK" -R atsvkvignesh/fcp-android --ref native
```

Install the test APK, sign in, and open each tile. The **Screens** workflow also opens every
screen in an emulator with test data and saves screenshots to the `ci-shots` branch.

## 6. Go live

When the test APK works, merge `native` into `main`. The `main` build is released as
`v2.0.<n>`, and phones update to the native app.
