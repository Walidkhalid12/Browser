package com.khalid.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin

// =====================================================================
// Glyphs (drawn, one stroke weight)
// =====================================================================
enum class G { Search, Dots, Close, Plus, Chevron, UpLeft, Arrow, Globe, Back, Forward, Reload, Share, Star, Mic }

@Composable
fun Glyph(kind: G, color: Color, size: Dp = 22.dp, stroke: Dp = 2.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = stroke.toPx()
        val st = Stroke(s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, w * y)
        when (kind) {
            G.Search -> {
                drawCircle(color, w * 0.31f, p(0.44f, 0.44f), style = st)
                drawLine(color, p(0.68f, 0.68f), p(0.9f, 0.9f), s, StrokeCap.Round)
            }
            G.Dots -> for (i in -1..1) drawCircle(color, s * 1.25f, p(0.5f, 0.5f + i * 0.3f))
            G.Close -> {
                drawLine(color, p(0.26f, 0.26f), p(0.74f, 0.74f), s, StrokeCap.Round)
                drawLine(color, p(0.74f, 0.26f), p(0.26f, 0.74f), s, StrokeCap.Round)
            }
            G.Plus -> {
                drawLine(color, p(0.5f, 0.18f), p(0.5f, 0.82f), s, StrokeCap.Round)
                drawLine(color, p(0.18f, 0.5f), p(0.82f, 0.5f), s, StrokeCap.Round)
            }
            G.Chevron -> drawPath(Path().apply {
                moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.68f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f)
            }, color, style = st)
            G.UpLeft -> {
                drawLine(color, p(0.76f, 0.76f), p(0.28f, 0.28f), s, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.28f, w * 0.64f); lineTo(w * 0.28f, w * 0.28f); lineTo(w * 0.64f, w * 0.28f)
                }, color, style = st)
            }
            G.Arrow -> {
                drawLine(color, p(0.18f, 0.5f), p(0.82f, 0.5f), s, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.56f, w * 0.24f); lineTo(w * 0.82f, w * 0.5f); lineTo(w * 0.56f, w * 0.76f)
                }, color, style = st)
            }
            G.Back -> {
                drawLine(color, p(0.82f, 0.5f), p(0.2f, 0.5f), s, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.44f, w * 0.24f); lineTo(w * 0.18f, w * 0.5f); lineTo(w * 0.44f, w * 0.76f)
                }, color, style = st)
            }
            G.Forward -> {
                drawLine(color, p(0.18f, 0.5f), p(0.8f, 0.5f), s, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.56f, w * 0.24f); lineTo(w * 0.82f, w * 0.5f); lineTo(w * 0.56f, w * 0.76f)
                }, color, style = st)
            }
            G.Reload -> {
                drawArc(color, 40f, 290f, false, p(0.2f, 0.2f), Size(w * 0.6f, w * 0.6f), style = st)
                drawPath(Path().apply {
                    moveTo(w * 0.62f, w * 0.12f); lineTo(w * 0.8f, w * 0.3f); lineTo(w * 0.56f, w * 0.36f)
                }, color, style = st)
            }
            G.Share -> {
                drawLine(color, p(0.5f, 0.14f), p(0.5f, 0.6f), s, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.32f, w * 0.3f); lineTo(w * 0.5f, w * 0.12f); lineTo(w * 0.68f, w * 0.3f)
                }, color, style = st)
                drawPath(Path().apply {
                    moveTo(w * 0.3f, w * 0.46f); lineTo(w * 0.2f, w * 0.46f); lineTo(w * 0.2f, w * 0.86f)
                    lineTo(w * 0.8f, w * 0.86f); lineTo(w * 0.8f, w * 0.46f); lineTo(w * 0.7f, w * 0.46f)
                }, color, style = st)
            }
            G.Star -> {
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.42f else 0.18f
                    val a = (-PI / 2 + i * PI / 5).toFloat()
                    val x = w * (0.5f + r * kotlin.math.cos(a)); val y = w * (0.53f + r * sin(a))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); drawPath(path, color)
            }
            G.Mic -> {
                drawRoundRect(color, p(0.36f, 0.1f), Size(w * 0.28f, w * 0.48f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
                drawArc(color, 0f, 180f, false, p(0.22f, 0.3f), Size(w * 0.56f, w * 0.44f), style = st)
                drawLine(color, p(0.5f, 0.74f), p(0.5f, 0.9f), s, StrokeCap.Round)
            }
            G.Globe -> {
                drawCircle(color, w * 0.4f, p(0.5f, 0.5f), style = st)
                drawLine(color, p(0.1f, 0.5f), p(0.9f, 0.5f), s * 0.85f)
                drawOval(color, p(0.33f, 0.1f), Size(w * 0.34f, w * 0.8f), style = Stroke(s * 0.85f))
            }
        }
    }
}

