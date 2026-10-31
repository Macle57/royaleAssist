package com.macle.royaleassist.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.macle.royaleassist.L
import com.macle.royaleassist.image.loadArgbImage
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.ui.AppNavigator
import com.macle.royaleassist.ui.DeckCard
import com.macle.royaleassist.ui.DeepLink
import com.macle.royaleassist.ui.HistoryScreen
import com.macle.royaleassist.ui.LookupRequest
import com.macle.royaleassist.ui.QuickLookup
import com.macle.royaleassist.ui.RoyalTheme
import com.macle.royaleassist.ui.ScanRequest
import com.macle.royaleassist.ui.ScreenshotScan
import com.macle.royaleassist.ui.SetupScreen
import com.macle.royaleassist.ui.VERIFY_CHECKING
import com.macle.royaleassist.ui.VERIFY_DONE
import com.macle.royaleassist.ui.VerifyScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/**
 * The iOS app shell for SwiftUI (`UIViewControllerRepresentable`): the setup screen with the
 * screen capture and picture-in-picture bubble ([IosOverlay]), a name lookup and a screenshot scan,
 * the history list and the RoyaleAPI check (WKWebView).
 */
fun MainViewController(): UIViewController {
    IosApp.start()
    lateinit var host: UIViewController
    host = ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) {
        RoyalTheme { IosShell(presenter = { host }) }
    }
    return host
}

/**
 * Hands a `royaleassist://` URL (SwiftUI `onOpenURL`) to the shell: lookup, scan, history, setup,
 * verify (see [DeepLink]). False for other URLs.
 */
fun handleOpenUrl(url: String): Boolean = IosApp.openUrl(url)

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
    IosApp.start()
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
private fun IosShell(presenter: () -> UIViewController) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Setup) }
    var verifyQuery by remember { mutableStateOf<String?>(null) }
    var verifyStatus by remember { mutableStateOf(VERIFY_CHECKING) }
    var lookupRequest by remember { mutableStateOf<LookupRequest?>(null) }
    var scanRequest by remember { mutableStateOf<ScanRequest?>(null) }
    val navigator = remember {
        object : AppNavigator {
            override fun openHistory() { screen = Screen.History }
            override fun openVerify(query: String?) {
                verifyQuery = query
                verifyStatus = VERIFY_CHECKING
                screen = Screen.Verify
            }
            override fun openPlayerPage(tag: String) = openUrl(RoyaleApiWeb.playerUrl(tag))
        }
    }

    // royaleassist:// links (Shortcuts, `xcrun simctl openurl` in tests).
    LaunchedEffect(Unit) {
        IosApp.links.collect { entry ->
            val (id, link) = entry ?: return@collect
            when (link) {
                is DeepLink.Lookup -> { screen = Screen.Setup; lookupRequest = LookupRequest(link.name, link.clan, id) }
                is DeepLink.Scan -> {
                    screen = Screen.Setup
                    when {
                        link.path != null -> scope.launch {
                            val img = withContext(Dispatchers.Default) { runCatching { loadArgbImage(link.path) }.getOrNull() }
                            if (img == null) L.w("app: cannot read ${link.path}")
                            else scanRequest = ScanRequest(id, img, link.path)
                        }
                        link.latestPhoto -> Photos.latest { img -> if (img != null) scanRequest = ScanRequest(id, img, "latest photo") }
                        else -> scanRequest = ScanRequest(id)
                    }
                }
                DeepLink.History -> screen = Screen.History
                DeepLink.Setup -> screen = Screen.Setup
                is DeepLink.Verify -> navigator.openVerify(link.query)
            }
        }
    }

    when (screen) {
        Screen.Setup -> SetupScreen(
            IosApp.settings, IosApp.lookup, navigator,
            capture = IosOverlay,
            topContent = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    QuickLookup(IosApp.lookup, IosApp.settings, navigator, request = lookupRequest)
                    ScreenshotScan(
                        IosApp.analyzer, IosApp.lookup, IosApp.settings, navigator,
                        pickImage = { onPicked -> Photos.pick(presenter(), onPicked) },
                        request = scanRequest,
                    )
                }
            },
        )
        Screen.History -> HistoryScreen(IosApp.cache, IosApp.lookup, navigator, onBack = { screen = Screen.Setup })
        Screen.Verify -> VerifyScreen(verifyStatus, onClose = { screen = Screen.Setup }) { modifier ->
            VerifyWebView(RoyaleApiWeb.searchUrl(verifyQuery ?: "Macle", exact = true), modifier) { clearance ->
                verifyStatus = if (clearance) VERIFY_DONE else "Loaded RoyaleAPI, but no cf_clearance cookie was set."
                scope.launch {
                    delay(800)
                    if (screen == Screen.Verify) screen = Screen.Setup
                }
            }
        }
    }
}

private fun openUrl(url: String) {
    val u = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(u, options = emptyMap<Any?, Any?>(), completionHandler = null)
}
