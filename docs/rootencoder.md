# RootEncoder 利用メモ (2.8.1)

依存: `com.github.pedroSG94.RootEncoder:library:2.8.1` (JitPack)。
使うのは**新 GenericStream API**。旧 `RtmpCamera2` 系の記憶で書かない。
API名に迷ったら GitHub master の実ソースで裏を取ってから書くこと。

## 実ソースリンク (裏取り済み)

| 内容 | URL |
|------|-----|
| `StreamBase` (`prepareVideo` 等の本体) | `library/.../com/pedro/library/base/StreamBase.kt` |
| 公式サンプル (CameraFragment) | `app/.../com/pedro/streamer/rotation/CameraFragment.kt` |
| `StreamBaseClient` (`reTry` 等) | `library/.../com/pedro/library/util/streamclient/StreamBaseClient.kt` |
| `MicrophoneSource` (`mute/unMute/setAudioEffect`) | `encoder/.../com/pedro/encoder/input/sources/audio/MicrophoneSource.kt` |
| `MicrophoneManager` (内部実装) | `encoder/.../com/pedro/encoder/input/audio/MicrophoneManager.java` |
| `Camera2Source` (カメラ操作) | `encoder/.../com/pedro/encoder/input/sources/video/Camera2Source.kt` |
| `CustomAudioEffect` | `encoder/.../com/pedro/encoder/input/audio/CustomAudioEffect.kt` |
| `AudioCodec` / `VideoCodec` | `common/.../com/pedro/common/AudioCodec.kt`・`VideoCodec.kt` |

(ベース: `https://raw.githubusercontent.com/pedroSG94/RootEncoder/master/`)

## 確認済み API と使い方

- 生成: `GenericStream(context, connectChecker)`。`getGlInterface().autoHandleOrientation = true`、
  `getStreamClient().setReTries(n)`。
- `ConnectChecker`: `com.pedro.common` のものを使う。コールバックは
  `onConnectionStarted/Success/Failed`, `onDisconnect`, `onAuthError/Success`, `onStreamingStats`。
- 映像準備 (名前付き引数で渡す):
  `prepareVideo(width, height, bitrate, fps=30, iFrameInterval=2, rotation=0, profile=-1, level=-1, ...)`
- 音声準備: `prepareAudio(sampleRate, isStereo, bitrate)`。
- コーデック明示: `setVideoCodec(VideoCodec.H264)`, `setAudioCodec(AudioCodec.AAC)`。
  `AudioCodec.AAC` (`audio/mp4a-latm`) = AAC-LC。HE-AAC は別 enum。
- ミュート: `(audioSource as MicrophoneSource).mute() / unMute()`。
  `StreamBase` に `disableAudio()/enableAudio()` は無い。
- カメラ: `(videoSource as Camera2Source)` に対し
  `getCameraFacing()/switchCamera()/openCameraId(id)/getCurrentCameraId()`、
  ライトは `enableLantern()/disableLantern()/isLanternEnabled()`。
- 配信中ビットレート変更: `setVideoBitrateOnFly(bps)`。
- 再接続: `getStreamClient().reTry(delay: Long, reason, backupUrl)`。delay は **Long**。
- 音量観測: `setAudioEffect(object : CustomAudioEffect() { override fun process(p: ByteArray) = p })`。
  加工せず素通しし、PCMピークだけ記録する。本アプリは `streamer/LevelMeterEffect.kt`。
  (ミュート時も `process` は生データで呼ばれるため、UI側でミュート時 0 扱いにする)

## 本アプリの互換設定 (PeerCast/旧プレーヤー向け)

- プロファイル: Constrained Baseline (`AVCProfileConstrainedBaseline`、minSdk 26 のため無条件可)
- Level: `streamer/H264Level.kt` で解像度・FPS から自動選定 (MaxMBPS基準: 720p30→3.1、1080p30→4.0)
- キーフレーム間隔: 2秒 (`iFrameInterval = 2`)
- 音声: AAC-LC、FPS: 30固定

## 禁止事項 (製品方針)

- RootEncoder 内部の FLV / RTMP 処理は変更しない。
- 独自 muxer・独自エンコーダ処理は追加しない。
