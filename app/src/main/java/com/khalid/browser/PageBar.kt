@file:OptIn(ExperimentalLayoutApi::class)

package com.khalid.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colours for chrome drawn in a page's own colour. */
data class Ink(val base: Color, val ink: Color, val fill: Color, val dim: Color, val light: Boolean)

fun inkFor(base: Color): Ink {
    val light = base.luminance() > 0.45f
    val ink = if (light) Color.Black else Color.White
    return Ink(base, ink, lerp(base, ink, if (light) 0.06f else 0.10f), ink.copy(alpha = 0.55f), light)
}

/**
 * v0.7 "The page leads, the browser steps back": the page fills the screen, the status bar takes the
 * page's top colour, and one slim bar in the page's own colour floats at the bottom.
 */
@Composable
fun PageView(st: BrowserState, tab: Tab) {
    val pal = LocalPal.current
    // 1.0.0: the page colours animate in the draw phase. PageView (and the WebView host in it) no longer
    // recomposes on each frame of a colour change; only the bar that is tinted with the colour does.
    val top = animateColorAsState(tab.topColor ?: pal.bg, tween(260), label = "top")
    val bottom = animateColorAsState(tab.bottomColor ?: tab.topColor ?: pal.bg, tween(260), label = "bottom")
    Box(Modifier.fillMaxSize().drawBehind { drawRect(bottom.value) }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).drawBehind { drawRect(top.value) })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WebPage(st, tab)
                if (!st.pip) EdgeSwipe(st, tab)
            }
            Box(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
        }
        if (st.pip) Unit
        else if (st.finding) TintedFindBar(st, tab, bottom, Modifier.align(Alignment.BottomCenter).imePadding())
        else {
            TintedPageBar(st, tab, bottom, Modifier.align(Alignment.BottomCenter))
            // While the bar is hidden, a tap or an upward swipe along the bottom edge brings it back.
            val hiddenBar by remember { androidx.compose.runtime.derivedStateOf { st.barK > 0.9f } }
            if (hiddenBar) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(18.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            st.settleBar(0f)
                        }
                    }
            )
        }
    }
}

/** Reads the animated colour in its own scope, so a colour change recomposes only the bar. */
@Composable
private fun TintedPageBar(st: BrowserState, tab: Tab, color: androidx.compose.runtime.State<Color>, modifier: Modifier) {
    PageBar(st, tab, color.value, modifier)
}

@Composable
private fun TintedFindBar(st: BrowserState, tab: Tab, color: androidx.compose.runtime.State<Color>, modifier: Modifier) {
    FindBar(st, tab, inkFor(color.value), modifier)
}

@Composable
private fun PageLoadLine(tab: Tab, color: Color, modifier: Modifier) {
    val loading = tab.progress in 1..99
    val target = if (loading) (0.08f + tab.progress / 100f * 0.92f) else 1f
    val prog by animateFloatAsState(target, tween(if (loading) 300 else 160, easing = EaseOut), label = "prog")
    val shown by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 120 else 320), label = "line")
    Box(modifier.fillMaxWidth().height(2.dp).graphicsLayer {
        alpha = shown
        transformOrigin = TransformOrigin(0f, 0.5f)
        scaleX = prog.coerceIn(0.02f, 1f)
    }.background(color))
}

