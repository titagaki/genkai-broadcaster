package io.github.titagaki.genkaibroadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.titagaki.genkaibroadcaster.streamer.StreamController

/**
 * 配信中にプロセスを生かしておくための Foreground Service。
 * エンジンはプロセス共有の [StreamController] が持ち、Activity の生死とは独立に動く。
 * 通知の「停止」は同じ Controller を止めてから Service を消す。
 */
class StreamService : Service() {

    /** 開始時に受け取った Controller の世代。onDestroy で古い世代の停止要求を無視するため */
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
            }
            else -> {
                session = intent?.getIntExtra(EXTRA_SESSION, -1) ?: -1
                startForeground(NOTIFICATION_ID, buildNotification())
                if (!StreamController.getInstance(this).isStreamingNow()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        StreamController.getInstance(this).onServiceDestroyed(session)
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Live streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while broadcasting" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
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
            .setSmallIcon(R.drawable.ic_notification_stream)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_notification_stop, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "stream_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "io.github.titagaki.genkaibroadcaster.START"
        private const val ACTION_STOP = "io.github.titagaki.genkaibroadcaster.STOP"
        private const val EXTRA_SESSION = "stream_session"

        /** @param session 呼び出し時点の Controller 世代。Service 終了時の照合に使う */
        fun start(context: Context, session: Int) {
            val intent = Intent(context, StreamService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION, session)
            // minSdk 26 なので常に startForegroundService でよい
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
