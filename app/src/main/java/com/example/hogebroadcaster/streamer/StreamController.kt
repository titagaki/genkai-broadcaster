package com.example.hogebroadcaster.streamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
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
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StreamState(
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "待機中",
    val stats: String = "",
    val muted: Boolean = false,
    val previewReady: Boolean = false,
    val startedAtMs: Long? = null
)

/**
 * RTMP配信の中核。RootEncoder の [GenericStream] を保持し、
 * プレビュー・配信開始/停止・カメラ/マイク操作・自動再接続を担う。
 *
 * - UI層はこのクラス経由でのみ配信機能に触ること (エンコーダAPIを直接叩かない)
 * - 状態は [state] に保持し、画面の再生成後も同じ状態を購読できる
 * - プロセス単位で共有し、Activity はプレビュー用 Surface のみ着脱する
 */
class StreamController private constructor(context: Context) {

    companion object {
        private var instance: StreamController? = null

        /** Activity / Service のメインスレッドから取得する。 */
        fun getInstance(context: Context): StreamController =
            instance ?: StreamController(context.applicationContext).also { instance = it }
    }

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(StreamConfig.PREFS_FILE, Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow(StreamState())
    val state = mutableState.asStateFlow()

    private var genericStream: StreamBase? = null
    private var surfaceView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var preparedKey: String? = null
    private var generation = 0
    private var savedLensId: String? = null
    private var savedFront = false
    private var savedTorch = false

    /** マイク入力レベル観測用 (音声は加工せず素通し) */
    private val levelEffect = LevelMeterEffect()

    private fun createStream() {
        if (genericStream != null) return
        val currentGeneration = ++generation
        val checker = object : ConnectChecker {
            private fun dispatch(action: () -> Unit) {
                onMainThreadHandler {
                    if (generation == currentGeneration && state.value.isStreaming) action()
                }
            }
            override fun onConnectionStarted(url: String) = dispatch { connectionStarted() }
            override fun onConnectionSuccess() = dispatch { connectionSuccess() }
            override fun onConnectionFailed(reason: String) = dispatch { connectionFailed(reason) }
            override fun onDisconnect() = dispatch { stopStream() }
            override fun onAuthError() = dispatch {
                stopStream("認証エラー")
                toast("認証エラー")
            }
            override fun onAuthSuccess() = dispatch { toast("認証OK") }
            override fun onStreamingStats(report: StreamingStatsReport) = dispatch { streamingStats(report) }
        }
        genericStream = GenericStream(appContext, checker).apply {
            getGlInterface().autoHandleOrientation = true
            getGlInterface().setAspectRatioMode(AspectRatioMode.Adjust)
            getStreamClient().setReTries(StreamConfig.RETRY_COUNT)
            // 互換性のため明示: H.264 + AAC-LC (HE-AAC ではない)
            setVideoCodec(VideoCodec.H264)
            setAudioCodec(AudioCodec.AAC)
        }
        if (savedFront) (genericStream?.videoSource as? Camera2Source)?.switchCamera()
        (genericStream?.audioSource as? MicrophoneSource)?.setAudioEffect(levelEffect)
        toggleMute(state.value.muted)
    }

    // ---------- lifecycle ----------

    private fun releaseEngine() {
        val stream = genericStream ?: return
        savedLensId = currentLensId() ?: savedLensId
        savedFront = isFrontCamera()
        savedTorch = isTorchOn()
        ++generation // 古いクライアントの遅延通知を次の配信へ持ち越さない
        genericStream = null
        preparedKey = null
        mutableState.value = state.value.copy(previewReady = false)
        stream.release()
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
        surfaceView?.let { detachSurface(it) }
        surfaceView = sv
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPreviewIfReady()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (surfaceView === sv) genericStream?.getGlInterface()?.setPreviewResolution(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (surfaceView === sv) {
                    mutableState.value = state.value.copy(previewReady = false)
                    if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
                    if (!isStreamingNow()) releaseEngine()
                }
            }
        }
        surfaceCallback = callback
        sv.holder.addCallback(callback)
        startPreviewIfReady()
    }

