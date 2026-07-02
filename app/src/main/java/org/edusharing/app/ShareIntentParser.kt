package org.edusharing.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.InputStream

/**
 * Turns an incoming [Intent] (ACTION_SEND share or ACTION_VIEW deep link) into a
 * [SharePayload]. File bytes are read natively via [ContentResolver] and base64-encoded,
 * so the WebView never needs file-system access.
 */
object ShareIntentParser {
    private const val TAG = "ShareIntentParser"

    /**
     * Hard cap on inlined file size. Shared files are read fully into memory, base64-encoded,
     * and shipped across the JS bridge as a string (the SPA reads `getLastIntent().stream`),
     * so this bounds transient memory. Files above the cap surface the SPA's "only limited-size
     * files can be shared" error instead of silently failing. Relies on
     * `android:largeHeap="true"` (see AndroidManifest) for the headroom; note the encode/handoff
     * transiently holds several multiples of the file size, so on lower-memory devices files
     * near this cap can still OOM.
     */
    private const val MAX_INLINE_BYTES = 150 * 1024 * 1024

    /** Short pause before the single read retry, to let a transient provider hiccup clear. */
    private const val RETRY_BACKOFF_MILLIS = 120L

    /**
     * Human-readable outcome of the most recent [parse], surfaced as an on-screen toast in
     * debug builds (see MainActivity) so share-read failures can be diagnosed without adb.
     */
    @Volatile
    var lastDiagnostic: String = ""
        private set

    /** @return a payload, or `null` when the intent carries nothing shareable. */
    fun parse(intent: Intent?, resolver: ContentResolver): SharePayload? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> parseSend(intent, resolver)
            Intent.ACTION_VIEW -> parseView(intent)
            else -> null
        }
    }

    private fun parseSend(intent: Intent, resolver: ContentResolver): SharePayload? {
        val type = intent.type ?: "*/*"
        val streamUri: Uri? = intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)

        if (streamUri != null) {
            val mimetype = resolver.getType(streamUri) ?: type
            // Skip reading files whose declared size is already over the cap, so an oversized
            // pick (e.g. a multi-GB video) isn't streamed fully into memory just to be rejected.
            val declaredSize = queryStreamSize(resolver, streamUri)
            val bytes = if (declaredSize != null && declaredSize > MAX_INLINE_BYTES) {
                Log.w(TAG, "share content declared size $declaredSize exceeds inline cap ($MAX_INLINE_BYTES): $streamUri")
                lastDiagnostic = "Datei zu groß (${declaredSize / (1024 * 1024)} MB)"
                null
            } else {
                readBytes(resolver, streamUri)
            }
            if (bytes != null) {
                lastDiagnostic = "Datei ok: ${bytes.size} bytes, $mimetype"
            }
            return SharePayload(
                action = SharePayload.ACTION_SEND,
                mimetype = mimetype,
                uri = streamUri.toString(),
                fileName = queryDisplayName(resolver, streamUri),
                stream = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
            )
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrEmpty()) {
            lastDiagnostic = "Text/Link geteilt (${sharedText.length} Zeichen)"
            return SharePayload(
                action = SharePayload.ACTION_SEND,
                mimetype = "text/plain",
                text = sharedText,
                uri = sharedText,
            )
        }
        lastDiagnostic = "Intent ohne STREAM und ohne TEXT (type=$type)"
        return null
    }

    private fun parseView(intent: Intent): SharePayload? {
        val data = intent.dataString ?: return null
        return SharePayload(action = SharePayload.ACTION_VIEW, uri = data)
    }

    /** Reads the declared byte size of a content URI, or `null` if the provider doesn't report it. */
    private fun queryStreamSize(resolver: ContentResolver, uri: Uri): Long? {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                    return c.getLong(idx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryStreamSize failed for $uri", e)
        }
        return null
    }

    /** Reads the display name backing a content URI, falling back to the last path segment. */
    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDisplayName failed for $uri", e)
        }
        return uri.lastPathSegment ?: ""
    }

    /** Reads the bytes behind a content URI, or `null` on failure / oversized content. */
    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? {
        // Two attempts: a shared content:// provider (especially a sender app's FileProvider)
        // can transiently fail or not be ready right after a share launch, which surfaced as
        // intermittent "empty stream" share failures. Retry once, and fall back to an asset
        // file descriptor when a plain input stream isn't offered.
        repeat(2) { attempt ->
            try {
                openShareStream(resolver, uri)?.use { input ->
                    val bytes = input.readBytes()
                    return if (bytes.size > MAX_INLINE_BYTES) {
                        Log.w(TAG, "share content exceeds inline cap (${bytes.size} bytes): $uri")
                        lastDiagnostic = "Datei zu groß (${bytes.size / (1024 * 1024)} MB)"
                        null
                    } else {
                        bytes
                    }
                }
                Log.w(TAG, "readBytes got no stream for $uri (attempt ${attempt + 1})")
                lastDiagnostic = "Kein Stream für die Datei (Provider lieferte nichts)"
            } catch (e: Exception) {
                Log.w(TAG, "readBytes attempt ${attempt + 1} failed for $uri", e)
                lastDiagnostic = "Lesefehler (${e.javaClass.simpleName}): ${e.message}"
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MILLIS)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    /** Opens a share URI, falling back to an asset file descriptor when no plain stream is given. */
    private fun openShareStream(resolver: ContentResolver, uri: Uri): InputStream? {
        resolver.openInputStream(uri)?.let { return it }
        return resolver.openAssetFileDescriptor(uri, "r")?.createInputStream()
    }
}