// =====================================================================
// HOME: the logo dot on a new tab, the page itself once something is open
// =====================================================================
private var introDone = false

@Composable
fun HomeScreen(st: BrowserState) {
    val tab = st.active
    val browsing = tab != null && !tab.blank
    if (!browsing) {
        Home05(st)
    } else {
        PageView(st, tab!!)
    }
}

/** A thin solid bar that fills while the page loads. Reads progress on its own so the page never recomposes. */
@Composable
private fun LoadLine(tab: Tab) {
    val pal = LocalPal.current
    val loading = tab.progress in 1..99
    val target = if (loading) (0.08f + tab.progress / 100f * 0.92f) else 1f
    val prog by animateFloatAsState(target, tween(if (loading) 300 else 160, easing = EaseOut), label = "prog")
    val shownLine by animateFloatAsState(if (loading) 1f else 0f, tween(if (loading) 120 else 320), label = "line")
    Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = shownLine }) {
        Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer {
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            scaleX = prog.coerceIn(0.02f, 1f)
        }.background(pal.fg))
    }
}

@Composable
fun LogoDot(modifier: Modifier, size: Dp, active: Boolean = true) {
    val pal = LocalPal.current
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val pop = remember { Animatable(if (introDone) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val maxPull = with(density) { 46.dp.toPx() }
    val raw = remember { floatArrayOf(0f, 0f) }

    LaunchedEffect(Unit) {
        if (!introDone) {
            delay(180)
            pop.animateTo(1f, tween(420, easing = EaseOut))
            introDone = true
        }
    }

    fun release() {
        raw[0] = 0f; raw[1] = 0f
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 1f, stiffness = 400f)) }
        scope.launch { pop.animateTo(1f, Springs.soft) }
    }

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val o = offset.value
                translationX = o.x; translationY = o.y
                scaleX = pop.value; scaleY = pop.value
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        pop.animateTo(0.94f, tween(90, easing = EaseOut))
                        pop.animateTo(1f, tween(220, easing = EaseOut))
                    }
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { scope.launch { pop.animateTo(1.04f, tween(160, easing = EaseOut)) } },
                    onDragEnd = { release() },
                    onDragCancel = { release() }
                ) { change, drag ->
                    change.consume()
                    raw[0] += drag.x; raw[1] += drag.y
                    val r = Offset(raw[0], raw[1])
                    val d = r.getDistance()
                    val pulled = maxPull * (1f - exp(-d / (maxPull * 2.6f)))
                    val target = if (d > 0f) r * (pulled / d) else Offset.Zero
                    scope.launch { offset.snapTo(target) }
                }
            }
            .background(pal.fg, CircleShape)
    )
}

@Composable
fun BottomBar(st: BrowserState, modifier: Modifier, compact: Boolean) {
    val pal = LocalPal.current
    val tab = st.active
    val h = if (compact) 52.dp else 56.dp
    // 0.8.0: the same bar as while browsing — tabs circle | search bar with settings inside | + circle.
    Row(
        modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = if (compact) 8.dp else 0.dp, bottom = if (compact) 10.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.onGloballyPositioned { st.origins[Screen.Tabs] = it.boundsInRoot() }
                .enterUp(0, 120).squish(0.9f, onLongPress = { st.card = Card.TabMenu }) { st.screen = Screen.Tabs }
                .size(h).clip(CircleShape).background(pal.soft),
            contentAlignment = Alignment.Center
        ) { TabCount(st.tabs.size) }
        Row(
            Modifier.weight(1f).onGloballyPositioned { st.origins[Screen.Search] = it.boundsInRoot() }
                .pointerInput(Unit) {
                    // swipe the bar left/right to change tab (never opens a new one)
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            val lim = 56.dp.toPx()
                            if (total > lim) st.switchBy(-1) else if (total < -lim) st.switchBy(1)
                        }
                    ) { ch, dx -> ch.consume(); total += dx }
                }
                .enterUp(1, 120).squish(0.97f) { st.screen = Screen.Search }
                .height(h).clip(RoundedCornerShape(h / 2)).background(pal.soft)
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (tab != null && !tab.blank) {
                    if (tab.private) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(pal.fg))
                        Spacer(Modifier.width(10.dp))
                    }
                    val query = tab.query
                    if (query != null) {
                        Glyph(G.Search, pal.fg, 16.dp, 1.8.dp)
                        Spacer(Modifier.width(10.dp))
                        T(query, 16.sp, 600, modifier = Modifier.weight(1f))
                    } else T(tab.host.ifBlank { tab.url }, 16.sp, 600, modifier = Modifier.weight(1f))
                } else {
                    Glyph(G.Search, pal.fg, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    T(if (st.aiMode) "Ask AI or type a URL" else if (st.privateMode) "Private search" else "Search or type a URL", 15.sp, 500, modifier = Modifier.weight(1f))
                }
            }
            // settings, inside the bar on the right
            Box(
                Modifier.onGloballyPositioned { st.origins[Screen.More] = it.boundsInRoot() }
                    .squish(0.9f) { st.quick = true }.size(h - 8.dp),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Dots, pal.fg, 22.dp) }
        }
        // + : new tab on the home page; on an empty home it goes to search
        Box(
            Modifier.enterUp(2, 120)
                .squish(0.9f) { if (st.active?.blank == true) st.screen = Screen.Search else { st.newTab(); st.screen = Screen.Home } }
                .size(h).clip(CircleShape).background(pal.fg),
            contentAlignment = Alignment.Center
        ) { Glyph(G.Plus, pal.bg, 20.dp) }
    }
}

