# 開発環境・ビルド

リファクタリングの候補・優先順位・注意点は [refactoring.md](refactoring.md) を参照。
UI変更は、[現在のUI仕様](../ui/current.md)で現状を確認し、[UI設計判断](../ui/design.md)に判断と理由を追記してから実装する。

## 環境構成

- 実ビルドは Windows 側の Android Studio で行う。WSL 側に java / gradle は無い。
- Android SDK: `C:/Users/megan/AppData/Local/Android/Sdk`
  - 注意: `platforms` には `android-36` のみ入っている。`compileSdk = 36` はそのため。
    他の platform を入れれば下げられるが、現状 36 前提で書くこと。
- Java は Android Studio 同梱の JBR (JDK 25) を使う。プロジェクトのバイトコードは Java 17。

## バージョン (検証済み組み合わせ)

| 項目 | バージョン |
|------|-----------|
| AGP | 9.0.1 (Kotlin 内蔵。`org.jetbrains.kotlin.android` は適用しない) |
| Kotlin / KGP | 2.3.21 (`org.jetbrains.kotlin.plugin.compose` の版で決まる。AGP 9.0.1 同梱の KGP は 2.2.10 だが、Compose プラグインが引き込む 2.3.21 が classpath 上で勝つ) |
| Gradle (wrapper) | 9.2.1 (`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` を同梱。Gradle 本体は初回実行時に自動取得) |
| compileSdk / targetSdk / minSdk | 36 / 34 / 26 |
| Compose BOM | 2025.01.00 |
| RootEncoder | 2.8.1 (JitPack) |

KGP と AGP の組み合わせを変える場合は、公式互換表
(`kotlinlang.org/docs/gradle-configure-project.html`) で AGP 対応範囲を確認すること。
2026-09 時点の表では KGP 2.3.20〜2.3.21 は Gradle 7.6.3〜9.3.0 / AGP 8.2.2〜9.0.0 が対応範囲。
AGP 9.0.1 はこの上限をわずかに超えるが、9.0.x のパッチ差なので警告止まりの想定 (実ビルドで確認)。
AGP 9.4.0 (Android Studio 2026.1.4 同梱) へ上げる場合は Gradle 9.6.0 以上が必要で、
KGP も 2.4.x (AGP 〜9.3.1 対応) に上げるのが筋。別コミットで行う。

## ビルド手順 (ユーザー向け案内)

1. Android Studio でリポジトリルートを開く
2. Gradle Sync
3. Build → Clean Project → Rebuild Project (実機で実行)

## リリースビルド (署名)

- 署名情報はリポジトリルートの `keystore.properties` (git 管理外) から `app/build.gradle.kts` が読む。
  雛形は `keystore.properties.example`。ファイルが無ければ release は未署名でビルドされる。
- 鍵ファイル (`*.jks`) はリポジトリ外 (例: `C:/Users/<name>/keys/`) に置き、`storeFile` に絶対パスを書く。
  `*.jks` `*.keystore` `keystore.properties` は `.gitignore` 済み。
- **鍵を失うと同じ applicationId で更新版を配布できなくなる** (利用者は再インストールが必要)。鍵とパスワードは必ずバックアップする。

### 鍵の作成 (初回のみ、Android Studio)

1. Build → Generate Signed App Bundle or APK… → APK → Next
2. Key store path の「Create new…」で保存先 (リポジトリ外)・パスワード・エイリアス・有効期限 (25年以上) を入力
3. 作成した値を `keystore.properties` に書く

### リリース APK の作成

- Build → Select Build Variant… で `app` を release にし、Build → Generate App Bundles or APKs → Generate APKs
  (`keystore.properties` があれば署名される)。終わったら Build Variant を debug に戻す (戻し忘れるとデバッグ用ページが出ない)。
  Build → Generate Signed App Bundle or APK… のウィザードでも可 (鍵情報を都度入力、出力は `app/release/`)。
- 出力: `app/build/outputs/apk/release/app-release.apk`。
  続けて `distReleaseApk` タスクが自動で走り、配布用の `app/build/outputs/dist/genkai-broadcaster-v<versionName>.apk` を作る。
