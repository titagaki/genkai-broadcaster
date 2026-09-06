package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hogebroadcaster.streamer.RESOLUTIONS
import com.example.hogebroadcaster.streamer.StreamConfig
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs

/**
 * 配信設定画面。配信先・映像・権限をまとめる。
 * 値は編集のたび [StreamPrefs] に保存する (保存ボタン不要の方針)。
 * 端末の戻るボタンでも配信画面に戻れる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    BackHandler { onBack() }

    var rtmpServer by remember { mutableStateOf(StreamPrefs.loadServer(prefs)) }
    var streamKey by remember { mutableStateOf(StreamPrefs.loadKey(prefs)) }
    var resolutionIndex by remember { mutableIntStateOf(StreamPrefs.loadResIndex(prefs)) }
    var bitrateKbps by remember { mutableIntStateOf(StreamPrefs.loadBitrateKbps(prefs)) }
    var isStreaming by remember { mutableStateOf(controller.isStreamingNow()) }

    DisposableEffect(controller) {
        controller.onStreamingChanged = { isStreaming = it }
        onDispose { controller.onStreamingChanged = null }
    }

    fun persist() {
        StreamPrefs.save(prefs, rtmpServer, streamKey, resolutionIndex, bitrateKbps)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
                Text("設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionTitle("配信先")
                OutlinedTextField(
                    value = rtmpServer,
                    onValueChange = { rtmpServer = it.trim(); persist() },
                    label = { Text("RTMPサーバーURL") },
                    placeholder = { Text(StreamConfig.DEFAULT_SERVER) },
                    singleLine = true,
                    enabled = !isStreaming,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it.trim(); persist() },
                    label = { Text("ストリームキー") },
                    placeholder = { Text("Gatewayの4桁キー / Stationはlivestream等") },
                    singleLine = true,
                    enabled = !isStreaming,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            rtmpServer = StreamConfig.DEFAULT_SERVER
                            persist()
                        },
                        enabled = !isStreaming,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Gateway", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            rtmpServer = "rtmp://192.168.1.1/live"
                            if (streamKey.isEmpty()) streamKey = "livestream"
                            persist()
                        },
                        enabled = !isStreaming,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("自宅Station例", maxLines = 1)
                    }
                }

                SectionTitle("映像")
                ResolutionDropdown(
                    selectedIndex = resolutionIndex,
                    enabled = !isStreaming,
                    onSelect = { resolutionIndex = it; persist() }
                )

                Text("映像ビットレート: ${bitrateKbps} kbps", fontWeight = FontWeight.Bold)
                Slider(
                    value = bitrateKbps.toFloat(),
                    onValueChange = {
                        bitrateKbps = it.toInt()
                        if (isStreaming) controller.setVideoBitrateOnFly(bitrateKbps * 1000)
                    },
                    onValueChangeFinished = { persist() },
                    valueRange = StreamConfig.BITRATE_MIN_KBPS.toFloat()..
                        StreamConfig.BITRATE_MAX_KBPS.toFloat(),
                    steps = StreamConfig.BITRATE_SLIDER_STEPS
                )

                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("権限を再確認")
                }

                Text(
                    "PeerCast向けRTMP配信アプリ。H.264+AAC/FLV互換で送ります。\n" +
                        "【Gateway】チャンネル作成後に表示される4桁キーを入力。\n" +
                        "【自宅Station】先にStation側でRTMP配信開始(SEARCHING)→配信画面で開始→RECEIVINGになればOK。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionDropdown(selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = RESOLUTIONS[selectedIndex].label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("解像度") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RESOLUTIONS.forEachIndexed { i, r ->
                DropdownMenuItem(
                    text = { Text(r.label) },
                    onClick = {
                        onSelect(i)
                        expanded = false
                    }
                )
            }
        }
    }
}
