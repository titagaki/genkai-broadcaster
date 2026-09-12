package com.example.genkaibroadcaster.streamer

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
    private const val KEY_FPS = "video_fps"
    private const val KEY_PORTRAIT = "stream_portrait"

    /** 旧版の単一URLキー。移行後は削除される */
    private const val KEY_LEGACY_URL = "rtmp_url"

    // 旧版(rtmp_url単一欄)からの移行: "rtmp://host/live/key" を server/key に分割
    private fun migrateLegacy(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LEGACY_URL)) return

        val legacyUrl = prefs.getString(KEY_LEGACY_URL, "").orEmpty()
        val editor = prefs.edit()
        if (!prefs.contains(KEY_SERVER)) {
            editor.putString(
                KEY_SERVER,
                legacyUrl.substringBeforeLast("/", "").ifEmpty { StreamConfig.DEFAULT_SERVER }
            )
        }
        if (!prefs.contains(KEY_STREAM_KEY)) {
            editor.putString(KEY_STREAM_KEY, legacyUrl.substringAfterLast("/", ""))
        }
        editor.remove(KEY_LEGACY_URL).apply()
    }

    fun loadServer(prefs: SharedPreferences): String {
        migrateLegacy(prefs)
        return prefs.getString(KEY_SERVER, StreamConfig.DEFAULT_SERVER).orEmpty()
    }

    fun loadKey(prefs: SharedPreferences): String {
        migrateLegacy(prefs)
        return prefs.getString(KEY_STREAM_KEY, "").orEmpty()
    }

    fun loadResIndex(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_RES_INDEX, StreamConfig.DEFAULT_RES_INDEX).coerceIn(RESOLUTIONS.indices)

    fun loadBitrateKbps(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_BITRATE_KBPS, StreamConfig.DEFAULT_BITRATE_KBPS)

    /** 選択肢にない値が保存されていた場合は既定値へ戻す */
    fun loadFps(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_FPS, StreamConfig.DEFAULT_VIDEO_FPS)
            .takeIf { it in StreamConfig.FPS_OPTIONS } ?: StreamConfig.DEFAULT_VIDEO_FPS

    fun loadPortrait(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PORTRAIT, StreamConfig.DEFAULT_PORTRAIT)

    fun savePortrait(prefs: SharedPreferences, portrait: Boolean) {
        prefs.edit().putBoolean(KEY_PORTRAIT, portrait).apply()
    }

    fun save(
        prefs: SharedPreferences,
        server: String,
        key: String,
        resIndex: Int,
        bitrateKbps: Int,
        fps: Int
    ) {
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_STREAM_KEY, key)
            .putInt(KEY_RES_INDEX, resIndex)
            .putInt(KEY_BITRATE_KBPS, bitrateKbps)
            .putInt(KEY_FPS, fps)
            .remove(KEY_LEGACY_URL) // 旧形式は移行済みなので削除
            .apply()
    }

    /** サーバーURL + キー → フルURL (キーが空ならサーバーURLのみ) */
    fun buildFullUrl(prefs: SharedPreferences): String {
        val server = loadServer(prefs).trim().trimEnd('/')
        val key = loadKey(prefs).trim().trim('/')
        return if (key.isEmpty()) server else "$server/$key"
    }

    /** 現在対応しているRTMP URL規則。詳細なURL検証とは分けて扱う。 */
    fun isAcceptedRtmpUrl(url: String): Boolean = url.startsWith("rtmp://")
}