- 署名の確認: `app-release.apk` (末尾に `-unsigned` が付いていない) なら `keystore.properties` の鍵で署名されている。
  厳密に見るなら `apksigner verify --print-certs <apk>` (build-tools 内) で証明書の CN を確認する。
- 配布は GitHub Releases にタグ (`vX.Y.Z`) を切って `dist/` の APK を添付する (README のインストール手順とファイル名を合わせている)。
  例: `gh release create v0.1.0 app/build/outputs/dist/genkai-broadcaster-v0.1.0.apk --title "v0.1.0" --notes-file notes.md`
- リリースごとに `versionCode` を +1、`versionName` をタグと合わせる。

## アプリアイコン

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` の adaptive icon (background 色 + foreground + monochrome)。
- 元絵は 1024px の角丸スクエア画像 (緑背景にキャラクター)。背景色 `#39A24F` を `values/ic_launcher_background.xml` に置き、
  foreground は元絵から緑背景を抜いたキャラクターのみを、外接円が 66dp セーフゾーンに収まるよう縮小して 108dp キャンバス中央に配置した PNG (mdpi〜xxxhdpi)。
- monochrome (Android 13 のテーマアイコン用) はキャラクターのシルエット。
- minSdk 26 のため旧式の `ic_launcher.png` は置いていない。差し替え時は同じ手順で PNG を作り直す。

## 単体テスト

`app/src/test` に JVM テスト (JUnit 4) を置いている。Android Studio で `app/src/test` を右クリック → Run、
または PowerShell でプロジェクト直下から `.\gradlew.bat :app:testDebugUnitTest`。`org.json` は android.jar のスタブが例外を投げるため、
`testImplementation("org.json:json")` で実装を差し込んでいる。

コマンドラインで実行する場合、`gradlew.bat` は `JAVA_HOME` を見る。Gradle 9.2.1 は JDK 25 で動くので、
Android Studio 同梱の `jbr` をそのまま使える:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

(Gradle 8.13 時代は JDK 25 で起動できず JDK 21 を別途指定していた。経緯はトラブル履歴 5 を参照)

対象は Android/エンジンに依存しない純粋ロジックのみ (`LensCatalog`、`H264Level`、`StreamDestination`)。
`CameraController` や `StreamController` は Camera2 と RootEncoder に結合しているため実機確認で担保する。

## エージェント側の検証手段

コンパイルはできないため、以下で代替する:

```bash
# 括弧バランス (全Kotlinファイル)
python3 -c "
import pathlib
base = pathlib.Path('app/src/main/java/io/github/titagaki/genkaibroadcaster')
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

- 360p / 480p / 720p / 1080p それぞれで縦・横を選択し、プレビューと受信映像の上下・左右が一致することを確認する。
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

### 2026-09-12 の変更に対する実機確認

- 設定画面: メニュー → 各ページ → 接続先の編集 と進み、戻るボタン・端末の戻るで逆順に戻ること。
  各階層で縦横を切り替え、Activity再生成後も同じ階層に留まることを確認する。
- 接続先: 追加 (PeerCast Gateway / Twitch / 空)、名前・URL・キーの編集、切替、削除 (最後の1件は不可) を確認する。
  旧版から更新した端末では、保存済みの `rtmp_server` / `stream_key` が1件目 `PeerCast Gateway` へ移行されることを確認する。
- ビットレート: 数値入力で範囲内の値が即時反映されること、範囲外はエラー表示になりフォーカスを外すと丸められること、
  ∧/∨ が 100 kbps 刻みで動き上下限で無効化されること、配信中の変更が反映されることを確認する。
- フレームレート: 15 / 24 / 30 / 60 それぞれで配信し、受信側のFPSと H.264 Level (720p60→3.2、1080p60→4.2) を確認する。
  60 fps 非対応カメラ (前面など) で映像が出続けることを確認する。
- プロファイル: 受信側で `ffprobe rtmp://...` (または PeerstPlayer の情報表示) を実行し、
  映像が `Constrained Baseline` で B フレームが無いこと、音声が `AAC (LC)` であることを確認する。
  Android の `KEY_PROFILE` はエンコーダへのヒントで、ベンダーによっては無視する
  (実機で profile_idc=66・constraint_set1_flag=0 の素の Baseline を確認済み) ため、端末を変えたら再確認する。
