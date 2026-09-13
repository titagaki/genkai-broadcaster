# 視聴側プレーヤー互換性 (ハードウェアエンコードと DXVA2)

スマホエンコード映像は、PC の古いプレーヤーで再生できない場合がある。
PeerCast に限らず、DXVA2 経路で H.264 をデコードするプレーヤー全般に関わる話なので、接続先ごとの手順とは分けて置く。

## 互換表 (PeerCast Gateway 公式ヘルプより)

| プレーヤー | 可否 | 備考 |
|------------|------|------|
| PCRPlayer (PeCaRecorder付属) | △ | 詳細設定→デコーダで DXVA2 をオフにする必要あり。本アプリの既定 (エンコーダ: 互換) なら DXVA2 オンでも可 (実機確認済み) |
| MPC-BE | × | |
| MPV / VLC / PeerstPlayer / ぺからいぶ！ | ○ | 推奨 |

## 原因 (2026-09-12 に本アプリの録画で確認)

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

## 本アプリの対策

- AAC-LC + GOP 2秒 + 適正 Level は有効 ([RootEncoder統合メモ](../engineering/rootencoder.md))。
- Constrained Baseline のヒントは HW エンコーダに無視されることがあるため、
  設定 → 映像 → エンコーダ で **「互換 (ソフトウェア)」** を選ぶ。AOSP の `c2.android.avc.encoder`
  (libavc) は Baseline 指定時に constraint_set1_flag を必ず立てる (`encoder/ih264e_utils.c`)。
  CPU 負荷・電池消費は増えるが、視聴側互換を優先して既定は「互換」。重い端末では「標準 (ハードウェア)」に切り替える。
- 検証済み (2026-09-12): 録画の SPS の constraint_set0/1 だけを立てたファイルは PCRPlayer + MS デコーダ +
  DXVA2 有効で再生できた。「互換」での実配信も `c2.android.avc.encoder` が選ばれ、DXVA2 有効のまま再生でき、
  720p30 の発熱は許容範囲だった。
