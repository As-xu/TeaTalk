package com.teatalk.xs.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateClientTest {

    private val client = UpdateClient()

    @Test
    fun `newer compares numeric segments`() {
        assertTrue(client.isNewer("0.26.0", "0.25.0"))
        assertTrue(client.isNewer("0.26.1", "0.26.0"))
        assertTrue(client.isNewer("1.0", "0.99.9"))
        assertFalse(client.isNewer("0.25.0", "0.25.0"))
        assertFalse(client.isNewer("0.24.9", "0.25.0"))
        assertFalse(client.isNewer("0.25", "0.25.0"))
    }

    @Test
    fun `parse reads tag apk and digest`() {
        val json = """
            {
              "tag_name": "v0.26.0",
              "html_url": "https://github.com/As-xu/TeaTalk/releases/tag/v0.26.0",
              "published_at": "2026-11-05T10:00:00Z",
              "assets": [
                {"name": "TeaTalk-0.26.0-debug.apk.sha256", "browser_download_url": "https://e/x.sha256", "size": 91},
                {"name": "TeaTalk-0.26.0-debug.apk", "browser_download_url": "https://e/x.apk", "size": 64000000,
                 "digest": "sha256:ABCDEF"}
              ]
            }
        """.trimIndent()
        val release = client.parse(json)
        requireNotNull(release)
        assertEquals("0.26.0", release.version)
        assertEquals("https://e/x.apk", release.apkUrl)
        assertEquals(64_000_000L, release.apkSize)
        assertEquals("abcdef", release.sha256)
    }

    @Test
    fun `parse without apk returns null`() {
        val json = """{"tag_name":"v0.26.0","assets":[{"name":"notes.txt","browser_download_url":"https://e/n.txt"}]}"""
        assertNull(client.parse(json))
    }

    @Test
    fun `parse without tag returns null`() {
        val json = """{"assets":[{"name":"x.apk","browser_download_url":"https://e/x.apk"}]}"""
        assertNull(client.parse(json))
    }

    @Test
    fun `parse tolerates missing digest and bad json`() {
        val json = """{"tag_name":"0.26.0","assets":[{"name":"x.apk","browser_download_url":"https://e/x.apk","size":1}]}"""
        assertNull(client.parse(json)?.sha256)
        assertNull(client.parse("not json"))
    }
}
