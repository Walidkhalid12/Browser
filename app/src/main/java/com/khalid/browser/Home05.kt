package com.khalid.browser

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =====================================================================
// 0.5.0 HOME: widgets inside the dot, sites in a ring around it,
// and a "Back to previous tab" bar above the search bar.
// =====================================================================

/** Every widget the dot can hold, in the order the picker lists them. */
val AllWidgets = listOf(
    "time" to "Time",
    "date" to "Date",
    "weather" to "Weather",
    "battery" to "Battery",
    "tabs" to "Open tabs",
    "downloads" to "Downloads",
    "blocked" to "Ads blocked",
)

@Composable
fun Home05(st: BrowserState) {
    val dotSize = if (st.store.widgets.isEmpty()) 104.dp else 176.dp
    // 1.0.0: the animated size is read only in layout (no recomposition of home on every frame).
    val size = animateFloatAsState(dotSize.value, tween(320, easing = EaseOut), label = "dotsize")
    val back = st.backTab
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).offset(y = if (back != null) (-56).dp else (-32).dp)) {
            if (st.store.widgets.isEmpty()) {
                Box(Modifier.align(Alignment.Center)) {
                    LogoDot(Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = { st.card = Card.Widgets })
                    }, 104.dp, active = st.screen == Screen.Home)
                }
            } else WidgetDot(st, { size.value.dp }, Modifier.align(Alignment.Center))
        }
        SiteRow(st, Modifier.align(Alignment.Center).offset {
            androidx.compose.ui.unit.IntOffset(0, ((if (back != null) (-56).dp else (-32).dp) + size.value.dp / 2 + 78.dp).roundToPx())
        })
        AiModeBar(st, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            // 1.0.3: update announcement, above "Back to previous tab"
            UpdateBanner(st)
            if (back != null) BackBar(st, back)
            BottomBar(st, Modifier, compact = false)
        }
    }
    // One-time tip: how to choose widgets.
    LaunchedEffect(Unit) {
        if (!st.store.flag("widget_hint")) {
            delay(1400)
            if (st.screen == Screen.Home && st.active?.blank == true) {
                st.store.setFlag("widget_hint")
                st.notify("Hold the dot to choose widgets", "Choose") { st.card = Card.Widgets }
            }
        }
    }
}

/** The wide bar that takes you back to the page you were on before leaving the app. */
@Composable
private fun BackBar(st: BrowserState, tab: Tab) {
    val pal = LocalPal.current
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .enterUp(0, 80)
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            .fillMaxWidth()
            .graphicsLayer { translationY = drag.value.coerceAtLeast(0f); alpha = 1f - (drag.value / 160f).coerceIn(0f, 1f) }
            .pointerInput(Unit) {
                // pull it down to dismiss
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (drag.value > 60.dp.toPx()) { drag.animateTo(300f, tween(160)); st.backTo = null }
                            else drag.animateTo(0f, tween(200, easing = EaseOut))
                        }
                    }
                ) { ch, dy -> ch.consume(); scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
            }
            .squish(0.98f) { st.goBackToPrevious() }
            .height(64.dp).clip(RoundedCornerShape(32.dp)).background(pal.fg)
            .padding(start = 8.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(pal.bg), contentAlignment = Alignment.Center) {
            T(tab.host.ifBlank { tab.label }.removePrefix("www.").take(1).uppercase(), 18.sp, 700)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T("Back to previous tab", 16.sp, 600, pal.bg)
            Spacer(Modifier.height(2.dp))
            T(tab.label, 12.sp, 500, pal.bg.copy(alpha = 0.6f))
        }
        Box(Modifier.graphicsLayer { rotationZ = 180f }) { Glyph(G.Back, pal.bg, 20.dp) }
    }
}

/** Most visited sites sitting in a ring around the dot. */
@Composable
private fun SiteRing(st: BrowserState, dot: Dp, modifier: Modifier) {
    val pal = LocalPal.current
    val sites = remember(st.store.history.size) { st.topSites(6) }
    if (sites.isEmpty()) return
    val r = dot / 2 + 54.dp
    val box = (r + 40.dp) * 2
    Box(modifier.size(box)) {
        sites.forEachIndexed { i, e ->
            val a = -PI / 2 + 2 * PI * i / sites.size
            val x = (r.value * cos(a)).toFloat().dp
            val y = (r.value * sin(a)).toFloat().dp
            Column(
                Modifier.align(Alignment.Center).offset(x = x, y = y + 8.dp)
                    .enterUp(i, 200).squish(0.9f) { st.open(e.url) }.width(68.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(pal.soft), contentAlignment = Alignment.Center) {
                    T(e.title.removePrefix("www.").take(1).uppercase(), 16.sp, 700)
                }
                Spacer(Modifier.height(5.dp))
                T(e.title.removePrefix("www.").substringBefore('.').take(10), 11.sp, 500, pal.fg.copy(alpha = 0.55f), align = TextAlign.Center)
            }
        }
    }
}

