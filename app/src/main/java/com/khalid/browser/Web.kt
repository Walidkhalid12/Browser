package com.khalid.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(ctx0: Context, st: BrowserState, tab: Tab): WebView {
    // "Dark pages": the web engine darkens sites when its context theme is dark (isLightTheme=false).
    val ctx = if (st.forceDark) android.view.ContextThemeWrapper(ctx0, R.style.Theme_Browser_WebDark) else ctx0
    val wv = WebView(ctx)
    wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    with(wv.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        mediaPlaybackRequiresUserGesture = true
        // 1.0.3: new windows come to onCreateWindow (opened as tabs) instead of leaving the app.
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (tab.private) cacheMode = WebSettings.LOAD_NO_CACHE
        // Smoother scrolling: tiles just outside the screen are drawn ahead of time.
        // 1.0.0: only for the tab in front (hidden tabs don't spend GPU memory on it; the tab-switch effect keeps this in step).
        offscreenPreRaster = tab.id == st.activeId
    }
    // Visible tab gets full renderer priority; hidden tabs step back on their own.
    runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
    wv.isHorizontalScrollBarEnabled = false
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
    }
    runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
    // No "X-Requested-With: app id" header: pages (and Google sign-in) see a normal browser.
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
    }
    wv.isVerticalScrollBarEnabled = false

    // The bar follows the page 1:1 while reading (no recomposition: barK is read only in layout/draw),
    // then settles to full or small when the scroll stops.
    val dens = ctx.resources.displayMetrics.density
    val settle = Runnable { if (tab.id == st.activeId) st.settleBar() }
    wv.setOnScrollChangeListener { _, _, y, _, oldY ->
        if (tab.id != st.activeId) return@setOnScrollChangeListener
        val dy = y - oldY
        if (dy == 0) return@setOnScrollChangeListener
        if (y <= 8 * dens) st.settleBar(0f)
        else if (dy > 0 && !wv.canScrollVertically(1)) st.settleBar(0f) // reached the end: show the bar again
        else st.dragBar(dy.toFloat(), 72 * dens)
        wv.removeCallbacks(settle); wv.postDelayed(settle, 110)
        scheduleSample(tab, 220)
    }
    wv.settings.userAgentString = mobileUA(wv.settings.userAgentString)
    if (tab.desktop) wv.settings.userAgentString = desktopUA(wv.settings.userAgentString)
    wv.settings.textZoom = st.site.zoom(tab.host) ?: st.textZoom
    tab.zoom = wv.settings.textZoom
    tab.pageHost = tab.host
    tab.shields = st.site.shields(tab.host)
    st.site.desktop(tab.host)?.let { d -> if (d != tab.desktop) { tab.desktop = d; wv.settings.userAgentString = if (d) desktopUA(wv.settings.userAgentString) else mobileUA(android.webkit.WebSettings.getDefaultUserAgent(ctx)) } }
    // Page bridge: media playing (background audio / picture-in-picture) and whether blocking is on for a site.
    wv.addJavascriptInterface(PageBridge(st, tab), "BrowserApp")
    val docStart = runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, Scripts.DOC_START, setOf("*")); true
        } else false
    }.getOrDefault(false)
    wv.setFindListener { active, count, done -> if (done) { st.findCount = count; st.findAt = if (count == 0) 0 else active + 1 } }

    wv.webViewClient = object : WebViewClient() {
        // 1.0.3: every web link stays in Browser (see LinkRouter); only real app links leave, when tapped.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when (val r = LinkRouter.route(url, request.hasGesture(), request.isRedirect, request.isForMainFrame)) {
                LinkRoute.Stay -> false
                LinkRoute.Block -> true
                is LinkRoute.InApp -> { view.loadUrl(r.url); true }
                is LinkRoute.External -> {
                    if (!st.host.openExternal(r.url)) r.fallback?.let { view.loadUrl(it) }
                    true
                }
            }
        }
        // Ads and trackers: third-party requests to listed hosts get an empty answer.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
            if (!tab.shields || request.isForMainFrame) return null
            val h = request.url.host ?: return null
            val page = tab.pageHost
            if (page.isNotEmpty() && (h == page || h.endsWith(".$page"))) return null
            if (!Blocker.blocked(h)) return null
            val n = tab.blockedRaw.incrementAndGet()
            WidgetStats.blocked.incrementAndGet()
            if (n == 1 || n % 4 == 0) Bg.main.post { tab.blocked = tab.blockedRaw.get() }
            return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }
        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            // Never load a page with a bad certificate. Tell the user instead of failing silently.
            handler.cancel()
            tab.sslError = true
            if (tab.id == st.activeId) st.notify("Not secure: this site's certificate is invalid")
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val newHost = hostOf(url)
            if (newHost != tab.pageHost) {
                tab.pageHost = newHost
                tab.shields = st.site.shields(newHost)
                tab.favicon = null
                val z = st.site.zoom(newHost) ?: st.textZoom
                if (z != tab.zoom) { tab.zoom = z; view.settings.textZoom = z }
                val d = st.site.desktop(newHost)
                if (d != null && d != tab.desktop) { tab.url = url; setDesktop(tab, d, remember = false); return }
            }
            tab.blockedRaw.set(0); tab.blocked = 0
            tab.sslError = false
            if (tab.private) originOf(url)?.let { tab.privateOrigins.add(it) }
            tab.url = url
            tab.progress = maxOf(tab.progress.coerceAtMost(99), 5)
            if (tab.id == st.activeId) st.barCollapsed = false
        }
        override fun onPageCommitVisible(view: WebView, url: String) { scheduleSample(tab, 60) }
        // The page's renderer died (low memory, a heavy site). Without this the whole app is killed.
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            runCatching {
                (tab.refresher?.parent as? ViewGroup)?.removeView(tab.refresher)
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            if (tab.view === view) { tab.view = null; tab.refresher = null; tab.saved = null; tab.pending = tab.url }
            tab.progress = 0
            tab.gen++
            currentState?.notify("Page reloaded after it stopped")
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.url = url
            view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { tab.title = it }
            tab.canBack = view.canGoBack(); tab.canForward = view.canGoForward()
            if (!tab.private) st.store.visit(url, tab.title)
            if (!docStart) view.evaluateJavascript(Scripts.LATE, null)
            tab.blocked = tab.blockedRaw.get()
            if (tab.restoreScroll > 0) { val y = tab.restoreScroll; tab.restoreScroll = 0; view.postDelayed({ view.scrollTo(0, y) }, 250) }
            st.saveTabs()
            tab.refresher?.isRefreshing = false
            scheduleSample(tab, 120); scheduleSample(tab, 700)
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.canBack = view.canGoBack(); tab.canForward = view.canGoForward()
        }
    }
    wv.webChromeClient = object : WebChromeClient() {
        // 1.0.3: pop-ups and new-window links (target=_blank, window.open) open as a new tab in Browser,
        // never in another browser. Pop-ups a page opens on its own, without a tap, are blocked.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) {
                if (tab.id == st.activeId) st.notify("Pop-up blocked")
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val t = st.openPopupTab(tab)
            val nw = createWebView(ctx0, st, t)
            t.view = nw
            transport.webView = nw
            resultMsg.sendToTarget()
            return true
        }
        // The page closed its pop-up (window.close, e.g. after a sign-in): close that tab and go back.
        override fun onCloseWindow(window: WebView) {
            st.tabs.firstOrNull { it.view === window }?.let { st.closePopupTab(it) }
        }
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            val before = tab.progress
            tab.progress = newProgress
            if ((before < 40 && newProgress >= 40) || (before < 80 && newProgress >= 80)) scheduleSample(tab, 30)
        }
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { if (icon != null) tab.favicon = icon }
        // Camera / microphone for a site: ask once per site, remember the answer.
        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            val wants = request.resources.mapNotNull {
                when (it) {
                    android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                    android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "media"
                    else -> null
                }
            }
            if (wants.isEmpty()) { request.deny(); return }
            val host = hostOf(request.origin.toString())
            val res = request.resources.filter { it != android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX }.toTypedArray()
            sitePermission(st, host, wants) { ok -> if (ok) request.grant(res) else request.deny() }
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
            sitePermission(st, hostOf(origin), listOf("location")) { ok -> callback.invoke(origin, ok, false) }
        }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            st.host.pickFiles(cb, params)
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            st.fullView?.let { st.fullCallback?.onCustomViewHidden() }
            st.fullView = view; st.fullCallback = callback
            st.host.setFullscreen(true)
            // Landscape video turns the screen; portrait video stays upright.
            wv.postDelayed({ wv.evaluateJavascript(Scripts.VIDEO_SHAPE) { r -> videoShapeFrom(st, r) } }, 300)
        }
        override fun onHideCustomView() {
            st.fullView = null; st.fullCallback = null
            st.host.setFullscreen(false)
            st.host.videoShape(0, 0)
        }
    }
    wv.setDownloadListener { url, ua, cd, mime, _ -> st.host.download(url, ua, cd, mime ?: "") }

    // Long-press a link or image: our own menu instead of the default text selection.
    val main = Handler(Looper.getMainLooper())
    wv.setOnLongClickListener {
        val h = wv.hitTestResult
        when (h.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                wv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                st.link = LinkTarget(h.extra, null); true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                wv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val img = h.extra
                val msg = Handler(Looper.getMainLooper()) { m ->
                    st.link = LinkTarget(m.data.getString("url") ?: img, img); true
                }.obtainMessage()
                runCatching { wv.requestFocusNodeHref(msg) }.onFailure { main.post { st.link = LinkTarget(img, img) } }
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                wv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                st.link = LinkTarget(null, h.extra); true
            }
            else -> false
        }
    }
    return wv
}

