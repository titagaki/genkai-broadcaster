package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hogebroadcaster.streamer.LensOption
import com.example.hogebroadcaster.streamer.RESOLUTIONS
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs
import com.example.hogebroadcaster.system.BatteryInfo
import com.example.hogebroadcaster.system.BatteryMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

/**
 * Moblin風の配信画面。
 * 全画面プレビュー + 上部ステータスバー + 下部クイックボタン。
 * 設定値は保持せず、配信開始時のみ prefs から読む (設定の真実は [StreamPrefs])。
 */
@Composable
fun StreamScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    onOpenSettings: () -> Unit
) {
    var isStreaming by remember { mutableStateOf(controller.isStreamingNow()) }
    var status by remember { mutableStateOf("待機中") }
    var stats by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(controller.isTorchOn()) }
    var isFront by remember { mutableStateOf(controller.isFrontCamera()) }
    var streamSeconds by remember { mutableLongStateOf(0L) }

    // 複数レンズ (カメラメニューで選択)
    var lenses by remember { mutableStateOf(controller.listLenses()) }
    var lensId by remember { mutableStateOf<String?>(controller.currentLensId()) }
    val backLenses = lenses.filter { !it.isFront }
    val frontLens = lenses.firstOrNull { it.isFront }

    // カメラメニューの表示状態 (2段階切替の2段目)
    var showCameraMenu by remember { mutableStateOf(false) }

    // 音量メーター (100msポーリング + 減衰で滑らかに)
    var meterLevel by remember { mutableFloatStateOf(0f) }

    // 電池残量 (30秒ポーリングで十分)
    val appContext = LocalContext.current.applicationContext
    var batteryText by remember { mutableStateOf(formatBattery(BatteryMonitor.getInfo(appContext))) }

    DisposableEffect(controller) {
        controller.onStatus = { status = it }
        controller.onStreamingChanged = { isStreaming = it }
        controller.onStats = { stats = it }
        onDispose {
            controller.onStatus = null
            controller.onStreamingChanged = null
            controller.onStats = null
        }
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            val start = System.currentTimeMillis()
            while (isActive) {
                streamSeconds = (System.currentTimeMillis() - start) / 1000
                delay(1000)
            }
        } else {
            streamSeconds = 0
        }
    }

    // 音量メーター更新。ミュート時は 0 固定
    LaunchedEffect(Unit) {
        while (isActive) {
            val raw = if (muted) 0f else controller.micLevel()
            meterLevel = max(raw, meterLevel * 0.75f)
            if (meterLevel < 0.01f) meterLevel = 0f
            delay(100)
        }
    }

    // 電池残量更新
    LaunchedEffect(Unit) {
        while (isActive) {
            batteryText = formatBattery(BatteryMonitor.getInfo(appContext))
            delay(30_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { controller.attachSurface(it) }
            },
            modifier = Modifier.fillMaxSize()
        )

        TopStatusBar(
            isStreaming = isStreaming,
            status = status,
            stats = stats,
            batteryText = batteryText,
            streamSeconds = streamSeconds,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 左端の音量メーター (IRL Pro風)
        AudioMeter(
            level = meterLevel,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        )

        BottomControlBar(
            isStreaming = isStreaming,
            status = status,
            isFront = isFront,
            torchOn = torchOn,
            muted = muted,
            onOpenCameraMenu = { showCameraMenu = true },
            onToggleTorch = {
                controller.setTorch(!torchOn)
                torchOn = controller.isTorchOn()
            },
            onToggleMute = {
                muted = !muted
                controller.toggleMute(muted)
            },
            onToggleStream = { toggleStream(controller, prefs) { status = it } },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // カメラ切替メニュー (2段階切替の2段目)
        if (showCameraMenu) {
            CameraMenuSheet(
                isFront = isFront,
                lensId = lensId,
                frontLens = frontLens,
                backLenses = backLenses,
                onSelectFront = {
                    controller.selectCamera(true)
                    isFront = true
                    showCameraMenu = false
                },
                onSelectBack = {
                    controller.selectCamera(false)
                    isFront = controller.isFrontCamera()
                    lensId = controller.currentLensId()
                    showCameraMenu = false
                },
                onSelectLens = { id ->
                    if (controller.openLens(id)) {
                        lensId = id
                        isFront = false
                    }
                    showCameraMenu = false
                },
                onDismiss = { showCameraMenu = false }
            )
        }
    }
}

/** prefs の設定で配信 開始/停止する。URL不正時は [onInvalidUrl] に理由を返す */
private fun toggleStream(
    controller: StreamController,
    prefs: SharedPreferences,
    onInvalidUrl: (String) -> Unit
) {
    if (controller.isStreamingNow()) {
        controller.stopStream()
        return
    }
    val resIndex = StreamPrefs.loadResIndex(prefs)
    val bitrateKbps = StreamPrefs.loadBitrateKbps(prefs)
    val res = RESOLUTIONS[resIndex]
    val url = StreamPrefs.buildFullUrl(prefs)
    if (!url.startsWith("rtmp://")) {
        onInvalidUrl("URLが不正です (右上の設定を確認)")
        return
    }
    if (controller.ensurePrepared(res.width, res.height, bitrateKbps * 1000)) {
        controller.startStream(url)
    }
}

/** 上部ステータスバー: LIVEバッジ + 統計 + 電池残量 + 設定ボタン */
@Composable
private fun TopStatusBar(
    isStreaming: Boolean,
    status: String,
    stats: String,
    batteryText: String,
    streamSeconds: Long,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x88000000))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveBadge(
            isStreaming = isStreaming,
            text = if (isStreaming) {
                "● LIVE ${"%02d:%02d".format(streamSeconds / 60, streamSeconds % 60)}"
            } else {
                "○ $status"
            }
        )
        if (stats.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            StatsChip(stats)
        }
        Spacer(Modifier.weight(1f))
        if (batteryText.isNotEmpty()) {
            Text(batteryText, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "設定", tint = Color.White)
        }
    }
}

