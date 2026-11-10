package com.eduqode.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App settings that can be changed from GitHub without a new APK.
 *
 * The app ships with defaults in assets/remote/. On every start it quietly downloads
 * remote/config.json and remote/inject.css from the GitHub repo, saves them, and uses
 * them from then on. Edit those two files on GitHub and every installed app picks
 * the change up on its next start (GitHub may take up to ~5 minutes to serve it).
 *
 * Only settings and page styling come from GitHub, never app code, as Google Play requires.
 */
class RemoteConfig(private val context: Context) {

    companion object {
        private const val BASE =
            "https://raw.githubusercontent.com/abdulqayoomabro/abdulqayoomabro-eduqode-app/main/remote/"
        private const val CONFIG = "config.json"
        private const val CSS = "inject.css"
    }

    data class Settings(
        val googleLoginMode: String,          // "handoff" = browser tab + website plugin, "off" = show a message
        val googleLoginMessage: String,
        val blockedPathParts: List<String>,
        val blockedQueryParts: List<String>,
        val css: String
    )

    @Volatile
    var settings: Settings = load()
        private set

    private val cacheDir = File(context.filesDir, "remote").apply { mkdirs() }

    private fun read(name: String): String {
        val cached = File(File(context.filesDir, "remote"), name)
        if (cached.exists()) {
            runCatching { return cached.readText() }
        }
        return context.assets.open("remote/$name").bufferedReader().use { it.readText() }
    }

    private fun load(): Settings = parse(read(CONFIG), read(CSS))

    private fun parse(configText: String, css: String): Settings {
        val json = runCatching { JSONObject(configText) }.getOrElse { JSONObject() }
        fun list(key: String, fallback: List<String>): List<String> {
            val arr = json.optJSONArray(key) ?: return fallback
            return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }
        return Settings(
            googleLoginMode = json.optString("googleLogin", "off"),
            googleLoginMessage = json.optString(
                "googleLoginMessage",
                "Google login is not available in the app yet. Please log in with your email and password."
            ),
            blockedPathParts = list("blockedPathParts", listOf("/cart", "/checkout")),
            blockedQueryParts = list("blockedQueryParts", listOf("add-to-cart")),
            css = css
        )
    }

    /** Downloads the latest settings in the background; calls [onChanged] on the main thread if anything changed. */
    fun refresh(onChanged: (Settings) -> Unit) {
        Thread {
            val config = download(CONFIG)
            val css = download(CSS)
            // Only accept a config that is valid JSON, so a typo on GitHub cannot break the app.
            val configOk = config != null && runCatching { JSONObject(config) }.isSuccess
            var changed = false
            if (configOk) changed = save(CONFIG, config!!) || changed
            if (css != null) changed = save(CSS, css) || changed
            if (changed) {
                val updated = load()
                settings = updated
                Handler(Looper.getMainLooper()).post { onChanged(updated) }
            }
        }.start()
    }

    private fun save(name: String, text: String): Boolean {
        val file = File(cacheDir, name)
        if (file.exists() && runCatching { file.readText() }.getOrNull() == text) return false
        file.writeText(text)
        return true
    }

    private fun download(name: String): String? = runCatching {
        val conn = (URL(BASE + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            useCaches = false
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.length < 200_000 }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
