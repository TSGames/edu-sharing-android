package org.edusharing.app

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * NOT part of the eduBridge contract (docs/android-app/eduBridge-contract.md) — this is a
 * transitional shim for version skew between this native shell and the server frontend it
 * loads. A server may still run the *old*, pre-eduBridge `CordovaService`, which detects
 * "running in the app" via legacy Cordova globals instead of `window.eduBridge`:
 *
 *  - `isReallyRunningCordova()` = `typeof window.cordova != 'undefined' || this.platform != null`
 *  - `isIOS()`/`isAndroid()` read `window.device.platform` directly (no `this.platform` fallback)
 *  - `this.platform` itself is only ever set from a `"ionic / edu-sharing-app"` User-Agent
 *    marker the old frontend expects — which this shell does not send (see
 *    `MainActivity.buildUserAgent`, which follows the current eduBridge-contract format
 *    instead).
 *
 * Without this shim, an old frontend loaded by this shell would conclude it is running in a
 * plain browser: `isRunningCordova()` -> false, breaking anything gated on it (e.g.
 * `onlyWeb` menu-entry filtering).
 *
 * This is deliberately the *minimal* shape needed for those checks to pass — it does NOT
 * reimplement any actual legacy Cordova plugin (no `window.plugins.intent`,
 * `resolveLocalFileSystemURL`, `NativeStorage`, ...). An old frontend still won't get share
 * handling from this shell; only "app mode" detection is faked.
 *
 * Safe/inert for an eduBridge-aware frontend: every legacy check above is only reached when
 * `this.platform` (set from `window.eduBridge`) is falsy, and `isReallyRunningCordova()`'s
 * `||` means the shim can only ever turn a `false` into `true`, never the reverse. A frontend
 * that has adopted eduBridge never reads `window.cordova`/`window.device` for anything.
 */
object LegacyFrontendCompat {
    // Casing matches the real cordova-plugin-device shape ('Android' / 'iOS'), since the old
    // CordovaService compares against those exact strings.
    private const val SCRIPT = """
        (function() {
            if (typeof window.cordova === 'undefined') {
                window.cordova = { platformId: 'android', __eduSharingCompatShim: true };
            }
            if (typeof window.device === 'undefined') {
                window.device = { platform: 'Android', __eduSharingCompatShim: true };
            }
        })();
    """

    private const val TAG = "LegacyFrontendCompat"

    /** Injects the shim before any page script runs, scoped to [allowedOrigin] only. */
    fun installIfSupported(webView: WebView, allowedOrigin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Old WebView without document-start injection support: skip. The page still
            // loads normally; only the legacy app-mode detection is unavailable there.
            Log.i(TAG, "DOCUMENT_START_SCRIPT unsupported; skipping legacy compat shim")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(allowedOrigin))
    }
}