- エンコーダ「互換 (ソフトウェア)」: logcat の `VideoEncoder: Encoder selected c2.android.avc.encoder` を確認し、
  受信側で `constraint_set1_flag=1` (ffprobe で `Constrained Baseline`) になること、
  PCRPlayer + DXVA2 有効で再生できること、720p30 で端末の発熱・コマ落ちが許容範囲であることを確認する。
- 音量メーター: 起動直後のプレビューで反応し、ステータスバーにマイク使用表示が出ること。
  配信開始 → 停止をまたいで途切れないこと。ホームへ戻る・設定画面へ移動でマイク使用表示が消えることを確認する。
- 操作帯: 縦持ちで下に横長、横持ちで右に縦長のスタジアム形で描かれ、プレビューと重ならないこと。
  配信ボタンが帯の中心にあり、横持ちで帯の幅がプレビューを過度に狭めないことを確認する。

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
- 対処 (当時): `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` に変更。
- 現在 (AGP 9 内蔵 Kotlin): `jvmTarget` は `compileOptions.targetCompatibility` に追従するため、
  このブロック自体を削除している。`org.jetbrains.kotlin.android` を適用しないので `kotlin {}` 拡張の扱いも変わる。

### 3. `disableAudio() / enableAudio()` が無い

- 新 GenericStream API (`StreamBase`) には存在しない。旧 API の記憶で書いたのが原因。
- 対処: `MicrophoneSource.mute() / unMute()` を使う。詳細は [RootEncoder統合メモ](rootencoder.md)。

### 4. リファクタ時の参照残り (`StreamPrefs.FILE`)

- 定数集約のリネーム後に `MainActivity` の参照が古いまま残り `Unresolved reference`。
- 教訓: リネーム・集約後は必ず `grep` で旧シンボル名の残存を確認する。

### 5. Gradle 8.13 が JDK 25 で起動できない → Gradle 9 / AGP 9 へ更新 (2026-09-13)

- 症状: Android Studio 2026.1.4 同梱の `jbr` が JDK 25 になり、`gradlew.bat` が
  `What went wrong: 25.0.3` とバージョン番号だけ出して落ちる。当面は JDK 21 を `JAVA_HOME` に指定して回避していた。
- 対処: Gradle 8.13 → 9.2.1、AGP 8.9.2 → 9.0.1 に更新 (別プロジェクト JPNKNVox と同じ組み合わせ)。
  AGP 9 は Kotlin コンパイルを内蔵するため以下を変更した:
  - `org.jetbrains.kotlin.android` の適用をルート・app 両方から削除 (適用したままだと AGP 9 がエラーにする。
    `android.builtInKotlin=false` で旧方式に戻せるが AGP 10 で撤去予定なので使わない)。
  - `kotlin { compilerOptions { jvmTarget } }` を削除 (`targetCompatibility` に追従)。
  - Kotlin の版は `org.jetbrains.kotlin.plugin.compose` の版 (2.3.21) で決まる。
- AGP 9 でデフォルトが変わる主な項目 (このプロジェクトでの影響):
  - `android.onlyEnableUnitTestForTheTestedBuildType=true`: 単体テストは debug のみ生成。`testDebugUnitTest` は従来通り。
  - `android.enableAppCompileTimeRClass=true`: アプリの `R` が非 final になる。`when` の分岐に `R.*` を使っていないので影響なし。
  - `android.sdk.defaultTargetSdkToCompileSdkIfUnset=true`: `targetSdk` は明示しているので影響なし。
- 注意: `gradle-wrapper.jar` と `gradlew*` は 8.13 が生成したものをそのまま使っている (9.2.1 の取得・実行は可能)。
  揃えたい場合は `.\gradlew.bat wrapper --gradle-version 9.2.1` を一度実行してコミットする。
