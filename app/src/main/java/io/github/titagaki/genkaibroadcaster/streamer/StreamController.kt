package io.github.titagaki.genkaibroadcaster.streamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.utils.CodecUtil
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream
import io.github.titagaki.genkaibroadcaster.BuildConfig
import io.github.titagaki.genkaibroadcaster.StreamService
import io.github.titagaki.genkaibroadcaster.comment.CommentEntry
import io.github.titagaki.genkaibroadcaster.comment.CommentOverlay
import io.github.titagaki.genkaibroadcaster.comment.CommentPosition
import io.github.titagaki.genkaibroadcaster.comment.CommentSourceClient
import io.github.titagaki.genkaibroadcaster.comment.CommentSourceState
import io.github.titagaki.genkaibroadcaster.comment.CommentSources
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RTMP配信の中核。RootEncoder の [GenericStream] を保持し、
 * プレビュー・配信開始/停止・マイク・自動再接続を担う。
 * カメラのレンズ切替とズームは [CameraController] に委譲し、UI 向けの入口だけをここに置く。
 * コメント表示は配信中だけ [CommentSourceClient] で提供アプリに bind し、[CommentOverlay] で映像に載せる。
 *
 * - UI層はこのクラス経由でのみ配信機能に触ること (エンコーダAPIを直接叩かない)
 * - 状態は [state] に保持し、画面の再生成後も同じ状態を購読できる
 * - 一時的な通知文は [messages] に流し、表示方法 (Toast 等) は UI 層が決める
 * - プロセス単位で共有し、Activity はプレビュー用 Surface のみ着脱する
 */
class StreamController private constructor(context: Context) {

    companion object {
        private const val TAG = "StreamController"
        private var instance: StreamController? = null

        /** Activity / Service のメインスレッドから取得する。 */
        fun getInstance(context: Context): StreamController =
            instance ?: StreamController(context.applicationContext).also { instance = it }
    }

    private val appContext: Context = context.applicationContext
    private val prefs = StreamPrefs(appContext)

    private val mutableState = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    /** ユーザーへ一時的に見せる通知文。購読者がいない間に出た分は捨てる */
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private var genericStream: StreamBase? = null
    private var surfaceView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var preparedConfig: StreamPreparationConfig? = null
    /** エンジン世代。解放済みエンジンからの遅延通知を捨てるために使う */
    private var generation = 0

    // レンズ列挙は初回のプレビュー開始まで遅らせる (起動直後のメインスレッドを塞がない)
    private val camera by lazy {
        CameraController(
            catalog = LensCatalog(CameraLenses.list(appContext)),
            host = object : CameraController.Host {
                override val camera: Camera2Source? get() = genericStream?.videoSource as? Camera2Source
                override val isOnPreview: Boolean get() = genericStream?.isOnPreview == true
                override val isStreaming: Boolean get() = isStreamingNow()
                override val state: StreamState get() = mutableState.value
                override fun updateState(transform: (StreamState) -> StreamState) = mutableState.update(transform)
                override fun postMessage(message: String) = this@StreamController.postMessage(message)
                override fun stopStream(status: String) = this@StreamController.stopStream(status)
                override fun releaseEngine() = this@StreamController.releaseEngine()
                override fun startPreviewIfReady() = this@StreamController.startPreviewIfReady()
            }
        )
    }

    /** マイク入力レベル観測用 (音声は加工せず素通し) */
    private val levelEffect = LevelMeterEffect()

    /**
     * 配信前の音量メーター用にマイクだけ回すときの受け口。PCM は捨てる。
     * 配信開始時は StreamBase が自分の受け口へ差し替える (MicrophoneSource.start は
     * 稼働中でもコールバックを更新してから return する)。
     */
    private val monitorSink = object : GetMicrophoneData {
        override fun inputPCMData(frame: Frame) = Unit
    }

    /** コメントの描画。フィルタはエンジン準備のたびに作り直して GL に渡す */
    private val commentOverlay = CommentOverlay(
        maxLines = StreamConfig.COMMENT_MAX_LINES,
        displayMillis = StreamConfig.COMMENT_DISPLAY_MS,
        textSizeRatio = StreamConfig.COMMENT_TEXT_SIZE_RATIO
    ).apply { setPosition(prefs.loadCommentPosition()) }

