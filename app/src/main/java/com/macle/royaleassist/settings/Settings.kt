package com.macle.royaleassist.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macle.royaleassist.ocr.ScanRegion
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
    private val regionLeft = floatPreferencesKey("region_left")
    private val regionTop = floatPreferencesKey("region_top")
    private val regionRight = floatPreferencesKey("region_right")
    private val regionBottom = floatPreferencesKey("region_bottom")
    private val fullFallbackKey = booleanPreferencesKey("full_screen_fallback")

    val apiToken: Flow<String?> = context.dataStore.data.map { it[tokenKey]?.takeIf { t -> t.isNotBlank() } }

    suspend fun currentToken(): String? = apiToken.first()

    suspend fun setApiToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(tokenKey) else prefs[tokenKey] = token.trim()
        }
    }

    /** Screen region that is OCR'd (fractions of the screen). */
    val scanRegion: Flow<ScanRegion> = context.dataStore.data.map { p ->
        val l = p[regionLeft]
        val t = p[regionTop]
        val r = p[regionRight]
        val b = p[regionBottom]
        if (l == null || t == null || r == null || b == null) ScanRegion.DEFAULT else ScanRegion(l, t, r, b).normalized()
    }

    suspend fun currentScanRegion(): ScanRegion = scanRegion.first()

    suspend fun setScanRegion(region: ScanRegion?) {
        context.dataStore.edit { p ->
            if (region == null) {
                listOf(regionLeft, regionTop, regionRight, regionBottom).forEach { p.remove(it) }
            } else {
                val n = region.normalized()
                p[regionLeft] = n.left; p[regionTop] = n.top; p[regionRight] = n.right; p[regionBottom] = n.bottom
            }
        }
    }

    /** When the region finds no name, also scan the whole screen (slower). Off by default. */
    val fullScreenFallback: Flow<Boolean> = context.dataStore.data.map { it[fullFallbackKey] ?: false }

    suspend fun setFullScreenFallback(on: Boolean) {
        context.dataStore.edit { it[fullFallbackKey] = on }
    }
}
