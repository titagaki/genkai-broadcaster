package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.titagaki.genkaibroadcaster.streamer.VideoStabilizationStatus
import io.github.titagaki.genkaibroadcaster.streamer.ZoomDebugOverride

/**
 * カメラページ: 手振れ補正。デバッグビルドではズーム方式の強制と診断表示も出す。
 *
 * @param stabilization 保存されている要求値 (オン/オフ)
 * @param stabilizationStatus 今のカメラへの適用結果。非対応なら案内文を出す
 */
@Composable
internal fun CameraPage(
    stabilization: Boolean,
    stabilizationStatus: VideoStabilizationStatus,
    onStabilizationChange: (Boolean) -> Unit,
    debuggable: Boolean,
    zoomOverride: ZoomDebugOverride,
    zoomOverrideEnabled: Boolean,
    diagnostics: String,
    onZoomOverrideSelect: (ZoomDebugOverride) -> Unit
) {
    SettingsSection("手振れ補正", "配信中も変更でき、即時反映されます") {
        RadioOptionList(
            options = listOf(true to "オン", false to "オフ"),
            selected = stabilization,
            enabled = true,
            onSelect = onStabilizationChange
        )
        Text(
            when {
                stabilization && stabilizationStatus == VideoStabilizationStatus.UNSUPPORTED ->
                    "このカメラは手振れ補正に対応していません。別のカメラや倍率では効くことがあります。"
                stabilization ->
                    "端末のカメラの電子式手振れ補正 (EIS) で映像の揺れを抑えます。画角がわずかに狭くなり、遅延が少し増えることがあります。"
                else ->
                    "手振れ補正を使いません。三脚や固定撮影ではオフのままで問題ありません。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!debuggable) return
    SettingsSection("ズーム方式", "デバッグビルド専用") {
        RadioOptionList(
            options = ZoomDebugOverride.entries.map { it to it.label() },
            selected = zoomOverride,
            enabled = zoomOverrideEnabled,
            onSelect = onZoomOverrideSelect
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
