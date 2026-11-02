package com.snapgpt.capture

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.snapgpt.services.AssistantState
import com.snapgpt.services.OverlayService

/**
 * Transparent activity that shows Android's own screen-capture consent dialog. SnapGPT cannot capture anything unless
 * the user taps "Start now" there. On Android 14+ the request is limited to the entire screen so the floating assistant
 * can work over any app.
 */
class ConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayService.deliverProjection(this, result.resultCode, data)
        } else {
            AssistantState.consentDenied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // result is delivered through the launcher after recreation
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }
}
