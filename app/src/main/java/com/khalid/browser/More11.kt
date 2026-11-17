package com.khalid.browser

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.min

// =====================================================================
// 1.1.0 screens and cards: reader, site info, QR, back history, tab menu, groups,
// site permission ask, private-tab lock, edge swipe.
// =====================================================================

/** Wrapped multi-line text (T() is single line). */
@Composable
fun P(text: String, size: androidx.compose.ui.unit.TextUnit = 16.sp, weight: Int = 400, color: Color = LocalPal.current.fg,
      modifier: Modifier = Modifier, lineHeight: Float = 1.5f, maxLines: Int = Int.MAX_VALUE) = BasicText(
    text, modifier,
    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size, color = color,
        lineHeight = (size.value * lineHeight).sp, letterSpacing = (-0.005f).em),
    maxLines = maxLines
)

// ---------------- page actions used from More ----------------
fun openReader(st: BrowserState, tab: Tab) {
    val v = tab.view ?: return
    v.evaluateJavascript(Scripts.READER) { raw ->
        val a = parseArticle(tab.url, raw)
        if (a == null) st.notify("Reader view isn't available on this page")
        else { st.article = a; st.screen = Screen.Reader }
    }
}

fun translatePage(st: BrowserState, tab: Tab) {
    if (tab.blank) return
    if (tab.host.contains("translate.goog") || tab.host.startsWith("translate.google")) { st.notify("Already translated"); return }
    st.open(translateUrl(tab.url))
}

// ---------------- reader ----------------
private val imageCache = object : android.util.LruCache<String, ImageBitmap>(24) {}

@Composable
private fun NetImage(url: String, modifier: Modifier) {
    val pal = LocalPal.current
    val img by produceState<ImageBitmap?>(imageCache.get(url), url) {
        if (value != null || isRobolectric) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                val bytes = try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1; while (o.outWidth / s > 1400) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    ?.asImageBitmap()?.also { imageCache.put(url, it) }
            }.getOrNull()
        }
    }
    val b = img
    if (b == null) Box(modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)).background(pal.soft))
    else Image(b, null, modifier.fillMaxWidth().aspectRatio((b.width.toFloat() / b.height).coerceIn(0.5f, 3f)).clip(RoundedCornerShape(18.dp)),
        contentScale = ContentScale.Crop)
}

@Composable
fun ReaderScreen(st: BrowserState) {
    val pal = LocalPal.current
    val a = st.article ?: return
    var size by remember { mutableIntStateOf(st.store.app.getSharedPreferences("browser", 0).getInt("reader_size", 18)) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "site") { T(a.site.uppercase(), 12.sp, 700, pal.fg.copy(alpha = 0.55f), tracking = 0.06f) }
            item(key = "title") { P(a.title, (size + 12).sp, 700, lineHeight = 1.18f) }
            if (a.by.isNotBlank()) item(key = "by") { T(a.by, 14.sp, 500, pal.fg.copy(alpha = 0.6f)) }
            item(key = "gap") { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(a.blocks, key = { i, _ -> i }) { _, (t, v) ->
                when (t) {
                    "h" -> P(v, (size + 3).sp, 700, modifier = Modifier.padding(top = 8.dp), lineHeight = 1.3f)
                    "li" -> Row {
                        T("•", size.sp, 700, modifier = Modifier.padding(end = 10.dp))
                        P(v, size.sp)
                    }
                    "q" -> Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.width(3.dp).heightIn(min = 24.dp).fillMaxHeight().background(pal.fg))
                        P(v, size.sp, 500, modifier = Modifier.padding(start = 14.dp))
                    }
                    "pre" -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.soft).padding(14.dp)) {
                        P(v, (size - 4).sp, 500, lineHeight = 1.4f)
                    }
                    "img" -> NetImage(v, Modifier)
                    else -> P(v, size.sp)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.squish { size = if (size >= 24) 15 else size + 3; st.store.app.getSharedPreferences("browser", 0).edit().putInt("reader_size", size).apply() }
                    .height(58.dp).width(96.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { T("Aa  $size", 15.sp, 600) }
            Box(
                Modifier.squish { st.host.share(a.url, a.title) }
                    .size(58.dp).clip(CircleShape).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Share, pal.fg, 20.dp) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.squish(0.92f) { st.screen = Screen.Home }
                    .size(58.dp).clip(CircleShape).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Close, pal.bg, 22.dp, 2.4.dp) }
        }
    }
}

// ---------------- bottom cards ----------------
@Composable
fun CardRow(text: String, primary: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), trailing: String? = null, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(
        modifier.squish(0.97f, onClick = onClick).height(56.dp).clip(RoundedCornerShape(20.dp))
            .background(if (primary) pal.fg else pal.soft).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(text, 16.sp, 600, if (primary) pal.bg else pal.fg, Modifier.weight(1f, fill = false))
        if (trailing != null) { Spacer(Modifier.weight(1f)); T(trailing, 14.sp, 600, (if (primary) pal.bg else pal.fg).copy(alpha = 0.6f)) }
    }
}

