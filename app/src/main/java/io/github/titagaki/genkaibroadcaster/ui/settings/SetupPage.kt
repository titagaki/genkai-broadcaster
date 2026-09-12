package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 権限ページ: カメラ・マイク・通知の権限を再要求する */
@Composable
internal fun SetupPage(onRequestPermissions: () -> Unit) {
    SettingsSection("権限", "カメラとマイクは配信に必要です") {
        OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("カメラ・マイク・通知の権限を確認")
        }
        Text("通知の許可は任意です。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
