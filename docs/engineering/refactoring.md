# リファクタリング記録

振る舞いの正本は[製品仕様](../product/spec.md)です。本書は、静的レビューで見つかった
重複・二重管理と、その修正内容を記録します。新しい記録を上に追加します。

## 第2回: 構造整理 (2026-09-13)

- 観点: クラス分割の適切さ、ベストプラクティス、重複
- 状態: コード変更と静的検証は完了。Android Studioでのビルド・単体テスト・実機確認は未実施
- 振る舞いの変更は無し (UI の見た目・文言・Toast の出るタイミングは従来どおり)

| 項目 | 主な変更 |
|------|----------|
| `StreamController` の分割 (881行 → 468行) | レンズ切替・ズームを`CameraController`へ、レンズ選択の純粋ロジックを`LensCatalog`へ、状態モデルを`StreamState.kt`へ |
| streamer層からのUI依存を除去 | `Toast`を`StreamController.messages` (SharedFlow) に置換。`AppRoot`が購読してToast表示 |
| `StreamPrefs`のインスタンス化 | `StreamPrefs(context)`。UI層から`SharedPreferences`型を排除。`loadActiveDestination`のJSON二重パースも解消 |
| 定数の整理 | `RESOLUTIONS`を`StreamConfig`内へ。`Resolution`は独立ファイル、`StreamPreparationConfig`は`StreamController`内のprivateへ |
| `SettingsScreen`の分割 (664行) | `ui/settings/`に`SettingsScreen`/`DestinationPages`/`VideoPage`/`CameraPage`/`SetupPage`/`SettingsComponents`。縦並びラジオ選択を`RadioOptionList<T>`に統合 |
| テーマ・色の集約 | `ui/Theme.kt`に`GenkaiTheme`と`OverlayColors`。`StreamScreen`内の色リテラルを置換 |
| `MutableStateFlow`更新の慣用化 | `mutableState.value = state.value.copy(...)`を`update { it.copy(...) }`へ |
| 失敗の可視化 | 意味のある`runCatching`に`Log.w`を付与 (カメラ・prepare・マイク・ズーム) |
| デッドコード除去 | minSdk 26で常に真な`>= O`/`< LOLLIPOP`分岐、`33`のマジックナンバー (`TIRAMISU`) |
| 通知アイコン | `android.R.drawable`のフレームワーク資源をやめ、`res/drawable/ic_notification_*.xml`を追加 |
| `isDebuggable` | `ApplicationInfo.FLAG_DEBUGGABLE`から`BuildConfig.DEBUG`へ (`buildFeatures.buildConfig = true`) |
| 単体テスト追加 | `app/src/test`に`LensCatalogTest`/`H264LevelTest`/`StreamDestinationTest` (JUnit 4) |
| 依存の明示 | `kotlinx-coroutines-android`、`junit`、テスト用`org.json` |
| 細部 | import順を辞書順に統一、`components.kt`→`Components.kt`、`StreamService`の定数をprivateに |

### 見送ったもの

- `StreamState.status`の文言のsealed class化と`strings.xml`化: 日本語専用のため現状維持。判断は[アーキテクチャ](architecture.md)に記載
- ktlint / detekt / Version Catalog の導入: ビルド構成の変更はKGP互換の確認が必要なため、別途ユーザー側で
- 既存の`ExposedDropdownMenuBox.menuAnchor()`の非推奨警告: BOM更新時に対応

### 第2回の実機確認

- 起動 → プレビュー → 配信開始/停止 → 再プレビューの一巡。Toast (「配信開始!」「再接続します」等) が従来どおり出ること
- `FRONT`/`BACK`切替、各倍率の選択、ピンチズーム、切替中に設定画面へ移動して戻る (`CameraController`へ移した状態機械の確認)
- カメラ切替失敗時 (タイムアウト・切断) に`cameraError`表示と配信停止が起きること
- 設定画面: 接続先の追加・編集・削除・選択、映像設定の各項目、デバッグビルドのカメラページ (ラジオ選択の見た目が従来どおり)
- 通知の小アイコンと「停止」アクションの表示
- `app/src/test`の全テストが通ること

## 第1回: 重複・二重管理の解消 (2026-09-08)

