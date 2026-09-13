package io.github.titagaki.genkaibroadcaster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.titagaki.genkaibroadcaster.streamer.StreamController
import io.github.titagaki.genkaibroadcaster.streamer.StreamFormat
import io.github.titagaki.genkaibroadcaster.streamer.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 配信中にプロセスを生かしておくための Foreground Service。
 * エンジンはプロセス共有の [StreamController] が持ち、Activity の生死とは独立に動く。
 * 通知の「停止」は同じ Controller を止めてから Service を消す。
 * 通知本文は配信画面の詳細行と同じ経過時間・送信ビットレートを毎秒更新して出す。
 */
class StreamService : Service() {

    /** 開始時に受け取った Controller の世代。onDestroy で古い世代の停止要求を無視するため */
    private var session = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null

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
                val controller = StreamController.getInstance(this)
                startForeground(NOTIFICATION_ID, buildNotification(notificationText(controller.state.value)))
                if (!controller.isStreamingNow()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startUpdating(controller)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        StreamController.getInstance(this).onServiceDestroyed(session)
        super.onDestroy()
    }

    /** 経過時間は状態の変化なしに進むので、毎秒読み直して文面が変わった時だけ通知を差し替える */
    private fun startUpdating(controller: StreamController) {
        updateJob?.cancel()
        updateJob = scope.launch {
            var shown: String? = null
            while (isActive) {
                val text = notificationText(controller.state.value)
                if (text != shown) {
                    shown = text
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
                }
                delay(1000)
            }
        }
    }

    private fun notificationText(state: StreamState): String {
        val elapsedSeconds = state.startedAtMs?.let { (SystemClock.elapsedRealtime() - it) / 1000 } ?: 0L
        return StreamFormat.notificationText(state, elapsedSeconds)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Live streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows while broadcasting" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
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
            // アプリ名は OS がヘッダーに出すのでタイトルは置かず、状態文を本文 (細字) に出す
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_stream)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_notification_stop, "停止", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