/** Scrim + a rounded card rising from the bottom, the same look as the link menu. */
@Composable
private fun BottomCard(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val pal = LocalPal.current
    val scrim by animateFloatAsState(if (visible) 1f else 0f, tween(220, easing = EaseOut), label = "cscrim")
    val scrimOn by remember { androidx.compose.runtime.derivedStateOf { scrim > 0.001f } } // 1.0.0: recompose only when it appears/disappears
    if (scrimOn) Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(pal.bg.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    )
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(280, easing = EaseOut)) { it / 4 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 4 }
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(pal.raised)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { content() }
        }
    }
}

@Composable
private fun CardHead(title: String, sub: String? = null) {
    val pal = LocalPal.current
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        T(title, 17.sp, 700)
        if (sub != null) { Spacer(Modifier.height(3.dp)); T(sub, 13.sp, 500, pal.fg.copy(alpha = 0.55f)) }
    }
}

@Composable
fun Cards(st: BrowserState) {
    val card = st.card
    var last by remember { mutableStateOf<Card?>(null) }
    if (card != null) last = card
    BottomCard(card != null, { st.card = null }) {
        when (val c = last) {
            is Card.SiteInfo -> SiteInfoCard(st)
            is Card.Qr -> QrCard(st, c)
            is Card.History -> HistoryCard(st, c.tabId)
            is Card.TabMenu -> TabMenuCard(st)
            is Card.Group -> GroupCard(st, c.tabId)
            is Card.Widgets -> WidgetsCard(st)
            is Card.FileMenu -> FileMenuCard(st, c.d)
            is Card.Update -> UpdateCard(st)
            is Card.DefaultBrowser -> DefaultBrowserCard(st)
            null -> {}
        }
    }
}

@Composable
private fun SiteInfoCard(st: BrowserState) {
    val pal = LocalPal.current
    val tab = st.active ?: return
    var bump by remember { mutableIntStateOf(0) }
    val secure = tab.url.startsWith("https://") && !tab.sslError
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteIcon(tab, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(tab.host.ifBlank { "Page" }, 17.sp, 700)
            Spacer(Modifier.height(3.dp))
            T(if (secure) "Connection is secure" else "Not secure", 13.sp, 600, if (secure) pal.fg.copy(alpha = 0.55f) else pal.fg)
        }
    }
    Row(
        Modifier.squish(0.97f) { st.setShields(tab, !tab.shields) }.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(20.dp))
            .background(pal.soft).padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            T("Block ads & trackers", 16.sp, 600)
            T(if (!st.adOn) "Off in Settings" else if (!tab.shields) "Ads allowed on this site" else if (tab.blocked == 1) "1 blocked on this page" else "${tab.blocked} blocked on this page",
                12.sp, 500, pal.fg.copy(alpha = 0.55f))
        }
        CleanSwitch(tab.shields && st.adOn)
    }
    val perms = remember(bump, tab.host) { st.site.perms(tab.host).sorted() }
    perms.forEach { p ->
        CardRow(p.replaceFirstChar { it.uppercase() } + " allowed", trailing = "Remove") {
            st.site.revoke(tab.host, p); bump++
            if (p == "location") runCatching { originOf(tab.url)?.let { android.webkit.GeolocationPermissions.getInstance().clear(it) } }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardRow("QR code", modifier = Modifier.weight(1f)) { st.card = Card.Qr(tab.url, tab.label) }
        CardRow("Copy link", modifier = Modifier.weight(1f)) { st.card = null; st.host.copy(tab.url); st.notify("Link copied") }
    }
}

@Composable
fun SiteIcon(tab: Tab, sizeDp: Int) {
    val pal = LocalPal.current
    val fav = tab.favicon
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(if (fav != null) pal.soft else pal.fg), contentAlignment = Alignment.Center) {
        if (fav != null) Image(remember(fav) { fav.asImageBitmap() }, null, Modifier.size((sizeDp * 0.55f).dp))
        else T(tab.host.take(1).uppercase().ifBlank { "•" }, (sizeDp * 0.4f).sp, 700, pal.bg)
    }
}

