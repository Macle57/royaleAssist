package com.macle.royaleassist.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * App settings in DataStore. The Clash Royale API token lives in the app's private storage
 * (not world-readable, excluded from backups via allowBackup=false).
 */
class Settings(private val context: Context) {
    private val tokenKey = stringPreferencesKey("cr_api_token")

    val apiToken: Flow<String?> = context.dataStore.data.map { it[tokenKey]?.takeIf { t -> t.isNotBlank() } }

    suspend fun currentToken(): String? = apiToken.first()

    suspend fun setApiToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(tokenKey) else prefs[tokenKey] = token.trim()
        }
    }
}
