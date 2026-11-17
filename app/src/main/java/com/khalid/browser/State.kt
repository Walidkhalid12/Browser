package com.khalid.browser

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class Screen { Home, Search, Tabs, More, Lists, Downloads, Reader }
enum class ListMode { Bookmarks, History, Recent }

val isRobolectric: Boolean get() = Build.FINGERPRINT == "robolectric"

data class Account(val email: String, val name: String)
data class Entry(val url: String, val title: String, val time: Long)

/** One download. status uses the DownloadManager.STATUS_* numbers (1.0.2: Browser's own engine uses the same ones).
 *  speed = bytes per second from the engine (-1 = unknown), note = a short line like "started over", resumable = can pause and continue. */
data class Dl(val id: Long, val name: String, val status: Int, val done: Long, val total: Long, val mime: String, val src: String = "", val time: Long = 0L, val reason: Int = 0,
              val speed: Double = -1.0, val note: String = "", val resumable: Boolean = true, val own: Boolean = false) {
    val running get() = status == 1 || status == 2 || status == 4
    val paused get() = status == 4
    val ok get() = status == 8
    val failed get() = status == 16
    val ext get() = name.substringAfterLast('.', "").take(4).uppercase().ifBlank { "FILE" }
}

/** A short message in a pill above the bottom bar, with one optional action. */
class Notice(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) {
    val id = System.nanoTime()
}

/** What was long-pressed on a page: a link, an image, or both. */
data class LinkTarget(val url: String?, val image: String?, val preview: Boolean = true)

/** A closed tab that can come back (undo / recently closed). */
class ClosedTab(val url: String, val title: String, val private: Boolean, val index: Int, val state: android.os.Bundle?, val group: String?, val wasActive: Boolean)

/** Small bottom cards (same look as the link menu). */
sealed class Card {
    object SiteInfo : Card()
    class Qr(val url: String, val title: String) : Card()
    class History(val tabId: Int) : Card()
    object TabMenu : Card()
    class Group(val tabId: Int) : Card()
    object Widgets : Card()
    class FileMenu(val d: Dl) : Card()
    /** 1.0.1: a newer Browser is on GitHub. */
    object Update : Card()
    /** 1.0.1: first launch only, offer to become the default browser. */
    object DefaultBrowser : Card()
}

/** A file shown inside the browser (0.5.0 viewer). */
class FileItem(val uri: Uri, val name: String, val mime: String, val downloadId: Long = -1L)

/** A site asks for location, camera or microphone. */
class PermAsk(val host: String, val what: List<String>, val done: (Boolean) -> Unit)

