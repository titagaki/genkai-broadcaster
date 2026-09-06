# AGENTS.md — hoge-broadcaster

PeerCast向け Android RTMP配信アプリ (Kotlin + Compose + RootEncoder)。ユーザーとは日本語で会話する。

## ビルド・検証

- 実ビルドはユーザーが手元の Android Studio (Windows) で行う。WSL側に java/gradle は無い。
- エージェント側の検証は `python3` の括弧チェックと `grep` の参照残りチェックのみ。
- 環境・バージョン・手順・トラブル履歴は `docs/development.md`。

## バージョン互換

- KGP は **2.3.x 必須**。2.1.0 では K2 コンパイラが内部エラーで落ちる (経緯は `docs/development.md` 参照)。
- KGP 2.3 で `kotlinOptions` は廃止。`kotlin { compilerOptions { jvmTarget.set(...) } }` を使う。
- KGP/AGP の組み合わせ変更時は公式互換表で確認する。

## RootEncoder (最重要)

- 新 GenericStream API (`StreamBase`/`GenericStream`) を使う。旧 `RtmpCamera2` 系の記憶で書かない。
- API名は記憶で書かず、実ソースで裏を取る。確認済み一覧・実ソースリンクは `docs/rootencoder.md`。
- FLV/RTMP内部処理・独自muxer・独自エンコーダは追加禁止。

## アーキテクチャ・層ルール

```
MainActivity (生成・権限・表示のみ、ロジック禁止)
streamer/ : StreamController / StreamConfig (定数) / StreamPrefs (設定キー)
system/   : 端末情報 (BatteryMonitor等)
ui/       : AppRoot / StreamScreen / SettingsScreen / components
```

- UI層 → streamer層は `StreamController`/`StreamPrefs`/`StreamConfig` 経由のみ。
- 定数は `StreamConfig` に、設定キー文字列は `StreamPrefs` に集約。

## ドキュメント

- 仕様の正本は `docs/spec.md`。振る舞い変更時は spec も更新する。
- 詳細は `docs/` に分離: `development.md` (環境・ビルド) / `rootencoder.md` (API) / `peercast.md` (調査)。
- 製品方針: PeerCast 用 RTMP push のみ。**SRT は対象外**、録画保存は不要。コメント表示は将来 BBS.JPNKN 向け。

## 運用

- コミット/push は明示的に頼まれた時のみ。`.kotlin/` `.gradle/` `build/` `local.properties` はコミット禁止。