/** The black dot, now holding widgets you swipe between. Hold it to choose them. */
@Composable
private fun WidgetDot(st: BrowserState, size: () -> Dp, modifier: Modifier) {
    val pal = LocalPal.current
    val list = st.store.widgets.toList()
    val pager = rememberPagerState(initialPage = (st.store.str("dot_page")?.toIntOrNull() ?: 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))) { list.size }
    LaunchedEffect(pager.currentPage) { st.store.setStr("dot_page", pager.currentPage.toString()) }
    // 1.0.1: the dot's pop reads the shared opening clock (starts after the first frame), not its own timer.
    val intro = LocalIntro.current
    val shared = remember { intro != null && !intro.done }
    val pop = remember { Animatable(if (shared) 1f else 0.6f) }
    LaunchedEffect(Unit) { if (!shared) pop.animateTo(1f, tween(380, easing = EaseOut)) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.sizeOf(size).graphicsLayer {
            val pv = if (shared && intro != null) 0.6f + 0.4f * EaseOut.transform((intro.t.value / 380f).coerceIn(0f, 1f)) else pop.value
            scaleX = pv; scaleY = pv; alpha = ((pv - 0.6f) / 0.4f).coerceIn(0f, 1f)
        }
            .clip(CircleShape).background(pal.fg)
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list.getOrElse(it) { "x$it" } }) { p ->
            val w = list.getOrNull(p) ?: return@HorizontalPager
            Box(
                Modifier.fillMaxSize().squish(0.95f, onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress); st.card = Card.Widgets
                }) { widgetTap(st, w) },
                contentAlignment = Alignment.Center
            ) { WidgetFace(st, w) }
        }
        if (list.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(list.size) { d ->
                val on = d == pager.currentPage
                val wdt = animateFloatAsState(if (on) 14f else 5f, tween(220, easing = EaseOut), label = "pd")
                Box(Modifier.height(5.dp).widthOf { wdt.value.dp }.clip(RoundedCornerShape(3.dp)).background(pal.bg.copy(alpha = if (on) 1f else 0.35f)))
            }
        }
    }
}

private fun widgetTap(st: BrowserState, w: String) {
    when (w) {
        "tabs" -> st.screen = Screen.Tabs
        "downloads" -> st.openDownloads(Screen.Home)
        "weather" -> st.open("weather")
        else -> {}
    }
}

/** Ticks once a minute, on the minute. */
@Composable
private fun rememberMinute(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            delay(60_000 - t % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun WidgetFace(st: BrowserState, w: String) {
    val pal = LocalPal.current
    val ink = pal.bg
    val dim = pal.bg.copy(alpha = 0.6f)
    val ctx = LocalContext.current
    @Composable
    fun Face(big: String, small: String, top: (@Composable () -> Unit)? = null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-4).dp)) {
            if (top != null) { top(); Spacer(Modifier.height(4.dp)) }
            T(big, if (big.length > 5) 30.sp else 38.sp, 700, ink, align = TextAlign.Center, tracking = -0.04f)
            Spacer(Modifier.height(2.dp))
            T(small, 12.sp, 600, dim, align = TextAlign.Center)
        }
    }
    when (w) {
        "time" -> {
            val now = rememberMinute()
            val h24 = android.text.format.DateFormat.is24HourFormat(ctx)
            val time = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
            val sub = SimpleDateFormat(if (h24) "EEEE" else "a · EEEE", Locale.getDefault()).format(Date(now))
            Face(time, sub)
        }
        "date" -> {
            val now = rememberMinute()
            val c = Calendar.getInstance().apply { timeInMillis = now }
            Face(c.get(Calendar.DAY_OF_MONTH).toString(), SimpleDateFormat("MMMM · EEE", Locale.getDefault()).format(Date(now)))
        }
        "weather" -> {
            var wx by remember { mutableStateOf(Weather.cached(st.store)) }
            LaunchedEffect(Unit) {
                while (true) {
                    if (wx == null || System.currentTimeMillis() - wx!!.at > 30 * 60_000L) Weather.fetch(st.store)?.let { wx = it }
                    delay(10 * 60_000L)
                }
            }
            val x = wx
            if (x == null) Face("--°", "Weather") { WeatherGlyph(WxKind.Cloud, ink, pal.fg, 30.dp) }
            else Face("${x.temp}°", "${x.place.take(14)} · ${x.hi}° ${x.lo}°") { WeatherGlyph(x.kind, ink, pal.fg, 30.dp) }
        }
        "battery" -> {
            var b by remember { mutableStateOf(battery(ctx)) }
            val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            // 1.0.0: no battery reads while the app is in the background; refreshed on return.
            LaunchedEffect(Unit) { while (true) { delay(30_000); life.awaitStarted(); b = battery(ctx) } }
            Face("${b.first}%", if (b.second) "Charging" else "Battery") { BatteryGlyph(b.first, b.second, ink, 34.dp) }
        }
        "tabs" -> Face(st.tabs.count { !it.blank }.toString(), "tabs open")
        "downloads" -> {
            var n by remember { mutableIntStateOf(0) }
            val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(st.store.downloads.size) {
                while (true) {
                    life.awaitStarted() // 1.0.0: no DownloadManager polling while the app is in the background
                    n = withContext(Dispatchers.IO) { runCatching { st.host.queryDownloads(st.store.downloads.toList()).count { it.running } }.getOrDefault(0) }
                    delay(if (n > 0) 1500 else 6000)
                }
            }
            if (n > 0) Face(n.toString(), if (n == 1) "downloading" else "downloading") { DownArrow(ink, 22.dp) }
            else Face(st.store.downloads.size.toString(), "downloads") { DownArrow(ink, 22.dp) }
        }
        "blocked" -> Face(st.tabs.sumOf { it.blocked }.toString(), "ads blocked")
        else -> Face("·", w)
    }
}

