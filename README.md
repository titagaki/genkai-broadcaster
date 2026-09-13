# Genkai Broadcaster

Android端末のカメラとマイクを使う、シンプルなRTMPライブ配信アプリです。
任意のRTMPサーバーURLとストリームキーを指定して、H.264 + AACの映像・音声を送信できます。

PeerCastStationとPeerCast Gatewayでの利用も想定し、入力用プリセットと接続ガイドを用意していますが、
PeerCast専用・特化アプリではありません。

Kotlin、Jetpack Compose、[RootEncoder](https://github.com/pedroSG94/RootEncoder)の
`GenericStream` APIで実装しています。

## 主な機能

- カメラとマイクのRTMPライブ配信
- 全面カメラプレビュー上での配信開始・停止
- 前面／背面カメラと利用可能な倍率の切替
- ピンチズームとマイクミュート
- 入力音量、電池残量、接続状態、配信時間、送信ビットレートの表示
- 切断時の自動再接続
- Foreground Serviceによるバックグラウンド配信と通知からの停止

## 配信設定

- 配信先: RTMPサーバーURL、ストリームキー
- 接続先を複数保存して切り替え。入力例: PeerCast Gateway、Twitch
- 映像方向: 縦 9:16、横 16:9
- 解像度: 360p、480p、720p、1080p
- フレームレート: 15、24、30、60 fps
- 映像ビットレート: 100～10000 kbps。配信中も変更可能
- エンコード: H.264 Constrained Baseline、AAC-LC、キーフレーム2秒

入力した接続先、映像方向、解像度、ビットレートは端末内へ保存されます。

## インストール

Google Play では配布していません。署名済み APK を
[GitHub Releases](https://github.com/titagaki/genkai-broadcaster/releases) に置いています。

1. Android 端末のブラウザで Releases ページを開き、最新版の Assets から `genkai-broadcaster-vX.Y.Z.apk` をダウンロードします。
2. ダウンロード通知またはファイルアプリから APK を開きます。
3. 「この提供元のアプリを許可」を求められたら許可します (ブラウザやファイルアプリごとに初回のみ)。
4. Play プロテクトの警告が出た場合は「詳細」→「インストールする」を選びます。
5. 初回起動時にカメラ、マイク、通知 (Android 13 以降) の権限を許可します。

更新するときは新しい APK を同じ手順でインストールすると上書きされ、保存した接続先や設定は引き継がれます。

## 使い方

1. 設定 > 配信 で接続先を追加し、RTMPサーバーURLとストリームキーを入力します。複数登録して切り替えられます。
2. 設定 > 映像 で映像方向、解像度、フレームレート、ビットレートを選びます。
3. 受信側を接続待ち状態にします。
4. 配信画面の開始ボタンを押します。

PeerCast固有の準備は
[PeerCast接続ガイド](docs/integrations/peercast.md)を参照してください。

## 対応範囲

- Android 8.0（API 26）以上
- RTMP push（`rtmp://`）
- 実機での利用を前提

SRT、RTSP、WHIP、端末内録画には対応していません。

## 開発

1. Android Studioでリポジトリルートを開きます。
2. Gradle Syncを実行します。
3. Java 17を使用して実機向けにビルドします。

検証済みのツールバージョンと詳しい手順は
[開発環境・ビルド](docs/engineering/development.md)を参照してください。

## ドキュメント

- [ドキュメント一覧](docs/README.md)
- [製品仕様](docs/product/spec.md)
- [現在のUI仕様](docs/ui/current.md)
- [アーキテクチャ](docs/engineering/architecture.md)
- [RootEncoder統合メモ](docs/engineering/rootencoder.md)
- [PeerCast接続ガイド](docs/integrations/peercast.md)
- [視聴側プレーヤー互換性](docs/integrations/player-compatibility.md)

## ライセンス

このアプリは [MIT License](LICENSE) で公開しています。

使用しているオープンソースライブラリ (RootEncoder、Kotlin、AndroidX、Jetpack Compose) は
いずれも Apache License 2.0 で配布されています。一覧とライセンス全文は
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。
