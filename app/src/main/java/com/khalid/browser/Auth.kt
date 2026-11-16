package com.khalid.browser

import androidx.compose.ui.draw.clipToBounds

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Google "G", built the way the real mark is: four coloured slices of a ring, an opening at the
 * upper right, and the blue bar. [bg] is the colour it sits on (used to cut the ring and the opening).
 */
@Composable
fun GoogleG(size: Dp, bg: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val r = w / 2f
        val t = r * 0.36f
        val c = Offset(r, r)
        fun slice(start: Float, sweep: Float, col: Color) = drawArc(col, start, sweep, true, Offset.Zero, Size(w, w))
        slice(0f, 48f, Color(0xFF4285F4))
        slice(48f, 94f, Color(0xFF34A853))
        slice(142f, 76f, Color(0xFFFBBC05))
        slice(218f, 100f, Color(0xFFEA4335))
        drawCircle(bg, r - t, c)
        slice(318f, 42.5f, bg)
        drawRect(Color(0xFF4285F4), Offset(r, r - t * 0.5f), Size(r, t))
        drawRect(bg, Offset(r + r * 0.86f, 0f), Size(r * 0.2f, r - t * 0.5f))
    }
}

private fun googleSignedIn(): Boolean = runCatching {
    val c = CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty()
    c.contains("SID=") || c.contains("__Secure-1PSID") || c.contains("__Secure-3PSID")
}.getOrDefault(false)

private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?hl=en&passive=false&continue=https%3A%2F%2Fmyaccount.google.com%2F"

/** Who is signed in on the web, read from Google's account list with the browser's own cookies. */
private suspend fun webAccount(): Account? = withContext(Dispatchers.IO) {
    runCatching {
        val u = "https://accounts.google.com/ListAccounts?gpsia=1&source=ChromiumBrowser&json=standard"
        val cookie = CookieManager.getInstance().getCookie("https://accounts.google.com").orEmpty()
        val c = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write(ByteArray(0)) }
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        val m = Regex("\"gaia\\.l\\.a\",\\d+,\"([^\"]*)\",\"([^\"]+@[^\"]+)\"").find(txt) ?: return@runCatching null
        Account(m.groupValues[2], m.groupValues[1].ifBlank { m.groupValues[2].substringBefore('@') })
    }.getOrNull()
}

@SuppressLint("SetJavaScriptEnabled")
private fun signInWebView(ctx: android.content.Context, onProgress: (Int) -> Unit, onPage: (String) -> Unit): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = mobileUA(settings.userAgentString)
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
    runCatching { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) }
    setBackgroundColor(android.graphics.Color.WHITE)
    webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) { onProgress(p) }
    }
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(v: WebView, u: String) { onPage(u) }
    }
}

private enum class Phase { Idle, Choosing, Working, Page }

/**
 * v1.0 sign-in ("C"): black, big type, one white "Continue with Google" pill.
 * One tap: the phone's Google account signs the browser in to Google, YouTube and Gmail in the
 * background (no Google page). Google's page appears only if Google refuses that for this app.
 */
@Composable
fun SignInScreen(st: BrowserState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("browser", android.content.Context.MODE_PRIVATE) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(st.account ?: prefs.getString("last_email", null)?.let { Account(it, prefs.getString("last_name", null) ?: it.substringBefore('@')) }) }
    var choices by remember { mutableStateOf<List<Account>>(emptyList()) }
    var progress by remember { mutableStateOf(0) }
    var finishing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }

    fun finish(acc: Account?, signed: Boolean) {
        st.webSignedIn = signed
        st.webSignInRequested = false
        val a = acc ?: picked ?: Account("", "Guest")
        prefs.edit().putString("last_email", a.email).putString("last_name", a.name).apply()
        st.account = a
        runCatching { CookieManager.getInstance().flush() }
    }

    fun onPage(u: String) {
        progress = 100
        if (finishing) return
        val host = Uri.parse(u).host.orEmpty()
        if (googleSignedIn() && !host.startsWith("accounts.google.")) {
            finishing = true
            scope.launch { finish(webAccount() ?: picked, true) }
        }
    }

    fun web(): WebView = view ?: signInWebView(ctx, { progress = it }, { onPage(it) }).also { view = it }

    // Warm the web engine while the screen is shown, so the tap does not pay for it.
    LaunchedEffect(Unit) {
        if (isRobolectric) return@LaunchedEffect
        
        delay(300); web()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { view?.let { it.stopLoading(); (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }
    }

    fun signInWith(acc: Account) {
        picked = acc; phase = Phase.Working; error = null
        scope.launch {
            if (googleSignedIn()) { finish(webAccount() ?: acc, true); return@launch }
            val direct = st.host.googleWebLogin(acc.email)
            if (direct != null) {
                // Signs the web in silently; onPage finishes when Google lands on My Account.
                web().loadUrl(direct)
                delay(15000)
                if (!finishing) { phase = Phase.Page; web().loadUrl(SIGN_IN_URL + "&Email=" + Uri.encode(acc.email)) }
            } else {
                phase = Phase.Page
                web().loadUrl(SIGN_IN_URL + "&Email=" + Uri.encode(acc.email))
            }
        }
    }

    fun tap(switch: Boolean = false) {
        if (phase == Phase.Working) return
        error = null
        val known = st.host.knownAccounts()
        val last = picked
        when {
            !switch && last != null && (known.isEmpty() || known.any { it.email.equals(last.email, true) }) -> signInWith(last)
            !switch && known.size == 1 -> signInWith(known[0])
            known.size > 1 -> { choices = known; phase = Phase.Choosing }
            else -> scope.launch {
                phase = Phase.Working
                st.host.signIn()
                    .onSuccess { signInWith(it) }
                    .onFailure { e -> phase = Phase.Idle; if (e !is CancellationException) error = e.message }
            }
        }
    }

    val black = Color.Black
    Box(Modifier.fillMaxSize().background(black)) {
        Welcome(picked?.email, phase == Phase.Working, error, onGo = { tap() }, onSwitch = { tap(switch = true) })

        // our own account list (no system dialog) when the phone shares more than one account
        if (phase == Phase.Choosing) AccountSheet(choices, onPick = { signInWith(it) }, onOther = {
            phase = Phase.Working
            scope.launch {
                st.host.signIn().onSuccess { signInWith(it) }
                    .onFailure { e -> phase = Phase.Idle; if (e !is CancellationException) error = e.message }
            }
        }, onDismiss = { phase = Phase.Idle })

        // The web engine works detached (never drawn) during the one-tap sign-in.
        // It is attached to the screen only for the fallback Google page.
        val v = view
        if (v != null && phase == Phase.Page) {
            Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GoogleG(20.dp)
                    Spacer(Modifier.width(10.dp))
                    T(if (finishing) "Signed in" else "Google needs you to confirm once", 15.sp, 600, Color(0xFF1F1F1F), Modifier.weight(1f))
                    Box(
                        Modifier.squish(0.94f) { finish(picked, googleSignedIn()) }.height(36.dp)
                            .clip(RoundedCornerShape(18.dp)).background(Color(0xFFF0F2F5)).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { T("Skip", 14.sp, 600, Color(0xFF1F1F1F)) }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer {
                    alpha = if (progress in 1..99) 1f else 0f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f); scaleX = progress / 100f
                }.background(Color(0xFF0B57D0)))
                AndroidView(
                    factory = { (v.parent as? ViewGroup)?.removeView(v); v },
                    onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                )
            }
        }
    }
}

