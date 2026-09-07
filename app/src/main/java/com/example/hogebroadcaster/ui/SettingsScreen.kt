package com.example.hogebroadcaster.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hogebroadcaster.streamer.RESOLUTIONS
import com.example.hogebroadcaster.streamer.StreamConfig
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs
import com.example.hogebroadcaster.streamer.ZoomDebugOverride

/** 配信先・映像・権限を分けて表示する。変更は端末へ自動保存する。 */
@Composable
fun SettingsScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    portrait: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    BackHandler { onBack() }
    var rtmpServer by remember { mutableStateOf(StreamPrefs.loadServer(prefs)) }
    var streamKey by remember { mutableStateOf(StreamPrefs.loadKey(prefs)) }
    var resolutionIndex by remember { mutableIntStateOf(StreamPrefs.loadResIndex(prefs)) }
    var bitrateKbps by remember { mutableIntStateOf(StreamPrefs.loadBitrateKbps(prefs)) }
    var showKey by remember { mutableStateOf(false) }
    val streamState by controller.state.collectAsState()
    val isStreaming = streamState.isStreaming

    fun persist() {
        StreamPrefs.save(prefs, rtmpServer, streamKey, resolutionIndex, bitrateKbps)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "配信画面に戻る")
                }
                Column(Modifier.weight(1f)) {
                    Text("配信設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("変更は自動で保存されます", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isStreaming) {
                        Text("配信処理中です。ビットレート以外の映像・接続設定は、停止後に変更できます。",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsSection("配信先", "RTMPサーバーとストリームキー") {
                        OutlinedTextField(
                            value = rtmpServer,
                            onValueChange = { rtmpServer = it.trim(); persist() },
                            label = { Text("RTMPサーバーURL") },
                            placeholder = { Text(StreamConfig.DEFAULT_SERVER) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true, enabled = !isStreaming,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = streamKey,
                            onValueChange = { streamKey = it.trim(); persist() },
                            label = { Text("ストリームキー") },
                            supportingText = { Text("配信先で指定されたストリームキー") },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showKey) "キーを隠す" else "キーを表示")
                                }
                            },
                            singleLine = true, enabled = !isStreaming,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { rtmpServer = StreamConfig.DEFAULT_SERVER; persist() },
                                enabled = !isStreaming, modifier = Modifier.weight(1f)
                            ) { Text("Gateway", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = {
                                    rtmpServer = "rtmp://192.168.1.1/live"
                                    if (streamKey.isEmpty()) streamKey = "livestream"
                                    persist()
                                },
                                enabled = !isStreaming, modifier = Modifier.weight(1f)
                            ) { Text("自宅Station例", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    SettingsSection("映像", "送信する映像の向きと画質") {
                        OrientationPicker(portrait, !isStreaming, onPortraitChanged)
                        Text(
                            if (portrait) "スマホを縦に持って配信します。" else "スマホを横に持って配信します。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ResolutionDropdown(resolutionIndex, portrait, !isStreaming) {
                            resolutionIndex = it
                            persist()
                        }
                        Text("映像ビットレート", style = MaterialTheme.typography.labelLarge)
                        Text("${bitrateKbps} kbps", style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = bitrateKbps.toFloat(),
                            onValueChange = {
                                bitrateKbps = it.toInt()
                                if (isStreaming) controller.setVideoBitrateOnFly(bitrateKbps * 1000)
                            },
                            onValueChangeFinished = { persist() },
                            valueRange = StreamConfig.BITRATE_MIN_KBPS.toFloat()..StreamConfig.BITRATE_MAX_KBPS.toFloat(),
                            steps = StreamConfig.BITRATE_SLIDER_STEPS
                        )
                        Text("H.264 + AAC / ${StreamConfig.VIDEO_FPS} fps / キーフレーム ${StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC}秒",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (controller.isDebuggable()) {
                        SettingsSection("カメラ診断", "デバッグビルド専用") {
                            Column(
                                modifier = Modifier.fillMaxWidth().selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ZoomDebugOverride.entries.forEach { override ->
                                    val selected = streamState.zoom.debugOverride == override
                                    OutlinedButton(
                                        onClick = { controller.setZoomDebugOverride(override) },
                                        enabled = !isStreaming,
                                        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }
                                    ) {
                                        RadioButton(selected = selected, onClick = null, enabled = !isStreaming)
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
                            Text(
                                controller.zoomDiagnostics(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SettingsSection("権限と接続の準備", "配信前に受け側も準備してください") {
                        OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("カメラ・マイク・通知の権限を確認")
                        }
                        Text("通知の許可は任意です。カメラとマイクは配信に必要です。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Gateway", fontWeight = FontWeight.SemiBold)
                        Text("FLVチャンネルを作成し、発行されたURLと4桁キーを入力します。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("自宅Station", fontWeight = FontWeight.SemiBold)
                        Text("Station側をRTMP受信待ち (SEARCHING) にしてから、このアプリで配信を開始します。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
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
