package com.eduqode.app.offline

import android.content.Context
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
 * Asks the website about once an hour what is new for the student (new lessons, announcements,
 * graded work, deadlines) and shows phone notifications. Downloaded courses with new content are
 * updated too. Works without Firebase; with the phone offline it simply waits for internet.
 */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val PERIODIC = "eduqode-notify"
        private const val ONCE = "eduqode-notify-now"
        private const val PREFS = "eduqode_notify"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NotifyWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NotifyWorker>().setConstraints(online).build()
            )
        }

        /**
         * One check. The first check only remembers "now", so a new install does not show old news.
         * Returns the notices that were shown.
         */
        suspend fun check(context: Context): List<AppNotice> = withContext(Dispatchers.IO) {
            val repo = OfflineRepository(context)
            if (!repo.auth.isLoggedIn) return@withContext emptyList()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sinceKey = "since_${repo.userId}"
            val seenKey = "seen_${repo.userId}"
            val since = prefs.getLong(sinceKey, 0)
            val (serverTime, notices) = repo.api.notifications(if (since == 0L) System.currentTimeMillis() else since)
            if (since == 0L) {
                prefs.edit().putLong(sinceKey, serverTime).apply()
                return@withContext emptyList()
            }
            val seen = prefs.getString(seenKey, "")!!.split('\n').filter { it.isNotBlank() }.toMutableList()
            val fresh = notices.filter { it.id !in seen }
            fresh.take(8).forEach { Notifier.news(context, it) }
            seen += fresh.map { it.id }
            prefs.edit()
                .putLong(sinceKey, maxOf(since, serverTime - 60_000))   // a minute of overlap; duplicates are filtered by id
                .putString(seenKey, seen.takeLast(300).joinToString("\n"))
                .apply()
            // New lessons in a downloaded course: bring the offline copy up to date.
            fresh.filter { it.type == "new_content" }.map { it.courseId }.toSet().forEach { cid ->
                runCatching { repo.updateDownloadedCourse(cid, force = true) }
            }
            fresh
        }
    }

    override suspend fun doWork(): Result = try {
        check(applicationContext)
        Result.success()
    } catch (e: ApiClient.ApiException) {
        if (e.code == 401) Result.success() else Result.retry()
    } catch (e: IOException) {
        Result.retry()
    }
}
