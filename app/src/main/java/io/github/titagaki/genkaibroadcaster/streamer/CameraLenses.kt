package io.github.titagaki.genkaibroadcaster.streamer

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * 端末のカメラレンズ1つ分の情報。
 *
 * @param cameraId Camera2 のトップレベルまたは論理親カメラID
 * @param label 表示名。焦点距離とセンサーサイズから求めた水平画角 (例 "71°")
 * @param isFront 前面カメラなら true
 * @param fovDegrees 水平画角 (度)。算出不可時は null
 * @param isLogicalMultiCamera 複数の物理カメラを束ねた論理カメラなら true
 * @param physicalCameraId 論理カメラ内の特定物理レンズへ固定する場合のID
 */
data class LensOption(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val fovDegrees: Float? = null,
    val isLogicalMultiCamera: Boolean = false,
    val supportsAutoLens: Boolean = false,
    val physicalCameraId: String? = null,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f
)

/**
 * 端末のレンズ列挙。IRL Pro の BACK 26°/71°/97° 表示に相当する。
 * Characteristics の取得にカメラ権限は不要。
 *
 * 論理カメラ配下の物理IDも個別レンズとして列挙する。
 * 同画角の重複IDは1つにまとめ、自動レンズ、画角の昇順で返す。
 */
object CameraLenses {

    fun list(context: Context): List<LensOption> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val all = runCatching {
            manager.cameraIdList.flatMap { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@flatMap emptyList()
                val degrees = fovDegrees(chars)
                val logical = isLogicalMultiCamera(chars)
                val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
                val autoLens = logical && facing == CameraCharacteristics.LENS_FACING_BACK &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val zoomRange = zoomRange(chars)
                buildList {
                    add(
                        LensOption(
                            cameraId = id,
                            label = if (autoLens) "自動レンズ" else degrees?.let { "${it.roundToInt()}°" } ?: "ID $id",
                            isFront = isFront,
                            fovDegrees = degrees,
                            isLogicalMultiCamera = logical,
                            supportsAutoLens = autoLens,
                            minZoomRatio = zoomRange.first,
                            maxZoomRatio = zoomRange.second
                        )
                    )
                    if (logical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        chars.physicalCameraIds.forEach { physicalId ->
                            val physicalChars = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
                                ?: return@forEach
                            val physicalDegrees = fovDegrees(physicalChars)
                            val physicalZoomRange = zoomRange(physicalChars)
                            add(
                                LensOption(
                                    cameraId = id,
                                    physicalCameraId = physicalId,
                                    label = physicalDegrees?.let { "${it.roundToInt()}°" } ?: "ID $physicalId",
                                    isFront = isFront,
                                    fovDegrees = physicalDegrees,
                                    minZoomRatio = physicalZoomRange.first,
                                    maxZoomRatio = physicalZoomRange.second
                                )
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        return all
            .distinctBy { it.cameraId to it.physicalCameraId }
            .sortedWith(compareBy<LensOption> { !it.supportsAutoLens }.thenBy { it.fovDegrees ?: Float.MAX_VALUE })
    }

    private fun isLogicalMultiCamera(chars: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        return capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
            chars.physicalCameraIds.size > 1
    }

    private fun zoomRange(chars: CameraCharacteristics): Pair<Float, Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                return it.lower to it.upper
            }
        }
        val max = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return 1f to max.coerceAtLeast(1f)
    }

    /** 水平画角 (度)。算出不可の場合は null */
    private fun fovDegrees(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: 0f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal > 0 && sensor != null && sensor.width > 0) {
            return (2 * atan(sensor.width / (2 * focal)) * 180.0 / PI).toFloat()
        }
        return null
    }
}
