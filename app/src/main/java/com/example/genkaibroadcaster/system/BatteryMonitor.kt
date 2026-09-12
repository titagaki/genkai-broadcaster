package com.example.genkaibroadcaster.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 電池残量の情報。
 *
 * @param percent 0〜100。取得失敗時は -1
 * @param isCharging 充電中 (満充電含む) なら true
 */
data class BatteryInfo(
    val percent: Int,
    val isCharging: Boolean
)

/**
 * 粘着ブロードキャストから電池状態を読む。権限不要・即時取得可。
 * 変化が緩やかなので呼び出し側は30秒程度のポーリングで十分。
 */
object BatteryMonitor {

    fun getInfo(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(percent, charging)
    }
}
