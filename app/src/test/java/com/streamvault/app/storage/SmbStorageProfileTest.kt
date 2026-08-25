package com.streamvault.app.storage

import com.streamvault.data.storage.SmbStorageProfile
import com.streamvault.data.storage.normalizedAndValidated

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbStorageProfileTest {
    @Test
    fun `normalization removes smb prefix and redundant separators`() {
        val normalized = profile(
            server = " smb://10.217.0.108/ ",
            share = "/streamvault/",
            timeshiftFolder = "/timeshift/",
            recordingsFolder = "/recordings/"
        ).normalizedAndValidated()

        assertThat(normalized.server).isEqualTo("10.217.0.108")
        assertThat(normalized.share).isEqualTo("streamvault")
        assertThat(normalized.maskedLocation).isEqualTo("smb://10.217.0.108/streamvault/")
        assertThat(normalized.timeshiftFolder).isEqualTo("timeshift")
        assertThat(normalized.recordingsFolder).isEqualTo("recordings")
    }

    @Test
    fun `relative traversal is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(timeshiftFolder = "../recordings").normalizedAndValidated()
        }
    }

    @Test
    fun `blank credentials are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(password = "").normalizedAndValidated()
        }
    }

    private fun profile(
        server: String = "10.217.0.108",
        share: String = "streamvault",
        timeshiftFolder: String = "timeshift",
        recordingsFolder: String = "recordings",
        password: String = "not-a-real-secret"
    ) = SmbStorageProfile(
        id = "test",
        name = "Vault RAID0",
        server = server,
        share = share,
        username = "streamvault",
        password = password,
        timeshiftFolder = timeshiftFolder,
        recordingsFolder = recordingsFolder
    )
}
