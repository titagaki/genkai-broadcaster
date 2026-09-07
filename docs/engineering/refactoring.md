# リファクタリング候補

- 作成日: 2026-09-07
- 対象: 縦横配信・UI見直し後の作業ツリー (未コミット変更を含む)
- 状態: 2026-09-07時点のレビュー記録。現在の実装状況は各項目へ着手する前に再確認する。
- 確認方法: コードの静的調査。ビルド・実機検証は未実施。

大きなクラス分割より、状態の二重管理と準備・停止処理の重複を減らすことを優先する。
振る舞いの正本は [製品仕様](../product/spec.md)。不具合修正や仕様変更は、構造だけの整理と分けて扱う。

## 優先順位

| 順位 | 候補 | 優先度 | 規模 | 主な効果 |
|------|------|--------|------|----------|
| 1 | カメラ表示状態をControllerへ一本化 | 高 | 小〜中 | 同期漏れ・重複の削減 |
| 2 | プレビューの準備・開始・停止を整理 | 高 | 小〜中 | ライフサイクル処理の見通し改善 |
| 3 | 準備設定の型化と単位変換の集約 | 中 | 小 | 比較条件・単位の取り違え防止 |
| 4 | URL検証ルールの共通化 | 中 | 小 | 検証変更時の修正漏れ防止 |
| 5 | 高頻度の表示更新を局所化 | 低〜中 | 小 | 画面構成と更新処理の分離 |

## 1. カメラ表示状態をControllerへ一本化

対象: [StreamScreen.kt](../../app/src/main/java/com/example/hogebroadcaster/ui/StreamScreen.kt) の
`isFront` / `lensId` / ズーム倍率とカメラ操作コールバック、
[StreamController.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamController.kt) の
`selectCamera()` / `openLens()` / `setZoomRatio()`。

### 現状

- ControllerとUIの両方にカメラ状態がある。
- 前面選択・背面選択・レンズ選択・プレビュー復帰のたびに、UIが同じ状態を取り直している。
- 操作追加時や切替途中の失敗時に、実機と表示の同期を忘れやすい。

### 進め方

- 既存の `StreamState` に表示用のカメラ状態を追加し、UIは購読だけにする。
- Controller内に実状態を取得・通知するprivate関数を置き、切替・復元・失敗後に呼ぶ。
- 復元用の「選択していた値」と、実機の「現在値」は区別する。保存値をそのまま実状態として表示しない。
- 専用の `CameraController` は新設せず、まず現在のController内で整理する。

確認: 前後・レンズ切替、切替失敗、設定往復、Activity再生成後の表示と実状態の一致。

## 2. プレビュー処理を一方向にする

対象: [StreamController.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamController.kt) の
`attachSurface()` / `detachSurface()` / `startPreviewIfReady()` / `prepareFromPrefs()`。

### 現状

- `surfaceDestroyed()` と `detachSurface()` に、プレビュー状態の解除・停止・非配信時のエンジン解放が重複する。
- `startPreviewIfReady()` → `prepareFromPrefs()` → `startPreviewIfReady()` という呼び戻しがある。
  準備済みキーで収束するが、再開条件を追いにくい。

### 進め方

- Surfaceの登録・コールバック解除は各処理に残し、共通の停止・必要時解放だけprivate関数へまとめる。
- 「設定からエンコーダを準備する」と「準備済みエンコーダでプレビューを開始する」を分離する。
- 再開の判断は呼び出し側で行い、準備関数から開始関数へ呼び戻さない。
- 古い接続通知やService終了通知を除外する世代チェックは維持する。
  配信中のSurface再接続だけでカメラを開き直さないことも維持する。

確認: 縦横切替、設定往復、バックグラウンド復帰、通知停止、再接続断念、停止直後の再開始。
RootEncoderの非同期停止については [rootencoder.md](rootencoder.md) を参照する。

## 3. 準備設定の型化と単位変換の集約

