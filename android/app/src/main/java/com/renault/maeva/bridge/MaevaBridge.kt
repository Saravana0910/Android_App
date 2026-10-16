package com.renault.maeva.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.renault.maeva.BuildConfig
import com.renault.maeva.push.DeviceRegistry
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only native surface exposed to page JavaScript.
 *
 * `addJavascriptInterface` attaches to every document the WebView loads, so
 * every method re-checks [trusted] before returning anything. The bridge
 * intentionally exposes only push-routing identifiers - never Salesforce
 * session tokens, cookies or user data.
 *
 * Salesforce side (Aura/LWC) usage:
 *
 *   if (window.MaevaAndroid) {
 *       const device = JSON.parse(window.MaevaAndroid.getDeviceInfo());
 *       // call an @AuraEnabled method that upserts a Mobile_Device__c record
 *       // for UserInfo.getUserId() with device.installationId + device.pushToken
 *       registerDevice(device).then(() =>
 *           window.MaevaAndroid.onDeviceRegistered(device.pushToken));
 *   }
 */
class MaevaBridge(
    context: Context,
    private val trusted: AtomicBoolean,
    private val evaluateJavascript: (String) -> Unit
) {

    private val registry = DeviceRegistry.get(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** JSON describing this installation, for the Salesforce registration call. */
    @JavascriptInterface
    fun getDeviceInfo(): String {
        if (!allowed("getDeviceInfo")) return "{}"
        return JSONObject()
            .put("platform", "android")
            .put("installationId", registry.installationId)
            .put("pushToken", registry.pushToken.orEmpty())
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("osVersion", android.os.Build.VERSION.RELEASE.orEmpty())
            .put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("pushAvailable", registry.isFirebaseAvailable)
            .put("needsRegistration", registry.needsRegistration())
            .toString()
    }

    /**
     * Asks for a fresh FCM token. The result is delivered asynchronously as a
     * `maeva:pushTokenReady` DOM event because the token call cannot block the
     * JavaScript thread.
     */
    @JavascriptInterface
    fun requestPushToken() {
        if (!allowed("requestPushToken")) return
        registry.refreshPushToken { token ->
            val detail = JSONObject()
                .put("pushToken", token.orEmpty())
                .put("installationId", registry.installationId)
                .toString()
            mainHandler.post {
                evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('maeva:pushTokenReady'," +
                        "{detail: $detail}));"
                )
            }
        }
    }

    /** Called by the page once Salesforce has stored the device record. */
    @JavascriptInterface
    fun onDeviceRegistered(token: String?) {
        if (!allowed("onDeviceRegistered")) return
        if (!token.isNullOrBlank()) {
            registry.registeredToken = token
            Log.i(TAG, "Device registration acknowledged by Salesforce.")
        }
    }

    /** Called by the page just before it performs a Salesforce logout. */
    @JavascriptInterface
    fun onSessionEnded() {
        if (!allowed("onSessionEnded")) return
        registry.clearRegistration()
    }

    private fun allowed(method: String): Boolean {
        if (trusted.get()) return true
        Log.w(TAG, "Blocked $method from an untrusted document.")
        return false
    }

    companion object {
        const val NAME = "MaevaAndroid"
        private const val TAG = "MaevaBridge"
    }
}
