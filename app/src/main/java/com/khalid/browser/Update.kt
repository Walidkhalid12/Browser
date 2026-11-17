package com.khalid.browser

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 1.0.1: a release on GitHub, read from the Releases API. */
class UpdateInfo(
    val version: String,
    /** 1.0.3: the release title on GitHub (e.g. "Bug fixes"); empty when it only repeats the version. */
    val name: String = "",
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    /** Hex SHA-256 of the APK when GitHub lists it ("digest": "sha256:..."), else null. */
    val sha256: String?,
    val page: String,
)

object Updater {
    const val REPO = "Walidkhalid12/Browser"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    /** "1.0.10" > "1.0.9", "v1.1" > "1.0.9", "1.0.1" == "1.0.1". Anything after '-' or '+' is ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Markdown release notes shown as plain lines. */
    fun plainNotes(md: String): String = md.lineSequence()
        .map { it.trim().trimStart('#', '>', ' ').replace("**", "").replace("`", "").replace(Regex("^[-*] "), "• ") }
        .filter { it.isNotBlank() && !it.startsWith("SHA-256", true) && !it.startsWith("sha256", true) }
        .take(8).joinToString("\n")

    /** 1.0.3: the release title, unless it only repeats the version ("v1.0.3", "Browser 1.0.3"). */
    fun releaseName(name: String, tag: String): String {
        val n = name.trim()
        val v = tag.trim().removePrefix("v").removePrefix("V")
        if (n.isEmpty()) return ""
        val bare = n.removePrefix("Browser").trim().removePrefix("v").removePrefix("V").trim()
        return if (bare == v || n == tag) "" else n.take(60)
    }

    /** 1.0.3: notes worth a separate box (not just the release title again). */
    fun extraNotes(u: UpdateInfo): String =
        if (u.notes.trim().removePrefix("\u2022 ").trim().equals(u.name, ignoreCase = true)) "" else u.notes

    /** The home banner shows for a newer version unless it was closed for that version. 1.0.5: closing only lasts
     * until the app is opened again (it isn't saved), so the announcement always comes back. */
    fun showBanner(found: UpdateInfo?, current: String, closedFor: String?): Boolean =
        found != null && compare(found.version, current) > 0 && closedFor != found.version

    /** 1.0.3: one system notification per version. */
    fun shouldNotify(found: String, current: String, notifiedFor: String?): Boolean =
        compare(found, current) > 0 && notifiedFor != found

    // 1.0.3: the last release found is kept, so the banner is there at launch and after the background check.
    const val PREFS = "browser"
    const val K_JSON = "update_json"
    const val K_CLOSED = "update_banner_closed"
    const val K_NOTIFIED = "update_notified"

