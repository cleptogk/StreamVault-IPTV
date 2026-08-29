package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Episode
import com.streamvault.player.PlaybackState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS = 250L

fun PlayerViewModel.seekForward() {
    notifyUserActivity()
    playerEngine.seekForward()
}

fun PlayerViewModel.seekBackward() {
    notifyUserActivity()
    playerEngine.seekBackward()
}

fun PlayerViewModel.seekToLiveEdge() {
    notifyUserActivity()
    playerEngine.seekToLiveEdge()
}

fun PlayerViewModel.playEpisode(episode: Episode, showResumePrompt: Boolean = true) {
    prepare(
        streamUrl = episode.streamUrl,
        epgChannelId = null,
        internalChannelId = episode.id,
        categoryId = -1,
        providerId = episode.providerId,
        isVirtual = false,
        contentType = ContentType.SERIES_EPISODE.name,
        title = buildEpisodePlaybackTitle(episode),
        artworkUrl = episode.coverUrl ?: currentSeries.value?.posterUrl ?: currentSeries.value?.backdropUrl,
        seriesId = currentSeriesId ?: episode.seriesId.takeIf { it > 0L },
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        showResumePrompt = showResumePrompt
    )
}

fun PlayerViewModel.toggleMute() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastMuteToggleAtMs < PLAYBACK_CONTROL_MUTE_TOGGLE_DEBOUNCE_MS) return
    lastMuteToggleAtMs = now
    playerEngine.toggleMute()
    val muted = playerEngine.isMuted.value
    mutePersistJob?.cancel()
    mutePersistJob = viewModelScope.launch {
        playerPreferencesCoordinator.setPlayerMuted(muted)
    }
}

fun PlayerViewModel.toggleControls() {
    closeChannelInfoOverlay()
    showControlsFlow.value = !showControlsFlow.value
    if (!showControlsFlow.value) {
        clearSeekPreview()
    }
}

fun PlayerViewModel.toggleAspectRatio() {
    val nextRatio = when (_aspectRatio.value) {
        AspectRatio.FIT -> AspectRatio.FILL
        AspectRatio.FILL -> AspectRatio.ZOOM
        AspectRatio.ZOOM -> AspectRatio.FIT
    }
    _aspectRatio.value = nextRatio

    if (currentContentId != -1L) {
        viewModelScope.launch {
            playerPreferencesCoordinator.setAspectRatioForChannel(currentContentId, nextRatio.name)
        }
    }
}

fun PlayerViewModel.dismissResumePrompt(resume: Boolean) {
    if (resumePromptSelectionJob?.isActive == true) return
    val prompt = _resumePrompt.value
    val playbackUrl = currentStreamUrl
    val requestVersion = prepareRequestVersion
    resumePromptSelectionJob = viewModelScope.launch {
        try {
            playerEngine.pause()
            if (!resume && channelsDvrClient.playbackIdentity(playbackUrl) != null) {
                runCatching { channelsDvrClient.updatePlaybackPosition(playbackUrl, 0L) }
                    .onFailure { error ->
                        android.util.Log.w("PlayerVM", "Channels DVR start-over reset failed", error)
                    }
            }
            val state = playerEngine.playbackState.first {
                it == PlaybackState.READY ||
                    it == PlaybackState.ERROR ||
                    it == PlaybackState.ENDED
            }
            if (
                state != PlaybackState.READY ||
                !isActivePlaybackSession(requestVersion, playbackUrl)
            ) {
                return@launch
            }
            // Channels HLS can carry EXT-X-START from server-side playback history.
            // Seek only after Media3 is ready or the early seek is silently ignored.
            playerEngine.seekTo(if (resume) prompt.positionMs.coerceAtLeast(0L) else 0L)
            _resumePrompt.value = ResumePromptState()
            playerEngine.play()
        } finally {
            resumePromptSelectionJob = null
        }
    }
}

fun PlayerViewModel.play() {
    notifyUserActivity()
    if (
        currentContentType == ContentType.LIVE &&
        timeshiftConfig.enabled &&
        timeshiftUiState.value.engineState.status == LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
    ) {
        playerEngine.resumeTimeshift()
    } else {
        playerEngine.play()
    }
}

fun PlayerViewModel.pause() {
    notifyUserActivity()
    if (currentContentType == ContentType.LIVE && timeshiftConfig.enabled) {
        playerEngine.pauseTimeshift()
    } else {
        playerEngine.pause()
        if (currentContentType != ContentType.LIVE) {
            queueForcedProgressFlush()
        }
    }
}
