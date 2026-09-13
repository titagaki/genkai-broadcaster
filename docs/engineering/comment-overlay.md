# コメント表示 (外部アプリ連携) 設計

- 最終更新: 2026-09-13
- 状態: 配信アプリ側 (受信・描画・設定) は実装済み。提供側は JPNKN Vox (`AndroidStudioProjects/JPNKNVox`、
  `docs/spec/comment-export-spec.md`) に実装済み。両者をつないだ実機確認は未 (§5)
- 製品仕様上の位置づけ: [製品仕様](../product/spec.md) F-18

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

配信アプリ側のパッケージ `comment/`:

| ファイル | 役割 |
|---------|------|
| `CommentEntry.kt` | AIDL で受け渡す 1 件のコメント (Parcelable 手書き) |
| `CommentSources.kt` | `PackageManager.queryIntentServices` で提供側を列挙する。UI の設定ページが選択肢として使う |
| `CommentSourceClient.kt` | `bindService` / `unbindService`、`ICommentListener.Stub`、メインスレッドへの配送、世代管理 |
| `CommentSourceState.kt` | UI に見せる接続状態 (`StreamState.commentSource`) |
| `CommentPosition.kt` | 表示位置 (上/下) の enum と保存値 |
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
- 提供側の Service は `android:exported="true"` で公開し、**権限では守らない**。
  当初は提供側が `signature` 権限を宣言し本アプリが `uses-permission` する想定だったが、
  本アプリと JPNKN Vox は署名鍵が別なので `signature` は使えず (鍵を揃えると既存インストールの更新ができなくなる)、
  `normal` の自前権限は定義側 (提供側) が本アプリより後にインストールされると付与されず
  `SecurityException: Not allowed to bind to service` になった (2026-09-13 実機で確認)。
  渡すのは公開掲示板・チャットのコメントだけで、提供側への書き込みの口は無いので保護なしで足りると判断した。
  守りたい提供側は自分で `Binder.getCallingUid()` から呼び出し元を検査すればよい。
- 版の扱い: 非互換な変更 (メソッドの引数変更、`CommentEntry` のフィールド変更) は `VERSION` を上げる。
  `CommentEntry` の Parcel 形式は末尾追加でも旧読み手が壊れるため、フィールド追加も非互換扱い。
- 配信アプリは提供側の停止 (`onServiceDisconnected` / `binderDied`) を受けたら、配信中なら
  数秒間隔で再 bind を試み、通知文 (`StreamController.messages`) で知らせる。
  提供側が居ない・版が違う場合は配信自体は止めず、コメントなしで続行する。

### 3.4 提供側の最小実装 (参考)

`AndroidManifest.xml`:

```xml
<service
    android:name=".CommentSourceService"
    android:exported="true">
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
  Android 11+ で見えるように `AndroidManifest.xml` に `<queries><intent><action .../></intent></queries>` を宣言している。
- 設定: `StreamPrefs.comment_source` (`ComponentName.flattenToString()`、null/空 = コメント表示なし)。
  設定画面の `コメント` ページで `なし` + 検出した提供側をラジオで選ぶ。配信中は変更不可。
  提供側がアンインストールされて一覧に無い保存値は「なし」として表示し、配信開始時は「提供アプリが見つかりません」を出す。
- 表示位置は `StreamPrefs.comment_position` (`bottom` / `top`、既定 bottom) で上下だけ選べる。配信中も
  `StreamController.setCommentPosition()` で即時反映する (描き直すだけで GL には触らない)。
  文字サイズ・表示秒数・最大行数は固定 (`StreamConfig` に定数を置く)。

### 4.2 寿命 (StreamController)

| タイミング | 処理 |
|-----------|------|
| `startStream` (RTMP 開始要求の後) | 設定に提供側があれば `CommentSourceClient.bind()`。不在・bind 失敗は `commentSource = Error` と通知文だけで配信は続ける |
| `onServiceConnected` | `getVersion()` 確認 → `registerListener` → `Ready(name)`。版不一致は unbind + `Error` |
| `onServiceDisconnected` | 提供側プロセスが落ちた。bind は残り OS が再起動するので `Connecting` にして待つ (再 bind しない) |
| `onBindingDied` | 提供側の更新・削除。`COMMENT_REBIND_DELAY_MS` 後に bind し直す |
| `onComments` | メインスレッドで `CommentOverlay.add()` → `CommentBoard.add()` → 描き直し |
| `stopStream` | `unregisterListener` → `unbind`、`CommentOverlay.clear()`、`commentSource = Off` |
| `prepareVideo` 成功 | `CommentOverlay.setOutputSize()` + 新しいフィルタを `setFilter` (§4.3) |
| `releaseEngine` | `CommentOverlay.detachFilter()` (期限タイマーも止める) |

`CommentSourceClient` はメインスレッドで扱う。AIDL コールバックは Binder スレッドで届くので `Handler(mainLooper)` で
メインへ移す。エンジン世代 (`generation`) と同様に client 側で接続世代 (`session`) を持ち、unbind 後に遅れて
届いたコールバックは捨てる。

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
- `GlStreamInterface.stop()` → `MainRender.release()` でフィルタ一覧は**空になる**。GL が止まるのは
  エンジン解放時のほか、設定変更で `prepareFromPrefs` が `stopPreview` する時。どちらも `prepareVideo` 成功が
  続くので、そこで**新しい** `ImageFilterRender` を作って `setFilter` する (`attachCommentOverlay`)。
  同じインスタンスを使い回さないのは、SET が既存フィルタの `release()` → 同じ位置へ `initGl()` をするため
  (解放済みインスタンスの再初期化に頼らない)。`setFilter` (SET) は一覧を置き換えるので二重登録にならない。

描画方式は **Bitmap を Canvas で描く** (`ImageFilterRender`)。`TextFilterRender` は 1 文字列しか持てず、
`ViewSurfaceFilterRender` (VirtualDisplay に View を描く) は毎フレーム更新向きだが構成要素が多い。
v1 は「新着時と期限切れ時だけ描き直す」静的な一覧表示なので Bitmap 方式で足りる。

- 表示: **右揃え**で最新 `COMMENT_MAX_LINES` (5) 行。上下は `CommentPosition` で選び、どちらでも新しいものが下
  (下寄せは下端から古い方へ、上寄せは上端から新しい方へ描く)。白文字 + 黒縁取り
  (`Paint.Style.STROKE` を先に描く) で背景を問わず読めるようにする。投稿者名は本文の前に薄い黄で添える
  (null なら省略。幅の 4 割で省略)。左寄せにしないのは、プレビュー上の音量メーター (左下) と重なって両方読めなくなるため。
- 文字サイズは出力の**短辺** × `COMMENT_TEXT_SIZE_RATIO` (1/24)。720p なら縦横どちらも 30px。
  高さ基準にしていた当初は縦配信 (720×1280) で 53px になり、幅 720 に十数文字しか入らず読めなかった (2026-09-13 実機)。
  長い本文は `TextUtils.ellipsize` で幅に収め、改行は空白に潰す。
- 期限: 表示から `COMMENT_DISPLAY_MS` (10 秒) で消す。`CommentBoard.nextExpiryAt()` で次の期限に
  1 回だけメインスレッドの遅延実行を予約し、毎フレームのタイマーは持たない。
- Bitmap は描き直すたびに新しく作る (再利用しない)。`setImage` 後に GL スレッドが読む途中で
  Canvas が同じ Bitmap を書き換える競合を避けるため。描き直しは秒単位の頻度なので割り当てコストは問題にならない。
- 表示なし (コメント 0 件) のときは **1x1 の透過 Bitmap を渡す** (スプライトが全面に引き伸ばすので透明のまま)。
  `setImage(null)` は使わない: `TextureLoader.load` (`encoder/.../input/gl/TextureLoader.java`) は null の要素に対して
  テクスチャ ID だけ作って `texImage2D` を飛ばすため、内容未定義のテクスチャが alpha 1 で合成される。
- ライブラリは渡した Bitmap を `recycle()` しない (次の `setImage` まで参照を持つだけ)。こちらも recycle しない。

### 4.4 純粋ロジック (`CommentBoard`, JVM テスト)

```kotlin
class CommentBoard(maxLines: Int, displayMillis: Long) {
    fun add(entries: List<CommentEntry>, nowMillis: Long)   // 重複 id は無視、超過分は古い順に落とす
    fun visible(nowMillis: Long): List<CommentEntry>        // 期限内を古い順で返す
    fun nextExpiryAt(nowMillis: Long): Long?                // 次に描き直すべき時刻 (無ければ null)
    fun clear()                                             // 表示だけ消す。受け付け済み id は保持
}
```

テストは `app/src/test/.../comment/CommentBoardTest.kt`: 重複 id、最大行数超過、期限切れの境界 (期限ちょうどで消える)、
`nextExpiryAt` が最も早い期限を返すこと、空のときの null、`clear` 後に同じ id を再表示しないこと。
受け付け済み id は最大 500 件まで覚え、古い方から忘れる。

### 4.5 状態と UI

- `StreamState.commentSource: CommentSourceState` (`Off` / `Connecting(name)` / `Ready(name)` / `Error(message)`)。
  配信画面の情報表示の 3 行目に `text` を出す (`Off` は空文字で行を出さない)。文言は `CommentSourceState.text` で組み立てる。
- 提供側からの `onStateChanged` は `READY` → `Ready`、`ERROR` → `Error(detail)`、それ以外 → `Connecting` に写す。
  `detail` は通知文 (Toast) には流さず、情報表示の行に載せる (通知文の連発を避ける)。

## 5. 実機で確認すること (実装後)

- `<queries>` 宣言だけで debug / release 両方の提供側が列挙されること (Android 11+)。→ 2026-09-13 debug 同士で確認済み。
- 縦配信でフィルタの Bitmap が出力寸法 (720×1280) に一致し、上下左右が正しいこと。
- 配信中に提供側を強制終了 → 再 bind で復帰すること。配信は途切れないこと。
- ソフトウェアエンコーダ + フィルタ有効時の CPU 負荷 (フィルタは GL 上の合成なのでエンコーダ負荷は変わらない見込み)。

## 6. 将来候補 (v1 では扱わない)

- ニコニコ風の流れるコメント (毎フレーム更新)。`ViewSurfaceFilterRender` か、`CommentOverlay` を
  フレームごとに描き直す方式へ切り替える。
- 表示位置・文字サイズ・表示秒数の設定 UI。
- 提供側への配信状態の通知 (接続先名など)。必要になったら `ICommentSource` にメソッドを足し `VERSION` を上げる。