/** What the UI needs from the Activity. */
interface BrowserHost {
    fun pickFiles(callback: android.webkit.ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun openExternal(url: String): Boolean
    fun download(url: String, userAgent: String, contentDisposition: String, mime: String)
    fun share(url: String, title: String)
    fun openDownloads()
    fun setFullscreen(on: Boolean)
    fun barIcons(lightStatus: Boolean, lightNav: Boolean)
    /** Read a row of on-screen pixels (window coordinates) asynchronously; dominant colour or null. */
    fun pixelRow(x: Int, y: Int, w: Int, done: (Int?) -> Unit)
    fun snapshot(x: Int, y: Int, w: Int, h: Int, scale: Float, done: (android.graphics.Bitmap?) -> Unit)
    /** Try to sign the web engine into Google with the device account; returns a one-time URL or null. */
    suspend fun googleWebLogin(email: String): String?
    fun queryDownloads(ids: List<Long>): List<Dl>
    fun cancelDownload(id: Long)
    fun openDownload(id: Long)
    fun copy(text: String)
    suspend fun signIn(): Result<Account>
    /** Google accounts this app can already see (the ones picked before). */
    fun knownAccounts(): List<Account>
    // ---- 1.1.0 ----
    fun haptic()
    fun requestPerms(perms: Array<String>, done: (Boolean) -> Unit)
    fun voiceSearch()
    fun systemVoice() {}
    fun frost(done: (android.graphics.Bitmap?) -> Unit) { done(null) }
    fun printPage(view: WebView, title: String)
    fun addToHome(url: String, title: String, icon: android.graphics.Bitmap?)
    fun unlockPrivate(done: (Boolean) -> Unit)
    fun mediaChanged()
    fun videoShape(w: Int, h: Int)
    // ---- 0.5.0: files ----
    /** Content uri of a finished download, readable by this app. */
    fun downloadFile(id: Long): FileItem? = null
    /** Show a file with another app on the phone. */
    fun openWith(f: FileItem) {}
    fun installApk(f: FileItem) {}
    fun shareFile(f: FileItem) {}
    /** Delete the file and forget the download. */
    fun deleteDownload(id: Long) {}
    fun retryDownload(d: Dl) {}
    /** 1.0.2: pause and continue Browser's own downloads. */
    fun pauseDownload(id: Long) {}
    fun resumeDownload(id: Long) {}
    /** Pick any file on the phone and show it in the browser. */
    fun pickAnyFile() {}
    // ---- 1.0.1 ----
    /** Look for a newer release on GitHub. manual = the person tapped "Check for updates". */
    fun checkUpdates(manual: Boolean) {}
    /** 1.0.3: remember that the home banner was closed for this version. */
    fun updateBannerClosed(version: String) {}
    /** Download the update APK in the app, check it, then hand it to Android's installer. */
    fun installUpdate() {}
    /** Ask Android to make Browser the default browser. */
    fun makeDefault() {}
    /** Leave Browser and go back to the app that opened the link. */
    fun backToCaller() {}
}

/** One browser tab. Its WebView is created the first time the tab is shown. */
/** 1.0.3: placeholder address of a pop-up tab until its page starts loading. */
const val POPUP_URL = "about:blank"

class Tab(val id: Int, url: String, title: String, val private: Boolean, val uid: String = java.util.UUID.randomUUID().toString()) {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    /** 1.0.1: the tab this one was opened from ("Open in new tab"); Back with no history returns there. */
    var openerId: Int = -1
    /** 1.0.1: opened by a link from another app; Back with no history returns to that app. */
    var fromApp: Boolean = false
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var view: WebView? = null
    /** URL to load once the WebView exists. */
    var pending: String? = url.ifBlank { null }
    val blank: Boolean get() = url.isBlank()
    val host: String get() = hostOf(url)
    /** The words searched, when this tab shows a Google results page. */
    val query: String? get() = googleQuery(url)
    /** Pull-to-refresh wrapper around the WebView. */
    var refresher: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    /** Saved WebView state when the view was released to save memory. */
    var saved: android.os.Bundle? = null
    var lastUsed: Long = System.currentTimeMillis()
    var desktop by mutableStateOf(false)
    /** Bumped when the WebView must be rebuilt (renderer crash). */
    var gen by mutableIntStateOf(0)
    /** The page's own colours, read from its top and bottom edges (null until known). */
    var topColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    var bottomColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)
    /** Tab group name, or null. */
    var group by mutableStateOf<String?>(null)
    /** Requests blocked on the current page (ads, trackers). */
    var blocked by mutableIntStateOf(0)
    val blockedRaw = java.util.concurrent.atomic.AtomicInteger(0)
    /** Host of the main page, readable from the network thread. */
    @Volatile var pageHost: String = hostOf(url)
    @Volatile var shields: Boolean = true
    /** 1.0.4: blocking is on for this tab's site and in Settings. */
    val blocking: Boolean get() = shields && AdBlock.enabled
    /** 1.0.4: a pop-up tab that hasn't shown its first page yet (an ad there closes the tab). */
    @Volatile var popupFresh = false
    /** 1.0.4: an ad page the person chose to open anyway. */
    @Volatile var allowOnce: String? = null
    /** 1.0.4: counts one blocked thing on this page and in the total. */
    fun countBlocked() {
        val n = blockedRaw.incrementAndGet()
        WidgetStats.blocked.incrementAndGet()
        if (n == 1 || n % 4 == 0) Bg.main.post { blocked = blockedRaw.get() }
    }
    var favicon by mutableStateOf<android.graphics.Bitmap?>(null)
    /** A media element is playing (reported by the page). */
    var mediaPlaying by mutableStateOf(false)
    /** Current text size, percent (per-site memory). */
    var zoom by mutableIntStateOf(100)
    /** The certificate had an error the user saw. */
    var sslError by mutableStateOf(false)
    /** Scroll position to put back after a restart when no saved WebView state was available. */
    var restoreScroll: Int = 0
    /** Origins a private tab visited, cleared when it closes. */
    val privateOrigins = HashSet<String>()
    /** Short label for the bar: searched words, else the page title, else the host. */
    val label: String get() = when {
        blank -> "New tab"
        query != null -> query!!
        title.isNotBlank() && title != "New tab" && !title.startsWith("http") -> title
        else -> host.ifBlank { url }
    }
}

fun googleQuery(u: String): String? = runCatching {
    val p = Uri.parse(u)
    if (p.host?.contains("google.") == true && p.path?.startsWith("/search") == true) p.getQueryParameter("q") else null
}.getOrNull()?.takeIf { it.isNotBlank() }

fun hostOf(u: String): String =
    runCatching { Uri.parse(u).host?.removePrefix("www.") }.getOrNull().orEmpty()

private val schemeRx = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val hostRx = Regex("^(localhost|[\\w-]+(\\.[\\w-]+)+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")