    /** コメント提供アプリとの接続。配信中だけ bind する */
    private val commentClient = CommentSourceClient(
        appContext,
        listener = object : CommentSourceClient.Listener {
            override fun onComments(entries: List<CommentEntry>) = commentOverlay.add(entries)
            override fun onStateChanged(state: CommentSourceState) {
                mutableState.update { it.copy(commentSource = state) }
            }
        },
        rebindDelayMillis = StreamConfig.COMMENT_REBIND_DELAY_MS
    )

    // ---------- lifecycle ----------

    private fun createStream() {
        if (genericStream != null) return
        val currentGeneration = ++generation
        val checker = object : ConnectChecker {
            private fun dispatch(action: () -> Unit) {
                onMainThreadHandler {
                    if (generation == currentGeneration && isStreamingNow()) action()
                }
            }
            override fun onConnectionStarted(url: String) = dispatch { connectionStarted() }
            override fun onConnectionSuccess() = dispatch { connectionSuccess() }
            override fun onConnectionFailed(reason: String) = dispatch { connectionFailed(reason) }
            override fun onDisconnect() = dispatch { stopStream() }
            override fun onAuthError() = dispatch {
                stopStream("認証エラー")
                postMessage("認証エラー")
            }
            override fun onAuthSuccess() = dispatch { postMessage("認証OK") }
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
        (genericStream?.videoSource as? Camera2Source)?.let { camera.onEngineCreated(it) }
        (genericStream?.audioSource as? MicrophoneSource)?.setAudioEffect(levelEffect)
        toggleMute(state.value.muted)
    }

    private fun releaseEngine() {
        val stream = genericStream ?: return
        camera.onEngineReleasing()
        ++generation // 古いクライアントの遅延通知を次の配信へ持ち越さない
        genericStream = null
        preparedConfig = null
        commentOverlay.detachFilter()
        mutableState.update { it.copy(previewReady = false) }
        stopMicMonitor(stream)
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
                if (surfaceView === sv) stopPreviewAndReleaseIfIdle()
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
        stopPreviewAndReleaseIfIdle()
    }

    private fun stopPreviewAndReleaseIfIdle() {
        mutableState.update { it.copy(previewReady = false) }
        stopMicMonitor()
        if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
        if (!isStreamingNow()) releaseEngine()
    }

    fun startPreviewIfReady() {
        if (!hasPermissions()) return
        val sv = surfaceView ?: return
        if (!sv.holder.surface.isValid) return
        createStream()
        if (!isStreamingNow() && !prepareFromPrefs()) return
        startPreparedPreviewIfReady(sv)
    }

    /** 準備済みエンジンへSurfaceを接続する。映像設定の準備は呼び出し側で完了させる。 */
    private fun startPreparedPreviewIfReady(sv: SurfaceView? = surfaceView) {
        val previewSurface = sv ?: return
        if (!previewSurface.holder.surface.isValid) return
        if (genericStream?.isOnPreview == true) return
        if (preparedConfig == null) return
        runCatching {
            val wasRunning = genericStream?.videoSource?.isRunning() == true
            if (!wasRunning) camera.onCameraOpening()
            genericStream?.startPreview(previewSurface)
            startMicMonitor()
            // 配信中のSurface再接続ではカメラを開き直さない。
            if (!wasRunning) {
                mutableState.update { it.copy(previewReady = false, cameraError = null) }
            } else {
                camera.refreshZoom()
                mutableState.update { it.copy(previewReady = true) }
            }
        }.onFailure {
            Log.w(TAG, "startPreview failed", it)
            mutableState.update { it.copy(previewReady = false) }
        }
    }

    /**
     * 保存済みの映像設定で準備する。プレビューと配信で同じ解像度・方向を使う。
     * @return 準備に成功したら true
     */
    private fun prepareFromPrefs(): Boolean {
        if (isStreamingNow()) return false
        if (!hasPermissions()) {
            postMessage("カメラ/マイク権限が必要です")
            return false
        }
        val res = prefs.loadResolution()
        val config = StreamPreparationConfig(
            width = res.width,
            height = res.height,
            fps = prefs.loadFps(),
            videoBitrateBps = bitrateKbpsToBps(prefs.loadBitrateKbps()),
            rotation = if (prefs.loadPortrait()) {
                StreamConfig.PORTRAIT_ROTATION
            } else {
                StreamConfig.LANDSCAPE_ROTATION
            },
            softwareEncoder = prefs.loadSoftwareEncoder()
        )
        createStream()
        if (preparedConfig == config) return true
        stopMicMonitor()
        if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
        mutableState.update { it.copy(previewReady = false) }
        preparedConfig = null
        val ok = runCatching {
            // prepareVideo 内でエンコーダを選び直すので、その前に種別を指定する
            genericStream?.forceCodecType(
                if (config.softwareEncoder) CodecUtil.CodecType.SOFTWARE
                else CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND,
                CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND
            )
            genericStream?.prepareVideo(
                config.width, config.height, config.videoBitrateBps,
                fps = config.fps,
                iFrameInterval = StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC,
                rotation = config.rotation,
                profile = StreamConfig.VIDEO_PROFILE,
                level = H264Level.select(config.width, config.height, config.fps)
            ) == true &&
                genericStream?.prepareAudio(
                    StreamConfig.AUDIO_SAMPLE_RATE,
                    StreamConfig.AUDIO_STEREO,
                    StreamConfig.AUDIO_BITRATE
                ) == true
        }.onFailure { Log.w(TAG, "prepareVideo/prepareAudio threw", it) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "prepare failed: $config")
            mutableState.update { it.copy(status = "映像・音声の設定に失敗しました。設定を確認してください") }
            postMessage("Video/Audio 設定に失敗しました")
            return false
        }
        preparedConfig = config
        attachCommentOverlay(config)
        return true
    }

