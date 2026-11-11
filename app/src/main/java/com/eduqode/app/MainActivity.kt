package com.eduqode.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.eduqode.app.offline.CourseState
import com.eduqode.app.offline.OfflineRepository
import com.eduqode.app.offline.Session
import com.eduqode.app.offline.SyncWorker
import com.eduqode.app.ui.CourseDownloadActivity
import com.eduqode.app.ui.OfflineCourseActivity
import com.eduqode.app.ui.OfflineLibraryActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "https://lms.eduqode.com/"
        const val EXTRA_OPEN_URL = "open_url"
        const val APP_UA_TAG = "EduQodeApp/1.0"

        // Google does not allow its login inside a WebView, so login runs in a browser tab
        // and the website hands the session back to the app through eduqode://auth?token=...
        const val LOGIN_HANDOFF_URL = "https://lms.eduqode.com/?eduqode_app_handoff=1"
        private const val TOKEN_LOGIN_URL = "https://lms.eduqode.com/?eduqode_app_token="

        // Pages that belong to the website, not opened in an external app.
        private val OWN_HOSTS = setOf("lms.eduqode.com", "eduqode.com", "www.eduqode.com")
        private val OWN_ORIGINS = setOf("https://lms.eduqode.com", "https://eduqode.com", "https://www.eduqode.com")
    }

    // Blocked pages, page styling and the Google login switch come from GitHub (see RemoteConfig).
    private lateinit var remote: RemoteConfig
    private var styleScript: ScriptHandler? = null

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenHolder: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fabDownload: ExtendedFloatingActionButton
    private lateinit var offlineRepo: OfflineRepository
    private var enrolledRefreshed = false

    private var firstLoadDone = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the splash until the first page has started showing (max ~4s).
        val started = System.currentTimeMillis()
        splash.setKeepOnScreenCondition {
            !firstLoadDone && System.currentTimeMillis() - started < 4000
        }

        webView = findViewById(R.id.webview)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        fullscreenHolder = findViewById(R.id.fullscreen_holder)

        // Keep content clear of the status and navigation bars (Android 15+ draws edge-to-edge).
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        offlineRepo = OfflineRepository(this)
        bottomNav = findViewById(R.id.bottom_nav)
        fabDownload = findViewById(R.id.fab_download)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_offline -> { openOfflineLibrary(); false }
                R.id.nav_home -> { if (webView.url?.startsWith(HOME_URL) != true || webView.canGoBack()) webView.loadUrl(HOME_URL); true }
                else -> false
            }
        }
        bottomNav.setOnItemReselectedListener { if (it.itemId == R.id.nav_home) webView.loadUrl(HOME_URL) }
        // Offline progress sync: every few hours, and right away if something is waiting.
        SyncWorker.schedulePeriodic(this)
        if (offlineRepo.auth.isLoggedIn) SyncWorker.enqueueNow(this)

        remote = RemoteConfig(this)
        setupWebView()
        installPageStyle()
        remote.refresh {
            installPageStyle()
            injectPageStyle()
        }

        swipe.setOnRefreshListener { webView.reload() }
        // Only pull-to-refresh when the page itself is scrolled to the top.
        swipe.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        val authUrl = authUrlFrom(intent)
        val openUrl = openUrlFrom(intent)
        when {
            authUrl != null -> webView.loadUrl(authUrl)
            openUrl != null -> webView.loadUrl(openUrl)
            savedInstanceState != null -> webView.restoreState(savedInstanceState)
            else -> webView.loadUrl(HOME_URL)
        }
        if (intent?.data?.scheme == "eduqode" && intent?.data?.host == "offline") openOfflineLibrary()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        authUrlFrom(intent)?.let { webView.loadUrl(it) }
        openUrlFrom(intent)?.let { webView.loadUrl(it) }
        if (intent.data?.scheme == "eduqode" && intent.data?.host == "offline") openOfflineLibrary()
    }

    /** A page of the EduQode website to open (for example "Watch online" from an offline lesson). */
    private fun openUrlFrom(intent: Intent?): String? {
        val url = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        val host = Uri.parse(url).host ?: return null
        return url.takeIf { OWN_HOSTS.contains(host) }
    }

    private fun openOfflineLibrary() {
        startActivity(Intent(this, OfflineLibraryActivity::class.java))
    }

    /** After each page: link the website login to the app, and offer the offline pack on course pages. */
    private fun afterPageLoaded(url: String) {
        val host = Uri.parse(url).host ?: return
        if (!OWN_HOSTS.contains(host)) return
        lifecycleScope.launch {
            val state = Session.refresh(this@MainActivity)
            if (state is Session.State.LoggedIn && !enrolledRefreshed && offlineRepo.isOnline()) {
                enrolledRefreshed = runCatching { offlineRepo.refreshEnrolled() }.isSuccess
            }
            updateDownloadButton(url)
        }
    }

    private suspend fun updateDownloadButton(url: String) {
        if (!offlineRepo.auth.isLoggedIn || customView != null) {
            fabDownload.visibility = View.GONE
            return
        }
        val uid = offlineRepo.userId
        val course = withContext(Dispatchers.IO) { offlineRepo.db.courseByPermalink(uid, url.substringBefore('?').substringBefore('#')) }
        if (course == null) {
            fabDownload.visibility = View.GONE
            return
        }
        val downloaded = course.state == CourseState.DOWNLOADED
        fabDownload.text = if (downloaded) "Open offline" else getString(R.string.download_offline_pack)
        fabDownload.visibility = View.VISIBLE
        fabDownload.setOnClickListener {
            if (downloaded) {
                startActivity(Intent(this, OfflineCourseActivity::class.java).putExtra(OfflineCourseActivity.EXTRA_COURSE, course.id))
            } else {
                startActivity(Intent(this, CourseDownloadActivity::class.java).putExtra(CourseDownloadActivity.EXTRA_COURSE, course.id))
            }
        }
    }

    /** eduqode://auth?token=XYZ  ->  website URL that logs this app in with the one-time token. */
    private fun authUrlFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "eduqode" || data.host != "auth") return null
        val token = data.getQueryParameter("token")?.takeIf { it.matches(Regex("^[A-Za-z0-9]{20,64}$")) }
            ?: return null
        return TOKEN_LOGIN_URL + token
    }

    private fun isGoogleLogin(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val path = uri.path ?: ""
        return host == "accounts.google.com" ||
            (host.endsWith(".google.com") && (path.contains("oauth") || path.contains("signin")))
    }

    /** Uses the browser hand-off when it is switched on in remote/config.json, otherwise explains. */
    private fun startGoogleLogin() {
        val s = remote.settings
        if (s.googleLoginMode == "handoff") {
            openLoginInBrowser()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.google_login_title)
                .setMessage(s.googleLoginMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** JavaScript that adds (or updates) the app's <style> tag on EduQode pages. */
    private fun styleJs(): String {
        val css = JSONObject.quote(remote.settings.css)
        return """
            (function () {
              var id = 'eduqode-app-style';
              function apply() {
                var el = document.getElementById(id);
                if (!el) {
                  el = document.createElement('style');
                  el.id = id;
                  (document.head || document.documentElement).appendChild(el);
                }
                if (el.textContent !== $css) el.textContent = $css;
                if (document.body) document.body.classList.add('in-app');
              }
              if (document.documentElement) apply();
              document.addEventListener('DOMContentLoaded', apply);
            })();
        """.trimIndent()
    }

    /** Runs the style script at the very start of every EduQode page, so buy buttons never flash. */
    private fun installPageStyle() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        styleScript?.remove()
        styleScript = WebViewCompat.addDocumentStartJavaScript(webView, styleJs(), OWN_ORIGINS)
    }

    /** Fallback for older phones and for pages already open when new settings arrive. */
    private fun injectPageStyle() {
        val host = Uri.parse(webView.url ?: return).host ?: return
        if (OWN_HOSTS.contains(host)) webView.evaluateJavascript(styleJs(), null)
    }

    /** Opens the website's login hand-off page in a Chrome tab, where Google login is allowed. */
    private fun openLoginInBrowser() {
        val uri = Uri.parse(LOGIN_HANDOFF_URL)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (e: ActivityNotFoundException) {
            openExternally(uri)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            allowFileAccess = false
            // The website reads this tag to hide prices and buy buttons inside the app.
            userAgentString = "$userAgentString $APP_UA_TAG"
        }
        applyCacheMode()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return handleNavigation(request.url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                fabDownload.visibility = View.GONE
                // Logged out on the website: log the app out too (offline courses get locked).
                if (url.contains("action=logout") || url.contains("loggedout=true")) {
                    lifecycleScope.launch { Session.logout(this@MainActivity) }
                }
                // Catches redirects and form posts that skip shouldOverrideUrlLoading.
                if (isBlocked(Uri.parse(url))) {
                    view.stopLoading()
                    showBlockedMessage()
                    if (view.canGoBack()) view.goBack() else view.loadUrl(HOME_URL)
                    return
                }
                if (isGoogleLogin(Uri.parse(url))) {
                    view.stopLoading()
                    if (view.canGoBack()) view.goBack()
                    startGoogleLogin()
                    return
                }
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectPageStyle()
                afterPageLoaded(url)
                firstLoadDone = true
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    firstLoadDone = true
                    swipe.isRefreshing = false
                    progress.visibility = View.GONE
                    view.loadUrl("file:///android_asset/offline.html")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 60) firstLoadDone = true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Returns true when the app handled the link itself (blocked or sent outside). */
    private fun handleNavigation(uri: Uri): Boolean {
        if (isBlocked(uri)) {
            showBlockedMessage()
            return true
        }
        if (isGoogleLogin(uri)) {
            startGoogleLogin()
            return true
        }
        val scheme = uri.scheme ?: return false
        if (scheme == "http" || scheme == "https") {
            val host = uri.host ?: return false
            if (OWN_HOSTS.contains(host)) {
                applyCacheMode()
                return false
            }
            // Other websites (social links etc.) open outside the app.
            openExternally(uri)
            return true
        }
        if (scheme == "eduqode" && uri.host == "offline") {
            openOfflineLibrary()
            return true
        }
        // mailto:, tel:, whatsapp:, intent: and similar.
        openExternally(uri)
        return true
    }

    private fun isBlocked(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!OWN_HOSTS.contains(host)) return false
        val path = (uri.path ?: "").lowercase()
        val query = (uri.query ?: "").lowercase()
        val s = remote.settings
        return s.blockedPathParts.any { path.startsWith(it) || path.contains("$it/") } ||
            s.blockedQueryParts.any { query.contains(it) }
    }

    private fun showBlockedMessage() {
        Toast.makeText(this, R.string.blocked_page, Toast.LENGTH_LONG).show()
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** Use the saved copy of pages already visited when the phone is offline. */
    private fun applyCacheMode() {
        webView.settings.cacheMode =
            if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openExternally(Uri.parse(url))
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenHolder.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fullscreenHolder.visibility = View.VISIBLE
        swipe.visibility = View.GONE
        bottomNav.visibility = View.GONE
        fabDownload.visibility = View.GONE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenHolder.removeView(view)
        fullscreenHolder.visibility = View.GONE
        swipe.visibility = View.VISIBLE
        bottomNav.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
