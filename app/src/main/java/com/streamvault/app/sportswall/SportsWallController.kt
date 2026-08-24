package com.streamvault.app.sportswall

import android.content.Context
import android.content.Intent
import com.streamvault.app.MainActivity
import com.streamvault.app.navigation.ExternalDestination
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.model.associateByAnyRawId
import com.streamvault.app.ui.screens.multiview.MultiViewManager
import com.streamvault.app.ui.screens.multiview.MultiViewPerformanceMode
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Channel
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SportsWallChannelSummary(
    val id: Long,
    val name: String,
    val number: Int,
    val category: String?,
    val providerId: Long,
    val sourceType: String = "live_channel",
    val sourceId: String? = null
)

data class SportsWallRecording(
    val id: String,
    val title: String,
    val playbackUrl: String
)

data class SportsWallPaneState(
    val pane: Int,
    val channel: SportsWallChannelSummary?
)

data class SportsWallState(
    val panes: List<SportsWallPaneState>,
    val focusedPane: Int,
    val audioPane: Int?,
    val performanceMode: String
)

class SportsWallControlException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

interface SportsWallControlPort {
    suspend fun state(): SportsWallState
    suspend fun searchChannels(query: String, limit: Int): List<SportsWallChannelSummary>
    suspend fun setLayout(channelIds: List<Long?>, launch: Boolean): SportsWallState
    suspend fun assignPane(pane: Int, channelId: Long, launch: Boolean): SportsWallState
    suspend fun assignRecording(pane: Int, recording: SportsWallRecording, launch: Boolean): SportsWallState
    suspend fun clearPane(pane: Int): SportsWallState
    suspend fun selectAudioPane(pane: Int?): SportsWallState
    suspend fun setPerformanceMode(mode: String): SportsWallState
    suspend fun savePreset(preset: Int): SportsWallState
    suspend fun loadPreset(preset: Int, launch: Boolean): SportsWallState
    suspend fun openFullscreen(pane: Int)
    suspend fun openRecordingFullscreen(recording: SportsWallRecording)
    fun restoreMultiView()
}

