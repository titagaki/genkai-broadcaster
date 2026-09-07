package com.example.hogebroadcaster

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.hogebroadcaster.streamer.StreamConfig
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.ui.AppRoot

/**
 * アプリの入口。責務は3つのみ:
 * 1. プロセス共有の [StreamController] の取得
 * 2. 権限要求 (registerForActivityResult は Activity に必須のためここに置く)
 * 3. Compose ルート ([AppRoot]) の表示
 *
 * 配信ロジックは streamer 層、画面は ui 層にあり、このファイルに書かないこと。
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: StreamController
    private lateinit var prefs: SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (controller.hasPermissions()) {
            controller.startPreviewIfReady()
        } else {
            Toast.makeText(this, "カメラ/マイク権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(StreamConfig.PREFS_FILE, MODE_PRIVATE)
        controller = StreamController.getInstance(this)
        setContent {
            AppRoot(
                controller = controller,
                prefs = prefs,
                onRequestPermissions = ::requestPermissionsIfNeeded,
                onOrientationChanged = { portrait ->
                    requestedOrientation = if (portrait) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            )
        }
        requestPermissionsIfNeeded()
    }

    /** カメラ/マイク (+通知) 権限を要求する。設定画面の「権限を再確認」からも呼ばれる */
    fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            controller.startPreviewIfReady()
        }
    }
}
