package io.github.titagaki.genkaibroadcaster.ui

import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.DisposableEffect
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
import io.github.titagaki.genkaibroadcaster.streamer.CameraZoomChoice
import io.github.titagaki.genkaibroadcaster.streamer.CameraZoomState
import io.github.titagaki.genkaibroadcaster.streamer.StreamController
import io.github.titagaki.genkaibroadcaster.streamer.StreamFormat
import io.github.titagaki.genkaibroadcaster.streamer.StreamPrefs
import io.github.titagaki.genkaibroadcaster.streamer.StreamState
import io.github.titagaki.genkaibroadcaster.system.BatteryMonitor
import io.github.titagaki.genkaibroadcaster.system.MicrophoneMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 配信画面。カメラプレビュー領域と操作帯 (スタジアム形) を分け、重ねない。
 * 縦配信では帯を下に、横配信では帯を右に置く。情報表示・設定・音量メーターはプレビュー上に重ねる。
 */
@Composable
fun StreamScreen(
    controller: StreamController,
    prefs: StreamPrefs,
    portrait: Boolean,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val streamState by controller.state.collectAsState()
    var urlError by remember { mutableStateOf<String?>(null) }
    val status = urlError ?: streamState.cameraError ?: streamState.status
    // 設定画面から戻ると StreamScreen は作り直されるので、初回コンポーズ時の値で足りる
    val resolution = remember { prefs.loadResolution() }
    val fps = remember { prefs.loadFps() }
    val dimensions = resolution.dimensions(portrait)
    val isFront = streamState.cameraIsFront
    var showCameraMenu by remember { mutableStateOf(false) }

    val startOrStop: () -> Unit = {
        urlError = null
        if (controller.isStreamingNow()) {
            controller.stopStream()
        } else {
            val url = prefs.buildFullUrl()
            urlError = when {
                StreamPrefs.isAcceptedRtmpUrl(url) -> { controller.startStream(url); null }
                url.isEmpty() -> "接続先が未登録です。設定から追加してください"
                else -> "配信先URLが不正です"
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val compactControls = maxHeight < 400.dp
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            PreviewArea(
                controller = controller,
                streamState = streamState,
                status = status,
                dimensions = dimensions,
                fps = fps,
                compact = compactControls,
                onOpenSettings = onOpenSettings,
                onRequestPermissions = onRequestPermissions,
                modifier = modifier
            )
        }
        val controls: @Composable (Boolean, Modifier) -> Unit = { vertical, modifier ->
            ControlBand(
                vertical = vertical,
                modifier = modifier,
                leading = {
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
                },
                center = {
                    OverlayIconButton(
                        icon = if (streamState.isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (streamState.isStreaming) "配信を停止" else "配信を開始",
                        label = if (streamState.isStreaming) "配信停止" else "配信開始",
                        containerColor = if (streamState.isStreaming) OverlayColors.stop else OverlayColors.start,
                        enabled = streamState.isStreaming || streamState.previewReady,
                        compact = compactControls,
                        onClick = startOrStop
                    )
                },
                trailing = {
                    OverlayIconButton(
                        icon = if (streamState.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (streamState.muted) "マイクをON" else "マイクをOFF",
                        label = if (streamState.muted) "OFF" else "ON",
                        active = streamState.muted,
                        enabled = streamState.previewReady || streamState.isStreaming,
                        compact = compactControls,
                        onClick = { controller.toggleMute(!streamState.muted) }
                    )
                }
            )
        }
        // 端末の向きは配信方向に固定しているので、縦配信=縦持ち、横配信=横持ち。
        // 縦は下に横長の帯、横は右に縦長の帯を置き、プレビューとは重ねない。
        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxWidth())
                controls(false, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxHeight())
                controls(true, Modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 12.dp))
            }
        }
    }
}

