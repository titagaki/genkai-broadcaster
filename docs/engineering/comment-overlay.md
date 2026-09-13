# コメント表示 (外部アプリ連携) 設計

- 最終更新: 2026-09-13
- 状態: 設計と I/F 定義 (AIDL) のみ。配信アプリ側の受信・描画、提供側アプリは未実装
- 製品仕様上の位置づけ: [製品仕様](../product/spec.md) §11 の候補2。実装時に §2.1 / §6 / §9 へ移す

## 1. 方針

コメント (PeerCast の掲示板、YouTube チャット等) の**取得は別アプリ (以下「提供側」) に任せ**、
配信アプリは提供側が公開する Bound Service から文字列を受け取って映像に焼き込むだけにする。

理由:

- 配信アプリは「任意の RTMP サーバーへ push する」だけを扱う ([AGENTS.md](../../AGENTS.md))。掲示板や
  チャット API の実装・認証・ネットワーク依存を持ち込むと、対応先ごとの保守が配信アプリに乗る。
- 取得側を差し替えられる。PeerCast 用・YouTube 用・テスト用 (定期的にダミーを流す) を
  それぞれ独立したアプリにできる。
- minSdk 26 のまま実現できる (AIDL / `bindService` / `<queries>` はいずれも追加ライブラリ不要)。

採らない案:

| 案 | 採らない理由 |
|----|-------------|
| 配信アプリ内で直接取得 | 上記の方針に反する。対応先が増えるたびに配信アプリが太る |
| 配信アプリが Service を公開し、提供側が push する | 提供側が配信中かどうかを知る手段が別途要る。接続の主導権は配信中だけ bind する配信アプリ側に置く方が寿命管理が単純 |
| Broadcast Intent で受け渡す | 順序・取りこぼし・バックグラウンド制限 (Android 8+ の暗黙 Broadcast 制限) の扱いが煩雑 |
| ContentProvider をポーリング | 遅延が出る。push 型の AIDL コールバックで足りる |

## 2. 全体構成

```text
提供側アプリ (別 APK)                          配信アプリ (本アプリ)
┌──────────────────────┐   bind (配信中のみ)   ┌──────────────────────────────┐
│ CommentSourceService │◀────────────────────│ comment/CommentSourceClient  │
│  extends Service     │   ICommentSource     │   ServiceConnection          │
│  ICommentSource.Stub │────────────────────▶│   ICommentListener.Stub      │
│  RemoteCallbackList  │   ICommentListener   │        │ CommentEntry        │
│  (掲示板/チャット取得)│   (oneway callback)  │        ▼                     │
└──────────────────────┘                      │ comment/CommentBoard (純粋)  │
                                              │   表示中一覧・期限・行数     │
                                              │        ▼                     │
                                              │ comment/CommentOverlay       │
                                              │   Bitmap 描画 → ImageFilter  │
                                              │        ▼                     │
                                              │ StreamController             │
                                              │   glInterface.setFilter()    │
                                              └──────────────────────────────┘
```

配信アプリ側の新規パッケージ `comment/` (実装時):

| ファイル | 役割 |
|---------|------|
| `CommentEntry.kt` | AIDL で受け渡す 1 件のコメント (Parcelable)。**定義済み** |
| `CommentSources.kt` | `PackageManager.queryIntentServices` で提供側を列挙する。UI の設定ページが選択肢として使う |
| `CommentSourceClient.kt` | `bindService` / `unbindService`、`ICommentListener.Stub`、メインスレッドへの配送、切断時の再 bind |
| `CommentBoard.kt` | 表示中コメントの一覧・期限・最大行数・重複排除 (純粋ロジック、JVM テスト対象) |
| `CommentOverlay.kt` | `CommentBoard` の内容を出力寸法の Bitmap に描き、`ImageFilterRender` へ渡す |

## 3. I/F 契約 (提供側との取り決め)

AIDL ファイルは `app/src/main/aidl/io/github/titagaki/genkaibroadcaster/comment/` に置き、
提供側アプリは**同じパスのファイルをそのままコピー**して使う (パッケージ名も同じにする。
`applicationId` が違っても Java パッケージは同じでよい)。

### 3.1 識別子

| 種類 | 値 |
|------|-----|
| Intent action (Service 検出用) | `io.github.titagaki.genkaibroadcaster.comment.action.COMMENT_SOURCE` |
| 権限 (bind に必要、提供側が宣言) | `io.github.titagaki.genkaibroadcaster.permission.BIND_COMMENT_SOURCE` |
| AIDL パッケージ | `io.github.titagaki.genkaibroadcaster.comment` |
| プロトコル版 | `ICommentSource.VERSION` (現在 1) |

