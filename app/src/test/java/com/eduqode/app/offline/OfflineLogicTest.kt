package com.eduqode.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the offline logic. The JSON fixtures are real responses recorded from the
 * EduQode App Connector plugin running on WordPress + Tutor LMS 4.2.1 (see tests/plugin).
 */
class OfflineLogicTest {

    private fun fixture(name: String) =
        JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())

    // ---- package parsing ----

    @Test fun parsesCoursePackageInOrder() {
        val pkg = JsonParsers.coursePackage(fixture("package_fixture.json"))
        assertEquals("Python Basics", pkg.course.title)
        assertEquals(listOf("Intro (YouTube)", "Install (own mp4)", "Quiz 1", "Variables (YouTube + offline link)"), pkg.lessons.map { it.title })
        assertEquals(listOf(0, 0, 0, 1), pkg.lessons.map { it.topicOrder })
    }

    @Test fun youtubeIsOnlineOnlyUnlessAnOfflineFileIsGiven() {
        val pkg = JsonParsers.coursePackage(fixture("package_fixture.json"))
        val intro = pkg.lessons.first { it.title.startsWith("Intro") }
        assertEquals("youtube", intro.videoOnlineProvider)
        assertTrue(VideoProviders.isOnlineOnly(intro.videoOnlineProvider))
        // Lesson 1 gets the test-mode MP4 configured in the plugin settings.
        assertEquals("https://test.example.com/test.mp4", VideoProviders.downloadUrl(intro.videoOffline))
        val own = pkg.lessons.first { it.title.startsWith("Install") }
        assertEquals(2048L, own.videoOffline!!.sizeBytes)
    }

    @Test fun quizIsOnlineOnly() {
        val quiz = JsonParsers.coursePackage(fixture("package_fixture.json")).lessons.first { it.type == "quiz" }
        assertTrue(quiz.onlineOnly)
        assertNull(quiz.videoOffline)
    }

    @Test fun attachmentsAndImagesAreListed() {
        val intro = JsonParsers.coursePackage(fixture("package_fixture.json")).lessons.first()
        assertEquals(listOf("application/pdf"), intro.attachments.map { it.mime })
        assertEquals(1, intro.images.size)
    }

    @Test fun unknownProviderIsNotDownloaded() {
        assertNull(VideoProviders.downloadUrl(OfflineVideoSource("youtube", "https://youtu.be/x", 0, "")))
        assertNull(VideoProviders.downloadUrl(OfflineVideoSource("direct", "ftp://x/y.mp4", 0, "")))
        assertNull(VideoProviders.downloadUrl(null))
    }

    @Test fun parsesEnrolledCourses() {
        val list = JsonParsers.enrolledCourses(fixture("courses_fixture.json"))
        assertEquals(1, list.size)
        assertEquals(3, list[0].lessonCount)
    }

    // ---- sync ----

    @Test fun parsesSyncResponse() {
        val r = JsonParsers.syncResponse(fixture("sync_fixture.json"))
        assertEquals("applied" to "", r.results["fx1"])
        assertEquals("rejected" to "no_access", r.results["fx2"])
        assertEquals(listOf(17L), r.noAccess)
        val p = r.progress.single()
        assertEquals(10L, p.courseId)
        assertEquals(90000L, p.positions[14L]!!.positionMs)
        assertTrue(p.completed.containsKey(13L))
    }

    @Test fun eventsBodyHasPositionsOnlyForVideoEvents() {
        val body = JsonParsers.eventsBody(
            listOf(
                PendingEvent("a", 1, 2, EventType.LESSON_COMPLETE, 10, 0, 0),
                PendingEvent("b", 1, 2, EventType.VIDEO_POSITION, 11, 500, 1000)
            ), "dev", listOf(1L)
        )
        val events = body.getJSONArray("events")
        assertFalse(events.getJSONObject(0).has("position_ms"))
        assertEquals(500L, events.getJSONObject(1).getLong("position_ms"))
        assertEquals(1L, body.getJSONArray("course_ids").getLong(0))
    }

    @Test fun mergeNeverUndoesCompletion() {
        val local = listOf(OfflineLogic.LocalLessonState(1, completedAt = 500, positionMs = 0, durationMs = 0, positionAt = 0, hasPendingPosition = false))
        val server = ServerProgress(9, 0, completed = emptyMap(), positions = emptyMap(), lastLessonId = 0, lastLessonAt = 0)
        assertTrue(OfflineLogic.mergeProgress(local, server).isEmpty())
    }

    @Test fun mergeTakesCompletionFromOtherDevice() {
        val local = listOf(OfflineLogic.LocalLessonState(1, 0, 0, 0, 0, false))
        val server = ServerProgress(9, 50, mapOf(1L to 777L), emptyMap(), 0, 0)
        assertEquals(777L, OfflineLogic.mergeProgress(local, server).single().completedAt)
    }

    @Test fun mergeKeepsNewerLocalPosition() {
        val local = listOf(OfflineLogic.LocalLessonState(1, 0, 60_000, 100_000, positionAt = 2000, hasPendingPosition = false))
        val older = ServerProgress(9, 0, emptyMap(), mapOf(1L to ServerPosition(10_000, 100_000, 1000)), 0, 0)
        assertTrue(OfflineLogic.mergeProgress(local, older).isEmpty())
        val newer = ServerProgress(9, 0, emptyMap(), mapOf(1L to ServerPosition(80_000, 100_000, 3000)), 0, 0)
        assertEquals(80_000L, OfflineLogic.mergeProgress(local, newer).single().position!!.positionMs)
    }

    @Test fun mergeKeepsUnsentLocalPosition() {
        val local = listOf(OfflineLogic.LocalLessonState(1, 0, 60_000, 100_000, positionAt = 1, hasPendingPosition = true))
        val server = ServerProgress(9, 0, emptyMap(), mapOf(1L to ServerPosition(80_000, 100_000, 3000)), 0, 0)
        assertTrue(OfflineLogic.mergeProgress(local, server).isEmpty())
    }

    @Test fun lastLessonNewestWins() {
        assertEquals(5L to 20L, OfflineLogic.newerLastLesson(4, 10, 5, 20))
        assertEquals(4L to 30L, OfflineLogic.newerLastLesson(4, 30, 5, 20))
    }

    @Test fun finalStatuses() {
        listOf("applied", "already", "duplicate", "stale", "rejected").forEach { assertTrue(it, OfflineLogic.isFinal(it)) }
        assertFalse(OfflineLogic.isFinal(""))
    }

    // ---- files ----

    @Test fun fileNamesAreStableAndDistinct() {
        val a = OfflineLogic.fileNameFor("https://x.com/a/video.mp4?token=1")
        assertEquals(a, OfflineLogic.fileNameFor("https://x.com/a/video.mp4?token=1"))
        assertTrue(a.endsWith(".mp4"))
        assertNotEquals(a, OfflineLogic.fileNameFor("https://x.com/b/video.mp4"))
        assertTrue(OfflineLogic.fileNameFor("https://x.com/download", "bin").endsWith(".bin"))
        assertTrue(OfflineLogic.fileNameFor("https://x.com/stream.php?f=a.mp4", "mp4").endsWith(".mp4"))
    }

    @Test fun imagesPointToLocalCopies() {
        val html = """<p><img src="https://s.com/a.png?x=1&amp;y=2"></p><img src="https://s.com/b.png">"""
        val out = OfflineLogic.rewriteImages(html, mapOf("https://s.com/a.png?x=1&y=2" to "1.png", "https://s.com/b.png" to "2.png"))
        assertEquals("""<p><img src="media/1.png"></p><img src="media/2.png">""", out)
    }

    @Test fun googleDriveLinksBecomeDirectDownloads() {
        val direct = "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOp&export=download&confirm=t"
        assertEquals(direct, OfflineLogic.directDownloadUrl("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?usp=sharing"))
        assertEquals(direct, OfflineLogic.directDownloadUrl("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp"))
        assertEquals(direct, OfflineLogic.directDownloadUrl("https://docs.google.com/uc?export=download&id=1AbCdEfGhIjKlMnOp"))
        assertEquals("https://cdn.x.com/a.mp4", OfflineLogic.directDownloadUrl("https://cdn.x.com/a.mp4"))
        assertNull(OfflineLogic.googleDriveId("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp"))
    }

    @Test fun storageCheck() {
        assertTrue(OfflineLogic.hasRoom(100L * 1024 * 1024, 200L * 1024 * 1024))
        assertFalse(OfflineLogic.hasRoom(180L * 1024 * 1024, 200L * 1024 * 1024))
    }

    @Test fun contentRange() {
        assertEquals(100L, OfflineLogic.contentRangeStart("bytes 100-199/1000"))
        assertEquals(-1L, OfflineLogic.contentRangeStart(null))
    }

    @Test fun pageWrapperBlocksScripts() {
        val page = OfflineLogic.wrapHtml("A <b>", "<p>x</p>")
        assertTrue(page.contains("script-src 'none'"))
        assertTrue(page.contains("A &lt;b&gt;"))
    }
}
