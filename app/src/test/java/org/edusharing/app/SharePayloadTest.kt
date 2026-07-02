package org.edusharing.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SharePayloadTest {
    @Test
    fun `toJson emits all fields`() {
        val payload = SharePayload(
            action = SharePayload.ACTION_SEND,
            mimetype = "text/plain",
            text = "hello",
            uri = "hello",
        )
        val json = JSONObject(payload.toJson())
        assertEquals("SEND", json.getString("action"))
        assertEquals("text/plain", json.getString("mimetype"))
        assertEquals("hello", json.getString("text"))
        assertEquals("hello", json.getString("uri"))
        assertEquals("", json.getString("fileName"))
        assertEquals("", json.getString("stream"))
    }

    @Test
    fun `toJson round trips a VIEW payload`() {
        val payload = SharePayload(action = SharePayload.ACTION_VIEW, uri = "https://example.org/x")
        val json = JSONObject(payload.toJson())
        assertEquals("VIEW", json.getString("action"))
        assertEquals("https://example.org/x", json.getString("uri"))
    }

    @Test
    fun `toJson emits file share fields`() {
        val payload = SharePayload(
            action = SharePayload.ACTION_SEND,
            mimetype = "image/jpeg",
            uri = "content://media/external/images/1",
            fileName = "photo.jpg",
            stream = "AAAA",
        )
        val json = JSONObject(payload.toJson())
        assertEquals("image/jpeg", json.getString("mimetype"))
        assertEquals("photo.jpg", json.getString("fileName"))
        assertEquals("AAAA", json.getString("stream"))
    }
}
