package com.renault.maeva.push

import android.content.Intent
import android.os.Bundle
import com.renault.maeva.web.UrlPolicy

/**
 * The deep-link payload carried by a push notification.
 *
 * Expected FCM `data` keys:
 *  - `objectApiName` + `recordId` : opens the record detail page
 *  - `targetPath`                 : site-relative fallback path
 *  - `notificationId`             : server-side id, used for de-duplication
 */
data class NotificationTarget(
    val notificationId: String?,
    val objectApiName: String?,
    val recordId: String?,
    val targetPath: String?
) {

    /** Resolves the payload to a safe, approved URL, or null when unusable. */
    fun resolveUrl(): String? =
        UrlPolicy.recordUrl(objectApiName, recordId) ?: UrlPolicy.resolveTarget(targetPath)

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        putExtra(EXTRA_OBJECT_API_NAME, objectApiName)
        putExtra(EXTRA_RECORD_ID, recordId)
        putExtra(EXTRA_TARGET_PATH, targetPath)
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "com.renault.maeva.extra.NOTIFICATION_ID"
        const val EXTRA_OBJECT_API_NAME = "com.renault.maeva.extra.OBJECT_API_NAME"
        const val EXTRA_RECORD_ID = "com.renault.maeva.extra.RECORD_ID"
        const val EXTRA_TARGET_PATH = "com.renault.maeva.extra.TARGET_PATH"

        fun fromData(data: Map<String, String>): NotificationTarget = NotificationTarget(
            notificationId = data["notificationId"],
            objectApiName = data["objectApiName"],
            recordId = data["recordId"],
            targetPath = data["targetPath"]
        )

        /** Reads a target back from a notification-tap intent, if present. */
        fun fromIntent(intent: Intent?): NotificationTarget? {
            val extras: Bundle = intent?.extras ?: return null
            if (!extras.containsKey(EXTRA_RECORD_ID) && !extras.containsKey(EXTRA_TARGET_PATH)) {
                return null
            }
            return NotificationTarget(
                notificationId = extras.getString(EXTRA_NOTIFICATION_ID),
                objectApiName = extras.getString(EXTRA_OBJECT_API_NAME),
                recordId = extras.getString(EXTRA_RECORD_ID),
                targetPath = extras.getString(EXTRA_TARGET_PATH)
            )
        }

        fun clearFrom(intent: Intent?) {
            intent?.apply {
                removeExtra(EXTRA_NOTIFICATION_ID)
                removeExtra(EXTRA_OBJECT_API_NAME)
                removeExtra(EXTRA_RECORD_ID)
                removeExtra(EXTRA_TARGET_PATH)
            }
        }
    }
}