識別子は `applicationId` に依存させない (debug 版は `io.github.titagaki.genkaibroadcaster.debug` になるため)。

### 3.2 インターフェース

`ICommentSource` (提供側が実装、配信アプリが呼ぶ):

| メソッド | 内容 |
|---------|------|
| `int getVersion()` | 実装しているプロトコル版。配信アプリは `ICommentSource.VERSION` と一致しなければ切断し、通知文を出す |
| `String getSourceName()` | 設定画面・状態表示に使う表示名 (例: `PeerCast 掲示板`) |
| `void registerListener(ICommentListener l)` | 以後のコメントをこの listener へ送る。同じ listener の二重登録は無視する |
| `void unregisterListener(ICommentListener l)` | 配信停止時に呼ぶ。配信アプリのプロセスが死んだ場合は提供側が `RemoteCallbackList` の死活検知で外す |

`ICommentListener` (配信アプリが実装、提供側が呼ぶ。すべて `oneway` で提供側をブロックしない):

| メソッド | 内容 |
|---------|------|
| `void onComments(in List<CommentEntry> comments)` | 新着コメント。受信順に並べる。1 回の呼び出しで複数件まとめてよい |
| `void onStateChanged(int state, String detail)` | 提供側の取得状態。`state` は `ICommentSource.STATE_*`、`detail` は表示用の補足 (エラー内容など。空可) |

`CommentEntry` (Parcelable):

| フィールド | 型 | 内容 |
|-----------|----|------|
| `id` | String | 提供側内で一意な ID。配信アプリは重複排除に使う (再 bind 後の重複送信対策) |
| `receivedAtMillis` | Long | 提供側が受信した時刻 (epoch ms)。表示期限の起点には使わず、並べ替えの参考にする |
| `author` | String? | 投稿者名。無ければ null |
| `body` | String | 本文。改行を含んでもよいが、配信アプリは 1 行に潰して表示する |

### 3.3 取り決め

- **配信中だけ bind する。** `startStream` で bind、`stopStream` (自動再接続の失敗確定を含む) で
  `unregisterListener` → `unbindService`。プレビューだけの間は bind しない (提供側の取得が動かない = 通信しない)。
- `registerListener` 後に届いたコメントだけを送る。過去分の再送はしない (bind 直後に古いコメントで画面が埋まるのを避ける)。
- 提供側の取得処理 (ポーリング周期、認証、再接続) は提供側が決める。配信アプリは関与しない。
  提供側が bind 中だけ取得を動かすか常時動かすかも提供側の自由。
- 提供側の Service は `android:exported="true"` + `android:permission` (上記 `BIND_COMMENT_SOURCE`) で保護する。
  権限は提供側が `protectionLevel="signature"` で宣言し、配信アプリは `<uses-permission>` で要求する。
  同じ鍵で署名したアプリ同士だけが繋がる (release は release 鍵、debug は debug 鍵で組を作る)。
- 版の扱い: 非互換な変更 (メソッドの引数変更、`CommentEntry` のフィールド変更) は `VERSION` を上げる。
  `CommentEntry` の Parcel 形式は末尾追加でも旧読み手が壊れるため、フィールド追加も非互換扱い。
- 配信アプリは提供側の停止 (`onServiceDisconnected` / `binderDied`) を受けたら、配信中なら
  数秒間隔で再 bind を試み、通知文 (`StreamController.messages`) で知らせる。
  提供側が居ない・権限が無い・版が違う場合は配信自体は止めず、コメントなしで続行する。

### 3.4 提供側の最小実装 (参考)

`AndroidManifest.xml`:

```xml
<permission
    android:name="io.github.titagaki.genkaibroadcaster.permission.BIND_COMMENT_SOURCE"
    android:protectionLevel="signature" />

<service
    android:name=".CommentSourceService"
    android:exported="true"
    android:permission="io.github.titagaki.genkaibroadcaster.permission.BIND_COMMENT_SOURCE">
    <intent-filter>
        <action android:name="io.github.titagaki.genkaibroadcaster.comment.action.COMMENT_SOURCE" />
    </intent-filter>
</service>
```

Service:

