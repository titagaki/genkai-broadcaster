package com.example.genkaibroadcaster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 配信画面・設定画面で共有する小さな部品群。
 * 固有の画面にしか使わない部品は各画面ファイル内に private で置くこと。
 */

/** 設定画面で使用する出力方向選択。 */
@Composable
fun OrientationPicker(portrait: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(true, false).forEach { vertical ->
            val selected = portrait == vertical
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.weight(1f).selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSelect(vertical) }
                )
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (vertical) Icons.Filled.StayCurrentPortrait else Icons.Filled.StayCurrentLandscape,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(if (vertical) "縦 9:16" else "横 16:9", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * IRL Pro風の縦型音量メーター。下から点灯する10セグメント。
 * 上2段=赤、中3段=黄、下5段=緑。
 *
 * @param level 0.0〜1.0
 */
@Composable
fun AudioMeter(level: Float, modifier: Modifier = Modifier, segments: Int = 10) {
    val lit = (level.coerceIn(0f, 1f) * segments).toInt()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (i in segments downTo 1) {
            val color = when {
                i > segments - 2 -> Color.Red
                i > segments - 5 -> Color.Yellow
                else -> Color.Green
            }
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 8.dp)
                    .background(if (i <= lit) color else Color.DarkGray)
            )
        }
    }
}
