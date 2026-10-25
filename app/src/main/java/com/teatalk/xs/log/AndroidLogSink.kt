package com.teatalk.xs.log

import android.util.Log
import com.teatalk.core.log.LogRecord
import com.teatalk.core.log.LogSink

/** 把结构化日志转给 android.util.Log（release/debug 都挂）。 */
class AndroidLogSink : LogSink {

    override fun write(record: LogRecord) {
        val line = record.toLine()
        when (record.level) {
            com.teatalk.core.log.LogLevel.V -> Log.v(TAG, line)
            com.teatalk.core.log.LogLevel.D -> Log.d(TAG, line)
            com.teatalk.core.log.LogLevel.I -> Log.i(TAG, line)
            com.teatalk.core.log.LogLevel.W -> Log.w(TAG, line)
            com.teatalk.core.log.LogLevel.E -> Log.e(TAG, line)
        }
    }

    private companion object {
        const val TAG = "TeaTalk"
    }
}