```kotlin
class CommentSourceService : Service() {
    private val listeners = RemoteCallbackList<ICommentListener>()

    private val binder = object : ICommentSource.Stub() {
        override fun getVersion() = ICommentSource.VERSION
        override fun getSourceName() = "PeerCast 掲示板"
        override fun registerListener(l: ICommentListener) { listeners.register(l) }
        override fun unregisterListener(l: ICommentListener) { listeners.unregister(l) }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** 取得側から呼ぶ。RemoteCallbackList が死んだ listener を自動で外す */
    private fun deliver(entries: List<CommentEntry>) {
        val n = listeners.beginBroadcast()
        try {
            for (i in 0 until n) runCatching { listeners.getBroadcastItem(i).onComments(entries) }
        } finally {
            listeners.finishBroadcast()
        }
    }
}
```

提供側の `build.gradle.kts` でも `buildFeatures { aidl = true }` が要る (AGP 8 以降は既定で無効)。

## 4. 配信アプリ側の設計

### 4.1 検出と設定

- `CommentSources.list(context)`: `Intent(ACTION_COMMENT_SOURCE)` で `queryIntentServices` し、
  `ComponentName` + ラベル (Service の `android:label`、無ければアプリ名) の一覧を返す。
  Android 11+ で見えるように `AndroidManifest.xml` に `<queries><intent><action .../></intent></queries>` を宣言する (**宣言済み**)。
- 設定: `StreamPrefs` に `comment_source` (`ComponentName.flattenToString()`、null/空 = コメント表示なし) を追加。
  設定画面に `コメント` ページを足し、`なし` + 検出した提供側をラジオで選ぶ。配信中は変更不可。
  提供側がアンインストールされて一覧に無い保存値は「なし」として扱う。
- 表示位置・文字サイズ・表示秒数・最大行数は v1 では固定 (`StreamConfig` に定数を置く)。設定 UI は作らない。

### 4.2 寿命 (StreamController)

| タイミング | 処理 |
|-----------|------|
| `startStream` | 設定に提供側があれば `CommentSourceClient.bind(component)`。bind 失敗 (不在・権限なし) は通知文のみ |
| `ICommentSource` 接続 | `getVersion()` 確認 → `registerListener`。版不一致は unbind + 通知文 |
| `onComments` | メインスレッドで `CommentBoard.add()` → `CommentOverlay.redraw()` |
| `stopStream` | `unregisterListener` → `unbind`。`CommentBoard.clear()`、オーバーレイを空にする |
| `releaseEngine` | フィルタは GL 側で解放されるので、次のエンジン生成後に再設定する |

`CommentSourceClient` はメインスレッドで扱う。AIDL コールバックは Binder スレッドで届くので、
既存の `onMainThreadHandler` と同じ方法でメインへ移す。エンジン世代 (`generation`) の考え方と同様に、
unbind 後に遅れて届いたコールバックは捨てる (client 側に接続世代を持つ)。

### 4.3 描画 (RootEncoder のフィルタ)

RootEncoder 2.8.1 の実ソースで確認した範囲 (裏取り先は [rootencoder.md](rootencoder.md) と同じベース URL):

- `GlStreamInterface.setFilter(BaseFilterRender)` / `addFilter` / `removeFilter` / `clearFilters`
  (`library/.../view/GlStreamInterface.kt`)。いずれもキューに積まれ、GL スレッドの次の描画で反映される。
- `ImageFilterRender.setImage(Bitmap?)` (`encoder/.../filters/object/ImageFilterRender.kt`)。
  `shouldLoad` を立てるだけで、テクスチャの再生成は GL スレッド側の次の `drawFilter` で行われる。
  旧名 `ImageObjectFilterRender` は deprecated。
- `BaseObjectFilterRender.setPosition(x, y)` / `setScale(sx, sy)` (パーセント単位)。
  既定は全面 (100% × 100%、左上原点) なので、**出力寸法と同じ大きさの透過 Bitmap を描いてそのまま貼る**。
  位置合わせをスプライト側でやらず、Bitmap 上の座標で完結させる。
- フィルタが受け取る寸法は `MainRender.initGl(context, encoderWidth, encoderHeight, ...)` の
  エンコーダ寸法 (縦配信なら 720×1280 に交換済み)。フィルタはプレビューにも配信にも同じ内容が乗る。
