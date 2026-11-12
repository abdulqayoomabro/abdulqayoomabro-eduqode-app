package com.eduqode.app

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONObject
import java.util.Locale

/**
 * The app's language: English or Urdu (اردو).
 *
 * Text written in Kotlin goes through [tr] with the English text as the key, like WordPress's __():
 * assets/i18n/ur.json has the Urdu for each English text. Layout and menu text uses normal Android
 * resources (res/values-ur). The choice is saved on the phone ("" = the phone's language) and sent to
 * the website, so the website, the app's offline screens and its notifications use the same language.
 */
object I18n {
    private const val PREFS = "eduqode_lang"
    private const val KEY = "lang"
    private const val PUSH = "push_to_website"
    val CHOICES = listOf("" to "Phone language", "en" to "English", "ur" to "اردو")

    @Volatile private var urdu: Map<String, String> = emptyMap()

    /** "en" or "ur": the language the app is showing now. */
    @Volatile var language: String = "en"
        private set

    val isUrdu: Boolean get() = language == "ur"

    /** Called once when the app starts (also before background work runs). */
    fun init(context: Context) {
        val app = context.applicationContext
        val chosen = choice(app)
        language = resolve(chosen)
        if (language == "ur" && urdu.isEmpty()) urdu = load(app)
        // "" keeps what Android has (the phone's language, or the app language set in Android's settings).
        if (chosen.isNotEmpty()) applyToActivities(app, chosen)
    }

    /** "", "en" or "ur" as the user chose it. */
    fun choice(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /**
     * Saves a new choice. Returns true when the shown language changed (screens are recreated by AppCompat).
     * fromApp: chosen in the app, so the website in the app's browser is switched too on its next visit.
     */
    fun setChoice(context: Context, value: String, fromApp: Boolean = true): Boolean {
        val v = if (value == "en" || value == "ur") value else ""
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, v).putBoolean(PUSH, fromApp).apply()
        val before = language
        if (v.isEmpty()) applyToActivities(context.applicationContext, v)
        init(context)
        return before != language
    }

    /** The website still has to be told about a language chosen in the app (then cleared). */
    fun takeWebsitePush(context: Context): Boolean {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(PUSH, false)) return false
        p.edit().putBoolean(PUSH, false).apply()
        return true
    }

    private fun resolve(choice: String): String = when (choice) {
        "ur", "en" -> choice
        else -> {
            val android = AppCompatDelegate.getApplicationLocales()[0] ?: Resources.getSystem().configuration.locales[0]
            if (android?.language == "ur") "ur" else "en"
        }
    }

    private fun applyToActivities(context: Context, chosen: String) {
        // Layout direction (right to left) and res/values-ur follow this.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+: Android keeps the app's language itself (also works when no screen is open).
            val lm = context.getSystemService(android.app.LocaleManager::class.java)
            val wantList = if (chosen.isEmpty()) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(chosen)
            if (lm != null && lm.applicationLocales.toLanguageTags() != wantList.toLanguageTags()) lm.applicationLocales = wantList
            return
        }
        val want = if (chosen.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(chosen)
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != want.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(want)
        }
    }

    private fun load(context: Context): Map<String, String> = runCatching {
        val o = JSONObject(context.assets.open("i18n/ur.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** For tests: use a translation table without assets. */
    fun useForTest(lang: String, table: Map<String, String>) {
        language = lang
        urdu = table
    }

    fun t(text: String): String = if (language == "ur") urdu[text] ?: text else text
}

/**
 * Translated text. With arguments the text is a format ("%s", "%1\$s", "%d"); the Urdu may put the
 * arguments in another order with "%2\$s".
 */
fun tr(text: String, vararg args: Any?): String {
    val f = I18n.t(text)
    return if (args.isEmpty()) f else runCatching { String.format(Locale.US, f, *args) }.getOrElse { String.format(Locale.US, text, *args) }
}