private fun battery(ctx: android.content.Context): Pair<Int, Boolean> = runCatching {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
    pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}.getOrDefault(0 to false)

@Composable
private fun BatteryGlyph(pct: Int, charging: Boolean, c: Color, w: Dp) {
    Canvas(Modifier.width(w).height(w * 0.5f)) {
        val bw = size.width * 0.9f; val h = size.height
        drawRoundRect(c.copy(alpha = 0.35f), Offset.Zero, Size(bw, h), CornerRadius(h * 0.28f))
        drawRoundRect(c, Offset.Zero, Size(bw * (pct.coerceIn(4, 100) / 100f), h), CornerRadius(h * 0.28f))
        drawRoundRect(c.copy(alpha = 0.35f), Offset(bw + size.width * 0.03f, h * 0.3f), Size(size.width * 0.07f, h * 0.4f), CornerRadius(h * 0.1f))
    }
}

// ---------------- weather ----------------
enum class WxKind { Sun, Moon, Cloud, Rain, Snow, Storm, Fog }
class Wx(val temp: Int, val hi: Int, val lo: Int, val kind: WxKind, val place: String, val at: Long)

object Weather {
    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "Browser/$APP_VERSION")
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    fun cached(store: Store): Wx? = runCatching {
        val o = JSONObject(store.str("weather") ?: return null)
        Wx(o.getInt("t"), o.getInt("hi"), o.getInt("lo"), WxKind.valueOf(o.getString("k")), o.getString("p"), o.getLong("at"))
    }.getOrNull()

    private fun wmo(code: Int, day: Boolean): WxKind = when (code) {
        0, 1 -> if (day) WxKind.Sun else WxKind.Moon
        2, 3 -> WxKind.Cloud
        45, 48 -> WxKind.Fog
        in 51..67, in 80..82 -> WxKind.Rain
        in 71..77, 85, 86 -> WxKind.Snow
        in 95..99 -> WxKind.Storm
        else -> WxKind.Cloud
    }

    private fun words(desc: String, day: Boolean): WxKind {
        val d = desc.lowercase()
        return when {
            "thunder" in d -> WxKind.Storm
            "snow" in d || "sleet" in d || "ice" in d -> WxKind.Snow
            "rain" in d || "drizzle" in d || "shower" in d -> WxKind.Rain
            "fog" in d || "mist" in d || "haze" in d -> WxKind.Fog
            "sun" in d || "clear" in d -> if (day) WxKind.Sun else WxKind.Moon
            else -> WxKind.Cloud
        }
    }

    /** Where the phone is (by its internet address, no location permission), then the weather there. */
    suspend fun fetch(store: Store): Wx? = withContext(Dispatchers.IO) {
        if (isRobolectric) return@withContext null
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val w = runCatching {
            val loc = JSONObject(get("https://ipwho.is/") ?: get("https://ipapi.co/json/")!!)
            val lat = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
            val city = loc.optString("city", "")
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")!!)
            val cur = j.getJSONObject("current"); val daily = j.getJSONObject("daily")
            Wx(
                Math.round(cur.getDouble("temperature_2m")).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                wmo(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1), city, System.currentTimeMillis()
            )
        }.getOrNull() ?: runCatching {
            // fallback: wttr.in finds the place itself
            val j = JSONObject(get("https://wttr.in/?format=j1")!!)
            val c = j.getJSONArray("current_condition").getJSONObject(0)
            val d = j.getJSONArray("weather").getJSONObject(0)
            val place = j.getJSONArray("nearest_area").getJSONObject(0).getJSONArray("areaName").getJSONObject(0).getString("value")
            Wx(
                c.getString("temp_C").toInt(), d.getString("maxtempC").toInt(), d.getString("mintempC").toInt(),
                words(c.getJSONArray("weatherDesc").getJSONObject(0).getString("value"), hour in 6..18), place, System.currentTimeMillis()
            )
        }.getOrNull()
        if (w != null) store.setStr("weather", JSONObject().put("t", w.temp).put("hi", w.hi).put("lo", w.lo)
            .put("k", w.kind.name).put("p", w.place).put("at", w.at).toString())
        w
    }
}