    /**
     * コメント用フィルタを GL に渡す。GL は準備し直し (stopPreview) で止まるとフィルタ一覧を空にするので、
     * 準備が成功するたびに新しいフィルタを作って `setFilter` (一覧の置き換え) で渡す。
     * GL が未起動でもキューに積まれ、起動後の最初の描画で反映される。
     */
    private fun attachCommentOverlay(config: StreamPreparationConfig) {
        val portrait = config.rotation == StreamConfig.PORTRAIT_ROTATION
        commentOverlay.setOutputSize(
            width = if (portrait) config.height else config.width,
            height = if (portrait) config.width else config.height
        )
        runCatching { genericStream?.getGlInterface()?.setFilter(commentOverlay.attachNewFilter()) }
            .onFailure { Log.w(TAG, "setFilter failed", it) }
    }

    // ---------- streaming ----------

    fun isStreamingNow(): Boolean = state.value.isStreaming

    /** RTMP配信を開始する。URL不正時は開始せず [messages] で通知する */
    fun startStream(url: String) {
        if (isStreamingNow()) return
        if (!StreamPrefs.isAcceptedRtmpUrl(url)) {
            postMessage("rtmp:// から始まるURLを入力してください")
            return
        }
        val resumePreview = genericStream?.isOnPreview == true
        if (!prepareFromPrefs()) return
        if (resumePreview) startPreparedPreviewIfReady()
        mutableState.update {
            it.copy(
                isStreaming = true, isConnected = false, status = "接続中...",
                stats = "", smoothedBitrateBps = 0L, startedAtMs = null
            )
        }
        try {
            StreamService.start(appContext, generation)
            genericStream?.startStream(url)
        } catch (e: Exception) {
            Log.w(TAG, "startStream failed", e)
            stopStream("開始失敗: ${e.message}")
            postMessage("配信を開始できませんでした")
            return
        }
        bindCommentSource()
    }

    /** 設定にコメント提供アプリがあれば bind する。不在なら通知だけ出し、配信は続ける */
    private fun bindCommentSource() {
        val key = prefs.loadCommentSource() ?: return
        val source = CommentSources.find(appContext, key)
        if (source == null) {
            mutableState.update { it.copy(commentSource = CommentSourceState.Error("提供アプリが見つかりません")) }
            postMessage("コメント提供アプリが見つかりません")
            return
        }
        commentClient.bind(source)
    }

    /** コメントの表示位置を変える。配信中でも即時反映する (保存は UI 側の StreamPrefs) */
    fun setCommentPosition(position: CommentPosition) = commentOverlay.setPosition(position)

    private fun unbindCommentSource() {
        commentClient.unbind()
        commentOverlay.clear()
        mutableState.update { it.copy(commentSource = CommentSourceState.Off) }
    }

