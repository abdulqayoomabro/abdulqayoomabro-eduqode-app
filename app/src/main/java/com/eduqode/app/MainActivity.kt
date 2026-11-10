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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOME_URL = "https://lms.eduqode.com/"
        const val APP_UA_TAG = "EduQodeApp/1.0"

        // Pages that belong to the website, not opened in an external app.
        private val OWN_HOSTS = setOf("lms.eduqode.com", "eduqode.com", "www.eduqode.com")

        // Buying happens on the website only, never inside the app.
        private val BLOCKED_PATH_PARTS = listOf("/cart", "/checkout", "/basket", "/my-cart")
        private val BLOCKED_QUERY_PARTS = listOf("add-to-cart", "p=28301", "page_id=28301", "tutor_pay_now")
    }

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var fullscreenHolder: FrameLayout

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
            insets
        }

        setupWebView()

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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
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
                // Catches redirects and form posts that skip shouldOverrideUrlLoading.
                if (isBlocked(Uri.parse(url))) {
                    view.stopLoading()
                    showBlockedMessage()
                    if (view.canGoBack()) view.goBack() else view.loadUrl(HOME_URL)
                    return
                }
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
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
        // mailto:, tel:, whatsapp:, intent: and similar.
        openExternally(uri)
        return true
    }

    private fun isBlocked(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!OWN_HOSTS.contains(host)) return false
        val path = (uri.path ?: "").lowercase()
        val query = (uri.query ?: "").lowercase()
        return BLOCKED_PATH_PARTS.any { path.startsWith(it) || path.contains("$it/") } ||
            BLOCKED_QUERY_PARTS.any { query.contains(it) }
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
