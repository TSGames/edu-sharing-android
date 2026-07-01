package org.edusharing.app

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log

/**
 * Turns an incoming [Intent] (ACTION_SEND share or ACTION_VIEW deep link) into a
 * [SharePayload]. File bytes are read natively via [ContentResolver] and base64-encoded,
 * so the WebView never needs file-system access.
 */
object ShareIntentParser {
    private const val TAG = "ShareIntentParser"

    /** Hard cap on inlined file size to avoid OOM when base64-encoding into memory (~32 MB). */
    private const val MAX_INLINE_BYTES = 32 * 1024 * 1024

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
            val bytes = readBytes(resolver, streamUri)
            return SharePayload(
                action = SharePayload.ACTION_SEND,
                mimetype = resolver.getType(streamUri) ?: type,
                uri = streamUri.toString(),
                fileName = queryDisplayName(resolver, streamUri),
                stream = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
            )
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sharedText.isNullOrEmpty()) {
            return SharePayload(
                action = SharePayload.ACTION_SEND,
                mimetype = "text/plain",
                text = sharedText,
                uri = sharedText,
            )
        }
        return null
    }

    private fun parseView(intent: Intent): SharePayload? {
        val data = intent.dataString ?: return null
        return SharePayload(action = SharePayload.ACTION_VIEW, uri = data)
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
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_INLINE_BYTES) {
                    Log.w(TAG, "share content exceeds inline cap (${bytes.size} bytes): $uri")
                    null
                } else {
                    bytes
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed for $uri", e)
            null
        }
    }
}
