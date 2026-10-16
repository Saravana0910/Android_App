package com.renault.maeva.web

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Handles the browser-level behaviour the Maeva pages rely on: progress,
 * `window.open`, file uploads and camera permission prompts.
 */
class MaevaWebChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onFileChooser: (Intent, ValueCallback<Array<Uri>>) -> Boolean,
    private val onCameraPermission: (PermissionRequest) -> Unit,
    private val openUrl: (String) -> Unit
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    /**
     * NEST_Base_LCHelper.js opens links with `window.open`. Multiple windows
     * are enabled so those calls reach here; the target URL is then routed
     * through the same policy as any other navigation.
     */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        val probe = WebView(view.context)
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                probeView: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                request.url?.toString()?.let(openUrl)
                // Destroying the probe synchronously from its own callback can
                // crash the WebView; defer it to the next main-thread message.
                Handler(Looper.getMainLooper()).post { probeView.destroy() }
                return true
            }
        }
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = probe
        resultMsg.sendToTarget()
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = runCatching {
        onFileChooser(fileChooserParams.createIntent(), filePathCallback)
    }.getOrElse {
        filePathCallback.onReceiveValue(null)
        false
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        onCameraPermission(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        super.onPermissionRequestCanceled(request)
    }
}
