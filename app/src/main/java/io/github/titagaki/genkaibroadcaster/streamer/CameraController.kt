package io.github.titagaki.genkaibroadcaster.streamer

import android.hardware.camera2.CameraCaptureSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.common.onMainThreadHandler
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import java.util.Collections
import java.util.IdentityHashMap

/**
 * カメラ (RootEncoder の [Camera2Source]) のレンズ切替とズームを担当する。
 *
 * レンズ切替は「再オープン → 最初の Capture 完了を待つ → 次の段階へ」を繰り返す状態機械で、
 * 途中の要求値 (pending/saved*) と UI に見せる確定状態 ([StreamState]) を区別する。
 * エンジンの生成・解放は [StreamController] が握るので、このクラスは [Host] 経由で
 * 「今のカメラ」「状態の更新」「失敗時の配信停止」だけを頼む。
 *
 * すべてのメソッドはメインスレッドから呼ぶ。Capture コールバックだけカメラスレッドから来る。
 */
internal class CameraController(
    private val catalog: LensCatalog,
    private val host: Host
) {

    /** [StreamController] が提供する足場 */
    interface Host {
        /** 現在のエンジンのカメラ。エンジン未生成なら null */
        val camera: Camera2Source?
        val isOnPreview: Boolean
        val isStreaming: Boolean
        val state: StreamState
        fun updateState(transform: (StreamState) -> StreamState)
        fun postMessage(message: String)
        /** 切替失敗などで配信を止める。停止理由は status に表示される */
        fun stopStream(status: String)
        /** カメラが止まっている・エラーの状態からエンジンを作り直す準備 */
        fun releaseEngine()
        fun startPreviewIfReady()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 復元要求 (確定状態とは別に持つ) ----
    private var savedLens: LensOption? = null
    private var pendingLens: LensOption? = null
    private var savedFront = false
    private var savedZoomRatio = 1f
    private var zoomDebugOverride = ZoomDebugOverride.AUTO

    // ---- 切替の進行状態 ----
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

    private val forceDigital: Boolean
        get() = zoomDebugOverride == ZoomDebugOverride.FORCE_DIGITAL

    // ---------- エンジン寿命に合わせたフック ----------

    /** エンジン生成直後。コールバックを張り、前回が前面なら前面へ切り替える */
    fun onEngineCreated(camera: Camera2Source) {
        configureCameraCallbacks(camera)
        if (savedFront) camera.switchCamera()
    }

    /** プレビュー開始でカメラが新たに開く直前。Capture 完了待ちを始め、復元するレンズを決める */
    fun onCameraOpening() {
        waitForCameraCapture()
        pendingLens = savedLens ?: if (savedFront) null else catalog.defaultBackLens(forceDigital)
    }

    /**
     * エンジン解放直前。確定状態を復元値として保存し、切替の進行状態を捨てる。
     * 切替要求中に解放された場合は要求値の方を残し、古い確定状態で上書きしない。
     */
    fun onEngineReleasing() {
        val requestedLens = pendingLens
        when {
            requestedLens != null -> {
                savedLens = requestedLens
                savedFront = requestedLens.isFront
            }
            awaitingCaptureGeneration == null -> {
                savedLens = host.state.selectedLens ?: savedLens
                savedFront = host.state.cameraIsFront
                savedZoomRatio = host.state.zoom.ratio
            }
        }
        pendingLens = null
        activePhysicalCameraId = null
        cameraCaptureReady = false
        activeCaptureSession = null
        awaitingCaptureGeneration = null
        synchronized(seenCaptureSessions) { seenCaptureSessions.clear() }
        ++cameraChangeGeneration
    }

    // ---------- Capture 完了待ち ----------

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
                if (host.camera === camera && pendingLens == null && cameraCaptureReady) {
                    refreshZoomState()
                }
            }

            override fun onCameraError(error: String) = onMainThreadHandler {
                if (host.camera === camera) failLensChange(error)
            }

            override fun onCameraOpened() = Unit

            override fun onCameraDisconnected() = onMainThreadHandler {
                if (host.camera === camera) failLensChange("カメラが切断されました")
            }
        })
        camera.setCustomOnCaptureCompletedCallback { session, _, _ ->
            if (host.camera !== camera) return@setCustomOnCaptureCompletedCallback
            val firstCapture = synchronized(seenCaptureSessions) { seenCaptureSessions.add(session) }
            if (!firstCapture) return@setCustomOnCaptureCompletedCallback
            val captureGeneration = awaitingCaptureGeneration ?: return@setCustomOnCaptureCompletedCallback
            activeCaptureSession = session
            onMainThreadHandler {
                if (host.camera === camera && session === activeCaptureSession &&
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
        val cameraId = currentCameraId(camera)
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
        host.updateState {
            it.copy(
                selectedLens = lens,
                cameraIsFront = savedFront,
                cameraError = null,
                previewReady = host.isOnPreview
            )
        }
        refreshZoomState()
        cameraCaptureReady = true
        val zoom = host.state.zoom
        applyZoom(camera, requestedZoom.coerceIn(zoom.minRatio, zoom.maxRatio))
    }

    private fun failLensChange(error: String) {
        val wasStreaming = host.isStreaming
        pendingLens = null
        cameraCaptureReady = false
        awaitingCaptureGeneration = null
        ++cameraChangeGeneration
        val message = "カメラ切替失敗: $error"
        Log.w(TAG, message)
        host.updateState { it.copy(previewReady = false, cameraError = message) }
        host.postMessage(message)
        if (wasStreaming) host.stopStream(message)
    }

    // ---------- レンズ照会 ----------

    /** カメラが閉じている最中は例外になるので null 扱いにする */
    private fun currentCameraId(camera: Camera2Source): String? =
        runCatching { camera.getCurrentCameraId() }.getOrNull()

    private fun currentLensFromCamera(camera: Camera2Source): LensOption? {
        val cameraId = currentCameraId(camera) ?: return null
        return catalog.resolve(cameraId, activePhysicalCameraId)
    }

    /** Capture完了で確定した現在のレンズ。停止中は直前のレンズ。 */
    fun currentLens(): LensOption? {
        val camera = host.camera ?: return savedLens
        if (!camera.isRunning()) return savedLens
        if (host.state.cameraError != null) return savedLens
        host.state.selectedLens?.let { return it }
        val cameraId = currentCameraId(camera) ?: return savedLens
        return catalog.find(cameraId, activePhysicalCameraId) ?: savedLens
    }

    fun isFrontCamera(): Boolean {
        val camera = host.camera ?: return savedFront
        return camera.getCameraFacing() == CameraHelper.Facing.FRONT
    }

    /** 同じ実レンズを倍率でまとめた、ユーザー表示用の選択肢。 */
    fun zoomChoices(front: Boolean): List<CameraZoomChoice> = catalog.zoomChoices(front, forceDigital)

    // ---------- レンズ切替 ----------

    /**
     * 前面/背面カメラを明示選択する。既にその面なら何もしない。
     * @param front true=前面, false=背面
     */
    fun selectCamera(front: Boolean) {
        val camera = host.camera ?: return
        if (pendingLens != null) return
        val want = if (front) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
        if (camera.isRunning() && host.state.cameraError == null && camera.getCameraFacing() == want) return
        val target = if (front) catalog.frontLens() else catalog.defaultBackLens(forceDigital)
        if (target != null && openLens(target)) return
        // レンズ一覧から選べない端末向けの素朴な切替
        if (camera.getCameraFacing() != want) {
            waitForCameraCapture()
            host.updateState { it.copy(previewReady = false, cameraError = null) }
            runCatching { camera.switchCamera() }
                .onFailure { Log.w(TAG, "switchCamera failed", it) }
        }
        activePhysicalCameraId = null
        savedLens = currentLens()
        savedFront = camera.getCameraFacing() == CameraHelper.Facing.FRONT
        savedZoomRatio = 1f
        refreshZoomState()
        setZoomRatio(1f)
    }

    /** 指定レンズへの切替要求を開始できたら true。確定は最初のCapture完了後に通知する。 */
    fun openLens(requestedLens: LensOption, requestedZoomRatio: Float? = null): Boolean {
        val camera = host.camera ?: return false
        if (pendingLens != null) return false
        val lens = catalog.find(requestedLens.cameraId, requestedLens.physicalCameraId) ?: return false
        val zoomRatio = requestedZoomRatio ?: catalog.baseRatio(lens)
        if (host.state.cameraError != null || !camera.isRunning()) {
            // カメラが止まっている/エラー中はエンジンごと作り直し、開き直しの過程で復元する
            if (host.isStreaming) return false
            host.releaseEngine()
            savedLens = lens
            savedFront = lens.isFront
            savedZoomRatio = zoomRatio
            host.updateState { it.copy(cameraError = null) }
            host.startPreviewIfReady()
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
            host.updateState { it.copy(previewReady = false, cameraError = null) }
            continueLensChange(camera, lens)
            true
        }.onFailure {
            Log.w(TAG, "openLens failed", it)
            failLensChange(it.message ?: "不明なエラー")
        }.getOrDefault(false)
    }

    fun selectZoomChoice(choice: CameraZoomChoice) {
        openLens(choice.lens, choice.ratio)
    }

    // ---------- ズーム ----------

    private fun refreshZoomState(): CameraZoomState {
        val camera = host.camera
        if (camera == null || !camera.isRunning()) return host.state.zoom
        val range = runCatching { camera.getZoomRange() }.getOrNull()
        val cameraId = currentCameraId(camera)
        val lens = cameraId?.let { catalog.resolve(it, activePhysicalCameraId) }
        val logicalMode = !forceDigital && activePhysicalCameraId == null && lens?.supportsAutoLens == true
        val baseRatio = catalog.baseRatio(lens)
        val nativeMinRatio = when {
            activePhysicalCameraId != null -> maxOf(1f, range?.lower ?: 1f, lens?.minZoomRatio ?: 1f)
            forceDigital -> maxOf(1f, range?.lower ?: 1f)
            else -> range?.lower ?: 1f
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
        host.updateState { it.copy(zoom = zoom) }
        return zoom
    }

    /** 配信中の Surface 再接続など、カメラを開き直さずに状態だけ読み直す */
    fun refreshZoom() {
        refreshZoomState()
    }

    /** ピンチ操作で指定された倍率を端末の対応範囲へ収めて適用する。 */
    fun setZoomRatio(ratio: Float): Float {
        val camera = host.camera ?: return host.state.zoom.ratio
        if (!camera.isRunning()) return host.state.zoom.ratio
        val zoom = host.state.zoom
        val target = ratio.coerceIn(zoom.minRatio, zoom.maxRatio)
        savedZoomRatio = target
        if (!cameraCaptureReady) {
            host.updateState { it.copy(zoom = zoom.copy(ratio = target)) }
            return target
        }
        return applyZoom(camera, target)
    }

    /** 現在倍率を基準にピンチ操作の倍率変化を適用する。 */
    fun changeZoomBy(scale: Float): Float {
        if (!scale.isFinite() || scale == 1f) return host.state.zoom.ratio
        return setZoomRatio(host.state.zoom.ratio * scale)
    }

    private fun applyZoom(camera: Camera2Source, target: Float): Float {
        val zoom = host.state.zoom
        val nativeTarget = target / zoom.baseRatio
        runCatching { camera.setZoom(nativeTarget) }
            .onFailure { Log.w(TAG, "setZoom($nativeTarget) failed", it) }
        val applied = (runCatching { camera.getZoom() }.getOrDefault(nativeTarget) * zoom.baseRatio)
            .coerceIn(zoom.minRatio, zoom.maxRatio)
        savedZoomRatio = applied
        host.updateState { it.copy(zoom = it.zoom.copy(ratio = applied)) }
        return applied
    }

    // ---------- デバッグ ----------

    fun zoomDiagnostics(): String {
        val zoom = host.state.zoom
        val lens = currentLens()
        val cameraId = lens?.cameraId ?: "-"
        val physical = lens?.physicalCameraId?.let { " / physical $it" }.orEmpty()
        return "API ${Build.VERSION.SDK_INT} / camera $cameraId$physical / ${zoom.mode.name} / " +
            "${"%.2f".format(zoom.minRatio)}-${"%.2f".format(zoom.maxRatio)}x"
    }

    /** 両方のCapability分岐を強制確認する (デバッグビルド専用の入口は StreamController 側) */
    fun setZoomDebugOverride(override: ZoomDebugOverride) {
        if (zoomDebugOverride == override || pendingLens != null) return
        val previousOverride = zoomDebugOverride
        zoomDebugOverride = override
        val camera = host.camera ?: return
        if (!camera.isRunning() || isFrontCamera()) {
            refreshZoomState()
            return
        }
        val target = when (override) {
            ZoomDebugOverride.AUTO, ZoomDebugOverride.FORCE_LOGICAL ->
                catalog.anyAutoLensBackCamera()?.let { CameraZoomChoice(1f, it) }
            ZoomDebugOverride.FORCE_DIGITAL -> catalog.nearestOneXBackChoice(forceDigital = true)
        }
        when {
            override != ZoomDebugOverride.AUTO && target == null -> {
                zoomDebugOverride = previousOverride
                val label = if (override == ZoomDebugOverride.FORCE_LOGICAL) "論理マルチカメラ" else "個別レンズ"
                host.postMessage("$label は利用できません")
                refreshZoomState()
            }
            target != null -> openLens(target.lens, target.ratio)
            else -> refreshZoomState()
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