@Composable
private fun QrCard(st: BrowserState, c: Card.Qr) {
    val pal = LocalPal.current
    val m = remember(c.url) { Qr.encode(c.url) }
    CardHead(c.title.ifBlank { hostOf(c.url) }, c.url.removePrefix("https://").removePrefix("http://").take(60))
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        if (m == null) T("This link is too long for a QR code", 15.sp, 500, pal.fg.copy(alpha = 0.6f))
        else Canvas(Modifier.size(240.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)) {
            val n = m.size
            val quiet = 3
            val cell = size.width / (n + quiet * 2)
            for (y in 0 until n) for (x in 0 until n) if (m[y][x])
                drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardRow("Share", true, Modifier.weight(1f)) { st.card = null; st.host.share(c.url, c.title) }
        CardRow("Copy link", modifier = Modifier.weight(1f)) { st.card = null; st.host.copy(c.url); st.notify("Link copied") }
    }
}

/** Long-press Back: the tab's page history; tap one to jump there. */
@Composable
private fun HistoryCard(st: BrowserState, tabId: Int) {
    val pal = LocalPal.current
    val tab = st.tabs.firstOrNull { it.id == tabId } ?: return
    val list = remember(tabId, tab.url) {
        runCatching {
            val h = tab.view?.copyBackForwardList() ?: return@runCatching emptyList<Triple<Int, String, String>>()
            (0 until h.size).map { i -> h.getItemAtIndex(i).let { Triple(i - h.currentIndex, it.title.orEmpty(), it.url.orEmpty()) } }.reversed()
        }.getOrDefault(emptyList())
    }
    CardHead("Page history", tab.host)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.first }) { (steps, title, url) ->
            Row(
                Modifier.squish(0.97f) { st.card = null; if (steps != 0) tab.view?.goBackOrForward(steps); st.screen = Screen.Home }
                    .fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (steps == 0) pal.fg else pal.soft).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = if (steps == 0) pal.bg else pal.fg
                Column(Modifier.weight(1f)) {
                    T(title.ifBlank { hostOf(url) }, 15.sp, 600, ink)
                    T(hostOf(url), 12.sp, 500, ink.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Long-press the tab button. */
@Composable
private fun TabMenuCard(st: BrowserState) {
    CardHead("Tabs", if (st.tabs.size == 1) "1 open" else "${st.tabs.size} open")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardRow("New tab", true, Modifier.weight(1f)) { st.card = null; st.newTab(private = false); st.screen = Screen.Search }
        CardRow("Private tab", modifier = Modifier.weight(1f)) { st.card = null; st.newTab(private = true); st.screen = Screen.Search }
    }
    CardRow("Recently closed", trailing = st.store.recent.size.takeIf { it > 0 }?.toString()) {
        st.card = null; st.listMode = ListMode.Recent; st.screen = Screen.Lists
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardRow("Close this tab", modifier = Modifier.weight(1f)) { st.card = null; st.close(st.activeId) }
        CardRow("Close all", modifier = Modifier.weight(1f)) { st.card = null; st.groupFilter = null; st.closeAll() }
    }
}

/** Put a tab in a group (or take it out), make a new group. */
@Composable
private fun GroupCard(st: BrowserState, tabId: Int) {
    val pal = LocalPal.current
    val tab = st.tabs.firstOrNull { it.id == tabId } ?: return
    var name by remember { mutableStateOf("") }
    CardHead("Group", tab.label)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "none") { CardRow("No group", tab.group == null) { tab.group = null; st.saveTabs(); st.card = null } }
        items(st.store.groups.toList(), key = { it }) { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardRow(g, tab.group == g, Modifier.weight(1f), trailing = st.tabs.count { it.group == g }.toString()) {
                    tab.group = g; st.saveTabs(); st.card = null
                }
                Box(Modifier.squish(0.9f) { st.deleteGroup(g) }.size(48.dp), contentAlignment = Alignment.Center) {
                    Glyph(G.Close, pal.fg, 14.dp, 2.dp)
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).background(pal.soft).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (name.isEmpty()) T("New group", 16.sp, 500, pal.fg.copy(alpha = 0.45f))
            BasicTextField(
                name, { name = it.take(24) }, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight(500), fontSize = 16.sp, color = pal.fg),
                cursorBrush = SolidColor(pal.fg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) { st.addGroup(name, tab); st.card = null } })
            )
        }
        Box(
            Modifier.squish(0.94f) { if (name.isNotBlank()) { st.addGroup(name, tab); st.card = null } }
                .height(44.dp).clip(RoundedCornerShape(22.dp)).background(pal.fg).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) { T("Add", 14.sp, 700, pal.bg) }
    }
}

