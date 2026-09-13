package io.github.titagaki.genkaibroadcaster.comment

/**
 * コメントを映像のどこに置くか。左右は右揃え固定で、上下だけ選べる。
 *
 * @param id 設定の保存値
 * @param label 設定画面の表示名
 */
enum class CommentPosition(val id: String, val label: String) {
    /** 右下。新しいものが下 */
    BOTTOM("bottom", "下"),
    /** 右上。上から古い順に並び、新しいものが下 */
    TOP("top", "上");

    companion object {
        /** 保存値から引く。未知の値は [BOTTOM] */
        fun fromId(id: String?): CommentPosition = entries.firstOrNull { it.id == id } ?: BOTTOM
    }
}
