package com.streamvault.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalPlayerBackStackTest {
    @Test
    fun `cold external recording replaces welcome with its return route`() {
        val request = PlayerNavigationRequest(
            streamUrl = "http://dvr.test/recording.m3u8",
            title = "49ers vs Raiders",
            returnRoute = Routes.HOME
        )

        assertEquals(Routes.HOME, externalPlayerBaseRoute(Routes.WELCOME, request))
    }

    @Test
    fun `cold external player without return route uses home`() {
        val request = PlayerNavigationRequest(
            streamUrl = "http://dvr.test/recording.m3u8",
            title = "49ers vs Raiders"
        )

        assertEquals(Routes.HOME, externalPlayerBaseRoute(Routes.WELCOME, request))
    }

    @Test
    fun `warm external player preserves the existing back stack`() {
        val request = PlayerNavigationRequest(
            streamUrl = "http://dvr.test/recording.m3u8",
            title = "49ers vs Raiders",
            returnRoute = Routes.HOME
        )

        assertNull(externalPlayerBaseRoute(Routes.MULTI_VIEW, request))
    }
}
