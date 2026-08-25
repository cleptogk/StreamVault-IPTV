package com.streamvault.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbStorageProfileStore @Inject constructor(@ApplicationContext context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val preferences: SharedPreferences = createEncryptedPreferences(context)
    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<SmbStorageProfile>> = _profiles.asStateFlow()

    fun upsert(profile: SmbStorageProfile) {
        val normalized = profile.normalizedAndValidated()
        val next = _profiles.value.map { existing ->
            if (normalized.enabled && existing.id != normalized.id) existing.copy(enabled = false) else existing
        }.toMutableList().apply {
            val index = indexOfFirst { it.id == normalized.id }
            if (index >= 0) set(index, normalized) else add(normalized)
        }
        writeProfiles(next)
    }

    fun delete(profileId: String) = writeProfiles(_profiles.value.filterNot { it.id == profileId })
    fun profile(profileId: String): SmbStorageProfile? = _profiles.value.firstOrNull { it.id == profileId }
    fun enabledProfile(): SmbStorageProfile? = _profiles.value.firstOrNull { it.enabled }

    private fun writeProfiles(profiles: List<SmbStorageProfile>) {
        preferences.edit {
            putString(KEY_PROFILES, json.encodeToString(ListSerializer(SmbStorageProfile.serializer()), profiles))
        }
        _profiles.value = profiles
    }

    private fun readProfiles(): List<SmbStorageProfile> = runCatching {
        val encoded = preferences.getString(KEY_PROFILES, null) ?: return@runCatching emptyList()
        json.decodeFromString(ListSerializer(SmbStorageProfile.serializer()), encoded)
    }.getOrDefault(emptyList())

    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val FILE_NAME = "streamvault_smb_storage"
        private const val KEY_PROFILES = "profiles"
    }
}

fun SmbStorageProfile.normalizedAndValidated(): SmbStorageProfile {
    val cleanServer = server.trim().removePrefix("smb://").trimEnd('/')
    val cleanShare = share.trim().trim('/')
    require(name.isNotBlank()) { "Enter a profile name" }
    require(cleanServer.isNotBlank() && '/' !in cleanServer) { "Enter a server name or IP address" }
    require(cleanShare.isNotBlank() && cleanShare != "." && cleanShare != "..") { "Enter an SMB share name" }
    require(username.isNotBlank()) { "Enter an SMB username" }
    require(password.isNotBlank()) { "Enter an SMB password" }
    validateRelativeFolder(timeshiftFolder, "timeshift")
    validateRelativeFolder(recordingsFolder, "recordings")
    return copy(
        name = name.trim(), server = cleanServer, share = cleanShare, username = username.trim(),
        domain = domain.trim(), timeshiftFolder = timeshiftFolder.trim().trim('/'),
        recordingsFolder = recordingsFolder.trim().trim('/')
    )
}

private fun validateRelativeFolder(value: String, label: String) {
    val clean = value.trim().trim('/')
    require(clean.isNotBlank()) { "Enter a $label folder" }
    require(clean.split('/').none { it == "." || it == ".." }) { "$label folder cannot contain relative traversal" }
}
