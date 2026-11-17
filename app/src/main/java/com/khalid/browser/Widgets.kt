package com.khalid.browser

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// =====================================================================
// 0.8.0 HOME-SCREEN WIDGETS: search bar, quick actions, top sites,
// ads blocked, and the dot. Black and white, follow the phone's dark mode.
// =====================================================================

const val ACT_SEARCH = "com.khalid.browser.action.SEARCH"
const val ACT_VOICE = "com.khalid.browser.action.VOICE"
const val ACT_PRIVATE = "com.khalid.browser.action.PRIVATE"
const val ACT_DOWNLOADS = "com.khalid.browser.action.DOWNLOADS"

/** Ads blocked since the last save; added to the stored total when the app goes to the background. */
object WidgetStats {
    val blocked = AtomicLong(0)
    private fun prefs(c: Context) = c.getSharedPreferences("browser_data", Context.MODE_PRIVATE)
    fun total(c: Context): Long = prefs(c).getLong("blocked_total", 0L) + blocked.get()
    fun save(c: Context) {
        val n = blocked.getAndSet(0)
        if (n > 0) prefs(c).edit().putLong("blocked_total", prefs(c).getLong("blocked_total", 0L) + n).apply()
    }
}

object Widgets {
    private fun pi(c: Context, code: Int, i: Intent): PendingIntent =
        PendingIntent.getActivity(c, code, i.setClass(c, MainActivity::class.java)
            // 1.0.1: bring the running Browser forward and deliver the tap to it (onNewIntent) instead of
            // rebuilding the screen; no launch animation from the widget, the app's own sheet opens at once.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(c: Context, a: String, code: Int) = pi(c, code, Intent(a))

    /** Redraw the widgets that show data (top sites, ads blocked). Called when the app goes to the background. */
    fun refresh(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        runCatching {
            m.getAppWidgetIds(ComponentName(c, SitesWidget::class.java)).takeIf { it.isNotEmpty() }?.let { ids -> ids.forEach { m.updateAppWidget(it, sites(c)) } }
            m.getAppWidgetIds(ComponentName(c, StatsWidget::class.java)).takeIf { it.isNotEmpty() }?.let { ids -> ids.forEach { m.updateAppWidget(it, stats(c)) } }
        }
    }

    fun search(c: Context) = RemoteViews(c.packageName, R.layout.widget_search).apply {
        setOnClickPendingIntent(R.id.w_root, action(c, ACT_SEARCH, 10))
        setOnClickPendingIntent(R.id.w_mic, action(c, ACT_VOICE, 11))
    }

    fun quick(c: Context) = RemoteViews(c.packageName, R.layout.widget_quick).apply {
        setOnClickPendingIntent(R.id.w_a0, action(c, ACT_SEARCH, 20))
        setOnClickPendingIntent(R.id.w_a1, action(c, ACT_VOICE, 21))
        setOnClickPendingIntent(R.id.w_a2, action(c, ACT_PRIVATE, 22))
        setOnClickPendingIntent(R.id.w_a3, action(c, ACT_DOWNLOADS, 23))
    }

    fun dot(c: Context) = RemoteViews(c.packageName, R.layout.widget_dot).apply {
        setOnClickPendingIntent(R.id.w_root, action(c, ACT_SEARCH, 30))
    }

    /** Most visited sites from history (same rule as the ring on the home page). */
    fun topSites(c: Context, n: Int): List<Pair<String, String>> = runCatching {
        val a = JSONArray(c.getSharedPreferences("browser_data", Context.MODE_PRIVATE).getString("history", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).getString("u") }
            .mapNotNull { u -> Uri.parse(u).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n)
            .map { it.key to "https://${it.key}/" }
    }.getOrDefault(emptyList())

    private val slots = listOf(
        Triple(R.id.w_s0, R.id.w_l0, R.id.w_n0), Triple(R.id.w_s1, R.id.w_l1, R.id.w_n1),
        Triple(R.id.w_s2, R.id.w_l2, R.id.w_n2), Triple(R.id.w_s3, R.id.w_l3, R.id.w_n3),
        Triple(R.id.w_s4, R.id.w_l4, R.id.w_n4), Triple(R.id.w_s5, R.id.w_l5, R.id.w_n5),
        Triple(R.id.w_s6, R.id.w_l6, R.id.w_n6), Triple(R.id.w_s7, R.id.w_l7, R.id.w_n7)
    )

    fun sites(c: Context) = RemoteViews(c.packageName, R.layout.widget_sites).apply {
        val list = topSites(c, 8)
        setViewVisibility(R.id.w_empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.w_grid, if (list.isEmpty()) View.GONE else View.VISIBLE)
        if (list.isEmpty()) setOnClickPendingIntent(R.id.w_root, action(c, ACT_SEARCH, 40))
        slots.forEachIndexed { i, (box, letter, name) ->
            val s = list.getOrNull(i)
            if (s == null) { setViewVisibility(box, View.INVISIBLE); return@forEachIndexed }
            setViewVisibility(box, View.VISIBLE)
            val short = s.first.substringBeforeLast('.').substringAfterLast('.').ifBlank { s.first }
            setTextViewText(letter, short.take(1).uppercase(Locale.US))
            setTextViewText(name, short.replaceFirstChar { it.titlecase(Locale.US) })
            setOnClickPendingIntent(box, pi(c, 100 + i, Intent(Intent.ACTION_VIEW, Uri.parse(s.second))))
        }
    }

    fun stats(c: Context) = RemoteViews(c.packageName, R.layout.widget_stats).apply {
        val n = WidgetStats.total(c)
        setTextViewText(R.id.w_count, when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
            else -> String.format(Locale.US, "%,d", n)
        })
        setOnClickPendingIntent(R.id.w_root, action(c, ACT_SEARCH, 50))
    }
}

private fun AppWidgetManager.put(ids: IntArray, v: RemoteViews) = ids.forEach { updateAppWidget(it, v) }

class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.put(ids, Widgets.search(c))
}
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.put(ids, Widgets.quick(c))
}
class SitesWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.put(ids, Widgets.sites(c))
}
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.put(ids, Widgets.stats(c))
}
class DotWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = m.put(ids, Widgets.dot(c))
}
