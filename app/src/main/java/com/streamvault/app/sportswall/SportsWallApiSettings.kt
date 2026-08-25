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
import com.streamvault.app.ui.screens.settings.SwitchSettingsRow

internal object SportsWallApiStartupSettings {
    private const val FILE_NAME = "sports_wall_api_startup"
    private const val KEY_START_AUTOMATICALLY = "start_automatically"

    fun startsAutomatically(context: Context): Boolean =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_START_AUTOMATICALLY, true)

    fun setStartsAutomatically(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_START_AUTOMATICALLY, enabled)
            .apply()
    }
}

@Composable
internal fun SportsWallApiSettings(context: Context) {
    var revealToken by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf(SportsWallApiCredentials.token(context)) }
    var fingerprint by remember { mutableStateOf(SportsWallApiCredentials.fingerprint(context)) }
    var startsAutomatically by remember {
        mutableStateOf(SportsWallApiStartupSettings.startsAutomatically(context))
    }

    SettingsSectionHeader(
        title = "Sports Wall API",
        subtitle = "LAN-only semantic control on port ${SportsWallApiServer.PORT}; every control request requires authentication."
    )
    SwitchSettingsRow(
        label = "Start control API automatically",
        value = if (startsAutomatically) {
            "Enabled at app launch and after Shield boot"
        } else {
            "Disabled; no LAN listener runs in the background"
        },
        checked = startsAutomatically,
        onCheckedChange = { enabled ->
            startsAutomatically = enabled
            SportsWallApiStartupSettings.setStartsAutomatically(context, enabled)
            if (enabled) SportsWallApiService.start(context) else SportsWallApiService.stop(context)
        }
    )
    SettingsRow(
        label = "Status",
        value = if (startsAutomatically) "Enabled · token $fingerprint" else "Disabled"
    )
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
