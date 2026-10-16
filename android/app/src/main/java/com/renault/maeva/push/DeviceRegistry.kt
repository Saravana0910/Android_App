package com.renault.maeva.push

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/**
 * Holds the three identifiers the Salesforce org needs to address this device:
 *
 *  - installation id : app-generated, stable per install, survives token rotation
 *  - FCM token       : the actual push address, rotates
 *  - registered token: the token Salesforce last acknowledged
 *
 * The Salesforce User ID is deliberately NOT stored here. The server derives it
 * from the authenticated session (UserInfo.getUserId()) and must never trust a
 * user id supplied by the client.
 */
class DeviceRegistry private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val appContext = context.applicationContext

    val installationId: String
        get() = prefs.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_INSTALLATION_ID, it) }
        }

    var pushToken: String?
        get() = prefs.getString(KEY_PUSH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_PUSH_TOKEN, value) }

    /** Token value Salesforce has confirmed, used to skip redundant registrations. */
    var registeredToken: String?
        get() = prefs.getString(KEY_REGISTERED_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_REGISTERED_TOKEN, value) }

    val isFirebaseAvailable: Boolean
        get() = FirebaseApp.getApps(appContext).isNotEmpty()

    fun needsRegistration(): Boolean {
        val current = pushToken
        return !current.isNullOrEmpty() && current != registeredToken
    }

    /** Clears the push binding on logout so a shared device stops receiving alerts. */
    fun clearRegistration() {
        prefs.edit { remove(KEY_REGISTERED_TOKEN) }
    }

    /**
     * Requests the current FCM token. Safe to call when Firebase is not
     * configured: [onResult] is then invoked with null.
     */
    fun refreshPushToken(onResult: (String?) -> Unit) {
        if (!isFirebaseAvailable) {
            Log.w(TAG, "Firebase is not configured; push notifications are disabled.")
            onResult(null)
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    pushToken = token
                    onResult(token)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Unable to obtain an FCM token", error)
                    onResult(null)
                }
        }.onFailure {
            Log.w(TAG, "FirebaseMessaging is unavailable", it)
            onResult(null)
        }
    }

    companion object {
        private const val TAG = "MaevaDeviceRegistry"
        private const val PREFS_NAME = "maeva_device"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_PUSH_TOKEN = "push_token"
        private const val KEY_REGISTERED_TOKEN = "registered_token"

        @Volatile
        private var instance: DeviceRegistry? = null

        fun get(context: Context): DeviceRegistry =
            instance ?: synchronized(this) {
                instance ?: DeviceRegistry(context).also { instance = it }
            }
    }
}
