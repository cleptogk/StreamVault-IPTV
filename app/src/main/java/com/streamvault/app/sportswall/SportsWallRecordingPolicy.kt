package com.streamvault.app.sportswall

import java.net.URI

internal object SportsWallRecordingPolicy {
    private val recordingIdPattern = Regex("[A-Za-z0-9_.-]{1,128}")
    private val playbackPathPattern =
        Regex("/dvr/files/([A-Za-z0-9_.-]{1,128})/(?:hls/(?:master|stream)\\.m3u8|m3u8|stream\\.mpg)")
    private const val NATIVE_COPY_QUERY = "acodec=aac&indexed=true&ssize=1&vcodec=copy"

    fun validate(recording: SportsWallRecording) {
        if (!recordingIdPattern.matches(recording.id)) {
            throw SportsWallControlException("invalid_recording_id", "The Channels DVR recording ID is invalid")
        }
        if (recording.title.isBlank() || recording.title.length > 256 || recording.title.any { it == '\n' || it == '\r' }) {
            throw SportsWallControlException("invalid_recording_title", "The recording title must be 1 to 256 characters")
        }
        val uri = runCatching { URI(recording.playbackUrl) }.getOrNull()
            ?: throw SportsWallControlException("invalid_recording_url", "The Channels DVR playback URL is invalid")
        val pathId = playbackPathPattern.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
        val allowedHost = uri.host?.matches(Regex("10\\.217\\.0\\.[0-9]{1,3}")) == true
        val directGrowingStream = uri.path.endsWith("/stream.mpg")
        val nativeHlsStream = uri.path.endsWith("/stream.m3u8")
        if (
            uri.scheme !in setOf("http", "https") || !allowedHost || uri.port != 8089 ||
            uri.userInfo != null || uri.fragment != null ||
            (directGrowingStream && (!recording.inProgress || !uri.query.isNullOrBlank())) ||
            (nativeHlsStream && (recording.inProgress || uri.rawQuery != NATIVE_COPY_QUERY)) ||
            (!directGrowingStream && !nativeHlsStream && !uri.query.isNullOrBlank()) ||
            pathId != recording.id
        ) {
            throw SportsWallControlException(
                "invalid_recording_url",
                "Playback must use the matching Channels DVR HLS recording path on the home LAN"
            )
        }
    }

    /**
     * Completed recordings use Channels DVR's lossless native-copy HLS
     * rendition. In-progress recordings use the direct growing MPEG-TS path;
     * transcoded HLS cannot generate segments quickly enough to stay current.
     */
    fun preferNativeVideo(recording: SportsWallRecording): SportsWallRecording {
        validate(recording)
        val uri = URI(recording.playbackUrl)
        return recording.copy(
            playbackUrl = if (recording.inProgress) {
                "${uri.scheme}://${uri.host}:${uri.port}/dvr/files/${recording.id}/stream.mpg"
            } else {
                "${uri.scheme}://${uri.host}:${uri.port}/dvr/files/${recording.id}/hls/stream.m3u8?$NATIVE_COPY_QUERY"
            }
        )
    }
}
