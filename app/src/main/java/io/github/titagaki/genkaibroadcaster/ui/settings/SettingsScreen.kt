package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.titagaki.genkaibroadcaster.comment.CommentSources
import io.github.titagaki.genkaibroadcaster.streamer.StreamConfig
import io.github.titagaki.genkaibroadcaster.streamer.StreamController
import io.github.titagaki.genkaibroadcaster.streamer.StreamDestination
import io.github.titagaki.genkaibroadcaster.streamer.StreamPrefs

/**
 * 設定のカテゴリ。トップのメニューから1階層だけ掘る構成にしている。
 * ページを増やすときはここに追加し、[SettingsScreen] の `when` に本体を足す。
 */
private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    STREAM("配信", "RTMPサーバーとストリームキー", Icons.Filled.CloudUpload),
    VIDEO("映像", "送信する映像の向きと画質", Icons.Filled.Videocam),
    COMMENT("コメント", "映像に載せるコメントの取得元", Icons.Filled.ChatBubbleOutline),
    CAMERA("カメラ", "ズーム方式の診断 (デバッグビルド専用)", Icons.Filled.CameraAlt),
    SETUP("権限", "カメラ・マイク・通知の権限を確認", Icons.Filled.VerifiedUser)
}

/**
 * 設定画面。カテゴリメニュー → 各ページの2階層 (配信ページだけ接続先の編集で3階層目)。
 * 変更は端末へ自動保存する。
 *
 * 選択中のページ・編集中の接続先IDは rememberSaveable で保持し、方向切替による
 * Activity 再生成後も同じ場所に留まる。戻る操作は編集 → ページ → メニュー → 配信画面の順に戻る。
 */
