package com.example.hogebroadcaster

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.common.onMainThreadHandler
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.base.StreamBase
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class Resolution(val label: String, val width: Int, val height: Int)

private val RESOLUTIONS = listOf(
    Resolution("480p (854x480)", 854, 480),
    Resolution("720p (1280x720)", 1280, 720),
    Resolution("1080p (1920x1080)", 1920, 1080),
)

class MainActivity : ComponentActivity(), ConnectChecker {

    private var genericStream: StreamBase? = null
    private var surfaceView: SurfaceView? = null

    // UI state hooks (set from Compose via callbacks)
    var onStatus: ((String) -> Unit)? = null
    var onStreamingChanged: ((Boolean) -> Unit)? = null
    var onStats: ((String) -> Unit)? = null

    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toast("カメラ/マイク権限が必要です: $denied")
        } else {
            startPreviewIfReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hoge_broadcaster", MODE_PRIVATE)

        genericStream = GenericStream(this, this).apply {
            getGlInterface().autoHandleOrientation = true
            getStreamClient().setReTries(10)
        }

        setContent {
            MaterialTheme {
                StreamerScreen(
                    activity = this,
                    prefs = prefs,
                )
            }
        }
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        try {
            if (genericStream?.isStreaming == true) genericStream?.stopStream()
            if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
            genericStream?.release()
        } catch (_: Exception) { }
        StreamService.stop(this)
        super.onDestroy()
    }

    // ---------- permission / preview ----------

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return cam && mic
    }

    fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else startPreviewIfReady()
    }

    fun attachSurface(sv: SurfaceView) {
        surfaceView = sv
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPreviewIfReady()
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
                genericStream?.getGlInterface()?.setPreviewResolution(w, h2)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (genericStream?.isOnPreview == true) genericStream?.stopPreview()
            }
        })
        startPreviewIfReady()
    }

    private var preparedKey: String? = null

    fun ensurePrepared(width: Int, height: Int, videoBitrate: Int): Boolean {
        val key = "$width-$height-$videoBitrate"
        if (preparedKey == key) return true
        val wasPreview = genericStream?.isOnPreview == true
        if (wasPreview) genericStream?.stopPreview()
        val ok = try {
            genericStream?.prepareVideo(width, height, videoBitrate, rotation = 0) == true &&
                genericStream?.prepareAudio(32000, true, 128 * 1000) == true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!ok) {
            toast("Video/Audio 設定に失敗しました")
            return false
        }
        preparedKey = key
        if (wasPreview || surfaceView != null) startPreviewIfReady()
        return true
    }

    private fun startPreviewIfReady() {
        if (!hasPermissions()) return
        val sv = surfaceView ?: return
        if (genericStream?.isOnPreview == true) return
        // default prepare (720p) if not prepared yet
        if (preparedKey == null) {
            try {
                genericStream?.prepareVideo(1280, 720, 3000 * 1000, rotation = 0)
                genericStream?.prepareAudio(32000, true, 128 * 1000)
                preparedKey = "1280-720-3000000"
            } catch (_: Exception) { }
        }
        try {
            genericStream?.startPreview(sv)
        } catch (_: Exception) { }
    }

    // ---------- stream actions (called from UI) ----------

    fun startStream(url: String) {
        if (genericStream?.isStreaming == true) return
        if (url.isBlank() || !url.startsWith("rtmp")) {
            toast("rtmp:// から始まるURLを入力してください")
            return
        }
        StreamService.start(this)
        genericStream?.startStream(url)
    }

    fun stopStream() {
        genericStream?.stopStream()
        StreamService.stop(this)
    }

    fun switchCamera() {
        (genericStream?.videoSource as? Camera2Source)?.switchCamera()
    }

    fun toggleMute(muted: Boolean) {
        if (muted) genericStream?.disableAudio() else genericStream?.enableAudio()
    }

    fun setVideoBitrateOnFly(bitrate: Int) {
        try {
            genericStream?.setVideoBitrateOnFly(bitrate)
        } catch (_: Exception) { }
    }

    // ---------- ConnectChecker ----------

    override fun onConnectionStarted(url: String) {
        onMainThreadHandler { onStatus?.invoke("接続中...") }
    }

    override fun onConnectionSuccess() {
        onMainThreadHandler {
            toast("配信開始!")
            onStatus?.invoke("LIVE")
            onStreamingChanged?.invoke(true)
        }
    }

    override fun onConnectionFailed(reason: String) {
        onMainThreadHandler {
            if (genericStream?.getStreamClient()?.reTry(5000, reason, null) == true) {
                onStatus?.invoke("再接続中... ($reason)")
                toast("再接続します: $reason")
            } else {
                onStatus?.invoke("接続失敗: $reason")
                onStreamingChanged?.invoke(false)
                StreamService.stop(this)
                toast("接続失敗: $reason")
            }
        }
    }

    override fun onDisconnect() {
        onMainThreadHandler {
            onStatus?.invoke("切断")
            onStreamingChanged?.invoke(false)
            onStats?.invoke("")
            StreamService.stop(this)
            toast("切断しました")
        }
    }

    override fun onAuthError() {
        onMainThreadHandler {
            genericStream?.stopStream()
            onStatus?.invoke("認証エラー")
            onStreamingChanged?.invoke(false)
            StreamService.stop(this)
            toast("認証エラー")
        }
    }

    override fun onAuthSuccess() {
        onMainThreadHandler { toast("認証OK") }
    }

    override fun onStreamingStats(report: StreamingStatsReport) {
        onMainThreadHandler {
            if (report.throughput != Throughput.UNKNOWN) {
                onStats?.invoke(
                    "%.1f Mbps [%s]".format(
                        report.smoothedBitrate / 1_000_000f,
                        report.throughput.name.lowercase()
                    )
                )
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ================= Compose UI =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamerScreen(activity: MainActivity, prefs: SharedPreferences) {
    // 旧版(rtmp_url単一欄)からの移行: "rtmp://host/live/key" を server/key に分割
    val legacy = remember { prefs.getString("rtmp_url", "").orEmpty() }
    val hasNew = remember { prefs.contains("rtmp_server") }
    var rtmpServer by remember {
        mutableStateOf(
            if (hasNew) prefs.getString("rtmp_server", "") ?: ""
            else legacy.substringBeforeLast("/", "").ifEmpty { "rtmp://pcgw.pgw.jp/live" }
        )
    }
    var streamKey by remember {
        mutableStateOf(
            if (hasNew) prefs.getString("stream_key", "") ?: ""
            else legacy.substringAfterLast("/", "")
        )
    }
    var resolutionIndex by remember { mutableIntStateOf(prefs.getInt("res_index", 1)) }
    var bitrateKbps by remember { mutableIntStateOf(prefs.getInt("bitrate_kbps", 3000)) }
    var isStreaming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("待機中") }
    var stats by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(false) }
    var streamSeconds by remember { mutableLongStateOf(0L) }

    DisposableEffect(activity) {
        activity.onStatus = { status = it }
        activity.onStreamingChanged = { isStreaming = it }
        activity.onStats = { stats = it }
        onDispose {
            activity.onStatus = null
            activity.onStreamingChanged = null
            activity.onStats = null
        }
    }

    // stream timer
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

    fun persist() {
        prefs.edit()
            .putString("rtmp_server", rtmpServer)
            .putString("stream_key", streamKey)
            .putInt("res_index", resolutionIndex)
            .putInt("bitrate_kbps", bitrateKbps)
            .remove("rtmp_url") // 旧形式は移行済みなので削除
            .apply()
    }

    // サーバーURL + キー → フルURL (キーが空ならサーバーURLのみ)
    fun fullUrl(): String {
        val s = rtmpServer.trim().trimEnd('/')
        val k = streamKey.trim().trim('/')
        return if (k.isEmpty()) s else "$s/$k"
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Preview (60% of screen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { activity.attachSurface(it) }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // LIVE badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(if (isStreaming) Color.Red else Color.DarkGray)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (isStreaming) "● LIVE ${"%02d:%02d".format(streamSeconds / 60, streamSeconds % 60)}"
                            else "○ " + status,
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(stats, color = Color.White)
                        }
                    }
                }
                // preview controls
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    IconButton(onClick = { activity.switchCamera() }) {
                        Icon(Icons.Filled.Cameraswitch, "切替", tint = Color.White)
                    }
                    IconButton(onClick = {
                        muted = !muted
                        activity.toggleMute(muted)
                    }) {
                        Icon(
                            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            "ミュート", tint = Color.White
                        )
                    }
                }
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = rtmpServer,
                    onValueChange = { rtmpServer = it.trim(); persist() },
                    label = { Text("RTMPサーバーURL") },
                    placeholder = { Text("rtmp://pcgw.pgw.jp/live") },
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
                // プリセット
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            rtmpServer = "rtmp://pcgw.pgw.jp/live"
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

                // resolution dropdown
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!isStreaming) expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = RESOLUTIONS[resolutionIndex].label,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isStreaming,
                        label = { Text("解像度") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        RESOLUTIONS.forEachIndexed { i, r ->
                            DropdownMenuItem(
                                text = { Text(r.label) },
                                onClick = {
                                    resolutionIndex = i
                                    persist()
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // bitrate slider
                Text("映像ビットレート: ${bitrateKbps} kbps", fontWeight = FontWeight.Bold)
                Slider(
                    value = bitrateKbps.toFloat(),
                    onValueChange = {
                        bitrateKbps = it.toInt()
                        if (isStreaming) activity.setVideoBitrateOnFly(bitrateKbps * 1000)
                    },
                    onValueChangeFinished = { persist() },
                    valueRange = 800f..8000f,
                    steps = 24,
                    enabled = true
                )

                val res = RESOLUTIONS[resolutionIndex]
                val url = fullUrl()
                Button(
                    onClick = {
                        if (isStreaming) {
                            activity.stopStream()
                        } else {
                            persist()
                            if (!url.startsWith("rtmp://")) {
                                return@Button
                            }
                            if (activity.ensurePrepared(res.width, res.height, bitrateKbps * 1000)) {
                                activity.startStream(url)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isStreaming) Color(0xFFB3261E) else Color(0xFF2E7D32)
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isStreaming) "配信を停止" else "配信を開始", fontWeight = FontWeight.Bold)
                }
                if (!isStreaming && url.isNotEmpty() && !url.startsWith("rtmp://")) {
                    Text(
                        "URLは rtmp:// から始めてください",
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedButton(
                    onClick = { activity.requestPermissionsIfNeeded() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("権限を再確認")
                }

                Text(
                    "PeerCast向けRTMP配信アプリ。H.264+AAC/FLV互換で送ります。\n" +
                        "【Gateway】チャンネル作成後に表示される4桁キーを入力。\n" +
                        "【自宅Station】先にStation側でRTMP配信開始(SEARCHING)→このアプリで開始→RECEIVINGになればOK。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
