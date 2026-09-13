package io.github.titagaki.genkaibroadcaster.streamer

import java.util.Locale

/**
 * 配信画面と通知で共通に使う表示文字列の整形。
 * Android に依存しない純粋ロジックなので JVM テスト対象。
 */
object StreamFormat {
    /** 経過時間。`mm:ss`、1時間を超えると `hh:mm:ss` */
    fun elapsed(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    /** 送信ビットレート (bps) を `x.x` の Mbps 表記にする。単位は呼び出し側で付ける */
    fun mbps(bps: Long): String = String.format(Locale.US, "%.1f", bps / 1_000_000.0)

    /**
     * 通知本文。接続済みなら「Live: mm:ss / x.xMbps」(ビットレート未計測なら時間だけ)、
     * 未接続なら状態文 (接続中... / 再接続中... など) をそのまま出す。
     */
    fun notificationText(state: StreamState, elapsedSeconds: Long): String {
        if (!state.isConnected) return state.status
        val parts = buildList {
            add(elapsed(elapsedSeconds))
            if (state.smoothedBitrateBps > 0) add("${mbps(state.smoothedBitrateBps)}Mbps")
        }
        return "Live: " + parts.joinToString(" / ")
    }
}
