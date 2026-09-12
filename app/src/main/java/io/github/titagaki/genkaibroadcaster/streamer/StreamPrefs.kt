package io.github.titagaki.genkaibroadcaster.streamer

import android.content.SharedPreferences

/**
 * 配信設定の SharedPreferences 読み書きを集約する。
 *
 * UI層はこのオブジェクト経由でのみ設定に触ること。
 * 生のキー文字列をUI層に書かない。
 */
object StreamPrefs {
    private const val KEY_DESTINATIONS = "destinations"
    private const val KEY_ACTIVE_DESTINATION = "active_destination"
    private const val KEY_RES_INDEX = "res_index"
    private const val KEY_BITRATE_KBPS = "bitrate_kbps"
    private const val KEY_FPS = "video_fps"
    private const val KEY_PORTRAIT = "stream_portrait"
    private const val KEY_SOFTWARE_ENCODER = "software_encoder"

    /** 旧版のキー。複数接続先 (destinations) へ移行後に削除される */
    private const val KEY_LEGACY_URL = "rtmp_url"
    private const val KEY_LEGACY_SERVER = "rtmp_server"
    private const val KEY_LEGACY_STREAM_KEY = "stream_key"

    /**
     * 旧版 (単一の rtmp_url、または rtmp_server + stream_key) から複数接続先へ移行する。
     * destinations が無い場合だけ実行し、旧値を1件目の接続先として取り込む。
     */
    private fun migrateLegacy(prefs: SharedPreferences) {
        if (prefs.contains(KEY_DESTINATIONS)) return

        val legacyUrl = prefs.getString(KEY_LEGACY_URL, "").orEmpty()
        val server = prefs.getString(KEY_LEGACY_SERVER, null)
            ?: legacyUrl.substringBeforeLast("/", "").ifEmpty { StreamConfig.DEFAULT_SERVER }
        val key = prefs.getString(KEY_LEGACY_STREAM_KEY, null)
            ?: legacyUrl.substringAfterLast("/", "")
        val first = StreamDestination(
            StreamDestination.newId(), StreamConfig.DEFAULT_DESTINATION_NAME, server, key
        )
        prefs.edit()
            .putString(KEY_DESTINATIONS, StreamDestination.listToJson(listOf(first)))
            .putString(KEY_ACTIVE_DESTINATION, first.id)
            .remove(KEY_LEGACY_URL)
            .remove(KEY_LEGACY_SERVER)
            .remove(KEY_LEGACY_STREAM_KEY)
            .apply()
    }

    fun loadDestinations(prefs: SharedPreferences): List<StreamDestination> {
        migrateLegacy(prefs)
        return StreamDestination.listFromJson(prefs.getString(KEY_DESTINATIONS, "").orEmpty())
    }

    /** 保存済みIDが一覧に無ければ先頭を採用する。一覧が空なら null */
    fun loadActiveDestinationId(prefs: SharedPreferences): String? {
        val list = loadDestinations(prefs)
        val saved = prefs.getString(KEY_ACTIVE_DESTINATION, null)
        return list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
    }

    fun loadActiveDestination(prefs: SharedPreferences): StreamDestination? {
        val id = loadActiveDestinationId(prefs) ?: return null
        return loadDestinations(prefs).firstOrNull { it.id == id }
    }

    fun saveDestinations(prefs: SharedPreferences, list: List<StreamDestination>, activeId: String?) {
        prefs.edit()
            .putString(KEY_DESTINATIONS, StreamDestination.listToJson(list))
            .putString(KEY_ACTIVE_DESTINATION, activeId)
            .apply()
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

    fun loadSoftwareEncoder(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SOFTWARE_ENCODER, StreamConfig.DEFAULT_SOFTWARE_ENCODER)

    fun saveSoftwareEncoder(prefs: SharedPreferences, software: Boolean) {
        prefs.edit().putBoolean(KEY_SOFTWARE_ENCODER, software).apply()
    }

    fun saveVideo(prefs: SharedPreferences, resIndex: Int, bitrateKbps: Int, fps: Int) {
        prefs.edit()
            .putInt(KEY_RES_INDEX, resIndex)
            .putInt(KEY_BITRATE_KBPS, bitrateKbps)
            .putInt(KEY_FPS, fps)
            .apply()
    }

    /** 選択中の接続先のフルURL。接続先が無ければ空文字 */
    fun buildFullUrl(prefs: SharedPreferences): String =
        loadActiveDestination(prefs)?.fullUrl().orEmpty()

    /** 現在対応しているRTMP URL規則。詳細なURL検証とは分けて扱う。 */
    fun isAcceptedRtmpUrl(url: String): Boolean = url.startsWith("rtmp://")
}
