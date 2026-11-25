package app.motioncues.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import app.motioncues.R
import app.motioncues.ui.MainActivity

/** What the foreground service is currently doing, shown in its notification. */
enum class ServiceStatus(@StringRes val textRes: Int) {
    ACTIVE(R.string.notification_active),
    WAITING_FOR_VEHICLE(R.string.notification_waiting),
    NEEDS_OVERLAY_PERMISSION(R.string.notification_needs_overlay),
    NEEDS_ACTIVITY_PERMISSION(R.string.notification_needs_activity),
}

object CueNotifications {

    const val CHANNEL_ID = "motion_cues"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, status: ServiceStatus): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val turnOff = PendingIntent.getService(
            context,
            1,
            Intent(context, MotionCueService::class.java).setAction(MotionCueService.ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(status.textRes))
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.notification_turn_off), turnOff)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, status: ServiceStatus) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Silently ignored by the system when notifications are not permitted.
        manager.notify(NOTIFICATION_ID, build(context, status))
    }
}