@Singleton
class SportsWallController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val multiViewManager: MultiViewManager,
    private val channelRepository: ChannelRepository,
    private val providerRepository: ProviderRepository,
    private val preferencesRepository: PreferencesRepository
) : SportsWallControlPort {

    override suspend fun state(): SportsWallState = SportsWallState(
        panes = multiViewManager.slots.value.mapIndexed { index, channel ->
            SportsWallPaneState(pane = index + 1, channel = channel?.toSummary())
        },
        focusedPane = multiViewManager.focusedSlotIndex.value + 1,
        audioPane = multiViewManager.pinnedAudioSlotIndex.value?.plus(1),
        performanceMode = preferencesRepository.multiViewPerformanceMode.first()
            ?.takeIf { it.isNotBlank() }
            ?: MultiViewPerformanceMode.AUTO.name
    )

    override suspend fun searchChannels(query: String, limit: Int): List<SportsWallChannelSummary> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length > 128) {
            throw SportsWallControlException("invalid_query", "Search query must be 128 characters or fewer")
        }
        val providerId = activeProviderId()
        val channels = if (normalizedQuery.isBlank()) {
            channelRepository.getChannels(providerId).first()
        } else {
            channelRepository.searchChannels(providerId, normalizedQuery).first()
        }
        return channels.asSequence()
            .filterNot(Channel::isProtectedForRemoteControl)
            .distinctBy(Channel::id)
            .take(limit.coerceIn(1, 50))
            .map(Channel::toSummary)
            .toList()
    }

    override suspend fun setLayout(channelIds: List<Long?>, launch: Boolean): SportsWallState {
        if (channelIds.size != MultiViewManager.MAX_SLOTS) {
            throw SportsWallControlException("invalid_layout", "A 2x2 layout requires exactly four pane entries")
        }
        val populatedIds = channelIds.filterNotNull()
        if (populatedIds.any { it <= 0L } || populatedIds.distinct().size != populatedIds.size) {
            throw SportsWallControlException("invalid_layout", "Channel IDs must be positive and cannot be duplicated")
        }
        val resolved = resolveChannels(populatedIds)
        val plan = channelIds.map { channelId -> channelId?.let(resolved::getValue) }
        multiViewManager.setSlots(plan)
        plan.indexOfFirst { it != null }.takeIf { it >= 0 }?.let(multiViewManager::setFocusedSlot)
        if (launch) restoreMultiView()
        return state()
    }

    override suspend fun assignPane(
        pane: Int,
        channelId: Long,
        launch: Boolean
    ): SportsWallState {
        val slotIndex = pane.toSlotIndex()
        val channel = resolveChannels(listOf(channelId)).getValue(channelId)
        multiViewManager.setChannel(slotIndex, channel)
        multiViewManager.setFocusedSlot(slotIndex)
        if (launch) restoreMultiView()
        return state()
    }

    override suspend fun assignRecording(
        pane: Int,
        recording: SportsWallRecording,
        launch: Boolean
    ): SportsWallState {
        val slotIndex = pane.toSlotIndex()
        val channel = recording.toChannel()
        multiViewManager.setChannel(slotIndex, channel)
        multiViewManager.setFocusedSlot(slotIndex)
        if (launch) restoreMultiView()
        return state()
    }

    override suspend fun clearPane(pane: Int): SportsWallState {
        multiViewManager.clearSlot(pane.toSlotIndex())
        return state()
    }

    override suspend fun selectAudioPane(pane: Int?): SportsWallState {
        val slotIndex = pane?.toSlotIndex()
        if (slotIndex != null && multiViewManager.slots.value[slotIndex] == null) {
            throw SportsWallControlException("empty_pane", "Cannot select audio from an empty pane")
        }
        multiViewManager.setPinnedAudioSlot(slotIndex)
        slotIndex?.let(multiViewManager::setFocusedSlot)
        return state()
    }

    override suspend fun setPerformanceMode(mode: String): SportsWallState {
        val resolved = MultiViewPerformanceMode.entries.firstOrNull {
            it.name.equals(mode.trim(), ignoreCase = true)
        } ?: throw SportsWallControlException(
            "invalid_performance_mode",
            "Performance mode must be AUTO, CONSERVATIVE, BALANCED, or MAXIMUM"
        )
        preferencesRepository.setMultiViewPerformanceMode(resolved.name)
        return state()
    }

    override suspend fun savePreset(preset: Int): SportsWallState {
        if (multiViewManager.slots.value.any { it?.isChannelsDvrRecording() == true }) {
            throw SportsWallControlException(
                "recording_not_presettable",
                "Channels DVR recordings are temporary wall sources and cannot be saved in presets"
            )
        }
        val index = preset.toPresetIndex()
        preferencesRepository.setMultiViewPreset(
            index,
            multiViewManager.slots.value.map { it?.id ?: 0L }
        )
        return state()
    }

    override suspend fun loadPreset(preset: Int, launch: Boolean): SportsWallState {
        val saved = preferencesRepository.getMultiViewPreset(preset.toPresetIndex()).first()
        if (saved.isEmpty() || saved.all { it <= 0L }) {
            throw SportsWallControlException("empty_preset", "Preset $preset is empty")
        }
        val channelIds = List(MultiViewManager.MAX_SLOTS) { index ->
            saved.getOrNull(index)?.takeIf { it > 0L }
        }
        return setLayout(channelIds, launch)
    }

    override suspend fun openFullscreen(pane: Int) {
        val channel = multiViewManager.slots.value[pane.toSlotIndex()]
            ?: throw SportsWallControlException("empty_pane", "Cannot fullscreen an empty pane")
        val request = if (channel.isChannelsDvrRecording()) {
            Routes.player(
                streamUrl = channel.streamUrl,
                title = channel.name,
                internalId = channel.id,
                contentType = "VOD",
                returnRoute = Routes.MULTI_VIEW
            )
        } else {
            Routes.livePlayer(channel, returnRoute = Routes.MULTI_VIEW)
        }
        startPlayer(request)
    }

    override suspend fun openRecordingFullscreen(recording: SportsWallRecording) {
        val channel = recording.toChannel()
        startPlayer(
            Routes.player(
                streamUrl = channel.streamUrl,
                title = channel.name,
                internalId = channel.id,
                contentType = "VOD",
                returnRoute = Routes.MULTI_VIEW
            )
        )
    }

    private fun startPlayer(request: com.streamvault.app.navigation.PlayerNavigationRequest) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PLAYER_REQUEST, request)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    override fun restoreMultiView() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EXTERNAL_DESTINATION, ExternalDestination.MultiView)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private suspend fun resolveChannels(channelIds: List<Long>): Map<Long, Channel> {
        if (channelIds.isEmpty()) return emptyMap()
        val providerId = activeProviderId()
        val resolved = channelRepository.getChannelsByIds(channelIds).first().associateByAnyRawId()
        channelIds.forEach { channelId ->
            val channel = resolved[channelId]
                ?: throw SportsWallControlException("channel_not_found", "Channel $channelId was not found")
            if (channel.providerId != providerId) {
                throw SportsWallControlException("wrong_provider", "Channel $channelId is not in the active provider")
            }
            if (channel.isProtectedForRemoteControl()) {
                throw SportsWallControlException("protected_channel", "Protected channels cannot be controlled remotely")
            }
        }
        return channelIds.associateWith { resolved.getValue(it) }
    }

    private suspend fun activeProviderId(): Long = providerRepository.getActiveProvider().first()?.id
        ?.takeIf { it > 0L }
        ?: throw SportsWallControlException("no_active_provider", "No active provider is configured")

    private fun Int.toSlotIndex(): Int {
        if (this !in 1..MultiViewManager.MAX_SLOTS) {
            throw SportsWallControlException("invalid_pane", "Pane must be between 1 and 4")
        }
        return this - 1
    }

    private fun Int.toPresetIndex(): Int {
        if (this !in 1..3) {
            throw SportsWallControlException("invalid_preset", "Preset must be between 1 and 3")
        }
        return this - 1
    }
}

