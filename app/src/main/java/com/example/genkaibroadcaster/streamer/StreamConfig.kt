package com.example.genkaibroadcaster.streamer

import android.media.MediaCodecInfo

/**
 * 配信に関する定数を一元管理する。
 *
 * マジックナンバーの散在を防ぐため、エンコード設定・再接続ポリシー・UI範囲は
 * すべてここで定義し、各層からはこの定数を参照すること。
 */
object StreamConfig {
    // ---- Video ----
    const val LANDSCAPE_ROTATION = 0
    const val PORTRAIT_ROTATION = 90
    const val DEFAULT_PORTRAIT = false // 既存の横長出力を維持
    const val DEFAULT_VIDEO_FPS = 30
    /** 選択できるフレームレート。60 はカメラが対応しない端末では実効 30 になる */
    val FPS_OPTIONS = listOf(15, 24, 30, 60)
    const val CAMERA_SWITCH_TIMEOUT_MS = 5000L

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

    // ---- Bitrate stepper (UI, kbps) ----
    const val BITRATE_MIN_KBPS = 100
    const val BITRATE_MAX_KBPS = 10000
    /** −/+ ボタン1回で増減する量 */
    const val BITRATE_STEP_KBPS = 100
    const val DEFAULT_BITRATE_KBPS = 3000

    // ---- Resolution ----
    const val DEFAULT_RES_INDEX = 2 // 720p

    // ---- Prefs ----
    const val PREFS_FILE = "genkai_broadcaster"
    const val DEFAULT_SERVER = "rtmp://pcgw.pgw.jp/live"
    /** 移行時・初回起動時に作られる最初の接続先の名前 */
    const val DEFAULT_DESTINATION_NAME = "PeerCast Gateway"

    // ---- Destination presets ----
    /** Twitch の共通インジェスト。地域別サーバーでも可 */
    const val TWITCH_SERVER = "rtmp://live.twitch.tv/app"

}

internal data class StreamPreparationConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val rotation: Int
)

data class Resolution(val label: String, val width: Int, val height: Int) {
    fun outputWidth(portrait: Boolean): Int = if (portrait) height else width
    fun outputHeight(portrait: Boolean): Int = if (portrait) width else height
    fun dimensions(portrait: Boolean): String = "${outputWidth(portrait)} x ${outputHeight(portrait)}"
}

/**
 * label は一般的な呼び名 + 通称 (短辺の画素数 + p) を併記する。
 * 保存値はこのリストのインデックスなので、順序変更・挿入時は DEFAULT_RES_INDEX と
 * 既存の保存値のずれに注意。
 */
val RESOLUTIONS = listOf(
    Resolution("省データ (360p)", 640, 360),
    Resolution("SD (480p)", 854, 480),
    Resolution("HD (720p)", 1280, 720),
    Resolution("Full HD (1080p)", 1920, 1080),
)
