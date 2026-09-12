# 開発環境・ビルド

リファクタリングの候補・優先順位・注意点は [refactoring.md](refactoring.md) を参照。
UI変更は、[現在のUI仕様](../ui/current.md)で現状を確認し、[UI設計](../ui/design.md)を合意してから実装する。
参考アプリの調査結果は [UI参考調査](../ui/references.md) を参照。

## 環境構成

- 実ビルドは Windows 側の Android Studio で行う。WSL 側に java / gradle は無い。
- Android SDK: `C:/Users/megan/AppData/Local/Android/Sdk`
  - 注意: `platforms` には `android-36` のみ入っている。`compileSdk = 36` はそのため。
    他の platform を入れれば下げられるが、現状 36 前提で書くこと。
- Java は Android Studio 同梱の JBR を使う。プロジェクトのバイトコードは Java 17。

## バージョン (検証済み組み合わせ)

| 項目 | バージョン |
|------|-----------|
| AGP | 8.9.2 |
| KGP | 2.3.21 |
| Gradle (wrapper) | 8.13 |
| compileSdk / targetSdk / minSdk | 36 / 34 / 26 |
| Compose BOM | 2025.01.00 |
| RootEncoder | 2.8.1 (JitPack) |

KGP と AGP の組み合わせを変える場合は、公式互換表
(`kotlinlang.org/docs/gradle-configure-project.html`) で AGP 対応範囲を確認すること。

## ビルド手順 (ユーザー向け案内)

1. Android Studio でリポジトリルートを開く
2. Gradle Sync
3. Build → Clean Project → Rebuild Project (実機で実行)

## エージェント側の検証手段

コンパイルはできないため、以下で代替する:

```bash
# 括弧バランス (全Kotlinファイル)
python3 -c "
import pathlib
base = pathlib.Path('app/src/main/java/com/example/genkaibroadcaster')
for f in sorted(base.rglob('*.kt')):
    p = f.read_text()
    print(f.name, p.count('{')-p.count('}'), p.count('(')-p.count(')'))
"
# リネーム・削除後の参照残りチェック
grep -rn "旧シンボル名" app/src
```

## 実機確認

### 縦横配信・UI変更後の実機確認

以下は確認手順であり、エージェント側では実施していない。

- 480p / 720p / 1080p それぞれで縦・横を選択し、プレビューと受信映像の上下・左右が一致することを確認する。
  VLC等のコーデック情報で、横は854x480 / 1280x720 / 1920x1080、縦は幅・高さを交換した出力になっていることを確認する。
- 停止中に縦・横を繰り返し切替し、方向だけの変更でも反映されること、次回起動でも選択が残ることを確認する。
- 設定画面内で方向を変え、Activity再生成後も設定画面に留まり、入力済み設定が保持されることを確認する。
- 接続中・LIVE・再接続中に方向変更ができないこと、停止後は変更できることを確認する。
- `FRONT`・`BACK`・各倍率、ミュート状態で設定往復と画面再生成を試し、映像・ミュート・経過時間が保持されることを確認する。
- 前面・背面でピンチズームし、表示倍率と送信映像が連続して変化し、最小・最大倍率で止まることを確認する。
- デバッグAPKの設定画面でズーム方式を自動・デジタル強制・論理強制へ切り替え、診断表示と実際の動作を確認する。
  論理マルチカメラ非対応端末では論理強制時に案内が出て、安全にデジタルズームへ残ることを確認する。
- カメラ切替失敗・切断・5秒タイムアウト時に操作不能にならず、停止中は別レンズ選択で再構築できることを確認する。
  配信中のカメラエラーでは配信が停止し、プレビューが再構築されることを確認する。
- 縦横両画面で文字サイズを拡大し、オーバーレイが重ならず、設定・停止ボタンを押せることを確認する。
- 通知停止・再接続断念・認証エラー後に送信が止まり、再度開始できることを確認する。

## トラブル履歴

### 1. KGP 2.1.0 で K2 コンパイラ内部エラー (2026-09-06)

- 症状: `:app:compileDebugKotlin` で `FileAnalysisException`。
  `FirIncompatibleClassExpressionChecker` が `registerForActivityResult` の SAM ラムダ (93行目) で
  `IllegalArgumentException: source must not be null`。
  同時に `kotlin-stdlib 2.3.21` 等の `Module was compiled with an incompatible version of Kotlin.
  The binary version of its metadata is 2.3.0, expected version is 2.1.0.` が出る。
- 原因: 依存ライブラリが Kotlin 2.3 メタデータで、KGP 2.1.0 が読めない。
- 対処: KGP を 2.3.21 に上げたら解消 (コード側の修正は不要だった)。

### 2. `kotlinOptions` がエラーになる (KGP 2.3〜)

- 症状: `Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.`
- 対処: `app/build.gradle.kts` を以下に変更。
  ```kotlin
  import org.jetbrains.kotlin.gradle.dsl.JvmTarget
  // ...
  kotlin {
      compilerOptions {
          jvmTarget.set(JvmTarget.JVM_17)
      }
  }
  ```

### 3. `disableAudio() / enableAudio()` が無い

- 新 GenericStream API (`StreamBase`) には存在しない。旧 API の記憶で書いたのが原因。
- 対処: `MicrophoneSource.mute() / unMute()` を使う。詳細は [RootEncoder統合メモ](rootencoder.md)。

### 4. リファクタ時の参照残り (`StreamPrefs.FILE`)

- 定数集約のリネーム後に `MainActivity` の参照が古いまま残り `Unresolved reference`。
- 教訓: リネーム・集約後は必ず `grep` で旧シンボル名の残存を確認する。
