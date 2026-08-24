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
}
