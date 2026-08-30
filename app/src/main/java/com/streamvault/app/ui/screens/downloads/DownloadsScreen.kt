package com.streamvault.app.ui.screens.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.app.navigation.Routes
import com.streamvault.app.sportswall.ChannelsDvrRecording
import com.streamvault.app.ui.components.rememberCrossfadeImageModel
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.design.AppColors
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import kotlinx.coroutines.delay

private const val CHANNELS_RECORDINGS_REFRESH_INTERVAL_MS = 15_000L

@Composable
fun DownloadsScreen(
    onNavigate: (String) -> Unit,
    onPlayRecording: (PlayerNavigationRequest) -> Unit,
    currentRoute: String,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onFolderSelected)
    }

    // Channels changes incomplete recordings in place as they finalize. Refresh
    // immediately when this destination enters composition, then keep the visible
    // library current so an old "Unavailable" snapshot cannot survive completion.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshChannelsRecordings()
                delay(CHANNELS_RECORDINGS_REFRESH_INTERVAL_MS)
            }
        }
    }

    HandleDownloadsUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::clearUserMessage
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_downloads),
            subtitle = stringResource(R.string.recordings_channels_subtitle),
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            RecordingsLibraryGrid(
                state = uiState,
                onRecordingClick = { recording ->
                    viewModel.playRecording(recording)?.let(onPlayRecording)
                },
                onRetryRecordings = viewModel::refreshChannelsRecordings,
                onOpenSettings = { onNavigate(Routes.SETTINGS) },
                onChangeDownloadFolder = { folderPicker.launch(null) },
                onOpenDownload = { download ->
                    viewModel.playDownload(download)?.let(context::startActivity)
                },
                onResumeDownload = viewModel::resumeDownload,
                onDeleteDownload = viewModel::showDeleteConfirm
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        )
    }

    uiState.deleteConfirmItem?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteConfirm
        )
    }
}

@Composable
private fun RecordingsLibraryGrid(
    state: DownloadsUiState,
    onRecordingClick: (ChannelsDvrRecording) -> Unit,
    onRetryRecordings: () -> Unit,
    onOpenSettings: () -> Unit,
    onChangeDownloadFolder: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onResumeDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (DownloadItem) -> Unit
) {
    val columns = if (LocalConfiguration.current.screenWidthDp < 700) {
        GridCells.Adaptive(180.dp)
    } else {
        GridCells.Adaptive(250.dp)
    }

    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            RecordingsSectionHeader(
                title = stringResource(R.string.recordings_channels_heading),
                actionLabel = stringResource(R.string.recordings_refresh),
                onAction = onRetryRecordings
            )
        }

        when {
            state.channelsLoading && state.channelsRecordings.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecordingsMessage(text = stringResource(R.string.recordings_loading), showProgress = true)
            }

            state.channelsError != null && state.channelsRecordings.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecordingsErrorState(
                    message = state.channelsError,
                    configured = state.channelsServerAddress.isNotBlank(),
                    onRetry = onRetryRecordings,
                    onOpenSettings = onOpenSettings
                )
            }

            state.channelsRecordings.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecordingsMessage(text = stringResource(R.string.recordings_empty))
            }

            else -> {
                if (state.channelsLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecordingsMessage(text = stringResource(R.string.recordings_loading), showProgress = true)
                    }
                } else if (state.channelsError != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecordingsErrorState(
                            message = state.channelsError,
                            configured = true,
                            onRetry = onRetryRecordings,
                            onOpenSettings = onOpenSettings
                        )
                    }
                }
                items(state.channelsRecordings, key = { "channels-${it.id}" }) { recording ->
                    RecordingCard(recording = recording, onClick = { onRecordingClick(recording) })
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            val folderLabel = state.storageConfig.displayName
                ?: state.storageConfig.treeUri
                ?: stringResource(R.string.download_folder_default)
            Column(modifier = Modifier.padding(top = 12.dp)) {
                RecordingsSectionHeader(
                    title = stringResource(R.string.recordings_local_downloads),
                    actionLabel = stringResource(R.string.download_folder_change),
                    onAction = onChangeDownloadFolder
                )
                Text(
                    text = folderLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }

        if (state.localDownloadsLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecordingsMessage(text = stringResource(R.string.downloads_loading), showProgress = true)
            }
        } else if (state.downloads.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecordingsMessage(text = stringResource(R.string.recordings_no_local_downloads))
            }
        } else {
            items(state.downloads, key = { "local-${it.id}" }) { download ->
                DownloadCard(
                    download = download,
                    onOpenClick = { onOpenDownload(download) },
                    onResumeClick = { onResumeDownload(download) },
                    onDeleteClick = { onDeleteDownload(download) }
                )
            }
        }
    }
}

