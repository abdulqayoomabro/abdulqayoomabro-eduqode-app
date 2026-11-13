# EduQode Android App

Android app for the EduQode Tutor LMS website (address set in `app/build.gradle.kts` → `SITE_URL`, now https://demo.lms.eduqode.com/demo/).

## What it does
- Opens the EduQode website inside the app, with login kept between sessions
- Adds `EduQodeApp` to the browser identity so the website can hide prices and buy buttons
- Blocks cart and checkout pages inside the app (buying happens on the website only)
- Full-screen video, file upload for assignments, downloads, pull-to-refresh
- Shows already-visited pages from cache when offline, plus a "No internet" screen

## Offline learning (version 2)
Download enrolled courses, study without internet, progress syncs to Tutor LMS when back online.
Also offline: quizzes (instant result), H5P exercises and H5P quizzes, assignments with files.
Plus course-news notifications, saved certificates, and a backup/restore add-on for moving to another site.
School features (classes/sections/batches, teacher and parent dashboards, attendance, report cards): [docs/SCHOOL.md](docs/SCHOOL.md).
Urdu (اردو) for the website, Tutor LMS screens, admin pages and the app: [docs/URDU.md](docs/URDU.md).
Instructors make H5P exercises from their Tutor LMS dashboard: [docs/H5P-INSTRUCTORS.md](docs/H5P-INSTRUCTORS.md).
Setup, how it works, and the REST API: [docs/OFFLINE.md](docs/OFFLINE.md). Test results: [docs/TESTING.md](docs/TESTING.md).
Needs the WordPress plugin `eduqode-app-connector` version 2 (zip on the Releases page).

## Getting the APK
Every push to `main` builds a debug APK on GitHub Actions.
On your phone, open the **Releases** page of this repo and download `EduQode-test.apk` from **EduQode test build**. (It is also attached under **Actions** > latest **Build APK** run > Artifacts.)

## Changing the app without a new APK
WordPress admin → **Settings → EduQode App → App settings**: Google login on/off, pages blocked in the app,
and the CSS that hides prices and buy buttons inside the app. Installed apps pick changes up on their next start.

## Google login
Google does not allow its login inside an app's web view. The plugin hands the login back from a browser tab.
Turn it on in Settings → EduQode App → App settings.

## Before Play Store
- Replace the placeholder icon in `app/src/main/res/drawable/ic_launcher_foreground.xml` with the real logo
- Create a private signing key and build a release `.aab` (the key in `app/debug.keystore` is for test APKs only)
