package com.khalid.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================
// 0.9.0: quick settings, the dot voice screen, AI mode.
// =====================================================================

val AiGreen = Color(0xFF22C55E)

// ---------------- AI mode helpers ----------------
fun aiUrl(q: String): String = "https://www.google.com/search?udm=50&hl=en&q=" + Uri.encode(q.trim())

fun isAiUrl(u: String): Boolean = runCatching {
    val p = Uri.parse(u)
    val h = p.host.orEmpty()
    (h.startsWith("google.") || h.contains(".google.")) && p.path == "/search" && p.getQueryParameter("udm") == "50"
}.getOrDefault(false)

fun aiQuery(u: String): String = runCatching { Uri.parse(u).getQueryParameter("q") }.getOrNull().orEmpty()

/** Restyles Google's AI Mode page for Browser: Google's header and tabs go, our own head comes in. */
fun injectAi(view: android.webkit.WebView, url: String) {
    val q = org.json.JSONObject.quote(aiQuery(url))
    val css = org.json.JSONObject.quote(
        "@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');" +
            "#searchform,#gb,#hdtb,#top_nav,#appbar,#footcnt,#sfooter,#fbar,footer,header[role=banner],div[role=navigation]{display:none!important}" +
            "#hk-ai-head{padding:20px 20px 6px;font-family:Inter,Roboto,sans-serif;color:inherit}" +
            "#hk-ai-head .hk-pill{display:inline-flex;align-items:center;gap:8px;height:30px;padding:0 14px;border-radius:15px;" +
            "background:#000;color:#fff;font:600 13px Inter,Roboto,sans-serif;box-shadow:0 0 0 2px #22C55E}" +
            "#hk-ai-head .hk-pill i{display:block;width:8px;height:8px;border-radius:4px;background:#22C55E}" +
            "#hk-ai-head .hk-q{margin-top:14px;font:700 27px/1.2 Inter,Roboto,sans-serif;letter-spacing:-.02em}" +
            "body h1,body h2,body h3,body p,body li{font-family:Inter,Roboto,sans-serif!important}"
    )
    val js = """(function(){var q=$q,c=$css;function run(){if(!document.head||!document.body)return;
if(!document.getElementById('hk-ai-css')){var s=document.createElement('style');s.id='hk-ai-css';s.textContent=c;document.head.appendChild(s);}
var h=document.getElementById('hk-ai-head');if(!h){h=document.createElement('div');h.id='hk-ai-head';
h.innerHTML='<div class="hk-pill"><i></i>AI mode</div><div class="hk-q"></div>';document.body.insertBefore(h,document.body.firstChild);}
h.querySelector('.hk-q').textContent=q;}
run();if(!window.__hkAi){window.__hkAi=1;var n=0;var t=setInterval(function(){run();if(++n>25)clearInterval(t);},800);}})();"""
    runCatching { view.evaluateJavascript(js, null) }
}

// ---------------- frosted background ----------------
/** A soft blur of a small bitmap, done in place (fast because the picture is tiny). */
fun boxBlur(b: Bitmap, radius: Int = 2, passes: Int = 3) {
    val w = b.width; val h = b.height
    if (w < 3 || h < 3) return
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        for (l in 0 until lines) for (i in 0 until n) {
            var r = 0; var g = 0; var bl = 0; var c = 0
            for (k in -radius..radius) {
                val j = (i + k).coerceIn(0, n - 1)
                val p = if (horizontal) src[l * w + j] else src[j * w + l]
                r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; c++
            }
            val idx = if (horizontal) l * w + i else i * w + l
            dst[idx] = (255 shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (bl / c)
        }
    }
    repeat(passes) { pass(px, tmp, true); pass(tmp, px, false) }
    b.setPixels(px, 0, w, 0, 0, w, h)
}

/** The blurry layer behind quick settings and voice: a blurred picture of the screen plus a light veil. */
@Composable
fun Frost(st: BrowserState, visible: Boolean, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(visible) {
        if (visible) { img = null; st.host.frost { b -> img = b?.asImageBitmap() } }
    }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 260 else 200, easing = EaseOut), label = "frost")
    val on by remember { androidx.compose.runtime.derivedStateOf { a > 0.001f } } // 1.0.0: alpha read only in the layer
    if (on) Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High) }
        Box(Modifier.fillMaxSize().background(pal.bg.copy(alpha = if (img != null) 0.35f else 0.82f)))
    }
}