/** Turn what was typed into a URL: a web address as is, anything else a Google search. */
fun toUrl(input: String): String {
    val s = input.trim()
    Shortcuts.url(s)?.let { return it }
    if (s.startsWith("http://", true) || s.startsWith("https://", true) ||
        s.startsWith("about:", true) || s.startsWith("file:", true) || s.startsWith("data:", true)) return s
    if (!s.contains(' ') && hostRx.matches(s)) return "https://$s"
    if (!s.contains(' ') && schemeRx.containsMatchIn(s) &&
        Regex("^(mailto|tel|sms|geo|intent|market):", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
    return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
}

fun looksLikeUrl(s: String): Boolean {
    val t = s.trim()
    if (t.length > 300 || t.contains(' ') || t.contains('\n')) return false
    return t.startsWith("http://", true) || t.startsWith("https://", true) || hostRx.matches(t)
}

/** Local history and bookmarks, kept in SharedPreferences as JSON. */
class Store(ctx: Context) {
    val app: Context = ctx.applicationContext ?: ctx
    private val prefs = ctx.getSharedPreferences("browser_data", Context.MODE_PRIVATE)
    val history = mutableStateListOf<Entry>()
    val bookmarks = mutableStateListOf<Entry>()
    /** IDs of files this browser downloaded, newest first. */
    val downloads = mutableStateListOf<Long>()

    init {
        history.addAll(read("history")); bookmarks.addAll(read("bookmarks"))
        runCatching {
            val a = JSONArray(prefs.getString("downloads", "[]"))
            for (i in 0 until a.length()) downloads.add(a.getLong(i))
        }
    }

    private fun writeDownloads() {
        val a = JSONArray(); downloads.forEach { a.put(it) }
        prefs.edit().putString("downloads", a.toString()).apply()
    }
    fun addDownload(id: Long) {
        downloads.remove(id); downloads.add(0, id)
        while (downloads.size > 100) downloads.removeAt(downloads.lastIndex)
        writeDownloads()
    }
    fun flag(key: String) = prefs.getBoolean("flag_$key", false)
    fun setFlag(key: String) { prefs.edit().putBoolean("flag_$key", true).apply() }
    fun forgetDownloads(ids: Collection<Long>) { ids.forEach { DlEngine.forget(it) }; downloads.removeAll(ids.toSet()); writeDownloads() }
    fun str(key: String): String? = prefs.getString("s_$key", null)
    fun setStr(key: String, v: String) { prefs.edit().putString("s_$key", v).apply() }
    /** Widgets inside the home dot, in order (0.5.0). */
    val widgets = mutableStateListOf<String>().apply {
        addAll((prefs.getString("s_dot_widgets", null) ?: "time,weather,date").split(',').filter { it.isNotBlank() })
    }
    fun saveWidgets() { setStr("dot_widgets", widgets.joinToString(",")) }

    private fun read(key: String): List<Entry> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); Entry(o.getString("u"), o.optString("t"), o.optLong("d"))
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun visit(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val i = history.indexOfFirst { it.url == url }
        if (i == 0) { history[0] = Entry(url, title.ifBlank { history[0].title }, System.currentTimeMillis()) }
        else {
            if (i > 0) history.removeAt(i)
            history.add(0, Entry(url, title, System.currentTimeMillis()))
            while (history.size > 400) history.removeAt(history.lastIndex)
        }
        write("history", history)
    }
    fun removeHistory(e: Entry) { history.remove(e); write("history", history) }
    fun clearHistory() { history.clear(); write("history", history) }

    fun isBookmarked(url: String) = url.isNotBlank() && bookmarks.any { it.url == url }
    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        val i = bookmarks.indexOfFirst { it.url == url }
        if (i >= 0) bookmarks.removeAt(i) else bookmarks.add(0, Entry(url, title, System.currentTimeMillis()))
        write("bookmarks", bookmarks)
    }
    fun removeBookmark(e: Entry) { bookmarks.remove(e); write("bookmarks", bookmarks) }

    fun saveTabs(tabs: List<Tab>, active: Int) {
        val a = JSONArray()
        tabs.filter { !it.private }.forEach {
            a.put(JSONObject().put("u", it.url).put("t", it.title).put("id", it.uid).put("g", it.group ?: "")
                .put("y", it.view?.scrollY ?: it.restoreScroll))
        }
        prefs.edit().putString("tabs", a.toString())
            .putInt("active", tabs.filter { !it.private }.indexOfFirst { it.id == active }).apply()
    }
    data class SavedTab(val url: String, val title: String, val uid: String, val group: String?, val scroll: Int)
    fun loadTabs(): Pair<List<SavedTab>, Int> = runCatching {
        val a = JSONArray(prefs.getString("tabs", "[]"))
        (0 until a.length()).map {
            a.getJSONObject(it).let { o ->
                SavedTab(o.getString("u"), o.optString("t"), o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    o.optString("g").ifBlank { null }, o.optInt("y"))
            }
        } to prefs.getInt("active", -1)
    }.getOrDefault(emptyList<SavedTab>() to -1)

    /** Recently closed tabs, newest first (at most 20). */
    val recent = mutableStateListOf<Entry>().also { it.addAll(read("recent")) }
    fun addRecent(url: String, title: String) {
        if (url.isBlank()) return
        recent.removeAll { it.url == url }
        recent.add(0, Entry(url, title, System.currentTimeMillis()))
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        write("recent", recent)
    }
    fun removeRecent(e: Entry) { recent.remove(e); write("recent", recent) }
    fun dropRecent(url: String) { if (recent.removeAll { it.url == url }) write("recent", recent) }
    fun clearRecent() { recent.clear(); write("recent", recent) }

    /** Tab group names (kept even when empty). */
    val groups = mutableStateListOf<String>().also { l ->
        runCatching { val a = JSONArray(prefs.getString("groups", "[]")); for (i in 0 until a.length()) l.add(a.getString(i)) }
    }
    fun saveGroups() { val a = JSONArray(); groups.forEach { a.put(it) }; prefs.edit().putString("groups", a.toString()).apply() }

    fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun setBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }
}

