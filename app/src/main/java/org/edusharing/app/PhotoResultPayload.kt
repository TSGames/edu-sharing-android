package org.edusharing.app

import org.json.JSONObject

/**
 * Result of a `takePhoto()` capture, delivered to `window.eduBridgeOnPhotoTaken`
 * (docs/android-app/eduBridge-contract.md §5). Always JPEG when [success].
 */
data class PhotoResultPayload(
    val success: Boolean,
    val mimetype: String = "image/jpeg",
    val fileName: String = "",
    val stream: String = "",
    val error: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("success", success)
        if (success) {
            put("mimetype", mimetype)
            put("fileName", fileName)
            put("stream", stream)
        } else {
            put("error", error)
        }
    }.toString()

    companion object {
        fun failure(error: String) = PhotoResultPayload(success = false, error = error)
    }
}
