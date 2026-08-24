package com.streamvault.app.sportswall

import java.net.URI

internal object SportsWallRecordingPolicy {
    private val recordingIdPattern = Regex("[A-Za-z0-9_.-]{1,128}")
    private val playbackPathPattern = Regex("/dvr/files/([A-Za-z0-9_.-]{1,128})/(?:hls/(?:master|stream)\\.m3u8|m3u8)")
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
        if (
            uri.scheme !in setOf("http", "https") || !allowedHost || uri.port != 8089 ||
            uri.userInfo != null || uri.fragment != null ||
            (uri.path.endsWith("/stream.m3u8") && uri.rawQuery != NATIVE_COPY_QUERY) ||
            (!uri.path.endsWith("/stream.m3u8") && !uri.query.isNullOrBlank()) ||
            pathId != recording.id
        ) {
            throw SportsWallControlException(
                "invalid_recording_url",
                "Playback must use the matching Channels DVR HLS recording path on the home LAN"
            )
        }
    }

    /**
     * Channels DVR advertises its lossless video-copy rendition with an unknown
     * resolution (0x0), so adaptive selection can prefer a lower transcoded
     * 1080p entry. Point playback at the single native video-copy playlist.
     */
    fun preferNativeVideo(recording: SportsWallRecording): SportsWallRecording {
        validate(recording)
        val uri = URI(recording.playbackUrl)
        return recording.copy(
            playbackUrl = "${uri.scheme}://${uri.host}:${uri.port}/dvr/files/${recording.id}/hls/stream.m3u8?$NATIVE_COPY_QUERY"
        )
    }
}