// ---------------- quick settings ----------------
@Composable
fun QuickSettings(st: BrowserState) {
    val pal = LocalPal.current
    Frost(st, st.quick) { st.quick = false }
    AnimatedVisibility(
        st.quick,
        enter = fadeIn(tween(200)) + scaleIn(tween(280, easing = EaseOut), initialScale = 0.9f),
        exit = fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.95f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val tab = st.active
            val page = tab != null && !tab.blank
            Column(
                Modifier.padding(horizontal = 20.dp).widthIn(max = 420.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp)).background(pal.bg)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Quick settings", 18.sp, 700, modifier = Modifier.weight(1f))
                    if (page) T(tab!!.host.removePrefix("www.").take(24), 13.sp, 500, pal.fg.copy(alpha = 0.5f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QTile("Dark mode", if (st.dark) "On" else "Off", st.dark, Modifier.weight(1f)) { st.requestTheme() }
                    QTile("Private", if (st.privateMode) "On" else "Off", st.privateMode, Modifier.weight(1f)) { st.privateMode = !st.privateMode }
                    QTile("AI mode", if (st.aiMode) "On" else "Off", st.aiMode, Modifier.weight(1f), accent = AiGreen) { st.setAi(!st.aiMode) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QTile("Dark pages", if (st.forceDark) "On" else "Off", st.forceDark, Modifier.weight(1f)) { st.setForceDarkPages(!st.forceDark) }
                    QTile(
                        "Block ads", if (!page) "No page" else if (!st.adOn) "Off in Settings" else if (tab!!.shields) (if (tab.blocked > 0) "${tab.blocked} blocked" else "On") else "Allowed here",
                        page && st.adOn && tab!!.shields, Modifier.weight(1f), enabled = page
                    ) { st.setShields(tab!!, !tab.shields) }
                    QTile("Desktop", if (!page) "No page" else if (tab!!.desktop) "On" else "Off", page && tab!!.desktop, Modifier.weight(1f), enabled = page) {
                        setDesktop(tab!!, !tab.desktop)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QLink("Bookmarks", Modifier.weight(1f)) { st.quick = false; st.listMode = ListMode.Bookmarks; st.screen = Screen.Lists }
                    QLink("History", Modifier.weight(1f)) { st.quick = false; st.listMode = ListMode.History; st.screen = Screen.Lists }
                    QLink("Downloads", Modifier.weight(1f)) { st.quick = false; st.openDownloads(Screen.Home) }
                }
                CardRow("All settings", primary = true) { st.quick = false; st.screen = Screen.More }
            }
        }
    }
}

@Composable
private fun QTile(title: String, state: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, accent: Color? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    val bg by animateColorAsState(if (on) (accent ?: pal.fg) else pal.soft, tween(220, easing = EaseOut), label = "qbg")
    val ink by animateColorAsState(if (on) (if (accent != null) Color.White else pal.bg) else pal.fg, tween(220, easing = EaseOut), label = "qink")
    val dot by animateFloatAsState(if (on) 1f else 0.5f, tween(220, easing = EaseOut), label = "qdot")
    Column(
        modifier.then(if (enabled) Modifier.squish(0.95f, onClick = onClick) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .height(92.dp).clip(RoundedCornerShape(24.dp)).background(bg).padding(14.dp)
    ) {
        Box(Modifier.size(12.dp).graphicsLayer { scaleX = dot; scaleY = dot }.clip(CircleShape).background(ink))
        Spacer(Modifier.weight(1f))
        T(title, 14.sp, 600, ink)
        T(state, 12.sp, 500, ink.copy(alpha = 0.6f))
    }
}

@Composable
private fun QLink(title: String, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        modifier.squish(0.95f, onClick = onClick).height(48.dp).clip(RoundedCornerShape(24.dp)).background(pal.soft),
        contentAlignment = Alignment.Center
    ) { T(title, 14.sp, 600) }
}

// ---------------- voice ----------------
@Composable
fun VoiceScreen(st: BrowserState) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val visible = st.voice
    var text by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    val rec = remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun stop() { runCatching { rec.value?.destroy() }; rec.value = null; listening = false }
    fun start() {
        stop()
        text = ""; done = false; error = null; level = 0f
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) { st.voice = false; st.host.systemVoice(); return }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; level = 0f }
            override fun onError(e: Int) {
                listening = false; level = 0f
                if (text.isNotBlank()) done = true
                else error = when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the dot to try again"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for voice. Tap the dot to retry"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Browser needs the microphone"
                    else -> "Voice stopped. Tap the dot to try again"
                }
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text = it }
                listening = false; level = 0f
                if (text.isNotBlank()) done = true else error = "Didn't catch that. Tap the dot to try again"
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        rec.value = r
        listening = true
        runCatching { r.startListening(i) }.onFailure { stop(); error = "Voice isn't available right now" }
    }

    LaunchedEffect(visible) {
        if (visible) {
            text = ""; done = false; error = null
            if (isRobolectric) return@LaunchedEffect
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { st.voice = false; st.host.systemVoice(); return@LaunchedEffect }
            st.host.requestPerms(arrayOf(android.Manifest.permission.RECORD_AUDIO)) { ok ->
                if (!st.voice) return@requestPerms
                if (ok) start() else { st.voice = false; st.notify("Browser needs the microphone for voice search") }
            }
        } else stop()
    }
    DisposableEffect(Unit) { onDispose { stop() } }

    Frost(st, visible) { st.voice = false }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(160))
    ) {
        Box(Modifier.fillMaxSize()) {
            // the dot, with the mic alive inside it
            val pop = remember { Animatable(0.6f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, tween(380, easing = EaseOut)) }
            val lv by animateFloatAsState(level, tween(110), label = "lv")
            val inf = rememberInfiniteTransition(label = "mic")
            val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "wave")
            val live by animateFloatAsState(if (listening) 1f else 0f, tween(260), label = "live")
            Column(Modifier.align(Alignment.Center).padding(bottom = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(170.dp)
                        .graphicsLayer { val s = pop.value * (1f + lv * 0.07f); scaleX = s; scaleY = s }
                        .clip(CircleShape).background(pal.fg)
                        .squish(0.94f) { if (!listening) start() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r0 = size.minDimension * 0.16f
                        // two soft rings that breathe outward while listening
                        for (k in 0..1) {
                            val p = (wave + k * 0.5f) % 1f
                            val rad = r0 + (size.minDimension * 0.34f - r0) * p * (0.6f + 0.4f * lv)
                            drawCircle(pal.bg.copy(alpha = 0.22f * (1f - p) * live), rad)
                        }
                        drawCircle(pal.bg.copy(alpha = 0.12f + 0.12f * lv * live), r0 * (1.25f + lv * 0.5f))
                    }
                    MicGlyph(pal.bg) { lv * live } // 1.0.0: voice level read in draw only, not every frame in composition
                }
                Spacer(Modifier.height(22.dp))
                T(
                    error ?: if (done) "Tap search, or the dot to say it again" else if (listening) (if (st.aiMode) "Listening · AI mode" else "Listening") else "Getting ready",
                    14.sp, 500, pal.fg.copy(alpha = 0.6f)
                )
            }
            // the bar with what you say, and the search circle once you finish
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isBlank()) T(if (st.aiMode) "Ask anything…" else "Say something…", 16.sp, 500, pal.fg.copy(alpha = 0.45f))
                    else P(text, 16.sp, 600, maxLines = 3, lineHeight = 1.3f)
                }
                val slot by animateDpAsState(if (done) 68.dp else 0.dp, tween(320, easing = EaseOut), label = "slot")
                val sIn by animateFloatAsState(if (done) 1f else 0f, tween(320, easing = EaseOut), label = "sin")
                Box(Modifier.width(slot).height(58.dp), contentAlignment = Alignment.CenterEnd) {
                    if (slot > 1.dp) Box(
                        Modifier.size(58.dp).graphicsLayer { scaleX = 0.5f + 0.5f * sIn; scaleY = 0.5f + 0.5f * sIn; alpha = sIn }
                            .clip(CircleShape).background(if (st.aiMode) AiGreen else pal.fg)
                            .squish(0.9f) { val q = text.trim(); if (q.isNotEmpty()) { st.voice = false; st.open(q) } },
                        contentAlignment = Alignment.Center
                    ) { Glyph(G.Search, if (st.aiMode) Color.White else pal.bg, 22.dp, 2.2.dp) }
                }
            }
        }
    }
}

