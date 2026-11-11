package com.eduqode.app.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.eduqode.app.R
import com.eduqode.app.offline.Lesson
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Quiz
import com.eduqode.app.offline.QuizAnswer
import com.eduqode.app.offline.QuizEngine
import com.eduqode.app.offline.QuizQuestion
import com.eduqode.app.offline.QuizResponse
import com.eduqode.app.offline.QuizResult
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Takes a Tutor LMS quiz with no internet. The result shows immediately (graded with Tutor's rules);
 * the attempt is sent to the website when online, where Tutor grades it again and records it.
 */
class QuizActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
        const val EXTRA_QUIZ = "quiz_id"
    }

    private lateinit var repo: OfflineRepository
    private var courseId = 0L
    private lateinit var lesson: Lesson
    private lateinit var quiz: Quiz

    private var questions: List<QuizQuestion> = emptyList()
    private val shownAnswers = HashMap<Long, List<QuizAnswer>>()
    private val responses = HashMap<Long, QuizResponse>()
    private var index = 0
    private var startedAt = 0L
    private var inProgress = false
    private var timer: CountDownTimer? = null
    private var collect: (() -> QuizResponse?)? = null

    private val content get() = findViewById<LinearLayout>(R.id.content)
    private val left get() = findViewById<MaterialButton>(R.id.btn_left)
    private val right get() = findViewById<MaterialButton>(R.id.btn_right)
    private val toolbar get() = findViewById<MaterialToolbar>(R.id.toolbar)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)
        val l = repo.db.lesson(repo.userId, intent.getLongExtra(EXTRA_QUIZ, 0))
        val q = l?.quiz
        if (!repo.auth.isLoggedIn || l == null || q == null) { finish(); return }
        lesson = l; quiz = q
        toolbar.title = l.title
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!inProgress) { finish(); return }
                MaterialAlertDialogBuilder(this@QuizActivity)
                    .setTitle("Leave the quiz?")
                    .setMessage("Your answers in this attempt will be lost. It will not count as an attempt.")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Leave") { _, _ -> timer?.cancel(); finish() }.show()
            }
        })
        showIntro()
    }

    // ---------- helpers to build views ----------

    private fun text(s: CharSequence, style: Int = 0, color: Int = R.color.text_primary, top: Int = 0): TextView =
        TextView(this).apply {
            text = s
            if (style != 0) setTextAppearance(style)
            setTextColor(ContextCompat.getColor(context, color))
            setPadding(0, dp(top), 0, 0)
        }

    private fun card(build: LinearLayout.() -> Unit): MaterialCardView = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
        addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); build() })
    }

    private fun image(url: String): ImageView? {
        val file = repo.localFile(courseId, url) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 900) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            maxHeight = dp(220)
        }
    }

    private fun scrollTop() = findViewById<ScrollView>(R.id.scroll).post { findViewById<ScrollView>(R.id.scroll).scrollTo(0, 0) }

    private fun formatTime(seconds: Long) = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

    // ---------- intro ----------

    private fun showIntro() {
        inProgress = false
        findViewById<LinearProgressIndicator>(R.id.quiz_progress).visibility = View.GONE
        toolbar.subtitle = null
        content.removeAllViews()
        lesson = repo.db.lesson(repo.userId, lesson.id) ?: lesson
        val attemptsLeft = repo.quizAttemptsLeft(lesson)
        val total = QuizEngine.pickQuestions(quiz.copy(questionsOrder = "sorting")).size

        content.addView(text(lesson.title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall))
        content.addView(card {
            addView(text("Questions: $total"))
            addView(text("Time limit: " + if (quiz.timeLimitSeconds > 0) formatTime(quiz.timeLimitSeconds.toLong()) + " (min:sec)" else "none"))
            addView(text("Passing grade: ${fmt(quiz.passingGrade)}%"))
            addView(text("Attempts left: " + (attemptsLeft?.toString() ?: "unlimited")))
            addView(text("Works without internet. Your result shows right away; it is saved to your account when you are online.",
                0, R.color.text_secondary, 8))
        })

        val attempts = repo.db.quizAttempts(repo.userId, lesson.id)
        val serverLast = lesson.quizLastJson.takeIf { it.isNotEmpty() }?.let { runCatching { QuizEngine.attemptSummary(JSONObject(it)) }.getOrNull() }
        if (attempts.isNotEmpty() || serverLast != null) {
            content.addView(card {
                addView(text("Previous attempts", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall))
                attempts.take(5).forEach { a ->
                    val official = if (a.serverEarned != null) " · website: ${fmt(a.serverEarned)}/${fmt(a.serverTotal ?: a.total)}" else ""
                    val state = when (a.state) {
                        "pending" -> "waiting to sync"
                        "rejected" -> if (a.reason == "attempts_exceeded") "not saved: no attempts left" else "not saved (${a.reason})"
                        else -> "saved to your account"
                    }
                    addView(text("${Ui.date(this@QuizActivity, a.endedAt)} · ${fmt(a.earned)}/${fmt(a.total)} · ${label(a.result)}$official · $state",
                        0, R.color.text_secondary, 6))
                }
                if (attempts.isEmpty() && serverLast != null) {
                    addView(text("On the website: ${fmt(serverLast.earned)}/${fmt(serverLast.total)} · ${label(serverLast.result.ifBlank { serverLast.status })}",
                        0, R.color.text_secondary, 6))
                }
            })
        }

        left.visibility = View.GONE
        right.visibility = View.VISIBLE
        right.isEnabled = attemptsLeft != 0
        right.text = if (attemptsLeft == 0) "No attempts left" else "Start quiz"
        right.setOnClickListener { startQuiz() }
    }

    private fun label(result: String) = when (result) {
        "pass" -> "Passed"
        "fail" -> "Not passed"
        "pending", "review_required" -> "Waiting for teacher review"
        else -> result
    }

    // ---------- taking the quiz ----------

    private fun startQuiz() {
        questions = QuizEngine.pickQuestions(quiz)
        shownAnswers.clear()
        questions.forEach { shownAnswers[it.id] = QuizEngine.displayAnswers(it) }
        responses.clear()
        index = 0
        startedAt = System.currentTimeMillis()
        inProgress = true
        if (quiz.timeLimitSeconds > 0) {
            timer = object : CountDownTimer(quiz.timeLimitSeconds * 1000L, 1000) {
                override fun onTick(ms: Long) { toolbar.subtitle = "Time left ${formatTime(ms / 1000)}" }
                override fun onFinish() {
                    toolbar.subtitle = "Time is up"
                    saveCurrent()
                    finishQuiz()
                }
            }.start()
        }
        showQuestion()
    }

    private fun saveCurrent() {
        val q = questions.getOrNull(index) ?: return
        val r = collect?.invoke()
        if (r != null) responses[q.id] = r else responses.remove(q.id)
    }

    private fun showQuestion() {
        val q = questions[index]
        content.removeAllViews()
        findViewById<LinearProgressIndicator>(R.id.quiz_progress).apply {
            visibility = View.VISIBLE
            setProgressCompat((index + 1) * 100 / questions.size, true)
        }
        if (quiz.timeLimitSeconds == 0) toolbar.subtitle = "Question ${index + 1} of ${questions.size}"
        content.addView(text("Question ${index + 1} of ${questions.size} · ${fmt(q.mark)} mark(s)",
            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium, R.color.text_secondary))
        content.addView(text(q.title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, top = 6))
        if (q.descriptionHtml.isNotBlank() && q.type != "fill_in_the_blank") {
            content.addView(text(HtmlCompat.fromHtml(q.descriptionHtml, HtmlCompat.FROM_HTML_MODE_COMPACT).trim(), 0, R.color.text_secondary, 6))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        content.addView(box)
        collect = buildInput(q, box, responses[q.id])

        left.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        left.text = "‹ Previous"
        left.setOnClickListener { saveCurrent(); index--; showQuestion(); scrollTop() }
        right.visibility = View.VISIBLE
        right.isEnabled = true
        right.text = if (index == questions.size - 1) "Finish" else "Next ›"
        right.setOnClickListener {
            saveCurrent()
            if (index == questions.size - 1) confirmFinish() else { index++; showQuestion(); scrollTop() }
        }
    }

    /** Builds the answer controls for one question; returns a function that reads the answer. */
    private fun buildInput(q: QuizQuestion, box: LinearLayout, current: QuizResponse?): () -> QuizResponse? {
        val answers = shownAnswers[q.id] ?: q.answers
        when (q.type) {
            "true_false", "single_choice" -> {
                val group = RadioGroup(this)
                answers.forEach { a ->
                    group.addView(RadioButton(this).apply {
                        id = View.generateViewId(); tag = a.id; text = a.title; textSize = 16f
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        isChecked = (current as? QuizResponse.Choice)?.answerId == a.id
                    })
                    if (a.image.isNotEmpty()) image(a.image)?.let { group.addView(it) }
                }
                box.addView(group)
                return {
                    val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId)
                    (checked?.tag as? Long)?.let { QuizResponse.Choice(it) }
                }
            }
            "multiple_choice" -> {
                val boxes = answers.map { a ->
                    CheckBox(this).apply {
                        tag = a.id; text = a.title; textSize = 16f; setPadding(dp(4), dp(10), dp(4), dp(10))
                        isChecked = (current as? QuizResponse.Choices)?.answerIds?.contains(a.id) == true
                    }.also { cb ->
                        box.addView(cb)
                        if (a.image.isNotEmpty()) image(a.image)?.let { box.addView(it) }
                    }
                }
                box.addView(text("Select all correct answers.", 0, R.color.text_secondary, 4))
                return {
                    val ids = boxes.filter { it.isChecked }.map { it.tag as Long }.toSet()
                    if (ids.isEmpty()) null else QuizResponse.Choices(ids)
                }
            }
            "fill_in_the_blank" -> {
                val template = q.answers.firstOrNull()?.title ?: ""
                var n = 0
                val shown = Regex(Regex.escape(QuizEngine.BLANK)).replace(template) { n++; "_____ (${n})" }
                box.addView(text(shown, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge))
                val prev = (current as? QuizResponse.Blanks)?.values ?: emptyList()
                val fields = (0 until QuizEngine.blankCount(q)).map { i ->
                    EditText(this).apply {
                        hint = "Blank ${i + 1}"; setText(prev.getOrNull(i) ?: ""); inputType = InputType.TYPE_CLASS_TEXT
                        isSingleLine = true
                    }.also { box.addView(it) }
                }
                return {
                    val values = fields.map { it.text.toString() }
                    if (values.all { it.isBlank() }) null else QuizResponse.Blanks(values)
                }
            }
            "open_ended", "short_answer" -> {
                val field = EditText(this).apply {
                    hint = "Your answer"; setText((current as? QuizResponse.Text)?.value ?: "")
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    minLines = if (q.type == "open_ended") 5 else 2
                    gravity = Gravity.TOP
                }
                box.addView(field)
                box.addView(text("Your teacher checks this answer after it syncs.", 0, R.color.text_secondary, 4))
                return { field.text.toString().takeIf { it.isNotBlank() }?.let { QuizResponse.Text(it) } }
            }
            "ordering" -> {
                val order = ((current as? QuizResponse.Sequence)?.answerIds?.mapNotNull { id -> answers.firstOrNull { it.id == id } }
                    ?.takeIf { it.size == answers.size } ?: answers).toMutableList()
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun render() {
                    list.removeAllViews()
                    order.forEachIndexed { i, a ->
                        list.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, dp(4))
                            addView(text("${i + 1}.  ${a.title}", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                                text = "▲"; isEnabled = i > 0
                                setOnClickListener { order.add(i - 1, order.removeAt(i)); render() }
                            })
                            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                                text = "▼"; isEnabled = i < order.size - 1
                                setOnClickListener { order.add(i + 1, order.removeAt(i)); render() }
                            })
                        })
                    }
                }
                render()
                box.addView(text("Put the items in the right order.", 0, R.color.text_secondary))
                box.addView(list)
                return { QuizResponse.Sequence(order.map { it.id }) }
            }
            "matching", "image_matching" -> {
                // One slot per pair (in Tutor's order); pick the matching item for each slot.
                val slots = q.answers.sortedBy { it.order }
                val options = answers
                val prev = (current as? QuizResponse.Sequence)?.answerIds ?: emptyList()
                val spinners = slots.mapIndexed { i, slot ->
                    if (q.type == "image_matching" && slot.image.isNotEmpty()) {
                        image(slot.image)?.let { box.addView(it) } ?: box.addView(text("Picture ${i + 1}", top = 8))
                    } else {
                        box.addView(text(slot.match.ifBlank { "Item ${i + 1}" }, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, top = 10))
                    }
                    Spinner(this).apply {
                        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Choose…") + options.map { it.title })
                        val sel = prev.getOrNull(i)?.let { id -> options.indexOfFirst { it.id == id } } ?: -1
                        setSelection(if (sel >= 0) sel + 1 else 0)
                        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {}
                            override fun onNothingSelected(p: AdapterView<*>?) {}
                        }
                    }.also { box.addView(it) }
                }
                return {
                    val ids = spinners.map { s -> if (s.selectedItemPosition > 0) options[s.selectedItemPosition - 1].id else 0L }
                    if (ids.all { it == 0L }) null else QuizResponse.Sequence(ids)
                }
            }
            "image_answering" -> {
                val prev = (current as? QuizResponse.ImageTexts)?.values ?: emptyMap()
                val fields = q.answers.sortedBy { it.order }.map { a ->
                    if (a.image.isNotEmpty()) image(a.image)?.let { box.addView(it) }
                    a.id to EditText(this).apply {
                        hint = "What is in the picture?"; setText(prev[a.id] ?: ""); isSingleLine = true
                    }.also { box.addView(it) }
                }
                return {
                    val values = fields.associate { (id, f) -> id to f.text.toString() }
                    if (values.values.all { it.isBlank() }) null else QuizResponse.ImageTexts(values)
                }
            }
            else -> {
                box.addView(text("This question needs the website.", 0, R.color.error_red))
                return { null }
            }
        }
    }

    private fun confirmFinish() {
        val unanswered = questions.count { responses[it.id] == null }
        if (unanswered == 0) { finishQuiz(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Finish the quiz?")
            .setMessage("$unanswered question(s) are not answered.")
            .setNegativeButton("Go back", null)
            .setPositiveButton("Finish") { _, _ -> finishQuiz() }.show()
    }

    private fun finishQuiz() {
        if (!inProgress) return
        inProgress = false
        timer?.cancel()
        val result = QuizEngine.result(quiz, questions, responses)
        val id = repo.saveQuizAttempt(courseId, lesson.id, startedAt, questions, responses, result)
        showResult(result, id)
    }

    // ---------- result ----------

    private fun correctAnswerText(q: QuizQuestion): String = when (q.type) {
        "true_false", "single_choice", "multiple_choice" -> q.answers.filter { it.isCorrect }.joinToString(", ") { it.title }
        "fill_in_the_blank" -> q.answers.firstOrNull()?.match?.split("|")?.joinToString(", ") { it.trim() } ?: ""
        "ordering" -> q.answers.sortedBy { it.order }.joinToString(" → ") { it.title }
        "matching" -> q.answers.sortedBy { it.order }.joinToString("\n") { "${it.match} = ${it.title}" }
        "image_matching" -> q.answers.sortedBy { it.order }.joinToString(", ") { it.title }
        "image_answering" -> q.answers.sortedBy { it.order }.joinToString(", ") { it.title }
        else -> ""
    }

    private fun showResult(result: QuizResult, attemptId: String) {
        findViewById<LinearProgressIndicator>(R.id.quiz_progress).visibility = View.GONE
        toolbar.subtitle = "Result"
        content.removeAllViews()
        val color = when (result.result) { "pass" -> R.color.ok_green; "fail" -> R.color.error_red; else -> R.color.warn_orange }
        content.addView(card {
            addView(text(label(result.result), com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, color))
            addView(text("Score: ${fmt(result.earned)} / ${fmt(result.total)}  (${fmt(Math.round(result.percent * 10) / 10.0)}%)",
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, top = 4))
            addView(text("Passing grade: ${fmt(quiz.passingGrade)}%", 0, R.color.text_secondary, 2))
            val status = text("", 0, R.color.text_secondary, 8)
            addView(status)
            lifecycleScope.launch {
                while (true) {
                    val a = repo.db.quizAttempt(repo.userId, attemptId)
                    status.text = when (a?.state) {
                        "synced" -> "Saved to your account" + (a.serverEarned?.let { " · website score ${fmt(it)}/${fmt(a.serverTotal ?: result.total)}" } ?: "")
                        "rejected" -> if (a.reason == "attempts_exceeded") "Not saved: no attempts left on the website." else "Not saved on the website (${a.reason})."
                        else -> if (repo.isOnline()) "Saving to your account…" else "Saved on this phone. It will be sent when you are online."
                    }
                    if (a?.state != "pending") break
                    delay(1500)
                }
            }
        })
        questions.forEachIndexed { i, q ->
            val r = result.questions.first { it.questionId == q.id }
            content.addView(card {
                addView(text("${i + 1}. ${q.title}", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall))
                val (msg, c) = when {
                    r.needsReview -> "Teacher will check this answer" to R.color.warn_orange
                    q.type in QuizEngine.REVIEW_TYPES -> "Not answered" to R.color.error_red
                    r.correct -> "Correct · ${fmt(r.earned)}/${fmt(r.max)}" to R.color.ok_green
                    else -> "Wrong · 0/${fmt(r.max)}" to R.color.error_red
                }
                addView(text(msg, 0, c, 4))
                if (!r.correct && q.type !in QuizEngine.REVIEW_TYPES) {
                    addView(text("Correct answer: " + correctAnswerText(q), 0, R.color.text_secondary, 4).apply { setTypeface(typeface, Typeface.ITALIC) })
                }
                if (q.explanation.isNotBlank()) addView(text(q.explanation, 0, R.color.text_secondary, 4))
            })
        }
        left.visibility = View.VISIBLE
        left.text = "Done"
        left.setOnClickListener { finish() }
        lesson = repo.db.lesson(repo.userId, lesson.id) ?: lesson
        val again = repo.quizAttemptsLeft(lesson)
        right.visibility = if (again == 0) View.GONE else View.VISIBLE
        right.text = "Try again"
        right.isEnabled = true
        right.setOnClickListener { showIntro() }
        scrollTop()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
