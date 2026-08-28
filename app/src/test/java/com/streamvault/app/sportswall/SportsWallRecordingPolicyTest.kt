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
    fun rewritesInProgressRecordingToIndexedNativePlaylist() {
        val recording = SportsWallRecordingPolicy.preferNativeVideo(
            SportsWallRecording(
                id = "866",
                title = "Game in progress",
                playbackUrl = "http://10.217.0.120:8089/dvr/files/866/hls/master.m3u8",
                inProgress = true
            )
        )

        assertThat(recording.playbackUrl).isEqualTo(
            "http://10.217.0.120:8089/dvr/files/866/hls/stream.m3u8?" +
                "acodec=aac&indexed=true&ssize=1&vcodec=copy"
        )
        assertThat(recording.inProgress).isTrue()
    }

    @Test
    fun acceptsMatchingDirectGrowingStreamForInProgressRecording() {
        SportsWallRecordingPolicy.validate(
            SportsWallRecording(
                id = "866",
                title = "Game in progress",
                playbackUrl = "http://10.217.0.120:8089/dvr/files/866/stream.mpg",
                inProgress = true
            )
        )
    }

    @Test
    fun rejectsDirectGrowingStreamForCompletedRecording() {
        val error = runCatching {
            SportsWallRecordingPolicy.validate(
                SportsWallRecording(
                    id = "866",
                    title = "Completed game",
                    playbackUrl = "http://10.217.0.120:8089/dvr/files/866/stream.mpg"
                )
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
    }

    @Test
    fun rejectsQueryParametersOnDirectGrowingStream() {
        val error = runCatching {
            SportsWallRecordingPolicy.validate(
                SportsWallRecording(
                    id = "866",
                    title = "Game in progress",
                    playbackUrl = "http://10.217.0.120:8089/dvr/files/866/stream.mpg?bitrate=8000",
                    inProgress = true
                )
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
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
