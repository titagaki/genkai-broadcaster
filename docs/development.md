# 開発環境・ビルド

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
base = pathlib.Path('app/src/main/java/com/example/hogebroadcaster')
for f in sorted(base.rglob('*.kt')):
    p = f.read_text()
    print(f.name, p.count('{')-p.count('}'), p.count('(')-p.count(')'))
"
# リネーム・削除後の参照残りチェック
grep -rn "旧シンボル名" app/src
```

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
- 対処: `MicrophoneSource.mute() / unMute()` を使う。詳細は `docs/rootencoder.md`。

### 4. リファクタ時の参照残り (`StreamPrefs.FILE`)

- 定数集約のリネーム後に `MainActivity` の参照が古いまま残り `Unresolved reference`。
- 教訓: リネーム・集約後は必ず `grep` で旧シンボル名の残存を確認する。
