package com.eduqode.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.R
import com.eduqode.app.offline.ApiClient
import com.eduqode.app.offline.Course
import com.eduqode.app.offline.CourseState
import com.eduqode.app.offline.DownloadProgress
import com.eduqode.app.offline.FileState
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Session
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Download screen for one course: what is in it, how big it is, and the download controls. */
class CourseDownloadActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
    }

    private lateinit var repo: OfflineRepository
    private var courseId = 0L
    private var preparing = false
    private var prepareError: String? = null
    private var pendingStart: Set<Long>? = null
    private var pendingAll = false

    private val adapter = LessonAdapter(false, { l, f -> repo.lessonAvailability(l, f) }) { lesson ->
        val c = repo.db.course(repo.userId, courseId)
        if (c?.state == CourseState.DOWNLOADED || c?.state == CourseState.FAILED) {
            startActivity(Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_COURSE, courseId).putExtra(LessonActivity.EXTRA_LESSON, lesson.id))
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startPending() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_download)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        findViewById<RecyclerView>(R.id.lessons).apply { layoutManager = LinearLayoutManager(context); adapter = this@CourseDownloadActivity.adapter }

        button(R.id.btn_download_all).setOnClickListener { confirmDownload(null) }
        button(R.id.btn_download_selected).setOnClickListener { chooseLessons() }
        button(R.id.btn_pause).setOnClickListener { repo.pause(courseId); refreshNow() }
        button(R.id.btn_resume).setOnClickListener { repo.resume(courseId); refreshNow() }
        button(R.id.btn_retry).setOnClickListener { repo.retryFailed(courseId); refreshNow() }
        button(R.id.btn_cancel).setOnClickListener { confirmRemove(cancel = true) }
        button(R.id.btn_remove).setOnClickListener { confirmRemove(cancel = false) }
        button(R.id.btn_open).setOnClickListener {
            startActivity(Intent(this, OfflineCourseActivity::class.java).putExtra(OfflineCourseActivity.EXTRA_COURSE, courseId))
        }

        lifecycleScope.launch {
            val state = Session.refresh(this@CourseDownloadActivity)
            if (state !is Session.State.LoggedIn) {
                MaterialAlertDialogBuilder(this@CourseDownloadActivity)
                    .setMessage("Please log in to your EduQode account first.")
                    .setPositiveButton("Log in") { _, _ -> Ui.openWebsite(this@CourseDownloadActivity, "${ApiClient.SITE}/dashboard/"); finish() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setCancelable(false).show()
                return@launch
            }
            prepare()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(800) }
            }
        }
    }

    private fun button(id: Int) = findViewById<MaterialButton>(id)

    private fun refreshNow() { lifecycleScope.launch { render() } }

    /** Fetches the latest course pack (structure, lesson text, file list) when online. */
    private suspend fun prepare() {
        val c = repo.db.course(repo.userId, courseId)
        val busy = c?.state == CourseState.DOWNLOADING || c?.state == CourseState.QUEUED
        if (busy || !repo.isOnline()) return
        preparing = true
        prepareError = null
        render()
        try {
            repo.preparePackage(courseId)
        } catch (e: ApiClient.ApiException) {
            prepareError = when (e.code) {
                403 -> "You are not enrolled in this course, or offline downloads are switched off for it."
                401 -> "Please log in again."
                else -> e.message
            }
        } catch (e: Exception) {
            prepareError = "Could not load the course. Check your internet and try again."
        }
        preparing = false
        render()
    }

    private suspend fun render() {
        val uid = repo.userId
        val (course, lessons, files, summary) = withContext(Dispatchers.IO) {
            val c = repo.db.course(uid, courseId)
            Quad(c, repo.db.lessons(uid, courseId), repo.db.files(uid, courseId), if (c != null) repo.summary(courseId) else null)
        }
        findViewById<TextView>(R.id.course_title).text = course?.title ?: ""
        findViewById<TextView>(R.id.course_desc).text = course?.excerpt ?: ""
        adapter.submit(lessons, files)

        val facts = findViewById<TextView>(R.id.facts)
        val note = findViewById<TextView>(R.id.video_note)
        if (summary != null && lessons.isNotEmpty()) {
            facts.text = buildString {
                append("Lessons: ${summary.lessons}\n")
                append("Videos: ${summary.videos} (${summary.offlineVideos} can play offline)\n")
                append("Downloadable resources: ${summary.resources}\n")
                if (summary.quizzes > 0) append("Quizzes: ${summary.quizzes} (online only)\n")
                append("Estimated download: ${OfflineLogic.formatBytes(summary.totalBytes)}")
                if (summary.unknownSizeFiles > 0) append(" + ${summary.unknownSizeFiles} file(s) of unknown size")
                append("\nFree space on phone: ${OfflineLogic.formatBytes(summary.freeBytes)}")
            }
            val onlineOnly = summary.videos - summary.offlineVideos
            note.visibility = if (onlineOnly > 0) View.VISIBLE else View.GONE
            note.text = "$onlineOnly video(s) are on YouTube or another online service. They show “Online only — internet required”. All other material of those lessons is still downloaded."
        } else {
            facts.text = when {
                preparing -> "Preparing the course…"
                prepareError != null -> prepareError
                !repo.isOnline() -> "Connect to the internet to download this course."
                else -> "Loading…"
            }
            note.visibility = View.GONE
        }

        val state = course?.state ?: CourseState.NONE
        val statusView = findViewById<TextView>(R.id.status)
        val bar = findViewById<LinearProgressIndicator>(R.id.progress)
        val progressText = findViewById<TextView>(R.id.progress_text)
        val total = summary?.totalBytes ?: 0
        val done = summary?.doneBytes ?: 0
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        statusView.text = when (state) {
            CourseState.NONE -> if (preparing) "Preparing…" else "Not downloaded"
            CourseState.QUEUED -> course?.error.orEmpty().ifBlank {
                if (repo.wifiOnly) "Waiting for Wi-Fi…" else "Waiting to start…"
            }
            CourseState.DOWNLOADING -> "Downloading… $pct%"
            CourseState.PAUSED -> "Paused at $pct%"
            CourseState.FAILED -> course?.error?.ifBlank { "Some files could not be downloaded" } ?: ""
            CourseState.DOWNLOADED -> "Downloaded · available offline"
            else -> ""
        }
        bar.isIndeterminate = preparing || (state == CourseState.DOWNLOADING && total <= 0)
        if (!bar.isIndeterminate) bar.setProgressCompat(if (state == CourseState.NONE) 0 else pct, true)
        val live = DownloadProgress.get(courseId)
        progressText.text = if (state == CourseState.NONE) "" else buildString {
            append("${OfflineLogic.formatBytes(done)} of ${OfflineLogic.formatBytes(total)} · ${OfflineLogic.formatBytes(maxOf(0, total - done))} left")
            if (state == CourseState.DOWNLOADING && live != null) {
                append("\n${OfflineLogic.formatBytes(live.bytesPerSecond)}/s · ${live.currentFile}")
            }
            val failed = files.count { it.state == FileState.FAILED }
            if (failed > 0) append("\n$failed file(s) failed")
        }

        val ready = summary != null && lessons.isNotEmpty() && !preparing && course?.accessExpired != true
        fun show(id: Int, visible: Boolean) { button(id).visibility = if (visible) View.VISIBLE else View.GONE }
        val active = state == CourseState.DOWNLOADING || state == CourseState.QUEUED
        show(R.id.btn_download_all, ready && state == CourseState.NONE)
        show(R.id.btn_download_selected, ready && (state == CourseState.NONE || state == CourseState.DOWNLOADED))
        button(R.id.btn_download_selected).text = if (state == CourseState.DOWNLOADED) "Change downloaded lessons" else "Download selected lessons"
        show(R.id.btn_open, state == CourseState.DOWNLOADED || state == CourseState.FAILED)
        show(R.id.btn_pause, active)
        // New content on the website (for example an offline video link added later) for a downloaded course.
        val updates = files.filter { it.state == FileState.PENDING }
        val hasUpdates = state == CourseState.DOWNLOADED && updates.isNotEmpty()
        show(R.id.btn_resume, state == CourseState.PAUSED || hasUpdates)
        button(R.id.btn_resume).text = if (hasUpdates)
            "Download new content (${updates.size} file(s), ${OfflineLogic.formatBytes(updates.sumOf { it.expectedBytes })})" else "Resume"
        show(R.id.btn_retry, state == CourseState.FAILED)
        show(R.id.btn_cancel, active || state == CourseState.PAUSED)
        show(R.id.btn_remove, state == CourseState.DOWNLOADED || state == CourseState.FAILED)
        button(R.id.btn_download_all).isEnabled = repo.isOnline()
        button(R.id.btn_download_selected).isEnabled = repo.isOnline()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun chooseLessons() {
        val lessons = repo.db.lessons(repo.userId, courseId).filter { it.isLesson }
        if (lessons.isEmpty()) return
        val titles = lessons.map { it.title }.toTypedArray()
        val checked = lessons.map { it.selected }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose lessons to download")
            .setMultiChoiceItems(titles, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Download") { _, _ ->
                val ids = lessons.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                if (ids.isEmpty()) return@setPositiveButton
                confirmDownload(ids)
            }.show()
    }

    /** Shows size and free space, checks storage, then starts. */
    private fun confirmDownload(selected: Set<Long>?) {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                repo.db.setSelected(repo.userId, courseId, selected)
                repo.summary(courseId)
            }
            val needed = maxOf(0, s.totalBytes - s.doneBytes)
            if (!OfflineLogic.hasRoom(needed, s.freeBytes)) {
                MaterialAlertDialogBuilder(this@CourseDownloadActivity)
                    .setTitle("Not enough storage")
                    .setMessage("This download needs about ${OfflineLogic.formatBytes(needed)}, but only ${OfflineLogic.formatBytes(s.freeBytes)} is free. Free up space or choose fewer lessons.")
                    .setPositiveButton(android.R.string.ok, null).show()
                return@launch
            }
            val network = if (repo.wifiOnly) "It will download over Wi-Fi only." else "It can use mobile data. You can switch to Wi-Fi only in My offline courses."
            MaterialAlertDialogBuilder(this@CourseDownloadActivity)
                .setTitle(if (selected == null) "Download complete course?" else "Download ${selected.size} lesson(s)?")
                .setMessage("About ${OfflineLogic.formatBytes(needed)} will be downloaded" +
                    (if (s.unknownSizeFiles > 0) " (plus ${s.unknownSizeFiles} file(s) of unknown size)" else "") +
                    ". Free space: ${OfflineLogic.formatBytes(s.freeBytes)}.\n\n$network")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Download") { _, _ ->
                    pendingStart = selected; pendingAll = selected == null
                    askNotificationsThenStart()
                }.show()
        }
    }

    private fun askNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPending()
        }
    }

    private fun startPending() {
        repo.startDownload(courseId, if (pendingAll) null else pendingStart)
        refreshNow()
    }

    private fun confirmRemove(cancel: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (cancel) "Cancel download?" else "Remove download?")
            .setMessage("Downloaded files of this course will be deleted from this phone only. Your enrollment and your progress on your account are not affected.")
            .setNegativeButton("Keep", null)
            .setPositiveButton(if (cancel) "Cancel download" else "Remove") { _, _ ->
                lifecycleScope.launch {
                    repo.removeDownload(courseId)
                    prepare()
                }
            }.show()
    }
}
