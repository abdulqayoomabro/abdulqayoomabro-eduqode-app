package com.eduqode.app.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assignments written on the phone. */
class AssignmentTest {

    @Test fun answerBecomesSafeParagraphs() {
        assertEquals("<p>First line<br>second line</p><p>New &lt;b&gt;para&lt;/b&gt; &amp; more</p>",
            OfflineLogic.answerHtml("  First line\nsecond line\n\n\nNew <b>para</b> & more  "))
        assertEquals("", OfflineLogic.answerHtml("   \n  "))
    }

    @Test fun fileNamesCannotLeaveTheFolder() {
        assertEquals("data.csv", OfflineLogic.safeFileName("../../data.csv"))
        assertEquals("report 1.pdf", OfflineLogic.safeFileName("C:\\Users\\x\\report 1.pdf"))
        assertEquals("_.._x", OfflineLogic.safeFileName("..\u0000..\u0001x"))
        assertEquals("file", OfflineLogic.safeFileName("..."))
        assertTrue(OfflineLogic.safeFileName("a".repeat(300)).length <= 80)
    }

    @Test fun readsAssignmentLimitsFromThePack() {
        val a = OfflineLogic.parseAssignment("""{"total_mark":10,"pass_mark":5,"max_files":2,"max_file_mb":5,"retry":true,"attempts":3,"offline_submit":true,"submitted":false}""")!!
        assertEquals(2, a.maxFiles); assertEquals(5, a.maxFileMb); assertTrue(a.offlineSubmit); assertFalse(a.submitted)
        assertNull(OfflineLogic.parseAssignment(""))
        assertFalse(OfflineLogic.parseAssignment("""{"total_mark":10}""")!!.offlineSubmit)
    }
}
