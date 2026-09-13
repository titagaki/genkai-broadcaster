package io.github.titagaki.genkaibroadcaster.streamer

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFormatTest {

    @Test
    fun `elapsed は1時間未満なら mm ss`() {
        assertEquals("00:00", StreamFormat.elapsed(0))
        assertEquals("00:12", StreamFormat.elapsed(12))
        assertEquals("59:59", StreamFormat.elapsed(3599))
    }

    @Test
    fun `elapsed は1時間以上なら hh mm ss`() {
        assertEquals("01:00:00", StreamFormat.elapsed(3600))
        assertEquals("10:02:03", StreamFormat.elapsed(10 * 3600 + 2 * 60 + 3))
    }

    @Test
    fun `mbps は小数1桁`() {
        assertEquals("1.2", StreamFormat.mbps(1_234_567))
        assertEquals("0.0", StreamFormat.mbps(0))
    }

    @Test
    fun `通知本文は接続済みなら Live と時間とビットレート`() {
        val state = StreamState(isStreaming = true, isConnected = true, status = "LIVE", smoothedBitrateBps = 1_200_000)
        assertEquals("Live: 00:12 / 1.2Mbps", StreamFormat.notificationText(state, 12))
    }

    @Test
    fun `通知本文はビットレート未計測なら時間だけ`() {
        val state = StreamState(isStreaming = true, isConnected = true, status = "LIVE")
        assertEquals("Live: 00:03", StreamFormat.notificationText(state, 3))
    }

    @Test
    fun `通知本文は未接続なら状態文そのまま`() {
        val state = StreamState(isStreaming = true, isConnected = false, status = "再接続中... (timeout)")
        assertEquals("再接続中... (timeout)", StreamFormat.notificationText(state, 40))
    }
}
