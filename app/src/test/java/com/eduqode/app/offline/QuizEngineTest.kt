package com.eduqode.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Offline quiz grading. The fixture is a real course pack from the plugin on WordPress + Tutor LMS 4.2.1.
 * For the same "all correct" answers, Tutor LMS itself graded 8 of 10 with "review required"
 * (see tests/plugin/run_tests.sh), and the phone must give the same result.
 */
class QuizEngineTest {

    private val pkg = JSONObject(javaClass.classLoader!!.getResource("quiz_package_fixture.json")!!.readText())
    private val items = (0 until pkg.getJSONArray("topics").length()).flatMap { t ->
        val arr = pkg.getJSONArray("topics").getJSONObject(t).getJSONArray("items")
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }
    private val quiz = QuizEngine.parse(items.first { it.getString("title") == "Offline Quiz" }.getJSONObject("quiz"))
    private fun q(type: String) = quiz.questions.first { it.type == type }

    private fun correctResponses(): Map<Long, QuizResponse> = quiz.questions.associate { q ->
        q.id to when (q.type) {
            "true_false", "single_choice" -> QuizResponse.Choice(q.answers.first { it.isCorrect }.id)
            "multiple_choice" -> QuizResponse.Choices(q.answers.filter { it.isCorrect }.map { it.id }.toSet())
            "fill_in_the_blank" -> QuizResponse.Blanks(listOf("Easy", " fun "))
            "ordering", "matching", "image_matching" -> QuizResponse.Sequence(q.answers.sortedBy { it.order }.map { it.id })
            "image_answering" -> QuizResponse.ImageTexts(q.answers.associate { it.id to "lahore" })
            else -> QuizResponse.Text("Because it is readable.")
        }
    }

    @Test fun parsesQuizFromRealPackage() {
        assertEquals(600, quiz.timeLimitSeconds)
        assertEquals(0, quiz.attemptsAllowed)
        assertEquals(50.0, quiz.passingGrade, 0.0)
        assertEquals(
            listOf("true_false", "single_choice", "multiple_choice", "fill_in_the_blank", "ordering", "matching", "image_answering", "short_answer"),
            quiz.questions.map { it.type }
        )
    }

    @Test fun sameResultAsTutorForCorrectAnswers() {
        val r = QuizEngine.result(quiz, quiz.questions, correctResponses())
        assertEquals(8.0, r.earned, 0.0)
        assertEquals(10.0, r.total, 0.0)
        assertEquals("pending", r.result) // short answer waits for the teacher, like Tutor's "review_required"
    }

    @Test fun singleAndTrueFalse() {
        assertTrue(QuizEngine.grade(q("single_choice"), QuizResponse.Choice(q("single_choice").answers.first { it.isCorrect }.id)).correct)
        assertFalse(QuizEngine.grade(q("single_choice"), QuizResponse.Choice(q("single_choice").answers.first { !it.isCorrect }.id)).correct)
        assertFalse(QuizEngine.grade(q("true_false"), null).correct)
    }

    @Test fun multipleChoiceNeedsExactlyTheCorrectSet() {
        val mc = q("multiple_choice")
        val right = mc.answers.filter { it.isCorrect }.map { it.id }.toSet()
        assertTrue(QuizEngine.grade(mc, QuizResponse.Choices(right)).correct)
        assertFalse(QuizEngine.grade(mc, QuizResponse.Choices(right.take(1).toSet())).correct)
        assertFalse(QuizEngine.grade(mc, QuizResponse.Choices(mc.answers.map { it.id }.toSet())).correct)
        assertEquals(2.0, QuizEngine.grade(mc, QuizResponse.Choices(right)).earned, 0.0)
    }

    @Test fun fillInTheBlankIgnoresCaseAndSpacesButNotOrder() {
        val f = q("fill_in_the_blank")
        assertEquals(2, QuizEngine.blankCount(f))
        assertTrue(QuizEngine.grade(f, QuizResponse.Blanks(listOf("EASY", "  fun"))).correct)
        assertFalse(QuizEngine.grade(f, QuizResponse.Blanks(listOf("fun", "easy"))).correct)
        assertFalse(QuizEngine.grade(f, QuizResponse.Blanks(listOf("easy"))).correct)
    }

