package org.edusharing.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    // Identifies the current share so the legacy shim can de-dupe our re-pushes (see
    // LegacyFrontendCompat.deliverShare); a fresh value is minted per parsed share.
    @Volatile private var shareNonce: String = ""
    private val shareNonceCounter = AtomicLong(0)
    // Signals that pendingShare (incl. base64 encoding) is ready; starts "open" (no share).
    @Volatile private var shareReady = CountDownLatch(0)

    private var backgroundedAt = 0L

    // Set before loading a (possibly new) server; consumed in onPageFinished, see startShell().
    private var pendingHistoryClear = false

    // Path of the temp file the camera app is currently writing to, if a capture is in flight.
    // Persisted across process death (the camera app can outlive this activity's process).
    private var pendingPhotoFile: File? = null

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

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
            handlePhotoResult(captured)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate()/setContentView(): draws behind the system bars
        // consistently across API levels instead of relying on the deprecated
        // android:statusBarColor/navigationBarColor theme attributes, which are no-ops on
        // apps targeting API 35+ (edge-to-edge is enforced there) and would otherwise leave
        // unstyled bars ("stripes") at the top/bottom of the screen.
        // Force light (white) system bar icons: the reserved strip behind them always shows
        // chrome_theme_color (a dark blue), regardless of the system's light/dark setting, so
        // SystemBarStyle.auto's system-theme-based icon choice would risk poor contrast.
        val chromeColor = getColor(R.color.chrome_theme_color)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(chromeColor),
            navigationBarStyle = SystemBarStyle.dark(chromeColor),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, backCallback)
        savedInstanceState?.getString(STATE_PENDING_PHOTO_PATH)?.let {
            pendingPhotoFile = File(it)
        }

        val configured = ServerConfig.getServerUrl(this)
        if (ServerConfig.isUsable(configured)) {
            serverUrl = ServerConfig.normalize(configured!!)
            startShell()
        } else {
            // First launch: let the user choose a server before loading the SPA.
            serverUrlLauncher.launch(Intent(this, ServerUrlActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoFile?.let { outState.putString(STATE_PENDING_PHOTO_PATH, it.absolutePath) }
    }

    private fun startShell() {
        serverUrl = ServerConfig.normalize(ServerConfig.getServerUrl(this)!!)
        setupWebView()
        // A share may have cold-started us: capture it before the page asks for it.
        prepareColdShare(intent)
        // startShell() re-runs on the SAME WebView instance when the user switches servers
        // (ServerUrlActivity's reset flow) -- loadUrl() alone doesn't clear the WebView's own
        // back/forward history, so without this the hardware/gesture back button could
        // navigate into pages from the PREVIOUS server. Cleared once the new page has actually
        // loaded (see onPageFinished), not immediately after loadUrl(), which isn't reliable.
        pendingHistoryClear = true
        webView.loadUrl(serverUrl)
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webview)
        // We draw edge-to-edge (enableEdgeToEdge() in onCreate). Use a MARGIN, not padding, to
        // keep the system bars clear: WebView's internal Chromium compositor positions
        // `position: fixed` CSS content (e.g. the SPA's toolbar) relative to the WebView's own
        // drawing surface, ignoring Android-level View.setPadding() -- so a padded WebView
        // would still show fixed-position page content jammed under the status bar. A margin
        // actually moves the WebView's bounds, so its whole viewport (fixed content included)
        // starts below the status bar / gesture nav area. The reserved strip shows the root
        // layout's themed background (activity_main.xml) instead of empty/blended space.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = bars.top
            params.bottomMargin = bars.bottom
            params.leftMargin = bars.left
            params.rightMargin = bars.right
            view.layoutParams = params
            WindowInsetsCompat.CONSUMED
        }
        // setOnApplyWindowInsetsListener only fires on the NEXT insets dispatch -- if the
        // initial one already happened before this listener was attached (quite possible here,
        // since setupWebView() can run after the window's first layout pass), the callback
        // above would otherwise never run at all, leaving the WebView with no margin and
        // content still jammed under the status bar. Force an immediate redelivery.
        ViewCompat.requestApplyInsets(webView)
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

        // ---- eduBridge: the current, documented contract (eduBridge-contract.md) ----------
        webView.addJavascriptInterface(
            EduBridge(this, contentResolver, BuildConfig.VERSION_NAME),
            EduBridge.NAME,
        )

        // ---- Legacy compat: NOT part of the eduBridge contract, see LegacyFrontendCompat --
        // Only helps a server frontend that hasn't been updated to eduBridge yet detect "app
        // mode"; an eduBridge-aware frontend never reads window.cordova/window.device.
        LegacyFrontendCompat.installIfSupported(webView, originOf(serverUrl))

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

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }

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
                shareNonce = nextShareNonce()
                val diagnostic = ShareIntentParser.lastDiagnostic
                // Legacy compat (not part of eduBridge): if the page already finished loading
                // before parsing completed, push to the un-migrated frontend now; otherwise
                // onPageFinished handles it. See maybeDeliverLegacyColdShare().
                runOnUiThread {
                    maybeDeliverLegacyColdShare()
                    showShareDiagnostic(diagnostic)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cold share parse failed", e)
                pendingShare = null
            } finally {
                latch.countDown()
            }
        }
    }

    /**
     * Legacy compat (NOT eduBridge): pushes the cold-start share into
     * [LegacyFrontendCompat]'s `window.plugins.intent` buffer, re-pushing on every
     * [onPageFinished] as long as a share is pending. The shim de-dupes repeated pushes of the
     * same share (by [shareNonce]) so re-pushing doesn't re-navigate within a document; it only
     * re-delivers to a *freshly reloaded* document, which is exactly what's needed.
     *
     * A single push during the initial load (the original behaviour) was unreliable: it raced
     * the document-start shim script — instant for text/link shares, so it fired before
     * `window.__eduSharingLegacyDeliverIntent` even existed — and any push that did land was
     * wiped from the shim's per-document buffer by the SPA's startup redirects (login → default
     * location) before the old CordovaService's share subscription got around to reading it.
     * Re-pushing on every page load survives those reloads without relying on sessionStorage
     * (whose ~5MB quota can't hold a base64-encoded file).
     *
     * An eduBridge-aware frontend never reads this buffer (it pulls via `getInitialShare()`),
     * so the re-pushes are a harmless no-op there and stop once `getInitialShare()` consumes
     * (which flips [coldShareDelivered]).
     */
    private fun maybeDeliverLegacyColdShare() {
        if (coldShareDelivered || !::webView.isInitialized) return
        val payload = pendingShare ?: return
        LegacyFrontendCompat.deliverShare(webView, payload, shareNonce)
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
            val nonce = nextShareNonce()
            val diagnostic = ShareIntentParser.lastDiagnostic
            val quoted = JSONObject.quote(payload.toJson())
            runOnUiThread {
                webView.evaluateJavascript(
                    "if(window.eduBridgeOnShare){window.eduBridgeOnShare($quoted);}",
                    null,
                )
                // Legacy compat (not part of eduBridge): see prepareColdShare above.
                LegacyFrontendCompat.deliverShare(webView, payload, nonce)
                showShareDiagnostic(diagnostic)
            }
        }
    }

    /** A process-unique token for a single share, used by the legacy shim's re-push de-dup. */
    private fun nextShareNonce(): String = "share-${shareNonceCounter.incrementAndGet()}"

    /**
     * Debug-only: surfaces what happened when a share was parsed (bytes read, read error, or
     * "no stream") as a toast, so share failures can be diagnosed on-device without adb. No-op
     * in release builds.
     */
    private fun showShareDiagnostic(diagnostic: String) {
        if (!BuildConfig.DEBUG || diagnostic.isEmpty()) return
        Toast.makeText(this, "Share: $diagnostic", Toast.LENGTH_LONG).show()
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
        // Deliberately does NOT set coldShareDelivered here (only onShareConsumed() -- called
        // via the frontend's eduBridge.consumeShare(), which it currently never invokes -- does
        // that). The SPA's startup/login flow does full-page reloads: CordovaService bootstraps
        // fresh on each one and calls getInitialShare() again, so marking the share "delivered"
        // on the first call silently dropped it whenever a reload happened between that first
        // (possibly not-yet-final) page and the page that actually shows the share screen. This
        // mirrors the legacy shim's re-push-until-consumed behaviour (see
        // MainActivity.maybeDeliverLegacyColdShare / LegacyFrontendCompat).
        return payload.toJson()
    }

    override fun onShareConsumed() {
        coldShareDelivered = true
        pendingShare = null
        // Debug-only: confirms the SPA (either eduBridge's getInitialShare() or, for an
        // un-migrated frontend, the legacy shim -- see LegacyFrontendCompat) actually called
        // consumeShare(), i.e. native's hand-off succeeded. If sharing still fails despite this
        // toast appearing, the bug is downstream in that frontend's own handling, not in
        // delivery -- called on the WebView binder thread, so hop to the UI thread for the toast.
        runOnUiThread { showShareDiagnostic("consumeShare() empfangen -- Übergabe an Frontend ok") }
    }

    /**
     * exitApp() is shared by two unrelated flows in the SPA (both old and new CordovaService):
     * "close" on the login page (`AppLoginPageComponent.buttonExitApp()`), where the user
     * wants to pick a different server, not leave the app entirely; and "return to the
     * originating app" after finishing a share (`AppSharePageComponent`'s
     * `OnBackBehaviour.closeApp`), where actually finishing the activity is correct. The SPA
     * gives us no way to distinguish the two other than which screen is showing.
     */
    override fun onExitRequested() {
        runOnUiThread {
            if (isOnLoginPage()) {
                resetToServerSelection()
            } else {
                finishAndRemoveTask()
            }
        }
    }

    private fun isOnLoginPage(): Boolean {
        if (!::webView.isInitialized) return false
        val path = webView.url?.let { Uri.parse(it).path } ?: return false
        // The app-login-page route (components/app), not its components/app/share child.
        return path.trimEnd('/').endsWith("/components/app")
    }

    private fun resetToServerSelection() {
        ServerConfig.clear(this)
        serverUrlLauncher.launch(Intent(this, ServerUrlActivity::class.java))
    }

    override fun onTakePhotoRequested() {
        runOnUiThread { startCameraCapture() }
    }

    // ---- Camera capture (takePhoto) -------------------------------------------------------

    private fun startCameraCapture() {
        if (pendingPhotoFile != null) {
            // A capture is already in flight; CordovaService also guards against concurrent
            // calls, this is just the native-side backstop.
            return
        }
        try {
            val file = PhotoCapture.createTempImageFile(this)
            pendingPhotoFile = file
            takePhotoLauncher.launch(PhotoCapture.uriForFile(this, file))
        } catch (e: Exception) {
            Log.w(TAG, "failed to start camera capture", e)
            pendingPhotoFile?.let { PhotoCapture.deleteQuietly(it) }
            pendingPhotoFile = null
            deliverPhotoResult(PhotoResultPayload.failure("ERROR"))
        }
    }

    private fun handlePhotoResult(captured: Boolean) {
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (!captured || file == null) {
            file?.let { PhotoCapture.deleteQuietly(it) }
            deliverPhotoResult(PhotoResultPayload.failure("CANCELLED"))
            return
        }
        ioExecutor.execute {
            val payload = try {
                val base64 = PhotoCapture.readAsRotatedJpegBase64(file)
                if (base64 != null) {
                    PhotoResultPayload(success = true, fileName = file.name, stream = base64)
                } else {
                    PhotoResultPayload.failure("ERROR")
                }
            } catch (e: Exception) {
                Log.w(TAG, "reading captured photo failed", e)
                PhotoResultPayload.failure("ERROR")
            } finally {
                PhotoCapture.deleteQuietly(file)
            }
            deliverPhotoResult(payload)
        }
    }

    private fun deliverPhotoResult(payload: PhotoResultPayload) {
        val quoted = JSONObject.quote(payload.toJson())
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if(window.eduBridgeOnPhotoTaken){window.eduBridgeOnPhotoTaken($quoted);}",
                    null,
                )
            }
        }
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
            // Checks eduBridgeOnBack first (current contract), then falls back to legacy
            // compat's tracked 'backbutton' listener (see LegacyFrontendCompat) for an old,
            // not-yet-updated frontend -- NOT part of the eduBridge contract.
            webView.evaluateJavascript(
                "(function(){" +
                    "if(window.eduBridgeOnBack){window.eduBridgeOnBack();return 'handled';}" +
                    "if(window.__eduSharingHasBackButtonListener){document.dispatchEvent(new Event('backbutton'));return 'handled';}" +
                    "return 'unhandled';" +
                    "})()",
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
                if (isSameHost(url)) return false
                // Legacy compat (not part of eduBridge): old CordovaService.restartCordova()'s
                // non-eduBridge-aware fallback (login page's top-right cancel button ->
                // buttonLoginBack()) navigates to this fixed external host to "reset" the app.
                // Just reloading our server's root here would only re-show the same server's
                // login (its OAuth token was already cleared by restartCordova() before this
                // navigation) -- give it the same "pick a different server" behavior as
                // buttonExitApp() (see onExitRequested()) instead, for consistent close/cancel
                // behavior on this screen, rather than opening an unrelated external
                // app-registry page with no way back into the app.
                if (isLegacyAppRegistryResetUrl(url)) {
                    resetToServerSelection()
                    return true
                }
                return openExternally(url.toString())
            }
            return openExternally(url.toString())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            if (pendingHistoryClear) {
                pendingHistoryClear = false
                view.clearHistory()
            }
            // Re-push a not-yet-consumed cold-start share to the legacy shim: the SPA's startup
            // redirects reset the shim's per-document buffer, so one push during the initial
            // load isn't enough for an un-migrated frontend (see maybeDeliverLegacyColdShare).
            maybeDeliverLegacyColdShare()
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

    private fun isLegacyAppRegistryResetUrl(url: Uri): Boolean =
        url.host.equals("app-registry.edu-sharing.com", ignoreCase = true)

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
        private const val STATE_PENDING_PHOTO_PATH = "pending_photo_path"
    }
}
