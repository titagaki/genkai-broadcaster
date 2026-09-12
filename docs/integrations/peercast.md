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
| PCRPlayer (PeCaRecorder付属) | △ | 詳細設定→デコーダで DXVA2 をオフにする必要あり。本アプリの既定 (エンコーダ: 互換) なら DXVA2 オンでも可 (実機確認済み) |
| MPC-BE | × | |
| MPV / VLC / PeerstPlayer / ぺからいぶ！ | ○ | 推奨 |

### 原因 (2026-09-12 に本アプリの録画で確認)

ヘルプには原因の記載がない。本アプリの出力を解析した結果:

- 端末の HW エンコーダは `AVCProfileConstrainedBaseline` を指定しても
  **profile_idc=66 で constraint_set1_flag=0** (素の Baseline) の SPS を出す。
- LAV Filters の DXVA2 用 H.264 モード表は Constrained Baseline / Main / High のみで、
  素の Baseline (FMO/ASO を含みうる) は HW デコード対象外
  (`decoder/LAVVideo/decoders/dxva2/dxva_common.cpp` の `prof_h264_high`)。
- PCRPlayer の DXVA2 設定は自前 EVR が `IDirect3DDeviceManager9` を
  デコーダに公開するかの切替で、デコード自体は外部デコーダ (MS / LAV) が行う。
- High プロファイルや Bフレームは本アプリの出力には含まれておらず、原因ではない。
- Microsoft DTV-DVD Video Decoder も同じで、constraint_set1_flag を立てただけの録画は DXVA2 有効で再生できた (下記)。

### 本アプリの対策

- AAC-LC + GOP 2秒 + 適正 Level は有効 ([RootEncoder統合メモ](../engineering/rootencoder.md))。
- Constrained Baseline のヒントは HW エンコーダに無視されることがあるため、
  設定 → 映像 → エンコーダ で **「互換 (ソフトウェア)」** を選ぶ。AOSP の `c2.android.avc.encoder`
  (libavc) は Baseline 指定時に constraint_set1_flag を必ず立てる (`encoder/ih264e_utils.c`)。
  CPU 負荷・電池消費は増えるが、視聴側互換を優先して既定は「互換」。重い端末では「標準 (ハードウェア)」に切り替える。
- 検証済み (2026-09-12): 録画の SPS の constraint_set0/1 だけを立てたファイルは PCRPlayer + MS デコーダ +
  DXVA2 有効で再生できた。「互換」での実配信も `c2.android.avc.encoder` が選ばれ、DXVA2 有効のまま再生でき、
  720p30 の発熱は許容範囲だった。
