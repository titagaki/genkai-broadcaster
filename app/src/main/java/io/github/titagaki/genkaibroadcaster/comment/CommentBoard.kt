package io.github.titagaki.genkaibroadcaster.comment

/**
 * 映像に載せるコメントの「今見せている一覧」。Android に依存しない純粋ロジック (JVM テスト対象)。
 *
 * - 同じ id は 1 回しか受け付けない (提供側の再接続で同じコメントが再送されても重ねない)
 * - 表示は最大 [maxLines] 行。超えた分は古い順に落とす
 * - 各コメントは受け付けた時刻から [displayMillis] 経つと消える
 *
 * 時刻は呼び出し側が渡す (単調増加なら何でもよい。実装は `SystemClock.elapsedRealtime()`)。
 */
class CommentBoard(
    private val maxLines: Int,
    private val displayMillis: Long
) {
    private class Shown(val entry: CommentEntry, val expiresAt: Long)

    private val shown = ArrayDeque<Shown>()
    /** 受け付け済み id。無限に増えないよう [SEEN_LIMIT] を超えたら古い方から忘れる */
    private val seenIds = LinkedHashSet<String>()

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(displayMillis > 0) { "displayMillis must be positive" }
    }

    /** 新着を受け付ける。重複は無視し、行数を超えた分は古い順に落とす。 */
    fun add(entries: List<CommentEntry>, nowMillis: Long) {
        prune(nowMillis)
        for (entry in entries) {
            if (!seenIds.add(entry.id)) continue
            while (seenIds.size > SEEN_LIMIT) seenIds.remove(seenIds.first())
            shown.addLast(Shown(entry, nowMillis + displayMillis))
            while (shown.size > maxLines) shown.removeFirst()
        }
    }

    /** 期限内のコメントを古い順で返す。 */
    fun visible(nowMillis: Long): List<CommentEntry> {
        prune(nowMillis)
        return shown.map { it.entry }
    }

    /** 次に表示が変わる (最も早く期限を迎える) 時刻。表示中のものが無ければ null */
    fun nextExpiryAt(nowMillis: Long): Long? {
        prune(nowMillis)
        return shown.minOfOrNull { it.expiresAt }
    }

    /** 表示中を全部消す。受け付け済み id は保持する (配信停止→再開で同じコメントを再表示しない) */
    fun clear() {
        shown.clear()
    }

    private fun prune(nowMillis: Long) {
        // 期限は追加順に単調なので先頭から見れば足りる
        while (shown.isNotEmpty() && shown.first().expiresAt <= nowMillis) shown.removeFirst()
    }

    private companion object {
        const val SEEN_LIMIT = 500
    }
}
