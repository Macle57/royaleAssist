package com.macle.royaleassist.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.ui.AppNavigator
import com.macle.royaleassist.ui.DeckCard
import com.macle.royaleassist.ui.HistoryScreen
import com.macle.royaleassist.ui.QuickLookup
import com.macle.royaleassist.ui.Royal
import com.macle.royaleassist.ui.RoyalTheme
import com.macle.royaleassist.ui.SetupScreen
import com.macle.royaleassist.ui.VERIFY_CHECKING
import com.macle.royaleassist.ui.VerifyScreen
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/**
 * The iOS app shell for SwiftUI (`UIViewControllerRepresentable`): the setup screen with a name
 * lookup on top, the history list and the RoyaleAPI check. No bubble or screen reading on iOS.
 */
fun MainViewController(): UIViewController {
    IosApp.ensureImages()
    return ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        RoyalTheme { IosShell() }
    }
}

/**
 * The deck card for one lookup result, for the picture-in-picture / Slide Over card. [onClose] is
 * called when the user closes the (unpinned) card.
 */
fun DeckCardViewController(
    result: LookupResult,
    decksToShow: Int = 3,
    maxWidthDp: Float = 320f,
    onClose: () -> Unit = {},
): UIViewController {
    IosApp.ensureImages()
    return ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        RoyalTheme {
            DeckCard(
                result,
                deckOnly = true,
                decksToShow = decksToShow,
                alpha = 1f,
                maxWidthDp = maxWidthDp,
                onOpenWeb = { openUrl(RoyaleApiWeb.playerUrl(it)) },
                onClose = onClose,
            )
        }
    }
}

private enum class Screen { Setup, History, Verify }

@Composable
private fun IosShell() {
    var screen by remember { mutableStateOf(Screen.Setup) }
    var verifyQuery by remember { mutableStateOf<String?>(null) }
    val navigator = remember {
        object : AppNavigator {
            override fun openHistory() { screen = Screen.History }
            override fun openVerify(query: String?) { verifyQuery = query; screen = Screen.Verify }
            override fun openPlayerPage(tag: String) = openUrl(RoyaleApiWeb.playerUrl(tag))
        }
    }
    when (screen) {
        Screen.Setup -> SetupScreen(
            IosApp.settings, IosApp.lookup, navigator,
            topContent = { QuickLookup(IosApp.lookup, IosApp.settings, navigator) },
        )
        Screen.History -> HistoryScreen(IosApp.cache, IosApp.lookup, navigator, onBack = { screen = Screen.Setup })
        Screen.Verify -> VerifyScreen(VERIFY_CHECKING, onClose = { screen = Screen.Setup }) { modifier ->
            VerifyWebView(RoyaleApiWeb.searchUrl(verifyQuery ?: "Macle", exact = true), modifier)
        }
    }
}

/**
 * Slot for the WKWebView that passes RoyaleAPI's Cloudflare check (filled in with the Xcode app:
 * `UIKitView` around a WKWebView whose cookies go to [IosApp.httpPlatform]). Placeholder for now.
 */
@Composable
fun VerifyWebView(url: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Royal.Night).padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "The RoyaleAPI check needs the in-app browser, which comes with the iPad app.\n$url",
            style = MaterialTheme.typography.bodySmall,
            color = Royal.Mist,
            textAlign = TextAlign.Center,
        )
    }
}

private fun openUrl(url: String) {
    val u = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(u, options = emptyMap<Any?, Any?>(), completionHandler = null)
}
