package com.khalid.browser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

fun fmtBytes(b: Long): String = when {
    b <= 0 -> "0 B"
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

private fun statusLine(d: Dl, speed: Double): String = when (d.status) {
    1 -> "Waiting…"
    2 -> buildString {
        append(if (d.total > 0) "${fmtBytes(d.done)} of ${fmtBytes(d.total)}" else fmtBytes(d.done))
        if (speed > 1024) {
            append("  ·  ${fmtBytes(speed.toLong())}/s")
            if (d.total > d.done) {
                append("  ·  " + DlName.timeLeft(((d.total - d.done) / speed).toLong()))
            }
        }
        if (d.note.isNotBlank()) append("  ·  ${d.note.lowercase(Locale.US)}")
    }
    4 -> "Paused  ·  " + DlName.pauseReason(d.reason) + (if (d.total > 0) "  ·  ${(d.done * 100 / d.total)}%" else "") +
        (if (d.own && !d.resumable && d.reason == 10) "  ·  this site can't continue, it will start over" else "")
    8 -> "${fmtBytes(if (d.total > 0) d.total else d.done)}  ·  " + (mediaDur[d.id]?.let { "video  ·  " + fmtDur(it) }
        ?: groupOf(d.name, d.mime).removeSuffix("s").lowercase(Locale.US).let { if (it == "doc") "document" else if (it == "app") "app  ·  tap to install" else it })
    16 -> "Failed  ·  " + DlName.failReason(d.reason).let { if (it.isBlank()) "" else "$it  ·  " } + "tap to try again"
    else -> ""
}

private fun dayLabel(t: Long): String {
    if (t <= 0) return "Earlier"
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val days = (now.get(java.util.Calendar.YEAR) - c.get(java.util.Calendar.YEAR)) * 366 + now.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR)
    return when { days <= 0 -> "Today"; days == 1 -> "Yesterday"; days < 7 -> "This week"; else -> "Earlier" }
}

