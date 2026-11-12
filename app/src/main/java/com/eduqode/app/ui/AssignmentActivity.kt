package com.eduqode.app.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eduqode.app.R
import com.eduqode.app.offline.AssignmentInfo
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Submission
import com.eduqode.app.offline.SubmissionState
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes a Tutor LMS Pro assignment with no internet: the answer and files are kept on the phone
 * (saved while typing) and sent to the website by the sync as soon as there is a connection.
 */
class AssignmentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
        const val EXTRA_ASSIGNMENT = "assignment_id"
    }

    private lateinit var repo: OfflineRepository
    private lateinit var info: AssignmentInfo
    private var courseId = 0L
    private var assignmentId = 0L
    private var sub: Submission? = null

    private val answer get() = findViewById<TextInputEditText>(R.id.answer)

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { attach(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assignment)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)
        assignmentId = intent.getLongExtra(EXTRA_ASSIGNMENT, 0)
        val lesson = repo.db.lesson(repo.userId, assignmentId)
        val i = lesson?.assignment
        if (!repo.auth.isLoggedIn || lesson == null || i == null) { finish(); return }
        info = i
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = lesson.title
            subtitle = "Assignment"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.info).text = buildString {
            if (info.totalMark > 0) append("Total marks ${n(info.totalMark)} · pass ${n(info.passMark)}\n")
            append(if (info.maxFiles > 0) "You can attach up to ${info.maxFiles} file(s), ${info.maxFileMb} MB each.\n" else "Write your answer below.\n")
            append("Your work is saved on this phone while you type and is sent to your teacher when you are online.")
        }
        findViewById<MaterialButton>(R.id.btn_attach).setOnClickListener { pick.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.btn_submit).setOnClickListener { confirmSubmit() }
        load()
    }

    private fun n(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    private fun load() {
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { repo.latestSubmission(assignmentId) }
            val s = if (latest != null && latest.state in setOf(SubmissionState.PENDING, SubmissionState.SUBMITTED)) latest
            else withContext(Dispatchers.IO) { repo.draft(courseId, assignmentId) }
            sub = s
            render(s, rejected = latest?.takeIf { it.state == SubmissionState.REJECTED })
        }
    }

    private fun render(s: Submission, rejected: Submission? = null) {
        val editable = s.state == SubmissionState.DRAFT
        if (answer.text.toString() != s.answer) answer.setText(s.answer)
        answer.isEnabled = editable
        findViewById<View>(R.id.btn_attach).visibility = if (editable && info.maxFiles > 0 && s.files.size < info.maxFiles) View.VISIBLE else View.GONE
        findViewById<View>(R.id.files_label).visibility = if (s.files.isNotEmpty() || (editable && info.maxFiles > 0)) View.VISIBLE else View.GONE
        val box = findViewById<LinearLayout>(R.id.files)
        box.removeAllViews()
        s.files.forEach { f ->
            box.addView(TextView(this).apply {
                text = "📎 ${f.name} · ${OfflineLogic.formatBytes(f.size)}" + if (editable) "   ✕ remove" else ""
                textSize = 15f
                setPadding(0, 12, 0, 12)
                if (editable) setOnClickListener {
                    sub = repo.removeSubmissionFile(currentDraft(), f.path)
                    render(sub!!)
                }
            })
        }
        val state = findViewById<TextView>(R.id.state)
        val (msg, color) = when {
            s.state == SubmissionState.SUBMITTED -> "Submitted ✓ Your teacher has it." to R.color.ok_green
            s.state == SubmissionState.PENDING -> (if (repo.isOnline()) "Submitted on this phone · sending…" else "Submitted on this phone · it will be sent when you are online.") to R.color.ok_green
            rejected != null -> "The website did not accept the last submission: ${rejected.reason}. Fix it and submit again." to R.color.error_red
            info.submitted -> "Already submitted on the website. You can submit again if your teacher allows it." to R.color.text_secondary
            else -> "" to R.color.text_secondary
        }
        state.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        state.text = msg
        state.setTextColor(ContextCompat.getColor(this, color))
        findViewById<MaterialButton>(R.id.btn_submit).apply {
            visibility = if (editable) View.VISIBLE else View.GONE
            text = if (repo.isOnline()) "Submit" else "Submit (sends when online)"
        }
    }

    /** The draft with the latest typed text. */
    private fun currentDraft(): Submission {
        val s = sub ?: repo.draft(courseId, assignmentId)
        return if (s.state == SubmissionState.DRAFT && s.answer != answer.text.toString()) repo.saveDraftAnswer(s, answer.text.toString()).also { sub = it } else s
    }

    private fun attach(uri: Uri) {
        val s = currentDraft()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var name = "file"
                var size = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                if (size > info.maxFileMb * 1024L * 1024L + 512 * 1024) return@withContext "This file is too big (max ${info.maxFileMb} MB)."
                val tmp = File(cacheDir, "pick-${System.nanoTime()}")
                try {
                    contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ?: return@withContext "This file could not be read."
                    if (Math.round(tmp.length() / (1024.0 * 1024.0)) > info.maxFileMb) return@withContext "This file is too big (max ${info.maxFileMb} MB)."
                    sub = repo.addSubmissionFile(s.id, tmp, name, contentResolver.getType(uri) ?: "")
                    null
                } finally {
                    tmp.delete()
                }
            }
            if (result != null) Toast.makeText(this@AssignmentActivity, result, Toast.LENGTH_LONG).show()
            sub?.let { render(it) }
        }
    }

    private fun confirmSubmit() {
        val s = currentDraft()
        repo.submissionProblem(s, info)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Submit your assignment?")
            .setMessage("After submitting you cannot change this answer." +
                if (repo.isOnline()) "" else "\n\nYou are offline: it is kept on this phone and sent automatically when you are online.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Submit") { _, _ ->
                repo.submitAssignment(s)
                sub = repo.latestSubmission(assignmentId)
                sub?.let { render(it) }
            }.show()
    }

    override fun onPause() {
        super.onPause()
        // Saved while the student moves between apps or the phone locks.
        sub?.takeIf { it.state == SubmissionState.DRAFT }?.let { currentDraft() }
    }
}