class BrowserState(initialDark: Boolean, val store: Store, val host: BrowserHost, account: Account?) {
    var screen by mutableStateOf(Screen.Home)
    var listMode by mutableStateOf(ListMode.Bookmarks)
    var dark by mutableStateOf(initialDark)
    var privateMode by mutableStateOf(false)
    var activeId by mutableIntStateOf(-1)
    var account by mutableStateOf(account)
    /** Full-screen video view from a page, if any. */
    var fullView by mutableStateOf<View?>(null)
    var fullCallback: WebChromeClient.CustomViewCallback? = null
    /** Where the Downloads screen returns to. */
    var downloadsBack by mutableStateOf(Screen.Home)
    var notice by mutableStateOf<Notice?>(null)
    var link by mutableStateOf<LinkTarget?>(null)
    /** 0 = full bar, 1 = small domain pill. Follows the finger while scrolling (read only in draw/layout). */
    var barK by androidx.compose.runtime.mutableFloatStateOf(0f)
    private var barAnim: android.animation.ValueAnimator? = null
    var barCollapsed: Boolean
        get() = barK > 0.5f
        set(v) { settleBar(if (v) 1f else 0f) }
    /** Move the bar with the page: dy in px, span = px of scroll for a full collapse. */
    fun dragBar(dy: Float, span: Float) {
        barAnim?.cancel(); barAnim = null
        barK = (barK + dy / span).coerceIn(0f, 1f)
    }
    fun settleBar(target: Float? = null) {
        val to = target ?: if (barK > 0.5f) 1f else 0f
        if (barK == to && barAnim == null) return
        barAnim?.cancel()
        if (isRobolectric) { barK = to; return }
        barAnim = android.animation.ValueAnimator.ofFloat(barK, to).apply {
            duration = (220 * kotlin.math.abs(barK - to)).toLong().coerceAtLeast(90)
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { barK = it.animatedValue as Float }
            start()
        }
    }
    /** Find in page. */
    var finding by mutableStateOf(false)
    var findCount by mutableIntStateOf(0)
    var findAt by mutableIntStateOf(0)
    /** Page text size, percent. */
    var textZoom by mutableIntStateOf(100)
    /** True once the web engine is signed in to Google. */
    var webSignedIn by mutableStateOf(false)
    /** Ask the gate to run the Google web sign-in again (from More). */
    var webSignInRequested by mutableStateOf(false)
    fun applyTextZoom(z: Int) { textZoom = z; tabs.forEach { it.view?.settings?.textZoom = z } }
    /** Release WebViews of tabs not used lately (keeps at most [keep] alive). */
    fun trimViews(keep: Int = 3) {
        active?.lastUsed = System.currentTimeMillis()
        val live = tabs.filter { it.view != null }.sortedByDescending { if (it.id == activeId) Long.MAX_VALUE else it.lastUsed }
        live.drop(keep).forEach { t ->
            val v = t.view ?: return@forEach
            runCatching { val b = android.os.Bundle(); v.saveState(b); t.saved = b }
            (t.refresher?.parent as? android.view.ViewGroup)?.removeView(t.refresher)
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            v.stopLoading(); v.destroy()
            t.view = null; t.refresher = null
            if (t.saved == null && !t.blank) t.pending = t.url
        }
    }
    /** Most visited sites, for the new tab page. */
    fun topSites(n: Int = 4): List<Entry> = store.history.groupBy { hostOf(it.url) }
        .filterKeys { it.isNotBlank() }
        .entries.sortedByDescending { it.value.size }.take(n)
        .map { e -> e.value.first().let { Entry("https://" + (Uri.parse(it.url).host ?: e.key) + "/", e.key, it.time) } }
    /** Bumped on every tab switch made by swiping the bar (shows the dots for a moment). */
    var swipes by mutableIntStateOf(0)

