package com.eduqode.app.offline

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Everything the offline screens need: download, remove, progress and stats. */
class OfflineRepository(context: Context) {

    private val app = context.applicationContext
    val db = OfflineDb.get(app)
    val auth = AuthStore(app)
    val api = ApiClient(app)
    private val prefs = app.getSharedPreferences("eduqode_offline", Context.MODE_PRIVATE)

    val userId: Long get() = auth.userId

    /** "Download over Wi-Fi only" setting. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()

    // ---------- files on disk (app-private storage, removed when the app is uninstalled) ----------

    fun rootDir(): File = File(app.filesDir, "offline")
    fun userDir(userId: Long = this.userId) = File(rootDir(), "u$userId")
    fun courseDir(courseId: Long, userId: Long = this.userId) = File(userDir(userId), "c$courseId")
    fun mediaDir(courseId: Long, userId: Long = this.userId) = File(courseDir(courseId, userId), "media")
    fun lessonFile(courseId: Long, lessonId: Long) = File(courseDir(courseId), "lesson_$lessonId.html")
    fun courseInfoFile(courseId: Long) = File(courseDir(courseId), "course.html")

    fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun freeBytes(): Long = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrDefault(0)

    fun usedBytes(): Long = dirSize(userDir())

    private fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun courseBytesOnDisk(courseId: Long): Long = dirSize(courseDir(courseId))

    // ---------- enrolled courses ----------

    suspend fun refreshEnrolled(): List<EnrolledCourse> = withContext(Dispatchers.IO) {
        val list = api.courses()
        db.saveEnrolled(userId, list)
        list
    }

    // ---------- preparing a download ----------

    data class Summary(
        val lessons: Int,
        val videos: Int,
        val offlineVideos: Int,
        val resources: Int,
        val quizzes: Int,
        val offlineQuizzes: Int,
        val totalBytes: Long,
        val doneBytes: Long,
        val unknownSizeFiles: Int,
        val freeBytes: Long
    )

    /**
     * Downloads the course pack (structure + lesson text), stores it, and lists the files to fetch.
     * Files already on the phone are kept, so an update only downloads what is missing.
     */
    suspend fun preparePackage(courseId: Long): Summary = withContext(Dispatchers.IO) {
        val uid = userId
        val pkg = api.coursePackage(courseId)
        val dir = courseDir(courseId).apply { mkdirs() }
        val media = mediaDir(courseId).apply { mkdirs() }

        val wanted = HashSet<String>()
        fun addFile(lessonId: Long, kind: String, url: String, name: String, mime: String, size: Long, fallbackExt: String) {
            if (url.isBlank()) return
            val local = File(media, OfflineLogic.fileNameFor(url, fallbackExt))
            wanted += url
            db.ensureFile(uid, courseId, lessonId, kind, url, name, mime, local.path, size)
        }

        // Course description + thumbnail
        addFile(0, FileKind.THUMBNAIL, pkg.course.thumbnailUrl, "Course image", "image/*", 0, "jpg")
        File(dir, "course.html").writeText(OfflineLogic.wrapHtml(pkg.course.title, pkg.course.descriptionHtml))

        val htmlPresent = HashSet<Long>()
        pkg.lessons.forEach { l ->
            if (l.type == "lesson") {
                // Images are small and may be shared by lessons, so they belong to the course (lesson 0).
                val names = l.images.associateWith { OfflineLogic.fileNameFor(it, "img") }
                l.images.forEach { addFile(0, FileKind.IMAGE, it, "Image", "image/*", 0, "img") }
                val html = OfflineLogic.rewriteImages(l.contentHtml, names)
                lessonFile(courseId, l.id).writeText(OfflineLogic.wrapHtml(l.title, html))
                htmlPresent += l.id
                l.attachments.forEach { a -> addFile(l.id, FileKind.ATTACHMENT, a.url, a.name, a.mime, a.sizeBytes, "bin") }
                VideoProviders.downloadUrl(l.videoOffline)?.let { url ->
                    addFile(l.id, FileKind.VIDEO, url, l.title, l.videoOffline?.mime ?: "video/mp4", l.videoOffline?.sizeBytes ?: 0, "mp4")
                }
            } else if (l.quizJson.isNotEmpty()) {
                // Pictures used in offline quiz questions.
                l.images.forEach { addFile(0, FileKind.IMAGE, it, "Quiz image", "image/*", 0, "img") }
            }
        }
        db.savePackage(uid, pkg, htmlPresent)

        // Lessons removed from the course on the website: delete their old files.
        db.deleteFilesNotIn(uid, courseId, wanted).forEach { File(it.localPath).delete(); File(it.localPath + ".part").delete() }
        dir.listFiles { f -> f.name.startsWith("lesson_") }?.forEach { f ->
            val id = f.name.removePrefix("lesson_").removeSuffix(".html").toLongOrNull()
            if (id != null && id !in htmlPresent) f.delete()
        }

        fillUnknownSizes(courseId)
        summary(courseId)
    }

