# Hoge Broadcaster 仕様書 (v0.1.0)

PeerCast向け Android RTMP配信アプリの仕様定義。
実装の基準とし、仕様変更時は本書を更新する。

- 対象バージョン: v0.1.0 (初期MVP)
- 最終更新: 2026-09-07

## 1. 概要・目的

スマートフォンのカメラ映像を PeerCast (PeerCastStation / PeerCast Gateway) へ
RTMP push で配信するための Android アプリ。

- メイン用途: PeerCast 配信 (野外・IRL等のスマホ起点配信)
- 方針: 余計な機能を持たせず「起動→URL/キー入力→配信開始」の最短動線に絞る
- 非目標: SRT 配信、チャット表示、OBS連携、マルチプラットフォーム同時配信 (対象外)

## 2. スコープ

### 2.1 対応 (MVP)

| # | 機能 | 概要 |
|---|------|------|
| F-01 | カメラプレビュー | 縦画面プレビュー表示 |
| F-02 | RTMP配信 開始/停止 | サーバーURL + ストリームキー指定で push |
| F-03 | 配信先プリセット | Gateway / 自宅Station例をワンタップ入力 |
| F-04 | 解像度選択 | 480p / 720p / 1080p |
| F-05 | ビットレート調整 | 800〜8000 kbps、配信中の on-the-fly 変更可 |
| F-06 | カメラ切替 | 前後カメラ切替 (配信中可) |
| F-07 | マイクミュート | 配信中のミュート切替 |
| F-08 | 自動再接続 | 切断時に5秒間隔・最大10回リトライ |
| F-09 | ステータス表示 | LIVEバッジ、経過時間、送信ビットレート |
| F-10 | 設定保存 | URL・キー・解像度・ビットレートを端末保存 |
| F-11 | バックグラウンド継続 | ForegroundService + 通知 (停止ボタン付き) |
| F-12 | 背面レンズ選択 | 複数背面カメラを画角表示 (例 26°/71°/97°) で選択 |
| F-13 | 音量メーター | マイク入力レベルを10セグメントで表示 |
| F-14 | 電池残量表示 | 上部バーに残量% + 充電状態を表示 |

### 2.2 非対応 (対象外)

- SRT / RTSP / WHIP 等、RTMP 以外の配信プロトコル
- チャット・コメント表示、ブラウザソース/オーバーレイ (将来候補あり)
- 縦横回転の動的対応 (ポートレート固定)

## 3. 対応環境

| 項目 | 値 |
|------|-----|
| OS | Android 8.0 (API 26) 以上 |
| minSdk | 26 |
| targetSdk | 34 |
| compileSdk | 36 |
| 画面 | ポートレート固定 |
| 実行環境 | 実機必須 (エミュレータのカメラでは配信検証不可の場合あり) |
| ビルド | Android Studio + Java 17 |

## 4. 技術スタック

| 層 | 採用 |
|----|------|
| 言語 | Kotlin |
| UI | Jetpack Compose (Material3) + `AndroidView` 内 `SurfaceView` |
| 配信エンジン | RootEncoder `library:2.8.1` の `GenericStream` (Camera2ソース) |
| 常駐化 | `StreamService` (foregroundServiceType `camera\|microphone`) |
| 設定保存 | SharedPreferences (`hoge_broadcaster`) |
| 依存取得 | JitPack (`com.github.pedroSG94.RootEncoder`) |

エンコードは H.264 (映像) + AAC-LC (音声) に固定し、
PeerCast の FLV 受け・古いPCプレーヤーとの互換性を優先する。
コーデック選択UIは持たない。

| 項目 | 設定 | 備考 |
|------|------|------|
| 映像コーデック | H.264 (`VideoCodec.H264` を明示) | |
| プロファイル | Constrained Baseline (`AVCProfileConstrainedBaseline`) | Bフレームなし、古いデコーダに最も安全 |
| Level | 解像度・FPSから自動選定 (`H264Level`) | MaxMBPS基準 (720p30→3.1、1080p30→4.0)。過剰なLevelを付けない |
| キーフレーム間隔 | 2秒 (`iFrameInterval=2`) | 途中参加時の復帰を早くする |
| 音声コーデック | AAC-LC (`AudioCodec.AAC` を明示。HE-AACではない) | |
| FPS | 30固定 | |

