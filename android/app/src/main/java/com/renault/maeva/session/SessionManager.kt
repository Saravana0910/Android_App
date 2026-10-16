package com.renault.maeva.session

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Clears the Experience Cloud session material held by the WebView.
 *
 * The app never stores Salesforce credentials or tokens itself: the session is
 * a normal `sid` cookie owned by the WebView, exactly as in a browser.
 */
object SessionManager {

    fun clearSession(webView: WebView?, onComplete: () -> Unit = {}) {
        webView?.clearHistory()
        webView?.clearFormData()
        WebStorage.getInstance().deleteAllData()

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies { cookieManager.flush(); onComplete() }
    }
}
