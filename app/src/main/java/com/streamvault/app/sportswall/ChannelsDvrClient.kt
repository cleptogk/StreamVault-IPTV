package com.streamvault.app.sportswall

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class ChannelsDvrRecording(
    val id: String,
    val title: String,
    val subtitle: String? = null
)

internal object ChannelsDvrAddressPolicy {
    private val lanHostPattern = Regex("10\\.217\\.0\\.[0-9]{1,3}")

    fun normalize(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid Channels DVR address")
        val allowedHost = uri.host?.matches(lanHostPattern) == true
        if (
            uri.scheme !in setOf("http", "https") || !allowedHost || uri.port != 8089 ||
            uri.userInfo != null || uri.fragment != null || !uri.query.isNullOrBlank() ||
            uri.path !in setOf("", "/")
        ) {
            throw IllegalArgumentException("Use the private LAN DVR address on port 8089")
        }
        return "${uri.scheme}://${uri.host}:${uri.port}"
    }
}

@Singleton
class ChannelsDvrClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listRecordings(serverAddress: String): List<ChannelsDvrRecording> = withContext(Dispatchers.IO) {
        val baseUrl = ChannelsDvrAddressPolicy.normalize(serverAddress)
        val url = "$baseUrl/api/v1/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("source", "recordings")
            .addQueryParameter("sort", "date_added")
            .addQueryParameter("order", "desc")
            .build()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Channels DVR returned HTTP ${response.code}")
            }
            val payload = response.body?.string()
                ?: throw IllegalStateException("Channels DVR returned an empty response")
            val root = json.parseToJsonElement(payload)
            recordingRows(root).mapNotNull(::parseRecording).take(500)
        }
    }

    fun toSportsWallRecording(
        serverAddress: String,
        recording: ChannelsDvrRecording
    ): SportsWallRecording {
        val baseUrl = ChannelsDvrAddressPolicy.normalize(serverAddress)
        val result = SportsWallRecording(
            id = recording.id,
            title = recording.title,
            playbackUrl = "$baseUrl/dvr/files/${recording.id}/hls/master.m3u8"
        )
        return SportsWallRecordingPolicy.preferNativeVideo(result)
    }

    private fun recordingRows(root: JsonElement): JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["items"] ?: root["episodes"]) as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    private fun parseRecording(element: JsonElement): ChannelsDvrRecording? {
        val row = element as? JsonObject ?: return null
        if (row.boolean("cancelled") == true || row.boolean("corrupted") == true) return null
        if (row.boolean("completed") == false || row.boolean("processed") == false) return null
        val id = row.string("id") ?: row.string("ID") ?: row.string("recording_id") ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) return null
        val title = row.string("event_title") ?: row.string("title") ?: row.string("name") ?: id
        val cleanTitle = title.replace('\n', ' ').replace('\r', ' ').trim().take(256)
        if (cleanTitle.isBlank()) return null
        val subtitle = (row.string("episode_title") ?: row.string("subtitle"))
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return ChannelsDvrRecording(id = id, title = cleanTitle, subtitle = subtitle)
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
}
