package com.eduqode.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.eduqode.app.R
import com.eduqode.app.offline.OfflineLogic
import com.eduqode.app.offline.OfflineRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Plays a downloaded H5P exercise with no internet (open source h5p-standalone player).
 *
 * Lesson mode: every xAPI statement is saved on the phone and sent to Tutor LMS Pro when online.
 * Quiz mode: the score and statements go back to the quiz screen, which sends them with the attempt.
 */
class H5PActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COURSE = "course_id"
        const val EXTRA_LESSON = "lesson_id"        // lesson or quiz the exercise belongs to
        const val EXTRA_CONTENT = "content_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUIZ_MODE = "quiz_mode"

        const val RESULT_CONTENT = "result_content"
        const val RESULT_RAW = "result_raw"
        const val RESULT_MAX = "result_max"
        const val RESULT_OPENED = "result_opened"
        const val RESULT_STATEMENTS = "result_statements"

        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val MAX_STATEMENTS = 200
    }

    private lateinit var repo: OfflineRepository
    private var courseId = 0L
    private var ownerId = 0L
    private var contentId = 0L
    private var quizMode = false
    private val openedAt = System.currentTimeMillis()
    private val statements = ArrayList<String>()
    private var score: Pair<Double, Double>? = null
    private var lastSaved = 0L

    private val status get() = findViewById<TextView>(R.id.status)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_h5p)
        Ui.applyInsets(findViewById(R.id.root))
        repo = OfflineRepository(this)
        courseId = intent.getLongExtra(EXTRA_COURSE, 0)
        ownerId = intent.getLongExtra(EXTRA_LESSON, 0)
        contentId = intent.getLongExtra(EXTRA_CONTENT, 0)
        quizMode = intent.getBooleanExtra(EXTRA_QUIZ_MODE, false)
        if (!repo.auth.isLoggedIn || contentId <= 0) { finish(); return }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "Exercise"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        findViewById<MaterialButton>(R.id.btn_done).setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        if (quizMode) status.text = "Answer the exercise and tap Check, then Done."

        val loader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/offline/", WebViewAssetLoader.InternalStoragePathHandler(this, repo.userDir()))
            .build()
        findViewById<WebView>(R.id.player).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "EduQodeH5P")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == ASSET_HOST) return false
                    // Links inside an exercise open outside the app (only when online).
                    if (repo.isOnline()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    else Toast.makeText(this@H5PActivity, "This link needs an internet connection.", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
            val base = "/offline/c$courseId/h5p"
            loadUrl("https://$ASSET_HOST/assets/h5p-player/player.html?base=${Uri.encode(base)}&id=$contentId" +
                "&iri=${Uri.encode("${com.eduqode.app.offline.ApiClient.SITE}/?h5p_content=$contentId")}")
        }
    }

    /** Called by the player page (on a background thread). */
    inner class Bridge {
        @JavascriptInterface
        fun onStatement(json: String) {
            val st = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread { handle(st, json) }
        }

        @JavascriptInterface
        fun onReady() {}

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                status.text = "This exercise could not be opened. Download the course again when online."
                status.setTextColor(ContextCompat.getColor(this@H5PActivity, R.color.error_red))
            }
        }
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    private fun handle(st: JSONObject, raw: String) {
        if (isFinishing) return
        val final = OfflineLogic.h5pFinalScore(st)
        if (quizMode) {
            if (statements.size < MAX_STATEMENTS) statements += raw
            if (final != null && score == null) {
                score = final
                status.text = "Score ${fmt(final.first)}/${fmt(final.second)} · tap Done to continue the quiz"
            }
            return
        }
        // Lesson: saved right away, so nothing is lost if the app is closed.
        if (statements.size >= MAX_STATEMENTS && final == null) return
        statements += raw
        // Some exercises send many "interacted" statements per second; keep them but do not flood.
        val now = SystemClock.elapsedRealtime()
        if (final == null && now - lastSaved < 300 && st.optJSONObject("verb")?.optString("id")?.endsWith("/interacted") == true) return
        lastSaved = now
        lifecycleScope.launch {
            val finishedLesson = withContext(Dispatchers.IO) { repo.saveH5PStatement(courseId, ownerId, contentId, st, openedAt) }
            if (final != null) {
                score = final
                status.text = "Done · score ${fmt(final.first)}/${fmt(final.second)}" +
                    if (repo.isOnline()) " · saving to your account" else " · saved on this phone"
                status.setTextColor(ContextCompat.getColor(this@H5PActivity, R.color.ok_green))
                if (finishedLesson) {
                    Snackbar.make(findViewById(R.id.root), "All exercises in this lesson are done.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun close() {
        if (quizMode) {
            val s = score
            if (s != null) {
                setResult(RESULT_OK, Intent()
                    .putExtra(RESULT_CONTENT, contentId)
                    .putExtra(RESULT_RAW, s.first)
                    .putExtra(RESULT_MAX, s.second)
                    .putExtra(RESULT_OPENED, openedAt)
                    .putExtra(RESULT_STATEMENTS, JSONArray(statements).toString()))
            } else {
                setResult(RESULT_CANCELED)
            }
        }
        finish()
    }

    override fun onDestroy() {
        findViewById<WebView>(R.id.player)?.apply {
            removeJavascriptInterface("EduQodeH5P")
            destroy()
        }
        super.onDestroy()
    }
}
