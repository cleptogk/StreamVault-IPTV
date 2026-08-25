package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.data.storage.SmbStorageProfile
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import java.util.UUID

@Composable
internal fun SmbStorageSettingsCard(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    var draft by remember { mutableStateOf(newSmbProfile()) }
    val statusColor = when (uiState.smbStorageStatusSuccess) {
        true -> Color(0xFF76D275)
        false -> Color(0xFFFF8A80)
        null -> OnSurfaceDim
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.05f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("StreamVault SMB storage", style = MaterialTheme.typography.titleMedium, color = OnBackground)
            Text(
                "Private network storage for live timeshift and saved live recordings. Credentials are encrypted on this Shield.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )

            uiState.smbStorageProfiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, color = OnBackground, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${profile.maskedLocation}${profile.timeshiftFolder} · ${profile.recordingsFolder}",
                            color = OnSurfaceDim,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(if (profile.enabled) "Active" else "Inactive", color = if (profile.enabled) Primary else OnSurfaceDim)
                    }
                    RecordingActionButton("Edit", Primary) { draft = profile }
                    RecordingActionButton("Test", Color(0xFF64B5F6)) { viewModel.testSmbStorageProfile(profile) }
                    RecordingActionButton("Delete", Color(0xFFFF8A80)) {
                        viewModel.deleteSmbStorageProfile(profile.id)
                        if (draft.id == profile.id) draft = newSmbProfile()
                    }
                }
            }

            SmbTextField("Profile name", draft.name) { draft = draft.copy(name = it) }
            SmbTextField("Server or IP", draft.server, "10.217.0.108") { draft = draft.copy(server = it) }
            SmbTextField("Share", draft.share, "streamvault") { draft = draft.copy(share = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SmbTextField("Username", draft.username) { draft = draft.copy(username = it) }
                }
                Box(modifier = Modifier.weight(1f)) {
                    SmbTextField("Password", draft.password, password = true) { draft = draft.copy(password = it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    SmbTextField("Timeshift folder", draft.timeshiftFolder) { draft = draft.copy(timeshiftFolder = it) }
                }
                Box(modifier = Modifier.weight(1f)) {
                    SmbTextField("Live recordings folder", draft.recordingsFolder) { draft = draft.copy(recordingsFolder = it) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
                Text("Use this profile for timeshift and recording", color = OnBackground)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingActionButton("Save profile", Primary) { viewModel.saveSmbStorageProfile(draft) }
                RecordingActionButton("Test connection", Color(0xFF64B5F6)) { viewModel.testSmbStorageProfile(draft) }
                RecordingActionButton("New profile", OnSurfaceDim) { draft = newSmbProfile() }
            }
            uiState.smbStorageStatus?.let { status ->
                Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SmbTextField(
    label: String,
    value: String,
    placeholder: String = "",
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground),
            singleLine = true,
            cursorBrush = SolidColor(Primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box {
                    if (value.isBlank() && placeholder.isNotBlank()) Text(placeholder, color = OnSurfaceDim)
                    inner()
                }
            }
        )
    }
}

private fun newSmbProfile() = SmbStorageProfile(
    id = UUID.randomUUID().toString(),
    name = "Vault RAID0",
    server = "10.217.0.108",
    share = "streamvault",
    username = "",
    password = "",
    timeshiftFolder = "timeshift",
    recordingsFolder = "recordings",
    enabled = true
)
