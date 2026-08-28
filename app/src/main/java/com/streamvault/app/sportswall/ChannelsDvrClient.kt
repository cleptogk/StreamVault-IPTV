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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ChannelsDvrRecording(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val inProgress: Boolean = false,
    val playable: Boolean = true,
    val corrupted: Boolean = false
)

data class ChannelsDvrPlaybackIdentity(
    val serverAddress: String,
    val recordingId: String
)

data class ChannelsDvrPlaybackState(
    val positionMs: Long,
    val watched: Boolean,
    val durationMs: Long? = null,
    val inProgress: Boolean = false
)

internal fun ChannelsDvrPlaybackState.shouldOfferResume(): Boolean =
    !watched && positionMs > 5_000L

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
    private val channelsHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun listRecordings(serverAddress: String): List<ChannelsDvrRecording> = withContext(Dispatchers.IO) {
        val baseUrl = ChannelsDvrAddressPolicy.normalize(serverAddress)
        val url = "$baseUrl/api/v1/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("source", "recordings")
            .addQueryParameter("sort", "date_added")
            .addQueryParameter("order", "desc")
            .build()
        val request = Request.Builder().url(url).get().build()
        channelsHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Channels DVR returned HTTP ${response.code}")
            }
            val payload = response.body?.string()
                ?: throw IllegalStateException("Channels DVR returned an empty response")
            val root = json.parseToJsonElement(payload)
            val rows = recordingRows(root)
            val activeFileIds = if (rows.any(::isIncompleteRecording)) {
                runCatching { loadActiveFileIds(baseUrl) }.getOrDefault(emptySet())
            } else {
                emptySet()
            }
            rows.mapNotNull { parseRecording(it, activeFileIds) }
        }
    }

    fun playbackIdentity(playbackUrl: String): ChannelsDvrPlaybackIdentity? {
        val uri = runCatching { URI(playbackUrl.trim()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.fragment != null) return null
        val baseUrl = runCatching {
            ChannelsDvrAddressPolicy.normalize("${uri.scheme}://${uri.host}:${uri.port}")
        }.getOrNull() ?: return null
        val match = Regex(
            "^/dvr/files/([A-Za-z0-9_.-]{1,128})/(?:hls/(?:master|stream)\\.m3u8|m3u8|stream\\.mpg)$"
        )
            .matchEntire(uri.path.orEmpty()) ?: return null
        val nativeCopyHls = uri.path.endsWith("/hls/stream.m3u8")
        val expectedNativeCopyQuery = "acodec=aac&indexed=true&ssize=1&vcodec=copy"
        if (
            (nativeCopyHls && uri.rawQuery != expectedNativeCopyQuery) ||
            (!nativeCopyHls && !uri.rawQuery.isNullOrBlank())
        ) return null
        val recordingId = match.groupValues[1]
        if (!isValidRecordingId(recordingId)) return null
        return ChannelsDvrPlaybackIdentity(
            serverAddress = baseUrl,
            recordingId = recordingId
        )
    }

    suspend fun loadPlaybackState(playbackUrl: String): ChannelsDvrPlaybackState? = withContext(Dispatchers.IO) {
        val identity = playbackIdentity(playbackUrl) ?: return@withContext null
        val request = Request.Builder()
            .url("${identity.serverAddress}/api/v1/episodes/${identity.recordingId}")
            .get()
            .build()
        channelsHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Channels DVR returned HTTP ${response.code}")
            }
            val payload = response.body?.string()
                ?: throw IllegalStateException("Channels DVR returned an empty response")
            parsePlaybackState(json.parseToJsonElement(payload))
        }
    }

    suspend fun updatePlaybackPosition(playbackUrl: String, positionMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val identity = playbackIdentity(playbackUrl) ?: return@withContext false
            val request = playbackProgressRequest(identity, positionMs)
            channelsHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Channels DVR returned HTTP ${response.code}")
                }
            }
            true
        }

    internal fun parsePlaybackState(element: JsonElement): ChannelsDvrPlaybackState? {
        val row = element as? JsonObject ?: return null
        val watched = row.boolean("watched") ?: row.boolean("Watched") ?: false
        val seconds = row.number("playback_time") ?: row.number("PlaybackTime") ?: 0.0
        if (!seconds.isFinite()) return null
        val durationSeconds = row.number("duration") ?: row.number("Duration")
        val durationMs = durationSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 1_000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() }
        return ChannelsDvrPlaybackState(
            positionMs = if (watched) 0L else {
                (seconds.coerceAtLeast(0.0) * 1_000.0)
                    .coerceAtMost(Long.MAX_VALUE.toDouble())
                    .toLong()
            },
            watched = watched,
            durationMs = durationMs,
            inProgress = row.boolean("completed") == false || row.boolean("processed") == false
        )
    }

    internal fun playbackProgressRequest(
        identity: ChannelsDvrPlaybackIdentity,
        positionMs: Long
    ): Request {
        val wholeSeconds = positionMs.coerceAtLeast(0L) / 1_000L
        return Request.Builder()
            .url("${identity.serverAddress}/dvr/files/${identity.recordingId}/playback_time/$wholeSeconds")
            .put(ByteArray(0).toRequestBody(null))
            .build()
    }

    fun toSportsWallRecording(
        serverAddress: String,
        recording: ChannelsDvrRecording
    ): SportsWallRecording {
        require(recording.playable) { "The Channels DVR recording is not currently playable" }
        val baseUrl = ChannelsDvrAddressPolicy.normalize(serverAddress)
        val result = SportsWallRecording(
            id = recording.id,
            title = recording.title,
            playbackUrl = "$baseUrl/dvr/files/${recording.id}/hls/master.m3u8",
            inProgress = recording.inProgress
        )
        return SportsWallRecordingPolicy.preferNativeVideo(result)
    }

    private fun loadActiveFileIds(baseUrl: String): Set<String> {
        val request = Request.Builder().url("$baseUrl/dvr/jobs").get().build()
        return channelsHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Channels DVR returned HTTP ${response.code}")
            }
            val payload = response.body?.string()
                ?: throw IllegalStateException("Channels DVR returned an empty jobs response")
            val root = json.parseToJsonElement(payload)
            parseActiveFileIds(root)
        }
    }

    internal fun parseActiveFileIds(
        root: JsonElement,
        nowSeconds: Double = System.currentTimeMillis() / 1_000.0
    ): Set<String> {
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["jobs"] ?: root["items"]) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return rows.mapNotNull { element ->
            val job = element as? JsonObject ?: return@mapNotNull null
            val start = job.number("Time") ?: return@mapNotNull null
            val duration = job.number("Duration") ?: return@mapNotNull null
            val fileId = job.string("FileID") ?: return@mapNotNull null
            fileId.takeIf {
                start <= nowSeconds + 60.0 &&
                    nowSeconds <= start + duration + 60.0 &&
                    job.boolean("Skipped") != true &&
                    job.boolean("Failed") != true &&
                    job.boolean("Dead") != true &&
                    job.string("Error").orEmpty().isBlank()
            }
        }.toSet()
    }

    private fun recordingRows(root: JsonElement): JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["items"] ?: root["episodes"]) as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }

    internal fun parseRecording(
        element: JsonElement,
        activeFileIds: Set<String> = emptySet()
    ): ChannelsDvrRecording? {
        val row = element as? JsonObject ?: return null
        // Channels can set `corrupted` after a single bad frame even though the
        // recording remains playable. Keep it as server metadata and gate only on
        // lifecycle fields that actually make the recording unusable.
        if (row.boolean("cancelled") == true) return null
        val id = row.string("id") ?: row.string("ID") ?: row.string("recording_id") ?: return null
        if (!isValidRecordingId(id)) return null
        val incomplete = row.boolean("completed") == false || row.boolean("processed") == false
        val inProgress = incomplete && id in activeFileIds
        val title = row.string("event_title") ?: row.string("title") ?: row.string("name") ?: id
        val cleanTitle = title.replace('\n', ' ').replace('\r', ' ').trim().take(256)
        if (cleanTitle.isBlank()) return null
        val subtitle = (row.string("episode_title") ?: row.string("subtitle"))
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return ChannelsDvrRecording(
            id = id,
            title = cleanTitle,
            subtitle = subtitle,
            inProgress = inProgress,
            playable = !incomplete || inProgress,
            corrupted = row.boolean("corrupted") == true
        )
    }

    private fun isIncompleteRecording(element: JsonElement): Boolean {
        val row = element as? JsonObject ?: return false
        return row.boolean("completed") == false || row.boolean("processed") == false
    }

    private fun isValidRecordingId(id: String): Boolean =
        id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && id !in setOf(".", "..")

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull
}
