package com.example.genkaibroadcaster.streamer

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 接続先 (RTMPサーバーURL + ストリームキー) の1件。
 * 複数保存して切り替える。保存形式は [StreamPrefs] が JSON で扱う。
 */
data class StreamDestination(
    val id: String,
    val name: String,
    val server: String,
    val key: String
) {
    /** サーバーURL + キー → フルURL (キーが空ならサーバーURLのみ) */
    fun fullUrl(): String {
        val s = server.trim().trimEnd('/')
        val k = key.trim().trim('/')
        return if (k.isEmpty()) s else "$s/$k"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("server", server).put("key", key)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(json: JSONObject): StreamDestination = StreamDestination(
            id = json.optString("id").ifEmpty { newId() },
            name = json.optString("name"),
            server = json.optString("server"),
            key = json.optString("key")
        )

        fun listFromJson(text: String): List<StreamDestination> = runCatching {
            val array = JSONArray(text)
            List(array.length()) { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<StreamDestination>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

/**
 * 接続先の入力例。選ぶと新しい接続先が作られ、キー以外が埋まった状態で編集に入る。
 * サーバーURLの定数は [StreamConfig] に置く。
 */
enum class DestinationPreset(val label: String, val server: String) {
    GATEWAY("PeerCast Gateway", StreamConfig.DEFAULT_SERVER),
    TWITCH("Twitch", StreamConfig.TWITCH_SERVER);

    fun create(): StreamDestination =
        StreamDestination(StreamDestination.newId(), label, server, key = "")
}
