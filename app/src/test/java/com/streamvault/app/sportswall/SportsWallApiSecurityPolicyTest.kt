package com.streamvault.app.sportswall

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SportsWallApiSecurityPolicyTest {
    @Test
    fun allowsOnlyConfiguredLanAndLoopbackAddresses() {
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress("10.217.0.112")).isTrue()
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress("127.0.0.1")).isTrue()
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress("::1")).isTrue()
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress("192.168.1.10")).isFalse()
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress("100.64.0.10")).isFalse()
        assertThat(SportsWallApiSecurityPolicy.isAllowedRemoteAddress(null)).isFalse()
    }
}
