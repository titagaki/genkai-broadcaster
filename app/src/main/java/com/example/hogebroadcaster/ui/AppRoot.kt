package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.hogebroadcaster.streamer.StreamController
import com.example.hogebroadcaster.streamer.StreamPrefs

/**
 * 画面遷移の管理。配信画面⇔設定画面の2画面のみ。
 *
 * Navigation-Compose を入れず自前のフラグ管理にしているのは、
 * 画面が2つだけでライブラリ追加のコストに見合わないため。
 * 3画面以上に増える場合は Navigation-Compose への移行を検討すること。
 */
@Composable
fun AppRoot(
    controller: StreamController,
    prefs: SharedPreferences,
    onRequestPermissions: () -> Unit,
    onOrientationChanged: (Boolean) -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(StreamPrefs.loadPortrait(prefs)) }
    val changeOrientation: (Boolean) -> Unit = { value ->
        if (!controller.isStreamingNow() && portrait != value) {
            StreamPrefs.savePortrait(prefs, value)
            portrait = value
        }
    }
    LaunchedEffect(portrait) {
        onOrientationChanged(portrait)
        controller.startPreviewIfReady()
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9CDBB5),
            onPrimary = Color(0xFF102B1D),
            primaryContainer = Color(0xFF254936),
            onPrimaryContainer = Color(0xFFC8F3DA),
            background = Color(0xFF101413),
            surface = Color(0xFF181E1B),
            surfaceVariant = Color(0xFF29322D),
            onSurface = Color(0xFFF0F3EF),
            onSurfaceVariant = Color(0xFFB9C4BC),
            outline = Color(0xFF536158)
        )
    ) {
        if (showSettings) {
            SettingsScreen(
                controller = controller,
                prefs = prefs,
                portrait = portrait,
                onPortraitChanged = changeOrientation,
                onBack = { showSettings = false },
                onRequestPermissions = onRequestPermissions
            )
        } else {
            StreamScreen(
                controller = controller,
                prefs = prefs,
                portrait = portrait,
                onOpenSettings = { showSettings = true },
                onRequestPermissions = onRequestPermissions
            )
        }
    }
}
