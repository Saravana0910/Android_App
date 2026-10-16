package com.renault.maeva

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.google.firebase.FirebaseApp
import com.renault.maeva.push.DeviceRegistry
import com.renault.maeva.push.NotificationPublisher

class MaevaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        NotificationPublisher.ensureChannel(this)
        initialiseFirebase()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Firebase is optional at build time: when google-services.json is absent
     * the plugin is skipped and no default app exists. The shell must still run
     * - only push notifications are unavailable.
     */
    private fun initialiseFirebase() {
        runCatching { FirebaseApp.initializeApp(this) }
            .onFailure { Log.w(TAG, "Firebase initialisation failed", it) }

        val registry = DeviceRegistry.get(this)
        if (registry.isFirebaseAvailable) {
            registry.refreshPushToken { token ->
                Log.i(TAG, "FCM token ${if (token.isNullOrEmpty()) "unavailable" else "ready"}.")
            }
        } else {
            Log.w(TAG, "google-services.json missing: push notifications are disabled.")
        }
    }

    private companion object {
        const val TAG = "MaevaApplication"
    }
}