@Composable
fun WeatherGlyph(kind: WxKind, c: Color, back: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        fun cloud(dy: Float = 0f) {
            drawCircle(c, s * 0.17f, Offset(s * 0.34f, s * 0.52f + dy))
            drawCircle(c, s * 0.22f, Offset(s * 0.55f, s * 0.42f + dy))
            drawRoundRect(c, Offset(s * 0.14f, s * 0.48f + dy), Size(s * 0.72f, s * 0.22f), CornerRadius(s * 0.11f))
        }
        when (kind) {
            WxKind.Sun -> {
                drawCircle(c, s * 0.2f, center)
                repeat(8) { k ->
                    val a = k * PI.toFloat() / 4
                    drawLine(c, center + Offset(cos(a), sin(a)) * (s * 0.31f), center + Offset(cos(a), sin(a)) * (s * 0.44f), s * 0.08f, StrokeCap.Round)
                }
            }
            WxKind.Moon -> { drawCircle(c, s * 0.32f, center); drawCircle(back, s * 0.27f, center + Offset(s * 0.17f, -s * 0.12f)) }
            WxKind.Cloud -> cloud(0.04f * s)
            WxKind.Fog -> repeat(3) { k -> drawRoundRect(c, Offset(s * (0.12f + 0.06f * (k % 2)), s * (0.26f + 0.18f * k)), Size(s * 0.7f, s * 0.1f), CornerRadius(s * 0.05f)) }
            WxKind.Rain -> { cloud(-0.1f * s); repeat(3) { k -> drawLine(c, Offset(s * (0.32f + 0.17f * k), s * 0.72f), Offset(s * (0.28f + 0.17f * k), s * 0.88f), s * 0.07f, StrokeCap.Round) } }
            WxKind.Snow -> { cloud(-0.1f * s); repeat(3) { k -> drawCircle(c, s * 0.045f, Offset(s * (0.3f + 0.2f * k), s * 0.8f)) } }
            WxKind.Storm -> {
                cloud(-0.12f * s)
                val p = Path().apply {
                    moveTo(s * 0.52f, s * 0.6f); lineTo(s * 0.38f, s * 0.8f); lineTo(s * 0.5f, s * 0.8f)
                    lineTo(s * 0.42f, s * 0.98f); lineTo(s * 0.64f, s * 0.74f); lineTo(s * 0.52f, s * 0.74f); lineTo(s * 0.6f, s * 0.6f); close()
                }
                drawPath(p, c)
            }
        }
    }
}

private operator fun Offset.times(f: Float) = Offset(x * f, y * f)

// ---------------- the picker ----------------
@Composable
fun WidgetsCard(st: BrowserState) {
    val pal = LocalPal.current
    val on = st.store.widgets
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        T("Widgets in the dot", 17.sp, 700)
        Spacer(Modifier.height(3.dp))
        T(if (on.isEmpty()) "None, the dot stays plain" else "${on.size} on · swipe inside the dot to switch", 13.sp, 500, pal.fg.copy(alpha = 0.55f))
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(AllWidgets, key = { _, w -> w.first }) { _, (id, name) ->
            val at = on.indexOf(id)
            Row(
                Modifier.squish(0.97f) {
                    if (at >= 0) on.remove(id) else on.add(id)
                    st.store.saveWidgets()
                }.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(20.dp)).background(pal.soft).padding(start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (at >= 0) pal.fg else pal.faint), contentAlignment = Alignment.Center) {
                    T(if (at >= 0) (at + 1).toString() else "", 13.sp, 700, pal.bg)
                }
                Spacer(Modifier.width(12.dp))
                T(name, 16.sp, 600, modifier = Modifier.weight(1f))
                CleanSwitch(at >= 0)
            }
        }
    }
}
