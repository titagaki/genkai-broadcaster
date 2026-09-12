package io.github.titagaki.genkaibroadcaster.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCatalogTest {

    // 論理カメラ "0" の配下に 超広角(115°) / 主(75°) / 望遠(30°) を持つ端末
    private val logical = LensOption(
        cameraId = "0", label = "自動レンズ", isFront = false, fovDegrees = 75f,
        isLogicalMultiCamera = true, supportsAutoLens = true, minZoomRatio = 0.6f, maxZoomRatio = 10f
    )
    private val ultraWide = LensOption("0", "115°", false, 115f, physicalCameraId = "2", maxZoomRatio = 4f)
    private val main = LensOption("0", "75°", false, 75f, physicalCameraId = "3", maxZoomRatio = 8f)
    private val tele = LensOption("0", "30°", false, 30f, physicalCameraId = "4", maxZoomRatio = 8f)
    private val front = LensOption("1", "80°", true, 80f, maxZoomRatio = 4f)
    private val multiCamera = LensCatalog(listOf(logical, ultraWide, main, tele, front))

    // 単一背面レンズ (論理カメラなし) の端末
    private val singleBack = LensOption("0", "70°", false, 70f, maxZoomRatio = 8f)
    private val simple = LensCatalog(listOf(singleBack, front))

    @Test
    fun `主カメラの画角は 70 度に最も近い物理レンズから取る`() {
        assertEquals(75f, multiCamera.mainBackFov)
        assertEquals(70f, simple.mainBackFov)
    }

    @Test
    fun `baseRatio は主カメラを 1x として画角比で決まる`() {
        assertEquals(1f, multiCamera.baseRatio(main))
        assertTrue(multiCamera.baseRatio(ultraWide) < 1f)
        assertTrue(multiCamera.baseRatio(tele) > 2f)
        // 前面・自動レンズ・null は常に 1x
        assertEquals(1f, multiCamera.baseRatio(front))
        assertEquals(1f, multiCamera.baseRatio(logical))
        assertEquals(1f, multiCamera.baseRatio(null))
    }

    @Test
    fun `論理カメラがあれば背面の選択肢はすべて論理カメラで倍率だけ変わる`() {
        val choices = multiCamera.zoomChoices(front = false, forceDigital = false)
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { it.lens === logical })
        assertEquals(choices.map { it.ratio }.sorted(), choices.map { it.ratio })
        assertTrue(choices.any { it.ratio == 1f })
    }

    @Test
    fun `デジタル強制時は物理レンズごとの選択肢になる`() {
        val choices = multiCamera.zoomChoices(front = false, forceDigital = true)
        assertTrue(choices.none { it.lens === logical })
        assertEquals(listOf(ultraWide, main, tele).map { multiCamera.baseRatio(it) }.sorted(),
            choices.map { it.ratio })
    }

    @Test
    fun `前面は固定刻みを最大倍率で切る`() {
        assertEquals(listOf(1f, 2f, 3f), multiCamera.zoomChoices(front = true, forceDigital = false).map { it.ratio })
    }

    @Test
    fun `論理カメラの無い端末は単一レンズの 1x だけ`() {
        val choices = simple.zoomChoices(front = false, forceDigital = false)
        assertEquals(1, choices.size)
        assertSame(singleBack, choices.single().lens)
        assertEquals(1f, choices.single().ratio)
    }

    @Test
    fun `既定の背面レンズは論理カメラ優先、無ければ 1x に近いもの`() {
        assertSame(logical, multiCamera.defaultBackLens(forceDigital = false))
        assertSame(main, multiCamera.defaultBackLens(forceDigital = true))
        assertSame(singleBack, simple.defaultBackLens(forceDigital = false))
    }

    @Test
    fun `resolve は物理IDが合わなければ同じカメラの論理レンズで代用する`() {
        assertSame(tele, multiCamera.resolve("0", "4"))
        assertSame(logical, multiCamera.resolve("0", "unknown"))
        assertNull(multiCamera.resolve("9", null))
        assertNull(multiCamera.find("0", "unknown"))
    }

    @Test
    fun `背面レンズが無ければ選択肢も既定レンズも無い`() {
        val frontOnly = LensCatalog(listOf(front))
        assertNull(frontOnly.mainBackFov)
        assertTrue(frontOnly.zoomChoices(front = false, forceDigital = false).isEmpty())
        assertNull(frontOnly.defaultBackLens(forceDigital = false))
    }
}
