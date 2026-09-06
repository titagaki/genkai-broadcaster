package com.example.hogebroadcaster.streamer

import android.content.SharedPreferences

/**
 * 配信設定の SharedPreferences 読み書きを集約する。
 *
 * UI層はこのオブジェクト経由でのみ設定に触ること。
 * 生のキー文字列をUI層に書かない。
 */
object StreamPrefs {
    private const val KEY_SERVER = "rtmp_server"
    private const val KEY_STREAM_KEY = "stream_key"
    private const val KEY_RES_INDEX = "res_index"
    private const val KEY_BITRATE_KBPS = "bitrate_kbps"

    /** 旧版の単一URLキー。移行後は削除される */
    private const val KEY_LEGACY_URL = "rtmp_url"

    // 旧版(rtmp_url単一欄)からの移行: "rtmp://host/live/key" を server/key に分割
    private fun legacy(prefs: SharedPreferences): String =
        prefs.getString(KEY_LEGACY_URL, "").orEmpty()

    fun loadServer(prefs: SharedPreferences): String =
        if (prefs.contains(KEY_SERVER)) prefs.getString(KEY_SERVER, "") ?: ""
        else legacy(prefs).substringBeforeLast("/", "").ifEmpty { StreamConfig.DEFAULT_SERVER }

    fun loadKey(prefs: SharedPreferences): String =
        if (prefs.contains(KEY_SERVER)) prefs.getString(KEY_STREAM_KEY, "") ?: ""
        else legacy(prefs).substringAfterLast("/", "")

    fun loadResIndex(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_RES_INDEX, StreamConfig.DEFAULT_RES_INDEX).coerceIn(RESOLUTIONS.indices)

    fun loadBitrateKbps(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BITRATE_KBPS, StreamConfig.DEFAULT_BITRATE_KBPS)

    fun save(
        prefs: SharedPreferences,
        server: String,
        key: String,
        resIndex: Int,
        bitrateKbps: Int
    ) {
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_STREAM_KEY, key)
            .putInt(KEY_RES_INDEX, resIndex)
            .putInt(KEY_BITRATE_KBPS, bitrateKbps)
            .remove(KEY_LEGACY_URL) // 旧形式は移行済みなので削除
            .apply()
    }

    /** サーバーURL + キー → フルURL (キーが空ならサーバーURLのみ) */
    fun buildFullUrl(prefs: SharedPreferences): String {
        val server = (prefs.getString(KEY_SERVER, "") ?: "").trim().trimEnd('/')
        val key = (prefs.getString(KEY_STREAM_KEY, "") ?: "").trim().trim('/')
        return if (key.isEmpty()) server else "$server/$key"
    }
}
