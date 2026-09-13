package io.github.titagaki.genkaibroadcaster.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentBoardTest {

    private fun entry(id: String, body: String = "body $id") = CommentEntry(id, 0L, null, body)

    private fun ids(list: List<CommentEntry>) = list.map { it.id }

    @Test
    fun `追加した順に見える`() {
        val board = CommentBoard(maxLines = 5, displayMillis = 1000)
        board.add(listOf(entry("a"), entry("b")), nowMillis = 0)
        board.add(listOf(entry("c")), nowMillis = 10)
        assertEquals(listOf("a", "b", "c"), ids(board.visible(10)))
    }

    @Test
    fun `同じ id は 2 回目以降を無視する`() {
        val board = CommentBoard(maxLines = 5, displayMillis = 1000)
        board.add(listOf(entry("a")), nowMillis = 0)
        board.add(listOf(entry("a", "再送"), entry("b")), nowMillis = 10)
        assertEquals(listOf("a", "b"), ids(board.visible(10)))
        assertEquals("body a", board.visible(10).first().body)
    }

    @Test
    fun `最大行数を超えたら古い順に落とす`() {
        val board = CommentBoard(maxLines = 2, displayMillis = 1000)
        board.add(listOf(entry("a"), entry("b"), entry("c")), nowMillis = 0)
        assertEquals(listOf("b", "c"), ids(board.visible(0)))
    }

    @Test
    fun `期限が来たものは消え、境界は期限ちょうどで消える`() {
        val board = CommentBoard(maxLines = 5, displayMillis = 1000)
        board.add(listOf(entry("a")), nowMillis = 0)
        board.add(listOf(entry("b")), nowMillis = 500)
        assertEquals(listOf("a", "b"), ids(board.visible(999)))
        assertEquals(listOf("b"), ids(board.visible(1000)))
        assertEquals(emptyList<String>(), ids(board.visible(1500)))
    }

    @Test
    fun `nextExpiryAt は最も早い期限を返し、空なら null`() {
        val board = CommentBoard(maxLines = 5, displayMillis = 1000)
        assertNull(board.nextExpiryAt(0))
        board.add(listOf(entry("a")), nowMillis = 0)
        board.add(listOf(entry("b")), nowMillis = 300)
        assertEquals(1000L, board.nextExpiryAt(500))
        assertEquals(1300L, board.nextExpiryAt(1000))
        assertNull(board.nextExpiryAt(1300))
    }

    @Test
    fun `clear は表示を消すが受け付け済み id は覚えている`() {
        val board = CommentBoard(maxLines = 5, displayMillis = 1000)
        board.add(listOf(entry("a")), nowMillis = 0)
        board.clear()
        assertEquals(emptyList<String>(), ids(board.visible(0)))
        board.add(listOf(entry("a"), entry("b")), nowMillis = 0)
        assertEquals(listOf("b"), ids(board.visible(0)))
    }

    @Test
    fun `期限切れは行数の計算に含めない`() {
        val board = CommentBoard(maxLines = 2, displayMillis = 100)
        board.add(listOf(entry("a"), entry("b")), nowMillis = 0)
        board.add(listOf(entry("c")), nowMillis = 100) // a, b は期限切れ
        assertEquals(listOf("c"), ids(board.visible(100)))
    }
}
