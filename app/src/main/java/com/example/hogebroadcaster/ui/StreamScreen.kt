package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hogebroadcaster.streamer.LensOption
import com.example.hogebroadcaster.streamer.RESOLUTIONS
import com.example.hogebroadcaster.streamer.StreamConfig
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs
import com.example.hogebroadcaster.system.BatteryMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

/** 送信フレーム全体を見せるビューと、縦横で配置を変える操作パネル。 */
@Composable
fun StreamScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    portrait: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val streamState by controller.state.collectAsState()
    var urlError by remember { mutableStateOf<String?>(null) }
    val status = urlError ?: streamState.status
    val resolution = remember(prefs) { RESOLUTIONS[StreamPrefs.loadResIndex(prefs)] }
    val dimensions = resolution.dimensions(portrait)
    val aspectRatio = resolution.outputWidth(portrait).toFloat() / resolution.outputHeight(portrait)
    var torchOn by remember { mutableStateOf(controller.isTorchOn()) }
    var isFront by remember { mutableStateOf(controller.isFrontCamera()) }
    var lensId by remember { mutableStateOf(controller.currentLensId()) }
    var showCameraMenu by remember { mutableStateOf(false) }
    val lenses = remember(streamState.previewReady) { controller.listLenses() }
    var meterLevel by remember { mutableFloatStateOf(0f) }
    val startedAtMs = streamState.startedAtMs
    var streamSeconds by remember(startedAtMs) {
        mutableLongStateOf(startedAtMs?.let { (SystemClock.elapsedRealtime() - it) / 1000 } ?: 0L)
    }
    val appContext = LocalContext.current.applicationContext
    var battery by remember { mutableStateOf(BatteryMonitor.getInfo(appContext)) }

    LaunchedEffect(startedAtMs) {
        if (startedAtMs != null) {
            while (isActive) {
                streamSeconds = (SystemClock.elapsedRealtime() - startedAtMs) / 1000
                delay(1000)
            }
        }
    }
    LaunchedEffect(controller, streamState.muted) {
        while (isActive) {
            meterLevel = if (streamState.muted) 0f else max(controller.micLevel(), meterLevel * 0.75f)
            if (meterLevel < 0.01f) meterLevel = 0f
            delay(100)
        }
    }
    LaunchedEffect(streamState.previewReady) {
        isFront = controller.isFrontCamera()
        lensId = controller.currentLensId()
        torchOn = controller.isTorchOn()
    }
    LaunchedEffect(appContext) {
        while (isActive) {
            battery = BatteryMonitor.getInfo(appContext)
            delay(30_000)
        }
    }

    val controls: @Composable (Boolean, Modifier) -> Unit = { compact, modifier ->
        ControlPanel(
            isStreaming = streamState.isStreaming,
            portrait = portrait,
            onPortraitChanged = onPortraitChanged,
            isFront = isFront,
            torchOn = torchOn,
            muted = streamState.muted,
            cameraReady = streamState.previewReady,
            status = status,
            compact = compact,
            onOpenCameraMenu = { showCameraMenu = true },
            onToggleTorch = {
                controller.setTorch(!torchOn)
                torchOn = controller.isTorchOn()
            },
            onToggleMute = { controller.toggleMute(!streamState.muted) },
            onToggleStream = {
                urlError = null
                if (controller.isStreamingNow()) {
                    controller.stopStream()
                } else {
                    val url = StreamPrefs.buildFullUrl(prefs)
                    if (url.startsWith("rtmp://")) controller.startStream(url)
                    else urlError = "配信先URLが不正です。右上の設定を確認してください"
                }
            },
            modifier = modifier
        )
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveBadge(
                    isStreaming = streamState.isConnected,
                    text = if (streamState.isConnected) {
                        "LIVE  ${"%02d:%02d".format(streamSeconds / 60, streamSeconds % 60)}"
                    } else status
                )
                Text(
                    "$dimensions / ${StreamConfig.VIDEO_FPS} fps" +
                        if (streamState.stats.isNotEmpty()) " / ${streamState.stats}" else " / RTMP",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (battery.percent >= 0) {
                Text(
                    "${battery.percent}%" + if (battery.isCharging) " 充電中" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 88.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "配信設定", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize()) {
                    StreamPreview(
                        controller, aspectRatio, portrait, streamState.previewReady,
                        meterLevel, onRequestPermissions, Modifier.weight(1f).fillMaxHeight()
                    )
                    controls(true, Modifier.width((this@BoxWithConstraints.maxWidth * 0.34f).coerceIn(180.dp, 264.dp)).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    StreamPreview(
                        controller, aspectRatio, portrait, streamState.previewReady,
                        meterLevel, onRequestPermissions, Modifier.weight(1f).fillMaxWidth()
                    )
                    controls(false, Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * 0.48f))
                }
            }
        }
    }

    if (showCameraMenu) {
        CameraMenuSheet(
            isFront = isFront,
            lensId = lensId,
            frontLens = lenses.firstOrNull { it.isFront },
            backLenses = lenses.filter { !it.isFront },
            onSelectFront = {
                controller.selectCamera(true)
                isFront = controller.isFrontCamera()
                lensId = controller.currentLensId()
                torchOn = controller.isTorchOn()
                showCameraMenu = false
            },
            onSelectBack = {
                controller.selectCamera(false)
                isFront = controller.isFrontCamera()
                lensId = controller.currentLensId()
                torchOn = controller.isTorchOn()
                showCameraMenu = false
            },
            onSelectLens = { id ->
                if (controller.openLens(id)) {
                    lensId = id
                    isFront = controller.isFrontCamera()
                    torchOn = controller.isTorchOn()
                }
                showCameraMenu = false
            },
            onDismiss = { showCameraMenu = false }
        )
    }
}

