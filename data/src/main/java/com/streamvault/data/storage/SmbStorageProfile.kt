package com.streamvault.data.storage

import kotlinx.serialization.Serializable

@Serializable
data class SmbStorageProfile(
    val id: String,
    val name: String,
    val server: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val timeshiftFolder: String = "timeshift",
    val recordingsFolder: String = "recordings",
    val enabled: Boolean = true
) {
    val maskedLocation: String get() = "smb://${server.trimEnd('/')}/${share.trim('/')}/"
}
