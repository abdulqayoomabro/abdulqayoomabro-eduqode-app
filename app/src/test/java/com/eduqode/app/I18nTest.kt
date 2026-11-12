package com.eduqode.app

import com.eduqode.app.offline.OfflineLogic
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nTest {
    private val urdu: Map<String, String> by lazy {
        val o = JSONObject(File("src/main/assets/i18n/ur.json").readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }

    @After fun english() = I18n.useForTest("en", emptyMap())

    @Test fun englishIsTheKeyItself() {
        I18n.useForTest("en", urdu)
        assertEquals("Start quiz", tr("Start quiz"))
        assertEquals("Question 2 of 5", tr("Question %s of %s", 2, 5))
        assertEquals("Downloading… 40%", tr("Downloading… %s%%", 40))
    }

    @Test fun urduTextAndArgumentOrder() {
        I18n.useForTest("ur", urdu)
        assertEquals("کوئز شروع کریں", tr("Start quiz"))
        // Urdu puts the total first: "5 MB میں سے 1 MB".
        assertEquals("5 MB میں سے 1 MB · 4 MB باقی", tr("%s of %s · %s left", "1 MB", "5 MB", "4 MB"))
        assertEquals("ڈاؤن لوڈ ہو رہا ہے… 40%", tr("Downloading… %s%%", 40))
        // A text without Urdu stays English.
        assertEquals("Something new", tr("Something new"))
    }

    @Test fun everyUrduTextFormats() {
        I18n.useForTest("ur", urdu)
        for ((key, value) in urdu) {
            val n = Regex("%(\\d+\\$)?[sd]").findAll(key.replace("%%", "")).count()
            val args = Array<Any?>(n) { 7 }
            val out = tr(key, *args)
            assertFalse("Urdu for '$key' did not format: $value", n > 0 && out == String.format(java.util.Locale.US, key, *args))
        }
    }

    @Test fun urduLessonsAreRightToLeft() {
        assertTrue(OfflineLogic.isRtlText("سبق 1: اردو کے حروف"))
        assertFalse(OfflineLogic.isRtlText("Lesson 1: Python basics"))
        assertTrue(OfflineLogic.wrapHtml("پہلا سبق", "<p>یہ اردو میں ہے</p>").contains("dir=\"rtl\""))
        assertFalse(OfflineLogic.wrapHtml("First lesson", "<p>English</p>").contains("dir=\"rtl\""))
    }
}
