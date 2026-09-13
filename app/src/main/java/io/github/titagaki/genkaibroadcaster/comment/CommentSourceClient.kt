package io.github.titagaki.genkaibroadcaster.comment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log

/**
 * コメント提供アプリの Service へ bind し、[ICommentListener] で受け取ったものを
 * メインスレッドの [Listener] へ渡す。配信中だけ使う (bind/unbind は `StreamController` が呼ぶ)。
 *
 * - メインスレッドから呼ぶこと。ServiceConnection のコールバックもメインスレッドで来る
 * - AIDL のコールバックは Binder スレッドで届くので [handler] でメインへ移す
 * - [session] で世代を持ち、unbind 後に遅れて届いたコールバックは捨てる
 * - 提供側プロセスが落ちても bind は残り、OS が再起動して `onServiceConnected` を再度呼ぶので
 *   自前の再 bind は `onBindingDied` (提供側の更新・削除) のときだけ行う
 */
class CommentSourceClient(
    context: Context,
    private val listener: Listener,
    private val rebindDelayMillis: Long
) {
    interface Listener {
        fun onComments(entries: List<CommentEntry>)
        fun onStateChanged(state: CommentSourceState)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var session = 0
    private var source: CommentSource? = null
    private var connection: ServiceConnection? = null
    private var remote: ICommentSource? = null
    private var rebindRunnable: Runnable? = null

    val isBound: Boolean get() = connection != null

    /** bind を開始する。結果は [Listener.onStateChanged] で通知する (同期的には接続しない) */
    fun bind(target: CommentSource) {
        unbind()
        val currentSession = ++session
        source = target
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (currentSession != session) return
                connected(currentSession, target, service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (currentSession != session) return
                // 提供側プロセスが落ちた。bind は残り OS が再起動するので待つ
                remote = null
                listener.onStateChanged(CommentSourceState.Connecting(target.label))
            }

            override fun onBindingDied(name: ComponentName) {
                if (currentSession != session) return
                // 提供側が更新・削除された。bind し直さないと復帰しない
                remote = null
                listener.onStateChanged(CommentSourceState.Connecting(target.label))
                scheduleRebind(target)
            }

            override fun onNullBinding(name: ComponentName) {
                if (currentSession != session) return
                fail("${target.label} が接続を受け付けません")
            }
        }
        connection = conn
        listener.onStateChanged(CommentSourceState.Connecting(target.label))
        val intent = Intent(CommentSources.ACTION).setComponent(target.component)
        val started = try {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bindService denied: ${target.component}", e)
            false
        }
        if (!started) {
            // 戻り値に関わらず unbindService は必要 (bindService の契約)
            fail("${target.label} に接続できません")
        }
    }

    /** 提供側から listener を外し bind を解く。未 bind なら何もしない */
    fun unbind() {
        cancelRebind()
        val conn = connection ?: return
        ++session
        remote?.let { r ->
            runCatching { r.unregisterListener(remoteListener) }
                .onFailure { Log.w(TAG, "unregisterListener failed", it) }
        }
        remote = null
        connection = null
        source = null
        runCatching { appContext.unbindService(conn) }
            .onFailure { Log.w(TAG, "unbindService failed", it) }
    }

    private fun connected(currentSession: Int, target: CommentSource, binder: IBinder) {
        val r = ICommentSource.Stub.asInterface(binder)
        try {
            val version = r.version
            if (version != ICommentSource.VERSION) {
                fail("${target.label} の版が合いません (v$version)")
                return
            }
            val name = r.sourceName?.ifBlank { null } ?: target.label
            r.registerListener(remoteListener)
            remote = r
            listener.onStateChanged(CommentSourceState.Ready(name))
        } catch (e: RemoteException) {
            Log.w(TAG, "connect to ${target.component} failed", e)
            if (currentSession == session) fail("${target.label} との通信に失敗")
        }
    }

    /** 接続をあきらめて unbind し、理由を通知する */
    private fun fail(message: String) {
        unbind()
        listener.onStateChanged(CommentSourceState.Error(message))
    }

    private fun scheduleRebind(target: CommentSource) {
        cancelRebind()
        val r = Runnable {
            rebindRunnable = null
            if (connection != null) bind(target)
        }
        rebindRunnable = r
        handler.postDelayed(r, rebindDelayMillis)
    }

    private fun cancelRebind() {
        rebindRunnable?.let { handler.removeCallbacks(it) }
        rebindRunnable = null
    }

    /** 提供側から呼ばれる側。Binder スレッドで届くのでメインへ移し、世代が古ければ捨てる */
    private val remoteListener = object : ICommentListener.Stub() {
        override fun onComments(comments: MutableList<CommentEntry>?) {
            val entries = comments?.toList().orEmpty()
            if (entries.isEmpty()) return
            val expected = session
            handler.post {
                if (expected == session && connection != null) listener.onComments(entries)
            }
        }

        override fun onStateChanged(state: Int, detail: String?) {
            val expected = session
            handler.post {
                if (expected != session || connection == null) return@post
                val name = source?.label ?: return@post
                listener.onStateChanged(
                    when (state) {
                        ICommentSource.STATE_READY -> CommentSourceState.Ready(name)
                        ICommentSource.STATE_ERROR ->
                            CommentSourceState.Error(detail?.ifBlank { null } ?: "$name でエラー")
                        else -> CommentSourceState.Connecting(name)
                    }
                )
            }
        }
    }

    private companion object {
        const val TAG = "CommentSourceClient"
    }
}
