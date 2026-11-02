package com.snapgpt.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.snapgpt.container

/**
 * Foreground service that hosts the floating assistant and the MediaProjection session. It starts only after the user
 * has granted Android's screen-capture consent, and it can be stopped at any time from the notification or the app.
 */
class OverlayService : Service() {
    private var coordinator: ScanCoordinator? = null
    private var bubbleHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROJECTION -> handleProjection(intent)
            ACTION_TOGGLE_BUBBLE -> {
                bubbleHidden = coordinator?.toggleBubble() ?: bubbleHidden
                notifyUpdate()
            }
            ACTION_STOP -> stopAssistant()
            else -> stopSelf() // never restarted by the system without a fresh user authorisation
        }
        return START_NOT_STICKY
    }

    private fun handleProjection(intent: Intent) {
        val notification = NotificationHelper.build(this, bubbleHidden)
        // Must be called promptly after startForegroundService(); the projection consent was just granted.
        ServiceCompat.startForeground(
            this, NotificationHelper.NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopAssistant()
            return
        }
        val c = coordinator ?: ScanCoordinator(this, container).also { coordinator = it }
        try {
            c.onProjectionGranted(code, data)
            AssistantState.setRunning(true)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Android refused the screen-capture session. Please start the assistant again.", Toast.LENGTH_LONG).show()
            stopAssistant()
        }
    }

    private fun notifyUpdate() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, bubbleHidden))
    }

    private fun stopAssistant() {
        coordinator?.shutdown()
        coordinator = null
        AssistantState.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        coordinator?.onConfigurationChanged()
    }

    override fun onDestroy() {
        coordinator?.shutdown()
        coordinator = null
        AssistantState.setRunning(false)
        AssistantState.setProjectionReady(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PROJECTION = "com.snapgpt.action.PROJECTION"
        const val ACTION_STOP = "com.snapgpt.action.STOP"
        const val ACTION_TOGGLE_BUBBLE = "com.snapgpt.action.TOGGLE_BUBBLE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"

        /** Called by the consent activity with the user's authorisation. */
        fun deliverProjection(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, OverlayService::class.java)
                .setAction(ACTION_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        /** Stop the assistant from the app UI. */
        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
