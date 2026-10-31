package com.macle.royaleassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.macle.royaleassist.host.AndroidNavigator
import com.macle.royaleassist.host.AndroidOverlayControls
import com.macle.royaleassist.host.AndroidScanArchive
import com.macle.royaleassist.ui.RoyalTheme
import com.macle.royaleassist.ui.SetupScreen
import kotlinx.coroutines.launch

/** Hosts the shared setup screen and hands it the Android-only parts (bubble, permissions, scans). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark UI: light status/navigation bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RoyaleAssistApp
        app.appScope.launch { app.ocr.ensureModels(applicationContext) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val navigator = AndroidNavigator(this)
        val overlay = AndroidOverlayControls(this)
        val scans = AndroidScanArchive(this, app.recorder, saveByDefault = BuildConfig.DEBUG)
        setContent {
            RoyalTheme { SetupScreen(app.settings, app.lookup, navigator, overlay, scans) }
        }
    }
}
