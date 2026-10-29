package io.github.elkelvinx.sashabridge.pen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG

/**
 * Notification that offers a capture from the PC when the annotation screen is not open.
 *
 * Android does not let an app open a screen from the background, so a capture asked for with
 * the PC shortcut while "Anotar" is closed waits behind this notification; touching it opens
 * the screen, which takes the capture. Any thread.
 *
 * @param context Any context; only its application context is kept.
 */
class CaptureNotifier(context: Context) {

    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Shows the notification, unless the user has not allowed notifications. */
    fun show() {
        if (!manager.areNotificationsEnabled()) {
            Log.w(LOG_TAG, "Notifications are off; the capture waits until Anotar is opened")
            return
        }
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AnnotateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.annotate_notification_title))
            .setContentText(context.getString(R.string.annotate_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(LOG_TAG, "Cannot show the capture notification", error)
        }
    }

    /** Removes the notification, once the capture was opened. */
    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    /** Creates the notification channel; repeating it is harmless. */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.annotate_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "captures"
        const val NOTIFICATION_ID = 1
    }
}
