package org.edusharing.app

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * `window.eduBridge` — the JS→native half of the contract
 * (docs/android-app/eduBridge-contract.md §2). Every method is synchronous and returns a
 * String (JSON or primitive) or void.
 *
 * NOTE: `@JavascriptInterface` methods are invoked on a private WebView binder thread, not
 * the UI thread, so short blocking waits (see [Host.awaitInitialShareJson]) are acceptable
 * here but UI work must be marshalled back by the [host].
 */
class EduBridge(
    private val host: Host,
    private val resolver: ContentResolver,
    private val appVersion: String,
) {
    /** Callbacks the bridge needs from its hosting activity. */
    interface Host {
        /** Blocks (briefly) until the cold-start share is encoded, then returns its JSON or "". */
        fun awaitInitialShareJson(): String

        /** Marks the last share as processed so it is not re-delivered on resume. */
        fun onShareConsumed()

        /**
         * Legacy compat: called right before the shim navigates to the origin root because a
         * new share arrived while already on the share screen (see LegacyFrontendCompat's
         * post-delivery check) -- arms the most recently delivered share for re-push once the
         * freshly loaded root page finishes loading.
         */
        fun onReloadRedeliverRequested()

        /** Closes the app (UI thread). */
        fun onExitRequested()

        /**
         * Starts a native camera capture (UI thread) and delivers the result later via
         * `window.eduBridgeOnPhotoTaken` — never blocks the calling JS thread (see
         * eduBridge-contract.md §2, "Ausnahme vom Sync-Muster").
         */
        fun onTakePhotoRequested()
    }

    @JavascriptInterface
    fun getPlatform(): String = "android"

    @JavascriptInterface
    fun getAppVersion(): String = appVersion

    @JavascriptInterface
    fun getInitialShare(): String = try {
        host.awaitInitialShareJson()
    } catch (e: Exception) {
        Log.w(TAG, "getInitialShare failed", e)
        ""
    }

    @JavascriptInterface
    fun consumeShare() {
        host.onShareConsumed()
    }

    @JavascriptInterface
    fun requestReloadRedeliver() {
        host.onReloadRedeliverRequested()
    }

    @JavascriptInterface
    fun exitApp() {
        host.onExitRequested()
    }

    /** Optional: base64 of a content URI, read on demand if not delivered inline. */
    @JavascriptInterface
    fun readFile(uri: String): String {
        if (uri.isBlank()) return ""
        return try {
            val bytes = ShareIntentParser.readBytes(resolver, Uri.parse(uri))
            bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "readFile failed for $uri", e)
            ""
        }
    }

    /** Fire-and-forget: kicks off the native camera flow; result arrives via the callback. */
    @JavascriptInterface
    fun takePhoto() {
        try {
            host.onTakePhotoRequested()
        } catch (e: Exception) {
            Log.w(TAG, "takePhoto failed to start", e)
        }
    }

    companion object {
        /** The object name injected into the WebView: `window.eduBridge`. */
        const val NAME = "eduBridge"
        private const val TAG = "EduBridge"
    }
}
