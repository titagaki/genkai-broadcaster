# Hoge Broadcaster — PeerCast向け Android RTMP配信アプリ

Kotlin + Jetpack Compose + [RootEncoder](https://github.com/pedroSG94/RootEncoder) (GenericStream)。
スマホのカメラ映像を **RTMP push (H.264+AAC / FLV互換)** で送る、PeerCast配信用の最小構成。

PeerCastStation・PeerCast Gateway ともに受けは RTMP なので、そのまま使えます。
SRT は本アプリの対象外です。

## 機能 (v0.1.0)
- カメラプレビュー (縦画面)
- RTMP配信 開始/停止 (サーバーURL + ストリームキーの2欄式)
- プリセット: PeerCast Gateway (`rtmp://pcgw.pgw.jp/live`) / 自宅PeerCastStation例
- 解像度選択: 480p / 720p / 1080p
- ビットレートスライダー: 800〜8000 kbps (配信中も on-the-fly 変更可)
- 前後カメラ切替、マイクミュート
- LIVEバッジ + 経過時間 + リアルタイム送信ビットレート表示
- 切断時の自動リトライ (5秒間隔・最大10回)
- ForegroundService によるバックグラウンド継続 + 通知からの停止
- 設定の端末内保存

## 使い方

### PeerCast Gateway (ポート開放不要)
1. ブラウザで Gateway にログインし、ストリームタイプ **FLV** でチャンネル作成
2. 表示された **RTMPサーバーURL** (`rtmp://pcgw.pgw.jp/live`) と **4桁キー** をアプリに入力
   (URLは「Gateway」プリセットボタン一発)
3. アプリで「配信を開始」→ Gateway 側が Receiving になればOK

### 自宅 PeerCastStation
1. Station側で「配信」→ ソース **RTMP Source**、URLは `rtmp://localhost/live/livestream` のまま等、チャンネル名を入れて配信開始 (状態 SEARCHING)
2. アプリ側サーバーURLに `rtmp://自宅のIPまたはホスト/live`、キーはStation側URLの末尾 (例 `livestream`) を入力
3. アプリで「配信を開始」→ Station側が RECEIVING になればOK

## 開き方
1. Android Studio でこのフォルダを開く
2. Gradle sync (初回は RootEncoder 2.8.1 を JitPack から取得)
3. 実機で実行 (エミュレータではカメラ配信不可の場合あり)

## 構成
- `app/src/main/java/com/example/hogebroadcaster/MainActivity.kt` — GenericStream保持 + Compose UI
- `app/src/main/java/com/example/hogebroadcaster/StreamService.kt` — foreground keep-alive
- `app/src/main/AndroidManifest.xml` — 権限 + service定義
- 依存: `com.github.pedroSG94.RootEncoder:library:2.8.1`, Compose BOM 2025.01.00

## 既知の注意
- 一部の古いPCプレーヤー (PCRPlayerのデフォルト設定等) でスマホエンコード映像が再生できない場合があります。
  視聴側は VLC / MPV / PeerstPlayer 推奨 (Gateway公式ヘルプより)。

## 今後の候補
- [ ] ビットレート自動調整
- [ ] 録画 (MP4保存)
- [ ] したらば等のコメント表示

## 注意
- minSdk 26 / targetSdk 34 / compileSdk 36
- Java 17 でビルドすること