// =====================================================================
// DOWNLOADS (0.5.0): live speed and time left, filters, days,
// files open inside the browser, a menu per file, open any file on the phone
// =====================================================================
@Composable
fun DownloadsScreen(st: BrowserState, open: Boolean) {
    val pal = LocalPal.current
    var items by remember { mutableStateOf(emptyList<Dl>()) }
    val speeds = remember { mutableStateMapOf<Long, Double>() }
    val seen = remember { HashMap<Long, Pair<Long, Long>>() }
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(open, st.store.downloads.size) {
        while (open) {
            life.awaitStarted() // 1.0.0: no DownloadManager polling while the app is in the background
            val ids = st.store.downloads.toList()
            items = withContext(Dispatchers.IO) { runCatching { st.host.queryDownloads(ids) }.getOrDefault(emptyList()) }
            val now = System.currentTimeMillis()
            items.filter { it.running }.forEach { d ->
                val prev = seen[d.id]
                if (prev != null && now > prev.second) {
                    val inst = (d.done - prev.first) * 1000.0 / (now - prev.second)
                    val old = speeds[d.id]
                    speeds[d.id] = if (old == null || old <= 0) inst else old * 0.7 + inst * 0.3
                }
                seen[d.id] = d.done to now
            }
            delay(if (items.any { it.running }) 500 else 1500)
        }
    }
    val active = items.count { it.running }
    var filter by remember { mutableStateOf("All") }
    val groups = items.map { groupOf(it.name, it.mime) }.distinct()
    val shown = if (filter == "All" || filter !in groups) items else items.filter { groupOf(it.name, it.mime) == filter }
    val totalBytes = items.filter { it.ok }.sumOf { if (it.total > 0) it.total else it.done }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title") {
                Column(Modifier.padding(start = 4.dp, bottom = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T("Downloads", 40.sp, 700, modifier = Modifier.weight(1f).enterUp(0, 60), tracking = -0.03f)
                        AnimatedVisibility(active > 0, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
                            Box(
                                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(pal.fg).padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(active, transitionSpec = {
                                    (slideInVertically(tween(220, easing = EaseOut)) { it } + fadeIn(tween(220))) togetherWith
                                        (slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)))
                                }, label = "active") { T("$it active", 14.sp, 600, pal.bg) }
                            }
                        }
                    }
                    if (items.isNotEmpty()) T(
                        "${items.size} ${if (items.size == 1) "file" else "files"}  ·  ${fmtBytes(totalBytes)}",
                        14.sp, 500, pal.fg.copy(alpha = 0.55f), Modifier.enterUp(1, 60)
                    )
                }
            }
            if (groups.size > 1) item(key = "filters") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (listOf("All") + listOf("Videos", "Images", "Audio", "Docs", "Apps", "Other").filter { it in groups }).forEach { g ->
                        val on = filter == g || (g == "All" && filter !in groups)
                        Box(
                            Modifier.squish(0.94f) { filter = g }.height(38.dp).clip(RoundedCornerShape(19.dp))
                                .background(if (on) pal.fg else pal.soft).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) { T(g, 14.sp, 600, if (on) pal.bg else pal.fg) }
                    }
                }
            }
            if (items.isEmpty()) item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 90.dp).enterUp(1, 60),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                        DownArrow(pal.bg)
                    }
                    Spacer(Modifier.height(18.dp))
                    T("No downloads yet", 17.sp, 600)
                    Spacer(Modifier.height(6.dp))
                    T("Files you save from pages show up here.", 14.sp, 500, pal.fg.copy(alpha = 0.55f))
                }
            }
            // 0.7.0: pictures and videos as a gallery
            val media = items.filter { it.ok && kindOf(it.name, it.mime).let { k -> k == FileKind.Image || k == FileKind.Video } }
            val galleryMode = filter == "Images" || filter == "Videos"
            if (!galleryMode && (filter == "All" || filter !in groups) && media.size >= 2) item(key = "strip") {
                Column(Modifier.animateItem()) {
                    T("Photos & videos", 13.sp, 600, pal.fg.copy(alpha = 0.55f), Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(media.take(20), key = { it.id }) { d ->
                            MediaTile(st, d, Modifier.size(width = 112.dp, height = 140.dp))
                        }
                    }
                }
            }
            if (galleryMode) {
                val cols = if (filter == "Images") 3 else 2
                media.filter { groupOf(it.name, it.mime) == filter }.chunked(cols).forEachIndexed { r, row ->
                    item(key = "g_${filter}_$r") {
                        Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { d ->
                                MediaTile(st, d, Modifier.weight(1f).aspectRatio(if (cols == 3) 0.8f else 1.25f))
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            var lastDay = ""
            val heads = HashSet<String>()
            (if (galleryMode) shown.filter { !it.ok } else shown).forEach { d ->
                val day = if (d.running) "Now" else dayLabel(d.time)
                if (day != lastDay && heads.add(day)) {
                    lastDay = day
                    item(key = "day_$day") { T(day, 13.sp, 600, pal.fg.copy(alpha = 0.55f), Modifier.padding(start = 6.dp, top = 8.dp).animateItem()) }
                }
                item(key = d.id) {
                    DownloadRow(
                        st, d, if (d.speed >= 0) d.speed else speeds[d.id] ?: 0.0,
                        Modifier.animateItem(fadeInSpec = tween(200), placementSpec = tween(240, easing = EaseOut), fadeOutSpec = tween(160)),
                        onOpen = {
                            when {
                                d.ok -> st.host.openDownload(d.id)
                                d.failed -> st.host.retryDownload(d)
                                d.own && d.paused -> st.host.resumeDownload(d.id)
                            }
                        },
                        onMore = {
                            // 1.0.2: Browser's own downloads pause and continue; long-press for Cancel
                            if (d.own && d.running) {
                                if (d.paused) st.host.resumeDownload(d.id) else st.host.pauseDownload(d.id)
                            } else if (d.running) {
                                st.host.cancelDownload(d.id)
                                st.store.forgetDownloads(listOf(d.id))
                                items = items.filter { it.id != d.id }
                            } else st.card = Card.FileMenu(d)
                        }
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val finished = items.filter { !it.running }
            if (finished.isNotEmpty()) Box(
                Modifier.squish { st.store.forgetDownloads(finished.map { it.id }); items = items.filter { it.running } }
                    .height(58.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) { T("Clear", 15.sp, 600) }
            Box(
                Modifier.squish { st.host.pickAnyFile() }
                    .height(58.dp).clip(RoundedCornerShape(29.dp)).background(pal.soft).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) { T("Open file", 15.sp, 600) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.squish(0.92f) { st.back() }.size(58.dp).clip(CircleShape).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { Glyph(G.Close, pal.bg, 22.dp, 2.4.dp) }
        }
    }
}

@Composable
private fun DownloadRow(st: BrowserState, d: Dl, speed: Double, modifier: Modifier, onOpen: () -> Unit, onMore: () -> Unit) {
    val pal = LocalPal.current
    val frac = if (d.ok) 1f else if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else 0f
    val shownF = animateFloatAsState(frac, tween(380, easing = EaseOut), label = "dl") // read in layout only (1.0.0)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thumb = DownloadThumb(st, d, with(density) { 46.dp.roundToPx() } * 2)
    Column(
        modifier.squish(0.97f, onLongPress = { if (!d.running || d.own) st.card = Card.FileMenu(d) }, onClick = onOpen).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(pal.soft)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (d.failed) pal.faint else pal.fg),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    if (kindOf(d.name, d.mime) == FileKind.Video) PlayBadge(18.dp)
                } else T(d.ext, 11.sp, 700, if (d.failed) pal.fg else pal.bg)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(d.name, 15.sp, 600)
                Spacer(Modifier.height(3.dp))
                T(statusLine(d, speed), 12.sp, 500, pal.fg.copy(alpha = 0.55f))
            }
            Box(Modifier.squish(0.9f, onClick = onMore).size(44.dp), contentAlignment = Alignment.Center) {
                when {
                    d.own && d.paused -> PlayPause(play = true, pal.fg)
                    d.own && d.running -> PlayPause(play = false, pal.fg)
                    d.running -> Glyph(G.Close, pal.fg, 14.dp, 2.dp)
                    else -> Glyph(G.Dots, pal.fg, 18.dp)
                }
            }
        }
        if (d.running) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.padding(end = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(pal.faint)
            ) {
                Box(Modifier.fillWidthOf { shownF.value.coerceAtLeast(0.02f) }.height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (d.paused) pal.fg.copy(alpha = 0.4f) else pal.fg))
            }
        }
    }
}

