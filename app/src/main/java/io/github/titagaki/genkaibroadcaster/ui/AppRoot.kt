package io.github.titagaki.genkaibroadcaster.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.titagaki.genkaibroadcaster.streamer.StreamController
import io.github.titagaki.genkaibroadcaster.streamer.StreamPrefs
import io.github.titagaki.genkaibroadcaster.ui.settings.SettingsScreen

/**
 * 画面遷移の管理。配信画面⇔設定画面の2画面のみ。
 * 設定画面内のカテゴリ階層 (メニュー⇔各ページ) は SettingsScreen が自前で持つ。
 *
 * Navigation-Compose を入れず自前のフラグ管理にしているのは、
 * トップレベルの画面が2つだけでライブラリ追加のコストに見合わないため。
 * トップレベルが3画面以上に増える場合は Navigation-Compose への移行を検討すること。
 */
@Composable
fun AppRoot(
    controller: StreamController,
    prefs: StreamPrefs,
    onRequestPermissions: () -> Unit,
    onOrientationChanged: (Boolean) -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var portrait by remember { mutableStateOf(prefs.loadPortrait()) }
    val changeOrientation: (Boolean) -> Unit = { value ->
        if (!controller.isStreamingNow() && portrait != value) {
            prefs.savePortrait(value)
            portrait = value
        }
    }
    LaunchedEffect(portrait) {
        onOrientationChanged(portrait)
        controller.startPreviewIfReady()
    }
    // streamer 層からの通知文はどの画面でも同じ見せ方 (Toast) にする
    val context = LocalContext.current
    LaunchedEffect(controller) {
        controller.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    GenkaiTheme {
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
