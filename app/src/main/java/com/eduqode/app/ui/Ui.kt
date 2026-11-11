package com.eduqode.app.ui

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
        if (time <= 0) "never" else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun date(context: Context, time: Long): String =
        if (time <= 0) "" else DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun syncLabel(c: Course): String = when (c.syncState) {
        SyncState.SYNCED -> if (c.lastSyncAt > 0) "Synced ${ago(c.lastSyncAt)}" else "Synced"
        SyncState.PENDING -> "Pending sync"
        SyncState.SYNCING -> "Syncing…"
        SyncState.FAILED -> "Sync failed" + if (c.syncError.isNotBlank()) ": ${c.syncError}" else ""
        else -> ""
    }

    fun stateLabel(c: Course): String = when {
        c.accessExpired -> "Access ended"
        c.state == CourseState.DOWNLOADED -> "Available offline"
        c.state == CourseState.DOWNLOADING -> "Downloading…"
        c.state == CourseState.QUEUED -> if (c.error.isNotBlank()) c.error else "Waiting to download…"
        c.state == CourseState.PAUSED -> "Paused"
        c.state == CourseState.FAILED -> "Download problem"
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
