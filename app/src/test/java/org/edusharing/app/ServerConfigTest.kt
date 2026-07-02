package org.edusharing.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun `normalize adds https scheme when missing`() {
        assertEquals("https://example.org", ServerConfig.normalize("example.org"))
    }

    @Test
    fun `normalize preserves explicit http scheme`() {
        assertEquals("http://example.org", ServerConfig.normalize("http://example.org"))
    }

    @Test
    fun `normalize trims whitespace and trailing slash`() {
        assertEquals("https://example.org", ServerConfig.normalize("  example.org/  "))
    }

    @Test
    fun `normalize on blank input returns blank`() {
        assertEquals("", ServerConfig.normalize("   "))
    }

    @Test
    fun `isUsable rejects null blank and bare scheme`() {
        assertFalse(ServerConfig.isUsable(null))
        assertFalse(ServerConfig.isUsable(""))
        assertFalse(ServerConfig.isUsable("https://"))
    }

    @Test
    fun `isUsable accepts a real host`() {
        assertTrue(ServerConfig.isUsable("example.org"))
        assertTrue(ServerConfig.isUsable("https://example.org"))
    }
}
