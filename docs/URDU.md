# Urdu (اردو)

Every user picks English or Urdu for themselves. The website, the app and its notifications then use that language.

## What is in Urdu

| Part | How |
|---|---|
| Tutor LMS student and teacher screens (dashboard, course page, lessons, quizzes, Q&A, reviews, profile, announcements) | `eduqode-app-connector/languages/tutor/tutor-ur.mo`, 925 texts of Tutor LMS (free). If an official Tutor LMS Urdu pack is installed, it is used first, and ours fills in what it lacks. |
| EduQode School (class, attendance, report cards, parent pages) | `eduqode-school/languages/eduqode-school-ur.mo` |
| EduQode admin pages (App settings, Backup) and the app's answers (reasons, errors, notifications) | `eduqode-app-connector/languages/eduqode-app-connector-ur.mo` |
| App screens (offline courses, lessons, quizzes, assignments, certificates, notifications) | `app/src/main/assets/i18n/ur.json` and `res/values-ur` |
| Lessons written in Urdu | Shown right to left with the Noto Nastaliq Urdu font, also offline (detected from the lesson text). |

Pages are right to left in Urdu. Course content (lesson text, quiz questions) is shown as the teacher wrote it.

## How a user switches

- **Website:** the "English · اردو" switch at the top of the Tutor LMS dashboard and the School page. A link with `?eduqode_lang=ur` (or `en`) does the same.
- **App:** Offline courses → ⋮ → *Language / زبان* (English, اردو, or the phone's language). The website in the app follows.
- **Admin:** Users → Profile → Language, once the Urdu language pack is installed (see below).

The choice is stored in the user's WordPress profile language, so it is the same on every device.

## One-time setup for the admin (recommended)

Settings → General → Site Language → **اردو** → Save (this installs WordPress's Urdu pack), then set Site Language back to **English** if the site should stay English for everyone else. After that, WordPress's own texts (admin menus, dates, "Log out") are also in Urdu for Urdu users, and "Language" appears in each user's profile.

Without this step everything listed above still works in Urdu; only WordPress's own words stay English.

## Changing a translation

Edit the dictionary and rebuild (CI fails if a text has no Urdu):

| Texts of | Dictionary | Build |
|---|---|---|
| Plugins | `tools/i18n/connector_ur.py`, `tools/i18n/school_ur.py` | `python3 tools/i18n/build_po.py` |
| Tutor LMS | `tools/i18n/tutor/b1.py` … `b4.py` (same order as `strings.json`) | `python3 tools/i18n/tutor/build.py wp-cli.phar` |
| App | `tools/i18n/app_ur.py` | `python3 tools/i18n/build_app.py` |

Then `wp i18n make-mo <languages folder>` for the plugin `.po` files. New app text goes through `tr("English text")` in Kotlin.

Tutor LMS Pro's own screens (its assignments, certificates and other add-on pages) are not in this file; they stay English unless a Tutor LMS Pro Urdu translation is installed (for example made with Loco Translate).
