package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.titagaki.genkaibroadcaster.streamer.ZoomDebugOverride

/** カメラページ (デバッグビルド専用): ズーム方式の強制と診断表示 */
@Composable
internal fun CameraPage(
    current: ZoomDebugOverride,
    enabled: Boolean,
    diagnostics: String,
    onSelect: (ZoomDebugOverride) -> Unit
) {
    SettingsSection("ズーム方式", "デバッグビルド専用") {
        RadioOptionList(
            options = ZoomDebugOverride.entries.map { it to it.label() },
            selected = current,
            enabled = enabled,
            onSelect = onSelect
        )
    }
    SettingsSection("診断", "API・カメラID・倍率範囲") {
        Text(diagnostics, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ZoomDebugOverride.label(): String = when (this) {
    ZoomDebugOverride.AUTO -> "自動判定"
    ZoomDebugOverride.FORCE_DIGITAL -> "デジタルを強制"
    ZoomDebugOverride.FORCE_LOGICAL -> "論理カメラを強制"
}
