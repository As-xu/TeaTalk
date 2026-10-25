package com.teatalk.core.log

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {

    private val collected = mutableListOf<LogRecord>()

    @BeforeTest
    fun setUp() {
        collected.clear()
        TeaTalkLog.install()
        TeaTalkLog.minLevel = LogLevel.D
    }

    @AfterTest
    fun tearDown() {
        TeaTalkLog.install()
    }

    @Test
    fun `level threshold filters lower levels`() {
        val sink = LogSink { collected += it }
        TeaTalkLog.install(sink)
        TeaTalkLog.minLevel = LogLevel.W

        TeaTalkLog.d("Ui", "debug.event")
        TeaTalkLog.i("Ui", "info.event")
        TeaTalkLog.w("Ui", "warn.event")
        TeaTalkLog.e("Ui", "error.event", null)

        assertEquals(listOf("warn.event", "error.event"), collected.map { it.event })
    }

    @Test
    fun `fields are stringified and formatted on one line`() {
        val sink = LogSink { collected += it }
        TeaTalkLog.install(sink)

        TeaTalkLog.i("Net", "req.start", "host" to "api.example.com", "stream" to true, "n" to 3)

        val record = collected.single()
        assertEquals(LogLevel.I, record.level)
        assertEquals("Net", record.tag)
        assertEquals("req.start", record.event)
        assertEquals("3", record.fields["n"])
        assertEquals("I/Net req.start host=api.example.com stream=true n=3", record.toLine())
    }

    @Test
    fun `throwable is summarised without raw message stack`() {
        val sink = LogSink { collected += it }
        TeaTalkLog.install(sink)

        val boom = IllegalStateException("http://user:secret@example.com/v1?key=sk-live-123")
        TeaTalkLog.e("Net", "req.error", boom, "status" to 500)

        val summary = collected.single().throwableSummary ?: error("no summary")
        assertTrue(summary.startsWith("IllegalStateException"), summary)
        assertFalse(summary.contains("sk-live-123"), "api key leaked: $summary")
        assertFalse(summary.contains("secret"), "credentials leaked: $summary")
        assertTrue(summary.contains("LogTest", ignoreCase = true) || summary.contains("@ "), summary)
    }

    @Test
    fun `ring buffer is bounded and newest first`() {
        val ring = RingBufferSink(capacity = 3)
        // 验证门面 + ring 组合
        TeaTalkLog.install(ring)
        repeat(5) { TeaTalkLog.i("Ui", "x$it") }

        assertEquals(3, ring.size())
        assertEquals(listOf("x4", "x3", "x2"), ring.snapshot().map { it.event })
        ring.clear()
        assertEquals(0, ring.size())
    }

    @Test
    fun `broken sink does not break others`() {
        val ok = AtomicInteger(0)
        TeaTalkLog.install(LogSink { error("boom") }, LogSink { ok.incrementAndGet() })

        TeaTalkLog.i("Ui", "event")

        assertEquals(1, ok.get())
        assertTrue(TeaTalkLog.droppedCount() >= 1)
    }

    @Test
    fun `redact keeps fingerprints stats but not plaintext`() {
        val secret = "APIKEY-should-never-appear"
        val fp = Redact.fingerprint(secret)
        assertFalse(fp.contains("APIKEY"), fp)
        assertTrue(fp.contains("len=${secret.length}"), fp)
        assertTrue(fp.startsWith("hash="), fp)
        assertEquals(Redact.fingerprint(secret), fp)

        assertEquals("https://api.example.com/v1/chat", Redact.url("https://api.example.com/v1/chat?key=sk-1&x=2"))
        assertEquals("***abcd", Redact.apiKey("sk-abcd"))
        assertEquals("<empty>", Redact.apiKey(""))
        assertEquals("Authorization=***,X-Other", Redact.headers(mapOf("Authorization" to "Bearer x", "X-Other" to "y")))
    }

    @Test
    fun `concurrent writes are safe`() {
        val ring = RingBufferSink(capacity = 500)
        TeaTalkLog.install(ring)
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        repeat(8) { t ->
            pool.execute {
                repeat(200) { i -> TeaTalkLog.i("Ui", "t$t-$i") }
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(500, ring.size())
        assertTrue(ring.snapshot().isNotEmpty())
    }
}