    fun stopStream(status: String = "切断") {
        if (!isStreamingNow()) return
        mutableState.update {
            it.copy(
                isStreaming = false, isConnected = false, status = status,
                stats = "", smoothedBitrateBps = 0L, startedAtMs = null
            )
        }
        unbindCommentSource()
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

    /** 配信中に映像ビットレートを変更する (kbps) */
    fun setVideoBitrateKbpsOnFly(bitrateKbps: Int) {
        runCatching { genericStream?.setVideoBitrateOnFly(bitrateKbpsToBps(bitrateKbps)) }
            .onFailure { Log.w(TAG, "setVideoBitrateOnFly failed", it) }
    }

    private fun bitrateKbpsToBps(bitrateKbps: Int): Int = bitrateKbps * 1000

    // ---------- camera (委譲) ----------

    /** 同じ実レンズを倍率でまとめた、ユーザー表示用の選択肢。 */
    fun cameraZoomChoices(front: Boolean): List<CameraZoomChoice> = camera.zoomChoices(front)

    fun selectCameraZoom(choice: CameraZoomChoice) = camera.selectZoomChoice(choice)

    /** 前面/背面カメラを明示選択する。既にその面なら何もしない。 */
    fun selectCamera(front: Boolean) = camera.selectCamera(front)

    /** 現在倍率を基準にピンチ操作の倍率変化を適用する。 */
    fun changeZoomBy(scale: Float): Float = camera.changeZoomBy(scale)

    fun isDebuggable(): Boolean = BuildConfig.DEBUG

    fun zoomDiagnostics(): String = camera.zoomDiagnostics()

    /** デバッグビルドで両方のCapability分岐を強制確認する。 */
    fun setZoomDebugOverride(override: ZoomDebugOverride) {
        if (!isDebuggable()) return
        camera.setZoomDebugOverride(override)
    }

    // ---------- mic ----------

    fun toggleMute(muted: Boolean) {
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return
        runCatching {
            if (muted) mic.mute() else mic.unMute()
            mutableState.update { it.copy(muted = muted) }
        }.onFailure { Log.w(TAG, "toggleMute($muted) failed", it) }
    }

    /**
     * 配信前でも音量メーターが動くよう、プレビュー中はマイクだけ起動しておく。
     * prepareAudio 済み (preparedConfig != null) が前提。配信中は StreamBase が管理するので触らない。
     */
    private fun startMicMonitor() {
        if (isStreamingNow() || preparedConfig == null) return
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return
        if (mic.isRunning()) return
        runCatching { mic.start(monitorSink) }
            .onFailure { Log.w(TAG, "mic monitor start failed", it) }
    }

    /** 配信中は StreamBase 側の stopSources が止めるので、自前で回している時だけ止める */
    private fun stopMicMonitor(stream: StreamBase? = genericStream) {
        if (isStreamingNow()) return
        val mic = stream?.audioSource as? MicrophoneSource ?: return
        if (mic.isRunning()) {
            runCatching { mic.stop() }
                .onFailure { Log.w(TAG, "mic monitor stop failed", it) }
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

    // ---------- ConnectChecker ----------

    private fun connectionStarted() {
        // リトライ開始通知で「再接続中」の理由表示を上書きしない。
        mutableState.update { it.copy(isConnected = false) }
    }

    private fun connectionSuccess() {
        mutableState.update {
            it.copy(isConnected = true, status = "LIVE", startedAtMs = it.startedAtMs ?: SystemClock.elapsedRealtime())
        }
        postMessage("配信開始!")
    }

    private fun connectionFailed(reason: String) {
        mutableState.update { it.copy(isConnected = false, stats = "", smoothedBitrateBps = 0L) }
        val retrying = genericStream?.getStreamClient()
            ?.reTry(StreamConfig.RETRY_DELAY_MS, reason, null) == true
        if (retrying) {
            mutableState.update { it.copy(status = "再接続中... ($reason)") }
            postMessage("再接続します: $reason")
        } else {
            stopStream("接続失敗: $reason")
            postMessage("接続失敗: $reason")
        }
    }

    private fun streamingStats(report: StreamingStatsReport) {
        if (state.value.isConnected && report.throughput != Throughput.UNKNOWN) {
            mutableState.update {
                it.copy(
                    stats = "${StreamFormat.mbps(report.smoothedBitrate)} Mbps [${report.throughput.name.lowercase()}]",
                    smoothedBitrateBps = report.smoothedBitrate
                )
            }
        }
    }

    private fun postMessage(message: String) {
        mutableMessages.tryEmit(message)
    }
}

/** prepareVideo/prepareAudio に渡した条件。同じなら再準備しない */
private data class StreamPreparationConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Int,
    val rotation: Int,
    val softwareEncoder: Boolean
)