/** Shows a tab's page. The WebView lives with the tab, so switching tabs keeps each page as it was. */
@Composable
fun WebPage(st: BrowserState, tab: Tab, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    key(tab.id, tab.gen) {
        AndroidView(
            factory = { ctx ->
                // 1.0.0: saved page state is read in the background at launch; make sure it is in place first.
                st.applySavedState()
                val v = tab.view ?: createWebView(ctx, st, tab).also { tab.view = it }
                (v.parent as? ViewGroup)?.removeView(v)
                val r = tab.refresher ?: SwipeRefreshLayout(ctx).also { tab.refresher = it }
                (r.parent as? ViewGroup)?.removeView(r)
                r.removeAllViews()
                r.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // Pull down at the top of a page to reload it.
                r.setOnChildScrollUpCallback { _, _ -> v.scrollY > 0 || v.canScrollVertically(-1) }
                r.setOnRefreshListener { v.reload() }
                r.setDistanceToTriggerSync((ctx.resources.displayMetrics.density * 110).toInt())
                val saved = tab.saved
                if (saved != null) { tab.saved = null; tab.pending = null; runCatching { v.restoreState(saved) }.onFailure { v.loadUrl(tab.url) } }
                tab.pending?.let { u -> tab.pending = null; v.loadUrl(u) }
                r
            },
            modifier = modifier.fillMaxSize(),
            update = { r ->
                r.setColorSchemeColors(pal.fg.toArgb())
                r.setProgressBackgroundColorSchemeColor(pal.soft.toArgb())
                tab.pending?.let { u -> tab.pending = null; tab.view?.loadUrl(u) }
            }
        )
    }
}

