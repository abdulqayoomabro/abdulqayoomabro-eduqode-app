# EduQode offline learning

Students download an enrolled course once, study it with no internet, and their progress
syncs to Tutor LMS automatically when they are back online.

## Which website the app uses

Set once in `app/build.gradle.kts` → `SITE_URL` (now `https://demo.lms.eduqode.com/demo`).
WordPress can be in a sub-folder like `/demo`; the app handles that. Install the plugin on that site.

## What is where

| Part | Location |
|---|---|
| WordPress plugin (REST API, sync, settings) | `wordpress/eduqode-app-connector/` |
| Android offline engine (database, downloads, sync) | `app/src/main/java/com/eduqode/app/offline/` |
| Android offline screens | `app/src/main/java/com/eduqode/app/ui/` |
| Plugin end-to-end tests (real WordPress + Tutor LMS) | `tests/plugin/` |
| App unit tests | `app/src/test/` |

## 1. Install the WordPress plugin

1. Download `eduqode-app-connector.zip` from the repository's **Releases → EduQode test build**.
2. WordPress admin → **Plugins → Add New → Upload Plugin** → choose the zip → **Install** → **Activate**.
   If version 1.0 is installed, WordPress offers to replace it; choose **Replace current with uploaded**.
3. Open **Settings → EduQode App**.

Works on the existing SiteGround hosting. No VPS, no extra server, no changes to WordPress or Tutor LMS files.

### SiteGround cache

The plugin marks all its answers `Cache-Control: no-store`. If SiteGround Optimizer's dynamic cache
still caches something, add these to **SG Optimizer → Caching → Exclude URLs**:
`/wp-json/eduqode/*` and `/?eduqode_app=*`.

## 2. Offline videos

YouTube videos are never downloaded (no stream extraction, no scraping). Lessons with YouTube
videos show **Online Only — Internet Required** for the video; their text, images and PDFs are
still downloaded.

A lesson video becomes downloadable when one of these is true:

1. **Settings → EduQode App → Offline video links**: choose the course and paste a direct MP4
   link that you own (your server, Bunny Storage, S3 + CDN, …) next to the lesson.
2. The lesson's Tutor video source is **HTML5 (mp4)** uploaded to WordPress.
3. The lesson's Tutor video source is **External URL** pointing directly to an `.mp4`/`.webm` file.

### Test mode

In **Settings → EduQode App**, put an MP4 you are allowed to use in **Test video URL**, and one or
more lesson IDs (shown in the Offline video links table) in **Use test video on lesson IDs**.
Those lessons then download that video, which is how to test download, pause/resume, offline
playback, seeking, completion and sync before moving real videos off YouTube.

### Adding a provider later (Bunny Stream, Vimeo with download rights, …)

* WordPress: return `{ "provider": "<name>", "url": "...", "size_bytes": 0, "mime": "video/mp4" }`
  from the `eduqode_app_offline_video` filter (arguments: `$offline, $lesson_id, $user_id`).
* App: add a class implementing `OfflineVideoProvider` in `VideoProviders.kt` and register it.

## 3. How it works

**Login.** The student logs in on the website inside the app as before. The app then asks the
plugin for its own token (POST `/?eduqode_app=issue_token` with the web view's login cookies).
The token is stored encrypted with an Android Keystore key. Logging out on the website logs the
app out; offline courses are then locked until the same student logs in again.

**Download.** `GET /wp-json/eduqode/v1/courses/{id}/package` returns topics, lessons, lesson
text, images, attachments and video sources, but only to enrolled students (or admins/the course
instructor). The app stores structure in SQLite and files in app-private storage
(`files/offline/u<user>/c<course>/`, removed when the app is uninstalled, excluded from backups).
Files download one at a time with WorkManager: they survive app restarts and reboots, resume with
HTTP Range requests, are checked for size, and only missing files are fetched again. A storage
check runs before each file. "Download over Wi-Fi only" is in My offline courses.

**Studying offline.** Lessons open from local files. Videos play with Media3 (seek, pause, resume
position). PDFs open in an in-app viewer. Quizzes stay online so answers never reach the phone.

**Sync.** Completing a lesson, the video position and the last opened lesson are saved locally and
queued with a unique ID. `POST /wp-json/eduqode/v1/sync` applies them through Tutor LMS's own
functions and hooks. Rules: completion is never undone; for video position and "last lesson" the
newest (by time on the phone) wins; repeated events are ignored ("duplicate"). The response
returns the server's progress, which the app merges the same way, so progress from other phones
and from the website appears in the app too. Sync runs when the connection returns, every 6 hours,
and with **Sync now**.

**Access ending.** If a student is no longer enrolled, the course is marked "Access ended" at the
next sync and cannot be opened; files stay until the student removes them, and it unlocks again if
access is renewed. Removing a download never touches enrollment or progress on the website.

## 4. REST API

All routes need the `X-EduQode-Token` header.

| Method | Route | Purpose |
|---|---|---|
| GET | `/eduqode/v1/me` | logged-in student |
| GET | `/eduqode/v1/courses` | enrolled courses |
| GET | `/eduqode/v1/courses/{id}/package` | offline pack |
| GET | `/eduqode/v1/courses/{id}/progress` | server progress |
| POST | `/eduqode/v1/sync` | `{events:[…], course_ids:[…]}` |
| POST | `/eduqode/v1/auth/revoke` | log this token out |

## 5. Play Store notes

* New permission: `POST_NOTIFICATIONS` (asked only when the first download starts).
  WorkManager adds its usual background-work permissions. No storage permission is used.
* Data safety form: the app sends learning progress (lesson completion, video position) to your
  own website; nothing goes to third parties.
* Release bundle (`EduQode-release.aab`) is signed only when the GitHub secrets
  `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`
  are set. Keep the upload key private and backed up.
