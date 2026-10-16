package com.renault.maeva.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.renault.maeva.MainActivity
import com.renault.maeva.R
import kotlin.math.absoluteValue

/** Builds and posts the notifications that replace the retired vendor pipeline. */
object NotificationPublisher {

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = context.getString(R.string.notification_channel_id)
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableLights(true)
            lightColor = ContextCompat.getColor(context, R.color.maeva_primary)
        }
        manager.createNotificationChannel(channel)
    }

    fun publish(context: Context, title: String?, body: String?, target: NotificationTarget) {
        ensureChannel(context)

        val intent = target.writeTo(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )

        val requestCode = notificationIdFor(target)
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            context,
            context.getString(R.string.notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.maeva_primary))
            .setContentTitle(title ?: context.getString(R.string.app_name))
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(requestCode, notification)
        }
    }

    /**
     * Stable per-record id so a re-sent notification for the same record
     * replaces the previous one instead of stacking.
     */
    private fun notificationIdFor(target: NotificationTarget): Int {
        val key = target.notificationId
            ?: target.recordId
            ?: target.targetPath
            ?: System.currentTimeMillis().toString()
        return key.hashCode().absoluteValue.coerceAtLeast(1)
    }
}