@Composable
private fun Welcome(email: String?, busy: Boolean, error: String?, onGo: () -> Unit, onSwitch: () -> Unit) {
    val white = Color.White
    val grey = Color(0xFF8A8A8A)
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(520, easing = EaseOut)) }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.padding(start = 28.dp, top = 40.dp).size(26.dp).clip(CircleShape).background(white))
        Column(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 26.dp).padding(bottom = 40.dp)
                .graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 24.dp.toPx() }
        ) {
            T("Your web,", 48.sp, 700, white, tracking = -0.035f)
            T("signed in.", 48.sp, 700, white, tracking = -0.035f)
            T("In one tap.", 48.sp, 700, Color(0xFF555555), tracking = -0.035f)
            Spacer(Modifier.height(22.dp))
            T("Google, YouTube and Gmail ready the moment you open a page.", 15.sp, 500, grey)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (error != null) T(error, 14.sp, 500, grey, Modifier.padding(bottom = 14.dp), align = TextAlign.Center)
            val pulse by animateFloatAsState(if (busy) 0.55f else 1f, tween(260), label = "busy")
            Row(
                Modifier.squish(0.97f, onClick = onGo).fillMaxWidth().height(58.dp)
                    .clip(RoundedCornerShape(29.dp)).background(white).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GoogleG(22.dp, white)
                T(if (busy) "Signing you in…" else "Continue with Google", 16.sp, 600, Color.Black,
                    Modifier.weight(1f).graphicsLayer { alpha = pulse }, align = TextAlign.Center)
                Spacer(Modifier.width(22.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (email != null && email.isNotBlank()) {
                    T(email, 13.sp, 500, Color(0xFF777777))
                    T("  ·  ", 13.sp, 500, Color(0xFF555555))
                    T("Switch", 13.sp, 600, Color(0xFFBBBBBB), Modifier.squish(0.94f, onClick = onSwitch))
                } else T("Uses the Google account on this phone", 13.sp, 500, Color(0xFF777777))
            }
        }
    }
}

@Composable
private fun AccountSheet(list: List<Account>, onPick: (Account) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit) {
    val show = remember { Animatable(0f) }
    LaunchedEffect(Unit) { show.animateTo(1f, tween(260, easing = EaseOut)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = show.value }.background(Color.Black.copy(alpha = 0.6f))
        .squish(1f, haptic = false, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .graphicsLayer { translationY = (1f - show.value) * 40.dp.toPx() }
                .clip(RoundedCornerShape(30.dp)).background(Color(0xFF151515))
                .squish(1f, haptic = false) { }.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            T("Choose an account", 18.sp, 700, Color.White, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp))
            list.forEach { a ->
                Row(
                    Modifier.squish(0.97f) { onPick(a) }.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF242424)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        T(a.email.take(1).uppercase(), 15.sp, 700, Color.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    T(a.email, 15.sp, 600, Color.White)
                }
            }
            Box(Modifier.squish(0.97f, onClick = onOther).fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                T("Another account", 15.sp, 600, Color(0xFF999999))
            }
        }
    }
}
