package com.streamvault.app.sportswall

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class SportsWallApiRecordingParsingTest {
    @Test
    fun parsesExplicitInProgressFlag() {
        val recording = parseSportsWallRecording(
            Json.parseToJsonElement(
                """{
                    "recordingId": "866",
                    "title": "49ers vs Raiders",
                    "playbackUrl": "http://10.217.0.120:8089/dvr/files/866/hls/master.m3u8",
                    "inProgress": true
                }""".trimIndent()
            ).jsonObject
        )

        assertThat(recording.inProgress).isTrue()
    }

    @Test
    fun defaultsMissingInProgressFlagToCompletedForCompatibility() {
        val recording = parseSportsWallRecording(
            Json.parseToJsonElement(
                """{
                    "recordingId": "865",
                    "title": "Completed game",
                    "playbackUrl": "http://10.217.0.120:8089/dvr/files/865/hls/master.m3u8"
                }""".trimIndent()
            ).jsonObject
        )

        assertThat(recording.inProgress).isFalse()
    }

    @Test
    fun rejectsNonBooleanInProgressFlag() {
        val error = runCatching {
            parseSportsWallRecording(
                Json.parseToJsonElement(
                    """{
                        "recordingId": "866",
                        "title": "49ers vs Raiders",
                        "playbackUrl": "http://10.217.0.120:8089/dvr/files/866/hls/master.m3u8",
                        "inProgress": "true"
                    }""".trimIndent()
                ).jsonObject
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SportsWallControlException::class.java)
        assertThat((error as SportsWallControlException).code).isEqualTo("invalid_inProgress")
    }
}
