package org.edusharing.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hosts the remote edu-sharing SPA in a [WebView] and bridges Android share intents to it
 * via `window.eduBridge` (see docs/android-app/eduBridge-contract.md).
 *
 * The WebView loads the live server over HTTPS (same-origin), so login, REST and OAuth all
 * run through the SPA's normal HttpClient — this shell adds no network or storage layer.
 */
class MainActivity : AppCompatActivity(), EduBridge.Host {

    private lateinit var webView: WebView
    private lateinit var serverUrl: String

    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Cold-start share held until the SPA pulls it via getInitialShare() (pull model).
    @Volatile private var pendingShare: SharePayload? = null
    @Volatile private var coldShareDelivered = false
    // Signals that pendingShare (incl. base64 encoding) is ready; starts "open" (no share).
    @Volatile private var shareReady = CountDownLatch(0)

    private var backgroundedAt = 0L

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(uris ?: emptyArray())
            filePathCallback = null
        }

    private val serverUrlLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val url = result.data?.getStringExtra(ServerUrlActivity.EXTRA_SERVER_URL)
            if (result.resultCode == Activity.RESULT_OK && ServerConfig.isUsable(url)) {
                ServerConfig.setServerUrl(this, url!!)
                startShell()
            } else {
                // No server chosen -> nothing to load.
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, backCallback)

        val configured = ServerConfig.getServerUrl(this)
        if (ServerConfig.isUsable(configured)) {
            serverUrl = ServerConfig.normalize(configured!!)
            startShell()
        } else {
            // First launch: let the user choose a server before loading the SPA.
            serverUrlLauncher.launch(Intent(this, ServerUrlActivity::class.java))
        }
    }

    private fun startShell() {
        serverUrl = ServerConfig.normalize(ServerConfig.getServerUrl(this)!!)
        setupWebView()
        // A share may have cold-started us: capture it before the page asks for it.
        prepareColdShare(intent)
        webView.loadUrl(serverUrl)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webview)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage holds the OAuth token + server URL
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = buildUserAgent(userAgentString)
        }

        webView.addJavascriptInterface(
            EduBridge(this, contentResolver, BuildConfig.VERSION_NAME),
            EduBridge.NAME,
        )
        webView.webViewClient = shellWebViewClient
        webView.webChromeClient = shellChromeClient
        webView.setDownloadListener { url, _, _, _, _ -> openExternally(url) }
    }

    /**
     * Appends the app marker the SPA's platform detection relies on:
     * `<default UA> edu-sharing-app/<version> android`.
     */
    private fun buildUserAgent(base: String): String =
        "$base edu-sharing-app/${BuildConfig.VERSION_NAME} android"

    // ---- Share handling -----------------------------------------------------------------

    private fun isShareIntent(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW

    /** Reads a cold-start share (incl. base64) off the UI thread; held for getInitialShare(). */
    private fun prepareColdShare(intent: Intent?) {
        if (!isShareIntent(intent)) return
        val latch = CountDownLatch(1)
        shareReady = latch
        coldShareDelivered = false
        ioExecutor.execute {
            try {
                pendingShare = ShareIntentParser.parse(intent, contentResolver)
            } catch (e: Exception) {
                Log.w(TAG, "cold share parse failed", e)
                pendingShare = null
            } finally {
                latch.countDown()
            }
        }
    }

    /** Handles a share that arrives while the app is already running (warm start). */
    private fun handleWarmShare(intent: Intent?) {
        if (!isShareIntent(intent)) return
        ioExecutor.execute {
            val payload = try {
                ShareIntentParser.parse(intent, contentResolver)
            } catch (e: Exception) {
                Log.w(TAG, "warm share parse failed", e)
                null
            } ?: return@execute
            val quoted = JSONObject.quote(payload.toJson())
            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.eduBridgeOnShare){window.eduBridgeOnShare($quoted);}",
                    null,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWarmShare(intent)
    }

    // ---- EduBridge.Host (called on the WebView binder thread) ----------------------------

    override fun awaitInitialShareJson(): String {
        if (coldShareDelivered) return ""
        // Wait briefly for the background base64 encoding to finish.
        shareReady.await(SHARE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val payload = pendingShare ?: return ""
        coldShareDelivered = true
        return payload.toJson()
    }

    override fun onShareConsumed() {
        coldShareDelivered = true
        pendingShare = null
    }

    override fun onExitRequested() {
        runOnUiThread { finishAndRemoveTask() }
    }

    // ---- Lifecycle callbacks forwarded to the SPA ----------------------------------------

    override fun onPause() {
        super.onPause()
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Match the SPA's own rule: only treat >1 min in the background as a real resume.
        if (backgroundedAt > 0 &&
            System.currentTimeMillis() - backgroundedAt > RESUME_THRESHOLD_MILLIS &&
            ::webView.isInitialized
        ) {
            webView.evaluateJavascript(
                "if(window.eduBridgeOnResume){window.eduBridgeOnResume();}",
                null,
            )
        }
        backgroundedAt = 0
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!::webView.isInitialized) {
                finish()
                return
            }
            // Let the SPA decide (WebView history vs. exit); fall back to native behaviour.
            webView.evaluateJavascript(
                "(function(){if(window.eduBridgeOnBack){window.eduBridgeOnBack();return 'handled';}return 'unhandled';})()",
            ) { result ->
                if (result == null || result == "\"unhandled\"" || result == "null") {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        }
    }

    // ---- Navigation / chrome -------------------------------------------------------------

    private val shellWebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            // Keep same-origin (edu-sharing) navigation inside the WebView; hand the rest to
            // the system (mailto:, tel:, market:, external https, ...).
            if (url.scheme == "http" || url.scheme == "https") {
                return if (isSameHost(url)) false else openExternally(url.toString())
            }
            return openExternally(url.toString())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(
                "if(window.eduBridgeOnReady){window.eduBridgeOnReady();}",
                null,
            )
        }
    }

    private val shellChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: Exception) {
                Log.w(TAG, "file chooser failed", e)
                filePathCallback = null
                false
            }
        }
    }

    private fun isSameHost(url: Uri): Boolean {
        val serverHost = Uri.parse(serverUrl).host ?: return false
        return url.host.equals(serverHost, ignoreCase = true)
    }

    /** Opens a URL outside the WebView; returns true (URL was consumed by this shell). */
    private fun openExternally(url: String): Boolean {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "no app to open $url", e)
        }
        return true
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHARE_TIMEOUT_SECONDS = 15L
        private const val RESUME_THRESHOLD_MILLIS = 60_000L
    }
}
