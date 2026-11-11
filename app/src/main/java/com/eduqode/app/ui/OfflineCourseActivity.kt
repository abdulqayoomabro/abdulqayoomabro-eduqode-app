package com.eduqode.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eduqode.app.R
import com.eduqode.app.offline.Lesson
import com.eduqode.app.offline.OfflineRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Offline course player: topics, lessons, completion and sync status. Works with no internet. */
class OfflineCourseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
    }

    private lateinit var repo: OfflineRepository
    private var courseId = 0L
    private var expiredShown = false
    private val adapter by lazy {
        LessonAdapter(true) { open(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_course)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.sync_menu)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.action_sync) {
                    if (repo.isOnline()) {
                        repo.syncNow()
                        lifecycleScope.launch {
                            val checked = repo.updateDownloadedCourse(courseId, force = true)
                            android.widget.Toast.makeText(this@OfflineCourseActivity,
                                if (checked) "Synced and checked for course updates" else "Progress synced", android.widget.Toast.LENGTH_SHORT).show()
                            render()
                        }
                    }
                    else MaterialAlertDialogBuilder(this@OfflineCourseActivity)
                        .setMessage("You are offline. Your progress is saved on this phone and will sync automatically when you are online.")
                        .setPositiveButton(android.R.string.ok, null).show()
                    true
                } else false
            }
        }
        findViewById<RecyclerView>(R.id.lessons).apply { layoutManager = LinearLayoutManager(context); adapter = this@OfflineCourseActivity.adapter }
        findViewById<MaterialButton>(R.id.btn_details).setOnClickListener {
            startActivity(Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_COURSE, courseId).putExtra(LessonActivity.EXTRA_COURSE_INFO, true))
        }

        // Bring the course up to date with the website when online (new lessons, quizzes, changes).
        lifecycleScope.launch {
            if (repo.updateDownloadedCourse(courseId)) render()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(3000) }
            }
        }
    }

    private suspend fun render() {
        val uid = repo.userId
        val c = withContext(Dispatchers.IO) { repo.db.course(uid, courseId) }
        if (c == null || !repo.auth.isLoggedIn) { finish(); return }
        if (c.accessExpired) {
            if (expiredShown) return
            expiredShown = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Access ended")
                .setMessage("Your access to this course has ended, so the offline copy is locked. It unlocks again after the next sync if your access is renewed.")
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setCancelable(false).show()
            return
        }
        val lessons = withContext(Dispatchers.IO) { repo.db.lessons(uid, courseId) }
        val rows = withContext(Dispatchers.IO) {
            LessonAdapter.rows(lessons, repo.db.files(uid, courseId), repo::lessonAvailability, repo::isDone)
        }
        findViewById<MaterialToolbar>(R.id.toolbar).title = c.title
        findViewById<TextView>(R.id.title).text = c.title
        findViewById<TextView>(R.id.desc).text = c.excerpt
        adapter.submit(rows)

        val real = lessons.filter { it.isLesson }
        val items = lessons.filter { it.isLesson || it.type == "quiz" || it.isAssignment }
        val doneIds = rows.filter { it.done }.map { it.lesson.id }.toSet()
        val done = items.count { it.id in doneIds }
        findViewById<TextView>(R.id.progress_text).text = "$done of ${items.size} lessons, quizzes and assignments done"
        findViewById<LinearProgressIndicator>(R.id.progress)
            .setProgressCompat(if (items.isEmpty()) 0 else done * 100 / items.size, false)
        val pending = withContext(Dispatchers.IO) { repo.db.pendingCount(uid, courseId) }
        findViewById<TextView>(R.id.sync_text).text = when {
            pending > 0 && !repo.isOnline() -> "Pending sync · $pending update(s) saved on this phone, will sync when online"
            pending > 0 -> "Pending sync · $pending update(s)"
            else -> Ui.syncLabel(c)
        }

        val next = lessons.firstOrNull { it.id == c.lastLessonId && it.isLesson }
            ?: real.firstOrNull { it.completedAt == 0L } ?: real.firstOrNull()
        findViewById<MaterialButton>(R.id.btn_continue).apply {
            text = when {
                next == null -> "No lessons"
                c.lastLessonId == next.id -> "Continue: ${next.title}"
                else -> "Start: ${next.title}"
            }
            isEnabled = next != null
            setOnClickListener { next?.let { open(it) } }
        }
    }

    private fun open(l: Lesson) {
        if (l.isOfflineQuiz) {
            startActivity(Intent(this, QuizActivity::class.java)
                .putExtra(QuizActivity.EXTRA_COURSE, courseId).putExtra(QuizActivity.EXTRA_QUIZ, l.id))
            return
        }
        if (l.isAssignment) {
            startActivity(Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_COURSE, courseId).putExtra(LessonActivity.EXTRA_LESSON, l.id))
            return
        }
        if (!l.isLesson) {
            // Quizzes stay online so answers are never stored in the app.
            if (repo.isOnline()) Ui.openWebsite(this, l.permalink)
            else MaterialAlertDialogBuilder(this)
                .setTitle("Online only")
                .setMessage("Quizzes need an internet connection. Connect and tap the quiz again.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        startActivity(Intent(this, LessonActivity::class.java)
            .putExtra(LessonActivity.EXTRA_COURSE, courseId).putExtra(LessonActivity.EXTRA_LESSON, l.id))
    }
}
