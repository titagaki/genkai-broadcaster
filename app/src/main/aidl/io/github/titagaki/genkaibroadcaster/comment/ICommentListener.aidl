// 配信アプリが実装し、提供側 (コメント取得アプリ) が呼ぶコールバック。
// すべて oneway にして、提供側が配信アプリの処理を待たされないようにする。
// 設計と取り決めは docs/engineering/comment-overlay.md を参照。
package io.github.titagaki.genkaibroadcaster.comment;

import io.github.titagaki.genkaibroadcaster.comment.CommentEntry;

oneway interface ICommentListener {
    /** 新着コメント。受信順に並べる。1 回の呼び出しで複数件まとめてよい */
    void onComments(in List<CommentEntry> comments);

    /**
     * 提供側の取得状態が変わった。
     * @param state  ICommentSource.STATE_*
     * @param detail 表示用の補足 (エラー内容など)。無ければ空文字
     */
    void onStateChanged(int state, String detail);
}