private fun Channel.isProtectedForRemoteControl(): Boolean = isAdult || isUserProtected

private fun Channel.toSummary(): SportsWallChannelSummary = SportsWallChannelSummary(
    id = id,
    name = name,
    number = number,
    category = categoryName ?: groupTitle,
    providerId = providerId,
    sourceType = if (isChannelsDvrRecording()) "channels_recording" else "live_channel",
    sourceId = channelsDvrRecordingId()
)

private const val CHANNELS_DVR_MARKER = "sports-wall:channels-dvr:"

internal fun SportsWallRecording.toChannel(): Channel {
    val preferredRecording = SportsWallRecordingPolicy.preferNativeVideo(this)
    val stableId = -1_000_000_000L - (id.hashCode().toLong() and 0x7fff_ffffL)
    return Channel(
        id = stableId,
        name = title.trim(),
        canonicalName = title.trim(),
        groupTitle = CHANNELS_DVR_MARKER + id,
        categoryName = "Channels DVR",
        streamUrl = preferredRecording.playbackUrl,
        providerId = 0L
    )
}

private fun Channel.isChannelsDvrRecording(): Boolean = groupTitle?.startsWith(CHANNELS_DVR_MARKER) == true

private fun Channel.channelsDvrRecordingId(): String? = groupTitle
    ?.takeIf { it.startsWith(CHANNELS_DVR_MARKER) }
    ?.removePrefix(CHANNELS_DVR_MARKER)
