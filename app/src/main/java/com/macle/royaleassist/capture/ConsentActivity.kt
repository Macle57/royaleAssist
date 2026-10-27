package com.macle.royaleassist.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import com.macle.royaleassist.L
import com.macle.royaleassist.overlay.OverlayService

/**
 * Invisible trampoline: shows the system "start recording / casting" consent dialog and forwards
 * the result to [OverlayService]. Used both from the main screen and when the bubble is tapped
 * without an active projection (Android 14+ requires fresh consent for every projection session).
 */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole display only: a single-app capture would miss the game when another app is on top.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            L.i("consent: result=${if (resultCode == RESULT_OK) "granted" else "denied"}")
            val svc = Intent(this, OverlayService::class.java)
            if (resultCode == RESULT_OK && data != null) {
                svc.setAction(OverlayService.ACTION_PROJECTION_GRANTED)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            } else {
                svc.setAction(OverlayService.ACTION_PROJECTION_DENIED)
            }
            startForegroundService(svc)
            finish()
        }
    }

    companion object {
        private const val REQ = 42

        fun intent(context: Context) = Intent(context, ConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
