package com.example.hogebroadcaster.streamer

import android.media.MediaCodecInfo

/**
 * 配信に関する定数を一元管理する。
 *
 * マジックナンバーの散在を防ぐため、エンコード設定・再接続ポリシー・UI範囲は
 * すべてここで定義し、各層からはこの定数を参照すること。
 */
object StreamConfig {
    // ---- Video ----
    const val VIDEO_ROTATION = 0
    const val VIDEO_FPS = 30
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val DEFAULT_VIDEO_BITRATE = 3_000_000 // bps

    /**
     * H.264 Constrained Baseline。
     * Bフレームなし・参照フレーム少なめで、古いPCプレーヤーとの互換性が最も高い。
     * API 21+ で利用可 (minSdk 26 のため常時利用可)。
     */
    const val VIDEO_PROFILE = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline

    /** キーフレーム間隔 (秒)。短めにして途中参加時の復帰を早くする */
    const val VIDEO_KEYFRAME_INTERVAL_SEC = 2

    // ---- Audio ----
    const val AUDIO_SAMPLE_RATE = 32000
    const val AUDIO_STEREO = true
    const val AUDIO_BITRATE = 128_000 // bps

    // ---- Reconnect policy ----
    const val RETRY_COUNT = 10
    const val RETRY_DELAY_MS = 5000L

    // ---- Bitrate slider (UI, kbps) ----
    const val BITRATE_MIN_KBPS = 800
    const val BITRATE_MAX_KBPS = 8000
    const val BITRATE_SLIDER_STEPS = 24
    const val DEFAULT_BITRATE_KBPS = 3000

    // ---- Resolution ----
    const val DEFAULT_RES_INDEX = 1 // 720p

    // ---- Prefs ----
    const val PREFS_FILE = "hoge_broadcaster"
    const val DEFAULT_SERVER = "rtmp://pcgw.pgw.jp/live"

    /** prepare済み設定の識別キー。設定変更検出に使う */
    fun preparedKey(width: Int, height: Int, videoBitrate: Int): String =
        "$width-$height-$videoBitrate"

    fun defaultPreparedKey(): String =
        preparedKey(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_VIDEO_BITRATE)
}

data class Resolution(val label: String, val width: Int, val height: Int)

val RESOLUTIONS = listOf(
    Resolution("480p (854x480)", 854, 480),
    Resolution("720p (1280x720)", 1280, 720),
    Resolution("1080p (1920x1080)", 1920, 1080),
)
