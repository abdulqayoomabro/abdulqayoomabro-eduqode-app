package com.eduqode.app

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eduqode.app.offline.ApiClient
import com.eduqode.app.offline.CourseState
import com.eduqode.app.offline.FileKind
import com.eduqode.app.offline.FileState
import com.eduqode.app.offline.JsonParsers
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Session
import com.eduqode.app.offline.SyncState
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.ui.CourseDownloadActivity
import com.eduqode.app.ui.LessonActivity
import com.eduqode.app.ui.OfflineCourseActivity
import com.eduqode.app.ui.OfflineLibraryActivity
import com.eduqode.app.ui.PdfViewerActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * End-to-end test on a real Android emulator against a real WordPress + Tutor LMS test site
 * running on the build machine (see tests/e2e). Steps run in order and share state, like a student would:
 * log in, download a course, pause and resume, go offline, study, complete a lesson offline,
 * come back online, and check that the website received the progress.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OfflineE2ETest {

    companion object {
        private const val TAG = "EduQodeE2E"
        private var ids = JSONObject()
        private val course get() = ids.getLong("course")
        private val l1 get() = ids.getLong("l1")   // YouTube lesson with image + PDF
        private val l2 get() = ids.getLong("l2")   // lesson with an authorized MP4
        private val l3 get() = ids.getLong("l3")   // text lesson, completed offline
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = OfflineRepository(context)

    private fun step(msg: String) { Log.i(TAG, msg) }

    private fun waitUntil(what: String, timeoutMs: Long, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(500)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun network(on: Boolean) {
        if (on) {
            shell("cmd connectivity airplane-mode disable")
            shell("svc wifi enable"); shell("svc data enable")
        } else {
            shell("cmd connectivity airplane-mode enable")
            shell("svc wifi disable"); shell("svc data disable")
        }
        waitUntil(if (on) "network on" else "network off", 60_000) { repo.isOnline() == on }
        if (on) Thread.sleep(3000) // let the connection settle
    }

    private fun httpText(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            try { inputStream.bufferedReader().readText() } finally { disconnect() }
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Logs in like a browser and returns the cookie header. */
    private fun websiteLogin(user: String, pass: String): String {
        val conn = (URL("${ApiClient.SITE}/wp-login.php").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Cookie", "wordpress_test_cookie=WP%20Cookie%20check")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write("log=$user&pwd=$pass&wp-submit=Log+In&testcookie=1".toByteArray()) }
        conn.responseCode
        val cookies = conn.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
            .map { it.substringBefore(';') }.distinct()
        conn.disconnect()
        return cookies.joinToString("; ")
    }

    @Test fun t01_logInAndGetAppToken() {
        ids = JSONObject(httpText("${ApiClient.SITE}/e2e-ids.json"))
        val cookie = websiteLogin("e2estudent", "e2estudent")
        assertEquals("e2estudent", Session.cookieLogin(cookie))
        assertTrue("token issued", repo.api.obtainToken(cookie))
        assertTrue(repo.auth.isLoggedIn)
        assertEquals("E2E Student", repo.auth.userName)
        step("PASS login: website login linked to the app, token stored")
    }

    @Test fun t02_enrolledCourseAndPackage(): Unit = runBlocking {
        val enrolled = repo.refreshEnrolled()
        assertTrue("enrolled course listed", enrolled.any { it.id == course })
        val s = repo.preparePackage(course)
        assertEquals(3, s.lessons)
        assertEquals("one lesson has an offline video", 1, s.offlineVideos)
        assertEquals(2, s.videos)
        assertEquals(1, s.resources)
        assertEquals(1, s.quizzes)
        assertTrue("size estimated (${s.totalBytes})", s.totalBytes > 5_000_000)
        assertTrue(repo.lessonFile(course, l1).readText().contains("OFFLINE-TEXT-MARKER"))
        step("PASS package: 3 lessons, 2 videos (1 offline), 1 PDF, 1 quiz, ${s.totalBytes} bytes")
    }

    @Test fun t03_downloadPauseResume() {
        repo.wifiOnly = false
        repo.startDownload(course, null)
        val uid = repo.userId
        fun video() = repo.db.files(uid, course).first { it.kind == FileKind.VIDEO }
        waitUntil("video download started", 90_000) { video().doneBytes > 1_000_000 }

        repo.pause(course)
        assertEquals(CourseState.PAUSED, repo.db.course(uid, course)!!.state)
        Thread.sleep(2500)
        val part = File(video().localPath + ".part")
        assertTrue("partial file kept while paused", part.exists() && part.length() > 0)
        val pausedSize = part.length()
        Thread.sleep(2500)
        assertEquals("nothing downloads while paused", pausedSize, part.length())
        assertTrue("paused before the end", pausedSize < video().expectedBytes)
        step("PASS pause: stopped at $pausedSize of ${video().expectedBytes} bytes")

        repo.resume(course)
        waitUntil("download finished", 180_000) { repo.db.course(uid, course)!!.state == CourseState.DOWNLOADED }
        val files = repo.db.files(uid, course)
        assertTrue("all files done: $files", files.all { it.state == FileState.DONE })
        val local = File(video().localPath)
        assertEquals(video().expectedBytes, local.length())
        assertFalse(part.exists())
        val original = (URL(repo.db.files(uid, course).first { it.kind == FileKind.VIDEO }.url).openConnection() as HttpURLConnection)
            .run { try { inputStream.readBytes() } finally { disconnect() } }
        assertEquals("downloaded video is byte-for-byte identical", sha256(original), sha256(local.readBytes()))
        step("PASS resume: finished, ${local.length()} bytes, identical to the server file")
    }

    /** Every screen opens without crashing and shows the downloaded course. */
    @Test fun t03b_allScreensOpen() {
        fun <A : android.app.Activity> open(intent: Intent, check: (A) -> Unit) {
            ActivityScenario.launch<A>(intent).use { s ->
                Thread.sleep(3000)
                assertEquals(Lifecycle.State.RESUMED, s.state)
                s.onActivity { check(it) }
            }
        }
        open<OfflineLibraryActivity>(Intent(context, OfflineLibraryActivity::class.java)) {
            assertEquals("downloaded course listed", 1, it.findViewById<RecyclerView>(R.id.downloaded_list).adapter!!.itemCount)
        }
        open<CourseDownloadActivity>(Intent(context, CourseDownloadActivity::class.java).putExtra(CourseDownloadActivity.EXTRA_COURSE, course)) {
            assertEquals("Open offline course shown", View.VISIBLE, it.findViewById<View>(R.id.btn_open).visibility)
            assertEquals("4 items in the lesson list", 4, it.findViewById<RecyclerView>(R.id.lessons).adapter!!.itemCount)
        }
        open<OfflineCourseActivity>(Intent(context, OfflineCourseActivity::class.java).putExtra(OfflineCourseActivity.EXTRA_COURSE, course)) {
            assertEquals(4, it.findViewById<RecyclerView>(R.id.lessons).adapter!!.itemCount)
        }
        val pdf = repo.localFile(course, repo.db.lesson(repo.userId, l1)!!.attachments.single().url)!!
        open<PdfViewerActivity>(Intent(context, PdfViewerActivity::class.java)
            .putExtra(PdfViewerActivity.EXTRA_PATH, pdf.path).putExtra(PdfViewerActivity.EXTRA_TITLE, "Notes")) {
            assertEquals(1, it.findViewById<RecyclerView>(R.id.pages).adapter!!.itemCount)
        }
        open<MainActivity>(Intent(context, MainActivity::class.java)) {
            assertEquals(View.VISIBLE, it.findViewById<View>(R.id.bottom_nav).visibility)
        }
        step("PASS screens: My offline courses, download screen, course player, PDF viewer and main screen open")
    }

    @Test fun t04_goOffline() {
        network(false)
        assertFalse(repo.isOnline())
        step("PASS offline: airplane mode on")
    }

    @Test fun t05_offlineTextImagePdf() {
        val html = repo.lessonFile(course, l1).readText()
        assertTrue(html.contains("OFFLINE-TEXT-MARKER"))
        val img = Regex("""src="media/([^"]+)"""").find(html)
        assertNotNull("image points to a local copy", img)
        assertTrue("image file stored", File(repo.mediaDir(course), img!!.groupValues[1]).exists())

        val lesson = repo.db.lesson(repo.userId, l1)!!
        val pdf = repo.localFile(course, lesson.attachments.single().url)
        assertNotNull("PDF downloaded", pdf)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> assertEquals(1, r.pageCount) }
        }
        step("PASS offline content: lesson text, image and PDF open with no internet")
    }

    @Test fun t06_offlineVideoPlaysAndSeeks() {
        val lesson = repo.db.lesson(repo.userId, l2)!!
        val file = repo.localFile(course, lesson.videoOfflineUrl)!!
        val ins = InstrumentationRegistry.getInstrumentation()
        var player: ExoPlayer? = null
        ins.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare()
            }
        }
        fun <T> onMain(f: () -> T): T { var r: T? = null; ins.runOnMainSync { r = f() }; @Suppress("UNCHECKED_CAST") return r as T }
        waitUntil("player ready", 20_000) { onMain { player!!.playbackState } == Player.STATE_READY }
        val duration = onMain { player!!.duration }
        assertTrue("about 30 s long ($duration)", duration in 29_000..31_000)
        ins.runOnMainSync { player!!.seekTo(10_000); player!!.play() }
        Thread.sleep(2000)
        ins.runOnMainSync { player!!.pause() }
        val pos = onMain { player!!.currentPosition }
        assertTrue("seek + play works offline ($pos)", pos in 10_000..14_000)
        ins.runOnMainSync { player!!.release() }
        step("PASS offline video: plays, seeks to 10 s, pauses at $pos ms")
    }

    @Test fun t07_lessonScreensOffline() {
        val intent = Intent(context, LessonActivity::class.java)
            .putExtra(LessonActivity.EXTRA_COURSE, course).putExtra(LessonActivity.EXTRA_LESSON, l2)
        ActivityScenario.launch<LessonActivity>(intent).use { s ->
            Thread.sleep(1500)
            s.onActivity {
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.video_box).visibility)
                assertEquals(View.GONE, it.findViewById<View>(R.id.online_only).visibility)
            }
        }
        val yt = Intent(context, LessonActivity::class.java)
            .putExtra(LessonActivity.EXTRA_COURSE, course).putExtra(LessonActivity.EXTRA_LESSON, l1)
        ActivityScenario.launch<LessonActivity>(yt).use { s ->
            Thread.sleep(1500)
            s.onActivity {
                assertEquals("YouTube shows Online Only", View.VISIBLE, it.findViewById<View>(R.id.online_only).visibility)
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.attachments_box).visibility)
            }
        }
        step("PASS lesson screens: MP4 lesson shows player, YouTube lesson shows Online Only + PDF")
    }

    @Test fun t08_completeLessonOffline() {
        val uid = repo.userId
        repo.markLessonComplete(course, l3)
        repo.saveVideoPosition(course, l2, 10_000, 30_000, sendNow = true)
        repo.recordLessonOpened(course, l2)
        assertTrue(repo.db.lesson(uid, l3)!!.completedAt > 0)
        Thread.sleep(5000)
        val pending = repo.db.pendingEvents(uid)
        assertEquals("3 updates waiting while offline: $pending", 3, pending.size)
        assertEquals(SyncState.PENDING, repo.db.course(uid, course)!!.syncState)
        step("PASS offline completion: saved on the phone, 3 updates queued, nothing lost")
    }

    @Test fun t09_progressIsOnDisk() {
        // Read the database file directly, the way the app would after a restart.
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("offline.db").path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            it.rawQuery("SELECT COUNT(*) FROM events", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            it.rawQuery("SELECT completed_at FROM lessons WHERE id=?", arrayOf("$l3")).use { c -> c.moveToFirst(); assertTrue(c.getLong(0) > 0) }
        }
        step("PASS persistence: progress and queue are in the database file")
    }

    @Test fun t10_reconnectSyncsAutomatically() {
        network(true)
        val uid = repo.userId
        // No "Sync now" pressed: WorkManager runs the queued sync when the network is back.
        waitUntil("automatic sync", 180_000) { repo.db.pendingEvents(uid).isEmpty() }
        waitUntil("course marked synced", 30_000) { repo.db.course(uid, course)!!.syncState == SyncState.SYNCED }
        assertTrue(repo.db.course(uid, course)!!.lastSyncAt > 0)
        step("PASS automatic sync: queue emptied after reconnecting")
    }

    @Test fun t11_websiteHasTheProgress() {
        val body = JsonParsers.eventsBody(emptyList(), repo.auth.deviceId, listOf(course))
        val p = repo.api.sync(body).progress.single { it.courseId == course }
        assertTrue("lesson completed on the website", p.completed.containsKey(l3))
        assertEquals(10_000L, p.positions[l2]!!.positionMs)
        assertEquals(l2, p.lastLessonId)
        step("PASS website: Tutor LMS has the offline completion, video position and last lesson (${p.percent}%)")
    }

    @Test fun t12_youtubeLessonIsOnlineOnly() {
        val uid = repo.userId
        val lesson = repo.db.lesson(uid, l1)!!
        val status = repo.lessonAvailability(lesson, repo.db.files(uid, course))
        assertEquals("Text offline · video online only", status)
        assertTrue(repo.db.files(uid, course).none { it.lessonId == l1 && it.kind == FileKind.VIDEO })
        step("PASS YouTube: not downloaded, marked online only")
    }

    @Test fun t13_removeDownloadKeepsEnrollment(): Unit = runBlocking {
        repo.removeDownload(course)
        assertFalse("files deleted", repo.courseDir(course).exists())
        assertEquals(CourseState.NONE, repo.db.course(repo.userId, course)!!.state)
        assertTrue("still enrolled on the website", repo.refreshEnrolled().any { it.id == course })
        val p = repo.api.sync(JsonParsers.eventsBody(emptyList(), repo.auth.deviceId, listOf(course))).progress.single()
        assertTrue("progress kept on the website", p.completed.containsKey(l3))
        step("PASS remove: local files deleted, enrollment and progress kept")
    }
}
