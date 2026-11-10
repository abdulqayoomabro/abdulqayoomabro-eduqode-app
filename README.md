# EduQode Android App

Android app for https://lms.eduqode.com (Tutor LMS).

## What it does
- Opens the EduQode website inside the app, with login kept between sessions
- Adds `EduQodeApp` to the browser identity so the website can hide prices and buy buttons
- Blocks cart and checkout pages inside the app (buying happens on the website only)
- Full-screen video, file upload for assignments, downloads, pull-to-refresh
- Shows already-visited pages from cache when offline, plus a "No internet" screen

## Getting the APK
Every push to `main` builds a debug APK on GitHub Actions.
Open the **Actions** tab, click the latest **Build APK** run, and download **EduQode-debug-apk** under Artifacts.

## Website side
Paste `wordpress/eduqode-app-hide-buy.php` into the Code Snippets plugin to hide buy elements inside the app.

## Before Play Store
- Replace the placeholder icon in `app/src/main/res/drawable/ic_launcher_foreground.xml` with the real logo
- Create a signing key and build a release `.aab`
