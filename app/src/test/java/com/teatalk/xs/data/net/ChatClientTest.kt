package com.teatalk.xs.data.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatClient 的错误分类合同：401/429/其它 HTTP/超时/断流。
 *
 * 这些场景在真机上不好复现（尤其 429 与断流），用 MockWebServer 固化行为。
 */
class ChatClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(readTimeoutMs: Long = 5_000): ChatClient = ChatClient(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build(),
    )

    private fun sse(vararg chunks: String): String = buildString {
        chunks.forEach { chunk ->
            append("data: {\"choices\":[{\"delta\":{\"content\":\"")
            append(chunk)
            append("\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun run(client: ChatClient): List<ChatEvent> = runBlocking {
        client.stream(
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            model = "test-model",
            messages = listOf("system" to "hi", "user" to "hello"),
        ).toList()
    }

    @Test
    fun `streams deltas then completed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse("你", "好")),
        )
        val events = run(client())
        assertEquals(3, events.size)
        assertEquals(ChatEvent.Delta("你"), events[0])
        assertEquals(ChatEvent.Delta("好"), events[1])
        assertEquals(ChatEvent.Completed(), events[2])
    }

    @Test
    fun `401 maps to unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val failed = run(client()).filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.UNAUTHORIZED, failed.error)
    }

    @Test
    fun `403 is not zeroed into unauthorized`() {
        // 403 = key 有效但没权限；之前和 401 合并，文案会误导用户以为 key 填错了
        server.enqueue(MockResponse().setResponseCode(403).setBody("no permission"))
        val failed = run(client()).filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.HTTP(403), failed.error)
    }

    @Test
    fun `mid stream error frame surfaces as failure`() {
        // 部分服务端（含 OpenAI 兼容的代理）会在流中途推 {"error":{...}}，不能当成空回复
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                        "data: {\"error\":{\"message\":\"quota exceeded\"}}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val events = run(client())
        assertTrue(events.any { it is ChatEvent.Delta })
        val failed = events.filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.HTTP(200), failed.error)
        assertTrue(failed.detail!!.contains("quota exceeded"))
    }

    @Test
    fun `finish reason length marks truncated`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Long\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val completed = run(client()).filterIsInstance<ChatEvent.Completed>().single()
        assertTrue(completed.truncated)
    }

    @Test
    fun `429 maps to rate limited`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val failed = run(client()).filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.RATE_LIMITED, failed.error)
    }

    @Test
    fun `other http status keeps the code`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
        val failed = run(client()).filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.HTTP(503), failed.error)
        assertTrue(failed.detail!!.contains("upstream down"))
    }

    @Test
    fun `read timeout maps to timeout`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )
        val failed = run(client(readTimeoutMs = 300))
            .filterIsInstance<ChatEvent.Failed>()
            .single()
        assertEquals(ChatError.TIMEOUT, failed.error)
    }

    @Test
    fun `broken stream maps to network`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val failed = run(client()).filterIsInstance<ChatEvent.Failed>().single()
        assertEquals(ChatError.NETWORK, failed.error)
    }
}
