package com.renault.maeva.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.renault.maeva.R

/**
 * Receives pushes from the Salesforce dispatcher that replaces the retired
 * vendor campaign API.
 *
 * Payloads must be data-only so this service always runs and can attach the
 * record deep link, including when the app is in the background.
 */
class MaevaMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val registry = DeviceRegistry.get(applicationContext)
        registry.pushToken = token
        // The token is re-published to Salesforce by the web bridge on the next
        // authenticated page load; there is no unauthenticated endpoint to call.
        registry.clearRegistration()
        Log.i(TAG, "FCM token rotated; re-registration pending next sign-in.")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val target = NotificationTarget.fromData(data)
        val title = data["title"] ?: message.notification?.title
        val body = data["body"] ?: message.notification?.body

        if (title.isNullOrBlank() && body.isNullOrBlank()) {
            Log.w(TAG, "Dropping push with no displayable content.")
            return
        }

        NotificationPublisher.publish(
            context = applicationContext,
            title = title ?: getString(R.string.app_name),
            body = body,
            target = target
        )
    }

    companion object {
        private const val TAG = "MaevaMessaging"
    }
}
