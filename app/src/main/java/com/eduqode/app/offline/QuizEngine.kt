package com.eduqode.app.offline

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class QuizAnswer(
    val id: Long,
    val title: String,
    val isCorrect: Boolean,
    val image: String,
    val match: String,
    val order: Int
)

data class QuizQuestion(
    val id: Long,
    val type: String,
    val title: String,
    val descriptionHtml: String,
    val explanation: String,
    val mark: Double,
    val order: Int,
    val randomize: Boolean,
    val answers: List<QuizAnswer>
)

data class QuizAttemptSummary(val attemptId: Long, val status: String, val total: Double, val earned: Double, val result: String)

data class Quiz(
    val timeLimitSeconds: Int,
    val attemptsAllowed: Int,           // 0 = unlimited
    val attemptsUsed: Int,              // on the website when the pack was made
    val passingGrade: Double,
    val maxQuestions: Int,
    val questionsOrder: String,
    val revealAnswers: Boolean,
    val questions: List<QuizQuestion>,
    val lastAttempt: QuizAttemptSummary?
)

/** A student's answer to one question. */
sealed class QuizResponse {
    data class Choice(val answerId: Long) : QuizResponse()                 // true/false, single choice
    data class Choices(val answerIds: Set<Long>) : QuizResponse()          // multiple choice
    data class Blanks(val values: List<String>) : QuizResponse()           // fill in the blanks
    data class Text(val value: String) : QuizResponse()                    // open ended, short answer
    data class Sequence(val answerIds: List<Long>) : QuizResponse()        // ordering, matching, image matching (per slot)
    data class ImageTexts(val values: Map<Long, String>) : QuizResponse()  // image answering
}

data class QuestionResult(val questionId: Long, val earned: Double, val max: Double, val correct: Boolean, val needsReview: Boolean)

data class QuizResult(
    val earned: Double,
    val total: Double,
    val percent: Double,
    val result: String,        // pass, fail, pending (teacher review)
    val questions: List<QuestionResult>
)

/**
 * Offline quiz rules. Grading follows Tutor LMS 4.2 (Quiz::manage_attempt_answers and
 * QuizModel::prepare_attempt_result) so the result on the phone matches the website.
 * The website grades the attempt again when it syncs; its result is the official one.
 */
object QuizEngine {

    const val BLANK = "{dash}"
    val REVIEW_TYPES = setOf("open_ended", "short_answer")
    val SEQUENCE_TYPES = setOf("ordering", "matching", "image_matching")

    // ---------- parsing ----------

