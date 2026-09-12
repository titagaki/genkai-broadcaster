# リファクタリング記録

- 初回レビュー: 2026-09-07
- 実施日: 2026-09-08
- 状態: コード変更と静的検証は完了。Android Studioでのビルドと実機確認は未実施

振る舞いの正本は[製品仕様](../product/spec.md)です。本書は、静的レビューで見つかった
重複・二重管理と、その修正内容を記録します。

## 実施結果

| 項目 | 状態 | 主な変更 |
|------|------|----------|
| カメラ表示状態の一本化 | 完了 | 前後・レンズ・倍率を`StreamState`から表示 |
| プレビュー処理の一方向化 | 完了 | 準備とSurface接続を分離し、停止処理を共通化 |
| 準備設定の型化 | 完了 | 連結文字列を`StreamPreparationConfig`へ置換 |
| ビットレート単位の集約 | 完了 | UIはkbps、Controller内でbpsへ変換 |
| RTMP URL判定の共通化 | 完了 | `StreamPrefs.isAcceptedRtmpUrl()`へ集約 |
| 高頻度UI更新の局所化 | 完了 | 音量、経過時間、電池の状態を表示部品内へ移動 |
| レンズ重複判定 | 完了 | 表示ラベルではなくCamera2 IDの組で識別 |

## カメラ状態

対象: [StreamController.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamController.kt)、
[StreamScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/StreamScreen.kt)

- `StreamState`に`cameraIsFront`、`selectedLens`、`zoom`、`cameraError`を保持する。
- UIはControllerへ現在値を再照会せず、`StateFlow`の確定済み状態だけを表示する。
- ピンチ倍率は`changeZoomBy()`で現在倍率へ適用し、UI側に倍率の複製を持たない。
- 復元要求の`savedLens`、`savedFront`、`savedZoomRatio`と、公開する確定状態を区別する。
- カメラ切替待機中にSurfaceが外れても、要求中の復元値を古い確定状態で上書きしない。

## プレビュー処理

対象: [StreamController.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamController.kt)

- `stopPreviewAndReleaseIfIdle()`へSurface喪失時の共通処理を集約した。
- `prepareFromPrefs()`は映像・音声の準備と成否返却だけを担当する。
- `startPreparedPreviewIfReady()`は準備済みエンジンへのSurface接続だけを担当する。
- 準備関数から`startPreviewIfReady()`を呼び戻さず、呼び出し元が再開を判断する。
- 配信中のSurface再接続では、動作中のカメラを開き直さない。
- エンジン世代、Service session、CaptureSession世代による遅延通知の除外は維持する。

## 設定と単位

対象: [StreamConfig.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamConfig.kt)、
[StreamPrefs.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamPrefs.kt)、
[SettingsScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/SettingsScreen.kt)

- 準備済み条件は幅、高さ、映像ビットレート、回転を持つ`StreamPreparationConfig`で比較する。
- SharedPreferencesとUIのビットレート単位は従来どおりkbpsとする。
- RootEncoderへ渡す直前だけController内でbpsへ変換する。
- URLの許可条件は従来どおり、小文字の`rtmp://`で始まることとする。
- UIとControllerは同じ判定関数を使い、画面内エラーとToastの役割分担は維持する。

## UI更新範囲

対象: [StreamScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/StreamScreen.kt)

- 100msごとの音量取得と減衰は`LiveAudioMeter`内で管理する。
- 1秒ごとの経過時間と30秒ごとの電池取得は`StreamInfo`内で管理する。
- 開始時刻は引き続きControllerの`StreamState.startedAtMs`を正本とする。
- 画面を離れると各`LaunchedEffect`がCompositionとともに停止する。

## 実機確認

詳しい手順は[開発環境・ビルド](development.md)を参照してください。特に次を確認します。

- 設定画面往復、Activity再生成、バックグラウンド復帰後もカメラ表示と実映像が一致する。
- カメラ切替中に画面を離れても、復帰後に要求したカメラと倍率が適用される。
- 解像度、ビットレート、方向のいずれかを変えた場合だけ再準備される。
- 配信中のビットレート変更が800,000～8,000,000 bpsとして反映される。
- URLエラー表示、自動再接続、通知停止、停止直後のプレビュー再開が従来どおり動作する。
- 音量の減衰、ミュート時の即時消灯、経過時間、電池表示が従来どおり更新される。