/** A small picture of the page for the Tabs grid. */
fun captureThumb(tab: Tab) {
    // Read from the screen with PixelCopy instead of drawing the WebView in software (that could crash on some phones).
    val v = tab.view ?: return
    val st = currentState ?: return
    if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow || !v.isShown) return
    val loc = IntArray(2); v.getLocationInWindow(loc)
    st.host.snapshot(loc[0], loc[1], v.width, v.height, 0.35f) { b -> if (b != null) tab.thumb = b.asImageBitmap() }
}

// ---------------- page colour ----------------
private val sampler = Handler(Looper.getMainLooper())

/** Read the page's colour a little later (coalesced: one read per tab per delay window). */
fun scheduleSample(tab: Tab, delayMs: Long) {
    if (isRobolectric) return
    sampler.removeCallbacksAndMessages(tab)
    sampler.postAtTime({ sampleColors(tab) }, tab, android.os.SystemClock.uptimeMillis() + delayMs)
}

/**
 * The colour of the page's top and bottom edges, read from the screen with PixelCopy (GPU readback,
 * async, a single 1px row each), only after scrolling stops. Dark-mode pages are read as drawn.
 */
fun sampleColors(tab: Tab) {
    val v = tab.view ?: return
    val st = currentState ?: return
    if (tab.id != st.activeId || v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return
    val loc = IntArray(2); v.getLocationInWindow(loc)
    val dens = v.resources.displayMetrics.density
    st.host.pixelRow(loc[0], loc[1] + 1, v.width) { c -> if (c != null) tab.topColor = androidx.compose.ui.graphics.Color(c) }
    // The bar floats over the page bottom, so read just above it.
    val by = loc[1] + v.height - (96 * dens).toInt()
    if (by > loc[1] + 4) st.host.pixelRow(loc[0], by, v.width) { c -> if (c != null) tab.bottomColor = androidx.compose.ui.graphics.Color(c) }
}

/** The state the sampler reports to (set by the activity). */
var currentState: BrowserState? = null

/** Chrome's mobile user agent without the WebView markers, so sites (and Google sign-in) treat us as a full browser. */
fun mobileUA(ua: String): String = ua.replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")

fun desktopUA(ua: String): String {
    val chrome = Regex("Chrome/[\\d.]+").find(ua)?.value ?: "Chrome/130.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

fun setDesktop(tab: Tab, on: Boolean, remember: Boolean = true) {
    if (remember) currentState?.site?.setDesktop(tab.host, on)
    val v = tab.view ?: run { tab.desktop = on; return }
    tab.desktop = on
    val base = mobileUA(android.webkit.WebSettings.getDefaultUserAgent(v.context))
    v.settings.userAgentString = if (on) desktopUA(base) else base
    v.settings.loadWithOverviewMode = true
    v.settings.useWideViewPort = true
    v.reload()
}

/** Most common colour in a row (lightly quantised so gradients and noise don't split the vote). */
fun dominant(b: Bitmap, row: Int, w: Int): Int? {
    val px = IntArray(w); b.getPixels(px, 0, w, 0, row, w, 1)
    val votes = HashMap<Int, Int>(); val sample = HashMap<Int, Int>()
    for (p in px) {
        if (android.graphics.Color.alpha(p) < 200) continue
        val k = (p and 0xF8F8F8)
        votes[k] = (votes[k] ?: 0) + 1
        sample.putIfAbsent(k, p or (0xFF shl 24))
    }
    val best = votes.maxByOrNull { it.value } ?: return null
    if (best.value < w / 4) return null
    return sample[best.key]
}


/** The page's side of the bridge (window.BrowserApp). Called on a background thread. */
class PageBridge(private val st: BrowserState, private val tab: Tab) {
    private val main = Handler(Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun media(playing: Boolean) {
        main.post { tab.mediaPlaying = playing; st.host.mediaChanged() }
    }
    @android.webkit.JavascriptInterface fun shields(host: String?): Boolean =
        tab.shields && st.site.shields(host.orEmpty().removePrefix("www."))
}

/** "w,h" from Scripts.VIDEO_SHAPE (a JSON string) to the host. */
fun videoShapeFrom(st: BrowserState, raw: String?) {
    val s = raw?.trim('"').orEmpty()
    val w = s.substringBefore(',').toIntOrNull() ?: 0
    val h = s.substringAfter(',', "").toIntOrNull() ?: 0
    st.host.videoShape(w, h)
}

/** A site asks for location, camera or microphone: remembered per site, else asked once in a card. */
fun sitePermission(st: BrowserState, host: String, what: List<String>, done: (Boolean) -> Unit) {
    val android = what.flatMap {
        when (it) {
            "camera" -> listOf(android.Manifest.permission.CAMERA)
            "microphone" -> listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            "location" -> listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> emptyList()
        }
    }.toTypedArray()
    fun grantNow() { if (android.isEmpty()) done(true) else st.host.requestPerms(android) { done(it) } }
    val asked = what.filter { it != "media" }
    if (asked.isEmpty() || st.site.perms(host).containsAll(asked)) { grantNow(); return }
    st.perm = PermAsk(host, asked) { ok ->
        st.perm = null
        if (ok) { asked.forEach { st.site.grant(host, it) }; grantNow() } else done(false)
    }
}