    @Test fun orderingAndMatching() {
        val o = q("ordering")
        val right = o.answers.sortedBy { it.order }.map { it.id }
        assertTrue(QuizEngine.grade(o, QuizResponse.Sequence(right)).correct)
        assertFalse(QuizEngine.grade(o, QuizResponse.Sequence(right.reversed())).correct)
        val m = q("matching")
        assertTrue(QuizEngine.grade(m, QuizResponse.Sequence(m.answers.sortedBy { it.order }.map { it.id })).correct)
        assertFalse(QuizEngine.grade(m, QuizResponse.Sequence(m.answers.sortedBy { it.order }.map { it.id }.reversed())).correct)
    }

    @Test fun imageAnsweringIgnoresCase() {
        val ia = q("image_answering")
        assertTrue(QuizEngine.grade(ia, QuizResponse.ImageTexts(ia.answers.associate { it.id to " LAHORE " })).correct)
        assertFalse(QuizEngine.grade(ia, QuizResponse.ImageTexts(ia.answers.associate { it.id to "Karachi" })).correct)
    }

    @Test fun shortAnswerWaitsForTeacherOnlyWhenAnswered() {
        val sa = q("short_answer")
        assertTrue(QuizEngine.grade(sa, QuizResponse.Text("text")).needsReview)
        assertFalse(QuizEngine.grade(sa, null).needsReview)
        val noShort = correctResponses() - sa.id
        assertEquals("pass", QuizEngine.result(quiz, quiz.questions, noShort).result) // 8 of 10 = 80% >= 50%
    }

    @Test fun failsBelowPassingGrade() {
        val r = QuizEngine.result(quiz, quiz.questions, emptyMap())
        assertEquals(0.0, r.earned, 0.0)
        assertEquals("fail", r.result)
    }

    @Test fun payloadUsesTutorFormat() {
        val p = QuizEngine.attemptPayload(5, 1000, quiz.questions, correctResponses())
        val ans = p.getJSONObject("answers")
        assertEquals(q("single_choice").answers.first { it.isCorrect }.id, ans.getLong(q("single_choice").id.toString()))
        assertEquals(2, ans.getJSONArray(q("multiple_choice").id.toString()).length())
        assertTrue(ans.getJSONObject(q("ordering").id.toString()).has("answers"))
        assertTrue(ans.getJSONObject(q("image_answering").id.toString()).has("answer_id"))
        assertEquals(8, p.getJSONArray("question_ids").length())
    }

    @Test fun questionOrderAndLimit() {
        assertEquals(quiz.questions.sortedBy { it.order }, QuizEngine.pickQuestions(quiz.copy(questionsOrder = "sorting")))
        assertEquals(quiz.questions.sortedBy { it.order }.reversed(), QuizEngine.pickQuestions(quiz.copy(questionsOrder = "desc")))
        val three = QuizEngine.pickQuestions(quiz.copy(maxQuestions = 3, questionsOrder = "rand"), Random(1))
        assertEquals(3, three.size)
        assertEquals(3, three.map { it.id }.toSet().size)
    }

    @Test fun attemptsLeft() {
        assertNull(QuizEngine.attemptsLeft(quiz, 5))
        assertEquals(1, QuizEngine.attemptsLeft(quiz.copy(attemptsAllowed = 3, attemptsUsed = 1), 1))
        assertEquals(0, QuizEngine.attemptsLeft(quiz.copy(attemptsAllowed = 1, attemptsUsed = 0), 1))
    }

    @Test fun answersSurviveSaving() {
        val r = correctResponses()
        assertEquals(r, QuizEngine.responsesFromJson(QuizEngine.responsesToJson(r)))
    }

    @Test fun onlineOnlyQuizHasNoQuizData() {
        val h5p = items.first { it.getString("title") == "H5P Quiz" }
        assertTrue(h5p.getBoolean("online_only"))
        assertFalse(h5p.has("quiz"))
    }
}
