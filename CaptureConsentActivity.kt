package com.snapgpt.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Transparent activity used because Android requires a visible, explicit consent flow
 * before each MediaProjection capture session. */
class CaptureConsentActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_MODE, intent.getStringExtra(EXTRA_MODE) ?: MODE_FULL)
            }
            ContextCompat.startForegroundService(this, service)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_MODE = "capture_mode"
        const val MODE_FULL = "full"
        const val MODE_AREA = "area"
    }
}
