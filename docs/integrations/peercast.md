# PeerCast接続ガイド

PeerCastStationとPeerCast Gatewayは、本アプリが対応するRTMP配信先の一例です。
本アプリはPeerCast専用ではありませんが、どちらもRTMP pushを受け付けるため接続できます。

## PeerCastStation での受け方

出典: `peercaststation/doc/help/broadcast.mkd`
(`https://github.com/kumaryu/peercaststation/blob/master/doc/help/broadcast.mkd`)

- 配信ダイアログでソース **RTMP Source** を選択。
- ストリームURLは `rtmp://localhost/live/livestream` のままで可
  (ホスト以降のパスは見ていない。任意のキーでよい)。
- タイプは FLV (RTMP は自動で FLV 扱い)。
- 手順: 先に Station 側で配信開始 (状態 SEARCHING) → エンコーダ側で push 開始 → RECEIVING で本番。

## PeerCast Gateway での受け方

出典: `https://pcgw.pgw.jp/doc/streamlabs` (Streamlabs Android アプリでの配信の仕方)

- RTMP URL: `rtmp://pcgw.pgw.jp/live`、キーはチャンネル作成時に表示される4桁の数字。
- ストリームタイプ FLV でチャンネル作成 → Gateway 側 Receiving で本番。
- 歴史的経緯: Android からの Peca 配信は `AndroidPecaBroadcaster` 等があったが、
  現行 Android ではインストール不可。汎用 RTMP アプリで Gateway を使うのが現実解。

## 視聴側プレーヤー互換表 (Gateway 公式ヘルプより)

スマホエンコード映像は、PC の古いプレーヤーで再生できない場合がある。

| プレーヤー | 可否 | 備考 |
|------------|------|------|
| PCRPlayer (PeCaRecorder付属) | △ | 詳細設定→デコーダで DXVA2 をオフにする必要あり |
| MPC-BE | × | |
| MPV / VLC / PeerstPlayer / ぺからいぶ！ | ○ | 推奨 |

原因の主流説: スマホの H.264 (High/Main + Bフレーム等) を古い DirectShow 系の
ハードウェアデコード経路が処理しきれない。VLC/MPV は FFmpeg ソフトウェアデコードで寛容。

本アプリの対策: Constrained Baseline + AAC-LC + GOP 2秒 + 適正 Level
(詳細は[RootEncoder統合メモ](../engineering/rootencoder.md))。ただし実害の報告は未確認。
