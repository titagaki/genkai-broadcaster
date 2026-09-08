package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hogebroadcaster.streamer.CameraZoomChoice
import com.example.hogebroadcaster.streamer.CameraZoomState
import com.example.hogebroadcaster.streamer.RESOLUTIONS
import com.example.hogebroadcaster.streamer.StreamConfig
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs
import com.example.hogebroadcaster.system.BatteryMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 全面カメラプレビューへ配信情報と操作を重ねる配信画面。 */
@Composable
fun StreamScreen(
    controller: StreamController,
    prefs: SharedPreferences,
    portrait: Boolean,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val streamState by controller.state.collectAsState()
    var urlError by remember { mutableStateOf<String?>(null) }
    val status = urlError ?: streamState.cameraError ?: streamState.status
    val resolution = remember(prefs) { RESOLUTIONS[StreamPrefs.loadResIndex(prefs)] }
    val dimensions = resolution.dimensions(portrait)
    val isFront = streamState.cameraIsFront
    var showCameraMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> SurfaceView(context).also { controller.attachSurface(it) } },
            onRelease = { controller.detachSurface(it) },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().pointerInput(controller, streamState.previewReady) {
                if (!streamState.previewReady) return@pointerInput
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange.isFinite() && zoomChange != 1f) {
                        controller.changeZoomBy(zoomChange)
                    }
                }
            }
        ) {}

        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compactControls = maxHeight < 400.dp
            StreamInfo(
                status = status,
                dimensions = dimensions,
                stats = streamState.stats,
                startedAtMs = streamState.startedAtMs,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp)
            )

            OverlayIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "配信設定",
                onClick = onOpenSettings,
                compact = compactControls,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            )

            LiveAudioMeter(
                controller = controller,
                muted = streamState.muted,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
            )

            OverlayIconButton(
                icon = if (streamState.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (streamState.muted) "マイクをON" else "マイクをOFF",
                label = if (streamState.muted) "OFF" else "ON",
                active = streamState.muted,
                enabled = streamState.previewReady || streamState.isStreaming,
                compact = compactControls,
                onClick = { controller.toggleMute(!streamState.muted) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactControls) 8.dp else 12.dp)
            ) {
                CameraZoomSelector(
                    isFront = isFront,
                    zoom = streamState.zoom,
                    choices = controller.cameraZoomChoices(isFront),
                    expanded = showCameraMenu,
                    enabled = streamState.previewReady || streamState.cameraError != null,
                    onExpandedChange = { showCameraMenu = it },
                    onSelectFacing = { controller.selectCamera(it) },
                    onSelectZoom = controller::selectCameraZoom
                )
                StreamButton(
                    isStreaming = streamState.isStreaming,
                    enabled = streamState.isStreaming || streamState.previewReady,
                    compact = compactControls,
                    onClick = {
                        urlError = null
                        if (controller.isStreamingNow()) {
                            controller.stopStream()
                        } else {
                            val url = StreamPrefs.buildFullUrl(prefs)
                            if (StreamPrefs.isAcceptedRtmpUrl(url)) controller.startStream(url)
                            else urlError = "配信先URLが不正です"
                        }
                    }
                )
            }
        }

        if (!streamState.previewReady) {
            PreviewUnavailable(
                hasPermissions = controller.hasPermissions(),
                cameraError = streamState.cameraError,
                onRequestPermissions = onRequestPermissions,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

}

@Composable
private fun StreamInfo(
    status: String,
    dimensions: String,
    stats: String,
    startedAtMs: Long?,
    modifier: Modifier = Modifier
) {
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
    LaunchedEffect(appContext) {
        while (isActive) {
            battery = BatteryMonitor.getInfo(appContext)
            delay(30_000)
        }
    }
    val details = buildList {
        if (startedAtMs != null) {
            val hours = streamSeconds / 3600
            val minutes = streamSeconds / 60 % 60
            add(
                if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, streamSeconds % 60)
                else "%02d:%02d".format(minutes, streamSeconds % 60)
            )
        }
        if (stats.isNotEmpty()) add(stats)
        if (battery.percent >= 0) add("${battery.percent}%${if (battery.isCharging) " 充電中" else ""}")
    }.joinToString(" / ")
    Column(
        modifier = modifier.widthIn(max = 320.dp)
            .background(Color(0x99101413), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "$status / $dimensions / ${StreamConfig.VIDEO_FPS} fps",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (details.isNotEmpty()) {
            Text(
                details,
                color = Color(0xFFD4DDD7),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveAudioMeter(
    controller: StreamController,
    muted: Boolean,
    modifier: Modifier = Modifier
) {
    var meterLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller, muted) {
        if (muted) {
            meterLevel = 0f
            return@LaunchedEffect
        }
        while (isActive) {
            meterLevel = max(controller.micLevel(), meterLevel * 0.75f)
            if (meterLevel < 0.01f) meterLevel = 0f
            delay(100)
        }
    }
    AudioMeter(level = if (muted) 0f else meterLevel, modifier = modifier)
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) Color(0xFFD1433E) else Color(0xAA101413),
                contentColor = Color.White,
                disabledContainerColor = Color(0x66101413),
                disabledContentColor = Color(0x88FFFFFF)
            ),
            modifier = Modifier.size(if (compact) 44.dp else 52.dp)
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(if (compact) 22.dp else 25.dp))
        }
        if (label != null && !compact) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp)
                    .background(Color(0x99101413), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun StreamButton(
    isStreaming: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color(0xFFB43832) else Color(0xFF2E7D4F),
                contentColor = Color.White
            ),
            modifier = Modifier.size(if (compact) 68.dp else 82.dp)
        ) {
            Icon(
                if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isStreaming) "配信を停止" else "配信を開始",
                modifier = Modifier.size(if (compact) 32.dp else 38.dp)
            )
        }
        if (!compact) {
            Text(
                if (isStreaming) "配信停止" else "配信開始",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 5.dp)
                    .background(Color(0x99101413), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PreviewUnavailable(
    hasPermissions: Boolean,
    cameraError: String?,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Color(0xDD101413), RoundedCornerShape(12.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(cameraError ?: "プレビュー準備中", color = Color.White, style = MaterialTheme.typography.titleSmall)
        if (!hasPermissions) {
            OutlinedButton(onClick = onRequestPermissions) { Text("カメラ・マイクを許可") }
        }
    }
}

@Composable
private fun CameraZoomSelector(
    isFront: Boolean,
    zoom: CameraZoomState,
    choices: List<CameraZoomChoice>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectFacing: (Boolean) -> Unit,
    onSelectZoom: (CameraZoomChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        Button(
            onClick = { onExpandedChange(!expanded) },
            enabled = enabled,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xCC2B2927),
                contentColor = Color.White,
                disabledContainerColor = Color(0x662B2927),
                disabledContentColor = Color(0x88FFFFFF)
            )
        ) {
            Text(
                "${if (isFront) "FRONT" else "BACK"}・${formatZoom(zoom.ratio)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SelectorRow("CAMERA") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup()
                    ) {
                        SelectorChoice("FRONT", isFront, enabled) { onSelectFacing(true) }
                        SelectorChoice("BACK", !isFront, enabled) { onSelectFacing(false) }
                    }
                }
                SelectorRow("ZOOM") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup()
                    ) {
                        choices.forEach { choice ->
                            SelectorChoice(
                                label = formatZoom(choice.ratio),
                                selected = abs(zoom.ratio - choice.ratio) < 0.06f,
                                enabled = enabled,
                                onClick = { onSelectZoom(choice) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 64.dp)
        )
        content()
    }
}

@Composable
private fun SelectorChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        shape = CircleShape,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick
        )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

private fun formatZoom(ratio: Float): String {
    val rounded = ratio.roundToInt()
    return if (abs(ratio - rounded) < 0.05f) "${rounded}x"
    else String.format(Locale.US, "%.1fx", ratio)
}
