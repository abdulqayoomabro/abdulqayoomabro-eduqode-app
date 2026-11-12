package com.eduqode.app.offline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.eduqode.app.R
import com.eduqode.app.ui.OfflineLibraryActivity

/** Local notifications for downloads and sync. Shown only if the student allowed notifications. */
object Notifier {
    private const val CH_DOWNLOADS = "downloads"
    private const val CH_SYNC = "sync"
    private const val CH_NEWS = "news"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOADS, "Course downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Progress sync", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_NEWS, "Course news (new lessons, results, deadlines)", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun show(context: Context, channel: String, id: Int, title: String, text: String, ongoing: Boolean = false, progress: Int = -1) {
        if (!allowed(context)) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, OfflineLibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (progress in 0..100) b.setProgress(100, progress, false)
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun downloadId(courseId: Long) = (1000 + courseId % 100000).toInt()

    fun downloadProgress(context: Context, courseId: Long, title: String, percent: Int) =
        show(context, CH_DOWNLOADS, downloadId(courseId), "Downloading: $title", "$percent%", ongoing = true, progress = percent)

    fun downloadStarted(context: Context, courseId: Long, title: String) =
        show(context, CH_DOWNLOADS, downloadId(courseId), "Download started", title, ongoing = true, progress = 0)

    fun downloadDone(context: Context, courseId: Long, title: String) =
        show(context, CH_DOWNLOADS, downloadId(courseId), "Ready offline", "$title can now be studied without internet.")

    fun downloadPaused(context: Context, courseId: Long, title: String) =
        show(context, CH_DOWNLOADS, downloadId(courseId), "Download paused", title)

    fun downloadFailed(context: Context, courseId: Long, title: String, reason: String) =
        show(context, CH_DOWNLOADS, downloadId(courseId), "Download problem: $title", reason)

    fun cancel(context: Context, courseId: Long) = NotificationManagerCompat.from(context).cancel(downloadId(courseId))

    /** New lesson, announcement, result or deadline: tapping it opens that page in the app. */
    fun news(context: Context, n: AppNotice) {
        if (!allowed(context)) return
        ensureChannels(context)
        val id = 5000 + (n.id.hashCode() and 0x7ffffff)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, com.eduqode.app.MainActivity::class.java)
                .putExtra(com.eduqode.app.MainActivity.EXTRA_OPEN_URL, n.url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(context, CH_NEWS)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(if (n.time > 0) n.time else System.currentTimeMillis())
            .setShowWhen(true)
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun syncDone(context: Context, count: Int) =
        show(context, CH_SYNC, 900, "Progress synced", "$count offline update(s) saved to your account.")

    fun syncFailed(context: Context, reason: String) =
        show(context, CH_SYNC, 900, "Progress not synced yet", reason)
}
