package io.github.titagaki.genkaibroadcaster.comment

/**
 * コメント提供アプリとの接続状態。UI は [text] をそのまま表示する
 * (文言は既存の `StreamState.status` と同じく非 UI 層で組み立てる)。
 */
sealed class CommentSourceState {
    /** 配信画面の情報表示に出す 1 行。[Off] は空文字 (行を出さない) */
    abstract val text: String

    /** コメント表示なし (設定が「なし」か、配信していない) */
    data object Off : CommentSourceState() {
        override val text: String get() = ""
    }

    /** bind 中、または提供側が取得先へ接続中 */
    data class Connecting(val name: String) : CommentSourceState() {
        override val text: String get() = "コメント: $name に接続中"
    }

    /** 提供側からコメントが届く状態 */
    data class Ready(val name: String) : CommentSourceState() {
        override val text: String get() = "コメント: $name"
    }

    /** 提供側が見つからない・権限がない・版が違う・提供側の取得エラー */
    data class Error(val message: String) : CommentSourceState() {
        override val text: String get() = "コメント: $message"
    }
}
