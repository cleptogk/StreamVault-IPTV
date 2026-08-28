package com.streamvault.app.ui.screens.downloads

import com.streamvault.app.sportswall.ChannelsDvrRecording
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStorageConfig

/**
 * UI state for the Downloads screen.
 */
data class DownloadsUiState(
    val channelsRecordings: List<ChannelsDvrRecording> = emptyList(),
    val channelsServerAddress: String = "",
    val channelsLoading: Boolean = true,
    val channelsError: String? = null,
    val downloads: List<DownloadItem> = emptyList(),
    val localDownloadsLoading: Boolean = true,
    val storageConfig: DownloadStorageConfig = DownloadStorageConfig(),
    val userMessage: String? = null,
    val deleteConfirmItem: DownloadItem? = null
)
