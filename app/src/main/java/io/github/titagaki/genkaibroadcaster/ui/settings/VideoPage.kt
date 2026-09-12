package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.titagaki.genkaibroadcaster.streamer.StreamConfig
import io.github.titagaki.genkaibroadcaster.ui.OrientationPicker

/** 映像ページ: 向き・解像度・フレームレート・ビットレート・エンコーダ */

@Composable
internal fun VideoPage(
    portrait: Boolean,
    resolutionIndex: Int,
    fps: Int,
    bitrateKbps: Int,
    softwareEncoder: Boolean,
    isStreaming: Boolean,
    onPortraitChanged: (Boolean) -> Unit,
    onResolutionChange: (Int) -> Unit,
    onFpsChange: (Int) -> Unit,
    onSoftwareEncoderChange: (Boolean) -> Unit,
    onBitrateChange: (Int) -> Unit
) {
    SettingsSection("向きと解像度", "配信停止中のみ変更できます") {
        OrientationPicker(portrait, !isStreaming, onPortraitChanged)
        Text(
            if (portrait) "スマホを縦に持って配信します。" else "スマホを横に持って配信します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ResolutionDropdown(resolutionIndex, portrait, !isStreaming, onResolutionChange)
    }
    SettingsSection("フレームレート", "60 fps はカメラが対応する端末のみ有効です") {
        FpsSelector(fps, !isStreaming, onFpsChange)
    }
    SettingsSection("ビットレート", "配信中も変更でき、即時反映されます") {
        BitrateStepper(bitrateKbps, onBitrateChange)
        Text("H.264 + AAC / キーフレーム ${StreamConfig.VIDEO_KEYFRAME_INTERVAL_SEC}秒",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsSection("エンコーダ", "配信停止中のみ変更できます") {
        EncoderSelector(softwareEncoder, !isStreaming, onSoftwareEncoderChange)
        Text(
            if (softwareEncoder) {
                "CPU でエンコードします。古いPCプレーヤー (DXVA2 有効) でも再生できます。電池を多く使うため、高解像度・高フレームレートで重い場合は「標準」を試してください。"
            } else {
                "端末のハードウェアでエンコードします。省電力ですが、古いPCプレーヤー (DXVA2 有効) で再生できないことがあります。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** エンコーダ選択。ズーム方式 (CameraPage) と同じ縦並びのラジオ選択 */
@Composable
private fun EncoderSelector(software: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    RadioOptionList(
        options = listOf(true to "互換 (ソフトウェア)", false to "標準 (ハードウェア)"),
        selected = software,
        enabled = enabled,
        onSelect = onSelect
    )
}

@Composable
private fun FpsSelector(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val options = StreamConfig.FPS_OPTIONS
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, fps ->
            SegmentedButton(
                selected = fps == selected,
                onClick = { onSelect(fps) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
            ) { Text("$fps") }
        }
    }
}

/**
 * ビットレートの数値入力。右端に `<input type="number">` 風の ∧/∨ ボタンを縦に並べる。
 *
 * 入力中は範囲内の値だけを即時反映し、範囲外はエラー表示に留める。
 * フォーカスが外れた時と IME の完了で範囲内に丸めて確定する。
 */
@Composable
private fun BitrateStepper(value: Int, onValueChange: (Int) -> Unit) {
    val min = StreamConfig.BITRATE_MIN_KBPS
    val max = StreamConfig.BITRATE_MAX_KBPS
    val step = StreamConfig.BITRATE_STEP_KBPS
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    val parsed = text.toIntOrNull()
    val inRange = parsed != null && parsed in min..max
    // ボタン操作など外部からの変更は、入力中でなければ表示へ反映する
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }

    fun commitClamped() {
        val clamped = (parsed ?: value).coerceIn(min, max)
        onValueChange(clamped)
        text = clamped.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(5)
            text = digits
            digits.toIntOrNull()?.let { if (it in min..max) onValueChange(it) }
        },
        isError = !inRange,
        suffix = { Text("kbps") },
        trailingIcon = {
            Column {
                IconButton(
                    onClick = { onValueChange((value + step).coerceAtMost(max)) },
                    enabled = value < max,
                    modifier = Modifier.size(width = 40.dp, height = 28.dp)
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "${step} kbps 上げる") }
                IconButton(
                    onClick = { onValueChange((value - step).coerceAtLeast(min)) },
                    enabled = value > min,
                    modifier = Modifier.size(width = 40.dp, height = 28.dp)
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "${step} kbps 下げる") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitClamped(); focusManager.clearFocus() }),
        textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) commitClamped()
            focused = state.isFocused
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolutionDropdown(selectedIndex: Int, portrait: Boolean, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = StreamConfig.RESOLUTIONS[selectedIndex]
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = "${selected.label} / ${selected.dimensions(portrait)}",
            onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text("送信解像度") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StreamConfig.RESOLUTIONS.forEachIndexed { i, resolution ->
                DropdownMenuItem(
                    text = { Text("${resolution.label} / ${resolution.dimensions(portrait)}") },
                    onClick = { onSelect(i); expanded = false }
                )
            }
        }
    }
}
