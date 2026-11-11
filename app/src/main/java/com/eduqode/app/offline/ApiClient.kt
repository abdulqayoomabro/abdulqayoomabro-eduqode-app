package com.eduqode.app.offline

import android.content.Context
import android.os.Build
import com.eduqode.app.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the EduQode App Connector plugin (/wp-json/eduqode/v1/...).
 * All calls are blocking; call them from a background thread or coroutine (Dispatchers.IO).
 */
class ApiClient(context: Context) {

    companion object {
        val SITE: String = BuildConfig.SITE_URL
        private val REST = "$SITE/?rest_route=/eduqode/v1"
        val USER_AGENT = "EduQodeApp/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
    }

    /** HTTP error from the server. 401 = token no longer valid, 403 = no access, 404 = plugin missing. */
    class ApiException(val code: Int, message: String) : IOException(message)

    private val auth = AuthStore(context)

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

    private fun read(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "Server error $code"
            throw ApiException(code, msg)
        }
        return runCatching { JSONObject(text) }.getOrElse {
            // An HTML page instead of JSON usually means the plugin is not installed or a cache served a page.
            throw ApiException(404, "The EduQode App Connector plugin is not answering on the website.")
        }
    }

    private fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val token = auth.token ?: throw ApiException(401, "Please log in.")
        val conn = open("$REST$path&_=${System.currentTimeMillis()}", method)
        try {
            conn.setRequestProperty("X-EduQode-Token", token)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val json = read(conn)
            return json
        } catch (e: ApiException) {
            if (e.code == 401) auth.needsLogin = true
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Gets an app token using the website login cookies from the app's web view.
     * Returns false if the student is not logged in on the website.
     */
    fun obtainToken(cookieHeader: String): Boolean {
        val conn = open("$SITE/?eduqode_app=issue_token", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Cookie", cookieHeader)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val form = "device_id=" + URLEncoder.encode(auth.deviceId, "UTF-8") +
                "&device_name=" + URLEncoder.encode("${Build.MANUFACTURER} ${Build.MODEL}", "UTF-8")
            conn.outputStream.use { it.write(form.toByteArray()) }
            val json = try {
                read(conn)
            } catch (e: ApiException) {
                if (e.code == 401) return false
                throw e
            }
            val user = json.getJSONObject("user")
            auth.save(json.getString("token"), user.getLong("id"), user.optString("login"), user.optString("name"))
            return true
        } finally {
            conn.disconnect()
        }
    }

    fun revoke() {
        runCatching { call("/auth/revoke", "POST", JSONObject()) }
    }

    fun me(): JSONObject = call("/me")
    fun courses(): List<EnrolledCourse> = JsonParsers.enrolledCourses(call("/courses"))
    fun coursePackage(courseId: Long): CoursePackage = JsonParsers.coursePackage(call("/courses/$courseId/package"))
    fun sync(body: JSONObject): JsonParsers.SyncResponse = JsonParsers.syncResponse(call("/sync", "POST", body))
}
