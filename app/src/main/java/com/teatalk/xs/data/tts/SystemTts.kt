package com.teatalk.xs.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统内置 TTS（`android.speech.tts.TextToSpeech`）。
 *
 * 逐段 `speak` 并等待 `onDone` 再读下一段：既能让 [TtsController] 拿到进度，
 * 也避免整段长文本被引擎截断。所有调用都切回主线程（TextToSpeech 要求）。
 */
class SystemTts(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var utteranceSeq = 0

    override suspend fun speak(segments: List<String>, rate: Float, pitch: Float, onSegment: (Int) -> Unit) {
        if (segments.isEmpty()) return
        val engine = ensureEngine() ?: throw TtsException("tts unavailable")
        withContext(Dispatchers.Main.immediate) {
            engine.setSpeechRate(rate.coerceIn(0.5f, 1.5f))
            engine.setPitch(pitch.coerceIn(0.5f, 1.5f))
        }
        for ((index, segment) in segments.withIndex()) {
            onSegment(index)
            val id = "tts-${System.nanoTime()}-${utteranceSeq++}"
            val deferred = CompletableDeferred<Unit>()
            pending[id] = deferred
            val queued = withContext(Dispatchers.Main.immediate) {
                engine.speak(segment, TextToSpeech.QUEUE_FLUSH, null, id)
            }
            if (queued != TextToSpeech.SUCCESS) {
                pending.remove(id)
                // 引擎进入不可用状态（被更新 / 崩溃 / 语音包被删）：丢掉旧实例，下次重建。
                resetEngine()
                throw TtsException("speak failed")
            }
            try {
                // 厂商引擎偶尔 onDone/onError 都不回调：按「字数 × 80ms + 5s」兜底，
                // 超时当成这段读完，别让状态永远停在「正在朗读」。
                val budgetMs = 5_000L + segment.length * 80L
                val completed = withTimeoutOrNull(budgetMs) { deferred.await() }
                if (completed == null) {
                    TeaTalkLog.w("Tts", "tts.utterance.timeout", "chars" to segment.length)
                    withContext(Dispatchers.Main.immediate) { engine.stop() }
                }
            } finally {
                pending.remove(id)
            }
        }
    }

    override fun stop() {
        tts?.stop()
        val stopped = TtsStopped()
        pending.values.forEach { if (!it.isCompleted) it.completeExceptionally(stopped) }
        pending.clear()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    /** 丢掉当前实例（不 shutdown 的话旧引擎仍占着音频焦点）。 */
    private fun resetEngine() {
        val old = tts
        tts = null
        if (old != null) {
            withContextNoCancel { old.shutdown() }
        }
    }

    private fun withContextNoCancel(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private suspend fun ensureEngine(): TextToSpeech? {
        tts?.let { return it }
        val created = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (status != TextToSpeech.SUCCESS) {
                        TeaTalkLog.w("Tts", "tts.init.fail", "status" to status)
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                        return@TextToSpeech
                    }
                    val ok = engine
                    if (ok == null) {
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    } else {
                        ok.setOnUtteranceProgressListener(listener)
                        if (cont.isActive) cont.resumeWith(Result.success(ok))
                    }
                }
                cont.invokeOnCancellation { engine?.shutdown() }
            }
        }
        if (created != null) {
            val locale = pickLocale(created)
            withContext(Dispatchers.Main.immediate) { created.language = locale }
            TeaTalkLog.i("Tts", "tts.init.ok", "locale" to locale.toLanguageTag())
            tts = created
        }
        return created
    }

    private fun pickLocale(engine: TextToSpeech): Locale {
        val system = Locale.getDefault()
        val available = engine.isLanguageAvailable(system)
        return if (available == TextToSpeech.LANG_AVAILABLE ||
            available == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            available == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        ) {
            system
        } else {
            Locale.CHINA
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending[it]?.complete(Unit) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending[it]?.completeExceptionally(TtsException("utterance error")) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            TeaTalkLog.w("Tts", "tts.utterance.error", "id" to utteranceId, "code" to errorCode)
            utteranceId?.let { pending[it]?.completeExceptionally(TtsException("utterance error $errorCode")) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending[it]?.completeExceptionally(TtsStopped()) }
        }
    }
}
