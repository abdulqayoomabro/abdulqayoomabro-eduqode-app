package com.eduqode.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import com.eduqode.app.R
import com.eduqode.app.offline.Attachment
import com.eduqode.app.offline.Lesson
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.VideoProviders
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One lesson, offline: downloaded video (with seek, pause and resume position), lesson text with
 * images, PDFs and other resources, previous/next, and "Mark as complete" (saved on the phone, synced later).
 */
@OptIn(UnstableApi::class)
class LessonActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
        const val EXTRA_LESSON = "lesson_id"
        const val EXTRA_COURSE_INFO = "course_info"
        private const val ASSET_HOST = "appassets.androidplatform.net"
    }

    private lateinit var repo: OfflineRepository
    private var courseId = 0L
    private var lesson: Lesson? = null
    private var player: ExoPlayer? = null
    private var saveJob: Job? = null
    private var fullscreen = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lesson)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)

        if (!repo.auth.isLoggedIn) { finish(); return }
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        setupContentView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (intent.getBooleanExtra(EXTRA_COURSE_INFO, false)) {
            showCourseInfo()
        } else {
            showLesson(intent.getLongExtra(EXTRA_LESSON, 0))
        }
    }

    // ---------- lesson text ----------

    private fun setupContentView() {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/offline/", WebViewAssetLoader.InternalStoragePathHandler(this, repo.userDir()))
            .build()
        findViewById<WebView>(R.id.content).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == ASSET_HOST) return false
                    if (!repo.isOnline()) {
                        Toast.makeText(this@LessonActivity, "This link needs an internet connection.", Toast.LENGTH_SHORT).show()
                    } else if (url.host?.endsWith("eduqode.com") == true) {
                        Ui.openWebsite(this@LessonActivity, url.toString())
                    } else {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    }
                    return true
                }
            }
        }
    }

    private fun loadPage(relativePath: String) {
        findViewById<WebView>(R.id.content).loadUrl("https://$ASSET_HOST/offline/c$courseId/$relativePath")
    }

    private fun showCourseInfo() {
        val c = repo.db.course(repo.userId, courseId) ?: run { finish(); return }
        findViewById<MaterialToolbar>(R.id.toolbar).title = c.title
        findViewById<View>(R.id.bottom_bar).visibility = View.GONE
        loadPage("course.html")
    }

    // ---------- lesson ----------

    private fun showLesson(lessonId: Long) {
        releasePlayer()
        val l = repo.db.lesson(repo.userId, lessonId) ?: run { finish(); return }
        lesson = l
        findViewById<MaterialToolbar>(R.id.toolbar).apply { title = l.title; subtitle = l.topicTitle }
        repo.recordLessonOpened(courseId, l.id)

        if (repo.lessonFile(courseId, l.id).exists()) loadPage("lesson_${l.id}.html")
        else findViewById<WebView>(R.id.content).loadDataWithBaseURL(null, "<p style='padding:16px;font-family:sans-serif'>This lesson has no text.</p>", "text/html", "utf-8", null)

        setupVideo(l)
        setupAttachments(l)
        setupNavigation(l)
    }

    private fun setupVideo(l: Lesson) {
        val box = findViewById<View>(R.id.video_box)
        val onlineOnly = findViewById<View>(R.id.online_only)
        box.visibility = View.GONE
        onlineOnly.visibility = View.GONE

        val local = l.videoOfflineUrl.takeIf { it.isNotEmpty() }?.let { repo.localFile(courseId, it) }
        val source: Uri? = when {
            local != null -> Uri.fromFile(local)
            l.videoOfflineUrl.isNotEmpty() && repo.isOnline() -> Uri.parse(l.videoOfflineUrl) // stream until downloaded
            else -> null
        }

        if (source != null) {
            box.visibility = View.VISIBLE
            val p = ExoPlayer.Builder(this).build()
            player = p
            val view = findViewById<PlayerView>(R.id.player)
            view.player = p
            view.setFullscreenButtonClickListener { setFullscreen(it) }
            p.setMediaItem(MediaItem.fromUri(source))
            if (l.positionMs > 0 && (l.durationMs == 0L || l.positionMs < l.durationMs - 3000)) p.seekTo(l.positionMs)
            p.prepare()
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) savePosition(sendNow = false)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        savePosition(sendNow = true)
                        if (lesson?.completedAt == 0L) {
                            Snackbar.make(findViewById(R.id.root), "Video finished", Snackbar.LENGTH_LONG)
                                .setAction("Mark complete") { markComplete() }.show()
                        }
                    }
                }
            })
            saveJob = lifecycleScope.launch {
                while (isActive) {
                    delay(5000)
                    if (player?.isPlaying == true) savePosition(sendNow = false)
                }
            }
            if (local == null) Toast.makeText(this, "Streaming: this video is not downloaded yet.", Toast.LENGTH_SHORT).show()
            return
        }

        if (l.hasVideo) {
            onlineOnly.visibility = View.VISIBLE
            val provider = VideoProviders.label(l.videoOnlineProvider)
            findViewById<TextView>(R.id.online_only_text).text =
                if (l.videoOfflineUrl.isNotEmpty()) "Video not downloaded yet" else "Online Only — Internet Required"
            findViewById<TextView>(R.id.online_only_sub).text = when {
                l.videoOfflineUrl.isNotEmpty() -> "Open the course's download screen and tap “Download new content” while online."
                else -> "This video is on $provider and can only be watched with internet. The rest of this lesson is available offline."
            }
            findViewById<MaterialButton>(R.id.btn_watch_online).apply {
                isEnabled = repo.isOnline()
                setOnClickListener { Ui.openWebsite(this@LessonActivity, l.permalink) }
            }
        }
    }

    private fun savePosition(sendNow: Boolean) {
        val p = player ?: return
        val l = lesson ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val pos = if (p.playbackState == Player.STATE_ENDED) duration else p.currentPosition
        repo.saveVideoPosition(courseId, l.id, pos, duration, sendNow)
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val hide = listOf(R.id.toolbar, R.id.attachments_box, R.id.content, R.id.bottom_bar)
        hide.forEach { id ->
            val v = findViewById<View>(id)
            if (on) { v.tag = v.visibility; v.visibility = View.GONE } else v.visibility = (v.tag as? Int) ?: View.VISIBLE
        }
        findViewById<View>(R.id.player).layoutParams = findViewById<View>(R.id.player).layoutParams.apply {
            height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else (220 * resources.displayMetrics.density).toInt()
        }
        findViewById<FrameLayout>(R.id.video_box).layoutParams = findViewById<View>(R.id.video_box).layoutParams.apply {
            height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---------- PDFs and other resources ----------

    private fun setupAttachments(l: Lesson) {
        val box = findViewById<View>(R.id.attachments_box)
        val group = findViewById<ChipGroup>(R.id.attachments)
        group.removeAllViews()
        box.visibility = if (l.attachments.isEmpty()) View.GONE else View.VISIBLE
        l.attachments.forEach { a ->
            val local = repo.localFile(courseId, a.url)
            group.addView(Chip(this).apply {
                text = (if (isPdf(a)) "PDF · " else "") + a.name + if (local == null) " (online)" else ""
                setOnClickListener { openAttachment(a, local) }
            })
        }
    }

    private fun isPdf(a: Attachment) = a.mime == "application/pdf" || a.url.substringBefore('?').lowercase().endsWith(".pdf")

    private fun openAttachment(a: Attachment, local: File?) {
        if (local == null) {
            if (repo.isOnline()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
            else Toast.makeText(this, "This file is not downloaded. Connect to the internet to open it.", Toast.LENGTH_LONG).show()
            return
        }
        if (isPdf(a)) {
            startActivity(Intent(this, PdfViewerActivity::class.java)
                .putExtra(PdfViewerActivity.EXTRA_PATH, local.path).putExtra(PdfViewerActivity.EXTRA_TITLE, a.name))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", local)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, a.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this file.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- navigation and completion ----------

    private fun setupNavigation(l: Lesson) {
        val all = repo.db.lessons(repo.userId, courseId).filter { it.isLesson }
        val i = all.indexOfFirst { it.id == l.id }
        val prev = all.getOrNull(i - 1)
        val next = all.getOrNull(i + 1)
        findViewById<MaterialButton>(R.id.btn_prev).apply {
            isEnabled = prev != null
            setOnClickListener { prev?.let { savePosition(true); showLesson(it.id) } }
        }
        findViewById<MaterialButton>(R.id.btn_next).apply {
            isEnabled = next != null
            setOnClickListener { next?.let { savePosition(true); showLesson(it.id) } }
        }
        updateCompleteButton()
    }

    private fun updateCompleteButton() {
        val done = (lesson?.completedAt ?: 0) > 0
        findViewById<MaterialButton>(R.id.btn_complete).apply {
            text = if (done) "Completed ✓" else "Mark as complete"
            isEnabled = !done
            setOnClickListener { markComplete() }
        }
    }

    private fun markComplete() {
        val l = lesson ?: return
        if (l.completedAt > 0) return
        repo.markLessonComplete(courseId, l.id)
        lesson = repo.db.lesson(repo.userId, l.id)
        updateCompleteButton()
        val msg = if (repo.isOnline()) "Lesson completed. Saving to your account…" else "Lesson completed. Saved on this phone; it will sync when you are online."
        Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_SHORT).show()
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        super.onPause()
        savePosition(sendNow = true)
        player?.pause()
    }

    private fun releasePlayer() {
        saveJob?.cancel()
        saveJob = null
        player?.release()
        player = null
        findViewById<PlayerView>(R.id.player).player = null
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
