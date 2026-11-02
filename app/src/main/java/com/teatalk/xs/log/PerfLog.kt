package com.teatalk.xs.log

import android.util.Log

/**
 * 性能埋点：**不受**「调试日志」开关影响，始终写 logcat。
 *
 * 只记录耗时/条数这类数值，不记录任何消息内容，可用
 * `adb logcat -s TeaTalkPerf:V` 观测，作为冷启动与长会话的回归口径。
 */
object PerfLog {

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val body = fields.joinToString(" ") { (key, value) -> "$key=$value" }
        Log.i(TAG, if (body.isEmpty()) name else "$name $body")
    }

    private const val TAG = "TeaTalkPerf"
}