- 初回レビュー: 2026-09-07
- 状態: コード変更と静的検証は完了。Android Studioでのビルドと実機確認は未実施

### 実施結果

| 項目 | 状態 | 主な変更 |
|------|------|----------|
| カメラ表示状態の一本化 | 完了 | 前後・レンズ・倍率を`StreamState`から表示 |
| プレビュー処理の一方向化 | 完了 | 準備とSurface接続を分離し、停止処理を共通化 |
| 準備設定の型化 | 完了 | 連結文字列を`StreamPreparationConfig`へ置換 |
| ビットレート単位の集約 | 完了 | UIはkbps、Controller内でbpsへ変換 |
| RTMP URL判定の共通化 | 完了 | `StreamPrefs.isAcceptedRtmpUrl()`へ集約 |
| 高頻度UI更新の局所化 | 完了 | 音量、経過時間、電池の状態を表示部品内へ移動 |
| レンズ重複判定 | 完了 | 表示ラベルではなくCamera2 IDの組で識別 |

### カメラ状態

対象: [StreamController.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamController.kt)、
[StreamScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/StreamScreen.kt)

- `StreamState`に`cameraIsFront`、`selectedLens`、`zoom`、`cameraError`を保持する。
- UIはControllerへ現在値を再照会せず、`StateFlow`の確定済み状態だけを表示する。
- ピンチ倍率は`changeZoomBy()`で現在倍率へ適用し、UI側に倍率の複製を持たない。
- 復元要求の`savedLens`、`savedFront`、`savedZoomRatio`と、公開する確定状態を区別する。
- カメラ切替待機中にSurfaceが外れても、要求中の復元値を古い確定状態で上書きしない。

### プレビュー処理

対象: [StreamController.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamController.kt)

- `stopPreviewAndReleaseIfIdle()`へSurface喪失時の共通処理を集約した。
- `prepareFromPrefs()`は映像・音声の準備と成否返却だけを担当する。
- `startPreparedPreviewIfReady()`は準備済みエンジンへのSurface接続だけを担当する。
- 準備関数から`startPreviewIfReady()`を呼び戻さず、呼び出し元が再開を判断する。
- 配信中のSurface再接続では、動作中のカメラを開き直さない。
- エンジン世代、Service session、CaptureSession世代による遅延通知の除外は維持する。

### 設定と単位

対象: [StreamConfig.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamConfig.kt)、
[StreamPrefs.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/streamer/StreamPrefs.kt)、
[SettingsScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/settings/SettingsScreen.kt)

- 準備済み条件は幅、高さ、映像ビットレート、回転を持つ`StreamPreparationConfig`で比較する。
- SharedPreferencesとUIのビットレート単位は従来どおりkbpsとする。
- RootEncoderへ渡す直前だけController内でbpsへ変換する。
- URLの許可条件は従来どおり、小文字の`rtmp://`で始まることとする。
- UIとControllerは同じ判定関数を使い、画面内エラーとToastの役割分担は維持する。

### UI更新範囲

対象: [StreamScreen.kt](../../app/src/main/java/io/github/titagaki/genkaibroadcaster/ui/StreamScreen.kt)

- 100msごとの音量取得と減衰は`LiveAudioMeter`内で管理する。
- 1秒ごとの経過時間と30秒ごとの電池取得は`StreamInfo`内で管理する。
- 開始時刻は引き続きControllerの`StreamState.startedAtMs`を正本とする。
- 画面を離れると各`LaunchedEffect`がCompositionとともに停止する。

### 実機確認

詳しい手順は[開発環境・ビルド](development.md)を参照してください。特に次を確認します。

- 設定画面往復、Activity再生成、バックグラウンド復帰後もカメラ表示と実映像が一致する。
- カメラ切替中に画面を離れても、復帰後に要求したカメラと倍率が適用される。
- 解像度、ビットレート、方向のいずれかを変えた場合だけ再準備される。
- 配信中のビットレート変更が800,000～8,000,000 bpsとして反映される。
- URLエラー表示、自動再接続、通知停止、停止直後のプレビュー再開が従来どおり動作する。
- 音量の減衰、ミュート時の即時消灯、経過時間、電池表示が従来どおり更新される。
