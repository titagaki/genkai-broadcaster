package com.example.genkaibroadcaster.ui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.genkaibroadcaster.streamer.RESOLUTIONS
import com.example.genkaibroadcaster.streamer.StreamConfig
import com.example.genkaibroadcaster.streamer.StreamController
import com.example.genkaibroadcaster.streamer.StreamPrefs
import com.example.genkaibroadcaster.streamer.ZoomDebugOverride

/**
 * 設定のカテゴリ。トップのメニューから1階層だけ掘る構成にしている。
 * ページを増やすときはここに追加し、[SettingsScreen] の `when` に本体を足す。
 */
private enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    STREAM("配信", "RTMPサーバーとストリームキー", Icons.Filled.CloudUpload),
    VIDEO("映像", "送信する映像の向きと画質", Icons.Filled.Videocam),
    CAMERA("カメラ", "ズーム方式の診断 (デバッグビルド専用)", Icons.Filled.CameraAlt),
    SETUP("権限と接続", "権限の確認と受け側の準備", Icons.Filled.VerifiedUser)
}

/**
 * 設定画面。カテゴリメニュー → 各ページの2階層。変更は端末へ自動保存する。
 *
 * 選択中のページは rememberSaveable で保持し、方向切替による Activity 再生成後も
 * 同じページに留まる。戻る操作はページ → メニュー → 配信画面の順に戻る。
 */
