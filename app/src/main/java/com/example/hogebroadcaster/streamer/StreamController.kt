package com.example.hogebroadcaster.streamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.hogebroadcaster.StreamService
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream

/**
 * RTMP配信の中核。RootEncoder の [GenericStream] を保持し、
 * プレビュー・配信開始/停止・カメラ/マイク操作・自動再接続を担う。
 *
 * - UI層はこのクラス経由でのみ配信機能に触ること (エンコーダAPIを直接叩かない)
 * - 接続状態の変化は [onStatus] / [onStreamingChanged] / [onStats] コールバックで通知する
 * - Activity に依存しない (Application Context のみ保持) ため、画面回転等に強い
 */
class StreamController(context: Context) : ConnectChecker {

    private val appContext: Context = context.applicationContext

    /** 状態表示用テキスト ("LIVE", "接続中..." 等) の通知先 */
    var onStatus: ((String) -> Unit)? = null

    /** 配信中フラグ変化の通知先 */
    var onStreamingChanged: ((Boolean) -> Unit)? = null

    /** 送信統計テキスト ("3.0 Mbps [good]" 等) の通知先 */
    var onStats: ((String) -> Unit)? = null

    private var genericStream: StreamBase? = null
    private var surfaceView: SurfaceView? = null
    private var preparedKey: String? = null

    /** マイク入力レベル観測用 (音声は加工せず素通し) */
    private val levelEffect = LevelMeterEffect()

    init {
        genericStream = GenericStream(appContext, this).apply {
            getGlInterface().autoHandleOrientation = true
            getStreamClient().setReTries(StreamConfig.RETRY_COUNT)
            // 互換性のため明示: H.264 + AAC-LC (HE-AAC ではない)
            setVideoCodec(VideoCodec.H264)
            setAudioCodec(AudioCodec.AAC)
        }
        (genericStream?.audioSource as? MicrophoneSource)?.setAudioEffect(levelEffect)
    }

    // ---------- lifecycle ----------

    /** Activity破棄時に呼ぶ。エンコーダと常駐Serviceを解放する */
    fun release() {
        try {
            if (genericStream?.isStreaming == true) genericStream?.stopStream()
            if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
            genericStream?.release()
        } catch (_: Exception) {
            // 解放時の例外は無視 (終了処理のため)
        }
        StreamService.stop(appContext)
    }

    // ---------- permission ----------

    fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return camera && mic
    }

    // ---------- preview ----------

    /**
     * プレビュー用 Surface を登録する。Compose の AndroidView factory から呼ぶ。
     * Surface の生成・破棄に合わせてプレビューの開始/停止を自動で行う。
     */
    fun attachSurface(sv: SurfaceView) {
        surfaceView = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPreviewIfReady()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                genericStream?.getGlInterface()?.setPreviewResolution(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
            }
        })
        startPreviewIfReady()
    }

    fun startPreviewIfReady() {
        if (!hasPermissions()) return
        val sv = surfaceView ?: return
        if (genericStream?.isOnPreview == true) return
        if (preparedKey == null) {
            prepareDefault()
        }
        runCatching { genericStream?.startPreview(sv) }
    }

    /**
     * 指定の映像設定でエンコーダを準備する。既に同設定なら何もしない。
     * @return 準備に成功したら true
     */
    fun ensurePrepared(width: Int, height: Int, videoBitrate: Int): Boolean {
        val key = StreamConfig.preparedKey(width, height, videoBitrate)
        if (preparedKey == key) return true
        val wasPreview = genericStream?.isOnPreview == true
        if (wasPreview) genericStream?.stopPreview()
        val ok = runCatching {
            genericStream?.prepareVideo(
                width, height, videoBitrate,
                fps = StreamConfig.VIDEO_FPS,
                iFrameInterval = StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC,
                rotation = StreamConfig.VIDEO_ROTATION,
                profile = StreamConfig.VIDEO_PROFILE,
                level = H264Level.select(width, height, StreamConfig.VIDEO_FPS)
            ) == true &&
                genericStream?.prepareAudio(
                    StreamConfig.AUDIO_SAMPLE_RATE,
                    StreamConfig.AUDIO_STEREO,
                    StreamConfig.AUDIO_BITRATE
                ) == true
        }.getOrDefault(false)
        if (!ok) {
            toast("Video/Audio 設定に失敗しました")
            return false
        }
        preparedKey = key
        if (wasPreview || surfaceView != null) startPreviewIfReady()
        return true
    }

    private fun prepareDefault() {
        val ok = runCatching {
            genericStream?.prepareVideo(
                StreamConfig.DEFAULT_WIDTH,
                StreamConfig.DEFAULT_HEIGHT,
                StreamConfig.DEFAULT_VIDEO_BITRATE,
                fps = StreamConfig.VIDEO_FPS,
                iFrameInterval = StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC,
                rotation = StreamConfig.VIDEO_ROTATION,
                profile = StreamConfig.VIDEO_PROFILE,
                level = H264Level.select(
                    StreamConfig.DEFAULT_WIDTH,
                    StreamConfig.DEFAULT_HEIGHT,
                    StreamConfig.VIDEO_FPS
                )
            ) == true &&
                genericStream?.prepareAudio(
                    StreamConfig.AUDIO_SAMPLE_RATE,
                    StreamConfig.AUDIO_STEREO,
                    StreamConfig.AUDIO_BITRATE
                ) == true
        }.getOrDefault(false)
        if (ok) preparedKey = StreamConfig.defaultPreparedKey()
    }

    // ---------- streaming ----------

    fun isStreamingNow(): Boolean = genericStream?.isStreaming == true

    /** RTMP配信を開始する。URL不正時は開始せずToastで通知する */
    fun startStream(url: String) {
        if (genericStream?.isStreaming == true) return
        if (url.isBlank() || !url.startsWith("rtmp")) {
            toast("rtmp:// から始まるURLを入力してください")
            return
        }
        StreamService.start(appContext)
        genericStream?.startStream(url)
    }

    fun stopStream() {
        genericStream?.stopStream()
        StreamService.stop(appContext)
    }

    /** 配信中に映像ビットレートを変更する (bps) */
    fun setVideoBitrateOnFly(bitrate: Int) {
        runCatching { genericStream?.setVideoBitrateOnFly(bitrate) }
    }

    // ---------- camera / mic ----------

    /**
     * 前面/背面カメラを明示選択する。既にその面なら何もしない。
     * @param front true=前面, false=背面
     */
    fun selectCamera(front: Boolean) {
        val camera = genericStream?.videoSource as? Camera2Source ?: return
        val want = if (front) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        if (camera.getCameraFacing() != want) {
            runCatching { camera.switchCamera() }
        }
    }

    fun isFrontCamera(): Boolean =
        (genericStream?.videoSource as? Camera2Source)?.getCameraFacing() ==
            CameraHelper.Facing.FRONT

    /** 背面カメラのライト切替。前面では無効 (ライブラリ側でガード) */
    fun setTorch(on: Boolean) {
        val camera = genericStream?.videoSource as? Camera2Source ?: return
        runCatching { if (on) camera.enableLantern() else camera.disableLantern() }
    }

    fun isTorchOn(): Boolean =
        (genericStream?.videoSource as? Camera2Source)?.isLanternEnabled() == true

    fun toggleMute(muted: Boolean) {
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return
        runCatching { if (muted) mic.mute() else mic.unMute() }
    }

    /**
     * 0.0〜1.0 のマイク入力レベル。マイク停止中は 0 を返す。
     * ミュート時も生の入力値を返すため、UI側でミュートなら 0 扱いにすること。
     */
    fun micLevel(): Float {
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return 0f
        if (!mic.isRunning()) return 0f
        return (levelEffect.peak / 32768f).coerceIn(0f, 1f)
    }

    // ---------- lenses (複数背面カメラ) ----------

    /** 端末のレンズ一覧 (前面・背面すべて)。カメラ権限なしでも取得可 */
    fun listLenses(): List<LensOption> = CameraLenses.list(appContext)

    /** 指定レンズIDに切替える。成功したら true */
    fun openLens(cameraId: String): Boolean {
        val camera = genericStream?.videoSource as? Camera2Source ?: return false
        return runCatching {
            camera.openCameraId(cameraId)
            true
        }.getOrDefault(false)
    }

    /** 現在使用中のレンズID。カメラ未起動時は null */
    fun currentLensId(): String? {
        val camera = genericStream?.videoSource as? Camera2Source ?: return null
        return runCatching { camera.getCurrentCameraId() }.getOrNull()
    }

    // ---------- ConnectChecker ----------

    override fun onConnectionStarted(url: String) {
        onMainThreadHandler { onStatus?.invoke("接続中...") }
    }

    override fun onConnectionSuccess() {
        onMainThreadHandler {
            toast("配信開始!")
            onStatus?.invoke("LIVE")
            onStreamingChanged?.invoke(true)
        }
    }

    override fun onConnectionFailed(reason: String) {
        onMainThreadHandler {
            val retrying = genericStream?.getStreamClient()
                ?.reTry(StreamConfig.RETRY_DELAY_MS, reason, null) == true
            if (retrying) {
                onStatus?.invoke("再接続中... ($reason)")
                toast("再接続します: $reason")
            } else {
                onStatus?.invoke("接続失敗: $reason")
                onStreamingChanged?.invoke(false)
                StreamService.stop(appContext)
                toast("接続失敗: $reason")
            }
        }
    }

    override fun onDisconnect() {
        onMainThreadHandler {
            onStatus?.invoke("切断")
            onStreamingChanged?.invoke(false)
            onStats?.invoke("")
            StreamService.stop(appContext)
            toast("切断しました")
        }
    }

    override fun onAuthError() {
        onMainThreadHandler {
            genericStream?.stopStream()
            onStatus?.invoke("認証エラー")
            onStreamingChanged?.invoke(false)
            StreamService.stop(appContext)
            toast("認証エラー")
        }
    }

    override fun onAuthSuccess() {
        onMainThreadHandler { toast("認証OK") }
    }

    override fun onStreamingStats(report: StreamingStatsReport) {
        onMainThreadHandler {
            if (report.throughput != Throughput.UNKNOWN) {
                onStats?.invoke(
                    "%.1f Mbps [%s]".format(
                        report.smoothedBitrate / 1_000_000f,
                        report.throughput.name.lowercase()
                    )
                )
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}
