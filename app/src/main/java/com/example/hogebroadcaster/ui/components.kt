package com.example.hogebroadcaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 配信画面・設定画面で共有する小さな部品群。
 * 固有の画面にしか使わない部品は各画面ファイル内に private で置くこと。
 */

/** カメラ前後セグメントの1ボタン。選択中は白抜きハイライト */
@Composable
fun CameraSegButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.Transparent,
            contentColor = if (selected) Color.Black else Color.White
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** LIVEバッジ。配信中は赤、それ以外はグレー */
@Composable
fun LiveBadge(isStreaming: Boolean, text: String) {
    Box(
        modifier = Modifier
            .background(if (isStreaming) Color.Red else Color.DarkGray)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** 送信統計チップ ("3.0 Mbps [good]" 等) */
@Composable
fun StatsChip(stats: String) {
    Box(
        modifier = Modifier
            .background(Color(0xAA000000))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(stats, color = Color.White, style = MaterialTheme.typography.bodyMedium)
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
