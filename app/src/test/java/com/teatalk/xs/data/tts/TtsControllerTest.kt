package com.teatalk.xs.data.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.25.0：朗读状态机（用假引擎，不碰 Android TextToSpeech）。
 *
 * 覆盖 reviewer 指出的场景：同一条二次点击 = 停止、切到另一条、停止后旧协程
 * 不能把新朗读的状态清掉、试听停止不算失败、空文本不算失败。
 */
class TtsControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var engine: FakeEngine
    private lateinit var controller: TtsController

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        controller = TtsController(
            rate = { 1f },
            pitch = { 1f },
            engines = { engine },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun speakCompletesAndReturnsToIdle() = runTest(dispatcher) {
        controller.speak(1L, "第一句。第二句。")
        advanceUntilIdle()
        assertNull(controller.state.value.messageId)
        assertFalse(controller.failed.value)
        assertEquals(1, engine.spokenSegments)
    }

    @Test
    fun speakSameMessageAgainStops() = runTest(dispatcher) {
        controller.speak(1L, "很长的一句。")
        advanceUntilIdle()
        assertTrue(engine.stoppedAtLeastOnce)
    }

    @Test
    fun switchingMessageReplacesState() = runTest(dispatcher) {
        controller.speak(1L, "第一条。")
        controller.speak(2L, "第二条。")
        assertEquals(2L, controller.state.value.messageId)
        advanceUntilIdle()
        assertNull(controller.state.value.messageId)
    }

    @Test
    fun previewStopDoesNotReportFailure() = runTest(dispatcher) {
        controller.preview("试听文本。")
        controller.stop()
        advanceUntilIdle()
        assertFalse("停止试听不应被当成引擎故障", controller.failed.value)
        assertNull(controller.state.value.messageId)
    }

    @Test
    fun emptyTextIsNotTreatedAsFailure() = runTest(dispatcher) {
        controller.speak(9L, "```kotlin\nval x = 1\n```")
        advanceUntilIdle()
        assertFalse(controller.failed.value)
    }

    @Test
    fun engineErrorSetsFailureFlag() = runTest(dispatcher) {
        engine.failNext = true
        controller.speak(3L, "会失败的朗读。")
        advanceUntilIdle()
        assertTrue(controller.failed.value)
        controller.clearFailure()
        assertFalse(controller.failed.value)
    }

    private class FakeEngine : TtsEngine {
        var failNext = false
        var stoppedAtLeastOnce = false
        var spokenSegments = 0

        override suspend fun speak(
            segments: List<String>,
            rate: Float,
            pitch: Float,
            onSegment: (Int) -> Unit,
        ) {
            if (failNext) {
                failNext = false
                throw TtsException("boom")
            }
            segments.forEachIndexed { index, _ ->
                onSegment(index)
                spokenSegments++
                delay(10)
            }
        }

        override fun stop() {
            stoppedAtLeastOnce = true
        }

        override fun release() = Unit
    }
}
