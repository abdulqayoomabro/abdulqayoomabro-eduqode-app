# EduQode Android App

Android app for https://lms.eduqode.com (Tutor LMS).

## What it does
- Opens the EduQode website inside the app, with login kept between sessions
- Adds `EduQodeApp` to the browser identity so the website can hide prices and buy buttons
- Blocks cart and checkout pages inside the app (buying happens on the website only)
- Full-screen video, file upload for assignments, downloads, pull-to-refresh
- Shows already-visited pages from cache when offline, plus a "No internet" screen

## Offline learning (version 2)
Download enrolled courses, study without internet, progress syncs to Tutor LMS when back online.
Setup, how it works, and the REST API: [docs/OFFLINE.md](docs/OFFLINE.md). Test results: [docs/TESTING.md](docs/TESTING.md).
Needs the WordPress plugin `eduqode-app-connector` version 2 (zip on the Releases page).

## Getting the APK
Every push to `main` builds a debug APK on GitHub Actions.
On your phone, open the **Releases** page of this repo and download `EduQode-test.apk` from **EduQode test build**. (It is also attached under **Actions** > latest **Build APK** run > Artifacts.)

## Changing the app from GitHub (no new APK needed)
Edit these files on GitHub; every installed app picks the change up on its next start:
- `remote/config.json`: pages blocked in the app, and the Google login switch
- `remote/inject.css`: styling added to EduQode pages inside the app (for example hiding prices and buy buttons)

## Google login
Google does not allow its login inside an app's web view. To turn it on:
1. Upload `eduqode-app-connector.zip` in WordPress (Plugins > Add New > Upload Plugin) and activate it.
2. In `remote/config.json` change `"googleLogin": "off"` to `"googleLogin": "handoff"`.

Until then the app shows a short message asking users to log in with email and password.

## Before Play Store
- Replace the placeholder icon in `app/src/main/res/drawable/ic_launcher_foreground.xml` with the real logo
- Create a private signing key and build a release `.aab` (the key in `app/debug.keystore` is for test APKs only)
