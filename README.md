# Hoge Broadcaster

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
- 配信先プリセット: PeerCast Gateway、自宅PeerCastStationの入力例
- 映像方向: 縦 9:16、横 16:9
- 解像度: 480p、720p、1080p
- 映像ビットレート: 800～8000 kbps。配信中も変更可能
- エンコード: H.264 Constrained Baseline、AAC-LC、30 fps

入力した接続先、映像方向、解像度、ビットレートは端末内へ保存されます。

## 使い方

1. 設定画面でRTMPサーバーURLとストリームキーを入力します。
2. 映像方向、解像度、ビットレートを選びます。
3. 受信側を接続待ち状態にします。
4. 配信画面の開始ボタンを押します。

PeerCastStation／PeerCast Gateway固有の準備は
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
