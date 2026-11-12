package com.eduqode.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline H5P. The fixture is a real course pack from the plugin on WordPress + Tutor LMS 4.2.1 +
 * the H5P plugin 1.17.10 with a True/False exercise. For one correct answer Tutor LMS (with
 * Tutor LMS Pro's H5P rules) graded the H5P quiz "total 2, earned 1, pass" (tests/plugin/run_tests.sh),
 * and the phone must show the same.
 */
class H5PTest {

    private val pkg = JsonParsers.coursePackage(JSONObject(javaClass.classLoader!!.getResource("h5p_package_fixture.json")!!.readText()))
    private val lesson = pkg.lessons.first { it.type == "lesson" }
    private val quizLesson = pkg.lessons.first { it.type == "quiz" }
    private val quiz = quizLesson.quiz!!

    private fun statement(verb: String, raw: Double? = null, max: Double? = null, parent: Boolean = false) = JSONObject().apply {
        put("verb", JSONObject().put("id", "http://adlnet.gov/expapi/verbs/$verb"))
        if (parent) put("context", JSONObject().put("contextActivities", JSONObject().put("parent", org.json.JSONArray().put(JSONObject().put("id", "x")))))
        if (raw != null) put("result", JSONObject().put("score", JSONObject().put("raw", raw).put("max", max).put("min", 0)))
    }

    @Test fun parsesExerciseFromRealPackage() {
        assertEquals(1, lesson.h5p.size)
        val c = lesson.h5p[0]
        assertEquals("H5P.TrueFalse", c.library)
        assertEquals("H5P.TrueFalse", JSONObject(c.h5pJson).getString("mainLibrary"))
        assertEquals("false", JSONObject(c.contentJson).getString("correct"))
        assertTrue(c.libraries.containsAll(listOf("H5P.TrueFalse-1.6", "H5P.Question-1.4", "H5P.JoubelUI-1.3")))
        // Every library folder is listed once, for the whole course.
        assertEquals(c.libraries.toSet(), pkg.h5pLibraries.keys)
        assertTrue(pkg.h5pLibraries.getValue("H5P.TrueFalse-1.6").any { it.path == "H5P.TrueFalse-1.6/library.json" && it.size > 0 })
        assertFalse(pkg.h5pGate)
    }

    @Test fun parsesH5PQuizQuestion() {
        assertTrue(quizLesson.quizJson.isNotEmpty())
        val q = quiz.questions.single()
        assertEquals("h5p", q.type)
        assertEquals(lesson.h5p[0].id, q.h5pContent!!.id)
    }

    @Test fun lessonItemsKeepIdAndTitle() {
        val json = OfflineLogic.h5pItemsJson(listOf(H5PItem(5, "Sky", "H5P.TrueFalse"), H5PItem(9, "", "", offline = false)))
        val back = OfflineLogic.parseH5PItems(json)
        assertEquals(listOf(5L, 9L), back.map { it.id })
        assertEquals(listOf(true, false), back.map { it.offline })
        assertTrue(OfflineLogic.parseH5PItems("").isEmpty())
        assertTrue(OfflineLogic.parseH5PItems("not json").isEmpty())
    }

    @Test fun serverPathsStayInsideTheirFolder() {
        assertTrue(OfflineLogic.safeRelativePath("H5P.TrueFalse-1.6/library.json"))
        assertTrue(OfflineLogic.safeRelativePath("images/photo-1.png"))
        assertFalse(OfflineLogic.safeRelativePath("../../shared_prefs/x.xml"))
        assertFalse(OfflineLogic.safeRelativePath("/data/data/x"))
        assertFalse(OfflineLogic.safeRelativePath("a//b"))
        assertFalse(OfflineLogic.safeRelativePath("a\\..\\b"))
        assertFalse(OfflineLogic.safeRelativePath(""))
    }

    @Test fun onlyTopLevelAnsweredOrCompletedFinishesAnExercise() {
        assertEquals(1.0 to 1.0, OfflineLogic.h5pFinalScore(statement("answered", 1.0, 1.0)))
        assertEquals(3.0 to 5.0, OfflineLogic.h5pFinalScore(statement("completed", 3.0, 5.0)))
        assertNull(OfflineLogic.h5pFinalScore(statement("interacted")))
        assertNull(OfflineLogic.h5pFinalScore(statement("attempted")))
        assertNull(OfflineLogic.h5pFinalScore(statement("answered")))                       // no score
        assertNull(OfflineLogic.h5pFinalScore(statement("answered", 1.0, 1.0, parent = true))) // a sub-question
    }

    @Test fun h5pQuizSameResultAsTutor() {
        val q = quiz.questions.single()
        val r = QuizEngine.result(quiz, quiz.questions, mapOf(q.id to QuizResponse.H5P(q.h5pContent!!.id, 1.0, 1.0, 1L, listOf("{}"))))
        assertEquals(2.0, r.total, 0.0)    // question mark 1 + exercise max 1 (Tutor LMS Pro)
        assertEquals(1.0, r.earned, 0.0)
        assertEquals("pass", r.result)     // 50% and the quiz needs 50%
        assertTrue(r.questions.single().correct)
    }

    @Test fun h5pQuizWithUnansweredExerciseGetsNoMarks() {
        val r = QuizEngine.result(quiz, quiz.questions, emptyMap())
        assertEquals(0.0, r.total, 0.0)
        assertEquals(0.0, r.earned, 0.0)
        assertEquals("fail", r.result)
    }

    @Test fun h5pAnswerSentInTheFormatThePluginReads() {
        val q = quiz.questions.single()
        val resp = QuizResponse.H5P(q.h5pContent!!.id, 1.0, 1.0, 123L, listOf("""{"verb":{"id":"x"}}"""))
        val body = QuizEngine.attemptPayload(quizLesson.id, 100L, quiz.questions, mapOf(q.id to resp))
        val h = body.getJSONObject("answers").getJSONObject(q.id.toString()).getJSONObject("h5p")
        assertEquals(q.h5pContent!!.id, h.getLong("content_id"))
        assertEquals(123L, h.getLong("opened_at"))
        assertEquals("""{"verb":{"id":"x"}}""", h.getJSONArray("statements").getString(0))
        // Saved on the phone and read back (result screen after a restart).
        val back = QuizEngine.responsesFromJson(QuizEngine.responsesToJson(mapOf(q.id to resp)))
        assertEquals(resp, back[q.id])
        assertNotNull(back[q.id] as? QuizResponse.H5P)
    }
}
