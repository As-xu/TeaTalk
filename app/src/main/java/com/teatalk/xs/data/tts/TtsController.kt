package com.teatalk.xs.data.tts

import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 0.25.0：朗读会话管理。
 *
 * 同一时刻只允许一条消息在朗读；再点同一条 = 停止，点另一条 = 打断前一条。
 * 状态用 [StateFlow] 暴露，聊天页据此把菜单项在「朗读 / 停止朗读」之间切换。
 */
class TtsController(
    private val rate: () -> Float,
    private val pitch: () -> Float,
    private val engines: () -> TtsEngine,
) {

    /** [messageId] 为空表示空闲；[index]/[total] 用于展示「第几段」。 */
    data class State(
        val messageId: Long? = null,
        val index: Int = 0,
        val total: Int = 0,
    ) {
        val speaking: Boolean get() = messageId != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次朗读失败；聊天页消费后调 [clearFailure]。 */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var job: Job? = null
    private var stopping: Boolean = false

    /** 每次朗读/停止 +1：旧协程的 finally 不能覆盖新朗读的状态。 */
    private var generation: Int = 0
    private val engine: TtsEngine by lazy(engines)

    /** 朗读这条消息；同一条正在朗读时改为停止。 */
    fun speak(messageId: Long, text: String) {
        if (_state.value.messageId == messageId) {
            stop()
            return
        }
        stop()
        val segments = TtsText.segments(text)
        if (segments.isEmpty()) {
            // 纯代码块 / 纯表情：没有可读内容，不是引擎故障，别弹「语音引擎不可用」。
            TeaTalkLog.i("Tts", "tts.empty", "message" to messageId)
            return
        }
        _failed.value = false
        stopping = false
        val gen = ++generation
        _state.value = State(messageId = messageId, index = 0, total = segments.size)
        TeaTalkLog.i(
            "Tts", "tts.start",
            "message" to messageId, "segments" to segments.size, "chars" to text.length,
        )
        job = scope.launch {
            var stopped = false
            try {
                engine.speak(segments, rate(), pitch()) { index ->
                    _state.value = _state.value.copy(index = index)
                }
            } catch (_: TtsStopped) {
                stopped = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TeaTalkLog.w("Tts", "tts.fail", "error" to e.message)
                _failed.value = true
            } finally {
                val wasStopped = stopped || stopping
                if (gen == generation) {
                    _state.value = State()
                    stopping = false
                }
                TeaTalkLog.i("Tts", "tts.done", "message" to messageId, "stopped" to wasStopped)
            }
        }
    }

    fun stop() {
        stopping = _state.value.speaking
        generation++
        job?.cancel()
        job = null
        engine.stop()
        if (_state.value.speaking) _state.value = State()
    }

    fun clearFailure() {
        _failed.value = false
    }

    /** 试听（设置页用）：不绑定消息 id。 */
    fun preview(text: String) {
        stop()
        _failed.value = false
        val segments = TtsText.segments(text)
        if (segments.isEmpty()) return
        TeaTalkLog.i("Tts", "tts.preview", "segments" to segments.size)
        val gen = ++generation
        _state.value = State(messageId = PREVIEW_ID, index = 0, total = segments.size)
        job = scope.launch {
            try {
                engine.speak(segments, rate(), pitch()) { index ->
                    _state.value = _state.value.copy(index = index)
                }
            } catch (_: TtsStopped) {
                // 用户点了停止
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TeaTalkLog.w("Tts", "tts.preview.fail", "error" to e.message)
                _failed.value = true
            } finally {
                if (gen == generation) _state.value = State()
            }
        }
    }

    fun release() {
        stop()
        engine.release()
        scope.cancel()
    }

    private companion object {
        /** 试听用的伪消息 id（真实消息 id 从 1 开始）。 */
        const val PREVIEW_ID = -1L
    }
}
