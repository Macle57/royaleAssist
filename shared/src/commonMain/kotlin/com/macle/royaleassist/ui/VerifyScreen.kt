package com.macle.royaleassist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.macle.royaleassist.overlay.PillButton

/** Status line while the RoyaleAPI check is running. */
const val VERIFY_CHECKING = "Checking RoyaleAPI... tap the checkbox if one appears."

/** Status line once a real RoyaleAPI page has loaded. */
const val VERIFY_DONE = "Verified. Returning..."

/**
 * Chrome of the RoyaleAPI browser check: a status line and Close above the platform web view,
 * which the host passes as [web] (Android: the system WebView whose cookies the HTTP client
 * shares; iOS: a WKWebView, filled in with the iOS app shell).
 */
@Composable
fun VerifyScreen(status: String, onClose: () -> Unit, web: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize().background(VerifyBackground).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillButton("Close", primary = false, onClick = onClose)
        }
        web(Modifier.fillMaxWidth().weight(1f))
    }
}

private val VerifyBackground = Color(0xFF0E1A2B)
