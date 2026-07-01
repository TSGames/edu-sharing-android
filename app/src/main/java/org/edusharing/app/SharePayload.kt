package org.edusharing.app

import org.json.JSONObject

/**
 * Unified share payload exchanged with the Angular SPA via `window.eduBridge`.
 * Field names and semantics are fixed by docs/android-app/eduBridge-contract.md
 * (§4) in the edu-sharing-community-repository.
 *
 * Rules:
 *  - Link/text share: [text] set, [stream]/[fileName] empty, [mimetype] = "text/plain".
 *  - File share:      [uri] = content://…, [stream] = base64 of bytes, [fileName] set.
 *  - Deep link:       [action] = "VIEW", [uri] = full edu-sharing URL.
 */
data class SharePayload(
    val action: String = ACTION_SEND,
    val mimetype: String = "",
    val text: String = "",
    val uri: String = "",
    val fileName: String = "",
    val stream: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("action", action)
        put("mimetype", mimetype)
        put("text", text)
        put("uri", uri)
        put("fileName", fileName)
        put("stream", stream)
    }.toString()

    companion object {
        const val ACTION_SEND = "SEND"
        const val ACTION_VIEW = "VIEW"
    }
}