@Composable
fun SettingsScreen(
    controller: StreamController,
    prefs: StreamPrefs,
    portrait: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        when {
            editingId != null -> editingId = null
            page != null -> page = null
            else -> onBack()
        }
    }
    BackHandler { goBack() }

    var destinations by remember { mutableStateOf(prefs.loadDestinations()) }
    var activeId by remember { mutableStateOf(prefs.loadActiveDestinationId()) }
    val editing = destinations.firstOrNull { it.id == editingId }
    var resolutionIndex by remember { mutableIntStateOf(prefs.loadResIndex()) }
    var bitrateKbps by remember { mutableIntStateOf(prefs.loadBitrateKbps()) }
    var fps by remember { mutableIntStateOf(prefs.loadFps()) }
    var softwareEncoder by remember { mutableStateOf(prefs.loadSoftwareEncoder()) }
    var commentSourceKey by remember { mutableStateOf(prefs.loadCommentSource()) }
    val appContext = LocalContext.current.applicationContext
    // 提供アプリの列挙は PackageManager 問い合わせなので、設定画面を開いている間は 1 回だけ行う
    val commentSources = remember(appContext) { CommentSources.list(appContext) }
    var showKey by remember { mutableStateOf(false) }
    val streamState by controller.state.collectAsState()
    val isStreaming = streamState.isStreaming
    val debuggable = controller.isDebuggable()

    fun persistVideo() {
        prefs.saveVideo(resolutionIndex, bitrateKbps, fps)
    }

    fun persistDestinations() {
        prefs.saveDestinations(destinations, activeId)
    }

    fun addDestination(destination: StreamDestination) {
        destinations = destinations + destination
        if (activeId == null) activeId = destination.id
        persistDestinations()
        editingId = destination.id
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = when {
                            editing != null -> "接続先一覧に戻る"
                            page != null -> "設定メニューに戻る"
                            else -> "配信画面に戻る"
                        })
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            editing != null -> editing.name.ifBlank { "接続先" }
                            else -> page?.title ?: "設定"
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            editing != null -> "接続先の編集"
                            else -> page?.subtitle ?: "変更は自動で保存されます"
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isStreaming && page != SettingsPage.SETUP) {
                        Text("配信処理中です。ビットレート以外の映像・接続設定は、停止後に変更できます。",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    when (page) {
                        null -> SettingsMenu(
                            debuggable = debuggable,
                            summaryOf = { target ->
                                when (target) {
                                    SettingsPage.STREAM -> {
                                        val active = destinations.firstOrNull { it.id == activeId }
                                        if (active == null) "接続先が未登録です"
                                        else listOf(
                                            active.name.ifBlank { active.server },
                                            if (active.key.isBlank()) "キー未設定" else "キー設定済み"
                                        ).joinToString(" / ")
                                    }
                                    SettingsPage.VIDEO -> listOf(
                                        if (portrait) "縦" else "横",
                                        StreamConfig.RESOLUTIONS[resolutionIndex].label,
                                        "$fps fps",
                                        "$bitrateKbps kbps",
                                        if (softwareEncoder) "互換" else "標準"
                                    ).joinToString(" / ")
                                    SettingsPage.COMMENT ->
                                        commentSources.firstOrNull { it.key == commentSourceKey }?.label ?: "なし"
                                    else -> target.subtitle
                                }
                            },
                            onSelect = { page = it }
                        )
                        SettingsPage.STREAM -> if (editing != null) {
                            DestinationEditor(
                                destination = editing,
                                showKey = showKey,
                                enabled = !isStreaming,
                                canDelete = destinations.size > 1,
                                onChange = { updated ->
                                    destinations = destinations.map { if (it.id == updated.id) updated else it }
                                    persistDestinations()
                                },
                                onToggleShowKey = { showKey = !showKey },
                                onDelete = {
                                    destinations = destinations.filterNot { it.id == editing.id }
                                    if (activeId == editing.id) activeId = destinations.firstOrNull()?.id
                                    persistDestinations()
                                    editingId = null
                                }
                            )
                        } else {
                            DestinationList(
                                destinations = destinations,
                                activeId = activeId,
                                enabled = !isStreaming,
                                onSelect = { activeId = it; persistDestinations() },
                                onEdit = { editingId = it },
                                onAdd = { addDestination(it) }
                            )
                        }
                        SettingsPage.VIDEO -> VideoPage(
                            portrait = portrait, resolutionIndex = resolutionIndex, fps = fps, bitrateKbps = bitrateKbps,
                            softwareEncoder = softwareEncoder,
                            isStreaming = isStreaming,
                            onPortraitChanged = onPortraitChanged,
                            onResolutionChange = { resolutionIndex = it; persistVideo() },
                            onFpsChange = { fps = it; persistVideo() },
                            onSoftwareEncoderChange = {
                                softwareEncoder = it
                                prefs.saveSoftwareEncoder(it)
                            },
                            onBitrateChange = {
                                bitrateKbps = it
                                if (isStreaming) controller.setVideoBitrateKbpsOnFly(bitrateKbps)
                                persistVideo()
                            }
                        )
                        SettingsPage.COMMENT -> CommentPage(
                            sources = commentSources,
                            selectedKey = commentSourceKey,
                            enabled = !isStreaming,
                            onSelect = { commentSourceKey = it; prefs.saveCommentSource(it) }
                        )
                        SettingsPage.CAMERA -> CameraPage(
                            current = streamState.zoom.debugOverride,
                            enabled = !isStreaming,
                            diagnostics = controller.zoomDiagnostics(),
                            onSelect = { controller.setZoomDebugOverride(it) }
                        )
                        SettingsPage.SETUP -> SetupPage(onRequestPermissions)
                    }
                }
            }
        }
    }
}

// ---- メニュー ----

@Composable
private fun SettingsMenu(
    debuggable: Boolean,
    summaryOf: (SettingsPage) -> String,
    onSelect: (SettingsPage) -> Unit
) {
    SettingsPage.entries
        .filter { it != SettingsPage.CAMERA || debuggable }
        .forEach { target ->
            Surface(
                onClick = { onSelect(target) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(target.icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(target.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(summaryOf(target), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
}
