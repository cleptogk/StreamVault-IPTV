package com.streamvault.app.ui.screens.downloads

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.navigation.Routes
import com.streamvault.app.sportswall.ChannelsDvrClient
import com.streamvault.app.sportswall.ChannelsDvrRecording
import okhttp3.OkHttpClient
import org.junit.Test

class DownloadsRecordingPolicyTest {
    private val client = ChannelsDvrClient(OkHttpClient())

    @Test
    fun blankConfigurationClearsRemoteRowsAndShowsConfigurationError() {
        val state = DownloadsUiState(
            channelsRecordings = listOf(ChannelsDvrRecording("865", "Old server recording")),
            channelsServerAddress = "http://10.217.0.120:8089",
            channelsLoading = true
        )

        val updated = blankChannelsConfigurationState(state, "Configure Channels DVR")

        assertThat(updated.channelsRecordings).isEmpty()
        assertThat(updated.channelsServerAddress).isEmpty()
        assertThat(updated.channelsLoading).isFalse()
        assertThat(updated.channelsError).isEqualTo("Configure Channels DVR")
    }

    @Test
    fun completedAndActiveRequestsUseSecuredUrlsAndReturnToRecordingsRoute() {
        val completed = channelsRecordingPlayerRequest(
            client,
            "http://10.217.0.120:8089",
            ChannelsDvrRecording("865", "Completed game")
        )
        val active = channelsRecordingPlayerRequest(
            client,
            "http://10.217.0.120:8089",
            ChannelsDvrRecording("866", "Active game", inProgress = true)
        )

        assertThat(completed.streamUrl).isEqualTo(
            "http://10.217.0.120:8089/dvr/files/865/hls/stream.m3u8?" +
                "acodec=aac&indexed=true&ssize=1&vcodec=copy"
        )
        assertThat(active.streamUrl)
            .isEqualTo("http://10.217.0.120:8089/dvr/files/866/stream.mpg")
        assertThat(completed.returnRoute).isEqualTo(Routes.DOWNLOADS)
        assertThat(active.returnRoute).isEqualTo(Routes.DOWNLOADS)
        assertThat(completed.contentType).isEqualTo("VOD")
        assertThat(active.contentType).isEqualTo("VOD")
    }

    @Test
    fun refreshCommitRequiresMatchingGenerationAndAddress() {
        assertThat(
            shouldCommitChannelsRefresh(4L, "server-b", 3L, "server-b")
        ).isFalse()
        assertThat(
            shouldCommitChannelsRefresh(4L, "server-b", 4L, "server-a")
        ).isFalse()
        assertThat(
            shouldCommitChannelsRefresh(4L, "server-b", 4L, "server-b")
        ).isTrue()
    }
}
