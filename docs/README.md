# ドキュメント

Genkai Broadcasterの文書を目的別に分類しています。
製品の振る舞いを変更する場合は、正本である[製品仕様](product/spec.md)も更新してください。

## Product

| 文書 | 役割 |
|------|------|
| [製品仕様](product/spec.md) | 対応範囲、振る舞い、状態、設定値の正本 |

## Engineering

| 文書 | 役割 |
|------|------|
| [アーキテクチャ](engineering/architecture.md) | 技術スタック、層構成、依存方向のルール |
| [開発環境・ビルド](engineering/development.md) | 検証済みバージョン、ビルド手順、実機確認、トラブル履歴 |
| [RootEncoder統合メモ](engineering/rootencoder.md) | 使用API、確認済みの挙動、実装上の制約 |
| [リファクタリング記録](engineering/refactoring.md) | 技術レビューで見つかった問題と修正内容、実機確認項目 |

## Integrations

| 文書 | 役割 |
|------|------|
| [PeerCast接続ガイド](integrations/peercast.md) | 対応先の一つであるPeerCastStation／PeerCast Gatewayの接続手順 |
| [視聴側プレーヤー互換性](integrations/player-compatibility.md) | ハードウェアエンコード映像が DXVA2 有効の PC プレーヤーで再生できない問題の互換表・原因・対策 |

## UI

| 文書 | 役割 |
|------|------|
| [現在のUI仕様](ui/current.md) | 現在のCompose実装を基にした画面構成と操作 |
| [UI設計](ui/design.md) | UIの設計判断、未決事項、確認項目 |
