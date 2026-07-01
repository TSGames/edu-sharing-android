package org.edusharing.app

import android.app.Application
import android.webkit.WebView

/**
 * Application entry point. Enables WebView remote debugging for debug builds so the
 * remote SPA can be inspected via chrome://inspect during development.
 */
class EduSharingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
