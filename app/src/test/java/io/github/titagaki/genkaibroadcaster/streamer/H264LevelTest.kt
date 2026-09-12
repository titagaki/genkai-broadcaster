package io.github.titagaki.genkaibroadcaster.streamer

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class H264LevelTest {

    @Test
    fun `720p30 は Level 3_1`() {
        assertEquals(CodecProfileLevel.AVCLevel31, H264Level.select(1280, 720, 30))
    }

    @Test
    fun `720p60 は Level 3_2`() {
        assertEquals(CodecProfileLevel.AVCLevel32, H264Level.select(1280, 720, 60))
    }

    @Test
    fun `1080p30 は Level 4`() {
        assertEquals(CodecProfileLevel.AVCLevel4, H264Level.select(1920, 1080, 30))
    }

    @Test
    fun `1080p60 は Level 4_2`() {
        assertEquals(CodecProfileLevel.AVCLevel42, H264Level.select(1920, 1080, 60))
    }

    @Test
    fun `360p15 は最小の Level 3`() {
        assertEquals(CodecProfileLevel.AVCLevel3, H264Level.select(640, 360, 15))
    }

    @Test
    fun `表を超える負荷は Level 4_2 に丸める`() {
        assertEquals(CodecProfileLevel.AVCLevel42, H264Level.select(3840, 2160, 60))
    }

    @Test
    fun `16の倍数でない幅高さはマクロブロック単位で切り上げる`() {
        // 854x480: 54x30 MB = 1620 MB/frame → 30fps で 48,600 > Level 3 の 40,500
        assertEquals(CodecProfileLevel.AVCLevel31, H264Level.select(854, 480, 30))
    }
}