@Composable
fun SettingsScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    portrait: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val goBack: () -> Unit = { if (page != null) page = null else onBack() }
    BackHandler { goBack() }

    var rtmpServer by remember { mutableStateOf(StreamPrefs.loadServer(prefs)) }
    var streamKey by remember { mutableStateOf(StreamPrefs.loadKey(prefs)) }
    var resolutionIndex by remember { mutableIntStateOf(StreamPrefs.loadResIndex(prefs)) }
    var bitrateKbps by remember { mutableIntStateOf(StreamPrefs.loadBitrateKbps(prefs)) }
    var fps by remember { mutableIntStateOf(StreamPrefs.loadFps(prefs)) }
    var showKey by remember { mutableStateOf(false) }
    val streamState by controller.state.collectAsState()
    val isStreaming = streamState.isStreaming
    val debuggable = controller.isDebuggable()

    fun persist() {
        StreamPrefs.save(prefs, rtmpServer, streamKey, resolutionIndex, bitrateKbps, fps)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (page != null) "設定メニューに戻る" else "配信画面に戻る")
                }
                Column(Modifier.weight(1f)) {
                    Text(page?.title ?: "設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(page?.subtitle ?: "変更は自動で保存されます", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    SettingsPage.STREAM -> listOf(
                                        rtmpServer.ifBlank { "サーバー未設定" },
                                        if (streamKey.isBlank()) "キー未設定" else "キー設定済み"
                                    ).joinToString(" / ")
                                    SettingsPage.VIDEO -> listOf(
                                        if (portrait) "縦" else "横",
                                        RESOLUTIONS[resolutionIndex].label,
                                        "$fps fps",
                                        "$bitrateKbps kbps"
                                    ).joinToString(" / ")
                                    else -> target.subtitle
                                }
                            },
                            onSelect = { page = it }
                        )
                        SettingsPage.STREAM -> StreamPage(
                            rtmpServer = rtmpServer, streamKey = streamKey, showKey = showKey,
                            enabled = !isStreaming,
                            onServerChange = { rtmpServer = it.trim(); persist() },
                            onKeyChange = { streamKey = it.trim(); persist() },
                            onToggleShowKey = { showKey = !showKey }
                        )
                        SettingsPage.VIDEO -> VideoPage(
                            portrait = portrait, resolutionIndex = resolutionIndex, fps = fps, bitrateKbps = bitrateKbps,
                            isStreaming = isStreaming,
                            onPortraitChanged = onPortraitChanged,
                            onResolutionChange = { resolutionIndex = it; persist() },
                            onFpsChange = { fps = it; persist() },
                            onBitrateChange = {
                                bitrateKbps = it
                                if (isStreaming) controller.setVideoBitrateKbpsOnFly(bitrateKbps)
                                persist()
                            }
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

// ---- 各ページ ----

@Composable
private fun StreamPage(
    rtmpServer: String,
    streamKey: String,
    showKey: Boolean,
    enabled: Boolean,
    onServerChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onToggleShowKey: () -> Unit
) {
    SettingsCard {
        OutlinedTextField(
            value = rtmpServer,
            onValueChange = onServerChange,
            label = { Text("RTMPサーバーURL") },
            placeholder = { Text(StreamConfig.DEFAULT_SERVER) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = streamKey,
            onValueChange = onKeyChange,
            label = { Text("ストリームキー") },
            supportingText = { Text("配信先で指定されたストリームキー") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowKey) {
                    Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "キーを隠す" else "キーを表示")
                }
            },
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsSection("入力例", "PeerCast向けのプリセット") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onServerChange(StreamConfig.DEFAULT_SERVER) },
                enabled = enabled, modifier = Modifier.weight(1f)
            ) { Text("Gateway", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            OutlinedButton(
                onClick = {
                    onServerChange("rtmp://192.168.1.1/live")
                    if (streamKey.isEmpty()) onKeyChange("livestream")
                },
                enabled = enabled, modifier = Modifier.weight(1f)
            ) { Text("自宅Station例", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun VideoPage(
    portrait: Boolean,
    resolutionIndex: Int,
    fps: Int,
    bitrateKbps: Int,
    isStreaming: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onResolutionChange: (Int) -> Unit,
    onFpsChange: (Int) -> Unit,
    onBitrateChange: (Int) -> Unit
) {
    SettingsSection("向きと解像度", "配信停止中のみ変更できます") {
        OrientationPicker(portrait, !isStreaming, onPortraitChanged)
        Text(
            if (portrait) "スマホを縦に持って配信します。" else "スマホを横に持って配信します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ResolutionDropdown(resolutionIndex, portrait, !isStreaming, onResolutionChange)
    }
    SettingsSection("フレームレート", "60 fps はカメラが対応する端末のみ有効です") {
        FpsSelector(fps, !isStreaming, onFpsChange)
    }
    SettingsSection("ビットレート", "配信中も変更でき、即時反映されます") {
        BitrateStepper(bitrateKbps, onBitrateChange)
        Text("H.264 + AAC / キーフレーム ${StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC}秒",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CameraPage(
    current: ZoomDebugOverride,
    enabled: Boolean,
    diagnostics: String,
    onSelect: (ZoomDebugOverride) -> Unit
) {
    SettingsSection("ズーム方式", "デバッグビルド専用") {
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ZoomDebugOverride.entries.forEach { override ->
                val selected = current == override
                OutlinedButton(
                    onClick = { onSelect(override) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                    Text(
                        when (override) {
                            ZoomDebugOverride.AUTO -> "自動判定"
                            ZoomDebugOverride.FORCE_DIGITAL -> "デジタルを強制"
                            ZoomDebugOverride.FORCE_LOGICAL -> "論理カメラを強制"
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
    SettingsSection("診断", "API・カメラID・倍率範囲") {
        Text(diagnostics, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupPage(onRequestPermissions: () -> Unit) {
    SettingsSection("権限", "カメラとマイクは配信に必要です") {
        OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("カメラ・マイク・通知の権限を確認")
        }
        Text("通知の許可は任意です。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsSection("接続の準備", "配信前に受け側も準備してください") {
        Text("Gateway", fontWeight = FontWeight.SemiBold)
        Text("FLVチャンネルを作成し、発行されたURLと4桁キーを入力します。",
            style = MaterialTheme.typography.bodySmall)
        Text("自宅Station", fontWeight = FontWeight.SemiBold)
        Text("Station側をRTMP受信待ち (SEARCHING) にしてから、このアプリで配信を開始します。",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---- 部品 ----

@Composable
private fun FpsSelector(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val options = StreamConfig.FPS_OPTIONS
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, fps ->
            SegmentedButton(
                selected = fps == selected,
                onClick = { onSelect(fps) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
            ) { Text("$fps") }
        }
    }
}

/**
 * ビットレートの数値入力。右端に `<input type="number">` 風の ∧/∨ ボタンを縦に並べる。
 *
 * 入力中は範囲内の値だけを即時反映し、範囲外はエラー表示に留める。
 * フォーカスが外れた時と IME の完了で範囲内に丸めて確定する。
 */
@Composable
private fun BitrateStepper(value: Int, onValueChange: (Int) -> Unit) {
    val min = StreamConfig.BITRATE_MIN_KBPS
    val max = StreamConfig.BITRATE_MAX_KBPS
    val step = StreamConfig.BITRATE_STEP_KBPS
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val parsed = text.toIntOrNull()
    val inRange = parsed != null && parsed in min..max
    // ボタン操作など外部からの変更は、入力中でなければ表示へ反映する
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }

    fun commitClamped() {
        val clamped = (parsed ?: value).coerceIn(min, max)
        onValueChange(clamped)
        text = clamped.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(5)
            text = digits
            digits.toIntOrNull()?.let { if (it in min..max) onValueChange(it) }
        },
        isError = !inRange,
        suffix = { Text("kbps") },
        trailingIcon = {
            Column {
                IconButton(
                    onClick = { onValueChange((value + step).coerceAtMost(max)) },
                    enabled = value < max,
                    modifier = Modifier.size(width = 40.dp, height = 28.dp)
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "${step} kbps 上げる") }
                IconButton(
                    onClick = { onValueChange((value - step).coerceAtLeast(min)) },
                    enabled = value > min,
                    modifier = Modifier.size(width = 40.dp, height = 28.dp)
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "${step} kbps 下げる") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitClamped(); focusManager.clearFocus() }),
        textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) commitClamped()
            focused = state.isFocused
        }
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionDropdown(selectedIndex: Int, portrait: Boolean, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = RESOLUTIONS[selectedIndex]
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = "${selected.label} / ${selected.dimensions(portrait)}",
            onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("送信解像度") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RESOLUTIONS.forEachIndexed { i, resolution ->
                DropdownMenuItem(
                    text = { Text("${resolution.label} / ${resolution.dimensions(portrait)}") },
                    onClick = { onSelect(i); expanded = false }
                )
            }
        }
    }
}
