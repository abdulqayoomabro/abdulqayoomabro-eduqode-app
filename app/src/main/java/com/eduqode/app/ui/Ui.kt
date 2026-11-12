package com.eduqode.app.ui

import com.eduqode.app.tr

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eduqode.app.MainActivity
import com.eduqode.app.R
import com.eduqode.app.offline.Course
import com.eduqode.app.offline.CourseState
import com.eduqode.app.offline.SyncState
import java.io.File

/** Small helpers shared by the offline screens. */
object Ui {

    /** "Language / زبان": English, اردو or the phone's language. The website follows the choice too. */
    fun chooseLanguage(activity: androidx.appcompat.app.AppCompatActivity) {
        val choices = com.eduqode.app.I18n.CHOICES
        val current = choices.indexOfFirst { it.first == com.eduqode.app.I18n.choice(activity) }.coerceAtLeast(0)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Language / زبان")
            .setSingleChoiceItems(choices.map { if (it.first.isEmpty()) tr(it.second) else it.second }.toTypedArray(), current) { d, which ->
                d.dismiss()
                val app = activity.applicationContext
                com.eduqode.app.I18n.setChoice(app, choices[which].first)
                com.eduqode.app.offline.Notifier.ensureChannels(app)
                val auth = com.eduqode.app.offline.AuthStore(app)
                if (auth.isLoggedIn) {
                    val lang = com.eduqode.app.I18n.language
                    Thread { runCatching { com.eduqode.app.offline.ApiClient(app).setLanguage(lang) } }.start()
                }
            }
            .show()
    }

    /** Keeps content clear of the status and navigation bars (Android 15+ draws edge-to-edge). */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun openWebsite(context: Context, url: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Loads a downloaded course image, scaled down; shows the first letter when there is none. */
    fun showThumb(image: ImageView, letter: TextView, file: File?, title: String) {
        val bmp = file?.takeIf { it.exists() }?.let {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 200) sample *= 2
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (bmp != null) {
            image.setImageBitmap(bmp); letter.text = ""
        } else {
            image.setImageDrawable(null); letter.text = title.trim().take(1).uppercase()
        }
    }

    fun ago(time: Long): String =
        if (time <= 0) tr("never") else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun date(context: Context, time: Long): String =
        if (time <= 0) "" else DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun syncLabel(c: Course): String = when (c.syncState) {
        SyncState.SYNCED -> if (c.lastSyncAt > 0) tr("Synced %s", ago(c.lastSyncAt)) else tr("Synced")
        SyncState.PENDING -> tr("Pending sync")
        SyncState.SYNCING -> tr("Syncing…")
        SyncState.FAILED -> tr("Sync failed") + if (c.syncError.isNotBlank()) ": ${c.syncError}" else ""
        else -> ""
    }

    fun stateLabel(c: Course): String = when {
        c.accessExpired -> tr("Access ended")
        c.state == CourseState.DOWNLOADED -> tr("Available offline")
        c.state == CourseState.DOWNLOADING -> tr("Downloading…")
        c.state == CourseState.QUEUED -> if (c.error.isNotBlank()) c.error else tr("Waiting to download…")
        c.state == CourseState.PAUSED -> tr("Paused")
        c.state == CourseState.FAILED -> tr("Download problem")
        else -> ""
    }

    fun stateColor(context: Context, c: Course): Int = ContextCompat.getColor(
        context,
        when {
            c.accessExpired || c.state == CourseState.FAILED -> R.color.error_red
            c.state == CourseState.DOWNLOADED -> R.color.ok_green
            else -> R.color.warn_orange
        }
    )
}
