package com.example.genkaibroadcaster

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
import com.example.genkaibroadcaster.streamer.StreamController

/**
 * Foreground keep-alive service for IRL streaming.
 * The process-scoped StreamController owns the encoder independently of Activity.
 * Notification actions stop that same controller before removing the service.
 */
class StreamService : Service() {

    private var session = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                StreamController.getInstance(this).stopStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                session = intent?.getIntExtra(EXTRA_SESSION, -1) ?: -1
                startForeground(NOTIF_ID, buildNotification())
                if (!StreamController.getInstance(this).isStreamingNow()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        StreamController.getInstance(this).onServiceDestroyed(session)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Live streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while broadcasting" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Genkai Broadcaster")
            .setContentText("RTMP配信処理中 (接続・再接続を含む)。タップでアプリに戻る")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "stream_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.genkaibroadcaster.START"
        const val ACTION_STOP = "com.example.genkaibroadcaster.STOP"
        private const val EXTRA_SESSION = "stream_session"

        fun start(context: Context, session: Int) {
            val intent = Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION, session)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