@Composable
private fun RecordingsSectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun RecordingsMessage(text: String, showProgress: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showProgress) CircularProgressIndicator(color = AppColors.Brand)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
    }
}

@Composable
private fun RecordingsErrorState(
    message: String,
    configured: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
        Button(onClick = if (configured) onRetry else onOpenSettings) {
            Text(stringResource(if (configured) R.string.recordings_retry else R.string.recordings_open_settings))
        }
    }
}

@Composable
private fun RecordingCard(recording: ChannelsDvrRecording, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = recording.playable, onClick = onClick),
        color = AppColors.Surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = recording.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (recording.playable) AppColors.TextPrimary else AppColors.TextDisabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            recording.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingStatusBadge(recording)
            }
        }
    }
}

@Composable
private fun RecordingStatusBadge(recording: ChannelsDvrRecording) {
    val (label, color) = when {
        recording.inProgress -> stringResource(R.string.recordings_status_recording) to AppColors.Live
        recording.playable -> stringResource(R.string.recordings_status_completed) to AppColors.Success
        else -> stringResource(R.string.recordings_status_unavailable) to AppColors.TextTertiary
    }
    RecordingBadge(text = label, color = color)
}

@Composable
private fun RecordingBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.9f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DownloadCard(
    download: DownloadItem,
    onOpenClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val progress = download.totalBytes?.takeIf { it > 0L }?.let { total ->
        (download.bytesWritten.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = download.status == DownloadStatus.COMPLETED, onClick = onOpenClick),
        color = AppColors.Surface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceElevated)
            ) {
                if (download.posterUrl != null) {
                    AsyncImage(
                        model = rememberCrossfadeImageModel(download.posterUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.downloads_no_thumb),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                StatusBadge(
                    status = download.status,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = download.contentName.ifBlank { stringResource(R.string.downloads_item_title) },
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            download.outputDisplayPath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            DownloadProgress(status = download.status, progress = progress)

            download.totalBytes?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (download.status == DownloadStatus.FAILED) {
                    TextButton(onClick = onResumeClick) {
                        Text(text = stringResource(R.string.download_resume))
                    }
                }
                TextButton(onClick = onDeleteClick) {
                    Text(text = stringResource(R.string.download_delete))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        DownloadStatus.COMPLETED -> AppColors.Success
        DownloadStatus.DOWNLOADING -> AppColors.Brand
        DownloadStatus.PAUSED -> AppColors.Warning
        DownloadStatus.FAILED -> AppColors.Live
        DownloadStatus.PENDING -> AppColors.TextTertiary
        DownloadStatus.CANCELLED -> AppColors.TextDisabled
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DownloadProgress(status: DownloadStatus, progress: Float) {
    when (status) {
        DownloadStatus.DOWNLOADING -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                    color = AppColors.Brand,
                    trackColor = AppColors.SurfaceElevated
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary
                )
            }
        }

        DownloadStatus.FAILED,
        DownloadStatus.PAUSED,
        DownloadStatus.CANCELLED,
        DownloadStatus.PENDING,
        DownloadStatus.COMPLETED -> {
            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun statusLabel(status: DownloadStatus): String {
    return when (status) {
        DownloadStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.downloads_status_downloading)
        DownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
        DownloadStatus.PENDING -> stringResource(R.string.downloads_status_pending)
        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.CANCELLED -> stringResource(R.string.downloads_status_cancelled)
    }
}

@Composable
private fun DeleteConfirmDialog(
    item: DownloadItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.downloads_delete_confirm_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.downloads_delete_confirm_msg,
                    item.contentName.ifBlank { stringResource(R.string.downloads_item_title) }
                )
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Live)
            ) {
                Text(text = stringResource(R.string.downloads_delete_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        }
    )
}

@Composable
fun HandleDownloadsUserMessage(
    userMessage: String?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit
) {
    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onShown()
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        else -> "${bytes / (1024L * 1024L * 1024L)} GB"
    }
}
