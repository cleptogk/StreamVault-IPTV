package com.streamvault.app.sportswall

import android.content.Context
import com.streamvault.app.BuildConfig
import fi.iki.elonen.NanoHTTPD
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class SportsWallApiServer(
    private val context: Context,
    private val controller: SportsWallControlPort,
    port: Int = PORT
) : NanoHTTPD(BIND_ADDRESS, port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        if (!SportsWallApiSecurityPolicy.isAllowedRemoteAddress(session.remoteIpAddress)) {
            return error(Response.Status.FORBIDDEN, "lan_only", "Requests are restricted to the home LAN")
        }

        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        if (path == "/v1/health" && session.method == Method.GET) {
            return success(
                buildJsonObject {
                    put("status", "ok")
                    put("apiVersion", 1)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("authenticationRequired", true)
                }
            )
        }

        if (!SportsWallApiCredentials.authenticate(context, session.headers["authorization"])) {
            return error(Response.Status.UNAUTHORIZED, "unauthorized", "A valid bearer token is required")
                .also { it.addHeader("WWW-Authenticate", "Bearer") }
        }

        return try {
            runBlocking { route(session, path) }
        } catch (exception: SportsWallControlException) {
            error(Response.Status.BAD_REQUEST, exception.code, exception.message)
        } catch (_: IllegalArgumentException) {
            error(Response.Status.BAD_REQUEST, "invalid_request", "The request body or parameters are invalid")
        } catch (_: Exception) {
            error(Response.Status.INTERNAL_ERROR, "internal_error", "The Sports Wall request could not be completed")
        }
    }

    private suspend fun route(session: IHTTPSession, path: String): Response {
        if (path == "/v1/state" && session.method == Method.GET) {
            return success(controller.state().toJson())
        }
        if (path == "/v1/channels/search" && session.method == Method.GET) {
            val query = session.parameters["q"]?.firstOrNull().orEmpty()
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val channels = controller.searchChannels(query, limit)
            return success(buildJsonObject {
                put("query", query)
                put("count", channels.size)
                put("channels", buildJsonArray { channels.forEach { add(it.toJson()) } })
            })
        }
        if (path == "/v1/layout" && session.method == Method.PUT) {
            val body = session.readJsonBody()
            val ids = body.requiredArray("channelIds").map { element ->
                when (element) {
                    JsonNull -> null
                    else -> element.jsonPrimitive.longOrNull
                        ?: throw SportsWallControlException("invalid_layout", "Each layout entry must be a channel ID or null")
                }
            }
            return success(controller.setLayout(ids, body.launchRequested()).toJson())
        }

        val paneMatch = PANE_PATH.matchEntire(path)
        if (paneMatch != null) {
            val pane = paneMatch.groupValues[1].toInt()
            return when (session.method) {
                Method.PUT -> {
                    val body = session.readJsonBody()
                    val channelId = body.requiredLong("channelId")
                    success(controller.assignPane(pane, channelId, body.launchRequested()).toJson())
                }
                Method.DELETE -> success(controller.clearPane(pane).toJson())
                else -> error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed", "Use PUT or DELETE for pane assignments")
            }
        }

        val recordingPaneMatch = RECORDING_PANE_PATH.matchEntire(path)
        if (recordingPaneMatch != null && session.method == Method.PUT) {
            val body = session.readJsonBody()
            return success(
                controller.assignRecording(
                    recordingPaneMatch.groupValues[1].toInt(),
                    body.requiredRecording(),
                    body.launchRequested()
                ).toJson()
            )
        }

        if (path == "/v1/audio" && session.method == Method.PUT) {
            val body = session.readJsonBody()
            val paneElement = body["pane"]
                ?: throw SportsWallControlException("missing_pane", "The pane field is required; use null to follow focus")
            val pane = if (paneElement is JsonNull) null else paneElement.jsonPrimitive.intOrNull
                ?: throw SportsWallControlException("invalid_pane", "Pane must be between 1 and 4 or null")
            return success(controller.selectAudioPane(pane).toJson())
        }

        if (path == "/v1/performance" && session.method == Method.PUT) {
            val mode = session.readJsonBody().requiredString("mode")
            return success(controller.setPerformanceMode(mode).toJson())
        }

        val presetMatch = PRESET_PATH.matchEntire(path)
        if (presetMatch != null && session.method == Method.POST) {
            val preset = presetMatch.groupValues[1].toInt()
            val action = presetMatch.groupValues[2]
            return success(
                when (action) {
                    "save" -> controller.savePreset(preset)
                    "load" -> controller.loadPreset(preset, session.readOptionalJsonBody().launchRequested())
                    else -> error("invalid_preset_action")
                }.toJson()
            )
        }

        if (path == "/v1/fullscreen" && session.method == Method.POST) {
            controller.openFullscreen(session.readJsonBody().requiredInt("pane"))
            return success(buildJsonObject { put("status", "fullscreen_requested") })
        }
        if (path == "/v1/playback/pause" && session.method == Method.POST) {
            return success(controller.pauseAll().toJson())
        }
        if (path == "/v1/playback/resume" && session.method == Method.POST) {
            return success(controller.resumeAll().toJson())
        }
        if (path == "/v1/recordings/fullscreen" && session.method == Method.POST) {
            controller.openRecordingFullscreen(session.readJsonBody().requiredRecording())
            return success(buildJsonObject { put("status", "fullscreen_requested") })
        }
        if (path == "/v1/restore" && session.method == Method.POST) {
            controller.restoreMultiView()
            return success(buildJsonObject { put("status", "multiview_requested") })
        }
        if (path == "/v1/launch" && session.method == Method.POST) {
            controller.restoreMultiView()
            return success(controller.state().toJson())
        }

        return error(Response.Status.NOT_FOUND, "not_found", "No Sports Wall endpoint matches this request")
    }

    private fun IHTTPSession.readJsonBody(): JsonObject {
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length > MAX_BODY_BYTES) {
            throw SportsWallControlException("body_too_large", "Request bodies are limited to $MAX_BODY_BYTES bytes")
        }
        val files = HashMap<String, String>()
        parseBody(files)
        val raw = files["postData"]
            ?: files["content"]?.let { temporaryPath -> File(temporaryPath).readText() }
            ?: ""
        if (raw.isBlank()) throw SportsWallControlException("missing_body", "A JSON request body is required")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw SportsWallControlException("body_too_large", "Request bodies are limited to $MAX_BODY_BYTES bytes")
        }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun IHTTPSession.readOptionalJsonBody(): JsonObject {
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        return if (length == 0L) JsonObject(emptyMap()) else readJsonBody()
    }

    private fun JsonObject.requiredArray(key: String): JsonArray = get(key)?.jsonArray
        ?: throw SportsWallControlException("missing_$key", "The $key field is required")

    private fun JsonObject.requiredLong(key: String): Long = get(key)?.jsonPrimitive?.longOrNull
        ?: throw SportsWallControlException("missing_$key", "The $key field must be an integer")

    private fun JsonObject.requiredInt(key: String): Int = get(key)?.jsonPrimitive?.intOrNull
        ?: throw SportsWallControlException("missing_$key", "The $key field must be an integer")

    private fun JsonObject.requiredString(key: String): String = get(key)?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw SportsWallControlException("missing_$key", "The $key field must be a non-empty string")

    private fun JsonObject.requiredRecording(): SportsWallRecording = SportsWallRecording(
        id = requiredString("recordingId"),
        title = requiredString("title"),
        playbackUrl = requiredString("playbackUrl")
    )

    private fun JsonObject.launchRequested(): Boolean = get("launch")?.jsonPrimitive?.booleanOrNull ?: true

    private fun success(payload: JsonElement): Response = jsonResponse(Response.Status.OK, payload)

    private fun error(status: Response.Status, code: String, message: String): Response = jsonResponse(
        status,
        buildJsonObject {
            put("error", code)
            put("message", message)
        }
    )

    private fun error(code: String): Nothing = throw SportsWallControlException(code, "Unsupported request")

    private fun jsonResponse(status: Response.Status, payload: JsonElement): Response = newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        payload.toString()
    ).also { response ->
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
    }

    companion object {
        const val PORT = 8789
        private const val BIND_ADDRESS = "0.0.0.0"
        private const val MAX_BODY_BYTES = 16 * 1024
        private val PANE_PATH = Regex("/v1/panes/([1-4])")
        private val RECORDING_PANE_PATH = Regex("/v1/panes/([1-4])/recording")
        private val PRESET_PATH = Regex("/v1/presets/([1-3])/(save|load)")
    }
}

private fun SportsWallChannelSummary.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("number", number)
    if (category == null) put("category", JsonNull) else put("category", category)
    put("providerId", providerId)
    put("sourceType", sourceType)
    if (sourceId == null) put("sourceId", JsonNull) else put("sourceId", sourceId)
}

private fun SportsWallState.toJson(): JsonObject = buildJsonObject {
    put("layout", "2x2")
    put("focusedPane", focusedPane)
    if (audioPane == null) put("audioPane", JsonNull) else put("audioPane", audioPane)
    put("performanceMode", performanceMode)
    put("paused", paused)
    if (fullscreenPane == null) put("fullscreenPane", JsonNull) else put("fullscreenPane", fullscreenPane)
    put("panes", buildJsonArray {
        panes.forEach { pane ->
            add(buildJsonObject {
                put("pane", pane.pane)
                if (pane.channel == null) put("channel", JsonNull) else put("channel", pane.channel.toJson())
            })
        }
    })
}
