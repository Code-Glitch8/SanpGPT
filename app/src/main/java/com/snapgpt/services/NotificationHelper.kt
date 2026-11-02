package com.snapgpt.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.snapgpt.MainActivity
import com.snapgpt.R

/** The mandatory foreground-service notification, with a Stop action so the user can end everything immediately. */
object NotificationHelper {
    const val CHANNEL_ID = "snapgpt_assistant"
    const val NOTIFICATION_ID = 4711

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(context: Context, bubbleHidden: Boolean): Notification {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            context, 1, Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP), flags,
        )
        val toggle = PendingIntent.getService(
            context, 2, Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE_BUBBLE), flags,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_snap)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_action_stop), stop)
            .addAction(0, context.getString(if (bubbleHidden) R.string.notif_action_show else R.string.notif_action_hide), toggle)
            .build()
    }
}
