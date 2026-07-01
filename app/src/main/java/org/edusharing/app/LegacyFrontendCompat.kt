package org.edusharing.app

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * NOT part of the eduBridge contract (docs/android-app/eduBridge-contract.md) — this is a
 * transitional shim for version skew between this native shell and the server frontend it
 * loads. A server may still run the *old*, pre-eduBridge `CordovaService`, which detects
 * "running in the app" and receives shares via legacy Cordova globals instead of
 * `window.eduBridge`:
 *
 *  - `isReallyRunningCordova()` = `typeof window.cordova != 'undefined' || this.platform != null`
 *  - `isIOS()`/`isAndroid()` read `window.device.platform` directly (no `this.platform` fallback)
 *  - `this.platform` itself is only ever set from a `"ionic / edu-sharing-app"` User-Agent
 *    marker the old frontend expects — which this shell does not send (see
 *    `MainActivity.buildUserAgent`, which follows the current eduBridge-contract format
 *    instead).
 *  - `whenDeviceIsReady()` (which sets up the share-redirect subscription) only ever runs
 *    once `this.deviceIsReady` flips to `true`, which old `CordovaService` only does via a
 *    real `deviceready` DOM event or by finding `window.plugins` truthy.
 *  - Share content itself arrives via `window.plugins.intent.getCordovaIntent(cb)` /
 *    `.setNewIntentHandler(cb)` (the old `cordova-plugin-intent` fork), which then calls
 *    `this.router.navigate([...,'app','share'], {queryParams: data})`.
 *
 * Without this shim, an old frontend loaded by this shell never detects app mode, never
 * reaches `deviceready`, and never receives share content — the share-redirect simply never
 * fires.
 *
 * Deliberately minimal: this fakes just enough of the old `intent` object shape
 * (`.type`, `.extras['android.intent.extra.TEXT'|'STREAM']`, `.clipItems[0].file`, and a
 * `.stream` field carrying the base64 payload) for `handleIntentBase`/`AppSharePageComponent`
 * to work — it does NOT reimplement `resolveLocalFileSystemURL`/`NativeStorage`/etc.
 * `AppSharePageComponent` doesn't need that shim either: it already reads
 * `getLastIntent().stream` directly for file content (confirmed unchanged in this file across
 * the whole history — the "old" custom intent plugin apparently always attached `.stream`
 * itself), so `resolveLocalFileSystemURL`'s result only affects a loading spinner / title
 * update, which resolves via its error path even when left unimplemented.
 *
 * Safe/inert for an eduBridge-aware frontend: `registerOnShareContent()`'s `eduBridge` branch
 * returns before ever reaching the `window.plugins.intent`-based code this shim feeds, and
 * `isReallyRunningCordova()`/`isIOS()`/`isAndroid()` all check `this.platform` (set from
 * `window.eduBridge`) first. Repeatedly firing `deviceready` is likewise a no-op there since
 * `deviceIsReady` is already `true` by the time it matters.
 */
object LegacyFrontendCompat {
    private const val TAG = "LegacyFrontendCompat"

    // Casing matches the real cordova-plugin-device shape ('Android' / 'iOS'), since the old
    // CordovaService compares against those exact strings. window.plugins.intent buffers a
    // delivered share (pendingIntent) so getCordovaIntent/setNewIntentHandler see it
    // regardless of registration order. deviceready is redispatched for ~20s to cover Angular
    // bootstrap time, since a DOM event fired before the old CordovaService constructor has
    // registered its listener would otherwise simply be missed.
    private const val SCRIPT = """
        (function() {
            if (typeof window.cordova === 'undefined') {
                window.cordova = { platformId: 'android', __eduSharingCompatShim: true };
            }
            if (typeof window.device === 'undefined') {
                window.device = { platform: 'Android', __eduSharingCompatShim: true };
            }

            if (!window.plugins) { window.plugins = {}; }
            if (!window.plugins.intent) {
                var pendingIntent = null;
                var newIntentHandler = null;
                window.__eduSharingLegacyDeliverIntent = function(json) {
                    try {
                        pendingIntent = JSON.parse(json);
                        if (newIntentHandler) { newIntentHandler(pendingIntent); }
                    } catch (e) {}
                };
                window.plugins.intent = {
                    getCordovaIntent: function(cb) {
                        if (cb && pendingIntent) { cb(pendingIntent); }
                    },
                    setNewIntentHandler: function(cb) {
                        newIntentHandler = cb;
                        if (pendingIntent) { cb(pendingIntent); }
                    },
                };
            }

            var deviceReadyAttempts = 0;
            var deviceReadyTimer = setInterval(function() {
                document.dispatchEvent(new Event('deviceready'));
                if (++deviceReadyAttempts >= 100) { clearInterval(deviceReadyTimer); }
            }, 200);
        })();
    """

    /** Injects the shim before any page script runs, scoped to [allowedOrigin] only. */
    fun installIfSupported(webView: WebView, allowedOrigin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Old WebView without document-start injection support: skip. The page still
            // loads normally; only the legacy app-mode/share detection is unavailable there.
            Log.i(TAG, "DOCUMENT_START_SCRIPT unsupported; skipping legacy compat shim")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, setOf(allowedOrigin))
    }

    /** Pushes a SEND share to the legacy `window.plugins.intent` shim, if it's installed. */
    fun deliverShare(webView: WebView, payload: SharePayload) {
        if (payload.action != SharePayload.ACTION_SEND) return
        val quoted = JSONObject.quote(toLegacyIntentJson(payload))
        webView.evaluateJavascript(
            "if(window.__eduSharingLegacyDeliverIntent){window.__eduSharingLegacyDeliverIntent($quoted);}",
            null,
        )
    }

    /** Maps our [SharePayload] onto the old `cordova-plugin-intent` object shape. */
    private fun toLegacyIntentJson(payload: SharePayload): String {
        val isFile = payload.stream.isNotEmpty()
        val extras = JSONObject().apply {
            if (isFile) {
                put("android.intent.extra.STREAM", payload.uri)
            } else {
                put("android.intent.extra.TEXT", payload.text.ifEmpty { payload.uri })
            }
        }
        return JSONObject().apply {
            put("type", payload.mimetype)
            put("extras", extras)
            if (payload.fileName.isNotEmpty()) {
                put("clipItems", JSONArray().put(JSONObject().put("file", payload.fileName)))
            }
            if (isFile) {
                put("stream", payload.stream)
            }
        }.toString()
    }
}
