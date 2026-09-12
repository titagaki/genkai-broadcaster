# アーキテクチャ

製品の振る舞いは[製品仕様](../product/spec.md)を正本とし、本書では実装構成と層の責務を扱います。

## 技術スタック

| 項目 | 採用 |
|------|------|
| 言語 | Kotlin |
| UI | Jetpack Compose (Material 3) + `AndroidView`内の`SurfaceView` |
| 配信エンジン | RootEncoder 2.8.1の`GenericStream`とCamera2ソース |
| 常駐化 | `StreamService` (`foregroundServiceType="camera\|microphone"`) |
| 設定保存 | SharedPreferences (`genkai_broadcaster`) |
| 依存取得 | JitPack (`com.github.pedroSG94.RootEncoder`) |

## 層構成

```text
app/src/main/java/com/example/genkaibroadcaster/
├── MainActivity.kt      生成、権限要求、画面方向、Compose表示
├── StreamService.kt     Foreground Serviceと通知
├── streamer/            配信、カメラ、設定値
├── system/              電池などの端末情報
└── ui/                  画面と表示部品
```

## 層ルール

- `MainActivity`は生成、権限、表示だけを担当し、配信ロジックを持たない。
- UI層から配信機能を使う場合は`StreamController`、`StreamPrefs`、`StreamConfig`を経由する。
- 定数は`StreamConfig`、SharedPreferencesのキー文字列は`StreamPrefs`へ集約する。
- UI層からRootEncoder APIを直接呼ばない。
- FLV／RTMPの内部処理、独自muxer、独自エンコーダーを追加しない。
