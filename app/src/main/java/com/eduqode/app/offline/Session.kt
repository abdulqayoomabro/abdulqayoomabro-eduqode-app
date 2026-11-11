package com.eduqode.app.offline

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Links the website login (web view cookies) with the app's token for offline features.
 * The student logs in once on the website inside the app; the app then gets its own token.
 */
object Session {

    sealed class State {
        object LoggedIn : State()
        object NotLoggedIn : State()
        object Offline : State()                       // no internet, and no token yet
        data class Error(val message: String) : State()
    }

    /** The WordPress username in the web view's login cookie, or null if not logged in on the website. */
    fun cookieLogin(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return cookieHeader.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("wordpress_logged_in_") }
            ?.substringAfter('=')
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.substringBefore('|')
            ?.takeIf { it.isNotBlank() }
    }

    private fun cookies(): String? = runCatching { CookieManager.getInstance().getCookie(ApiClient.SITE) }.getOrNull()

    /** Call on the main thread (reads web view cookies), does network work in the background. */
    suspend fun refresh(context: Context): State {
        val repo = OfflineRepository(context)
        val auth = repo.auth
        val cookie = cookies()
        val login = cookieLogin(cookie)
        val online = repo.isOnline()

        // Same student already has a working token.
        if (auth.isLoggedIn && !auth.needsLogin && (login == null || login.equals(auth.userLogin, ignoreCase = true))) {
            return State.LoggedIn
        }
        if (!online) return if (auth.isLoggedIn) State.LoggedIn else State.Offline
        if (login == null || cookie == null) return if (auth.isLoggedIn && !auth.needsLogin) State.LoggedIn else State.NotLoggedIn

        return withContext(Dispatchers.IO) {
            try {
                if (repo.api.obtainToken(cookie)) {
                    SyncWorker.enqueueNow(context)
                    State.LoggedIn
                } else {
                    State.NotLoggedIn
                }
            } catch (e: ApiClient.ApiException) {
                if (e.code == 404) State.Error("Please ask the site admin to install the EduQode App Connector plugin (version 2).")
                else State.Error(e.message ?: "Could not connect")
            } catch (e: Exception) {
                if (auth.isLoggedIn) State.LoggedIn else State.Error("Could not connect. Check your internet.")
            }
        }
    }

    /** Student logged out on the website: log the app out too (downloads stay, but are locked). */
    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        val repo = OfflineRepository(context)
        if (repo.auth.token != null && repo.isOnline()) repo.api.revoke()
        repo.auth.clear()
    }
}
