package com.renault.maeva.web

import android.net.Uri
import com.renault.maeva.BuildConfig

/**
 * Single source of truth for "may this URL be rendered inside the WebView?"
 * and for building Maeva site URLs.
 *
 * The URL shapes mirror NEST_Base_LCHelper.js in the Salesforce org, which
 * builds record links as `<sitePath><sobjectType>/<recordId>`.
 */
object UrlPolicy {

    private const val SCHEME_HTTPS = "https"

    private val allowedDomains: List<String> = BuildConfig.MAEVA_ALLOWED_DOMAINS
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    private val recordIdPattern = Regex("^[a-zA-Z0-9]{15}([a-zA-Z0-9]{3})?$")

    /** Maps a Salesforce object API name to its Experience Cloud route segment. */
    private val recordRoutes: Map<String, String> = mapOf(
        "lead" to "lead",
        "opportunity" to "opportunity",
        "account" to "account",
        "contact" to "contact",
        "case" to "case",
        "task" to "task",
        "event" to "event"
    )

    private const val FALLBACK_ROUTE = "detail"

    val baseUrl: String = BuildConfig.MAEVA_BASE_URL.trimEnd('/')

    val sitePath: String = BuildConfig.MAEVA_SITE_PATH
        .let { if (it.startsWith("/")) it else "/$it" }
        .let { if (it.endsWith("/")) it else "$it/" }

    val homeUrl: String = baseUrl + sitePath

    val startUrl: String = baseUrl + BuildConfig.MAEVA_START_PATH

    /**
     * True when the URL is an https URL on an approved domain. Everything else
     * is handed to the system browser or a Custom Tab.
     */
    fun isInternal(url: String?): Boolean {
        val uri = url?.let(::parse) ?: return false
        if (!uri.scheme.equals(SCHEME_HTTPS, ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return allowedDomains.any { host == it || host.endsWith(".$it") }
    }

    /** Schemes that must be delegated to another app rather than a browser. */
    fun isExternalAppScheme(url: String?): Boolean {
        val scheme = url?.let(::parse)?.scheme?.lowercase() ?: return false
        return scheme in setOf("tel", "mailto", "sms", "smsto", "geo", "intent", "market", "whatsapp")
    }

    fun isHttpScheme(url: String?): Boolean {
        val scheme = url?.let(::parse)?.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == SCHEME_HTTPS
    }

    /**
     * Builds the site URL for a record, e.g. `Lead` + `00Q...` ->
     * `https://<base>/NestSFA/s/lead/00Q...`. Returns null when the payload is
     * not a plausible Salesforce record reference.
     */
    fun recordUrl(objectApiName: String?, recordId: String?): String? {
        val id = recordId?.trim().orEmpty()
        if (!recordIdPattern.matches(id)) return null
        val route = recordRoutes[objectApiName?.trim()?.lowercase()] ?: FALLBACK_ROUTE
        return "$homeUrl$route/$id"
    }

    /**
     * Resolves a server-supplied target path. Only site-relative paths and URLs
     * already on an approved domain are accepted, so a push payload cannot
     * redirect the WebView to an attacker-controlled page.
     */
    fun resolveTarget(path: String?): String? {
        val candidate = path?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        if (candidate.startsWith("//")) return null
        if (candidate.startsWith("/")) return baseUrl + candidate
        if (isInternal(candidate)) return candidate
        return null
    }

    fun hostOf(url: String?): String? = url?.let(::parse)?.host?.lowercase()

    private fun parse(url: String): Uri? = runCatching { Uri.parse(url) }.getOrNull()
}