@Composable
fun PageBar(st: BrowserState, tab: Tab, base: Color, modifier: Modifier) {
    val pal = LocalPal.current
    val c = inkFor(base)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val imeOpen = WindowInsets.isImeVisible
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { st.barK > 0.5f } }
    val hidden by animateFloatAsState(if (imeOpen) 1f else 0f, tween(180), label = "ime")

    val i = st.tabs.indexOfFirst { it.id == tab.id }
    val prev = st.tabs.getOrNull(i - 1)
    val next = st.tabs.getOrNull(i + 1)
    val canNew = false // 0.5.0: swiping past the last tab no longer opens a new one (use +)
    val dragX = remember { Animatable(0f) }

    // Dots under the bar: while swiping, and for a moment after a switch.
    var dotsHold by remember { mutableStateOf(false) }
    LaunchedEffect(st.swipes) { if (st.swipes > 0) { dotsHold = true; delay(1300); dotsHold = false } }
    // 1.0.0: derived, so a finger drag no longer recomposes the whole bar on every frame
    val swiping by remember { androidx.compose.runtime.derivedStateOf { abs(dragX.value) > 2f } }
    val dots by animateFloatAsState(
        if (st.tabs.size > 1 && !collapsed && (dotsHold || swiping)) 1f else 0f, tween(200), label = "dots"
    )

    // One-time hint: "swipe to switch tabs", shown the first time two or more tabs are open.
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(st.tabs.size > 1) {
        if (st.tabs.size > 1 && !st.store.flag("swipe_hint")) {
            delay(900); hint = true
            dragX.animateTo(with(density) { -14.dp.toPx() }, tween(260, easing = EaseOut))
            dragX.animateTo(0f, tween(320, easing = EaseOut))
            delay(3400); hint = false; st.store.setFlag("swipe_hint")
        }
    }
    val hintA by animateFloatAsState(if (hint && !collapsed && !imeOpen) 1f else 0f, tween(240), label = "hint")

    val textMeasurer = rememberTextMeasurer()
    val pillStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(600), fontSize = 13.sp, letterSpacing = (-0.01f).em)
    val host = tab.host.ifBlank { tab.url }.take(40)
    val hostW = remember(host) { textMeasurer.measure(host, pillStyle).size.width }

    val col by rememberUpdatedState(collapsed)
    val nb by rememberUpdatedState(Triple(prev, next, canNew))

    val navPx = WindowInsets.navigationBars.getBottom(density)
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding()
            .graphicsLayer {
                // 0.5.0: the bar slides all the way off the screen while reading
                val k = st.barK
                alpha = (1f - hidden) * (1f - k * k)
                translationY = hidden * 40.dp.toPx() + k * (90.dp.toPx() + navPx)
            }
    ) {
        val fullW = with(density) { (maxWidth - 28.dp).toPx() }
        val gap = with(density) { 10.dp.toPx() }
        val step = fullW + gap
        val smallW = hostW + with(density) { 32.dp.toPx() }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // hint pill
            Box(
                Modifier.graphicsLayer { alpha = hintA; translationY = (1f - hintA) * 8.dp.toPx() }
                    .padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(c.ink.copy(alpha = 0.92f))
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) { T("‹   swipe to switch tabs", 14.sp, 500, c.base) }

            // the bar row (gesture area does not move; the pills inside slide)
            Box(
                Modifier
                    .layout { m, cs ->
                        // the touch area follows the bar, so the page stays tappable around the small pill
                        val w = fullW.roundToInt()
                        val h = 58.dp.roundToPx()
                        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { p.place(0, 0) }
                    }
                    .pointerInput(imeOpen, collapsed) {
                        if (imeOpen || collapsed) return@pointerInput
                        val vt = VelocityTracker()
                        var axis = 0 // 0 undecided, 1 horizontal, 2 vertical
                        var tx = 0f; var ty = 0f
                        val slop = viewConfiguration.touchSlop
                        detectDragGestures(
                            onDragStart = { vt.resetTracking(); axis = 0; tx = 0f; ty = 0f; scope.launch { dragX.stop() } },
                            onDragEnd = {
                                if (axis == 2) {
                                    if (ty < -36.dp.toPx()) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); st.screen = Screen.Tabs }
                                    else if (ty > 24.dp.toPx() && !col) st.settleBar(1f)
                                    return@detectDragGestures
                                }
                                val v = vt.calculateVelocity().x
                                val (p, n, nw) = nb
                                val x = dragX.value
                                val fast = 900.dp.toPx()
                                val dir = when {
                                    (x < -step * 0.25f || v < -fast) && (n != null || nw) && x < 0 -> 1
                                    (x > step * 0.25f || v > fast) && p != null && x > 0 -> -1
                                    else -> 0
                                }
                                scope.launch {
                                    if (dir == 0) dragX.animateTo(0f, tween(220, easing = EaseOut))
                                    else {
                                        dragX.animateTo(-dir * step, tween(170, easing = EaseOut))
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (!st.store.flag("swipe_hint")) { hint = false; st.store.setFlag("swipe_hint") }
                                        st.switchBy(dir)
                                        dragX.snapTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { dragX.animateTo(0f, tween(220, easing = EaseOut)) } }
                        ) { change, amount ->
                            tx += amount.x; ty += amount.y
                            if (axis == 0) {
                                if (abs(tx) > slop && abs(tx) > abs(ty)) axis = if (col) 2 else 1
                                else if (abs(ty) > slop) axis = 2
                                else return@detectDragGestures
                            }
                            change.consume()
                            if (axis == 2) return@detectDragGestures
                            vt.addPosition(change.uptimeMillis, change.position)
                            val (p, n, nw) = nb
                            val x = dragX.value + amount.x
                            val free = (x > 0 && p != null) || (x < 0 && (n != null || nw))
                            val nx = if (free) x else dragX.value + amount.x * 0.3f
                            scope.launch { dragX.snapTo(nx.coerceIn(-step, step)) }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // neighbours, peeking in from the sides while swiping
                prev?.let { pt ->
                    val pc = inkFor(pt.bottomColor ?: pt.topColor ?: pal.bg)
                    Box(Modifier.requiredWidth(with(density) { fullW.toDp() }).requiredHeight(58.dp)
                        .graphicsLayer { translationX = dragX.value - step; alpha = if (dragX.value > 0.5f) 1f else 0f }
                    ) { FullPill(pt, st.tabs.size, pc, interactive = false) }
                }
                if (next != null || canNew) {
                    val nt = next
                    val ncol = inkFor(nt?.bottomColor ?: nt?.topColor ?: pal.bg)
                    Box(Modifier.requiredWidth(with(density) { fullW.toDp() }).requiredHeight(58.dp)
                        .graphicsLayer { translationX = dragX.value + step; alpha = if (dragX.value < -0.5f) 1f else 0f }
                    ) {
                        if (nt != null) FullPill(nt, st.tabs.size, ncol, interactive = false)
                    }
                }

                // the current bar: tabs circle, the island, and the + / settings pill (0.6.0)
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = dragX.value }
                ) {
                    Box(Modifier.requiredWidth(with(density) { fullW.toDp() }).fillMaxHeight()) {
                        FullPill(tab, st.tabs.size, c, interactive = !collapsed, st = st)
                    }
                }
            }

            // tab dots
            Row(
                Modifier.padding(top = 7.dp, bottom = 5.dp).height(5.dp).graphicsLayer { alpha = dots },
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val n = st.tabs.size.coerceAtMost(9)
                val at = if (n == 0) 0 else i.coerceIn(0, n - 1)
                repeat(n) { d ->
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (d == at) c.ink else c.ink.copy(alpha = 0.3f)))
                }
            }
        }
    }
}

