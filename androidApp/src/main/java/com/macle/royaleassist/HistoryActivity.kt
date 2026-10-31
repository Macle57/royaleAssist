package com.macle.royaleassist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.macle.royaleassist.host.AndroidNavigator
import com.macle.royaleassist.ui.HistoryScreen
import com.macle.royaleassist.ui.RoyalTheme

/** Hosts the shared history list (every looked-up player, newest first). */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val app = application as RoyaleAssistApp
        val navigator = AndroidNavigator(this)
        setContent { RoyalTheme { HistoryScreen(app.cache, app.lookup, navigator, onBack = { finish() }) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, HistoryActivity::class.java)
    }
}