対象: [StreamController.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamController.kt) の
`prepareFromPrefs()` / `setVideoBitrateOnFly()`、
[StreamConfig.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamConfig.kt) の `preparedKey()`、
[SettingsScreen.kt](../../app/src/main/java/com/example/hogebroadcaster/ui/SettingsScreen.kt) のビットレート変更。

### 現状

- 準備条件は複数の変数で組み立て、同一性を連結文字列 `preparedKey` で表している。
- 設定項目を増やすと、準備引数と比較キーの片方だけ更新する余地がある。
- kbpsからbpsへの変換がControllerとUIに分散している。

### 進め方

- 幅・高さ・ビットレート・回転を持つ小さな `data class` を作り、その値自体を準備済み条件と比較する。
- UI向けのビットレート変更APIは名前も含めてkbpsに揃え、bps変換はController内で行う。
- SharedPreferencesの保存形式・既存値・移行処理は変更しない。

確認: 同一設定では不要な再準備をしないこと、方向だけの変更でも再準備すること、配信中のビットレート変更値。

## 4. URL検証ルールの共通化

対象: [StreamScreen.kt](../../app/src/main/java/com/example/hogebroadcaster/ui/StreamScreen.kt) の開始操作、
[StreamController.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamController.kt) の `startStream()`、
[StreamPrefs.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/StreamPrefs.kt) の `buildFullUrl()` 付近。

### 現状と進め方

- `startsWith("rtmp://")` がUIとControllerに重複している。
- URL組み立ての近くに副作用のない検証関数を1つ置き、両方から利用する。
- Controller側の防御的チェックは残し、画面内エラー表示とToastの違いも維持する。
- ホスト名の検証など、受け付けるURLを変える強化は別の振る舞い変更として扱う。

確認: 正常URL・不正スキーム・空欄・キー空欄・旧設定移行後の開始可否とエラー表示が変わらないこと。

## 5. 高頻度の表示更新を局所化

対象: [StreamScreen.kt](../../app/src/main/java/com/example/hogebroadcaster/ui/StreamScreen.kt) の
音量・経過時間・電池情報の状態と `LaunchedEffect`。

### 現状と進め方

- 100msごとの音量、1秒ごとの経過時間、30秒ごとの電池情報を画面最上位で管理している。
- まず音量の取得・減衰・表示を、privateなメーター用Composableへまとめる。
- 必要なら経過時間と電池情報もヘッダー側へ寄せる。表示関数だけでなく更新状態も移す。
- 実際の性能問題は未確認。改善を断定せず、汎用タイマー管理クラスや監視Serviceは追加しない。

確認: ミュート時の即時消灯、画面離脱時の更新停止、復帰時の経過時間維持。開始時刻は引き続きControllerで保持する。

## 別件の不具合修正候補

[CameraLenses.kt](../../app/src/main/java/com/example/hogebroadcaster/streamer/CameraLenses.kt) の
`distinctBy { it.label }` は、前面・背面の丸め後の画角ラベルが同じ場合に一方を一覧から落とす可能性がある。

- 重複判定に少なくとも `isFront` を含める修正を検討する。
- 選択肢が変わるため、上記リファクタリングとは別の不具合修正として扱う。
- 対象端末での再現は未確認。

## 今は行わない整理

- `MainActivity` の専用Manager化。現在の生成・表示・方向指定・権限要求の範囲で十分。
- 2画面だけのため、Navigation導入は見送る。
- DI・Repository・DataStoreの導入や、Controllerを多数のManagerへ分割する変更は今回の目的には過剰。
- ファイルが長いという理由だけで、画面固有のprivate Composableを共通部品へ移さない。

## 着手順と検証

1. カメラ表示状態の一本化。
2. プレビュー処理の整理。
3. 準備設定の型化・単位変換の集約。
4. URL検証の共通化。
5. 必要に応じて表示更新の局所化。

候補ごとに小さな差分で進め、振る舞い変更を混ぜない。
エージェント側の括弧チェック・参照残り確認だけでは型や実機動作を保証できない。
各段階でWindows側Android Studioによるビルドと、[development.md](development.md) の実機確認を行う。
