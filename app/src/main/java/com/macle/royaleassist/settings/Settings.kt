package com.macle.royaleassist.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.cache.CacheTtl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Look and behaviour of the floating overlay; applied live by the running service. */
data class OverlayPrefs(
    /** Bubble opacity, 0.3..1. */
    val bubbleAlpha: Float = 0.7f,
    /** Result panel opacity, 0.3..1. */
    val panelAlpha: Float = 0.95f,
    /** Seconds before an unpinned result hides, 2..30. */
    val autoHideSeconds: Int = 6,
    /** Results stay until closed. */
    val alwaysPin: Boolean = false,
    /** Size of bubble and panel (dp and text), 0.75..1.5. */
    val scale: Float = 1f,
    /** Card shows the opponent's last-battle deck instead of the stats block. */
    val deckOnly: Boolean = false,
) {
    fun normalized() = copy(
        bubbleAlpha = bubbleAlpha.coerceIn(MIN_ALPHA, 1f),
        panelAlpha = panelAlpha.coerceIn(MIN_ALPHA, 1f),
        autoHideSeconds = autoHideSeconds.coerceIn(2, 30),
        scale = scale.coerceIn(0.75f, 1.5f),
    )

    companion object {
        const val MIN_ALPHA = 0.3f
    }
}

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
    private val bubbleAlphaKey = floatPreferencesKey("bubble_alpha")
    private val panelAlphaKey = floatPreferencesKey("panel_alpha")
    private val autoHideKey = intPreferencesKey("auto_hide_seconds")
    private val alwaysPinKey = booleanPreferencesKey("always_pin")
    private val scaleKey = floatPreferencesKey("overlay_scale")
    private val deckOnlyKey = booleanPreferencesKey("deck_only")

    val overlayPrefs: Flow<OverlayPrefs> = context.dataStore.data.map { p ->
        val d = OverlayPrefs()
        OverlayPrefs(
            bubbleAlpha = p[bubbleAlphaKey] ?: d.bubbleAlpha,
            panelAlpha = p[panelAlphaKey] ?: d.panelAlpha,
            autoHideSeconds = p[autoHideKey] ?: d.autoHideSeconds,
            alwaysPin = p[alwaysPinKey] ?: d.alwaysPin,
            scale = p[scaleKey] ?: d.scale,
            deckOnly = p[deckOnlyKey] ?: d.deckOnly,
        ).normalized()
    }

    suspend fun updateOverlayPrefs(change: (OverlayPrefs) -> OverlayPrefs) {
        context.dataStore.edit { p ->
            val d = OverlayPrefs()
            val cur = OverlayPrefs(
                p[bubbleAlphaKey] ?: d.bubbleAlpha, p[panelAlphaKey] ?: d.panelAlpha,
                p[autoHideKey] ?: d.autoHideSeconds, p[alwaysPinKey] ?: d.alwaysPin,
                p[scaleKey] ?: d.scale, p[deckOnlyKey] ?: d.deckOnly,
            )
            val n = change(cur).normalized()
            p[bubbleAlphaKey] = n.bubbleAlpha
            p[panelAlphaKey] = n.panelAlpha
            p[autoHideKey] = n.autoHideSeconds
            p[alwaysPinKey] = n.alwaysPin
            p[scaleKey] = n.scale
            p[deckOnlyKey] = n.deckOnly
        }
    }

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

    private val cacheTtlKey = stringPreferencesKey("cache_ttl")

    /** How long cached lookups are reused without network. */
    val cacheTtl: Flow<CacheTtl> = context.dataStore.data.map { CacheTtl.fromName(it[cacheTtlKey]) }

    suspend fun setCacheTtl(ttl: CacheTtl) {
        context.dataStore.edit { it[cacheTtlKey] = ttl.name }
    }

    /** When the region finds no name, also scan the whole screen (slower). Off by default. */
    val fullScreenFallback: Flow<Boolean> = context.dataStore.data.map { it[fullFallbackKey] ?: false }

    suspend fun setFullScreenFallback(on: Boolean) {
        context.dataStore.edit { it[fullFallbackKey] = on }
    }
}
