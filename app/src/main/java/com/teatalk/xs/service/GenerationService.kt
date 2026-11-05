package com.teatalk.xs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.teatalk.xs.MainActivity
import com.teatalk.xs.R

/**
 * 0.26.0：生成期间的保活前台服务。
 *
 * Android 会在应用切到后台后回收进程、掐断 OkHttp 的流式连接；风险登记第 4 条
 * 就是「长回复被系统杀掉」。这里只做一件事：生成中挂一个低优先级通知，
 * 让进程在后台存活到流结束。
 *
 * 通知正文**不含**任何生成内容或角色名（锁屏可见），只有一句固定文案。
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(this))
        // 被系统重启时没有生成任务了，直接结束。
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.teatalk.xs.action.STOP_GENERATION"

        /**
         * 开始保活。重复调用是安全的（`startForegroundService` 对已运行的服务只是再投递一次
         * onStartCommand）；生成结束务必调用 [stop]。
         */
        fun start(context: Context) {
            val intent = Intent(context, GenerationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 结束保活。用 [Context.stopService] 而不是再发一条 `ACTION_STOP`：
         * Android 12+ 禁止后台启动前台服务，生成在后台结束时 `startForegroundService`
         * 会抛 `ForegroundServiceStartNotAllowedException`，通知就再也撤不掉了。
         * `stopService` 不受该限制，服务不在时是空操作。
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GenerationService::class.java)) }
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_generation)
                .setContentTitle(context.getString(R.string.generation_service_title))
                .setContentText(context.getString(R.string.generation_service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.generation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.generation_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