/** What you can do with one downloaded file. */
@Composable
fun FileMenuCard(st: BrowserState, d: Dl) {
    val pal = LocalPal.current
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        T(d.name, 17.sp, 700)
        Spacer(Modifier.height(3.dp))
        T(statusLine(d, 0.0), 13.sp, 500, pal.fg.copy(alpha = 0.55f))
    }
    fun done() { st.card = null }
    if (d.own && d.running) {
        // 1.0.2: a download on its way: pause, continue or cancel
        CardRow(if (d.paused) "Continue" else "Pause", true) { done(); if (d.paused) st.host.resumeDownload(d.id) else st.host.pauseDownload(d.id) }
        if (d.src.isNotBlank()) CardRow("Copy link") { done(); st.host.copy(d.src); st.notify("Link copied") }
        CardRow("Cancel download") { done(); st.host.deleteDownload(d.id); st.notify("Cancelled ${d.name}") }
        return
    }
    if (d.ok) {
        CardRow("Open", true) { done(); st.host.openDownload(d.id) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardRow("Open with…", modifier = Modifier.weight(1f)) { done(); st.host.downloadFile(d.id)?.let { st.host.openWith(it) } }
            CardRow("Share", modifier = Modifier.weight(1f)) { done(); st.host.downloadFile(d.id)?.let { st.host.shareFile(it) } }
        }
    } else if (d.failed) CardRow("Try again", true) { done(); st.host.retryDownload(d) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.src.isNotBlank()) CardRow("Copy link", modifier = Modifier.weight(1f)) { done(); st.host.copy(d.src); st.notify("Link copied") }
        CardRow("Remove from list", modifier = Modifier.weight(1f)) { done(); st.store.forgetDownloads(listOf(d.id)) }
    }
    CardRow("Delete file") { done(); st.host.deleteDownload(d.id); st.notify("Deleted ${d.name}") }
}

@Composable
fun DownArrow(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val s = 2.4.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.14f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.72f), s, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.26f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.74f), s, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.74f, w * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.74f), s, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.2f, w * 0.9f), androidx.compose.ui.geometry.Offset(w * 0.8f, w * 0.9f), s, cap)
    }
}

// =====================================================================
// NOTICE: one short message in a solid pill, gone after a few seconds
// =====================================================================
@Composable
fun NoticePill(st: BrowserState, modifier: Modifier) {
    val pal = LocalPal.current
    val n = st.notice
    LaunchedEffect(n?.id) {
        if (n != null) { delay(3600); if (st.notice?.id == n.id) st.notice = null }
    }
    var last by remember { mutableStateOf<Notice?>(null) }
    if (n != null) last = n
    AnimatedVisibility(
        n != null && st.screen != Screen.Downloads,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = EaseOut)) { it / 3 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 }
    ) {
        val shown = last ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(pal.fg)
                .padding(start = 20.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T(shown.text, 15.sp, 600, pal.bg, Modifier.weight(1f))
            if (shown.action != null) Box(
                Modifier.squish(0.94f) { st.notice = null; shown.onAction?.invoke() }
                    .height(40.dp).clip(RoundedCornerShape(20.dp)).background(pal.bg).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { T(shown.action, 14.sp, 700) }
        }
    }
}

