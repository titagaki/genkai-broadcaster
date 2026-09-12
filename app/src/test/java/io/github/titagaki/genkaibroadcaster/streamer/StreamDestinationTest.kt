package io.github.titagaki.genkaibroadcaster.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDestinationTest {

    private fun dest(server: String, key: String) = StreamDestination("id", "name", server, key)

    @Test
    fun `fullUrl はサーバーとキーをスラッシュ1つで繋ぐ`() {
        assertEquals("rtmp://host/live/abc", dest("rtmp://host/live", "abc").fullUrl())
    }

    @Test
    fun `fullUrl は余分なスラッシュと空白を取り除く`() {
        assertEquals("rtmp://host/live/abc", dest(" rtmp://host/live/ ", " /abc/ ").fullUrl())
    }

    @Test
    fun `キーが空ならサーバーURLだけ`() {
        assertEquals("rtmp://host/live", dest("rtmp://host/live/", "").fullUrl())
    }

    @Test
    fun `JSON へ往復しても同じ内容になる`() {
        val original = listOf(
            StreamDestination("a", "PeerCast", "rtmp://a/live", "key-a"),
            StreamDestination("b", "Twitch", "rtmp://b/app", "")
        )
        val restored = StreamDestination.listFromJson(StreamDestination.listToJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `壊れた JSON は空リスト`() {
        assertTrue(StreamDestination.listFromJson("not json").isEmpty())
        assertTrue(StreamDestination.listFromJson("").isEmpty())
    }

    @Test
    fun `id の無い要素には新しい id を振る`() {
        val list = StreamDestination.listFromJson("""[{"name":"x","server":"rtmp://s","key":"k"}]""")
        assertEquals(1, list.size)
        assertNotEquals("", list.single().id)
    }
}
