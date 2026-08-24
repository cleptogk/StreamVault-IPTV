package com.streamvault.app.sportswall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SportsWallBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            SportsWallApiService.start(context)
        }
    }
}
