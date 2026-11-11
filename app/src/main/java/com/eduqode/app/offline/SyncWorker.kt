package com.eduqode.app.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends progress recorded offline to Tutor LMS as soon as there is internet, then brings back the
 * latest progress from the website (other phones, the website itself). Runs automatically when the
 * connection returns, every few hours, and when the student taps "Sync now".
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val NOW = "eduqode-sync"
        private const val PERIODIC = "eduqode-sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueueNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = OfflineRepository(applicationContext)
        val db = repo.db
        val userId = repo.userId
        if (!repo.auth.isLoggedIn) return@withContext Result.success()

        var sent = 0
        try {
            var rounds = 0
            while (rounds++ < 10) {
                val events = db.pendingEvents(userId)
                val courseIds = db.courses(userId).filter { it.state != CourseState.NONE }.map { it.id }.toSet()
                if (events.isEmpty() && courseIds.isEmpty()) break

                val touched = events.map { it.courseId }.toSet()
                touched.forEach { db.setSyncState(userId, it, SyncState.SYNCING) }
                db.markSending(events.map { it.id }, true)

                val response = try {
                    repo.api.sync(JsonParsers.eventsBody(events, repo.auth.deviceId, courseIds))
                } catch (e: IOException) {
                    db.eventFailed(events.map { it.id }, e.message ?: "network")
                    throw e
                }

                // Finished events leave the queue; anything the server did not answer stays for next time.
                val finished = events.filter { e -> response.results[e.id]?.let { OfflineLogic.isFinal(it.first) } == true }
                db.deleteEvents(finished.map { it.id })
                db.markSending(events.filter { it !in finished }.map { it.id }, false)
                sent += finished.size

                response.results.values.filter { it.second == "no_access" }.let { rejected ->
                    if (rejected.isNotEmpty()) {
                        events.filter { response.results[it.id]?.second == "no_access" }.map { it.courseId }.toSet()
                            .forEach { db.setAccessExpired(userId, it, true) }
                    }
                }
                response.noAccess.forEach { db.setAccessExpired(userId, it, true) }


                val now = System.currentTimeMillis()
                response.progress.forEach { p ->
                    merge(db, userId, p)
                    db.setAccessExpired(userId, p.courseId, false)
                    val left = db.pendingCount(userId, p.courseId)
                    db.setSyncState(userId, p.courseId, if (left == 0) SyncState.SYNCED else SyncState.PENDING, "", now)
                }
                // The website refused a completion (for example a Tutor LMS Pro rule such as
                // "watch the video first"): show the lesson as not completed again, and say why.
                events.filter { e ->
                    e.type == EventType.LESSON_COMPLETE &&
                        response.results[e.id]?.let { it.first == "rejected" && it.second == "validation" } == true
                }.forEach { e ->
                    db.unmarkCompleted(userId, e.lessonId)
                    db.setSyncState(userId, e.courseId, SyncState.FAILED,
                        "A lesson could not be marked complete offline. Please complete it on the website.")
                }
                if (events.size < 200) break
            }
            // Courses whose events were all answered without a progress block (for example rejected ones).
            db.courses(userId).filter { it.syncState == SyncState.SYNCING }.forEach {
                val left = db.pendingCount(userId, it.id)
                db.setSyncState(userId, it.id, if (left == 0) SyncState.SYNCED else SyncState.PENDING, "", System.currentTimeMillis())
            }
            if (sent > 0) Notifier.syncDone(applicationContext, sent)
            Result.success()
        } catch (e: ApiClient.ApiException) {
            if (e.code == 401) {
                db.courses(userId).filter { db.pendingCount(userId, it.id) > 0 }.forEach {
                    db.setSyncState(userId, it.id, SyncState.FAILED, "Log in again in the app to sync your progress.")
                }
                Notifier.syncFailed(applicationContext, "Log in again in the app to sync your progress.")
                Result.failure()
            } else {
                markFailed(db, userId, e.message ?: "Server error")
                Result.retry()
            }
        } catch (e: IOException) {
            markFailed(db, userId, "No connection. Will try again automatically.")
            if (runAttemptCount >= 4) Notifier.syncFailed(applicationContext, "Will try again when the connection is stable.")
            Result.retry()
        }
    }

    private fun markFailed(db: OfflineDb, userId: Long, message: String) {
        db.courses(userId).filter { db.pendingCount(userId, it.id) > 0 }.forEach {
            db.setSyncState(userId, it.id, SyncState.FAILED, message)
        }
    }

    private fun merge(db: OfflineDb, userId: Long, p: ServerProgress) {
        val pendingPos = db.pendingPositionLessons(userId)
        val local = db.lessons(userId, p.courseId).filter { it.isLesson }.map {
            OfflineLogic.LocalLessonState(it.id, it.completedAt, it.positionMs, it.durationMs, it.positionAt, it.id in pendingPos)
        }
        OfflineLogic.mergeProgress(local, p).forEach { u ->
            u.completedAt?.let { db.markCompleted(userId, u.lessonId, it) }
            u.position?.let { db.savePosition(userId, u.lessonId, it.positionMs, it.durationMs, it.updatedAt) }
        }
        db.setCourseProgress(userId, p.courseId, p.percent)
        db.applyServerLastLesson(userId, p.courseId, p.lastLessonId, p.lastLessonAt)
    }
}
