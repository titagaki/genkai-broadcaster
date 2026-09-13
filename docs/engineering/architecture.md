# アーキテクチャ

製品の振る舞いは[製品仕様](../product/spec.md)を正本とし、本書では実装構成と層の責務を扱います。

## 技術スタック

| 項目 | 採用 |
|------|------|
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) + `AndroidView`内の`SurfaceView` |
| 配信エンジン | RootEncoder 2.8.1の`GenericStream`とCamera2ソース |
| 常駐化 | `StreamService` (`foregroundServiceType="camera\|microphone"`) |
| 設定保存 | SharedPreferences (`genkai_broadcaster`)。接続先一覧は `org.json` でJSON文字列として保存 |
| 依存取得 | JitPack (`com.github.pedroSG94.RootEncoder`) |

## 層構成

```text
app/src/main/java/io/github/titagaki/genkaibroadcaster/
├── MainActivity.kt          生成、権限要求、画面方向、Compose表示
├── StreamService.kt         Foreground Serviceと通知
├── streamer/
│   ├── StreamController.kt  配信・プレビュー・マイク。UI からの唯一の入口 (カメラ操作は転送)
│   ├── CameraController.kt  レンズ切替 (Capture完了待ちの状態機械) とズーム。internal
│   ├── LensCatalog.kt       レンズ一覧から選択肢・倍率を決める純粋ロジック (JVMテスト対象)
│   ├── CameraLenses.kt      Camera2 からのレンズ列挙 (LensOption)
│   ├── StreamState.kt       UI が購読する状態モデル (StreamState, CameraZoomState など)
│   ├── StreamConfig.kt      定数 (解像度一覧 RESOLUTIONS を含む)
│   ├── StreamPrefs.kt       SharedPreferences の読み書き (インスタンス)
│   ├── StreamDestination.kt 接続先モデルとプリセット
│   ├── Resolution.kt / H264Level.kt / LevelMeterEffect.kt
├── comment/                 コメント提供アプリとの連携 (現状 CommentEntry のみ。設計は comment-overlay.md)
├── system/                  電池・マイクなどの端末情報 (BatteryMonitor, MicrophoneMonitor)
└── ui/
    ├── AppRoot.kt           画面切替、通知文 (messages) の Toast 表示
    ├── Theme.kt             MaterialTheme の配色と、プレビュー上に重ねる部品の色 (OverlayColors)
    ├── StreamScreen.kt      配信画面
    ├── Components.kt        画面をまたいで使う部品
    └── settings/            設定画面 (SettingsScreen と各ページ、設定画面専用の部品)
app/src/main/aidl/.../comment/    コメント提供アプリとの AIDL 契約 (ICommentSource / ICommentListener / CommentEntry)
app/src/test/java/.../streamer/   LensCatalog / H264Level / StreamDestination の JVM テスト
```

## 層ルール

- `MainActivity`は生成、権限、表示だけを担当し、配信ロジックを持たない。
- UI層から配信機能を使う場合は`StreamController`、`StreamPrefs`、`StreamConfig`、`StreamDestination`を経由する。
  `CameraController`は`internal`で、UIは`StreamController`の転送メソッド (`cameraZoomChoices`、`selectCamera`、`changeZoomBy`など) だけを使う。
- 定数は`StreamConfig`、SharedPreferencesのキー文字列は`StreamPrefs`へ集約する。`StreamPrefs`はインスタンスとして
  `MainActivity`が生成し、UI層には`SharedPreferences`型を渡さない。
- streamer層はToastなどのUIを直接出さない。一時的な通知文は`StreamController.messages` (SharedFlow) に流し、`AppRoot`がToastで表示する。
- `StreamState.status`の文言は現状streamer層で組み立てている (日本語専用アプリのため`strings.xml`化もしていない)。
  多言語化する場合は`status`を`sealed class`にし、文字列化をUI層へ移す。
- Camera2・エンジンに依存しないロジックは`LensCatalog`のように純粋クラスへ寄せ、`app/src/test`でJVMテストする。
- UI層からRootEncoder APIを直接呼ばない。
- FLV／RTMPの内部処理、独自muxer、独自エンコーダーを追加しない。
