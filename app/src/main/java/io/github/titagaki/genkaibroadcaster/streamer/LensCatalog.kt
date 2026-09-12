package io.github.titagaki.genkaibroadcaster.streamer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 端末のレンズ一覧から「どのレンズをどの倍率で見せるか」を決める純粋なロジック。
 *
 * Camera2 やエンジンには触らず [LensOption] のリストだけを入力とするので、
 * JVM 単体テストで検証できる。実端末での列挙は [CameraLenses.list] が行う。
 *
 * 表示倍率は「主カメラ (画角が約70°に最も近い背面レンズ) を 1x とした値」で、
 * 各レンズ固有の倍率は [baseRatio] で換算する。
 */
class LensCatalog(val lenses: List<LensOption>) {

    /** 主カメラの水平画角。背面レンズが無い、または画角不明なら null */
    val mainBackFov: Float? by lazy {
        val candidates = lenses.filter { !it.isFront && it.fovDegrees != null }
        val physical = candidates.filter { it.physicalCameraId != null }
        val standalone = candidates.filter { !it.isLogicalMultiCamera }
        (physical.ifEmpty { standalone }.ifEmpty { candidates })
            .minByOrNull { abs(it.fovDegrees!! - MAIN_FOV_DEGREES) }?.fovDegrees
    }

    /** cameraId と physicalCameraId が完全一致するレンズ */
    fun find(cameraId: String, physicalCameraId: String?): LensOption? =
        lenses.firstOrNull { it.cameraId == cameraId && it.physicalCameraId == physicalCameraId }

    /** [find] で見つからなければ、同じ cameraId の論理/トップレベルレンズで代用する */
    fun resolve(cameraId: String, physicalCameraId: String?): LensOption? =
        find(cameraId, physicalCameraId) ?: find(cameraId, null)

    /** 前面カメラ (物理レンズ固定なし)。無ければ null */
    fun frontLens(): LensOption? = lenses.firstOrNull { it.isFront && it.physicalCameraId == null }

    /** 自動レンズ切替に対応する背面の論理カメラ。無ければ null */
    fun anyAutoLensBackCamera(): LensOption? = lenses.firstOrNull { !it.isFront && it.supportsAutoLens }

    /**
     * 自動レンズ切替に使う背面の論理カメラ。配下の物理レンズが主カメラの画角に
     * 最も近いものを優先し、同点ならズーム範囲が広いものを選ぶ。
     * @param forceDigital デジタルズーム強制時は常に null
     */
    fun preferredLogicalBackCamera(forceDigital: Boolean): LensOption? {
        if (forceDigital) return null
        val reference = mainBackFov
        return lenses.filter { !it.isFront && it.supportsAutoLens }
            .minWithOrNull(
                compareBy<LensOption> { logical ->
                    if (reference == null) {
                        0f
                    } else {
                        lenses.asSequence()
                            .filter { it.cameraId == logical.cameraId && it.physicalCameraId != null }
                            .mapNotNull { it.fovDegrees }
                            .minOfOrNull { abs(it - reference) }
                            ?: abs((logical.fovDegrees ?: reference) - reference)
                    }
                }.thenByDescending { it.maxZoomRatio - it.minZoomRatio }
            )
    }

    /**
     * レンズ固有の表示倍率 (主カメラ基準)。画角比を tan で換算し 0.1 刻みに丸める。
     * 前面・自動レンズ・画角不明は 1x。
     */
    fun baseRatio(lens: LensOption?): Float {
        if (lens == null || lens.isFront || lens.supportsAutoLens) return 1f
        val reference = mainBackFov ?: return 1f
        val fov = lens.fovDegrees ?: return 1f
        val raw = tan(reference * PI / 360.0) / tan(fov * PI / 360.0)
        return ((raw * 10).roundToInt() / 10f).coerceAtLeast(0.1f)
    }

    /** 同じ実レンズを倍率でまとめた、ユーザー表示用の選択肢。 */
    fun zoomChoices(front: Boolean, forceDigital: Boolean): List<CameraZoomChoice> {
        if (front) {
            val lens = frontLens() ?: return emptyList()
            return FRONT_ZOOM_STEPS
                .filter { it <= lens.maxZoomRatio + 0.01f }
                .map { CameraZoomChoice(it, lens) }
        }
        val autoLens = preferredLogicalBackCamera(forceDigital)
        if (autoLens != null) {
            val physicalRatios = lenses
                .filter { it.cameraId == autoLens.cameraId && it.physicalCameraId != null }
                .map { baseRatio(it) }
            return (physicalRatios + 1f)
                .filter { it in autoLens.minZoomRatio..autoLens.maxZoomRatio }
                .distinct()
                .sorted()
                .map { CameraZoomChoice(it, autoLens) }
        }
        val manualChoices = lenses
            .filter { !it.isFront && !it.isLogicalMultiCamera }
            .groupBy { baseRatio(it) }
            .map { (ratio, group) ->
                val lens = group.firstOrNull { it.physicalCameraId != null } ?: group.first()
                CameraZoomChoice(ratio, lens)
            }
            .sortedBy { it.ratio }
        if (manualChoices.isNotEmpty()) return manualChoices
        val reference = mainBackFov
        return lenses.filter { !it.isFront && it.physicalCameraId == null }
            .minByOrNull { lens ->
                if (reference == null) -(lens.maxZoomRatio - lens.minZoomRatio)
                else abs((lens.fovDegrees ?: reference) - reference)
            }
            ?.let { listOf(CameraZoomChoice(1f, it)) }
            ?: emptyList()
    }

    /** 背面の 1x に最も近い選択肢 (デジタルズームでの既定) */
    fun nearestOneXBackChoice(forceDigital: Boolean): CameraZoomChoice? =
        zoomChoices(front = false, forceDigital = forceDigital).minByOrNull { abs(it.ratio - 1f) }

    /** 起動時・背面選択時の既定レンズ。自動レンズ優先、無ければ 1x に近いもの */
    fun defaultBackLens(forceDigital: Boolean): LensOption? =
        preferredLogicalBackCamera(forceDigital) ?: nearestOneXBackChoice(forceDigital)?.lens

    private companion object {
        /** 「主カメラ」とみなす水平画角の目安 */
        const val MAIN_FOV_DEGREES = 70f
        /** 前面カメラはレンズが1つなのでデジタル倍率の固定刻みを出す */
        val FRONT_ZOOM_STEPS = listOf(1f, 2f, 3f, 5f, 10f)
    }
}
