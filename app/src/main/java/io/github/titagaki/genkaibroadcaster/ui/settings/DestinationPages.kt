package io.github.titagaki.genkaibroadcaster.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.titagaki.genkaibroadcaster.streamer.DestinationPreset
import io.github.titagaki.genkaibroadcaster.streamer.StreamConfig
import io.github.titagaki.genkaibroadcaster.streamer.StreamDestination

/** 配信ページ: 接続先の一覧・追加・編集 */

@Composable
internal fun DestinationList(
    destinations: List<StreamDestination>,
    activeId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: (StreamDestination) -> Unit
) {
    SettingsSection("接続先", "配信に使う接続先を選びます。鉛筆で編集") {
        if (destinations.isEmpty()) {
            Text("接続先がありません。下の入力例から追加してください。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            destinations.forEach { destination ->
                val selected = destination.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { onSelect(destination.id) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(destination.name.ifBlank { "(名前なし)" }, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(
                                destination.server.ifBlank { "サーバー未設定" },
                                if (destination.key.isBlank()) "キー未設定" else "キー設定済み"
                            ).joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onEdit(destination.id) }, enabled = enabled) {
                        Icon(Icons.Filled.Edit, contentDescription = "${destination.name} を編集")
                    }
                }
            }
        }
    }
    SettingsSection("接続先を追加", "入力例を選ぶとサーバーURLが入った状態で編集に入ります") {
        DestinationPresetButtons(enabled, onAdd)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationPresetButtons(enabled: Boolean, onAdd: (StreamDestination) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DestinationPreset.entries.forEach { preset ->
            OutlinedButton(onClick = { onAdd(preset.create()) }, enabled = enabled) { Text(preset.label) }
        }
        OutlinedButton(
            onClick = { onAdd(StreamDestination(StreamDestination.newId(), "", "", "")) },
            enabled = enabled
        ) { Text("空の接続先") }
    }
}

@Composable
internal fun DestinationEditor(
    destination: StreamDestination,
    showKey: Boolean,
    enabled: Boolean,
    canDelete: Boolean,
    onChange: (StreamDestination) -> Unit,
    onToggleShowKey: () -> Unit,
    onDelete: () -> Unit
) {
    SettingsCard {
        OutlinedTextField(
            value = destination.name,
            onValueChange = { onChange(destination.copy(name = it)) },
            label = { Text("名前") },
            placeholder = { Text("例: PeerCast Gateway") },
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = destination.server,
            onValueChange = { onChange(destination.copy(server = it.trim())) },
            label = { Text("RTMPサーバーURL") },
            placeholder = { Text(StreamConfig.DEFAULT_SERVER) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = destination.key,
            onValueChange = { onChange(destination.copy(key = it.trim())) },
            label = { Text("ストリームキー") },
            supportingText = { Text("配信先で指定されたストリームキー") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleShowKey) {
                    Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "キーを隠す" else "キーを表示")
                }
            },
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsSection("削除", if (canDelete) "この接続先を一覧から消します" else "最後の接続先は削除できません") {
        TextButton(onClick = onDelete, enabled = enabled && canDelete) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("この接続先を削除", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