    /** Switch to the tab beside the current one. dir = +1 next, -1 previous. Past the last tab opens a new one. */
    fun switchBy(dir: Int): Boolean {
        val i = tabs.indexOfFirst { it.id == activeId }
        if (i < 0) return false
        val j = i + dir
        val target = tabs.getOrNull(j)
        when {
            target != null -> activeId = target.id
            else -> return false
        }
        barCollapsed = false
        swipes++
        saveTabs()
        return true
    }
    fun notify(text: String, action: String? = null, onAction: (() -> Unit)? = null) { notice = Notice(text, action, onAction) }
    fun openDownloads(from: Screen) { downloadsBack = from; screen = Screen.Downloads }
    fun back() {
        screen = when (screen) {
            Screen.Lists -> Screen.More
            Screen.Downloads -> downloadsBack
            else -> Screen.Home
        }
    }

    val origins = mutableStateMapOf<Screen, Rect>()
    var themeOrigin: Offset? = null
    var themeRequests by mutableIntStateOf(0)
        private set
    fun requestTheme() { themeRequests++ }

    private var nextId = 1
    val tabs = mutableStateListOf<Tab>()
    val active: Tab? get() = tabs.firstOrNull { it.id == activeId }

    init {
        val (saved, act) = store.loadTabs()
        saved.forEach { s ->
            tabs.add(Tab(nextId++, s.url, s.title.ifBlank { if (s.url.isBlank()) "New tab" else hostOf(s.url) }, false, s.uid).also {
                it.group = s.group; it.restoreScroll = s.scroll
            })
        }
        if (tabs.isEmpty()) tabs.add(Tab(nextId++, "", "New tab", false))
        activeId = tabs.getOrNull(act)?.id ?: tabs.last().id
    }

    fun newTab(url: String = "", private: Boolean = privateMode): Tab {
        val t = Tab(nextId++, url, if (url.isBlank()) "New tab" else hostOf(url), private)
        t.group = groupFilter
        tabs.add(t); activeId = t.id; saveTabs()
        return t
    }

    /** Take a tab out, keeping what is needed to bring it back. */
    private fun detach(t: Tab): ClosedTab {
        applySavedState()
        val i = tabs.indexOf(t)
        var state: android.os.Bundle? = t.saved
        t.view?.let { v ->
            if (!t.private) runCatching { val b = android.os.Bundle(); if (v.saveState(b) != null) state = b }
            (t.refresher?.parent as? android.view.ViewGroup)?.removeView(t.refresher)
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            if (t.private) runCatching { v.clearCache(true); v.clearHistory(); v.clearFormData() }
            v.stopLoading(); v.destroy()
        }
        if (t.private) clearPrivateData(t)
        t.view = null; t.refresher = null
        tabs.remove(t)
        if (!t.private && !t.blank && !t.url.startsWith("about:")) store.addRecent(t.url, t.title)
        return ClosedTab(t.url, t.title, t.private, i, if (t.private) null else state, t.group, t.id == activeId)
    }

    /** Private tabs: forget the site storage of origins only they visited (cookies are shared, see HANDOFF). */
    private fun clearPrivateData(t: Tab) {
        if (isRobolectric) return
        val normal = tabs.filter { !it.private }.mapNotNull { originOf(it.url) }.toSet()
        val known = store.history.mapNotNull { originOf(it.url) }.toHashSet()
        t.privateOrigins.filter { it !in normal && it !in known }.forEach { o ->
            runCatching { android.webkit.WebStorage.getInstance().deleteOrigin(o) }
            runCatching { android.webkit.GeolocationPermissions.getInstance().clear(o) }
        }
    }

    private fun restore(c: ClosedTab): Tab {
        val t = Tab(nextId++, c.url, c.title, c.private)
        t.group = c.group
        if (c.state != null) { t.saved = c.state; t.pending = null }
        tabs.add(c.index.coerceIn(0, tabs.size), t)
        if (!c.private) store.dropRecent(c.url)
        return t
    }

    fun close(id: Int, undo: Boolean = true) {
        val t = tabs.firstOrNull { it.id == id } ?: return
        val i = tabs.indexOf(t)
        val wasActive = activeId == id
        val c = detach(t)
        if (wasActive) activeId = (tabs.getOrNull(i) ?: tabs.lastOrNull())?.id ?: -1
        saveTabs()
        if (undo && !c.url.isBlank()) notify("Tab closed", "Undo") {
            val r = restore(c)
            if (c.wasActive || tabs.size == 1) activeId = r.id
            saveTabs()
        }
    }

