package com.renault.maeva

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import com.renault.maeva.bridge.MaevaBridge
import com.renault.maeva.databinding.ActivityMainBinding
import com.renault.maeva.push.DeviceRegistry
import com.renault.maeva.push.NotificationTarget
import com.renault.maeva.session.SessionManager
import com.renault.maeva.web.MaevaLoadError
import com.renault.maeva.web.MaevaWebChromeClient
import com.renault.maeva.web.MaevaWebViewClient
import com.renault.maeva.web.UrlPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single screen of the Maeva shell. All business UI is the existing
 * Experience Cloud site rendered in the WebView; this class only owns
 * navigation policy, push deep links, uploads, downloads and session cleanup.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val trustedContext = AtomicBoolean(false)
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraRequest: PermissionRequest? = null
    private var lastFailedUrl: String? = null

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback ?: return@registerForActivityResult
            fileChooserCallback = null
            val uris = if (result.resultCode == RESULT_OK) {
                parseFileChooserResult(result.data)
            } else {
                null
            }
            callback.onReceiveValue(uris)
        }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingCameraRequest ?: return@registerForActivityResult
            pendingCameraRequest = null
            if (granted) request.grant(request.resources) else request.deny()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!inflateLayout()) return

        configureWebView()
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.retryButton.setOnClickListener { retryLastLoad() }
        registerBackNavigation()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState != null && binding.webView.restoreState(savedInstanceState) != null) {
            handleIntentTarget(intent, isColdStart = false)
        } else {
            val target = NotificationTarget.fromIntent(intent)?.resolveUrl()
            loadUrl(target ?: UrlPolicy.startUrl)
            NotificationTarget.clearFrom(intent)
        }
    }

    /**
     * Inflating the layout fails on devices where Android System WebView is
     * missing or disabled; without this guard the app would simply crash on
     * launch with no explanation.
     */
    private fun inflateLayout(): Boolean = try {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        true
    } catch (error: Exception) {
        AlertDialog.Builder(this)
            .setTitle(R.string.webview_missing_title)
            .setMessage(R.string.webview_missing_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
        false
    }

    private fun configureWebView() {
        val client = MaevaWebViewClient(
            trusted = trustedContext,
            onLoadingStateChanged = ::onLoadingStateChanged,
            onPageError = ::showError,
            onPageLoaded = ::onPageLoaded,
            openExternally = ::openExternally
        )

        binding.webView.apply {
            webViewClient = client
            webChromeClient = MaevaWebChromeClient(
                onProgress = ::onProgressChanged,
                onFileChooser = ::startFileChooser,
                onCameraPermission = ::handleCameraPermission,
                openUrl = ::openUrl
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                // The site must not be able to read the app sandbox.
                allowFileAccess = false
                allowContentAccess = false
                // Replaces the retired vendor user-agent marker. The Salesforce
                // components that branch on the mobile shell must test for
                // "MaevaAndroid" instead of the old vendor token.
                userAgentString = "$userAgentString MaevaAndroid/${BuildConfig.VERSION_NAME}"
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

            addJavascriptInterface(
                MaevaBridge(
                    context = applicationContext,
                    trusted = trustedContext,
                    evaluateJavascript = { script -> binding.webView.evaluateJavascript(script, null) }
                ),
                MaevaBridge.NAME
            )

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                downloadFile(url, userAgent, contentDisposition, mimeType)
            }

            isVerticalScrollBarEnabled = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Salesforce SSO redirects set cookies across my.salesforce.com and
            // the Experience Cloud domain, so third-party cookies are required.
            setAcceptThirdPartyCookies(binding.webView, true)
        }
    }

    private fun registerBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!binding.errorView.isShown && binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentTarget(intent, isColdStart = false)
    }

    /** Opens the record referenced by a tapped notification. */
    private fun handleIntentTarget(intent: Intent?, isColdStart: Boolean) {
        val target = NotificationTarget.fromIntent(intent) ?: return
        val url = target.resolveUrl()
        NotificationTarget.clearFrom(intent)

        if (url == null) {
            Snackbar.make(binding.root, R.string.toast_record_unavailable, Snackbar.LENGTH_LONG)
                .show()
            if (isColdStart) loadUrl(UrlPolicy.startUrl)
            return
        }
        loadUrl(url)
    }

    private fun loadUrl(url: String) {
        lastFailedUrl = null
        binding.errorView.visibility = android.view.View.GONE
        binding.swipeRefresh.visibility = android.view.View.VISIBLE
        binding.webView.loadUrl(url)
    }

    private fun retryLastLoad() {
        val url = lastFailedUrl ?: binding.webView.url ?: UrlPolicy.startUrl
        loadUrl(url)
    }

    private fun onLoadingStateChanged(isLoading: Boolean) {
        if (!isLoading) {
            binding.swipeRefresh.isRefreshing = false
            binding.progressBar.visibility = android.view.View.GONE
        } else {
            binding.progressBar.visibility = android.view.View.VISIBLE
        }
    }

    private fun onProgressChanged(progress: Int) {
        binding.progressBar.progress = progress
        binding.progressBar.visibility =
            if (progress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun onPageLoaded(url: String?) {
        // NEST_Base_LC routes logout through /secur/logout.jsp. Clearing after
        // that page has loaded ensures Salesforce ends the session server-side
        // before the local cookies are dropped.
        if (url != null && url.contains(LOGOUT_PATH, ignoreCase = true)) {
            DeviceRegistry.get(this).clearRegistration()
            SessionManager.clearSession(binding.webView) { loadUrl(UrlPolicy.startUrl) }
            return
        }

        if (!UrlPolicy.isInternal(url)) return
        binding.errorView.visibility = android.view.View.GONE
        binding.swipeRefresh.visibility = android.view.View.VISIBLE

        // Nudge the page to (re)register the device whenever the push token is
        // newer than the one Salesforce acknowledged.
        if (DeviceRegistry.get(this).needsRegistration()) {
            binding.webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('maeva:registrationNeeded'));",
                null
            )
        }
    }

    private fun showError(error: MaevaLoadError) {
        lastFailedUrl = binding.webView.url
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = android.view.View.GONE
        binding.swipeRefresh.visibility = android.view.View.GONE
        binding.errorView.visibility = android.view.View.VISIBLE
        when (error) {
            MaevaLoadError.OFFLINE -> {
                binding.errorTitle.setText(R.string.error_offline_title)
                binding.errorMessage.setText(R.string.error_offline_message)
            }

            MaevaLoadError.GENERIC -> {
                binding.errorTitle.setText(R.string.error_generic_title)
                binding.errorMessage.setText(R.string.error_generic_message)
            }
        }
    }

    /** Routes a URL produced by `window.open` through the normal policy. */
    private fun openUrl(url: String) {
        if (UrlPolicy.isInternal(url)) {
            loadUrl(url)
        } else {
            openExternally(url)
        }
    }

    private fun openExternally(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val launched = if (UrlPolicy.isHttpScheme(url)) {
            runCatching {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(this, uri)
            }.isSuccess
        } else {
            false
        }

        if (!launched) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (notFound: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFileChooser(intent: Intent, callback: ValueCallback<Array<Uri>>): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (notFound: ActivityNotFoundException) {
            fileChooserCallback = null
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun parseFileChooserResult(data: Intent?): Array<Uri>? {
        if (data == null) return null
        data.clipData?.let { clip ->
            return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        }
        return data.data?.let { arrayOf(it) }
    }

    private fun handleCameraPermission(request: PermissionRequest) {
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        if (!wantsCamera) {
            request.deny()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            pendingCameraRequest = request
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun downloadFile(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            // The document is behind the Salesforce session, so the WebView
            // cookies must travel with the DownloadManager request.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (downloadManager == null) {
            Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { downloadManager.enqueue(request) }
            .onSuccess {
                Toast.makeText(this, R.string.toast_download_started, Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Toast.makeText(this, R.string.toast_no_app_available, Toast.LENGTH_SHORT).show()
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::binding.isInitialized) binding.webView.saveState(outState)
    }

    override fun onPause() {
        if (this::binding.isInitialized) binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::binding.isInitialized) binding.webView.onResume()
    }

    override fun onDestroy() {
        if (this::binding.isInitialized) {
            binding.webView.removeJavascriptInterface(MaevaBridge.NAME)
            binding.webView.destroy()
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    private companion object {
        const val LOGOUT_PATH = "/secur/logout.jsp"
    }
}
