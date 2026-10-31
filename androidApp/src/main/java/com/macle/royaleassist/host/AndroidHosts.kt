package com.macle.royaleassist.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.macle.royaleassist.HistoryActivity
import com.macle.royaleassist.capture.ConsentActivity
import com.macle.royaleassist.debug.ScanRecorder
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.VerifyActivity
import com.macle.royaleassist.overlay.OverlayService
import com.macle.royaleassist.ui.AppNavigator
import com.macle.royaleassist.ui.OverlayControls
import com.macle.royaleassist.ui.OverlayStatus
import com.macle.royaleassist.ui.ScanArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Navigation of the shared screens through activities and intents. */
class AndroidNavigator(private val context: Context) : AppNavigator {
    override fun openHistory() {
        context.startActivity(HistoryActivity.intent(context))
    }

    override fun openVerify(query: String?) {
        context.startActivity(VerifyActivity.intent(context, query))
    }

    override fun openPlayerPage(tag: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RoyaleApiWeb.playerUrl(tag))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The bubble service, overlay permission and MediaProjection consent behind the setup screen. */
class AndroidOverlayControls(private val context: Context) : OverlayControls {
    override val status: StateFlow<OverlayStatus> get() = OverlayService.status

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun openOverlayPermission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }

    override fun requestCapture() {
        context.startActivity(ConsentActivity.intent(context))
    }

    override fun startBubble() = OverlayService.start(context)

    override fun stopBubble() = OverlayService.stop(context)

    override fun editRegion() = OverlayService.send(context, OverlayService.ACTION_EDIT_REGION)

    override fun showRegion() = OverlayService.send(context, OverlayService.ACTION_SHOW_REGION)

}

/** The debug scan recorder; [context] must be an activity (the export opens a share sheet). */
class AndroidScanArchive(
    private val context: Context,
    private val recorder: ScanRecorder,
    override val saveByDefault: Boolean,
) : ScanArchive {
    override val keep: Int get() = ScanRecorder.KEEP

    override suspend fun stats(): Pair<Int, Long> = withContext(Dispatchers.IO) { recorder.stats().let { it.count to it.bytes } }

    override suspend fun clear() = withContext(Dispatchers.IO) { recorder.clear() }

    override suspend fun export() {
        val share = recorder.exportBundle()
        context.startActivity(Intent.createChooser(share, "Share debug bundle"))
    }
}