- `GlStreamInterface.stop()` → `MainRender.release()` でフィルタ一覧は**空になる**。
  本アプリは配信・プレビュー停止でエンジンを解放するので、`createStream` 後・`prepareVideo` 成功後に
  `setFilter(overlay.filter)` を呼び直し、直後に `redraw()` でテクスチャを入れ直す。
  `setFilter` (SET) は一覧を置き換えるので二重登録にならない (フィルタは本アプリでは 1 つだけ)。

描画方式は **Bitmap を Canvas で描く** (`ImageFilterRender`)。`TextFilterRender` は 1 文字列しか持てず、
`ViewSurfaceFilterRender` (VirtualDisplay に View を描く) は毎フレーム更新向きだが構成要素が多い。
v1 は「新着時と期限切れ時だけ描き直す」静的な一覧表示なので Bitmap 方式で足りる。

- 表示: 左下に最新 N 行 (新しいものが下)。白文字 + 黒縁取り (`Paint.Style.STROKE` を先に描く) で
  背景を問わず読めるようにする。投稿者名は本文の前に薄い色で添える (null なら省略)。
- 文字サイズは出力高さに比例 (例: 高さ / 24 px)。横配信 720p で 30px 前後。長い本文は幅で切り詰めて `…` を付ける。
- 期限: 表示から `COMMENT_DISPLAY_MS` (例 10 秒) で消す。`CommentBoard.nextExpiryAt()` で次の期限に
  1 回だけメインスレッドの遅延実行を予約し、毎フレームのタイマーは持たない。
- Bitmap は描き直すたびに新しく作る (再利用しない)。`setImage` 後に GL スレッドが読む途中で
  Canvas が同じ Bitmap を書き換える競合を避けるため。描き直しは秒単位の頻度なので割り当てコストは問題にならない。
- 表示なし (コメント 0 件) のときも**透過 Bitmap を渡す**。`setImage(null)` は使わない:
  `TextureLoader.load` (`encoder/.../input/gl/TextureLoader.java`) は null の要素に対して
  テクスチャ ID だけ作って `texImage2D` を飛ばすため、内容未定義のテクスチャが alpha 1 で合成される。
- ライブラリは渡した Bitmap を `recycle()` しない (次の `setImage` まで参照を持つだけ)。こちらも recycle しない。

### 4.4 純粋ロジック (`CommentBoard`, JVM テスト)

```kotlin
class CommentBoard(maxLines: Int, displayMillis: Long) {
    fun add(entries: List<CommentEntry>, nowMillis: Long)   // 重複 id は無視、超過分は古い順に落とす
    fun visible(nowMillis: Long): List<CommentEntry>        // 期限内を古い順で返す
    fun nextExpiryAt(nowMillis: Long): Long?                // 次に描き直すべき時刻 (無ければ null)
    fun clear()
}
```

テスト観点: 重複 id、最大行数超過、期限切れの境界、`nextExpiryAt` が最も早い期限を返すこと、空のときの null。

### 4.5 状態と UI

- `StreamState` に `commentSource: CommentSourceState` (`Off` / `Connecting` / `Ready(name)` / `Error(detail)`) を追加し、
  配信画面の情報表示 (F-09 の並び) に 1 行足す。文言は既存の `status` と同じく streamer 側で組み立てる。
- 提供側からの `onStateChanged` は `Ready` / `Error` に写す。`detail` はそのまま通知文には流さず、
  情報表示の行に載せる (通知文の連発を避ける)。

## 5. 実機で確認すること (実装後)

- `<queries>` 宣言だけで debug / release 両方の提供側が列挙されること (Android 11+)。
- 提供側を**後から**インストールしても `BIND_COMMENT_SOURCE` が配信アプリに付与されること
  (署名一致のカスタム権限は定義側が後入れでも再評価されるはずだが未確認)。
- 縦配信でフィルタの Bitmap が出力寸法 (720×1280) に一致し、上下左右が正しいこと。
- 配信中に提供側を強制終了 → 再 bind で復帰すること。配信は途切れないこと。
- ソフトウェアエンコーダ + フィルタ有効時の CPU 負荷 (フィルタは GL 上の合成なのでエンコーダ負荷は変わらない見込み)。

## 6. 将来候補 (v1 では扱わない)

- ニコニコ風の流れるコメント (毎フレーム更新)。`ViewSurfaceFilterRender` か、`CommentOverlay` を
  フレームごとに描き直す方式へ切り替える。
- 表示位置・文字サイズ・表示秒数の設定 UI。
- 提供側への配信状態の通知 (接続先名など)。必要になったら `ICommentSource` にメソッドを足し `VERSION` を上げる。