/** カメラプレビューと、その上に重ねる情報・設定ボタン・音量メーター。操作帯は含めない。 */
@Composable
private fun PreviewArea(
    controller: StreamController,
    streamState: StreamState,
    status: String,
    dimensions: String,
    fps: Int,
    compact: Boolean,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
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
        StreamInfo(
            status = status,
            dimensions = dimensions,
            fps = fps,
            stats = streamState.stats,
            startedAtMs = streamState.startedAtMs,
            commentText = streamState.commentSource.text,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp)
        )
        OverlayIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "配信設定",
            onClick = onOpenSettings,
            compact = compact,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
        )
        LiveAudioMeter(
            controller = controller,
            muted = streamState.muted,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)
        )
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

/**
 * 操作をまとめる帯。陸上トラックのように平行線と半円で閉じた形 (スタジアム形) にする。
 * [CircleShape] は矩形に対しては短辺の半分を角半径にするので、そのままスタジアム形になる。
 *
 * 3つのスロットを等幅 (縦帯では等高) にし、[center] が帯の中心に来るようにする。
 * SpaceEvenly だとピル型セレクターの幅に引きずられて中央の配信ボタンがずれるため。
 */
@Composable
private fun ControlBand(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = OverlayColors.band,
        border = BorderStroke(1.dp, OverlayColors.bandBorder),
        modifier = modifier
    ) {
        val slot: @Composable (Modifier, @Composable () -> Unit) -> Unit = { slotModifier, content ->
            Box(slotModifier, contentAlignment = Alignment.Center) { content() }
        }
        if (vertical) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 28.dp)) {
                slot(Modifier.weight(1f), leading)
                slot(Modifier.weight(1f), center)
                slot(Modifier.weight(1f), trailing)
            }
        } else {
            Row(Modifier.padding(horizontal = 28.dp, vertical = 10.dp)) {
                slot(Modifier.weight(1f), leading)
                slot(Modifier.weight(1f), center)
                slot(Modifier.weight(1f), trailing)
            }
        }
    }
}

@Composable
private fun StreamInfo(
    status: String,
    dimensions: String,
    fps: Int,
    stats: String,
    startedAtMs: Long?,
    commentText: String,
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
        if (startedAtMs != null) add(StreamFormat.elapsed(streamSeconds))
        if (stats.isNotEmpty()) add(stats)
        if (battery.percent >= 0) add("${battery.percent}%${if (battery.isCharging) " 充電中" else ""}")
    }.joinToString(" / ")
    Column(
        modifier = modifier.widthIn(max = 320.dp)
            .background(OverlayColors.scrim, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "$status / $dimensions / $fps fps",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (details.isNotEmpty()) {
            Text(
                details,
                color = OverlayColors.secondaryText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // コメント提供アプリとの接続状態 (配信中で設定があるときだけ)
        if (commentText.isNotEmpty()) {
            Text(
                commentText,
                color = OverlayColors.secondaryText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 音量メーターと、その下に使用中 (推定) のマイク種別 */
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
    val appContext = LocalContext.current.applicationContext
    var microphone by remember { mutableStateOf(MicrophoneMonitor.getInfo(appContext)) }
    DisposableEffect(appContext) {
        val callback = MicrophoneMonitor.register(appContext) {
            microphone = MicrophoneMonitor.getInfo(appContext)
        }
        onDispose { MicrophoneMonitor.unregister(appContext, callback) }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AudioMeter(level = if (muted) 0f else meterLevel)
        Text(
            microphone.label(),
            color = OverlayColors.secondaryText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    active: Boolean = false,
    containerColor: Color? = null,
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
                containerColor = containerColor ?: if (active) OverlayColors.buttonActive else OverlayColors.button,
                contentColor = Color.White,
                disabledContainerColor = OverlayColors.buttonDisabled,
                disabledContentColor = OverlayColors.contentDisabled
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
                    .background(OverlayColors.scrim, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
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
        modifier = modifier.background(OverlayColors.panel, RoundedCornerShape(12.dp)).padding(16.dp),
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
                containerColor = OverlayColors.selector,
                contentColor = Color.White,
                disabledContainerColor = OverlayColors.selectorDisabled,
                disabledContentColor = OverlayColors.contentDisabled
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