@Composable
private fun StreamPreview(
    controller: StreamController,
    aspectRatio: Float,
    portrait: Boolean,
    ready: Boolean,
    meterLevel: Float,
    onRequestPermissions: () -> Unit,
    modifier: Modifier
) {
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val frameWidth = minOf(maxWidth, maxHeight * aspectRatio)
        val frameHeight = frameWidth / aspectRatio
        Box(Modifier.size(frameWidth, frameHeight).border(1.dp, Color(0xFF424C46))) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also { controller.attachSurface(it) } },
                onRelease = { controller.detachSurface(it) },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                if (portrait) "縦配信 / 9:16" else "横配信 / 16:9",
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color(0xAA101413), RoundedCornerShape(4.dp)).padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AudioMeter(
                level = meterLevel,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            )
        }
        if (!ready) {
            Column(
                Modifier.padding(16.dp).background(Color(0xE6101413), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("プレビュー準備中", color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (!controller.hasPermissions()) {
                    OutlinedButton(onClick = onRequestPermissions) { Text("カメラ・マイクを許可") }
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    isStreaming: Boolean,
    portrait: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    isFront: Boolean,
    torchOn: Boolean,
    muted: Boolean,
    cameraReady: Boolean,
    status: String,
    compact: Boolean,
    onOpenCameraMenu: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleStream: () -> Unit,
    modifier: Modifier
) {
    val tools: @Composable (Modifier) -> Unit = { itemModifier ->
        QuickControl(Icons.Filled.PhotoCamera, if (isFront) "前面カメラ" else "背面・レンズ",
            false, cameraReady, compact, onOpenCameraMenu, itemModifier)
        QuickControl(if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            if (torchOn) "ライトON" else "ライトOFF", torchOn, cameraReady && !isFront,
            compact, onToggleTorch, itemModifier)
        QuickControl(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            if (muted) "ミュート中" else "マイクON", muted, cameraReady || isStreaming,
            compact, onToggleMute, itemModifier, warning = muted)
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Column(
            Modifier.weight(1f, fill = compact).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("配信方向", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OrientationPicker(portrait, !isStreaming, onPortraitChanged)
            if (isStreaming) {
                Text("方向の変更は配信停止後にできます", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { tools(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tools(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onToggleStream,
            enabled = isStreaming || cameraReady,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color(0xFFB43832) else MaterialTheme.colorScheme.primary,
                contentColor = if (isStreaming) Color.White else MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) {
            Icon(if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isStreaming) "配信を停止" else "配信を開始", fontWeight = FontWeight.Bold)
        }
        if (status != "LIVE" && status != "待機中" && status != "切断") {
            Text(status, modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickControl(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    warning: Boolean = false
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                warning -> MaterialTheme.colorScheme.errorContainer
                active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                warning -> MaterialTheme.colorScheme.onErrorContainer
                active -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    ) {
        if (compact) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraMenuSheet(
    isFront: Boolean,
    lensId: String?,
    frontLens: LensOption?,
    backLenses: List<LensOption>,
    onSelectFront: () -> Unit,
    onSelectBack: () -> Unit,
    onSelectLens: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("カメラとレンズ", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            CameraMenuItem(if (frontLens != null) "前面 ${frontLens.label}" else "前面カメラ", isFront, onSelectFront)
            if (backLenses.isEmpty()) {
                CameraMenuItem("背面カメラ", !isFront, onSelectBack)
            } else {
                backLenses.forEach { lens ->
                    CameraMenuItem("背面 ${lens.label}", !isFront && lens.cameraId == lensId) {
                        onSelectLens(lens.cameraId)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CameraMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        trailingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
