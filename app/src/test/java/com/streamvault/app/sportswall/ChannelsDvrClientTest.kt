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
            ChannelsDvrRecording(id = "855", title = "Bears vs Bengals")
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
}