// =====================================================================
// LINK MENU: long-press a link or an image on a page
// =====================================================================
@Composable
fun LinkMenu(st: BrowserState) {
    val pal = LocalPal.current
    val target = st.link
    var last by remember { mutableStateOf<LinkTarget?>(null) }
    if (target != null) last = target
    val scrim by animateFloatAsState(if (target != null) 1f else 0f, tween(220, easing = EaseOut), label = "scrim")
    val scrimOn by remember { androidx.compose.runtime.derivedStateOf { scrim > 0.001f } } // 1.0.0: recompose only when it appears/disappears
    if (scrimOn) Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(pal.bg.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { st.link = null } }
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            target != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(280, easing = EaseOut)) { it / 4 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 4 }
        ) {
            val t = last ?: return@AnimatedVisibility
            val url = t.url
            val img = t.image
            fun done() { st.link = null }
            Column(
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(pal.bg)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                    T(hostOf(url ?: img ?: "").ifBlank { "Link" }, 17.sp, 700)
                    Spacer(Modifier.height(3.dp))
                    T((url ?: img ?: "").removePrefix("https://").removePrefix("http://"), 13.sp, 500, pal.fg.copy(alpha = 0.55f))
                }
                if (url != null) {
                    MenuRow("Open in new tab", true) { done(); st.openLinkInNewTab(url, false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuRow("In background", modifier = Modifier.weight(1f)) { done(); st.openInBackground(url, st.active?.private == true) }
                        MenuRow("Private tab", modifier = Modifier.weight(1f)) { done(); st.openLinkInNewTab(url, true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuRow("Copy link", modifier = Modifier.weight(1f)) { done(); st.host.copy(url); st.notify("Link copied") }
                        MenuRow("Share", modifier = Modifier.weight(1f)) { done(); st.host.share(url, "") }
                    }
                }
                if (img != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuRow("Open image", modifier = Modifier.weight(1f)) { done(); st.openLinkInNewTab(img, st.active?.private == true) }
                        MenuRow("Save image", modifier = Modifier.weight(1f)) {
                            done()
                            st.host.download(img, st.active?.view?.settings?.userAgentString ?: "", "", "")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, primary: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        modifier.squish(0.97f, onClick = onClick).height(56.dp).clip(RoundedCornerShape(20.dp))
            .background(if (primary) pal.fg else pal.soft).padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) { T(text, 16.sp, 600, if (primary) pal.bg else pal.fg) }
}

/** A picture or video in the Downloads gallery: tap to open in Browser, hold for the file menu. */
@Composable
private fun MediaTile(st: BrowserState, d: Dl, modifier: Modifier) {
    val pal = LocalPal.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thumb = DownloadThumb(st, d, with(density) { 220.dp.roundToPx() })
    val video = kindOf(d.name, d.mime) == FileKind.Video
    val a by animateFloatAsState(if (thumb != null) 1f else 0f, tween(220), label = "tile")
    Box(
        modifier.squish(0.96f, onLongPress = { st.card = Card.FileMenu(d) }) { st.host.openDownload(d.id) }
            .clip(RoundedCornerShape(18.dp)).background(pal.soft),
        contentAlignment = Alignment.Center
    ) {
        if (thumb == null) T(d.ext, 12.sp, 700, pal.fg.copy(alpha = 0.5f))
        else androidx.compose.foundation.Image(
            thumb, d.name, Modifier.fillMaxSize().graphicsLayer { alpha = a },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        if (video) {
            PlayBadge(34.dp)
            mediaDur[d.id]?.let { ms ->
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp)
                ) { T(fmtDur(ms), 11.sp, 600, androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

/** White play circle over a video picture. */
@Composable
private fun PlayBadge(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(size * 0.42f)) {
            val w = this.size.width
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.2f, 0f); lineTo(w, w * 0.5f); lineTo(w * 0.2f, w); close() }
            drawPath(p, androidx.compose.ui.graphics.Color.White)
        }
    }
}

/** 1.0.2: pause (two bars) and continue (triangle) for a download row. */
@Composable
private fun PlayPause(play: Boolean, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        if (play) {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.22f, w * 0.12f); lineTo(w * 0.9f, w * 0.5f); lineTo(w * 0.22f, w * 0.88f); close() }
            drawPath(p, color)
        } else {
            val r = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.18f, w * 0.12f), androidx.compose.ui.geometry.Size(w * 0.22f, w * 0.76f), r)
            drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.6f, w * 0.12f), androidx.compose.ui.geometry.Size(w * 0.22f, w * 0.76f), r)
        }
    }
}
