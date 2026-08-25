package com.streamvault.app.ui.screens.multiview

/**
 * Resolves the only pane allowed to decode audio.
 *
 * Fullscreen always owns audio. In the grid, an explicit remote/UI pin wins until the
 * operator moves focus to another pane; otherwise audio follows focus.
 */
internal fun resolveMultiViewAudioOwner(
    focusedSlotIndex: Int,
    pinnedAudioSlotIndex: Int?,
    fullscreenSlotIndex: Int?,
    activeSlotIndexes: Set<Int>
): Int? {
    if (fullscreenSlotIndex != null) {
        return fullscreenSlotIndex.takeIf(activeSlotIndexes::contains)
    }
    return pinnedAudioSlotIndex
        ?.takeIf(activeSlotIndexes::contains)
        ?: focusedSlotIndex.takeIf(activeSlotIndexes::contains)
}
