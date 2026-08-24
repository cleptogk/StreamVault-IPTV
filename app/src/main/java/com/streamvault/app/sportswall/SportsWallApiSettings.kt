package com.streamvault.app.sportswall

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.streamvault.app.ui.screens.settings.ClickableSettingsRow
import com.streamvault.app.ui.screens.settings.SettingsRow
import com.streamvault.app.ui.screens.settings.SettingsSectionHeader

@Composable
internal fun SportsWallApiSettings(context: Context) {
    var revealToken by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(SportsWallApiCredentials.token(context)) }
    var fingerprint by remember { mutableStateOf(SportsWallApiCredentials.fingerprint(context)) }

    SettingsSectionHeader(
        title = "Sports Wall API",
        subtitle = "LAN-only semantic control on port ${SportsWallApiServer.PORT}; every control request requires authentication."
    )
    SettingsRow(label = "Status", value = "Enabled · token $fingerprint")
    ClickableSettingsRow(
        label = if (revealToken) "Hide bearer token" else "Show bearer token",
        value = if (revealToken) "Token is visible below" else "Reveal only while pairing a trusted controller",
        onClick = { revealToken = !revealToken }
    )
    if (revealToken) {
        SettingsRow(label = "Bearer token", value = token)
        ClickableSettingsRow(
            label = "Regenerate bearer token",
            value = "Immediately revokes the current token",
            onClick = {
                token = SportsWallApiCredentials.rotate(context)
                fingerprint = SportsWallApiCredentials.fingerprint(context)
            }
        )
    }
}
