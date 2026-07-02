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
 *  - Old `CordovaService.exitApp()` and `onBackKeyDown()` (invoked e.g. by the login page's
 *    close button, `AppLoginPageComponent.buttonExitApp()`) call `navigator.app.exitApp()`
 *    directly, with no `window.eduBridge` check — so it silently no-ops without this shim.
 *    `onBackKeyDown()` itself only runs when the old code's hardware-back handling (a
 *    `'backbutton'` DOM event, normally fired by real Cordova core) actually fires.
 *  - Old `CordovaService.getPhotoFromCamera()` (used e.g. by the create-menu's camera option,
 *    `CreateMenuComponent.openCamera()`) calls `navigator.camera.getPicture(...)` after a
 *    `cordova.plugins.permissions` check, neither of which exist here — the permission check
 *    throws (caught, silently swallowed), so the whole call has no visible effect.
 *
 * Without this shim, an old frontend loaded by this shell never detects app mode, never
 * reaches `deviceready`, never receives share content, its close/back button silently does
 * nothing, and taking a photo has no effect.
 *
 * OAuth token storage needs no shim at all: old `getPermanentStorage`/`setPermanentStorage`
 * use `window.localStorage` unconditionally on Android (the `NativeStorage` plugin fallback
 * is gated behind `isIOS()`, which is `false` here) — this shell's real WebView
 * `localStorage` already satisfies that path.
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
 * returns before ever reaching the `window.plugins.intent`-based code this shim feeds,
 * `getPhotoFromCamera()`'s `eduBridge?.takePhoto` branch likewise returns before ever touching
 * `navigator.camera`, and `isReallyRunningCordova()`/`isIOS()`/`isAndroid()` all check
 * `this.platform` (set from `window.eduBridge`) first. Repeatedly firing `deviceready` is
 * likewise a no-op there since `deviceIsReady` is already `true` by the time it matters.
 */
object LegacyFrontendCompat {
    private const val TAG = "LegacyFrontendCompat"

    // Casing matches the real cordova-plugin-device shape ('Android' / 'iOS'), since the old
    // CordovaService compares against those exact strings. window.plugins.intent buffers a
    // delivered share (pendingIntent) so getCordovaIntent/setNewIntentHandler see it
    // regardless of registration order. When the share is actually handed to the old
    // CordovaService, we clear the buffer (so it can't double-fire) and call
    // eduBridge.consumeShare() so the native shell stops re-pushing it on every page load
    // (see MainActivity.maybeDeliverLegacyColdShare -- the cold-start share is re-delivered
    // until consumed, because the SPA's startup redirects wipe this per-document buffer).
    // deviceready is redispatched for ~20s to cover Angular bootstrap time, since a DOM event
    // fired before the old CordovaService constructor has registered its listener would
    // otherwise simply be missed.
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
                var takePending = function() {
                    var intent = pendingIntent;
                    pendingIntent = null;
                    // Tell native the cold-start share was picked up so it stops re-pushing.
                    // consumeShare() runs synchronously, so coldShareDelivered is already set
                    // by the time this returns -- no re-push races this handoff.
                    try {
                        if (window.eduBridge && window.eduBridge.consumeShare) {
                            window.eduBridge.consumeShare();
                        }
                    } catch (e) {}
                    return intent;
                };
                window.__eduSharingLegacyDeliverIntent = function(json) {
                    try {
                        pendingIntent = JSON.parse(json);
                    } catch (e) { return; }
                    if (newIntentHandler && pendingIntent) { newIntentHandler(takePending()); }
                };
                window.plugins.intent = {
                    getCordovaIntent: function(cb) {
                        if (cb && pendingIntent) { cb(takePending()); }
                    },
                    setNewIntentHandler: function(cb) {
                        newIntentHandler = cb;
                        if (pendingIntent) { cb(takePending()); }
                    },
                };
            }

            var deviceReadyAttempts = 0;
            var deviceReadyTimer = setInterval(function() {
                document.dispatchEvent(new Event('deviceready'));
                if (++deviceReadyAttempts >= 100) { clearInterval(deviceReadyTimer); }
            }, 200);

            // navigator.app.exitApp()/backHistory() -- old CordovaService.exitApp() and its
            // onBackKeyDown() call these directly (no window.eduBridge check). Route them to
            // the real, already-injected eduBridge native binding.
            if (!navigator.app) {
                navigator.app = {
                    exitApp: function() {
                        if (window.eduBridge && window.eduBridge.exitApp) {
                            window.eduBridge.exitApp();
                        }
                    },
                    backHistory: function() { history.back(); },
                };
            }

            // Old CordovaService listens for a custom 'backbutton' DOM event (normally fired
            // by the real Cordova core on the hardware/gesture back press) to run its own
            // back/close logic (including the exitApp() path above after finishing a share).
            // Track whether it actually registered a listener so MainActivity's back handler
            // knows whether dispatching this event will do anything.
            var realAddEventListener = document.addEventListener.bind(document);
            document.addEventListener = function(type, listener, options) {
                if (type === 'backbutton') { window.__eduSharingHasBackButtonListener = true; }
                return realAddEventListener(type, listener, options);
            };

            // navigator.camera.getPicture()/cordova.plugins.permissions -- old
            // CordovaService.getPhotoFromCamera() (e.g. the create-menu's camera option) calls
            // these directly. Route the actual capture through the real, already-injected
            // eduBridge.takePhoto() native binding; the permission stub always reports granted
            // since native capture delegates to the system camera app and needs no runtime
            // permission of its own.
            if (!window.cordova.plugins) { window.cordova.plugins = {}; }
            if (!window.cordova.plugins.permissions) {
                window.cordova.plugins.permissions = {
                    CAMERA: 'android.permission.CAMERA',
                    checkPermission: function(perm, successCb) {
                        if (successCb) { successCb({ hasPermission: true }); }
                    },
                    requestPermission: function(perm, successCb) {
                        if (successCb) { successCb({ hasPermission: true }); }
                    },
                };
            }
            if (!navigator.camera) {
                var pendingCameraCallbacks = null;
                navigator.camera = {
                    DestinationType: { DATA_URL: 0, FILE_URI: 1 },
                    PictureSourceType: { PHOTOLIBRARY: 0, CAMERA: 1, SAVEDPHOTOALBUM: 2 },
                    EncodingType: { JPEG: 0, PNG: 1 },
                    getPicture: function(successCb, errorCb, options) {
                        if (pendingCameraCallbacks) {
                            if (errorCb) { errorCb('a photo capture is already in progress'); }
                            return;
                        }
                        if (!window.eduBridge || !window.eduBridge.takePhoto) {
                            if (errorCb) { errorCb('camera not available'); }
                            return;
                        }
                        pendingCameraCallbacks = { successCb: successCb, errorCb: errorCb };
                        window.eduBridge.takePhoto();
                    },
                };
                if (!window.eduBridgeOnPhotoTaken) {
                    window.eduBridgeOnPhotoTaken = function(json) {
                        var callbacks = pendingCameraCallbacks;
                        pendingCameraCallbacks = null;
                        if (!callbacks) { return; }
                        try {
                            var payload = JSON.parse(json);
                            if (payload.success) {
                                if (callbacks.successCb) { callbacks.successCb(payload.stream); }
                            } else if (callbacks.errorCb) {
                                callbacks.errorCb(payload.error || 'unknown error');
                            }
                        } catch (e) {
                            if (callbacks.errorCb) { callbacks.errorCb('parse error'); }
                        }
                    };
                }
            }
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
