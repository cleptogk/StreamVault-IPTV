package com.streamvault.app.sportswall

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test

class ChannelsDvrClientTest {
    private val client = ChannelsDvrClient(OkHttpClient())

    @Test
    fun `corrupted marker does not hide an otherwise completed recording`() {
        val row = Json.parseToJsonElement(
            """{
                "id": "855",
                "event_title": "Bears vs Bengals",
                "corrupted": true,
                "completed": true,
                "processed": true
            }""".trimIndent()
        )

        assertThat(client.parseRecording(row)).isEqualTo(
            ChannelsDvrRecording(id = "855", title = "Bears vs Bengals", corrupted = true)
        )
    }

    @Test
    fun `cancelled recording remains hidden even when marked corrupted`() {
        val row = Json.parseToJsonElement(
            """{
                "id": "cancelled",
                "title": "Cancelled game",
                "corrupted": true,
                "cancelled": true,
                "completed": true,
                "processed": true
            }""".trimIndent()
        )

        assertThat(client.parseRecording(row)).isNull()
    }

    @Test
    fun `healthy active job makes incomplete recording playable in progress`() {
        val row = Json.parseToJsonElement(
            """{
                "id": "866",
                "event_title": "49ers vs Raiders",
                "completed": false,
                "processed": false,
                "corrupted": true
            }""".trimIndent()
        )

        assertThat(client.parseRecording(row, activeFileIds = setOf("866"))).isEqualTo(
            ChannelsDvrRecording(
                id = "866",
                title = "49ers vs Raiders",
                inProgress = true,
                playable = true,
                corrupted = true
            )
        )
    }

    @Test
    fun `incomplete recording without healthy active job remains visible but unavailable`() {
        val row = Json.parseToJsonElement(
            """{
                "id": "partial",
                "title": "Interrupted recording",
                "completed": false,
                "processed": false
            }""".trimIndent()
        )

        assertThat(client.parseRecording(row)).isEqualTo(
            ChannelsDvrRecording(
                id = "partial",
                title = "Interrupted recording",
                playable = false
            )
        )
    }

    @Test
    fun `active job parser rejects failed and errored jobs`() {
        val jobs = Json.parseToJsonElement(
            """[
                {"FileID":"866","Time":900,"Duration":1000,"Skipped":false,"Failed":false,"Dead":false,"Error":""},
                {"FileID":"failed","Time":900,"Duration":1000,"Failed":true},
                {"FileID":"errored","Time":900,"Duration":1000,"Error":"tuner lost"},
                {"FileID":"expired","Time":1,"Duration":10}
            ]""".trimIndent()
        )

        assertThat(client.parseActiveFileIds(jobs, nowSeconds = 1_000.0)).containsExactly("866")
    }

    @Test
    fun `completed and active recordings map to indexed native HLS`() {
        val completed = client.toSportsWallRecording(
            "http://10.217.0.120:8089",
            ChannelsDvrRecording(id = "865", title = "Completed")
        )
        val active = client.toSportsWallRecording(
            "http://10.217.0.120:8089",
            ChannelsDvrRecording(id = "866", title = "Active", inProgress = true)
        )

        assertThat(completed.playbackUrl).isEqualTo(
            "http://10.217.0.120:8089/dvr/files/865/hls/stream.m3u8?" +
                "acodec=aac&indexed=true&ssize=1&vcodec=copy"
        )
        assertThat(active.playbackUrl)
            .isEqualTo(
                "http://10.217.0.120:8089/dvr/files/866/hls/stream.m3u8?" +
                    "acodec=aac&indexed=true&ssize=1&vcodec=copy"
            )
    }

    @Test
    fun `playback identity accepts only private Channels recording urls`() {
        assertThat(
            client.playbackIdentity(
                "http://10.217.0.120:8089/dvr/files/866/hls/stream.m3u8?" +
                    "acodec=aac&indexed=true&ssize=1&vcodec=copy"
            )
        ).isEqualTo(
            ChannelsDvrPlaybackIdentity("http://10.217.0.120:8089", "866")
        )
        assertThat(client.playbackIdentity("https://example.com/dvr/files/866/hls/master.m3u8")).isNull()
        assertThat(client.playbackIdentity("http://10.217.0.120:8089/dvr/files/../settings")).isNull()
        assertThat(client.playbackIdentity("http://10.217.0.120:8089/dvr/files/866/preview.jpg")).isNull()
        assertThat(client.playbackIdentity("http://10.217.0.120:8089/dvr/files/866/hls/stream.m3u8?indexed=true")).isNull()
        assertThat(client.playbackIdentity("http://10.217.0.120:8089/dvr/files/866/stream.mpg?token=x")).isNull()
        assertThat(client.playbackIdentity("http://user@10.217.0.120:8089/dvr/files/866/stream.mpg")).isNull()
    }

    @Test
    fun `playback position parses seconds and progress update uses whole seconds`() {
        val progress = Json.parseToJsonElement(
            """{"playback_time":9277.75,"watched":false,"completed":false,"processed":false}"""
        )
        assertThat(client.parsePlaybackState(progress)).isEqualTo(
            ChannelsDvrPlaybackState(
                positionMs = 9_277_750L,
                watched = false,
                inProgress = true
            )
        )

        val request = client.playbackProgressRequest(
            ChannelsDvrPlaybackIdentity("http://10.217.0.120:8089", "866"),
            9_281_990L
        )
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.url.toString()).isEqualTo(
            "http://10.217.0.120:8089/dvr/files/866/playback_time/9281"
        )
    }

    @Test
    fun `watched recording does not expose a stale resume position`() {
        val progress = Json.parseToJsonElement(
            """{"playback_time":6812,"duration":7070,"watched":true,"completed":true,"processed":true}"""
        )
        assertThat(client.parsePlaybackState(progress)).isEqualTo(
            ChannelsDvrPlaybackState(
                positionMs = 0L,
                watched = true,
                durationMs = 7_070_000L
            )
        )
    }

    @Test
    fun `resume policy follows Channels watched state instead of a local completion threshold`() {
        assertThat(
            ChannelsDvrPlaybackState(
                positionMs = 6_812_000L,
                watched = false,
                durationMs = 7_070_000L
            ).shouldOfferResume()
        ).isTrue()
        assertThat(ChannelsDvrPlaybackState(positionMs = 5_000L, watched = false).shouldOfferResume()).isFalse()
        assertThat(ChannelsDvrPlaybackState(positionMs = 90_000L, watched = true).shouldOfferResume()).isFalse()
    }
}
