package com.example.hogebroadcaster.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.hogebroadcaster.streamer.StreamController

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
    onRequestPermissions: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(
            controller = controller,
            prefs = prefs,
            onBack = { showSettings = false },
            onRequestPermissions = onRequestPermissions
        )
    } else {
        StreamScreen(
            controller = controller,
            prefs = prefs,
            onOpenSettings = { showSettings = true }
        )
    }
}
