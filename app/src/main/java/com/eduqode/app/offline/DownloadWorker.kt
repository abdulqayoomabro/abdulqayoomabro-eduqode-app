package com.eduqode.app.offline

import android.content.ContentValues
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Live numbers for the download screen (same process, no database writes per byte). */
object DownloadProgress {
    data class Live(val bytesPerSecond: Long, val currentFile: String)
    private val live = ConcurrentHashMap<Long, Live>()
    fun set(courseId: Long, v: Live) { live[courseId] = v }
    fun get(courseId: Long): Live? = live[courseId]
    fun clear(courseId: Long) { live.remove(courseId) }
}

/**
 * Downloads one course in the background, one file at a time (gentle on battery and data).
 * - Survives app restarts and reboots (WorkManager).
 * - Resumes partly downloaded files with HTTP Range requests (files are saved as .part until complete).
 * - Checks free storage before each file and the final size after it.
 * - Network problems: WorkManager retries later with back-off, when the network (or Wi-Fi) is back.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val KEY_USER = "user"
        private const val KEY_COURSE = "course"

        fun workName(userId: Long, courseId: Long) = "download-$userId-$courseId"

        fun enqueue(context: Context, userId: Long, courseId: Long, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_USER to userId, KEY_COURSE to courseId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("eduqode-download")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(userId, courseId), ExistingWorkPolicy.REPLACE, request)
        }
    }

    private enum class Outcome { DONE, FAILED, STOPPED }

    private val db = OfflineDb.get(applicationContext)
    private val repo = OfflineRepository(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = inputData.getLong(KEY_USER, 0)
        val courseId = inputData.getLong(KEY_COURSE, 0)
        // Downloads belong to one student; if someone else is logged in now, stop.
        if (userId == 0L || userId != repo.userId) return@withContext Result.failure()
        val course = db.course(userId, courseId) ?: return@withContext Result.failure()
        if (course.state == CourseState.PAUSED || course.state == CourseState.NONE) return@withContext Result.success()

        if (course.state == CourseState.QUEUED) Notifier.downloadStarted(applicationContext, courseId, course.title)
        db.setCourseState(userId, courseId, CourseState.DOWNLOADING)

        try {
            val order = listOf(FileKind.THUMBNAIL, FileKind.IMAGE, FileKind.ATTACHMENT, FileKind.VIDEO)
            val todo = db.files(userId, courseId)
                .filter { it.state == FileState.PENDING || it.state == FileState.DOWNLOADING }
                .sortedBy { order.indexOf(it.kind) }

            for (file in todo) {
                if (isStopped) return@withContext Result.success()

                val remaining = db.files(userId, courseId)
                    .filter { it.state == FileState.PENDING || it.state == FileState.DOWNLOADING }
                    .sumOf { maxOf(0, it.expectedBytes - it.doneBytes) }
                if (!OfflineLogic.hasRoom(remaining, repo.freeBytes())) {
                    val msg = "Not enough storage. Free up ${OfflineLogic.formatBytes(remaining)} and tap Retry."
                    db.setCourseState(userId, courseId, CourseState.FAILED, msg)
                    Notifier.downloadFailed(applicationContext, courseId, course.title, msg)
                    return@withContext Result.failure()
                }

                when (download(file, userId, courseId, course.title)) {
                    Outcome.STOPPED -> return@withContext Result.success()
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // Network dropped or server busy: try again later (WorkManager waits for the network).
            if (isStopped) return@withContext Result.success()
            db.setCourseState(userId, courseId, CourseState.QUEUED, "Waiting for a connection…")
            DownloadProgress.clear(courseId)
            return@withContext Result.retry()
        }

        DownloadProgress.clear(courseId)
        updateTotals(userId, courseId)
        val failed = db.files(userId, courseId).count { it.state == FileState.FAILED }
        if (failed == 0) {
            db.updateCourse(userId, courseId, ContentValues().apply {
                put("state", CourseState.DOWNLOADED); put("error", ""); put("downloaded_at", System.currentTimeMillis())
            })
            Notifier.downloadDone(applicationContext, courseId, course.title)
        } else {
            val msg = "$failed file(s) could not be downloaded. Tap Retry."
            db.setCourseState(userId, courseId, CourseState.FAILED, msg)
            Notifier.downloadFailed(applicationContext, courseId, course.title, msg)
        }
        Result.success()
    }

    private fun updateTotals(userId: Long, courseId: Long) {
        val s = repo.summary(courseId)
        db.updateCourse(userId, courseId, ContentValues().apply {
            put("total_bytes", s.totalBytes); put("done_bytes", s.doneBytes)
        })
    }

    /** Downloads one file. Throws IOException for network problems (the whole job is retried later). */
    private fun download(file: StoredFile, userId: Long, courseId: Long, courseTitle: String): Outcome {
        db.setFileState(file.id, FileState.DOWNLOADING)
        var lastSave = 0L
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        var lastDone = -1L
        var knownTotal = file.expectedBytes

        val result = try {
            FileDownloader(ApiClient.USER_AGENT).download(
                url = OfflineLogic.directDownloadUrl(file.url),
                target = File(file.localPath),
                expectedBytes = file.expectedBytes,
                shouldStop = { isStopped },
                onProgress = { done, total ->
                    if (lastDone >= 0) windowBytes += done - lastDone
                    lastDone = done
                    if (total > 0 && total != knownTotal) {
                        knownTotal = total
                        db.updateFile(file.id, ContentValues().apply { put("expected_bytes", total) })
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSave > 700) {
                        lastSave = now
                        val elapsed = (now - windowStart).coerceAtLeast(1)
                        DownloadProgress.set(courseId, DownloadProgress.Live(windowBytes * 1000 / elapsed, file.name))
                        if (elapsed > 3000) { windowStart = now; windowBytes = 0 }
                        db.updateFile(file.id, ContentValues().apply { put("done_bytes", done) })
                        val s = repo.summary(courseId)
                        if (s.totalBytes > 0) {
                            Notifier.downloadProgress(applicationContext, courseId, courseTitle,
                                (s.doneBytes * 100 / s.totalBytes).toInt().coerceIn(0, 100))
                        }
                    }
                }
            )
        } catch (e: IOException) {
            db.updateFile(file.id, ContentValues().apply {
                put("state", FileState.PENDING)
                put("done_bytes", File(file.localPath + ".part").let { if (it.exists()) it.length() else 0L })
            })
            throw e
        }

        return when (result) {
            is FileDownloader.Outcome.Done -> { finish(file, result.size); Outcome.DONE }
            is FileDownloader.Outcome.Failed -> { db.setFileState(file.id, FileState.FAILED, result.reason); Outcome.FAILED }
            is FileDownloader.Outcome.Stopped -> {
                db.updateFile(file.id, ContentValues().apply { put("done_bytes", result.doneBytes); put("state", FileState.PENDING) })
                Outcome.STOPPED
            }
        }
    }

    private fun finish(file: StoredFile, size: Long) {
        db.updateFile(file.id, ContentValues().apply {
            put("state", FileState.DONE); put("error", ""); put("done_bytes", size); put("expected_bytes", size)
        })
    }
}
