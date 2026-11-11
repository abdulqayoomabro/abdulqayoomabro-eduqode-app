package com.eduqode.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App settings that can be changed without a new APK: Settings > EduQode App > App settings on the website.
 * The app ships with defaults in assets/remote/, downloads the latest settings on every start, saves them,
 * and uses the saved copy when offline. Only settings and page styling come from the website, never app code.
 */
class RemoteConfig(private val context: Context) {

    companion object {
        /** Settings > EduQode App > App settings on the website (EduQode App Connector 2.2+). */
        private val ENDPOINT = "${BuildConfig.SITE_URL.trimEnd('/')}/?rest_route=/eduqode/v1/app-config"
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
            val body = download()?.let { runCatching { JSONObject(it) }.getOrNull() }
            val config = body?.optJSONObject("config")?.toString()
            val css = body?.optString("css", null)
            // Only accept valid settings, so a mistake on the website cannot break the app.
            val configOk = config != null
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

    private fun download(): String? = runCatching {
        val conn = (URL("$ENDPOINT&_=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
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