    fun saveFound(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_JSON, json).apply()
    }
    /** The release found earlier, if it is still newer than this app. */
    fun cached(ctx: Context, current: String): UpdateInfo? {
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_JSON, null) ?: return null
        val u = runCatching { parse(json) }.getOrNull() ?: return null
        return u.takeIf { compare(it.version, current) > 0 }
    }

    /** Parse the API answer. Null when there is no APK attached. */
    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", true)) continue
            val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            return UpdateInfo(
                version = o.optString("tag_name").removePrefix("v"),
                name = releaseName(o.optString("name"), o.optString("tag_name")),
                notes = plainNotes(o.optString("body")),
                apkUrl = a.optString("browser_download_url"),
                apkName = name,
                apkSize = a.optLong("size", -1L),
                sha256 = digest,
                page = o.optString("html_url"),
            )
        }
        return null
    }

    sealed class Check {
        class Newer(val info: UpdateInfo, val json: String = "") : Check()
        object UpToDate : Check()
        class Failed(val why: String) : Check()
    }

    // 1.0.5: the web page's "latest" redirect, used when the API is rate-limited or down (it has no hourly limit).
    private const val WEB = "https://github.com/$REPO"

    /** 1.0.5: the API answer couldn't be used; [why] is the reason shown to the person if the fallback fails too. */
    private class ApiDown(val why: String) : Exception(why)

    /**
     * Network call; run off the main thread. 1.0.5: asks the GitHub API first; when the API says no (rate limit,
     * server error, no answer), it reads the release from github.com/…/releases/latest instead. [api] and [web] are
     * only changed by tests.
     */
    fun check(current: String, api: String = API, web: String = WEB): Check {
        val apiWhy = try {
            return fromApi(current, api)
        } catch (e: ApiDown) { e.why }
        return try { fromWeb(current, web) } catch (e: Exception) { Check.Failed(apiWhy) }
    }

    private fun open(url: String, current: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000
            useCaches = false
            setRequestProperty("User-Agent", "Browser/$current (Android; +https://github.com/$REPO)")
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun fromApi(current: String, api: String): Check {
        val c = try { open(api, current) } catch (e: Exception) { throw ApiDown(reason(e)) }
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = try { c.responseCode } catch (e: Exception) { throw ApiDown(reason(e)) }
            when (code) {
                200 -> {
                    val json = try { c.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { throw ApiDown(reason(e)) }
                    val info = try { parse(json) } catch (e: Exception) { throw ApiDown("GitHub sent an answer Browser couldn't read") }
                        ?: return Check.UpToDate
                    return if (compare(info.version, current) > 0) Check.Newer(info, json) else Check.UpToDate
                }
                404 -> return Check.UpToDate
                403, 429 -> {
                    val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val mins = reset?.let { ((it * 1000 - System.currentTimeMillis()) / 60000 + 1).coerceIn(1, 60) }
                    throw ApiDown(if (mins != null) "GitHub's limit was reached. Try again in $mins min" else "GitHub's limit was reached. Try again later")
                }
                else -> throw ApiDown("GitHub didn't answer (error $code). Try again later")
            }
        } finally { c.disconnect() }
    }

    /** 1.0.5: github.com/…/releases/latest redirects to …/releases/tag/vX.Y.Z; the APK has a fixed name. */
    private fun fromWeb(current: String, web: String): Check {
        val c = open("$web/releases/latest", current)
        try {
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code !in 300..399) throw IllegalStateException("no redirect ($code)")
            val loc = c.getHeaderField("Location") ?: throw IllegalStateException("no location")
            val tag = loc.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#')
            if (tag.isEmpty()) return Check.UpToDate
            val version = tag.removePrefix("v").removePrefix("V")
            if (compare(version, current) <= 0) return Check.UpToDate
            val apk = "Browser-$version.apk"
            val json = JSONObject()
                .put("tag_name", tag).put("name", "").put("body", "")
                .put("html_url", "$web/releases/tag/$tag")
                .put("assets", org.json.JSONArray().put(JSONObject()
                    .put("name", apk).put("size", -1)
                    .put("browser_download_url", "$web/releases/download/$tag/$apk")))
                .toString()
            val info = parse(json) ?: return Check.UpToDate
            return Check.Newer(info, json)
        } finally { c.disconnect() }
    }

    /** 1.0.5: say why the check failed, instead of "No connection" for everything. */
    fun reason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "GitHub took too long to answer. Try again"
        is java.net.ConnectException -> "Couldn't connect to GitHub. Check your internet"
        is javax.net.ssl.SSLException -> "Secure connection to GitHub failed. Check the phone's date and time"
        else -> "Couldn't reach GitHub. Check your internet"
    }

    // ---- 1.0.5: when to check ----
    /** Automatic checks (app opened or brought back) at most this often. Only successful checks count. */
    const val AUTO_EVERY_MS = 30L * 60 * 1000
    const val K_CHECKED = "update_ok_at"

    /** An automatic check is due when the last successful one is old enough (or the clock went back). */
    fun due(lastOk: Long, now: Long, every: Long = AUTO_EVERY_MS): Boolean = lastOk <= 0L || now < lastOk || now - lastOk >= every

    /** Record a check that reached GitHub: when, and the newer release (or none). */
    fun saveResult(ctx: Context, r: Check, now: Long = System.currentTimeMillis()) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        when (r) {
            is Check.Newer -> p.putLong(K_CHECKED, now).putString(K_JSON, r.json)
            is Check.UpToDate -> p.putLong(K_CHECKED, now).remove(K_JSON)
            is Check.Failed -> {}
        }
        p.apply()
    }

    /** Download the APK to the app's cache with progress (0..1). Runs off the main thread. Returns the file or throws. */
    fun download(ctx: Context, u: UpdateInfo, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("update-") }?.forEach { it.delete() }
        val out = File(dir, "update-${u.version.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk")
        var url = URL(u.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            // GitHub sends the file from another host; follow the redirect ourselves (also https -> https only).
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Browser-updater")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                val next = c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect")
                c.disconnect(); url = URL(url, next); hops++
                if (url.protocol != "https") throw IllegalStateException("Not secure")
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed ($code)")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: u.apkSize
        val md = MessageDigest.getInstance("SHA-256")
        try {
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastTick = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val now = System.nanoTime()
                        if (total > 0 && now - lastTick > 50_000_000L) { lastTick = now; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        } finally { c.disconnect() }
        progress(1f)
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (u.apkSize > 0 && out.length() != u.apkSize) { out.delete(); throw IllegalStateException("The download was cut off") }
        if (u.sha256 != null && u.sha256 != hex) { out.delete(); throw IllegalStateException("The file didn't match GitHub's checksum") }
        return out
    }

    /**
     * Check the downloaded APK before the installer sees it: same app id, a newer version, and signed with
     * the same key as the installed Browser (Android would refuse the install otherwise anyway).
     * Returns null when it's fine, else the reason.
     */
    @Suppress("DEPRECATION")
    fun verify(ctx: Context, apk: File): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The file isn't a valid app"
        if (info.packageName != ctx.packageName) return "This file is a different app"
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val myCode = if (Build.VERSION.SDK_INT >= 28) mine.longVersionCode else mine.versionCode.toLong()
        if (newCode <= myCode) return "This version is not newer"
        fun sigs(p: android.content.pm.PackageInfo): Set<String> {
            val list = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.toList() else p.signatures?.toList()
            return list.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val a = sigs(info); val b = sigs(mine)
        if (a.isNotEmpty() && b.isNotEmpty() && a != b) return "The file isn't signed by Browser's developer"
        return null
    }
}

