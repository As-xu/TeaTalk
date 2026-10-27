package com.teatalk.xs.log

import com.teatalk.core.log.LogLevel
import com.teatalk.core.log.LogRecord
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FileLogSink 的落盘合同：写文件、关开关不写、超限轮转。
 *
 * 之前这块完全没有测试，轮转/开关都是「看代码觉得对」。
 */
class FileLogSinkTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("teatalk-log-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(ms: Long, text: String) = LogRecord(
        timeMs = ms,
        level = LogLevel.I,
        tag = "Db",
        event = "test.event",
        fields = mapOf("text" to text),
    )

    /** 单线程 executor 是异步的，等待目标文件出现。 */
    private fun awaitFile(timeoutMs: Long = 3_000, predicate: (File) -> Boolean = { it.isFile }): File {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = dir.listFiles()?.firstOrNull(predicate)
            if (found != null) return found
            Thread.sleep(20)
        }
        error("log file not found in ${dir.absolutePath}: ${dir.list()?.toList()}")
    }

    @Test
    fun `writes structured lines to a dated file`() {
        val sink = FileLogSink(dir)
        sink.write(record(System.currentTimeMillis(), "hello"))
        val file = awaitFile { it.isFile }
        val content = file.readText()
        assertTrue(file.name.startsWith("teatalk-"))
        assertTrue(content.contains("I/Db test.event text=hello"))
    }

    @Test
    fun `disabled sink writes nothing`() {
        val sink = FileLogSink(dir)
        sink.enabled = false
        sink.write(record(System.currentTimeMillis(), "hidden"))
        Thread.sleep(300)
        assertTrue(dir.listFiles().orEmpty().none { it.isFile && it.readText().contains("hidden") })
    }

    @Test
    fun `rotates when file exceeds the cap`() {
        val sink = FileLogSink(dir)
        // 一条 2.1MB 的记录直接把当天文件顶过 2MB 上限；下一条写入时才发生轮转
        val big = "x".repeat(2 * 1024 * 1024 + 1024)
        sink.write(record(System.currentTimeMillis(), big))
        awaitFile { it.isFile && it.length() > 0 }
        sink.write(record(System.currentTimeMillis(), "second"))
        // 轮转是异步的：轮询直到 .1 出现
        val deadline = System.currentTimeMillis() + 5_000
        var rotated: File? = null
        while (System.currentTimeMillis() < deadline) {
            rotated = dir.listFiles()?.firstOrNull { it.name.endsWith(".log.1") }
            if (rotated != null) break
            Thread.sleep(20)
        }
        assertTrue("expected a rotated .log.1 file", rotated != null)
        awaitFile { it.name.endsWith(".log") && it.readText().contains("second") }
    }
}