/** 電池表示文言。充電中は⚡、通常は🔋 */
private fun formatBattery(info: BatteryInfo): String {
    if (info.percent < 0) return ""
    val icon = if (info.isCharging) "⚡" else "🔋"
    return "$icon${info.percent}%"
}

/** 下部コントロールバー: カメラメニュー・ライト・マイク・開始/停止 */
@Composable
private fun BottomControlBar(
    isStreaming: Boolean,
    status: String,
    isFront: Boolean,
    torchOn: Boolean,
    muted: Boolean,
    onOpenCameraMenu: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xAA000000))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // パネルタップでは切替えず、メニューを開く (2段階切替の1段目)
            Row {
                CameraSegButton(label = "BACK", selected = !isFront) { onOpenCameraMenu() }
                CameraSegButton(label = "FRONT", selected = isFront) { onOpenCameraMenu() }
            }
            IconButton(onClick = onToggleTorch) {
                Icon(
                    if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    contentDescription = "ライト",
                    tint = if (torchOn) Color.Yellow else Color.White
                )
            }
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = "ミュート",
                    tint = if (muted) Color.Red else Color.White
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onToggleStream,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color(0xFFB3261E) else Color(0xFF2E7D32)
            ),
            modifier = Modifier.size(84.dp)
        ) {
            Text(
                if (isStreaming) "停止" else "開始",
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        if (!isStreaming && status != "待機中") {
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * カメラ切替メニュー (2段階切替の2段目)。下から出るシートで選択する。
 * FRONT + 背面レンズ一覧 (画角表示) を出し、現在の選択をハイライトする。
 * レンズ列挙に失敗した場合は BACK/FRONT の2択になる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraMenuSheet(
    isFront: Boolean,
    lensId: String?,
    frontLens: LensOption?,
    backLenses: List<LensOption>,
    onSelectFront: () -> Unit,
    onSelectBack: () -> Unit,
    onSelectLens: (cameraId: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Text(
            "カメラ選択",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        CameraMenuItem(
            label = if (frontLens != null) "FRONT ${frontLens.label}" else "FRONT",
            selected = isFront,
            onClick = onSelectFront
        )
        if (backLenses.isEmpty()) {
            CameraMenuItem(
                label = "BACK",
                selected = !isFront,
                onClick = onSelectBack
            )
        } else {
            backLenses.forEach { lens ->
                CameraMenuItem(
                    label = "BACK ${lens.label}",
                    selected = !isFront && lens.cameraId == lensId,
                    onClick = { onSelectLens(lens.cameraId) }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CameraMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = onClick)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