// ---------------- cards ----------------

/**
 * 1.0.3: the update announcement on the home screen. A quiet pill above the bottom bar with the version and
 * the release title; "Update" opens the update card and starts the download, the cross hides it for this version.
 */
@Composable
fun UpdateBanner(st: BrowserState, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    val u = st.update ?: return
    if (!Updater.showBanner(u, APP_VERSION, st.updateBannerClosed)) return
    val busy = st.updateProgress >= 0f
    Row(
        modifier
            .enterUp(0, 80)
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            .fillMaxWidth()
            .squish(0.98f) { st.card = Card.Update }
            .height(64.dp).clip(RoundedCornerShape(32.dp)).background(pal.soft)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(pal.soft))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T("Browser ${u.version} is available", 15.sp, 600)
            Spacer(Modifier.height(2.dp))
            T(if (busy) "Downloading ${(st.updateProgress * 100).toInt()}%" else u.name.ifBlank { "Tap to see what's new" }, 12.sp, 500, pal.fg.copy(alpha = 0.6f))
        }
        Box(
            Modifier.squish(0.94f) { st.card = Card.Update; st.host.installUpdate() }
                .height(40.dp).clip(RoundedCornerShape(20.dp)).background(pal.fg).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) { T("Update", 14.sp, 700, pal.bg) }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.squish(0.9f) { st.closeUpdateBanner() }.size(40.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) { Glyph(G.Close, pal.fg.copy(alpha = 0.55f), 16.dp) }
    }
}

@Composable
fun UpdateCard(st: BrowserState) {
    val pal = LocalPal.current
    val u = st.update ?: return
    val busy = st.updateProgress >= 0f
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(pal.fg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(pal.raised))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T("Browser ${u.version} is here", 17.sp, 700)
                Spacer(Modifier.height(3.dp))
                T((if (u.name.isNotBlank()) "${u.name} · " else "") + "You have $APP_VERSION" + if (u.apkSize > 0) " · ${"%.1f".format(u.apkSize / 1048576f)} MB" else "", 13.sp, 500, pal.fg.copy(alpha = 0.6f))
            }
        }
        val notes = Updater.extraNotes(u)
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.soft).padding(horizontal = 16.dp, vertical = 12.dp)) {
                TM(notes, 14.sp, 500, pal.fg.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (busy) {
            // In-app download progress, same pill shape as the buttons.
            val p = st.updateProgress
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(27.dp)).background(pal.soft)) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(54.dp).clip(RoundedCornerShape(27.dp)).background(pal.fg.copy(alpha = 0.16f)))
                Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                    T(if (p >= 1f) "Checking the file…" else "Downloading ${(p * 100).toInt()}%", 15.sp, 600)
                }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).squish(0.95f) { st.card = null }.height(54.dp).clip(RoundedCornerShape(27.dp)).background(pal.soft),
                contentAlignment = Alignment.Center
            ) { T("Later", 15.sp, 600) }
            Box(
                Modifier.weight(1.4f).squish(0.95f) { st.host.installUpdate() }.height(54.dp).clip(RoundedCornerShape(27.dp)).background(pal.fg),
                contentAlignment = Alignment.Center
            ) { T("Update now", 15.sp, 700, pal.bg) }
        }
        st.updateStatus?.takeIf { !busy }?.let {
            Spacer(Modifier.height(8.dp)); T(it, 12.sp, 500, pal.fg.copy(alpha = 0.6f), Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
fun DefaultBrowserCard(st: BrowserState) {
    val pal = LocalPal.current
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(pal.fg))
        Spacer(Modifier.height(16.dp))
        T("Make Browser your default?", 20.sp, 700, tracking = -0.02f)
        Spacer(Modifier.height(6.dp))
        TM("Links from other apps will open here, with ads blocked and downloads done right.", 14.sp, 500, pal.fg.copy(alpha = 0.6f),
            Modifier.padding(horizontal = 12.dp), center = true)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth().squish(0.96f) { st.card = null; st.host.makeDefault() }.height(56.dp)
                .clip(RoundedCornerShape(28.dp)).background(pal.fg),
            contentAlignment = Alignment.Center
        ) { T("Set as default browser", 16.sp, 700, pal.bg) }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().squish(0.96f) { st.card = null; st.notify("You can do it any time in More") }.height(52.dp)
                .clip(RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) { T("Not now", 15.sp, 600, pal.fg.copy(alpha = 0.7f)) }
    }
}
