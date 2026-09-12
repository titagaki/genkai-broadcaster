package com.example.genkaibroadcaster.streamer

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.genkaibroadcaster.StreamService
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
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

enum class CameraZoomMode { DIGITAL, AUTO_LENS }

enum class ZoomDebugOverride { AUTO, FORCE_DIGITAL, FORCE_LOGICAL }

data class CameraZoomState(
    val ratio: Float = 1f,
    val minRatio: Float = 1f,
    val maxRatio: Float = 1f,
    val baseRatio: Float = 1f,
    val mode: CameraZoomMode = CameraZoomMode.DIGITAL,
    val debugOverride: ZoomDebugOverride = ZoomDebugOverride.AUTO
)

data class CameraZoomChoice(val ratio: Float, val lens: LensOption)

data class StreamState(
    val isStreaming: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "待機中",
    val stats: String = "",
    val muted: Boolean = false,
    val previewReady: Boolean = false,
    val startedAtMs: Long? = null,
    val zoom: CameraZoomState = CameraZoomState(),
    val selectedLens: LensOption? = null,
    val cameraIsFront: Boolean = false,
    val cameraError: String? = null
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
    private val availableLenses by lazy { CameraLenses.list(appContext) }
    private val mainBackFov by lazy {
        val candidates = availableLenses.filter { !it.isFront && it.fovDegrees != null }
        val physical = candidates.filter { it.physicalCameraId != null }
        val standalone = candidates.filter { !it.isLogicalMultiCamera }
        (physical.ifEmpty { standalone }.ifEmpty { candidates })
            .minByOrNull { abs(it.fovDegrees!! - 70f) }?.fovDegrees
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mutableState = MutableStateFlow(StreamState())
    val state = mutableState.asStateFlow()

    private var genericStream: StreamBase? = null
    private var surfaceView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var preparedConfig: StreamPreparationConfig? = null
    private var generation = 0
    private var savedLens: LensOption? = null
    private var pendingLens: LensOption? = null
    private var savedFront = false
    private var savedZoomRatio = 1f
    private var zoomDebugOverride = ZoomDebugOverride.AUTO
    private var activePhysicalCameraId: String? = null
    @Volatile
    private var activeCaptureSession: CameraCaptureSession? = null
    private val seenCaptureSessions = Collections.newSetFromMap(
        IdentityHashMap<CameraCaptureSession, Boolean>()
    )
    @Volatile
    private var cameraCaptureReady = false
    private var cameraChangeGeneration = 0
    @Volatile
    private var awaitingCaptureGeneration: Int? = null

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
        (genericStream?.videoSource as? Camera2Source)?.let { camera ->
            configureCameraCallbacks(camera)
            if (savedFront) camera.switchCamera()
        }
        (genericStream?.audioSource as? MicrophoneSource)?.setAudioEffect(levelEffect)
        toggleMute(state.value.muted)
    }

    // ---------- lifecycle ----------

    private fun releaseEngine() {
        val stream = genericStream ?: return
        val requestedLens = pendingLens
        when {
            requestedLens != null -> {
                savedLens = requestedLens
                savedFront = requestedLens.isFront
            }
            awaitingCaptureGeneration == null -> {
                savedLens = state.value.selectedLens ?: savedLens
                savedFront = state.value.cameraIsFront
                savedZoomRatio = state.value.zoom.ratio
            }
        }
        pendingLens = null
        activePhysicalCameraId = null
        cameraCaptureReady = false
        activeCaptureSession = null
        awaitingCaptureGeneration = null
        synchronized(seenCaptureSessions) { seenCaptureSessions.clear() }
        ++cameraChangeGeneration
        ++generation // 古いクライアントの遅延通知を次の配信へ持ち越さない
        genericStream = null
        preparedConfig = null
        mutableState.value = state.value.copy(previewReady = false)
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
        mutableState.value = state.value.copy(previewReady = false)
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
            if (!wasRunning) {
                waitForCameraCapture()
                pendingLens = savedLens ?: if (savedFront) null else defaultBackLens()
            }
            genericStream?.startPreview(previewSurface)
            startMicMonitor()
            // 配信中のSurface再接続ではカメラを開き直さない。
            if (!wasRunning) {
                mutableState.value = state.value.copy(previewReady = false, cameraError = null)
            } else {
                refreshZoomState()
                mutableState.value = state.value.copy(previewReady = true)
            }
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
        val config = StreamPreparationConfig(
            width = res.width,
            height = res.height,
            fps = StreamPrefs.loadFps(prefs),
            videoBitrateBps = bitrateKbpsToBps(StreamPrefs.loadBitrateKbps(prefs)),
            rotation = if (StreamPrefs.loadPortrait(prefs)) {
                StreamConfig.PORTRAIT_ROTATION
            } else {
                StreamConfig.LANDSCAPE_ROTATION
            }
        )
        createStream()
        if (preparedConfig == config) return true
        stopMicMonitor()
        if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
        mutableState.value = state.value.copy(previewReady = false)
        preparedConfig = null
        val ok = runCatching {
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
        }.getOrDefault(false)
        if (!ok) {
            mutableState.value = state.value.copy(status = "映像・音声の設定に失敗しました。設定を確認してください")
            toast("Video/Audio 設定に失敗しました")
            return false
        }
        preparedConfig = config
        return true
    }

    // ---------- streaming ----------

    fun isStreamingNow(): Boolean = state.value.isStreaming

    /** RTMP配信を開始する。URL不正時は開始せずToastで通知する */
    fun startStream(url: String) {
        if (isStreamingNow()) return
        if (!StreamPrefs.isAcceptedRtmpUrl(url)) {
            toast("rtmp:// から始まるURLを入力してください")
            return
        }
        val resumePreview = genericStream?.isOnPreview == true
        if (!prepareFromPrefs()) return
        if (resumePreview) startPreparedPreviewIfReady()
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

    /** 配信中に映像ビットレートを変更する (kbps) */
    fun setVideoBitrateKbpsOnFly(bitrateKbps: Int) {
        runCatching { genericStream?.setVideoBitrateOnFly(bitrateKbpsToBps(bitrateKbps)) }
    }

    private fun bitrateKbpsToBps(bitrateKbps: Int): Int = bitrateKbps * 1000

    // ---------- camera / mic ----------

    private fun waitForCameraCapture() {
        cameraCaptureReady = false
        val change = ++cameraChangeGeneration
        awaitingCaptureGeneration = change
        mainHandler.postDelayed({
            if (awaitingCaptureGeneration == change && !cameraCaptureReady) {
                failLensChange("カメラ映像の開始がタイムアウトしました")
            }
        }, StreamConfig.CAMERA_SWITCH_TIMEOUT_MS)
    }

    private fun configureCameraCallbacks(camera: Camera2Source) {
        activeCaptureSession = null
        synchronized(seenCaptureSessions) { seenCaptureSessions.clear() }
        cameraCaptureReady = false
        awaitingCaptureGeneration = null
        camera.setCameraCallback(object : CameraCallbacks {
            override fun onCameraChanged(facing: CameraHelper.Facing) = onMainThreadHandler {
                if (genericStream?.videoSource === camera && pendingLens == null && cameraCaptureReady) {
                    refreshZoomState()
                }
            }

            override fun onCameraError(error: String) = onMainThreadHandler {
                if (genericStream?.videoSource === camera) failLensChange(error)
            }

            override fun onCameraOpened() = Unit

            override fun onCameraDisconnected() = onMainThreadHandler {
                if (genericStream?.videoSource === camera) failLensChange("カメラが切断されました")
            }
        })
        camera.setCustomOnCaptureCompletedCallback { session, _, _ ->
            if (genericStream?.videoSource !== camera) return@setCustomOnCaptureCompletedCallback
            val firstCapture = synchronized(seenCaptureSessions) { seenCaptureSessions.add(session) }
            if (!firstCapture) return@setCustomOnCaptureCompletedCallback
            val captureGeneration = awaitingCaptureGeneration ?: return@setCustomOnCaptureCompletedCallback
            activeCaptureSession = session
            onMainThreadHandler {
                if (genericStream?.videoSource === camera && session === activeCaptureSession &&
                    awaitingCaptureGeneration == captureGeneration && cameraChangeGeneration == captureGeneration
                ) {
                    awaitingCaptureGeneration = null
                    cameraCaptureReady = true
                    ++cameraChangeGeneration
                    val target = pendingLens
                    if (target != null) continueLensChange(camera, target)
                    else confirmLens(camera, currentLensFromCamera(camera))
                }
            }
        }
    }

    /** 1回の再オープンごとにCapture完了を待ち、次の切替段階へ進む。 */
    private fun continueLensChange(camera: Camera2Source, target: LensOption) {
        if (!cameraCaptureReady) return
        val wantFacing = if (target.isFront) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        val cameraId = runCatching { camera.getCurrentCameraId() }.getOrNull()
        when {
            activePhysicalCameraId != null &&
                (camera.getCameraFacing() != wantFacing || cameraId != target.cameraId || target.physicalCameraId == null) -> {
                waitForCameraCapture()
                camera.openPhysicalCamera(null)
                activePhysicalCameraId = null
            }
            camera.getCameraFacing() != wantFacing -> {
                waitForCameraCapture()
                camera.switchCamera()
            }
            cameraId != target.cameraId -> {
                waitForCameraCapture()
                camera.openCameraId(target.cameraId)
            }
            activePhysicalCameraId != target.physicalCameraId -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    failLensChange("物理カメラ固定にはAndroid 9以降が必要です")
                    return
                }
                waitForCameraCapture()
                camera.openPhysicalCamera(target.physicalCameraId)
                activePhysicalCameraId = target.physicalCameraId
            }
            else -> confirmLens(camera, target)
        }
    }

    private fun confirmLens(camera: Camera2Source, lens: LensOption?) {
        val requestedZoom = savedZoomRatio
        pendingLens = null
        savedLens = lens
        savedFront = camera.getCameraFacing() == CameraHelper.Facing.FRONT
        cameraCaptureReady = false // 再オープン時の1xを保存値として採用しない。
        mutableState.value = state.value.copy(
            selectedLens = lens,
            cameraIsFront = savedFront,
            cameraError = null,
            previewReady = genericStream?.isOnPreview == true
        )
        refreshZoomState()
        cameraCaptureReady = true
        applyZoom(camera, requestedZoom.coerceIn(state.value.zoom.minRatio, state.value.zoom.maxRatio))
    }

    private fun currentLensFromCamera(camera: Camera2Source): LensOption? {
        val cameraId = runCatching { camera.getCurrentCameraId() }.getOrNull() ?: return null
        return availableLenses.firstOrNull {
            it.cameraId == cameraId && it.physicalCameraId == activePhysicalCameraId
        } ?: availableLenses.firstOrNull { it.cameraId == cameraId && it.physicalCameraId == null }
    }

    private fun failLensChange(error: String) {
        val wasStreaming = isStreamingNow()
        pendingLens = null
        cameraCaptureReady = false
        awaitingCaptureGeneration = null
        ++cameraChangeGeneration
        val message = "カメラ切替失敗: $error"
        mutableState.value = state.value.copy(
            previewReady = false,
            cameraError = message
        )
        toast(message)
        if (wasStreaming) stopStream(message)
    }

    private fun preferredLogicalBackCamera(): LensOption? {
        if (zoomDebugOverride == ZoomDebugOverride.FORCE_DIGITAL) return null
        val reference = mainBackFov
        return availableLenses.filter { !it.isFront && it.supportsAutoLens }
            .minWithOrNull(
                compareBy<LensOption> { logical ->
                    if (reference == null) {
                        0f
                    } else {
                        availableLenses.asSequence()
                            .filter { it.cameraId == logical.cameraId && it.physicalCameraId != null }
                            .mapNotNull { it.fovDegrees }
                            .minOfOrNull { abs(it - reference) }
                            ?: abs((logical.fovDegrees ?: reference) - reference)
                    }
                }.thenByDescending { it.maxZoomRatio - it.minZoomRatio }
            )
    }

    private fun lensBaseRatio(lens: LensOption?): Float {
        if (lens == null || lens.isFront || lens.supportsAutoLens) return 1f
        val reference = mainBackFov ?: return 1f
        val fov = lens.fovDegrees ?: return 1f
        val raw = tan(reference * PI / 360.0) / tan(fov * PI / 360.0)
        return ((raw * 10).roundToInt() / 10f).coerceAtLeast(0.1f)
    }

    /** 同じ実レンズを倍率でまとめた、ユーザー表示用の選択肢。 */
    fun cameraZoomChoices(front: Boolean): List<CameraZoomChoice> {
        if (front) {
            val lens = availableLenses.firstOrNull { it.isFront && it.physicalCameraId == null }
                ?: return emptyList()
            return listOf(1f, 2f, 3f, 5f, 10f)
                .filter { it <= lens.maxZoomRatio + 0.01f }
                .map { CameraZoomChoice(it, lens) }
        }
        val autoLens = preferredLogicalBackCamera()
        if (autoLens != null) {
            val physicalRatios = availableLenses
                .filter { it.cameraId == autoLens.cameraId && it.physicalCameraId != null }
                .map { lensBaseRatio(it) }
            return (physicalRatios + 1f)
                .filter { it in autoLens.minZoomRatio..autoLens.maxZoomRatio }
                .distinct()
                .sorted()
                .map { CameraZoomChoice(it, autoLens) }
        }
        val manualChoices = availableLenses
            .filter { !it.isFront && !it.isLogicalMultiCamera }
            .groupBy { lensBaseRatio(it) }
            .map { (ratio, lenses) ->
                val lens = lenses.firstOrNull { it.physicalCameraId != null } ?: lenses.first()
                CameraZoomChoice(ratio, lens)
            }
            .sortedBy { it.ratio }
        if (manualChoices.isNotEmpty()) return manualChoices
        val reference = mainBackFov
        return availableLenses.filter { !it.isFront && it.physicalCameraId == null }
            .minByOrNull { lens ->
                if (reference == null) -(lens.maxZoomRatio - lens.minZoomRatio)
                else abs((lens.fovDegrees ?: reference) - reference)
            }
            ?.let { listOf(CameraZoomChoice(1f, it)) }
            ?: emptyList()
    }

    fun selectCameraZoom(choice: CameraZoomChoice) {
        openLens(choice.lens, choice.ratio)
    }

    private fun defaultBackLens(): LensOption? = preferredLogicalBackCamera()
        ?: cameraZoomChoices(false).minByOrNull { abs(it.ratio - 1f) }?.lens

    private fun refreshZoomState(): CameraZoomState {
        val camera = genericStream?.videoSource as? Camera2Source
        if (camera == null || !camera.isRunning()) return state.value.zoom
        val range = runCatching { camera.getZoomRange() }.getOrNull()
        val cameraId = runCatching { camera.getCurrentCameraId() }.getOrNull()
        val lens = listLenses().firstOrNull {
            it.cameraId == cameraId && it.physicalCameraId == activePhysicalCameraId
        } ?: listLenses().firstOrNull { it.cameraId == cameraId && it.physicalCameraId == null }
        val logicalMode = zoomDebugOverride != ZoomDebugOverride.FORCE_DIGITAL &&
            activePhysicalCameraId == null && lens?.supportsAutoLens == true
        val baseRatio = lensBaseRatio(lens)
        val nativeMinRatio = if (activePhysicalCameraId != null) {
            maxOf(1f, range?.lower ?: 1f, lens?.minZoomRatio ?: 1f)
        } else if (zoomDebugOverride == ZoomDebugOverride.FORCE_DIGITAL) {
            maxOf(1f, range?.lower ?: 1f)
        } else {
            range?.lower ?: 1f
        }
        val reportedMaxRatio = range?.upper ?: 1f
        val nativeMaxRatio = if (activePhysicalCameraId != null) {
            maxOf(nativeMinRatio, minOf(reportedMaxRatio, lens?.maxZoomRatio ?: reportedMaxRatio))
        } else {
            maxOf(nativeMinRatio, reportedMaxRatio)
        }
        val minRatio = baseRatio * nativeMinRatio
        val maxRatio = baseRatio * nativeMaxRatio
        val reported = runCatching { camera.getZoom() }.getOrDefault(0f)
        val current = if (cameraCaptureReady && reported >= nativeMinRatio) baseRatio * reported
            else savedZoomRatio.coerceIn(minRatio, maxRatio)
        val zoom = CameraZoomState(
            ratio = current,
            minRatio = minRatio,
            maxRatio = maxRatio,
            baseRatio = baseRatio,
            mode = if (logicalMode) CameraZoomMode.AUTO_LENS else CameraZoomMode.DIGITAL,
            debugOverride = zoomDebugOverride
        )
        savedZoomRatio = current
        mutableState.value = state.value.copy(zoom = zoom)
        return zoom
    }

    /** ピンチ操作で指定された倍率を端末の対応範囲へ収めて適用する。 */
    fun setZoomRatio(ratio: Float): Float {
        val camera = genericStream?.videoSource as? Camera2Source ?: return state.value.zoom.ratio
        if (!camera.isRunning()) return state.value.zoom.ratio
        val zoom = state.value.zoom
        val target = ratio.coerceIn(zoom.minRatio, zoom.maxRatio)
        savedZoomRatio = target
        if (!cameraCaptureReady) {
            mutableState.value = state.value.copy(zoom = zoom.copy(ratio = target))
            return target
        }
        return applyZoom(camera, target)
    }

    /** 現在倍率を基準にピンチ操作の倍率変化を適用する。 */
    fun changeZoomBy(scale: Float): Float {
        if (!scale.isFinite() || scale == 1f) return state.value.zoom.ratio
        return setZoomRatio(state.value.zoom.ratio * scale)
    }

    private fun applyZoom(camera: Camera2Source, target: Float): Float {
        val baseRatio = state.value.zoom.baseRatio
        val nativeTarget = target / baseRatio
        runCatching { camera.setZoom(nativeTarget) }
        val applied = (runCatching { camera.getZoom() }.getOrDefault(nativeTarget) * baseRatio)
            .coerceIn(state.value.zoom.minRatio, state.value.zoom.maxRatio)
        savedZoomRatio = applied
        mutableState.value = state.value.copy(zoom = state.value.zoom.copy(ratio = applied))
        return applied
    }

    fun isDebuggable(): Boolean =
        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun zoomDiagnostics(): String {
        val zoom = state.value.zoom
        val lens = currentLens()
        val cameraId = lens?.cameraId ?: "-"
        val physical = lens?.physicalCameraId?.let { " / physical $it" }.orEmpty()
        return "API ${Build.VERSION.SDK_INT} / camera $cameraId$physical / ${zoom.mode.name} / " +
            "${"%.2f".format(zoom.minRatio)}-${"%.2f".format(zoom.maxRatio)}x"
    }

    /** デバッグビルドで両方のCapability分岐を強制確認する。 */
    fun setZoomDebugOverride(override: ZoomDebugOverride) {
        if (!isDebuggable() || zoomDebugOverride == override || pendingLens != null) return
        val previousOverride = zoomDebugOverride
        zoomDebugOverride = override
        val camera = genericStream?.videoSource as? Camera2Source ?: return
        if (!camera.isRunning() || isFrontCamera()) {
            refreshZoomState()
            return
        }
        val target = when (override) {
            ZoomDebugOverride.AUTO, ZoomDebugOverride.FORCE_LOGICAL ->
                listLenses().firstOrNull { !it.isFront && it.supportsAutoLens }
                    ?.let { CameraZoomChoice(1f, it) }
            ZoomDebugOverride.FORCE_DIGITAL ->
                cameraZoomChoices(false).minByOrNull { abs(it.ratio - 1f) }
        }
        if (override != ZoomDebugOverride.AUTO && target == null) {
            zoomDebugOverride = previousOverride
            val label = if (override == ZoomDebugOverride.FORCE_LOGICAL) "論理マルチカメラ" else "個別レンズ"
            toast("$label は利用できません")
            refreshZoomState()
            return
        } else if (target != null) {
            openLens(target.lens, target.ratio)
        } else {
            refreshZoomState()
        }
    }

    /**
     * 前面/背面カメラを明示選択する。既にその面なら何もしない。
     * @param front true=前面, false=背面
     */
    fun selectCamera(front: Boolean) {
        val camera = genericStream?.videoSource as? Camera2Source ?: return
        if (pendingLens != null) return
        val want = if (front) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        if (camera.isRunning() && state.value.cameraError == null && camera.getCameraFacing() == want) return
        val target = if (front) {
            availableLenses.firstOrNull { it.isFront && it.physicalCameraId == null }
        } else {
            defaultBackLens()
        }
        if (target != null && openLens(target)) return
        if (camera.getCameraFacing() != want) {
            waitForCameraCapture()
            mutableState.value = state.value.copy(previewReady = false, cameraError = null)
            runCatching { camera.switchCamera() }
        }
        activePhysicalCameraId = null
        savedLens = currentLens()
        savedFront = camera.getCameraFacing() == CameraHelper.Facing.FRONT
        savedZoomRatio = 1f
        refreshZoomState()
        setZoomRatio(1f)
    }

    fun isFrontCamera(): Boolean {
        val camera = genericStream?.videoSource as? Camera2Source ?: return savedFront
        return camera.getCameraFacing() == CameraHelper.Facing.FRONT
    }

    fun toggleMute(muted: Boolean) {
        val mic = genericStream?.audioSource as? MicrophoneSource ?: return
        runCatching {
            if (muted) mic.mute() else mic.unMute()
            mutableState.value = state.value.copy(muted = muted)
        }
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
    }

    /** 配信中は StreamBase 側の stopSources が止めるので、自前で回している時だけ止める */
    private fun stopMicMonitor(stream: StreamBase? = genericStream) {
        if (isStreamingNow()) return
        val mic = stream?.audioSource as? MicrophoneSource ?: return
        if (mic.isRunning()) runCatching { mic.stop() }
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
    fun listLenses(): List<LensOption> = availableLenses

    /** 指定レンズへの切替要求を開始できたら true。確定は最初のCapture完了後に通知する。 */
    fun openLens(requestedLens: LensOption, requestedZoomRatio: Float? = null): Boolean {
        val camera = genericStream?.videoSource as? Camera2Source ?: return false
        if (pendingLens != null) return false
        val lens = listLenses().firstOrNull {
            it.cameraId == requestedLens.cameraId && it.physicalCameraId == requestedLens.physicalCameraId
        } ?: return false
        val zoomRatio = requestedZoomRatio ?: lensBaseRatio(lens)
        if (state.value.cameraError != null || !camera.isRunning()) {
            if (isStreamingNow()) return false
            releaseEngine()
            savedLens = lens
            savedFront = lens.isFront
            savedZoomRatio = zoomRatio
            mutableState.value = state.value.copy(cameraError = null)
            startPreviewIfReady()
            return true
        }
        val currentLens = currentLensFromCamera(camera)
        if (requestedZoomRatio != null && currentLens?.cameraId == lens.cameraId &&
            currentLens.physicalCameraId == lens.physicalCameraId
        ) {
            setZoomRatio(zoomRatio)
            return true
        }
        return runCatching {
            pendingLens = lens
            savedZoomRatio = zoomRatio
            mutableState.value = state.value.copy(previewReady = false, cameraError = null)
            continueLensChange(camera, lens)
            true
        }.onFailure {
            failLensChange(it.message ?: "不明なエラー")
        }.getOrDefault(false)
    }

    /** Capture完了で確定した現在のレンズ。停止中は直前のレンズ。 */
    fun currentLens(): LensOption? {
        val camera = genericStream?.videoSource as? Camera2Source ?: return savedLens
        if (!camera.isRunning()) return savedLens
        if (state.value.cameraError != null) return savedLens
        state.value.selectedLens?.let { return it }
        val cameraId = runCatching { camera.getCurrentCameraId() }.getOrNull() ?: return savedLens
        return listLenses().firstOrNull {
            it.cameraId == cameraId && it.physicalCameraId == activePhysicalCameraId
        } ?: savedLens
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
