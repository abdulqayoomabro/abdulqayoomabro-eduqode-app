package com.eduqode.app.offline

import com.eduqode.app.tr

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
    /** H5P files of a course: libraries/<Name-1.2>/... (shared) and content/<id>/{h5p.json,content/...}. */
    fun h5pDir(courseId: Long, userId: Long = this.userId) = File(courseDir(courseId, userId), "h5p")
    fun h5pContentDir(courseId: Long, contentId: Long) = File(h5pDir(courseId), "content/$contentId")

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
        runCatching { saveCertificates(list.mapNotNull { it.certificate }) }
        list
    }

    // ---------- certificates (kept on the phone, also when a course download is removed) ----------

    fun certificatesDir(userId: Long = this.userId) = File(userDir(userId), "certificates")

    /** Certificates known on this phone, with the saved picture when there is one. */
    fun certificates(): List<Pair<CertificateInfo, File?>> {
        val index = File(certificatesDir(), "index.json")
        if (!index.exists()) return emptyList()
        return runCatching {
            val a = org.json.JSONArray(index.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val c = CertificateInfo(o.getLong("course_id"), o.optString("title"), o.optString("hash"), o.optString("view_url"), o.optString("image_url"))
                c to File(certificatesDir(), "c${c.courseId}-${c.hash.take(16).filter { it.isLetterOrDigit() }}.jpg").takeIf { it.exists() && it.length() > 0 }
            }
        }.getOrDefault(emptyList())
    }

    /** Stores the list and downloads pictures that are not on the phone yet (small JPEG files). */
    fun saveCertificates(list: List<CertificateInfo>) {
        if (list.isEmpty() && !File(certificatesDir(), "index.json").exists()) return
        val dir = certificatesDir().apply { mkdirs() }
        val known = certificates().associateBy { it.first.courseId }
        val merged = (known.mapValues { it.value.first } + list.associateBy { it.courseId }).values
        File(dir, "index.json").writeText(org.json.JSONArray().apply {
            merged.forEach { c -> put(org.json.JSONObject().put("course_id", c.courseId).put("title", c.courseTitle).put("hash", c.hash)
                .put("view_url", c.viewUrl).put("image_url", c.imageUrl)) }
        }.toString())
        list.filter { it.imageUrl.isNotBlank() }.forEach { c ->
            val target = File(dir, "c${c.courseId}-${c.hash.take(16).filter { it.isLetterOrDigit() }}.jpg")
            if (target.exists() && target.length() > 0) return@forEach
            val part = File(target.path + ".part")
            val conn = (URL(c.imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", ApiClient.USER_AGENT)
            }
            try {
                if (conn.responseCode == 200 && conn.contentType?.startsWith("image/") == true) {
                    conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (part.length() > 0) part.renameTo(target)
                }
            } finally {
                conn.disconnect(); part.delete()
            }
        }
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
        addFile(0, FileKind.THUMBNAIL, pkg.course.thumbnailUrl, tr("Course image"), "image/*", 0, "jpg")
        File(dir, "course.html").writeText(OfflineLogic.wrapHtml(pkg.course.title, pkg.course.descriptionHtml))

        // H5P: library files are shared by every exercise of the course (lesson 0); each exercise's
        // own files belong to its lesson or quiz. h5p.json and content.json come inside the pack.
        val h5pRoot = h5pDir(courseId)
        fun addH5PFile(lessonId: Long, url: String, target: File, size: Long) {
            wanted += url
            db.ensureFile(uid, courseId, lessonId, FileKind.H5P, url, target.name, "", target.path, size)
        }
        pkg.h5pLibraries.forEach { (_, files) ->
            files.forEach { f -> addH5PFile(0, f.url, File(h5pRoot, "libraries/${f.path}"), f.size) }
        }
        fun addH5PContent(ownerId: Long, c: H5PContent) {
            val dir = File(h5pRoot, "content/${c.id}").apply { mkdirs() }
            File(dir, "h5p.json").writeText(c.h5pJson)
            File(dir, "content").mkdirs()
            File(dir, "content/content.json").writeText(c.contentJson)
            c.files.forEach { f -> addH5PFile(ownerId, f.url, File(dir, "content/${f.path}"), f.size) }
        }

        val htmlPresent = HashSet<Long>()
        pkg.lessons.forEach { l ->
            l.h5p.forEach { addH5PContent(l.id, it) }
            l.quiz?.questions?.forEach { q -> q.h5pContent?.let { addH5PContent(l.id, it) } }
            if (l.type == "lesson" || l.type == "assignment") {
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
                l.images.forEach { addFile(0, FileKind.IMAGE, it, tr("Quiz image"), "image/*", 0, "img") }
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

    /** True once the student has taken the quiz (on this phone or on the website). */
    fun quizTaken(lesson: Lesson): Boolean =
        lesson.quizAttemptsUsed > 0 || db.quizAttempts(userId, lesson.id).any { it.state != "rejected" }

    /** Latest result, e.g. "Passed 4/6" (phone attempt first, else the website's last attempt). */
    fun quizResultLabel(lesson: Lesson): String? {
        val local = db.quizAttempts(userId, lesson.id).firstOrNull { it.state != "rejected" }
        val (earned, total, result) = when {
            local != null -> Triple(local.serverEarned ?: local.earned, local.serverTotal ?: local.total, local.serverResult.ifBlank { local.result })
            lesson.quizLastJson.isNotEmpty() -> runCatching {
                QuizEngine.attemptSummary(org.json.JSONObject(lesson.quizLastJson)).let { Triple(it.earned, it.total, it.result.ifBlank { it.status }) }
            }.getOrNull() ?: return null
            else -> return null
        }
        fun n(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(java.util.Locale.US, "%.1f", d)
        val word = when (result) { "pass" -> tr("Passed"); "fail" -> tr("Not passed"); else -> tr("Awaiting review") }
        return "$word ${n(earned)}/${n(total)}"
    }

    // ---------- assignments written on the phone ----------

    fun submissionDir(courseId: Long, submissionId: String) = File(courseDir(courseId), "submissions/$submissionId")

    /** The submission being written, or a new one (started now). */
    fun draft(courseId: Long, assignmentId: Long): Submission {
        val list = db.submissions(userId, assignmentId)
        list.firstOrNull { it.state == SubmissionState.DRAFT }?.let { return it }
        // After a refusal the student fixes the answer and sends it again as a new submission.
        val rejected = list.firstOrNull()?.takeIf { it.state == SubmissionState.REJECTED }
        val now = System.currentTimeMillis()
        val s = Submission(UUID.randomUUID().toString(), courseId, assignmentId, rejected?.answer ?: "", emptyList(),
            rejected?.startedAt ?: now, now, SubmissionState.DRAFT, "")
        db.saveSubmission(userId, s)
        rejected?.files?.forEach { f -> File(f.path).takeIf { it.exists() }?.let { addSubmissionFile(s.id, it, f.name, f.mime) } }
        return db.submissions(userId, assignmentId).first { it.id == s.id }
    }

    fun latestSubmission(assignmentId: Long): Submission? = db.submissions(userId, assignmentId).firstOrNull()

    fun saveDraftAnswer(s: Submission, answer: String): Submission {
        val updated = s.copy(answer = answer, updatedAt = System.currentTimeMillis())
        db.saveSubmission(userId, updated)
        return updated
    }

    /** Copies a file into the submission (the original stays where it was). */
    fun addSubmissionFile(submissionId: String, source: File, name: String, mime: String): Submission? {
        val s = db.submissionById(userId, submissionId) ?: return null
        val dir = submissionDir(s.courseId, s.id).apply { mkdirs() }
        var target = File(dir, OfflineLogic.safeFileName(name))
        var n = 1
        while (target.exists()) target = File(dir, "${n++}-" + OfflineLogic.safeFileName(name))
        source.copyTo(target)
        val updated = s.copy(files = s.files + SubmissionFile(target.path, name, mime, target.length()), updatedAt = System.currentTimeMillis())
        db.saveSubmission(userId, updated)
        return updated
    }

    fun removeSubmissionFile(s: Submission, path: String): Submission {
        File(path).delete()
        val updated = s.copy(files = s.files.filter { it.path != path }, updatedAt = System.currentTimeMillis())
        db.saveSubmission(userId, updated)
        return updated
    }

    /** Problems that would make the website refuse the submission (checked before it is queued). */
    fun submissionProblem(s: Submission, info: AssignmentInfo): String? = when {
        s.answer.isBlank() -> tr("Write your answer first.")
        info.maxFiles <= 0 && s.files.isNotEmpty() -> tr("This assignment does not take files.")
        s.files.size > info.maxFiles -> tr("At most %s file(s).", info.maxFiles)
        s.files.any { Math.round(it.size / (1024.0 * 1024.0)) > info.maxFileMb } -> tr("Each file can be at most %s MB.", info.maxFileMb)
        else -> null
    }

    /** Finishes the submission on the phone; it is sent to the website as soon as there is internet. */
    fun submitAssignment(s: Submission) {
        val now = System.currentTimeMillis()
        db.saveSubmission(userId, s.copy(state = SubmissionState.PENDING, updatedAt = now, reason = ""))
        db.markCompleted(userId, s.assignmentId, now)
        db.setSyncState(userId, s.courseId, SyncState.PENDING)
        SyncWorker.enqueueNow(app)
    }

    // ---------- H5P exercises ----------

    /** True when every file an H5P exercise needs is on the phone. */
    fun h5pReady(courseId: Long, contentId: Long, files: List<StoredFile> = db.files(userId, courseId)): Boolean {
        val h5pJson = File(h5pContentDir(courseId, contentId), "h5p.json")
        if (!h5pJson.exists()) return false
        val folders = runCatching {
            val deps = org.json.JSONObject(h5pJson.readText()).getJSONArray("preloadedDependencies")
            (0 until deps.length()).map { i -> deps.getJSONObject(i).let { "${it.getString("machineName")}-${it.getString("majorVersion")}.${it.getString("minorVersion")}" } }
        }.getOrDefault(emptyList())
        val libsRoot = File(h5pDir(courseId), "libraries").path + "/"
        val contentRoot = h5pContentDir(courseId, contentId).path + "/"
        val needed = files.filter { f ->
            f.kind == FileKind.H5P && (f.localPath.startsWith(contentRoot) ||
                folders.any { f.localPath.startsWith("$libsRoot$it/") })
        }
        return folders.isNotEmpty() && needed.all { it.state == FileState.DONE && File(it.localPath).exists() }
    }

    /**
     * Stores one xAPI statement of an H5P exercise in a lesson and queues it for Tutor LMS Pro.
     * When it finishes the exercise and every exercise of the lesson is done, the lesson's
     * "H5P finished" is recorded too (Tutor LMS Pro needs it before the lesson can be completed).
     * Returns true when that happened just now.
     */
    fun saveH5PStatement(courseId: Long, lessonId: Long, contentId: Long, statement: org.json.JSONObject, openedAt: Long): Boolean {
        val now = System.currentTimeMillis()
        val payload = org.json.JSONObject().put("content_id", contentId).put("statement", statement.toString()).put("opened_at", openedAt)
        db.addEvent(userId, PendingEvent(UUID.randomUUID().toString(), courseId, lessonId, EventType.H5P_STATEMENT, now, 0, 0, payload.toString()))
        db.setSyncState(userId, courseId, SyncState.PENDING)
        var finishedNow = false
        OfflineLogic.h5pFinalScore(statement)?.let { (raw, max) ->
            db.markH5PDone(userId, courseId, lessonId, contentId, raw, max, now)
            val lesson = db.lesson(userId, lessonId)
            if (lesson != null && !lesson.h5pFinished) {
                val done = db.h5pDone(userId, lessonId).keys
                if (lesson.h5p.isNotEmpty() && lesson.h5p.all { it.id in done }) {
                    db.setH5PFinished(userId, lessonId)
                    // A moment later than the statement so the website applies them in this order.
                    db.addEvent(userId, PendingEvent(UUID.randomUUID().toString(), courseId, lessonId, EventType.H5P_LESSON_FINISHED, now + 1, 0, 0))
                    finishedNow = true
                }
            }
        }
        SyncWorker.enqueueNow(app)
        return finishedNow
    }

    /** Exercises of a lesson the student has finished: id -> (score, max). */
    fun h5pDone(lessonId: Long): Map<Long, Pair<Double, Double>> = db.h5pDone(userId, lessonId)

    /** Lesson cannot be marked complete yet because of Tutor's "finish H5P first" setting. */
    fun h5pBlocksCompletion(lesson: Lesson): Boolean {
        if (lesson.h5p.isEmpty() || lesson.h5pFinished) return false
        val course = db.course(userId, lesson.courseId) ?: return false
        return course.h5pGate
    }

    /** Lesson, quiz or assignment is done (for the ✓ in the course list and progress). */
    fun isDone(lesson: Lesson): Boolean = lesson.completedAt > 0 || (lesson.type == "quiz" && quizTaken(lesson))

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
        if (lesson.isAssignment) {
            val mine = files.filter { it.lessonId == lesson.id }
            val sub = latestSubmission(lesson.id)
            return when {
                mine.any { it.state == FileState.FAILED } -> tr("Download failed")
                mine.any { it.state != FileState.DONE && it.state != FileState.SKIPPED } -> tr("Downloading")
                sub?.state == SubmissionState.PENDING -> tr("Submitted on this phone · will be sent when online")
                sub?.state == SubmissionState.REJECTED -> tr("Not sent: %s", sub.reason)
                sub?.state == SubmissionState.DRAFT && sub.answer.isNotBlank() -> tr("Draft saved on this phone")
                lesson.completedAt > 0 -> tr("Submitted · instructions and files offline")
                else -> tr("Instructions and files offline · submit on the website")
            }
        }
        if (lesson.type == "quiz" && lesson.quizJson.isEmpty()) {
            return if (lesson.onlineReason.isNotBlank()) tr("Online only: %s", lesson.onlineReason) else tr("Online only")
        }
        if (lesson.isOfflineQuiz) {
            val quiz = lesson.quiz ?: return tr("Online only")
            val left = QuizEngine.attemptsLeft(quiz.copy(attemptsUsed = lesson.quizAttemptsUsed), pendingQuizAttempts(lesson.id))
            val last = quizResultLabel(lesson)
            return (if (last != null) "$last · " else "") + tr("Available offline") + when (left) {
                null -> ""
                0 -> tr(" · no attempts left")
                else -> tr(" · %s attempt(s) left", left)
            }
        }
        if (!lesson.isLesson) return tr("Online only")
        val mine = files.filter { it.lessonId == lesson.id }
        if (!lesson.selected) return tr("Not downloaded")
        val h5pLibs = if (lesson.h5p.isEmpty()) emptyList() else files.filter { it.lessonId == 0L && it.kind == FileKind.H5P }
        if (mine.any { it.state == FileState.FAILED } || h5pLibs.any { it.state == FileState.FAILED }) return tr("Download failed")
        if (mine.any { it.state != FileState.DONE } || h5pLibs.any { it.state != FileState.DONE }) return tr("Downloading")
        if (lesson.h5p.isNotEmpty()) {
            val offline = lesson.h5p.filter { it.offline }
            val done = h5pDone(lesson.id).keys
            val count = offline.count { it.id in done }
            val online = lesson.h5p.size - offline.size
            return tr("Available offline") + tr(" · exercises %s/%s done", count, offline.size) + if (online > 0) tr(" · %s need the website", online) else ""
        }
        return when {
            lesson.videoOfflineCapable -> tr("Available offline")
            lesson.hasVideo && VideoProviders.isOnlineOnly(lesson.videoOnlineProvider) -> tr("Text offline · video online only")
            else -> tr("Available offline")
        }
    }
}