    /** Close every tab (or every tab in the group being shown), with undo. */
    fun closeAll() {
        val list = tabs.filter { groupFilter == null || it.group == groupFilter }
        if (list.isEmpty()) return
        val act = activeId
        val closed = list.map { detach(it) }
        if (tabs.none { it.id == act }) activeId = tabs.lastOrNull()?.id ?: -1
        saveTabs()
        val n = closed.size
        notify(if (n == 1) "1 tab closed" else "$n tabs closed", "Undo") {
            var back: Tab? = null
            closed.forEach { c -> val r = restore(c); if (c.wasActive) back = r }
            back?.let { activeId = it.id }
            if (activeId == -1) activeId = tabs.lastOrNull()?.id ?: -1
            saveTabs()
        }
    }

    /** Open a link in a new tab behind the current one. */
    fun openInBackground(url: String, private: Boolean) {
        val cur = active
        val t = Tab(nextId++, toUrl(url), hostOf(url), private)
        t.group = cur?.group
        val i = tabs.indexOfFirst { it.id == activeId }
        tabs.add(if (i < 0) tabs.size else i + 1, t)
        saveTabs()
        notify("Opened in background", "Switch") { activeId = t.id; screen = Screen.Home; saveTabs() }
    }

    fun reopen(e: Entry) {
        store.removeRecent(e)
        newTab(e.url, private = false); screen = Screen.Home
    }

    // ---- 1.1.0 state ----
    val site = SitePrefs(store.app)
    var card by mutableStateOf<Card?>(null)
    // 0.9.0
    var quick by mutableStateOf(false)
    var ai by mutableStateOf<AiChat?>(null)
    var voice by mutableStateOf(false)
    var aiMode by mutableStateOf(store.bool("ai_mode", false))
    fun setAi(on: Boolean) { aiMode = on; store.setBool("ai_mode", on) }
    var article by mutableStateOf<Article?>(null)
    var perm by mutableStateOf<PermAsk?>(null)
    /** Tab group shown in the Tabs grid (null = all). */
    var groupFilter by mutableStateOf<String?>(null)
    /** Edge swipe: signed drag in px (>0 from the left edge = back), and the finger's y. */
    var edgeX by androidx.compose.runtime.mutableFloatStateOf(0f)
    var edgeY by androidx.compose.runtime.mutableFloatStateOf(0f)
    var forceDark by mutableStateOf(store.bool("force_dark", false))
    var lockPrivate by mutableStateOf(store.bool("lock_private", true))
    var privateLocked by mutableStateOf(false)
    /** Picture-in-picture: only the page (or full-screen video) is drawn. */
    var pip by mutableStateOf(false)
    fun changeLockPrivate(on: Boolean) { lockPrivate = on; store.setBool("lock_private", on); if (!on) privateLocked = false }

