package com.khalid.browser

import java.net.URLDecoder

/**
 * 1.0.3: where a link goes. Every web link stays in Browser; only real app links (phone, email, maps,
 * the Play Store, app deep links) leave, and only when the person tapped them.
 * Pure Kotlin so the rules are unit tested without a phone.
 */
sealed class LinkRoute {
    /** Let the page load it normally. */
    object Stay : LinkRoute()
    /** Load this web address in Browser instead. */
    data class InApp(val url: String) : LinkRoute()
    /** Hand to another app. If no app can open it, load [fallback] (a web address) in Browser. */
    data class External(val url: String, val fallback: String?) : LinkRoute()
    /** Do nothing (another browser, or an app link no one tapped). */
    object Block : LinkRoute()
}

object LinkRouter {
    private val WEB = setOf("http", "https")
    private val PAGE = setOf("about", "data", "blob", "javascript", "file")

    /** Schemes other browsers register to steal links ("open in Chrome"). */
    private val BROWSER_SCHEMES = setOf(
        "googlechrome", "googlechromes", "chrome", "firefox", "mozilla", "fenix", "focus",
        "opera", "opera-http", "opera-https", "microsoft-edge", "microsoft-edge-http", "microsoft-edge-https",
        "brave", "samsunginternet", "sbrowser", "duckduckgo", "vivaldi", "yandexbrowser", "ucbrowser", "mibrowser",
        "x-safari-http", "x-safari-https",
    )

    /** Other browsers' app ids: an intent:// link aimed at one of them stays here. */
    val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.google.android.apps.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx", "com.microsoft.emmx", "com.brave.browser",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser",
        "com.yandex.browser", "com.UCMobile.intl", "com.mi.globalbrowser", "com.android.browser",
        "com.kiwibrowser.browser", "com.android.htmlviewer",
    )

    fun scheme(url: String): String = url.substringBefore(':', "").trim().lowercase()

    private fun isWeb(url: String?) = url != null && scheme(url) in WEB && url.length > scheme(url).length + 3

    /**
     * [tapped]: the person tapped something (request.hasGesture). [redirect]: a server redirect, usually
     * right after a tap. [mainFrame]: the whole page navigates, not a frame inside it.
     */
    fun route(url: String, tapped: Boolean, redirect: Boolean = false, mainFrame: Boolean = true): LinkRoute {
        val s = scheme(url)
        if (s in WEB || s in PAGE || s.isEmpty()) return LinkRoute.Stay
        val intended = tapped || redirect
        val r = when {
            s == "intent" -> intentRoute(url, intended)
            s in BROWSER_SCHEMES -> embeddedWebUrl(url)?.let { LinkRoute.InApp(it) } ?: LinkRoute.Block
            intended -> LinkRoute.External(url, null)
            else -> LinkRoute.Block
        }
        // A frame inside the page (often an ad) can't take the page somewhere or open apps on its own.
        if (!mainFrame && !tapped) return LinkRoute.Block
        return r
    }

    /** The pieces of an intent:// link: intent://host/path#Intent;scheme=https;package=...;S.browser_fallback_url=...;end */
    class IntentLink(val data: String, val scheme: String?, val pkg: String?, val fallback: String?) {
        /** The web address it points at, when its target is a web page. */
        val webUrl: String? get() = if (scheme != null && scheme.lowercase() in WEB && data.startsWith("//") && data.length > 2) "${scheme.lowercase()}:$data" else null
    }

    fun parseIntent(url: String): IntentLink {
        val body = url.substringAfter(':')
        val data = body.substringBefore("#Intent;")
        val extras = if (body.contains("#Intent;")) body.substringAfter("#Intent;").substringBefore(";end") else ""
        var scheme: String? = null; var pkg: String? = null; var fallback: String? = null
        extras.split(';').forEach { kv ->
            val k = kv.substringBefore('=', ""); val v = kv.substringAfter('=', "")
            when (k) {
                "scheme" -> scheme = v
                "package" -> pkg = v
                "S.browser_fallback_url" -> fallback = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
        }
        return IntentLink(data, scheme, pkg, fallback)
    }

    private fun intentRoute(url: String, intended: Boolean): LinkRoute {
        val i = parseIntent(url)
        val fb = i.fallback?.takeIf { isWeb(it) }
        // A web page dressed as an app link ("open in Chrome"): open it here.
        i.webUrl?.let { return LinkRoute.InApp(it) }
        // Aimed at another browser: use its web fallback, never that browser.
        if (i.pkg != null && i.pkg in BROWSER_PACKAGES) return fb?.let { LinkRoute.InApp(it) } ?: LinkRoute.Block
        if (i.scheme != null && i.scheme.lowercase() in BROWSER_SCHEMES)
            return embeddedWebUrl("${i.scheme}:${i.data}")?.let { LinkRoute.InApp(it) } ?: fb?.let { LinkRoute.InApp(it) } ?: LinkRoute.Block
        // A real app link: only when tapped. Otherwise the web version, if there is one.
        if (!intended) return fb?.let { LinkRoute.InApp(it) } ?: LinkRoute.Block
        return LinkRoute.External(url, fb)
    }

    /** googlechrome://example.com/x -> https://example.com/x, microsoft-edge:https://a.b, firefox://open-url?url=... */
    fun embeddedWebUrl(url: String): String? {
        val s = scheme(url)
        val rest = url.substring(s.length + 1)
        // ...?url=https%3A%2F%2F...
        Regex("[?&](?:url|link|u)=([^&]+)").find(rest)?.groupValues?.get(1)?.let { enc ->
            val dec = runCatching { URLDecoder.decode(enc, "UTF-8") }.getOrDefault(enc)
            if (isWeb(dec)) return dec
        }
        val r = rest.removePrefix("//")
        if (isWeb(r)) return r
        if (r.isBlank() || r.startsWith("open") || r.startsWith("navigate")) return null
        val secure = !(s == "googlechrome" || s.endsWith("-http") || s == "x-safari-http")
        val host = r.substringBefore('/').substringBefore('?')
        if (!host.contains('.') || host.contains(' ')) return null
        return (if (secure) "https://" else "http://") + r
    }
}