## 5. 配信プロトコル仕様

- 方式: RTMP push のみ。URLスキーム `rtmp://` 以外は受け付けない
- フルURLの組み立て: `サーバーURL(末尾/除去) + "/" + ストリームキー(前後/除去)`
  - キー空欄時はサーバーURLをそのまま使用
- 受け側の前提:
  - PeerCastStation: 先に Station 側で RTMP Source 配信開始 (SEARCHING)
    → 本アプリで push → RECEIVING で本番開始
  - PeerCast Gateway: FLVチャンネル作成後に発行される URL + 4桁キーを入力
    → Gateway 側 Receiving で本番開始
- 再送・ボンディング等の回線対策は持たない (単一RTMPコネクション)

## 6. 画面仕様

画面は「配信画面」「設定画面」の2画面構成 (Moblin風)。
配信画面は全画面プレビュー + オーバーレイ操作系とし、設定類は右上の歯車から開く設定画面に分離する。

### 6.1 配信画面レイアウト

```
+----------------------------------+
|● LIVE 00:00  [3.0 Mbps]    [歯車]|
|                                  |
|        (全画面プレビュー)         |
|                                  |
| [背面|前面]   [ライト] [マイク]  |
|              (開始/停止)         |
+----------------------------------+
```

### 6.2 配信画面UI要素仕様

- プレビュー: `SurfaceView` を全画面に埋め込み。権限取得後に開始
- 左端に音量メーター (10セグメント、ミュート時は消灯)
- 上部ステータスバー (半透明黒): LIVEバッジ (`● LIVE mm:ss` / 待機時は `○ <状態>`) + 送信統計 + 電池残量 + 右端に設定ボタン (歯車)
- 下部コントロールバー (半透明黒、Moblin風クイックボタン):
  - カメラ前後セグメント `[BACK|FRONT]` — 現在面をハイライト。タップでは切替えず、
    カメラ切替メニュー (ボトムシート) を開く2段階操作
  - カメラ切替メニュー — `FRONT` + 背面レンズ一覧 (例 `BACK 26°` `BACK 71°` `BACK 97°`) を
    ラジオ選択で切替。レンズ列挙失敗時は `BACK`/`FRONT` の2択
  - ライト切替 (背面カメラのランタンのみ有効、ON時は黄色表示)
  - マイクミュート (ミュート時は赤表示)
  - 中央の大きな円形ボタンで配信 開始/停止 (開始時=緑、配信中=赤)
- 不正URL時は開始せず、ボタン下に注意文を表示

### 6.3 設定画面UI要素仕様

- タイトルバー (戻るボタン + 「設定」)。端末の戻るボタンでも配信画面に戻る
- 配信先セクション: RTMPサーバーURL、ストリームキー、プリセット (`Gateway` / `自宅Station例`)
- 映像セクション: 解像度ドロップダウン、ビットレートスライダー (800〜8000 kbps)
- 権限再確認ボタン、PeerCast向け説明文
- 配信中はサーバーURL・キー・解像度を変更不可 (ビットレートのみ配信中変更可・即時反映)

## 7. 状態仕様

### 7.1 配信状態遷移

```
待機中 → 接続中... → LIVE ─→ 切断 → 待機中
                     ├→ 再接続中... → LIVE (リトライ成功時)
                     └→ 接続失敗 / 認証エラー → 待機中
```

### 7.2 状態と表示・動作の対応

| 状態 | バッジ | 動作 |
|------|--------|------|
| 待機中 | ○ 待機中/切断 | Service停止、タイマー0 |
| 接続中 | ○ 接続中... | Service起動済み |
| LIVE | ● LIVE + 経過時間 | Service起動中、統計表示 |
| 再接続中 | ○ 再接続中... | 5秒間隔で最大10回自動リトライ |
| 接続失敗/認証エラー | ○ 失敗理由 | Service停止、Toastで通知 |