    // ---- 1.0.4: ad blocker ----
    var adOn by mutableStateOf(AdBlock.enabled)
    var adUpdated by mutableStateOf(AdBlock.updatedAt)
    var adUpdating by mutableStateOf(AdBlock.updating)
    init {
        AdBlock.onChange = { Bg.main.post { adUpdated = AdBlock.updatedAt; adUpdating = AdBlock.updating } }
    }
    fun setAdBlock(on: Boolean) {
        AdBlock.setEnabled(store.app, on); adOn = on
        tabs.firstOrNull { it.id == activeId }?.view?.reload()
    }
    fun updateFilters() {
        if (adUpdating) return
        adUpdating = true
        Thread {
            val ok = runCatching { AdBlock.update(store.app) }.getOrDefault(false)
            Bg.main.post {
                adUpdating = AdBlock.updating; adUpdated = AdBlock.updatedAt
                notify(if (ok) "Filter lists updated" else "Couldn't update the filter lists. Check your connection")
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun setForceDarkPages(on: Boolean) { forceDark = on; store.setBool("force_dark", on); rebuildViews() }

    /** Rebuild every live WebView (keeps history via saved state). */
    fun rebuildViews() {
        tabs.forEach { t ->
            val v = t.view ?: return@forEach
            runCatching { val b = android.os.Bundle(); v.saveState(b); t.saved = b }
            (t.refresher?.parent as? android.view.ViewGroup)?.removeView(t.refresher)
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            v.stopLoading(); v.destroy()
            t.view = null; t.refresher = null
            if (t.saved == null && !t.blank) t.pending = t.url
            t.gen++
        }
    }

    /** Text size for the current site, remembered by host. */
    fun cycleZoom(t: Tab) {
        val steps = listOf(85, 100, 115, 130, 150)
        val z = steps[(steps.indexOf(t.zoom) + 1).mod(steps.size)]
        t.zoom = z; t.view?.settings?.textZoom = z
        site.setZoom(t.host, z)
    }

    fun setShields(t: Tab, on: Boolean) {
        site.setShields(t.host, on); t.shields = on
        t.view?.reload()
    }

    fun addGroup(name: String, tab: Tab?) {
        val n = name.trim().take(24)
        if (n.isBlank()) return
        if (n !in store.groups) { store.groups.add(n); store.saveGroups() }
        tab?.group = n
        saveTabs()
    }
    fun deleteGroup(name: String) {
        store.groups.remove(name); store.saveGroups()
        tabs.filter { it.group == name }.forEach { it.group = null }
        if (groupFilter == name) groupFilter = null
        saveTabs()
    }

    /** Persist every normal tab's WebView state to disk (restored after the app is killed). */
    fun persistState(dir: java.io.File) {
        applySavedState()
        // 1.0.0: WebView state is read and turned into bytes here (main thread, fast); the files are
        // written on the background thread, each to a temp file renamed into place (never half-written).
        val out = ArrayList<Pair<String, ByteArray>>()
        tabs.filter { !it.private && !it.blank }.forEach { t ->
            val b = t.view?.let { v -> runCatching { android.os.Bundle().also { if (v.saveState(it) == null) throw IllegalStateException() } }.getOrNull() } ?: t.saved
            val bytes = b?.toBytes() ?: return@forEach
            if (bytes.size > 2_000_000) return@forEach
            out.add(t.uid to bytes)
        }
        Bg.run {
            val d = java.io.File(dir, "tabstate").apply { mkdirs() }
            val keep = HashSet<String>()
            out.forEach { (uid, bytes) ->
                runCatching {
                    val tmp = java.io.File(d, "$uid.tmp")
                    tmp.writeBytes(bytes)
                    val f = java.io.File(d, uid)
                    if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) error("rename") }
                    keep.add(uid)
                }
            }
            d.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        }
        saveTabs()
    }

    /** Read saved WebView states back into the restored tabs (before any WebView exists). */
    fun loadState(dir: java.io.File) {
        val m = readStates(dir, tabs.filter { it.view == null && !it.blank }.map { it.uid })
        tabs.forEach { t ->
            if (t.view != null || t.blank) return@forEach
            m[t.uid]?.let { t.saved = it; t.pending = null }
        }
    }

    private fun readStates(dir: java.io.File, uids: List<String>): Map<String, android.os.Bundle> {
        val d = java.io.File(dir, "tabstate")
        if (!d.isDirectory) return emptyMap()
        val m = HashMap<String, android.os.Bundle>()
        uids.forEach { uid ->
            val f = java.io.File(d, uid)
            if (f.isFile) runCatching { bundleOf(f.readBytes())?.let { m[uid] = it } }
        }
        return m
    }

    // ---- 1.0.0: tab state files are read off the main thread at launch ----
    private var stateLoad: java.util.concurrent.Future<Map<String, android.os.Bundle>>? = null

    /**
     * Same as [loadState] + clearing the scroll of restored tabs, but the files are read on the
     * background thread. The result is put in place as soon as it is ready, and at the latest the
     * moment anything needs it (a WebView being created, a tab closed, state saved).
     */
    fun loadStateAsync(dir: java.io.File) {
        val uids = tabs.filter { it.view == null && !it.blank }.map { it.uid }
        if (uids.isEmpty()) return
        val f = java.util.concurrent.FutureTask { readStates(dir, uids) }
        stateLoad = f
        runCatching { Bg.io.execute { f.run(); Bg.main.post { applySavedState() } } }.onFailure { f.run() }
    }

    /** Puts the states read by [loadStateAsync] into their tabs (waits for the read if still running). */
    fun applySavedState() {
        val f = stateLoad ?: return
        stateLoad = null
        val m = runCatching { f.get() }.getOrDefault(emptyMap())
        var any = false
        tabs.forEach { t ->
            if (t.view != null || t.blank || t.saved != null) return@forEach
            m[t.uid]?.let { t.saved = it; t.pending = null; t.restoreScroll = 0; any = true }
        }
        if (any) saveTabs()
    }

    /** Load a search or address in the current tab (or a new one). */
    fun open(input: String) {
        if (input.isBlank()) return
        val t0 = input.trim()
        // AI mode: anything that isn't an address (or an @shortcut) is asked to Google AI Mode
        if (aiMode && !looksLikeUrl(t0) && !t0.startsWith("@")) { askAi(t0); return }
        val url = toUrl(input)
        if (active?.id == homeTabId) { backTo = null; homeTabId = -1 }
        val t = active ?: newTab()
        t.url = url
        t.progress = 5
        val v = t.view
        if (v != null) v.loadUrl(url) else t.pending = url
        screen = Screen.Home
        saveTabs()
    }

    fun openInNewTab(input: String) { newTab(); open(input) }

    /** 1.0.3: a tab for a pop-up or a target=_blank link. The page fills it, so it starts with no address to load. */
    fun openPopupTab(from: Tab): Tab {
        val t = newTab(POPUP_URL, from.private)
        t.pending = null
        t.title = "Loading\u2026"
        t.openerId = from.id
        t.progress = 5
        t.popupFresh = true
        screen = Screen.Home
        return t
    }

    /** 1.0.3: the page closed its pop-up; the tab that opened it comes back. */
    fun closePopupTab(t: Tab) {
        val opener = tabs.firstOrNull { it.id == t.openerId }
        val wasActive = t.id == activeId
        close(t.id, undo = false)
        if (wasActive && opener != null) activeId = opener.id
        saveTabs()
    }

    /** Open a link in a background-free new tab and show it. */
    fun openLinkInNewTab(url: String, private: Boolean) {
        val from = activeId
        newTab(toUrl(url), private).openerId = from
        screen = Screen.Home
    }

    fun saveTabs() = store.saveTabs(tabs, activeId)

    // ---- 0.5.0: open on the home page, with a way back to the last tab ----
    /** The tab that was in front when the app was left; shown as "Back to previous tab" on home. */
    var backTo by mutableStateOf<Int?>(null)
    private var homeTabId = -1
    val backTab: Tab? get() = backTo?.let { id -> tabs.firstOrNull { it.id == id } }?.takeIf { it.id != activeId && !it.blank }

    /** Show the home page in front. The page that was open stays as a tab. */
    /**
     * 1.0.1: Back while a page is in front, like Chrome. Exactly one step back in this tab's history.
     * Only when there is none left: a tab opened from another tab closes and that tab comes back, a tab
     * opened from another app closes and that app comes back, any other page goes to the home screen.
     * Returns false only when there is no page in front (then Back leaves the app as usual).
     */
    fun pageBack(): Boolean {
        val t = active ?: return false
        if (t.blank) return false
        val v = t.view
        // Read the history live (a remembered flag can be one page behind after a script navigation).
        if (v != null && v.canGoBack()) { v.goBack(); return true }
        val opener = tabs.firstOrNull { it.id == t.openerId }
        when {
            opener != null -> { close(t.id, undo = false); activeId = opener.id; screen = Screen.Home; saveTabs() }
            t.fromApp -> {
                close(t.id, undo = false)
                val blank = tabs.lastOrNull { it.blank && !it.private }
                if (blank != null) activeId = blank.id else newTab(private = false)
                screen = Screen.Home; saveTabs()
                host.backToCaller()
            }
            else -> leaveToHome()
        }
        return true
    }

    // ---- 1.0.1: updates and default browser ----
    var update by mutableStateOf<UpdateInfo?>(null)
    /** Download progress 0..1 while the update downloads, -1 when idle. */
    var updateProgress by mutableFloatStateOf(-1f)
    /** Short status under "Check for updates" (e.g. "Checking…", "You're up to date"). */
    var updateStatus by mutableStateOf<String?>(null)
    /** 1.0.5: "Check for updates" was tapped and GitHub hasn't answered yet. */
    var updateChecking by mutableStateOf(false)
    var isDefault by mutableStateOf(false)
    var introDone by mutableStateOf(false)
    /** 1.0.3: the version whose home banner was closed (it comes back for the next version). */
    var updateBannerClosed by mutableStateOf<String?>(null)
    fun closeUpdateBanner() {
        val v = update?.version ?: return
        updateBannerClosed = v
        host.updateBannerClosed(v)
        notify("You can update any time in More")
    }

    fun leaveToHome() {
        val a = active ?: return
        if (a.blank) return
        val blank = tabs.lastOrNull { it.blank && !it.private }
        val prev = a.id
        if (blank != null) activeId = blank.id else newTab(private = false)
        homeTabId = activeId
        backTo = prev
        screen = Screen.Home
        finding = false
        saveTabs()
    }

    fun goBackToPrevious() {
        val t = backTab ?: run { backTo = null; return }
        val home = tabs.firstOrNull { it.id == homeTabId }
        activeId = t.id
        backTo = null
        // the empty tab made only for the home page goes away again
        if (home != null && home.blank && tabs.size > 1) close(home.id, undo = false)
        homeTabId = -1
        screen = Screen.Home
        saveTabs()
    }

    /** File open in the in-app viewer. */
    var viewing by mutableStateOf<FileItem?>(null)
}

/** Google search suggestions. Empty on any failure. */
suspend fun suggest(q: String): List<String> = withContext(Dispatchers.IO) {
    if (isRobolectric) return@withContext emptyList()
    runCatching {
        val c = URL(
            "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&q=" +
                URLEncoder.encode(q, "UTF-8")
        ).openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500
        try {
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val arr = JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally { c.disconnect() }
    }.getOrDefault(emptyList())
}
