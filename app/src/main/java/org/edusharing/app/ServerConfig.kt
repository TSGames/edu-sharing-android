package org.edusharing.app

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the edu-sharing server the WebView should open. The user picks it natively on
 * first launch (or a build-time default is used); the SPA then handles login.
 *
 * The OAuth token and other SPA state live in the WebView's own `localStorage`
 * (same-origin) — not here.
 */
object ServerConfig {
    private const val PREFS = "edu_sharing_prefs"
    private const val KEY_SERVER_URL = "server_url"

    /** Returns the stored server URL, or the build default if none was stored yet. */
    fun getServerUrl(context: Context): String? {
        val stored = prefs(context).getString(KEY_SERVER_URL, null)
        if (!stored.isNullOrBlank()) return stored
        val default = BuildConfig.DEFAULT_SERVER_URL
        return if (isUsable(default)) default else null
    }

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit { putString(KEY_SERVER_URL, normalize(url)) }
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_SERVER_URL) }
    }

    /**
     * Normalizes user input into a loadable URL: trims, defaults to https:// when no
     * scheme is given, and strips a trailing slash so route concatenation is predictable.
     */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "https://$url"
        }
        return url.trimEnd('/')
    }

    /** A URL is usable once it carries a host beyond the bare scheme. */
    fun isUsable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val n = normalize(url)
        return n.length > "https://".length && n.substringAfter("://").isNotBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