    fun parse(json: JSONObject): Quiz {
        val qs = json.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until qs.length()).map { i ->
            val q = qs.getJSONObject(i)
            val ans = q.optJSONArray("answers") ?: JSONArray()
            QuizQuestion(
                id = q.getLong("id"), type = q.optString("type"), title = q.optString("title"),
                descriptionHtml = q.optString("description"), explanation = q.optString("explanation"),
                mark = q.optDouble("mark", 1.0), order = q.optInt("order"), randomize = q.optBoolean("randomize"),
                answers = (0 until ans.length()).map { j ->
                    val a = ans.getJSONObject(j)
                    QuizAnswer(a.getLong("id"), a.optString("title"), a.optBoolean("is_correct"), a.optString("image"),
                        a.optString("match"), a.optInt("order"))
                }
            )
        }
        val last = json.optJSONObject("last_attempt")?.let { attemptSummary(it) }
        return Quiz(
            timeLimitSeconds = json.optInt("time_limit_seconds"), attemptsAllowed = json.optInt("attempts_allowed"),
            attemptsUsed = json.optInt("attempts_used"), passingGrade = json.optDouble("passing_grade", 80.0),
            maxQuestions = json.optInt("max_questions"), questionsOrder = json.optString("questions_order", "rand"),
            revealAnswers = json.optBoolean("reveal_answers"), questions = questions, lastAttempt = last
        )
    }

    fun attemptSummary(a: JSONObject) = QuizAttemptSummary(
        a.optLong("attempt_id"), a.optString("status"), a.optDouble("total", 0.0), a.optDouble("earned", 0.0), a.optString("result")
    )

    // ---------- taking the quiz ----------

    /** Questions for one attempt: Tutor's order setting and "max questions" limit. */
    fun pickQuestions(quiz: Quiz, random: Random = Random.Default): List<QuizQuestion> {
        val sorted = quiz.questions.sortedBy { it.order }
        val ordered = when (quiz.questionsOrder) {
            "rand" -> sorted.shuffled(random)
            "desc" -> sorted.reversed()
            else -> sorted
        }
        return if (quiz.maxQuestions in 1 until ordered.size) ordered.take(quiz.maxQuestions) else ordered
    }

    /** Answer options in the order to show them. Ordering questions are always shuffled. */
    fun displayAnswers(q: QuizQuestion, random: Random = Random.Default): List<QuizAnswer> {
        val sorted = q.answers.sortedBy { it.order }
        return if (q.type == "ordering" || q.randomize) sorted.shuffled(random) else sorted
    }

    /** Number of blanks in a fill-in-the-blank question. */
    fun blankCount(q: QuizQuestion): Int =
        q.answers.firstOrNull()?.title?.split(BLANK)?.size?.minus(1)?.coerceAtLeast(1) ?: 1

    fun attemptsLeft(quiz: Quiz, localPending: Int): Int? =
        if (quiz.attemptsAllowed <= 0) null else (quiz.attemptsAllowed - quiz.attemptsUsed - localPending).coerceAtLeast(0)

    // ---------- grading (mirrors Tutor LMS) ----------

    private fun asciiLower(s: String) = buildString(s.length) { s.forEach { c -> append(if (c in 'A'..'Z') c + 32 else c) } }

    /** Like WordPress sanitize_text_field for plain text: no line breaks/tabs, single spaces, trimmed. */
    fun cleanText(s: String) = s.replace(Regex("<[^>]*>"), "").replace(Regex("[\\r\\n\\t ]+"), " ").trim()

    fun grade(q: QuizQuestion, r: QuizResponse?): QuestionResult {
        val correct: Boolean = when (q.type) {
            "true_false", "single_choice" -> {
                val id = (r as? QuizResponse.Choice)?.answerId
                q.answers.any { it.id == id && it.isCorrect }
            }
            "multiple_choice" -> {
                val given = (r as? QuizResponse.Choices)?.answerIds ?: emptySet()
                given == q.answers.filter { it.isCorrect }.map { it.id }.toSet()
            }
            "fill_in_the_blank" -> {
                val gaps = q.answers.firstOrNull()?.match?.split("|")?.map { asciiLower(it.trim()) } ?: emptyList()
                val given = ((r as? QuizResponse.Blanks)?.values ?: emptyList()).map { asciiLower(cleanText(it)) }
                gaps.isNotEmpty() && given == gaps
            }
            in SEQUENCE_TYPES -> {
                val given = (r as? QuizResponse.Sequence)?.answerIds ?: emptyList()
                given == q.answers.sortedBy { it.order }.map { it.id }
            }
            "image_answering" -> {
                val given = (r as? QuizResponse.ImageTexts)?.values ?: emptyMap()
                q.answers.all { a -> (given[a.id] ?: "").trim().equals(a.title.trim(), ignoreCase = true) }
            }
            else -> false
        }
        if (q.type in REVIEW_TYPES) {
            val text = (r as? QuizResponse.Text)?.value ?: ""
            return QuestionResult(q.id, 0.0, q.mark, correct = false, needsReview = text.replace(Regex("<[^>]*>"), "").isNotBlank())
        }
        return QuestionResult(q.id, if (correct) q.mark else 0.0, q.mark, correct, needsReview = false)
    }

    fun result(quiz: Quiz, questions: List<QuizQuestion>, responses: Map<Long, QuizResponse>): QuizResult {
        val graded = questions.map { grade(it, responses[it.id]) }
        val total = graded.sumOf { it.max }
        val earned = graded.sumOf { it.earned }.coerceAtLeast(0.0)
        val percent = if (total > 0) earned * 100 / total else 0.0
        val result = when {
            graded.any { it.needsReview } -> "pending"
            percent >= quiz.passingGrade.toInt() -> "pass"
            else -> "fail"
        }
        return QuizResult(earned, total, percent, result, graded)
    }

    // ---------- answers in Tutor LMS's own format (sent with the sync) ----------

    fun tutorValue(q: QuizQuestion, r: QuizResponse): Any = when (r) {
        is QuizResponse.Choice -> r.answerId
        is QuizResponse.Choices -> JSONArray(r.answerIds.toList())
        is QuizResponse.Blanks -> JSONArray(r.values)
        is QuizResponse.Text -> r.value
        is QuizResponse.Sequence -> JSONObject().put("answers", JSONArray(r.answerIds))
        is QuizResponse.ImageTexts -> JSONObject().put("answer_id", JSONObject().apply { r.values.forEach { (k, v) -> put(k.toString(), v) } })
    }

    fun attemptPayload(quizId: Long, startedAt: Long, questions: List<QuizQuestion>, responses: Map<Long, QuizResponse>): JSONObject {
        val answers = JSONObject()
        questions.forEach { q -> responses[q.id]?.let { answers.put(q.id.toString(), tutorValue(q, it)) } }
        return JSONObject()
            .put("quiz_id", quizId)
            .put("started_at", startedAt)
            .put("question_ids", JSONArray(questions.map { it.id }))
            .put("answers", answers)
    }

    // ---------- saving a half-done attempt / showing results again ----------

    fun responsesToJson(responses: Map<Long, QuizResponse>): JSONObject = JSONObject().apply {
        responses.forEach { (id, r) ->
            put(id.toString(), when (r) {
                is QuizResponse.Choice -> JSONObject().put("k", "c").put("v", r.answerId)
                is QuizResponse.Choices -> JSONObject().put("k", "m").put("v", JSONArray(r.answerIds.toList()))
                is QuizResponse.Blanks -> JSONObject().put("k", "b").put("v", JSONArray(r.values))
                is QuizResponse.Text -> JSONObject().put("k", "t").put("v", r.value)
                is QuizResponse.Sequence -> JSONObject().put("k", "s").put("v", JSONArray(r.answerIds))
                is QuizResponse.ImageTexts -> JSONObject().put("k", "i").put("v", JSONObject().apply { r.values.forEach { (k, v) -> put(k.toString(), v) } })
            })
        }
    }

    fun responsesFromJson(json: JSONObject): Map<Long, QuizResponse> {
        val out = HashMap<Long, QuizResponse>()
        json.keys().forEach { key ->
            val id = key.toLongOrNull() ?: return@forEach
            val o = json.getJSONObject(key)
            out[id] = when (o.getString("k")) {
                "c" -> QuizResponse.Choice(o.getLong("v"))
                "m" -> o.getJSONArray("v").let { a -> QuizResponse.Choices((0 until a.length()).map { a.getLong(it) }.toSet()) }
                "b" -> o.getJSONArray("v").let { a -> QuizResponse.Blanks((0 until a.length()).map { a.getString(it) }) }
                "t" -> QuizResponse.Text(o.getString("v"))
                "s" -> o.getJSONArray("v").let { a -> QuizResponse.Sequence((0 until a.length()).map { a.getLong(it) }) }
                else -> o.getJSONObject("v").let { m -> QuizResponse.ImageTexts(m.keys().asSequence().associate { it.toLong() to m.getString(it) }) }
            }
        }
        return out
    }
}
