package org.edusharing.app

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the list of publicly known edu-sharing servers from the app-registry, offered as
 * quick-pick shortcuts alongside manual server URL entry (see [ServerUrlActivity]). Not part
 * of the eduBridge contract — purely a native UX convenience.
 */
object ServerRegistry {
    private const val TAG = "ServerRegistry"

    // Sent as the app-registry's `min`/`max` compatibility gate (see fetch()'s doc comment
    // below) so the server only returns entries this shell is actually compatible with.
    private const val APP_VERSION = "3.0"
    private const val REGISTRY_URL = "https://app-registry.edu-sharing.com/servers.php?version=$APP_VERSION"
    private const val TIMEOUT_MILLIS = 10_000

    data class Entry(val name: String, val url: String)

    /**
     * Blocking network call — run off the main thread. Returns an empty list on any failure
     * (network error, malformed JSON, ...) so manual URL entry remains the fallback.
     *
     * Some entries carry `min`/`max` integer fields. These appear to gate entries by a
     * compatible app-version range, mirroring how this host used to serve version-pinned
     * Cordova JS bundles to the old hybrid app. The `?version=` query param above lets the
     * server apply that filtering itself, so this shell doesn't need to re-check `min`/`max`
     * client-side — every entry with a name and URL in the response is shown as-is.
     */
    fun fetch(): List<Entry> = try {
        val connection = URL(REGISTRY_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        parse(body)
    } catch (e: Exception) {
        Log.w(TAG, "failed to fetch server registry", e)
        emptyList()
    }

    private fun parse(body: String): List<Entry> {
        val array = JSONArray(body)
        val entries = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            entries.add(Entry(name, url))
        }
        return entries
    }
}