    fun detachSurface(sv: SurfaceView) {
        if (surfaceView !== sv) return
        surfaceCallback?.let { sv.holder.removeCallback(it) }
        surfaceCallback = null
        surfaceView = null
        mutableState.value = state.value.copy(previewReady = false)
        if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
        if (!isStreamingNow()) releaseEngine()
    }

    fun startPreviewIfReady() {
        if (!hasPermissions()) return
        val sv = surfaceView ?: return
        if (!sv.holder.surface.isValid) return
        createStream()
        if (!isStreamingNow() && !prepareFromPrefs()) return
        if (genericStream?.isOnPreview == true) return
        if (preparedKey == null) return
        runCatching {
            val wasRunning = genericStream?.videoSource?.isRunning() == true
            genericStream?.startPreview(sv)
            // 配信中のSurface再接続ではカメラを開き直さない。
            if (!wasRunning) {
                val restoreTorch = savedTorch
                savedLensId?.let { openLens(it) }
                setTorch(restoreTorch)
            }
            mutableState.value = state.value.copy(previewReady = true)
        }.onFailure {
            mutableState.value = state.value.copy(previewReady = false)
        }
    }

    /**
     * 保存済みの映像設定で準備する。プレビューと配信で同じ解像度・方向を使う。
     * @return 準備に成功したら true
     */
    private fun prepareFromPrefs(): Boolean {
        if (isStreamingNow()) return false
        if (!hasPermissions()) {
            toast("カメラ/マイク権限が必要です")
            return false
        }
        val res = RESOLUTIONS[StreamPrefs.loadResIndex(prefs)]
        val width = res.width
        val height = res.height
        val videoBitrate = StreamPrefs.loadBitrateKbps(prefs) * 1000
        val rotation = if (StreamPrefs.loadPortrait(prefs)) {
            StreamConfig.PORTRAIT_ROTATION
        } else {
            StreamConfig.LANDSCAPE_ROTATION
        }
        createStream()
        val key = StreamConfig.preparedKey(width, height, videoBitrate, rotation)
        if (preparedKey == key) return true
        val wasPreview = genericStream?.isOnPreview == true
        if (wasPreview) genericStream?.stopPreview()
        mutableState.value = state.value.copy(previewReady = false)
        preparedKey = null
        val ok = runCatching {
            genericStream?.prepareVideo(
                width, height, videoBitrate,
                fps = StreamConfig.VIDEO_FPS,
                iFrameInterval = StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC,
                rotation = rotation,
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
            mutableState.value = state.value.copy(status = "映像・音声の設定に失敗しました。設定を確認してください")
            toast("Video/Audio 設定に失敗しました")
            return false
        }
        preparedKey = key
        if (wasPreview) startPreviewIfReady()
        return true
    }

    // ---------- streaming ----------

    fun isStreamingNow(): Boolean = state.value.isStreaming

    /** RTMP配信を開始する。URL不正時は開始せずToastで通知する */
    fun startStream(url: String) {
        if (isStreamingNow()) return
        if (!url.startsWith("rtmp://")) {
            toast("rtmp:// から始まるURLを入力してください")
            return
        }
        if (!prepareFromPrefs()) return
        mutableState.value = state.value.copy(isStreaming = true, isConnected = false,
            status = "接続中...", stats = "", startedAtMs = null)
        try {
            StreamService.start(appContext, generation)
            genericStream?.startStream(url)
        } catch (e: Exception) {
            stopStream("開始失敗: ${e.message}")
            toast("配信を開始できませんでした")
        }
    }

    fun stopStream(status: String = "切断") {
        if (!isStreamingNow()) return
        mutableState.value = state.value.copy(isStreaming = false, isConnected = false,
            status = status, stats = "", startedAtMs = null)
        // RTMP停止は非同期。終了したインスタンスは再利用しない。
        ++generation
        try {
            if (genericStream?.isStreaming == true) genericStream?.stopStream()
        } finally {
            try {
                releaseEngine()
            } finally {
                StreamService.stop(appContext)
            }
        }
        startPreviewIfReady()
    }

    /** OSによるService終了でも、通知だけ消して送信を残さない。 */
    fun onServiceDestroyed(session: Int) {
        if (generation == session) stopStream()
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
        savedLensId = currentLensId()
        savedFront = camera.getCameraFacing() == CameraHelper.Facing.FRONT
        savedTorch = camera.isLanternEnabled()
    }

    fun isFrontCamera(): Boolean {
        val camera = genericStream?.videoSource as? Camera2Source ?: return savedFront
        return camera.getCameraFacing() == CameraHelper.Facing.FRONT
    }

    /** 背面カメラのライト切替。前面では無効 (ライブラリ側でガード) */
    fun setTorch(on: Boolean) {
        val camera = genericStream?.videoSource as? Camera2Source ?: return
        runCatching { if (on) camera.enableLantern() else camera.disableLantern() }
        savedTorch = isTorchOn()
    }

    fun isTorchOn(): Boolean {
        val camera = genericStream?.videoSource as? Camera2Source ?: return savedTorch
        return if (camera.isRunning()) camera.isLanternEnabled() else savedTorch
    }

    fun toggleMute(muted: Boolean) {
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return
        runCatching {
            if (muted) mic.mute() else mic.unMute()
            mutableState.value = state.value.copy(muted = muted)
        }
    }

    /**
     * 0.0〜1.0 のマイク入力レベル。マイク停止中は 0 を返す。
     * ミュート時は生の入力値をUIへ渡さない。
     */
    fun micLevel(): Float {
        if (state.value.muted) return 0f
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
        if (!camera.isRunning()) return false
        val lens = listLenses().firstOrNull { it.cameraId == cameraId } ?: return false
        return runCatching {
            // openCameraIdだけではCamera2Source内部の前後フラグが変わらない。
            val front = camera.getCameraFacing() == CameraHelper.Facing.FRONT
            if (front != lens.isFront) camera.switchCamera()
            if (camera.getCurrentCameraId() != cameraId) camera.openCameraId(cameraId)
            savedLensId = cameraId
            savedFront = lens.isFront
            savedTorch = camera.isLanternEnabled()
            true
        }.getOrDefault(false)
    }

    /** 現在または直前に使用したレンズID。未選択なら null */
    fun currentLensId(): String? {
        val camera = genericStream?.videoSource as? Camera2Source ?: return savedLensId
        if (!camera.isRunning()) return savedLensId
        return runCatching { camera.getCurrentCameraId() }.getOrNull() ?: savedLensId
    }

    // ---------- ConnectChecker ----------

    private fun connectionStarted() {
        // リトライ開始通知で「再接続中」の理由表示を上書きしない。
        mutableState.value = state.value.copy(isConnected = false)
    }

    private fun connectionSuccess() {
        mutableState.value = state.value.copy(isConnected = true, status = "LIVE",
            startedAtMs = state.value.startedAtMs ?: SystemClock.elapsedRealtime())
        toast("配信開始!")
    }

    private fun connectionFailed(reason: String) {
        mutableState.value = state.value.copy(isConnected = false, stats = "")
        val retrying = genericStream?.getStreamClient()
            ?.reTry(StreamConfig.RETRY_DELAY_MS, reason, null) == true
        if (retrying) {
            mutableState.value = state.value.copy(status = "再接続中... ($reason)")
            toast("再接続します: $reason")
        } else {
            stopStream("接続失敗: $reason")
            toast("接続失敗: $reason")
        }
    }

    private fun streamingStats(report: StreamingStatsReport) {
        if (state.value.isConnected && report.throughput != Throughput.UNKNOWN) {
            mutableState.value = state.value.copy(
                stats = "%.1f Mbps [%s]".format(
                    report.smoothedBitrate / 1_000_000f,
                    report.throughput.name.lowercase()
                )
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }
}
