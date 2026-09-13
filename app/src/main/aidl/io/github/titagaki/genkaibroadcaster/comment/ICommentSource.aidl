// 提供側 (コメント取得アプリ) が Service として公開し、配信アプリが配信中だけ bind して使う。
// 検出用 action と bind に必要な権限の名前は docs/engineering/comment-overlay.md §3.1 を参照。
package io.github.titagaki.genkaibroadcaster.comment;

import io.github.titagaki.genkaibroadcaster.comment.ICommentListener;

interface ICommentSource {
    /**
     * プロトコル版。非互換な変更 (メソッドの引数変更、CommentEntry のフィールド変更・追加) で上げる。
     * 配信アプリは getVersion() がこの値と一致しない提供側には接続しない。
     */
    const int VERSION = 1;

    /** onStateChanged の state。提供側がまだ取得を始めていない */
    const int STATE_IDLE = 0;
    /** 取得先へ接続中 */
    const int STATE_CONNECTING = 1;
    /** 取得中 (コメントが届く状態) */
    const int STATE_READY = 2;
    /** 取得に失敗している (detail に理由)。提供側が自力で復帰したら READY を送る */
    const int STATE_ERROR = 3;

    /** 実装しているプロトコル版 (VERSION を返す) */
    int getVersion();

    /** 設定画面・状態表示に使う表示名 (例: "PeerCast 掲示板") */
    String getSourceName();

    /**
     * 以後届くコメントをこの listener へ送る。登録前のコメントは再送しない。
     * 同じ listener の二重登録は無視する。
     */
    void registerListener(ICommentListener listener);

    /** 配信停止時に配信アプリが呼ぶ。配信アプリのプロセス終了時は提供側が死活検知で外す */
    void unregisterListener(ICommentListener listener);
}