/** Group chips above the Tabs grid: All, each group. */
@Composable
fun GroupChips(st: BrowserState, modifier: Modifier) {
    val pal = LocalPal.current
    if (st.store.groups.isEmpty()) return
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { Chip("All", st.groupFilter == null) { st.groupFilter = null } }
        items(st.store.groups.toList(), key = { it }) { g ->
            Chip("$g  ${st.tabs.count { it.group == g }}", st.groupFilter == g) { st.groupFilter = if (st.groupFilter == g) null else g }
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        Modifier.squish(0.94f, onClick = onClick).height(38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (on) pal.fg else pal.soft).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { T(text, 14.sp, 600, if (on) pal.bg else pal.fg) }
}

// ---------------- site permission ask ----------------
@Composable
fun PermCard(st: BrowserState) {
    val ask = st.perm
    var last by remember { mutableStateOf<PermAsk?>(null) }
    if (ask != null) last = ask
    BottomCard(ask != null, { ask?.done?.invoke(false) }) {
        val a = last ?: return@BottomCard
        val what = when (a.what.size) {
            1 -> "your " + a.what[0]
            else -> "your " + a.what.dropLast(1).joinToString(", ") + " and " + a.what.last()
        }
        CardHead(a.host.ifBlank { "This site" }, "wants to use $what")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow("Block", modifier = Modifier.weight(1f)) { a.done(false) }
            CardRow("Allow", true, Modifier.weight(1f)) { a.done(true) }
        }
    }
}

// ---------------- private tabs lock ----------------
@Composable
fun PrivateLock(st: BrowserState) {
    val pal = LocalPal.current
    val show = st.privateLocked && st.active?.private == true && (st.screen == Screen.Home || st.screen == Screen.Reader)
    val a by animateFloatAsState(if (show) 1f else 0f, tween(180), label = "lock")
    val hiddenNow by remember { androidx.compose.runtime.derivedStateOf { a < 0.001f } } // 1.0.0: alpha read only in the layer
    if (hiddenNow) return
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(pal.bg).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(pal.fg))
            Spacer(Modifier.height(22.dp))
            T("Private tabs are locked", 20.sp, 700)
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.squish { st.host.unlockPrivate { ok -> if (ok) st.privateLocked = false } }
                    .height(56.dp).clip(RoundedCornerShape(28.dp)).background(pal.fg).padding(horizontal = 30.dp),
                contentAlignment = Alignment.Center
            ) { T("Unlock", 16.sp, 700, pal.bg) }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.squish { st.tabs.lastOrNull { !it.private }?.let { st.activeId = it.id } ?: st.newTab(private = false) }
                    .height(48.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) { T("Go to normal tabs", 15.sp, 600, pal.fg.copy(alpha = 0.6f)) }
        }
    }
}

// ---------------- edge swipe back / forward ----------------
/**
 * Swipe in from the left edge = back, right edge = forward, with a round arrow that follows the finger.
 * Only with 3-button navigation: with gesture navigation the system owns the edges (system back = page back).
 */
@Composable
fun BoxScope.EdgeSwipe(st: BrowserState, tab: Tab) {
    val pal = LocalPal.current
    val density = LocalDensity.current
    val gestures = WindowInsets.systemGestures
    val sysLeft = gestures.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
    if (sysLeft > with(density) { 8.dp.toPx() }) return
    val edge = 18.dp
    val trigger = with(density) { 84.dp.toPx() }
    @Composable
    fun strip(left: Boolean) {
        Box(
            Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).width(edge).fillMaxHeight()
                .padding(vertical = 90.dp)
                .pointerInput(left) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> st.edgeX = 0f; st.edgeY = o.y + 90.dp.toPx() },
                        onDragEnd = {
                            val x = st.edgeX
                            if (x > trigger && tab.canBack) { st.host.haptic(); tab.view?.goBack() }
                            else if (x < -trigger && tab.canForward) { st.host.haptic(); tab.view?.goForward() }
                            st.edgeX = 0f
                        },
                        onDragCancel = { st.edgeX = 0f }
                    ) { ch, dx ->
                        ch.consume()
                        val nx = st.edgeX + dx
                        st.edgeX = if (left) nx.coerceAtLeast(0f) else nx.coerceAtMost(0f)
                    }
                }
        )
    }
    strip(true); strip(false)
    // indicator
    val x = st.edgeX
    if (abs(x) > 1f) {
        val left = x > 0
        val k = min(abs(x) / trigger, 1f)
        val can = if (left) tab.canBack else tab.canForward
        Box(
            Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(((if (left) 1 else -1) * (min(abs(x), trigger) * 0.6f - 30.dp.toPx())).toInt(), (st.edgeY - 26.dp.toPx()).toInt()) }
                .size(52.dp).graphicsLayer { alpha = (0.35f + 0.65f * k) * (if (can) 1f else 0.4f); scaleX = 0.8f + 0.2f * k; scaleY = 0.8f + 0.2f * k }
                .clip(CircleShape).background(if (k >= 1f && can) pal.fg else pal.soft),
            contentAlignment = Alignment.Center
        ) { Glyph(if (left) G.Back else G.Forward, if (k >= 1f && can) pal.bg else pal.fg, 22.dp) }
    }
}
