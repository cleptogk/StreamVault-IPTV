package com.streamvault.app.sportswall

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

object SportsWallApiCredentials {
    private const val SECURE_PREFS_NAME = "sports_wall_api_secure"
    private const val FALLBACK_PREFS_NAME = "sports_wall_api_private"
    private const val TOKEN_KEY = "bearer_token"
    private const val TOKEN_BYTES = 32

    @Volatile
    private var cachedPreferences: SharedPreferences? = null

    fun token(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return synchronized(this) {
            preferences.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
                ?: generateToken().also { generated ->
                    persistToken(preferences, generated)
                }
        }
    }

    fun rotate(context: Context): String = synchronized(this) {
        generateToken().also { generated ->
            persistToken(preferences(context), generated)
        }
    }

    fun fingerprint(context: Context): String = MessageDigest.getInstance("SHA-256")
        .digest(token(context).toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun authenticate(context: Context, authorizationHeader: String?): Boolean {
        val supplied = authorizationHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return MessageDigest.isEqual(
            token(context).toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )
    }

    private fun preferences(context: Context): SharedPreferences {
        cachedPreferences?.let { return it }
        return synchronized(this) {
            cachedPreferences ?: createPreferences(context.applicationContext).also {
                cachedPreferences = it
            }
        }
    }

    private fun createPreferences(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    @SuppressLint("UseKtx")
    private fun persistToken(preferences: SharedPreferences, token: String) {
        // The KTX helper discards commit()'s result; a control credential must fail closed
        // when durable storage cannot be confirmed.
        check(preferences.edit().putString(TOKEN_KEY, token).commit()) {
            "Unable to persist the Sports Wall API token"
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

internal object SportsWallApiSecurityPolicy {
    fun isAllowedRemoteAddress(address: String?): Boolean {
        val normalized = address?.removePrefix("/")?.substringBefore('%').orEmpty()
        return normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized.startsWith("10.217.0.")
    }
}