@Composable
fun TabCount(n: Int) {
    val pal = LocalPal.current
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(pal.fg), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = n,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(220, easing = EaseOut)) { if (up) it else -it } + fadeIn(tween(220))) togetherWith
                    (slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(180)))
            },
            label = "count"
        ) { T(if (it > 99) ":D" else it.toString(), 13.sp, 700, pal.bg) }
    }
}

// =====================================================================
// SEARCH
// =====================================================================
private data class Sug(val text: String, val url: String?, val icon: G)

@Composable
fun SearchScreen(st: BrowserState, open: Boolean) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf(emptyList<String>()) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            q = ""
            delay(170); runCatching { focus.requestFocus() }
            delay(80)
            clip = runCatching {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
            }.getOrNull()?.takeIf { looksLikeUrl(it) && it != st.active?.url }
        } else focusManager.clearFocus(force = true)
    }
    LaunchedEffect(q) {
        val t = q.trim()
        if (t.isEmpty()) { remote = emptyList(); return@LaunchedEffect }
        delay(140)
        remote = suggest(t)
    }

    // Inline completion from sites visited most ("you" -> "youtube.com").
    val hosts = remember(st.store.history.size) {
        st.store.history.map { hostOf(it.url) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    val completion = remember(q, hosts) { completeHost(q, hosts) }
    val shortcut = remember(q) { Shortcuts.parse(q) }

    fun go(input: String) {
        if (input.isBlank()) return
        if (input.trimStart().startsWith("@") && Shortcuts.parse(input)?.second.isNullOrBlank()) return
        focusManager.clearFocus(force = true)
        st.open(input)
    }

    val shown = remember(q, remote, st.store.history.size) {
        val t = q.trim()
        if (t.isEmpty()) {
            st.store.history.take(5).map { Sug(it.title.ifBlank { hostOf(it.url) }, it.url, G.Globe) }
        } else {
            val hist = st.store.history.asSequence()
                .filter { it.title.contains(t, true) || it.url.contains(t, true) }
                .take(2).map { Sug(it.title.ifBlank { hostOf(it.url) }, it.url, G.Globe) }.toList()
            val sug = remote.filter { !it.equals(t, true) }.take(6).map { Sug(it, null, G.Search) }
            hist + sug
        }
    }
    val recent = remember(st.store.history.size) {
        st.store.history.map { hostOf(it.url) }.filter { it.isNotBlank() }.distinct().take(4)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp)).background(pal.soft)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Glyph(G.Search, pal.fg, 20.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) T(if (st.aiMode) "Ask AI or type a URL" else "Search or type a URL", 17.sp, 500, pal.fg.copy(alpha = 0.45f))
                    val sfx = completion
                    if (sfx != null) T(buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.Transparent, fontWeight = FontWeight(500))) { append(q) }
                        withStyle(SpanStyle(color = pal.fg.copy(alpha = 0.4f), fontWeight = FontWeight(500))) { append(sfx) }
                    }, 17.sp)
                    BasicTextField(
                        q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 17.sp, color = pal.fg),
                        cursorBrush = SolidColor(pal.fg),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { go(q + (completion ?: "")) })
                    )
                }
                if (q.isEmpty()) Box(Modifier.squish(0.9f) { st.host.voiceSearch() }.size(40.dp), contentAlignment = Alignment.Center) {
                    Glyph(G.Mic, pal.fg, 20.dp, 1.8.dp)
                }
                AnimatedVisibility(
                    q.isNotEmpty(),
                    enter = scaleIn(tween(160, easing = EaseOut)) + fadeIn(tween(160)),
                    exit = scaleOut(tween(120)) + fadeOut(tween(120))
                ) {
                    Box(Modifier.squish(0.9f) { q = "" }.size(40.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                            Glyph(G.Close, pal.bg, 14.dp, 2.2.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.enterUp(0, 120).squish { st.screen = Screen.Home }
                    .height(56.dp).clip(RoundedCornerShape(28.dp)).background(pal.fg).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) { T("Cancel", 16.sp, 600, pal.bg) }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val cur = st.active
            if (q.isBlank() && cur != null && !cur.blank) {
                item(key = "cur") {
                    Row(
                        Modifier.animateItem().enterUp(0, 160).squish(0.96f) { q = cur.query ?: cur.url }
                            .fillMaxWidth().height(64.dp).clip(RoundedCornerShape(22.dp)).background(pal.soft)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            T(cur.query ?: cur.title, 15.sp, 600)
                            T(if (cur.query != null) "Google Search" else cur.url, 12.sp, 500, pal.fg.copy(alpha = 0.55f))
                        }
                        Spacer(Modifier.width(10.dp))
                        T("Edit", 14.sp, 600)
                    }
                }
            }
            if (q.trimStart().startsWith("@") && (shortcut == null || !q.contains(' '))) {
                // "@" shortcuts: pick a site to search
                items(Shortcuts.all.filter { ("@" + it.key).startsWith(q.trim().lowercase()) }, key = { "sc:" + it.key }) { sc ->
                    SuggestionRow("@" + sc.key + "   " + sc.name, q.trim(), G.Search, Modifier.animateItem()) {
                        q = "@" + sc.key + " "
                    }
                }
            } else if (q.isNotBlank()) {
                item(key = "typed") {
                    val sc = shortcut
                    val full = q.trim() + (completion ?: "")
                    if (sc != null) SuggestionRow("Search " + sc.first.name + " for " + sc.second, sc.second, G.Search, Modifier.animateItem()) { go(q) }
                    else SuggestionRow(full, q.trim(), if (looksLikeUrl(full)) G.Globe else G.Search, Modifier.animateItem()) { go(full) }
                }
                if (completion != null) item(key = "typed2") {
                    SuggestionRow(q.trim(), q.trim(), G.Search, Modifier.animateItem()) { go("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")) }
                }
            }
            if (shown.isNotEmpty()) item(key = "h1") {
                Label(if (q.isBlank()) "Recent" else "Suggestions", Modifier.animateItem().enterUp(1, 160))
            }
            items(shown, key = { "s:" + it.text + (it.url ?: "") }) { s ->
                val i = shown.indexOf(s)
                SuggestionRow(s.text, q.trim(), s.icon, Modifier.animateItem().enterUp(i + 2, 160),
                    onFill = if (s.url == null) ({ q = s.text }) else null) { go(s.url ?: s.text) }
            }
            if (q.isBlank() && recent.isNotEmpty()) {
                item(key = "h2") { Label("Sites", Modifier.padding(top = 14.dp).animateItem().enterUp(8, 160)) }
                item(key = "recent") {
                    Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (i in 0 until 4) {
                            val n = recent.getOrNull(i)
                            if (n == null) { Spacer(Modifier.weight(1f)); continue }
                            Column(
                                Modifier.weight(1f).enterUp(9 + i, 160).squish(0.96f) { go(n) }
                                    .aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(pal.soft)
                                    .padding(horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                                    T(n.take(1).uppercase(), 18.sp, 700, pal.bg)
                                }
                                Spacer(Modifier.height(10.dp))
                                T(n.substringBeforeLast('.').substringAfterLast('.').replaceFirstChar { it.uppercase() }, 12.sp, 600)
                            }
                        }
                    }
                }
            }
            val c = clip
            if (q.isBlank() && c != null) {
                item(key = "h3") { Label("Clipboard", Modifier.padding(top = 14.dp).animateItem().enterUp(13, 160)) }
                item(key = "clip") {
                    Row(
                        Modifier.animateItem().enterUp(14, 160).squish(0.96f) { go(c) }
                            .fillMaxWidth().height(64.dp).clip(RoundedCornerShape(22.dp)).background(pal.fg)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T("Go to  " + c.removePrefix("https://").removePrefix("http://"), 16.sp, 500, pal.bg, Modifier.weight(1f))
                        Glyph(G.Arrow, pal.bg, 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    T(text, 13.sp, 600, LocalPal.current.fg.copy(alpha = 0.55f), modifier.padding(start = 6.dp, bottom = 2.dp))

@Composable
private fun SuggestionRow(word: String, q: String, icon: G, modifier: Modifier, onFill: (() -> Unit)? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    val text = remember(word, q) {
        buildAnnotatedString {
            val i = if (q.isBlank()) -1 else word.indexOf(q, ignoreCase = true)
            if (i < 0) append(word) else {
                append(word.substring(0, i))
                withStyle(SpanStyle(fontWeight = FontWeight(700))) { append(word.substring(i, i + q.length)) }
                append(word.substring(i + q.length))
            }
        }
    }
    Row(
        modifier.squish(0.96f, onClick = onClick).fillMaxWidth().height(58.dp)
            .clip(RoundedCornerShape(20.dp)).background(pal.soft).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(icon, pal.fg, 18.dp, 1.8.dp)
        Spacer(Modifier.width(14.dp))
        T(text, 16.sp, modifier = Modifier.weight(1f))
        if (onFill != null) {
            Box(Modifier.squish(0.9f, onClick = onFill).size(44.dp), contentAlignment = Alignment.Center) {
                Glyph(G.UpLeft, pal.fg, 18.dp, 1.8.dp)
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

// =====================================================================
// TABS
// =====================================================================
@Composable
fun TabsScreen(st: BrowserState) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("Tabs", 40.sp, 700, modifier = Modifier.weight(1f).enterUp(0, 60), tracking = -0.03f)
                Box(
                    Modifier.enterUp(1, 60).height(36.dp).clip(RoundedCornerShape(18.dp)).background(pal.soft)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(st.tabs.size, transitionSpec = {
                        (slideInVertically(tween(220, easing = EaseOut)) { it } + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)))
                    }, label = "open") { T("$it open", 14.sp, 600) }
                }
                if (st.store.recent.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.enterUp(2, 60).squish(0.94f) { st.listMode = ListMode.Recent; st.screen = Screen.Lists }
                            .height(36.dp).clip(RoundedCornerShape(18.dp)).background(pal.soft).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { T("Recent", 14.sp, 600) }
                }
            }
            GroupChips(st, Modifier.padding(top = 8.dp))
            val shownTabs = st.tabs.filter { st.groupFilter == null || it.group == st.groupFilter }
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 1.0.0: index from the list itself (was an indexOf scan per card)
                itemsIndexed(shownTabs, key = { _, it -> it.id }) { i, tab ->
                    TabCard(
                        tab, tab.id == st.activeId, st.privateLocked,
                        Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = tween(260, easing = EaseOut),
                            fadeOutSpec = tween(160)
                        ).enterUp(i, 80),
                        onOpen = { st.activeId = tab.id; st.saveTabs(); st.screen = Screen.Home },
                        onClose = { st.close(tab.id) },
                        onLong = { st.card = Card.Group(tab.id) }
                    )
                }
                item(key = "new") {
                    Column(Modifier.animateItem(placementSpec = tween(260, easing = EaseOut)).enterUp(shownTabs.size, 80)) {
                        Box(
                            Modifier.squish(0.96f) { st.newTab(); st.screen = Screen.Home }.fillMaxWidth().aspectRatio(0.72f)
                                .clip(RoundedCornerShape(26.dp)).background(pal.soft),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(52.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                                    Glyph(G.Plus, pal.bg, 22.dp, 2.4.dp)
                                }
                                Spacer(Modifier.height(12.dp))
                                T("New tab", 14.sp, 600)
                            }
                        }
                    }
                }
                if (shownTabs.isEmpty()) item(key = "empty", span = { GridItemSpan(2) }) {
                    T(if (st.groupFilter != null) "No tabs in this group. Long-press a tab to add it." else "No open tabs", 15.sp, 500, pal.fg.copy(alpha = 0.5f), Modifier.padding(top = 20.dp).animateItem())
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.enterUp(0, 140).squish { st.closeAll() }
                    .height(58.dp).width(124.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { T("Close all", 15.sp, 600) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.enterUp(1, 140).squish(0.92f) { st.newTab(); st.screen = Screen.Search }.size(58.dp).clip(CircleShape).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Plus, pal.bg, 24.dp, 2.4.dp) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.enterUp(2, 140).squish { st.screen = Screen.Home }
                    .height(58.dp).width(124.dp).clip(RoundedCornerShape(29.dp)).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { T("Done", 15.sp, 700, pal.bg) }
        }
    }
}

@Composable
private fun TabCard(tab: Tab, active: Boolean, locked: Boolean, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit, onLong: () -> Unit) {
    val pal = LocalPal.current
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pageBg = tab.topColor ?: pal.bg
    Column(
        modifier
            .graphicsLayer {
                translationX = dx.value
                alpha = 1f - (kotlin.math.abs(dx.value) / (size.width * 1.2f)).coerceIn(0f, 0.8f)
            }
            .pointerInput(Unit) {
                // swipe a card sideways to close the tab
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (kotlin.math.abs(dx.value) > size.width * 0.4f) {
                                dx.animateTo(if (dx.value > 0) size.width * 1.4f else -size.width * 1.4f, tween(160, easing = EaseOut))
                                onClose()
                            } else dx.animateTo(0f, tween(220, easing = EaseOut))
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, tween(220, easing = EaseOut)) } }
                ) { ch, d -> ch.consume(); scope.launch { dx.snapTo(dx.value + d) } }
            }
    ) {
        Box(
            Modifier.squish(0.96f, onLongPress = onLong, onClick = onOpen)
                .fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(26.dp))
                .background(if (active) pal.fg else pal.soft).padding(if (active) 5.dp else 4.dp)
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(pageBg), contentAlignment = Alignment.Center) {
                val img = tab.thumb
                when {
                    tab.private && locked -> Box(Modifier.size(22.dp).clip(CircleShape).background(pal.fg))
                    img != null && !tab.blank -> Image(img, null, Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
                    tab.blank -> Box(Modifier.size(22.dp).clip(CircleShape).background(pal.fg))
                    else -> Box(Modifier.size(52.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                        T(tab.host.take(1).uppercase(), 22.sp, 700, pal.bg)
                    }
                }
                if (tab.private) Box(
                    Modifier.align(Alignment.TopStart).padding(10.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                        .background(pal.fg).padding(horizontal = 10.dp), contentAlignment = Alignment.Center
                ) { T("Private", 11.sp, 700, pal.bg) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (tab.private && locked) "Private tab" else tab.title, 14.sp, 700, modifier = Modifier.weight(1f))
            Box(
                Modifier.squish(0.9f, onClick = onClose).size(28.dp).clip(CircleShape).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Close, pal.fg, 12.dp, 2.dp) }
        }
    }
}

