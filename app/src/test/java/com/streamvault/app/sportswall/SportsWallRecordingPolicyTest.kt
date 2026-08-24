package com.streamvault.app.sportswall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsWallRecordingPolicyTest {
    @Test
    fun acceptsMatchingChannelsDvrHlsPath() {
        SportsWallRecordingPolicy.validate(
            SportsWallRecording("865", "Game", "http://10.217.0.120:8089/dvr/files/865/hls/master.m3u8")
        )
    }

    @Test
    fun rewritesMasterPlaylistToNativeVideoCopyPlaylist() {
        val recording = SportsWallRecordingPolicy.preferNativeVideo(
            SportsWallRecording("865", "Game", "http://10.217.0.120:8089/dvr/files/865/hls/master.m3u8")
        )

        assertThat(recording.playbackUrl).isEqualTo(
            "http://10.217.0.120:8089/dvr/files/865/hls/stream.m3u8?acodec=aac&indexed=true&ssize=1&vcodec=copy"
        )
    }

    @Test
    fun rejectsUnapprovedNativePlaylistParameters() {
        val error = runCatching {
            SportsWallRecordingPolicy.validate(
                SportsWallRecording(
                    "865",
                    "Game",
                    "http://10.217.0.120:8089/dvr/files/865/hls/stream.m3u8?vcodec=copy"
                )
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
    }

    @Test
    fun rejectsArbitraryLanUrl() {
        val error = runCatching {
            SportsWallRecordingPolicy.validate(
                SportsWallRecording("865", "Game", "http://10.217.0.120:8089/api/v1/episodes")
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
    }

    @Test
    fun rejectsMismatchedRecordingId() {
        val error = runCatching {
            SportsWallRecordingPolicy.validate(
                SportsWallRecording("865", "Game", "http://10.217.0.120:8089/dvr/files/999/m3u8")
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
    }

    @Test
    fun normalizesPrivateChannelsDvrServerAddress() {
        assertThat(ChannelsDvrAddressPolicy.normalize("http://10.217.0.120:8089/"))
            .isEqualTo("http://10.217.0.120:8089")
    }

    @Test
    fun rejectsChannelsDvrAddressWithUnexpectedPathOrPort() {
        assertThat(
            runCatching {
                ChannelsDvrAddressPolicy.normalize("http://10.217.0.120:8090/api/v1/episodes")
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
