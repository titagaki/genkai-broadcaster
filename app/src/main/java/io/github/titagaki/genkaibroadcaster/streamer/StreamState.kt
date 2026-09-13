package io.github.titagaki.genkaibroadcaster.streamer

/** ズームの実現方式。AUTO_LENS は論理マルチカメラに倍率を渡して OS にレンズを選ばせる */
enum class CameraZoomMode { DIGITAL, AUTO_LENS }

/** デバッグビルドでズーム方式を強制するためのスイッチ */
enum class ZoomDebugOverride { AUTO, FORCE_DIGITAL, FORCE_LOGICAL }

/**
 * 現在のズーム状態。倍率は「主カメラ基準の表示倍率」で、端末の生の倍率は [baseRatio] で換算する。
 */
data class CameraZoomState(
    val ratio: Float = 1f,
    val minRatio: Float = 1f,
    val maxRatio: Float = 1f,
    val baseRatio: Float = 1f,
    val mode: CameraZoomMode = CameraZoomMode.DIGITAL,
    val debugOverride: ZoomDebugOverride = ZoomDebugOverride.AUTO
)

/** ユーザーに見せるズーム選択肢 (表示倍率 + それを実現するレンズ) */
data class CameraZoomChoice(val ratio: Float, val lens: LensOption)

/**
 * UI が購読する配信・カメラの確定状態。
 * 切替要求中の値 (復元用) は含めず、Capture 完了などで確定したものだけを載せる。
 */
data class StreamState(
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "待機中",
    val stats: String = "",
    /** 平滑化した送信ビットレート (bps)。[stats] と同じ条件で更新し、未計測・切断時は 0 */
    val smoothedBitrateBps: Long = 0L,
    val muted: Boolean = false,
    val previewReady: Boolean = false,
    val startedAtMs: Long? = null,
    val zoom: CameraZoomState = CameraZoomState(),
    val selectedLens: LensOption? = null,
    val cameraIsFront: Boolean = false,
    val cameraError: String? = null
)