### 7.3 エラーハンドリング

- URL形式不正: 配信開始しない + 赤字メッセージ
- 接続失敗: 自動リトライ (上限後は停止・理由表示)
- 認証エラー: 即停止
- カメラ/マイク権限なし: プレビュー開始せず、権限要求ダイアログ
- エンコード設定失敗 (prepare失敗): Toast通知して開始しない

## 8. 権限仕様

| 権限 | 用途 | 必須 |
|------|------|------|
| CAMERA | 映像取得 | ○ |
| RECORD_AUDIO | 音声取得 | ○ |
| INTERNET | RTMP送信 | ○ (インストール時) |
| POST_NOTIFICATIONS | 配信中通知 (API 33+) | 任意 (なければ通知のみ出ない) |
| FOREGROUND_SERVICE / CAMERA / MICROPHONE | 常駐配信 | ○ (宣言のみ) |
| WAKE_LOCK | 配信中のスリープ抑止 | 任意 |

## 9. 設定保存仕様

SharedPreferences `hoge_broadcaster` に保存:

| キー | 内容 | 既定値 |
|------|------|--------|
| rtmp_server | サーバーURL | `rtmp://pcgw.pgw.jp/live` |
| stream_key | ストリームキー | (空) |
| res_index | 解像度選択 (0/1/2) | 1 (720p) |
| bitrate_kbps | ビットレート | 3000 |

旧版キー `rtmp_url` が残っている場合は初回起動時に server/key へ分割移行し、旧キーを削除する。

## 10. ファイル構成 (保守性のための層分離)

```
hoge-broadcaster/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
├── docs/
│   └── spec.md (本書)
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/example/hogebroadcaster/
            ├── MainActivity.kt     (入口: 生成・権限・表示のみ。ロジック禁止)
            ├── StreamService.kt    (foreground keep-alive + 通知)
            ├── streamer/           (配信ドメイン層。Android UIに依存しない)
            │   ├── StreamConfig.kt     (定数の一元管理)
            │   ├── StreamPrefs.kt      (設定の読み書き集約)
            │   ├── StreamController.kt (GenericStream保持・配信操作・再接続)
            │   ├── CameraLenses.kt     (レンズ列挙・画角算出)
            │   └── LevelMeterEffect.kt (マイクレベル観測用素通しエフェクト)
            ├── system/             (端末情報層)
            │   └── BatteryMonitor.kt   (電池残量取得)
            └── ui/                 (表示層。エンコーダAPIを直接叩かない)
                ├── AppRoot.kt          (2画面の遷移管理)
                ├── StreamScreen.kt     (Moblin風配信画面)
                ├── SettingsScreen.kt   (設定画面)
                └── components.kt       (共有小部品)
```

層ルール:
- UI層 → streamer層は `StreamController` / `StreamPrefs` / `StreamConfig` 経由のみ
- 定数は `StreamConfig` に集約し、マジックナンバーを書かない
- 設定キー文字列は `StreamPrefs` 外に書かない

## 11. ビルド・実行手順

1. Android Studio でリポジトリルートを開く
2. Gradle sync (RootEncoder 2.8.1 を JitPack から取得)
3. 実機を選択して Run
4. 初回起動でカメラ・マイク権限を許可

## 12. 既知の注意・制約

- 一部の古いPCプレーヤー (PCRPlayer既定設定等) でスマホエンコード映像が再生できない場合がある。
  視聴側は VLC / MPV / PeerstPlayer を推奨 (Gateway公式ヘルプの互換表に準拠)。
- 単一RTMPコネクションのため、モバイル回線の瞬断時は自動再接続までの間、受け側が切断表示になる。
- ポートレート固定。横画面配信は非対応。

## 13. 今後の候補 (優先度順・未確定)

1. ビットレート自動調整 (帯域追従)
2. BBS.JPNKN のコメント表示
