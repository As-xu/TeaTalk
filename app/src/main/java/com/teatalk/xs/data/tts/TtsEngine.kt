package com.teatalk.xs.data.tts

/**
 * 0.25.0：朗读引擎抽象。
 *
 * 目前只有系统内置 TTS（[SystemTts]）；接口留在这里，后续接云端 `/audio/speech`
 * 时不用再改调用方（[TtsController]）。
 */
interface TtsEngine {

    /**
     * 依次朗读 [segments]，每段开始前回调 [onSegment]（用于高亮当前句/进度）。
     * 正常播完返回；被 [stop] 打断时抛 [TtsStopped]；引擎不可用抛 [TtsException]。
     */
    suspend fun speak(segments: List<String>, rate: Float, pitch: Float, onSegment: (Int) -> Unit)

    fun stop()

    fun release()
}

/** 用户主动停止，不算失败。 */
class TtsStopped : Exception("tts stopped")

/** 引擎不可用 / 合成失败。 */
class TtsException(message: String) : Exception(message)
