package com.streamvault.app.ui.screens.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiViewAudioPolicyTest {
    @Test
    fun `grid audio follows focused pane when audio is not pinned`() {
        assertEquals(
            2,
            resolveMultiViewAudioOwner(
                focusedSlotIndex = 2,
                pinnedAudioSlotIndex = null,
                fullscreenSlotIndex = null,
                activeSlotIndexes = setOf(0, 1, 2, 3)
            )
        )
    }

    @Test
    fun `fullscreen pane overrides a stale pinned pane`() {
        assertEquals(
            3,
            resolveMultiViewAudioOwner(
                focusedSlotIndex = 3,
                pinnedAudioSlotIndex = 0,
                fullscreenSlotIndex = 3,
                activeSlotIndexes = setOf(0, 1, 2, 3)
            )
        )
    }

    @Test
    fun `fullscreen waits silently until its engine is active`() {
        assertNull(
            resolveMultiViewAudioOwner(
                focusedSlotIndex = 3,
                pinnedAudioSlotIndex = 0,
                fullscreenSlotIndex = 3,
                activeSlotIndexes = setOf(0, 1, 2)
            )
        )
    }
}