// =====================================================================
// MORE
// =====================================================================
@Composable
fun MoreScreen(st: BrowserState) {
    val pal = LocalPal.current
    val tab = st.active
    val page = tab != null && !tab.blank
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { T("More", 40.sp, 700, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).enterUp(0, 60), tracking = -0.03f) }
            if (page) item(key = "nav") {
                Row(Modifier.enterUp(1, 60), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val v = tab!!.view
                    NavButton(G.Back, tab.canBack, Modifier.weight(1f), onLong = { st.card = Card.History(tab.id) }) { v?.goBack(); st.screen = Screen.Home }
                    NavButton(G.Forward, tab.canForward, Modifier.weight(1f), onLong = { st.card = Card.History(tab.id) }) { v?.goForward(); st.screen = Screen.Home }
                    NavButton(G.Reload, true, Modifier.weight(1f)) { v?.reload(); st.screen = Screen.Home }
                    NavButton(G.Share, true, Modifier.weight(1f)) { st.host.share(tab.url, tab.title) }
                    val marked = st.store.isBookmarked(tab.url)
                    NavButton(G.Star, true, Modifier.weight(1f), filled = marked) { st.store.toggleBookmark(tab.url, tab.title) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.weight(1f).enterUp(2, 60).squish(0.96f) { st.newTab(); st.screen = Screen.Search }
                            .height(84.dp).clip(RoundedCornerShape(26.dp)).background(pal.fg).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T("New tab", 17.sp, 600, pal.bg, Modifier.weight(1f))
                        Glyph(G.Plus, pal.bg, 22.dp, 2.2.dp)
                    }
                    val dotScale by animateFloatAsState(if (st.privateMode) 1f else 0.45f, tween(220, easing = EaseOut), label = "pd")
                    Row(
                        Modifier.weight(1f).enterUp(3, 60).squish(0.96f) { st.privateMode = !st.privateMode }
                            .height(84.dp).clip(RoundedCornerShape(26.dp)).background(pal.soft).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T("Private tab", 17.sp, 600, modifier = Modifier.weight(1f))
                        Box(Modifier.size(18.dp).graphicsLayer { scaleX = dotScale; scaleY = dotScale }.clip(CircleShape).background(pal.fg))
                    }
                }
            }
            if (page) item(key = "tools") {
                Row(Modifier.enterUp(2, 60), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolChip("Find in page", false, Modifier.weight(1f)) { st.finding = true; st.screen = Screen.Home }
                    ToolChip(if (tab!!.desktop) "Desktop ✓" else "Desktop site", tab.desktop, Modifier.weight(1f)) {
                        setDesktop(tab, !tab.desktop); st.screen = Screen.Home
                    }
                    ToolChip("Text ${tab.zoom}%", tab.zoom != 100, Modifier.weight(1f)) { st.cycleZoom(tab) }
                }
            }
            if (page) item(key = "tools2") {
                Row(Modifier.enterUp(3, 60), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolChip("Reader", false, Modifier.weight(1f)) { openReader(st, tab!!) }
                    ToolChip("Translate", false, Modifier.weight(1f)) { translatePage(st, tab!!) }
                    ToolChip(if (tab!!.shields && tab.blocked > 0) "Site · ${tab.blocked}" else "Site info", false, Modifier.weight(1f)) {
                        st.screen = Screen.Home; st.card = Card.SiteInfo
                    }
                }
            }
            if (page) item(key = "tools3") {
                Row(Modifier.enterUp(3, 60), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolChip("Add to Home", false, Modifier.weight(1f)) { st.host.addToHome(tab!!.url, tab.label, tab.favicon) }
                    ToolChip("Save PDF", false, Modifier.weight(1f)) { tab!!.view?.let { st.host.printPage(it, tab.title) } }
                    ToolChip("QR code", false, Modifier.weight(1f)) { st.screen = Screen.Home; st.card = Card.Qr(tab!!.url, tab.label) }
                }
            }
            val rows = listOf("Bookmarks", "History", "Recently closed", "Downloads", "Dark mode", "Dark pages", "Lock private tabs")
            rows.forEachIndexed { i, name ->
                item(key = name) {
                    val isToggle = name == "Dark mode" || name == "Dark pages" || name == "Lock private tabs"
                    Row(
                        Modifier.enterUp(4 + i, 60).squish(0.96f) {
                            when (name) {
                                "Dark mode" -> st.requestTheme()
                                "Bookmarks" -> { st.listMode = ListMode.Bookmarks; st.screen = Screen.Lists }
                                "History" -> { st.listMode = ListMode.History; st.screen = Screen.Lists }
                                "Recently closed" -> { st.listMode = ListMode.Recent; st.screen = Screen.Lists }
                                "Dark pages" -> st.setForceDarkPages(!st.forceDark)
                                "Lock private tabs" -> {
                                    if (st.lockPrivate) st.host.unlockPrivate { ok -> if (ok) st.changeLockPrivate(false) } else st.changeLockPrivate(true)
                                }
                                "Downloads" -> st.openDownloads(Screen.More)
                            }
                        }
                            .fillMaxWidth().height(66.dp).clip(RoundedCornerShape(22.dp)).background(pal.soft)
                            .padding(start = 20.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        T(name, 17.sp, 500, modifier = Modifier.weight(1f))
                        if (isToggle) Box(Modifier.onGloballyPositioned { if (name == "Dark mode") st.themeOrigin = it.boundsInRoot().center }) {
                            CleanSwitch(when (name) { "Dark pages" -> st.forceDark; "Lock private tabs" -> st.lockPrivate; else -> st.dark })
                        } else {
                            if (name == "Recently closed" && st.store.recent.isNotEmpty()) {
                                T(st.store.recent.size.toString(), 15.sp, 600, pal.fg.copy(alpha = 0.55f))
                                Spacer(Modifier.width(8.dp))
                            }
                            if (name == "Downloads" && st.store.downloads.isNotEmpty()) {
                                T(st.store.downloads.size.toString(), 15.sp, 600, pal.fg.copy(alpha = 0.55f))
                                Spacer(Modifier.width(8.dp))
                            }
                            Glyph(G.Chevron, pal.fg, 20.dp, 2.dp)
                        }
                    }
                }
            }
            st.account?.let { acc ->
                item(key = "acc") {
                    Row(
                        Modifier.enterUp(12, 60).fillMaxWidth().height(66.dp).clip(RoundedCornerShape(22.dp)).background(pal.soft)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                            T(acc.name.take(1).uppercase(), 15.sp, 700, pal.bg)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            T(acc.name, 15.sp, 600)
                            T(if (st.webSignedIn) acc.email + " · signed in on the web" else acc.email, 12.sp, 500, pal.fg.copy(alpha = 0.55f))
                        }
                        if (!st.webSignedIn) Box(
                            Modifier.squish(0.94f) { st.webSignInRequested = true }.height(36.dp)
                                .clip(RoundedCornerShape(18.dp)).background(pal.fg).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) { T("Sign in", 13.sp, 700, pal.bg) }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 36.dp).enterUp(10, 60), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(pal.fg))
                    Spacer(Modifier.height(10.dp))
                    T("Browser $APP_VERSION", 13.sp, 500, pal.fg.copy(alpha = 0.55f))
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 22.dp, bottom = 22.dp)
                .squish(0.92f) { st.screen = Screen.Home }
                .size(58.dp).clip(CircleShape).background(pal.fg),
            contentAlignment = Alignment.Center
        ) { Glyph(G.Close, pal.bg, 22.dp, 2.4.dp) }
    }
}