/** 0.8.0 bar: tabs circle | the island with the page and settings inside on the right | + circle. */
@Composable
private fun FullPill(tab: Tab, count: Int, c: Ink, interactive: Boolean, st: BrowserState? = null) {
    val live = interactive && st != null
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // tabs circle
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(c.fill)
                .then(if (live) Modifier.squish(onLongPress = { st!!.card = Card.TabMenu }) { st!!.screen = Screen.Tabs } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(c.ink), contentAlignment = Alignment.Center) {
                T(if (count > 99) ":D" else count.toString(), 13.sp, 700, c.base)
            }
        }
        // the island: page title and address, settings inside on the right
        Box(
            Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(50)).background(c.fill)
                .then(if (live) Modifier.squish(0.98f, onLongPress = {
                    if (!tab.blank) st!!.link = LinkTarget(tab.url, null)
                }) { st!!.screen = Screen.Search } else Modifier)
        ) {
            PillProgress(tab, c)
            Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val ai = isAiUrl(tab.url)
                    T(if (ai) aiQuery(tab.url) else tab.label, 15.sp, 600, c.ink)
                    Spacer(Modifier.height(1.dp))
                    T(if (ai) "AI mode" else (if (tab.private) "private · " else "") + tab.host.ifBlank { tab.url }, 12.sp, 500, if (ai) AiGreen else c.dim)
                }
                Box(
                    Modifier.size(46.dp).then(if (live) Modifier.squish(0.9f) { st!!.quick = true } else Modifier),
                    contentAlignment = Alignment.Center
                ) { Glyph(G.Dots, c.ink, 22.dp) }
            }
        }
        // + circle: a new tab on the home page
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(c.ink)
                .then(if (live) Modifier.squish(0.9f) { st!!.newTab(); st.screen = Screen.Home } else Modifier),
            contentAlignment = Alignment.Center
        ) { Glyph(G.Plus, c.base, 20.dp) }
    }
}

@Composable
private fun NewTabPill(c: Ink) {
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(c.fill).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Glyph(G.Plus, c.ink, 18.dp)
        Spacer(Modifier.width(10.dp))
        T("New tab", 16.sp, 600, c.ink)
    }
}

/** Page loading shown as a soft fill sweeping through the bar itself. */
@Composable
private fun PillProgress(tab: Tab, c: Ink) {
    val loading = tab.progress in 1..99
    val target = if (loading) (0.08f + tab.progress / 100f * 0.92f) else 1f
    val prog by animateFloatAsState(target, tween(if (loading) 300 else 200, easing = EaseOut), label = "prog")
    val shown by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 120 else 380), label = "pshow")
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = shown
        transformOrigin = TransformOrigin(0f, 0.5f)
        scaleX = prog.coerceIn(0.02f, 1f)
    }.background(c.ink.copy(alpha = if (c.light) 0.07f else 0.12f)))
}