    /** Asks the server for the size of videos and attachments whose size is not known yet. */
    private suspend fun fillUnknownSizes(courseId: Long) = coroutineScope {
        val gate = Semaphore(4)
        db.files(userId, courseId)
            .filter { it.expectedBytes == 0L && it.state != FileState.DONE && (it.kind == FileKind.VIDEO || it.kind == FileKind.ATTACHMENT) }
            .take(60)
            .map { f ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val size = headSize(OfflineLogic.directDownloadUrl(f.url))
                        if (size > 0) db.updateFile(f.id, ContentValues().apply { put("expected_bytes", size) })
                    }
                }
            }.awaitAll()
    }

    private fun headSize(url: String): Long = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"; connectTimeout = 6000; readTimeout = 6000
            setRequestProperty("User-Agent", ApiClient.USER_AGENT)
        }
        try {
            if (c.responseCode in 200..299) c.contentLengthLong else -1
        } finally {
            c.disconnect()
        }
    }.getOrDefault(-1)

    /** Counts and sizes for the confirmation screen (only lessons chosen for download). */
    fun summary(courseId: Long): Summary {
        val lessons = db.lessons(userId, courseId)
        val selected = lessons.filter { it.selected }.map { it.id }.toSet()
        val files = db.files(userId, courseId).filter { it.lessonId == 0L || it.lessonId in selected }
        return Summary(
            lessons = lessons.count { it.isLesson },
            videos = lessons.count { it.isLesson && it.hasVideo },
            offlineVideos = lessons.count { it.isLesson && it.videoOfflineCapable },
            resources = lessons.sumOf { it.attachments.size },
            quizzes = lessons.count { it.type == "quiz" },
            offlineQuizzes = lessons.count { it.isOfflineQuiz },
            totalBytes = files.sumOf { maxOf(it.expectedBytes, it.doneBytes) },
            doneBytes = files.filter { it.state == FileState.DONE }.sumOf { maxOf(it.expectedBytes, it.doneBytes) } +
                files.filter { it.state != FileState.DONE }.sumOf { it.doneBytes },
            unknownSizeFiles = files.count { it.expectedBytes == 0L && it.state != FileState.DONE && it.kind != FileKind.IMAGE && it.kind != FileKind.THUMBNAIL },
            freeBytes = freeBytes()
        )
    }

    // ---------- download control ----------

    /** Marks which lessons to download (null = all), then starts or continues the download. */
    fun startDownload(courseId: Long, selectedLessonIds: Set<Long>?) {
        db.setSelected(userId, courseId, selectedLessonIds)
        applySelection(courseId)
        db.resetFailedFiles(userId, courseId)
        db.setCourseState(userId, courseId, CourseState.QUEUED)
        DownloadWorker.enqueue(app, userId, courseId, wifiOnly)
    }

    /** Files of lessons that are not selected are skipped (and deleted if they were downloaded before). */
    private fun applySelection(courseId: Long) {
        val selected = db.lessons(userId, courseId).filter { it.selected }.map { it.id }.toSet()
        db.files(userId, courseId).forEach { f ->
            val wanted = f.lessonId == 0L || f.lessonId in selected
            if (!wanted && f.state != FileState.SKIPPED) {
                File(f.localPath).delete(); File(f.localPath + ".part").delete()
                db.updateFile(f.id, ContentValues().apply { put("state", FileState.SKIPPED); put("done_bytes", 0) })
            } else if (wanted && f.state == FileState.SKIPPED) {
                db.setFileState(f.id, FileState.PENDING)
            } else if (wanted && f.state == FileState.DONE && !File(f.localPath).exists()) {
                // File was deleted from the phone by something else: download it again.
                db.updateFile(f.id, ContentValues().apply { put("state", FileState.PENDING); put("done_bytes", 0) })
            }
        }
    }

    /**
     * Brings a downloaded course up to date with the website: new or changed lessons, quizzes and
     * files. Only what is new is downloaded. Runs at most every 15 minutes per course unless [force].
     * Returns true when the course was checked.
     */
    suspend fun updateDownloadedCourse(courseId: Long, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val c = db.course(userId, courseId) ?: return@withContext false
        if (c.state !in setOf(CourseState.DOWNLOADED, CourseState.FAILED) || c.accessExpired || !isOnline()) return@withContext false
        val key = "checked_${userId}_$courseId"
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(key, 0) < 15 * 60_000) return@withContext false
        prefs.edit().putLong(key, now).apply()
        try {
            preparePackage(courseId)
        } catch (e: ApiClient.ApiException) {
            if (e.code == 403) db.setAccessExpired(userId, courseId, true)
            return@withContext false
        }
        applySelection(courseId)
        val waiting = db.files(userId, courseId).any { it.state == FileState.PENDING }
        if (waiting) {
            db.setCourseState(userId, courseId, CourseState.QUEUED)
            DownloadWorker.enqueue(app, userId, courseId, wifiOnly)
        }
        true
    }

    /** Checks every downloaded course for updates (see [updateDownloadedCourse]). */
    suspend fun updateAllDownloaded(force: Boolean = false) {
        db.courses(userId).filter { it.state == CourseState.DOWNLOADED || it.state == CourseState.FAILED }
            .forEach { runCatching { updateDownloadedCourse(it.id, force) } }
    }

    fun pause(courseId: Long) {
        db.setCourseState(userId, courseId, CourseState.PAUSED)
        WorkManager.getInstance(app).cancelUniqueWork(DownloadWorker.workName(userId, courseId))
        db.course(userId, courseId)?.let { Notifier.downloadPaused(app, courseId, it.title) }
    }

    fun resume(courseId: Long) {
        db.resetFailedFiles(userId, courseId)
        db.setCourseState(userId, courseId, CourseState.QUEUED)
        DownloadWorker.enqueue(app, userId, courseId, wifiOnly)
    }

    fun retryFailed(courseId: Long) = resume(courseId)

    /** Stops any download and deletes the downloaded copy. Enrollment and website progress are not touched. */
    suspend fun removeDownload(courseId: Long) = withContext(Dispatchers.IO) {
        val uid = userId
        val wm = WorkManager.getInstance(app)
        val name = DownloadWorker.workName(uid, courseId)
        wm.cancelUniqueWork(name)
        // Give a running download a moment to stop writing before its folder is deleted.
        for (i in 0 until 20) {
            val running = runCatching { wm.getWorkInfosForUniqueWork(name).get() }.getOrDefault(emptyList())
                .any { it.state == WorkInfo.State.RUNNING }
            if (!running) break
            delay(150)
        }
        courseDir(courseId, uid).deleteRecursively()
        db.removeDownload(uid, courseId)
        Notifier.cancel(app, courseId)
    }

    // ---------- learning progress (works offline) ----------

    private fun newEvent(courseId: Long, lessonId: Long, type: String, positionMs: Long = 0, durationMs: Long = 0) =
        PendingEvent(UUID.randomUUID().toString(), courseId, lessonId, type, System.currentTimeMillis(), positionMs, durationMs)

    fun markLessonComplete(courseId: Long, lessonId: Long) {
        val now = System.currentTimeMillis()
        db.markCompleted(userId, lessonId, now)
        db.addEvent(userId, newEvent(courseId, lessonId, EventType.LESSON_COMPLETE))
        db.setSyncState(userId, courseId, SyncState.PENDING)
        SyncWorker.enqueueNow(app)
    }

    fun saveVideoPosition(courseId: Long, lessonId: Long, positionMs: Long, durationMs: Long, sendNow: Boolean) {
        val now = System.currentTimeMillis()
        db.savePosition(userId, lessonId, positionMs, durationMs, now)
        db.upsertCoalescedEvent(userId, newEvent(courseId, lessonId, EventType.VIDEO_POSITION, positionMs, durationMs), sameCourseOnly = false)
        db.setSyncState(userId, courseId, SyncState.PENDING)
        if (sendNow) SyncWorker.enqueueNow(app)
    }

    fun recordLessonOpened(courseId: Long, lessonId: Long) {
        val now = System.currentTimeMillis()
        db.setLastLesson(userId, courseId, lessonId, now)
        db.upsertCoalescedEvent(userId, newEvent(courseId, lessonId, EventType.LESSON_ACCESS), sameCourseOnly = true)
        db.setSyncState(userId, courseId, SyncState.PENDING)
        SyncWorker.enqueueNow(app)
    }

    fun syncNow() = SyncWorker.enqueueNow(app)

    // ---------- offline quizzes ----------

    /** Attempts taken on this phone that the website has not confirmed yet. */
    fun pendingQuizAttempts(quizId: Long): Int = db.quizAttempts(userId, quizId).count { it.state == "pending" }

    /** Attempts left, counting the website and unsent attempts on this phone (null = unlimited). */
    fun quizAttemptsLeft(lesson: Lesson): Int? {
        val quiz = lesson.quiz ?: return 0
        return QuizEngine.attemptsLeft(quiz.copy(attemptsUsed = lesson.quizAttemptsUsed), pendingQuizAttempts(lesson.id))
    }

    /** Saves a finished attempt on the phone and queues it for Tutor LMS. Returns its id. */
    fun saveQuizAttempt(
        courseId: Long, quizId: Long, startedAt: Long, questions: List<QuizQuestion>,
        responses: Map<Long, QuizResponse>, result: QuizResult
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.addQuizAttempt(userId, LocalQuizAttempt(
            id = id, courseId = courseId, quizId = quizId, startedAt = startedAt, endedAt = now,
            responsesJson = QuizEngine.responsesToJson(responses).toString(), questionIds = questions.map { it.id },
            earned = result.earned, total = result.total, result = result.result, state = "pending",
            serverEarned = null, serverTotal = null, serverResult = "", reason = ""
        ))
        db.addEvent(userId, PendingEvent(
            id, courseId, quizId, EventType.QUIZ_ATTEMPT, now, 0, 0,
            QuizEngine.attemptPayload(quizId, startedAt, questions, responses).toString()
        ))
        db.setSyncState(userId, courseId, SyncState.PENDING)
        SyncWorker.enqueueNow(app)
        return id
    }

    // ---------- local files for a lesson ----------

    fun localFile(courseId: Long, url: String): File? {
        val f = db.fileByUrl(userId, courseId, url) ?: return null
        val file = File(f.localPath)
        return if (f.state == FileState.DONE && file.exists()) file else null
    }

    fun lessonFiles(courseId: Long, lessonId: Long): List<StoredFile> =
        db.files(userId, courseId).filter { it.lessonId == lessonId }

    /** Offline status of one lesson for the lesson list. */
    fun lessonAvailability(lesson: Lesson, files: List<StoredFile>): String {
        if (lesson.type == "quiz" && lesson.quizJson.isEmpty()) {
            return if (lesson.onlineReason.isNotBlank()) "Online only: ${lesson.onlineReason}" else "Online only"
        }
        if (lesson.isOfflineQuiz) {
            val quiz = lesson.quiz ?: return "Online only"
            val left = QuizEngine.attemptsLeft(quiz.copy(attemptsUsed = lesson.quizAttemptsUsed), pendingQuizAttempts(lesson.id))
            return "Available offline" + when (left) {
                null -> ""
                0 -> " · no attempts left"
                else -> " · $left attempt(s) left"
            }
        }
        if (!lesson.isLesson) return "Online only"
        val mine = files.filter { it.lessonId == lesson.id }
        if (!lesson.selected) return "Not downloaded"
        if (mine.any { it.state == FileState.FAILED }) return "Download failed"
        if (mine.any { it.state != FileState.DONE }) return "Downloading"
        return when {
            lesson.videoOfflineCapable -> "Available offline"
            lesson.hasVideo && VideoProviders.isOnlineOnly(lesson.videoOnlineProvider) -> "Text offline · video online only"
            else -> "Available offline"
        }
    }
}