@Composable
private fun ToolChip(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        modifier.squish(0.94f, onClick = onClick).height(52.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) pal.fg else pal.soft),
        contentAlignment = Alignment.Center
    ) { T(text, 14.sp, 600, if (on) pal.bg else pal.fg) }
}

@Composable
private fun NavButton(g: G, enabled: Boolean, modifier: Modifier, filled: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        modifier.squish(0.92f, onLongPress = onLong) { if (enabled) onClick() }.height(58.dp).clip(RoundedCornerShape(22.dp))
            .background(if (filled) pal.fg else pal.soft).graphicsLayer { alpha = if (enabled) 1f else 0.35f },
        contentAlignment = Alignment.Center
    ) { Glyph(g, if (filled) pal.bg else pal.fg, 20.dp, 2.dp) }
}

// =====================================================================
// BOOKMARKS / HISTORY
// =====================================================================
@Composable
fun ListScreen(st: BrowserState) {
    val pal = LocalPal.current
    val mode = st.listMode
    val list = when (mode) { ListMode.Bookmarks -> st.store.bookmarks; ListMode.History -> st.store.history; ListMode.Recent -> st.store.recent }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title") {
                T(when (mode) { ListMode.Bookmarks -> "Bookmarks"; ListMode.History -> "History"; ListMode.Recent -> "Recently closed" }, 40.sp, 700,
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp).enterUp(0, 60), tracking = -0.03f)
            }
            if (list.isEmpty()) item(key = "empty") {
                T(if (mode == ListMode.Bookmarks) "No bookmarks yet. Tap the star in More to save a page." else "Nothing here yet.",
                    15.sp, 500, pal.fg.copy(alpha = 0.5f), Modifier.padding(start = 4.dp).enterUp(1, 60))
            }
            items(list.take(300), key = { it.url + it.time }) { e ->
                Row(
                    Modifier.animateItem(fadeOutSpec = tween(160), placementSpec = tween(240, easing = EaseOut))
                        .squish(0.97f) { if (mode == ListMode.Recent) st.reopen(e) else st.open(e.url) }
                        .fillMaxWidth().height(66.dp).clip(RoundedCornerShape(22.dp)).background(pal.soft)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                        T(hostOf(e.url).take(1).uppercase(), 14.sp, 700, pal.bg)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        T(e.title.ifBlank { hostOf(e.url) }, 15.sp, 600)
                        T(hostOf(e.url), 12.sp, 500, pal.fg.copy(alpha = 0.55f))
                    }
                    Box(
                        Modifier.squish(0.9f) {
                            when (mode) { ListMode.Bookmarks -> st.store.removeBookmark(e); ListMode.History -> st.store.removeHistory(e); ListMode.Recent -> st.store.removeRecent(e) }
                        }.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) { Glyph(G.Close, pal.fg, 14.dp, 2.dp) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mode != ListMode.Bookmarks && list.isNotEmpty()) Box(
                Modifier.squish { if (mode == ListMode.Recent) st.store.clearRecent() else st.store.clearHistory() }
                    .height(58.dp).width(124.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { T("Clear all", 15.sp, 600) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.squish(0.92f) { st.screen = Screen.More }
                    .size(58.dp).clip(CircleShape).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Close, pal.bg, 22.dp, 2.4.dp) }
        }
    }
}

@Composable
fun CleanSwitch(on: Boolean) {
    val pal = LocalPal.current
    val p = remember { Animatable(if (on) 1f else 0f) }
    LaunchedEffect(on) { p.animateTo(if (on) 1f else 0f, tween(220, easing = EaseOut)) }
    val trackW = 54.dp; val trackH = 32.dp; val pad = 4.dp; val thumb = 24.dp
    Box(
        Modifier.width(trackW).height(trackH).clip(RoundedCornerShape(16.dp))
            .background(if (on) pal.fg else pal.faint)
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val v = p.value
                    val travel = (trackW - thumb - pad * 2).toPx()
                    translationX = pad.toPx() + travel * v
                    translationY = pad.toPx()
                }
                .size(thumb).clip(CircleShape).background(if (on) pal.bg else Color.White)
        )
    }
}
