# Testing report — offline learning (app 2.0, plugin 2.0.0)

Every result below comes from tests that were actually run. They run again automatically on
every push (GitHub Actions → **Build APK**). Last full run: all jobs green.

## What was tested, and how

| Suite | Where it runs | Result |
|---|---|---|
| Plugin end-to-end tests (`tests/plugin`) | Real WordPress 6.8.3 + Tutor LMS 4.2.1 (official sources), SQLite | **45 / 45 passed** |
| App unit tests (`app/src/test`) | JVM, including a real local HTTP server for download/resume | **27 / 27 passed** |
| Emulator end-to-end test (`app/src/androidTest`) | Android 14 emulator ↔ real WordPress + Tutor LMS test site with a real 11 MB MP4 and a real PDF | **14 / 14 passed**, then Tutor LMS checked from the server side |

## The 20 tests from the brief

| # | Test | Status | Evidence |
|---|---|---|---|
| 1 | Download a complete course on Wi-Fi | ✅ Passed (emulator) | t03: all files done, 11.25 MB |
| 2 | Open the course in airplane mode | ✅ Passed (emulator) | t04–t07 with airplane mode on |
| 3 | Read lesson text offline | ✅ Passed (emulator) | t05 |
| 4 | Open PDFs offline | ✅ Passed (emulator) | t05 (PdfRenderer opens it), t03b (PDF screen opens) |
| 5 | Play a downloaded MP4 offline | ✅ Passed (emulator) | t06 |
| 6 | Seek and pause | ✅ Passed (emulator) | t06: seek to 10 s, play, pause at ~12 s |
| 7 | Complete a lesson offline | ✅ Passed (emulator) | t08 |
| 8 | Close and reopen the app | ⚠️ Partly | t09 reads the database file directly (as after a restart). A full process restart / reboot was not automated. |
| 9 | Offline progress stays saved | ✅ Passed (emulator) | t08–t09 |
| 10 | Reconnect to the internet | ✅ Passed (emulator) | t10 |
| 11 | Automatic sync with Tutor LMS | ✅ Passed (emulator) | t10: queue emptied with no "Sync now" press |
| 12 | Progress visible on the website | ✅ Passed | t11 via API, plus server-side check: `is_completed_lesson` = yes, course 25 % |
| 13 | Pause and resume a download | ✅ Passed (emulator + unit) | t03: paused at ~2 MB, file stayed the same size while paused, resumed, SHA-256 identical to server file |
| 14 | Interrupt and retry | ✅ Passed (unit, real HTTP) | dropped connection → retried → resumed from the partial file; busy server → retried; 404 → reported, not retried |
| 15 | Remove an offline course | ✅ Passed (emulator) | t13: folder deleted |
| 16 | Enrollment intact after removal | ✅ Passed | t13 + server-side `is_enrolled` = yes |
| 17 | Not enough storage | ⚠️ Logic only | Storage rule unit-tested; the "not enough storage" message was not triggered on a full device |
| 18 | YouTube lessons online only | ✅ Passed (emulator) | t07 shows "Online Only — Internet Required", t12 no YouTube file downloaded |
| 19 | Existing online playback | ⚠️ Not automated | The website still opens in the app (t03b main screen). YouTube playback inside the web view is unchanged code but was not played in the test |
| 20 | Login and navigation still work | ✅ Passed (emulator) | t01 website login → app token; t03b main screen with bottom bar |

## Also tested (plugin)

* Token: refused without login, refused for GET, fake/revoked tokens refused, re-login on the same phone replaces the old token.
* Only enrolled courses are listed; a non-enrolled course's pack is refused (403).
* Sync: duplicate events ignored; an older video position from a second phone does not overwrite a newer one; completion never undone; quizzes cannot be completed offline; lessons from other courses refused.
* A Tutor rule that refuses a completion (like Tutor LMS Pro rules) is reported, and the app then shows the lesson as not completed with a message.
* Tutor's own "redirect to next lesson" hook is paused during sync and restored after.
* All plugin answers are sent with `Cache-Control: no-store`.

## Bugs found by testing and fixed

1. Tutor LMS stops the request after a lesson is completed (it redirects the browser), so the app never got the sync answer. Fixed in the plugin.
2. All new offline screens crashed on start because the app used the splash-screen theme for every screen. Fixed (found by the emulator test).
3. Video links like `stream.php?f=x.mp4` were saved with a `.php` name. Fixed.

## Not tested — please check on your phone and site

* On **lms.eduqode.com** itself (plugin not installed there yet; SiteGround caching).
* On a **real phone** (only the Android 14 emulator).
* **Tutor LMS Pro** features: content drip, prerequisites, enrollment expiry, required video %. The plugin uses Tutor's own checks and hooks, but Pro's code was not available to test.
* Full app restart / phone reboot during a download; very large courses (several GB); downloads running longer than 10 minutes (Android pauses and restarts them; the code continues from the partial file).
* Notifications (shown only if allowed) were not checked visually.
* Google login (unchanged; still off).

## Known limitations

* YouTube and Vimeo videos stay online only. To make a video available offline, give the lesson a direct MP4 link you own (Settings → EduQode App).
* Quizzes stay online only, so correct answers never reach the phone.
* Downloaded files are in app-private storage (other apps cannot read them) but are not encrypted; a rooted phone could copy them.
* If access to a course ends, the offline copy is locked at the next sync; files stay until the student removes them.

## Recommendations

1. Install the plugin on a staging copy of the site first, then on lms.eduqode.com.
2. Use **Test mode** with one lesson to try the whole flow on your phone.
3. Move videos you want offline to Bunny Stream/Storage or your own server (MP4) and paste the links in Settings → EduQode App.
4. Before the Play Store release: add the real logo, create the upload key, and fill in the Data safety form (progress data goes to your own website only).
