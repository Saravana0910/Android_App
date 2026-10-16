package com.renault.maeva.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Navigation policy for the Maeva shell.
 *
 * Only approved Salesforce/IdP hosts render inside the WebView. Anything else
 * is handed to the system, which keeps third-party content outside the
 * authenticated session.
 */
class MaevaWebViewClient(
    private val trusted: AtomicBoolean,
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onPageError: (MaevaLoadError) -> Unit,
    private val onPageLoaded: (String?) -> Unit,
    private val openExternally: (String) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return true
        return handleUrl(url)
    }

    fun handleUrl(url: String): Boolean {
        if (UrlPolicy.isInternal(url)) return false
        if (UrlPolicy.isHttpScheme(url) || UrlPolicy.isExternalAppScheme(url)) {
            openExternally(url)
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        trusted.set(UrlPolicy.isInternal(url))
        onLoadingStateChanged(true)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // Experience Cloud is a single-page app: the document never reloads, so
        // the trust flag has to follow client-side route changes too.
        trusted.set(UrlPolicy.isInternal(url))
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        trusted.set(UrlPolicy.isInternal(url))
        onLoadingStateChanged(false)
        if (UrlPolicy.isInternal(url)) {
            view.evaluateJavascript(READY_SIGNAL, null)
        }
        onPageLoaded(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        onLoadingStateChanged(false)
        onPageError(MaevaLoadError.fromErrorCode(error.errorCode))
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // 4xx responses are routinely returned for sub-resources and for
        // Salesforce redirects; only a failed main document is user-visible.
        if (!request.isForMainFrame) return
        if (errorResponse.statusCode < 500) return
        onLoadingStateChanged(false)
        onPageError(MaevaLoadError.GENERIC)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onLoadingStateChanged(false)
        onPageError(MaevaLoadError.GENERIC)
    }

    private companion object {
        /** Lets the Salesforce page detect the native shell without UA sniffing. */
        const val READY_SIGNAL =
            "(function(){window.maevaNativeShell=true;" +
                "window.dispatchEvent(new CustomEvent('maeva:ready'));})();"
    }
}

enum class MaevaLoadError {
    OFFLINE,
    GENERIC;

    companion object {
        fun fromErrorCode(code: Int): MaevaLoadError = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO -> OFFLINE

            else -> GENERIC
        }
    }
}
