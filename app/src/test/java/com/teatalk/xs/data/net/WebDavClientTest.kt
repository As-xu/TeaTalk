package com.teatalk.xs.data.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDavClient 的协议合同：PROPFIND 解析、MKCOL 已存在、401 归类、PUT/GET/DELETE。
 *
 * XML 用真实坚果云/Nextcloud 会返回的形状（绝对 href + 集合 + 文件），
 * 覆盖「href 归一化」和「跳过自身条目」这两处最容易错的逻辑。
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient

    private val config = WebDavConfig("http://127.0.0.1:1/dav/", "user", "pass")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WebDavClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cfg() = WebDavConfig(server.url("/dav/").toString(), "user", "pass")

    private fun multiStatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
${responses.joinToString("\n")}
</D:multistatus>"""

    private fun entry(href: String, collection: Boolean, size: Long = 0): String = """
<D:response>
  <D:href>$href</D:href>
  <D:propstat><D:prop>
    <D:displayname>${href.trimEnd('/').substringAfterLast('/')}</D:displayname>
    <D:resourcetype>${if (collection) "<D:collection/>" else ""}</D:resourcetype>
    <D:getcontentlength>$size</D:getcontentlength>
  </D:prop></D:propstat>
</D:response>"""

    @Test
    fun listParsesAbsoluteHrefsAndSkipsSelf() = runBlocking {
        val base = server.url("/dav/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multiStatus(
                    entry("$base/TeaTalk/", collection = true),
                    entry("$base/TeaTalk/backup-20260101-120000.zip", collection = false, size = 2048),
                    entry("$base/TeaTalk/notes.txt", collection = false, size = 10),
                ),
            ),
        )

        val entries = client.list(cfg(), "TeaTalk")

        assertEquals(2, entries.size)
        val zip = entries.first { it.name.endsWith(".zip") }
        assertEquals("TeaTalk/backup-20260101-120000.zip", zip.path)
        assertEquals(2048, zip.size)
        assertFalse(zip.isCollection)
        assertTrue(entries.none { it.name == "TeaTalk" })

        // 请求本身：Depth 1 + PROPFIND + 目录以 / 结尾。
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("/dav/TeaTalk/", request.path)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun listHandlesRelativeHrefs() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multiStatus(
                    entry("/dav/TeaTalk/", collection = true),
                    entry("/dav/TeaTalk/backup-20260102-120000.zip", collection = false, size = 1),
                ),
            ),
        )

        val entries = client.list(cfg(), "TeaTalk")

        assertEquals(listOf("backup-20260102-120000.zip"), entries.map { it.name })
    }

    /**
     * Nextcloud 的基址常含用户名，中文用户名会被百分号编码；服务端返回的 href 也是编码过的。
     * 两边都要解码后再比较，否则相对路径会多出一截，恢复/清理全部 404。
     */
    @Test
    fun listHandlesNonAsciiBaseAndEncodedHref() = runBlocking {
        val base = server.url("/dav/files/张三/").toString()
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multiStatus(
                    entry("/dav/files/%E5%BC%A0%E4%B8%89/TeaTalk/", collection = true),
                    entry("/dav/files/%E5%BC%A0%E4%B8%89/TeaTalk/backup-20260103-120000.zip", collection = false, size = 9),
                ),
            ),
        )

        val entries = client.list(WebDavConfig(base, "user", "pass"), "TeaTalk")

        assertEquals(listOf("backup-20260103-120000.zip"), entries.map { it.name })
        assertEquals("TeaTalk/backup-20260103-120000.zip", entries.single().path)
        // 目录请求要保持编码后的路径 + 结尾斜杠。
        assertEquals(
            "/dav/files/%E5%BC%A0%E4%B8%89/TeaTalk/",
            server.takeRequest().path,
        )
    }

    /** `+` 在 URLDecoder 里会被当成空格，这里必须原样保留。 */
    @Test
    fun listKeepsPlusInFileNames() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multiStatus(
                    entry("/dav/TeaTalk/", collection = true),
                    entry("/dav/TeaTalk/a+b.zip", collection = false, size = 1),
                ),
            ),
        )

        val entries = client.list(cfg(), "TeaTalk")

        assertEquals(listOf("a+b.zip"), entries.map { it.name })
    }

    @Test
    fun unauthorizedBecomesTypedException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { client.test(cfg()) }.exceptionOrNull()
        assertTrue(error is WebDavException)
        assertEquals(401, (error as WebDavException).status)
    }

    @Test
    fun testAcceptsMultiStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multiStatus()))
        client.test(cfg())
        val request = server.takeRequest()
        assertEquals("0", request.getHeader("Depth"))
    }

    @Test
    fun makeDirsToleratesExistingCollection() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(201))
        client.makeDirs(cfg(), "TeaTalk/sub")
        assertEquals("/dav/TeaTalk", server.takeRequest().path)
        assertEquals("/dav/TeaTalk/sub", server.takeRequest().path)
    }

    @Test
    fun deleteTreatsMissingAsSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.delete(cfg(), "TeaTalk/gone.zip"))
    }

    @Test
    fun putThenGetRoundTrip() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        client.put(cfg(), "TeaTalk/backup-a.zip", byteArrayOf(1, 2, 3))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(3, put.bodySize)

        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        assertEquals("hello", String(client.get(cfg(), "TeaTalk/backup-a.zip")))
    }
}
