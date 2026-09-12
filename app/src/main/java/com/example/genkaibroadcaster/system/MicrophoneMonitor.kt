package com.example.genkaibroadcaster.system

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 配信に使われている (と推定される) マイクの情報。
 *
 * @param typeLabel 種別の表示名 (例: 内蔵マイク / USB マイク)
 * @param productName 製品名。内蔵マイクや取得不能時は空
 */
data class MicrophoneInfo(
    val typeLabel: String,
    val productName: String
) {
    /** 1行表示用。USB・有線は製品名を添える */
    fun label(): String =
        if (productName.isBlank()) typeLabel else "$typeLabel ($productName)"
}

/**
 * 接続中の入力デバイスから、OS が既定で選ぶマイクを推定する。
 *
 * アプリは `setPreferredDevice` を使わず OS のルーティングに任せているため、
 * 実際の選択先は AudioPolicy の入力優先順 (有線ヘッドセット > USB ヘッドセット > USB 機器 > 内蔵)
 * をなぞって推定する。Bluetooth (SCO/LE) はアプリ側で開始しない限り入力に使われないので対象外。
 * 権限不要・即時取得可。抜き差しは [register] のコールバックで検知する。
 */
object MicrophoneMonitor {

    /** 優先順 (先頭ほど優先) と表示名 */
    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET to "有線マイク",
        AudioDeviceInfo.TYPE_USB_HEADSET to "USB マイク",
        AudioDeviceInfo.TYPE_USB_DEVICE to "USB マイク",
        AudioDeviceInfo.TYPE_USB_ACCESSORY to "USB マイク",
        AudioDeviceInfo.TYPE_BUILTIN_MIC to "内蔵マイク",
    )

    fun getInfo(context: Context): MicrophoneInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return MicrophoneInfo("マイク不明", "")
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for ((type, label) in PRIORITY) {
            val device = inputs.firstOrNull { it.type == type } ?: continue
            // 内蔵マイクの productName は端末名なので出さない
            val name = if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "" else device.productName.toString()
            return MicrophoneInfo(label, name)
        }
        return MicrophoneInfo("マイク不明", "")
    }

    /**
     * 入力デバイスの抜き差しで [onChange] を呼ぶ。戻り値を [unregister] に渡して解除する。
     * コールバックはメインスレッドで呼ばれる。
     */
    fun register(context: Context, onChange: () -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChange()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChange()
        }
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.registerAudioDeviceCallback(callback, null)
        return callback
    }

    fun unregister(context: Context, callback: AudioDeviceCallback) {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.unregisterAudioDeviceCallback(callback)
    }
}
