# RootEncoder 利用メモ (2.8.1)

依存: `com.github.pedroSG94.RootEncoder:library:2.8.1` (JitPack)。
使うのは**新 GenericStream API**。旧 `RtmpCamera2` 系の記憶で書かない。
API名に迷ったら利用中のタグ `2.8.1` の実ソースで裏を取ってから書くこと。

## 実ソースリンク (裏取り済み)

| 内容 | URL |
|------|-----|
| `StreamBase` (`prepareVideo` 等の本体) | `library/.../com/pedro/library/base/StreamBase.kt` |
| `GlStreamInterface` (自動回転・プレビュー比率) | `library/.../com/pedro/library/view/GlStreamInterface.kt` |
| 公式サンプル (CameraFragment) | `app/.../com/pedro/streamer/rotation/CameraFragment.kt` |
| `StreamBaseClient` (`reTry` 等) | `library/.../com/pedro/library/util/streamclient/StreamBaseClient.kt` |
| `MicrophoneSource` (`mute/unMute/setAudioEffect`) | `encoder/.../com/pedro/encoder/input/sources/audio/MicrophoneSource.kt` |
| `MicrophoneManager` (内部実装) | `encoder/.../com/pedro/encoder/input/audio/MicrophoneManager.java` |
| `Camera2Source` (カメラ操作) | `encoder/.../com/pedro/encoder/input/sources/video/Camera2Source.kt` |
| `CustomAudioEffect` | `encoder/.../com/pedro/encoder/input/audio/CustomAudioEffect.kt` |
| `AudioCodec` / `VideoCodec` | `common/.../com/pedro/common/AudioCodec.kt`・`VideoCodec.kt` |

(ベース: `https://raw.githubusercontent.com/pedroSG94/RootEncoder/2.8.1/`)

## 確認済み API と使い方

- 生成: `GenericStream(context, connectChecker)`。`getGlInterface().autoHandleOrientation = true`、
  `getStreamClient().setReTries(n)`。
- `ConnectChecker`: `com.pedro.common` のものを使う。コールバックは
  `onConnectionStarted/Success/Failed`, `onDisconnect`, `onAuthError/Success`, `onStreamingStats`。
- 映像準備 (名前付き引数で渡す):
  `prepareVideo(width, height, bitrate, fps=30, iFrameInterval=2, rotation=0, profile=-1, level=-1, ...)`
- 縦横出力: 横長の入力寸法 (例1280x720) はそのまま使い、横は `rotation=0`、縦は `rotation=90`。
  `StreamBase` が縦の場合にGLのエンコード寸法を720x1280へ交換し、エンコーダにもrotationを渡す。
  2.8.1の公式 `rotation/CameraFragment.setOrientationMode()` と同じ方式。配信・プレビュー停止後に準備する。
- `getGlInterface().setAspectRatioMode(AspectRatioMode.Adjust)` (`com.pedro.encoder.utils.gl`) はプレビューの比率維持表示。
  本アプリではSurfaceを画面全体に置き、映像フレームはSurface内で比率を維持する。
  `autoHandleOrientation=true` は端末の持ち方を補正するが、
  `prepareVideo` で決めたエンコード寸法は変えない。
- 音声準備: `prepareAudio(sampleRate, isStereo, bitrate)`。
- コーデック明示: `setVideoCodec(VideoCodec.H264)`, `setAudioCodec(AudioCodec.AAC)`。
  `AudioCodec.AAC` (`audio/mp4a-latm`) = AAC-LC。HE-AAC は別 enum。
- ミュート: `(audioSource as MicrophoneSource).mute() / unMute()`。
  `StreamBase` に `disableAudio()/enableAudio()` は無い。
- カメラ: `(videoSource as Camera2Source)` に対し
  `getCameraFacing()/switchCamera()/openCameraId(id)/getCurrentCameraId()`。
- ズーム: `setZoom(level: Float)`、`getZoomRange()`、`getZoom()`。
  Android 11以降ではライブラリ内部で`CONTROL_ZOOM_RATIO`、それ以前は`SCALER_CROP_REGION`を使用する。
  UIの倍率は標準画角を`1x`とした値に正規化し、物理レンズ固定時だけ画角から求めた基準倍率で割って渡す。
  論理マルチカメラの判定はCamera2の`REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`と
  `physicalCameraIds`を使い、対応する論理背面カメラを開いて連続倍率を渡す。
- 物理レンズ固定: `physicalCamerasAvailable()`、`openPhysicalCamera(id)`。自動へ戻す場合は
  `openPhysicalCamera(null)`。論理親IDと物理IDは別々に保持し、Camera2のopaque IDを連結して解釈しない。
- `openCameraId()`と`openPhysicalCamera()`はCaptureSession構成完了を待たない。
  `setCustomOnCaptureCompletedCallback()`で新しいSessionの最初のCapture完了を検出する。
  固定解除・親変更・物理固定は1 Sessionずつ完了を待って進め、最終Sessionで選択確定と保存倍率の再適用を行う。
- 配信中ビットレート変更: `setVideoBitrateOnFly(bps)`。
- 再接続: `getStreamClient().reTry(delay: Long, reason, backupUrl)`。delay は **Long**。
- `reTry()` の `false` は配信本体の停止を意味しない。失敗確定時は `StreamBase.stopStream()` を明示的に呼ぶ。
- `stopStream(): Boolean` の戻り値はエンコーダ再準備の成否であり、RTMP切断完了ではない。
  RTMPの通常切断は非同期で `onDisconnect()` が後着する。再接続用の `disconnect(false)` では同通知は発生しない。
  本アプリは停止後にエンジンを解放し、インスタンス世代で古い通知を無視する。
- `stopPreview()` は配信中の映像ソースを停止しない。Surfaceの再接続だけならカメラを開き直す必要はない。
- `Camera2Source.openCameraId()` は同一IDでもカメラを再オープンし、ソース自身の前後フラグは更新しない。
  ID指定時も `switchCamera()` で前後を合わせ、同一IDの不要な再オープンは避ける。
- 音量観測: `setAudioEffect(object : CustomAudioEffect() { override fun process(p: ByteArray) = p })`。
  加工せず素通しし、PCMピークだけ記録する。本アプリは `streamer/LevelMeterEffect.kt`。
  (ミュート時も `process` は生データで呼ばれるため、ControllerとUIでミュート時 0 扱いにする)

## 本アプリの互換設定

- プロファイル: Constrained Baseline (`AVCProfileConstrainedBaseline`、minSdk 26 のため無条件可)
- Level: `streamer/H264Level.kt` で解像度・FPS から自動選定 (MaxMBPS基準: 720p30→3.1、1080p30→4.0)
- キーフレーム間隔: 2秒 (`iFrameInterval = 2`)
- 音声: AAC-LC、FPS: 15/24/30/60 から選択 (既定 30、`StreamPrefs.loadFps`)

## 禁止事項 (製品方針)

- RootEncoder 内部の FLV / RTMP 処理は変更しない。
- 独自 muxer・独自エンコーダ処理は追加しない。