/** A microphone drawn in lines; its capsule fills up with your voice. */
@Composable
private fun MicGlyph(color: Color, levelOf: () -> Float) {
    Canvas(Modifier.size(54.dp)) {
        val level = levelOf()
        val w = size.width; val h = size.height
        val sw = w * 0.07f
        val capW = w * 0.32f; val capH = h * 0.5f
        val left = (w - capW) / 2f; val top = h * 0.08f
        drawRoundRect(color, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(capW, capH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capW / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        // fill rising with the voice
        val fillH = (capH - sw * 2f) * level
        if (fillH > 1f) drawRoundRect(color, topLeft = Offset(left + sw, top + capH - sw - fillH),
            size = androidx.compose.ui.geometry.Size(capW - sw * 2f, fillH), cornerRadius = androidx.compose.ui.geometry.CornerRadius((capW - sw * 2f) / 2f))
        drawArc(color, 0f, 180f, false, topLeft = Offset(w * 0.24f, h * 0.22f), size = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(w / 2f, h * 0.72f), Offset(w / 2f, h * 0.9f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.9f), Offset(w * 0.64f, h * 0.9f), sw, StrokeCap.Round)
    }
}

// ---------------- AI mode bar ----------------
@Composable
fun AiModeBar(st: BrowserState, modifier: Modifier) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val fill = remember { Animatable(0f) }      // green flash of the bar
    val out = remember { Animatable(0f) }       // circle leaving the bar to the right
    val mark = remember { Animatable(0f) }      // the tick (or cross) drawing itself
    val edge = remember { Animatable(if (st.aiMode) 1f else 0f) } // green edges while on
    var busy by remember { mutableStateOf(false) }
    var turningOn by remember { mutableStateOf(true) }
    LaunchedEffect(st.aiMode) { if (!busy) edge.animateTo(if (st.aiMode) 1f else 0f, tween(300, easing = EaseOut)) }

    fun toggle() {
        if (busy) return
        busy = true
        val on = !st.aiMode
        turningOn = on
        st.host.haptic()
        scope.launch {
            mark.snapTo(0f)
            if (on) {
                fill.animateTo(1f, tween(220, easing = EaseOut))
                st.setAi(true)
                out.animateTo(1f, tween(380, easing = EaseOut))
                mark.animateTo(1f, tween(320, easing = EaseOut))
                delay(650)
                launch { edge.animateTo(1f, tween(320, easing = EaseOut)) }
                launch { fill.animateTo(0f, tween(320, easing = EaseOut)) }
                out.animateTo(0f, tween(340, easing = EaseOut))
            } else {
                launch { edge.animateTo(0f, tween(420, easing = EaseOut)) }
                st.setAi(false)
                out.animateTo(1f, tween(380, easing = EaseOut))
                mark.animateTo(1f, tween(300, easing = EaseOut))
                delay(500)
                out.animateTo(0f, tween(320, easing = EaseOut))
            }
            busy = false
        }
    }

    val base = if (pal.dark) Color(0xFF1C1C1C) else Color.Black
    val barW = 148.dp; val barH = 46.dp; val circle = 46.dp
    val travel = with(density) { (circle + 10.dp).toPx() }
    Box(modifier.width(barW).height(barH)) {
        // the circle sits behind the bar and slides out to its right
        Box(
            Modifier.align(Alignment.CenterEnd).size(circle)
                .graphicsLayer {
                    translationX = travel * out.value
                    val s = 0.6f + 0.4f * out.value
                    scaleX = s; scaleY = s
                    alpha = (out.value * 1.6f).coerceAtMost(1f)
                }
                .clip(CircleShape).background(if (turningOn) AiGreen else base),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(22.dp)) {
                val sw = size.width * 0.13f
                val m = mark.value
                fun seg(a: Offset, b: Offset, p: Float) {
                    if (p > 0f) drawLine(Color.White, a, a + (b - a) * p.coerceAtMost(1f), sw, StrokeCap.Round)
                }
                val w = size.width; val h = size.height
                if (turningOn) {
                    seg(Offset(w * 0.12f, h * 0.54f), Offset(w * 0.4f, h * 0.8f), m / 0.4f)
                    seg(Offset(w * 0.4f, h * 0.8f), Offset(w * 0.9f, h * 0.22f), (m - 0.4f) / 0.6f)
                } else {
                    seg(Offset(w * 0.18f, h * 0.18f), Offset(w * 0.82f, h * 0.82f), m / 0.5f)
                    seg(Offset(w * 0.82f, h * 0.18f), Offset(w * 0.18f, h * 0.82f), (m - 0.5f) / 0.5f)
                }
            }
        }
        Box(
            Modifier.matchParentSizeCompat()
                .squish(0.96f, haptic = false) { toggle() }
                .clip(RoundedCornerShape(barH / 2))
                .background(lerp(base, AiGreen, fill.value))
                .border(2.dp, AiGreen.copy(alpha = edge.value), RoundedCornerShape(barH / 2)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).graphicsLayer { val s = 0.4f + 0.6f * edge.value; scaleX = s; scaleY = s }
                    .clip(CircleShape).background(lerp(Color.White, AiGreen, edge.value * (1f - fill.value))))
                Spacer(Modifier.width(9.dp))
                T("AI mode", 15.sp, 600, Color.White)
            }
        }
    }
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()
