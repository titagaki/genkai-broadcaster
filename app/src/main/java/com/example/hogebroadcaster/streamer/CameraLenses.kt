package com.example.hogebroadcaster.streamer

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
 * @param cameraId Camera2 のカメラID ([Camera2Source.openCameraId] に渡す)
 * @param label 表示名。焦点距離とセンサーサイズから求めた水平画角 (例 "71°")
 * @param isFront 前面カメラなら true
 * @param fovDegrees 水平画角 (度)。算出不可時は null
 */
data class LensOption(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val fovDegrees: Float? = null
)

/**
 * 端末のレンズ列挙。IRL Pro の BACK 26°/71°/97° 表示に相当する。
 * Characteristics の取得にカメラ権限は不要。
 *
 * 同画角の重複IDは1つにまとめ、画角の昇順で返す。
 */
object CameraLenses {

    fun list(context: Context): List<LensOption> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyList()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val all = runCatching {
            manager.cameraIdList.mapNotNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val degrees = fovDegrees(chars)
                LensOption(
                    cameraId = id,
                    label = degrees?.let { "${it.roundToInt()}°" } ?: "ID $id",
                    isFront = facing == CameraCharacteristics.LENS_FACING_FRONT,
                    fovDegrees = degrees
                )
            }
        }.getOrDefault(emptyList())
        return all
            .distinctBy { it.label }
            .sortedBy { it.fovDegrees ?: Float.MAX_VALUE }
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
