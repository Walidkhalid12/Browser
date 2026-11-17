package com.khalid.browser

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 1.0.4: the filter lists the ad blocker uses. They're downloaded from their makers, never bundled. */
object AdLists {
    class FilterList(val name: String, val url: String, val file: String, val privacy: Boolean, val credit: String)

    val ALL = listOf(
        FilterList("EasyList", "https://easylist.to/easylist/easylist.txt", "easylist.txt", false,
            "EasyList authors, GPLv3 / CC BY-SA 3.0"),
        FilterList("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "easyprivacy.txt", true,
            "EasyList authors, GPLv3 / CC BY-SA 3.0"),
        FilterList("AdGuard Mobile Ads", "https://filters.adtidy.org/extension/ublock/filters/11.txt", "adguard_mobile.txt", false,
            "AdGuard, GPLv3"),
        FilterList("Peter Lowe's list", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            "peter_lowe.txt", false, "Peter Lowe, pgl.yoyo.org"),
        FilterList("EasyList Cookie List", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", "easylist_cookie.txt", false,
            "EasyList authors, GPLv3 / CC BY-SA 3.0"),
    )

    /** Drops comments and rule kinds the engine doesn't use, so the saved file is smaller and loads faster. */
    fun slim(text: String): String {
        val sb = StringBuilder(text.length / 2)
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.length < 3 || l[0] == '!' || l[0] == '[') continue
            if (l.contains("#?#") || l.contains("#$#") || l.contains("#%#") || l.contains("#@?#") || l.contains("#@$#") ||
                l.contains("#@%#") || l.contains("##+js(") || l.contains("#@#+js(") || l.contains("##^") || l.contains("\$\$")) continue
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    /** A downloaded file looks like a real filter list (not an error page). */
    fun looksValid(text: String): Boolean {
        if (text.length < 2000) return false
        val head = text.take(400).trimStart()
        if (head.startsWith("<")) return false
        return text.lineSequence().take(4000).count { it.startsWith("||") || it.contains("##") } > 50
    }
}

/**
 * 1.0.4: the ad blocker. It keeps the compiled engine in memory, loads it in the background after launch,
 * and refreshes the lists about once a week (Wi-Fi only, see FilterJob). Until the first lists arrive
 * the small built-in ad server list (Blocker) does the blocking.
 */
object AdBlock {
    const val PREFS = "adblock"
    private const val K_ON = "on"
    private const val K_UPDATED = "updated_at"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    @Volatile var engine: AdEngine? = null
        private set
    /** Off in Settings: nothing is blocked or hidden anywhere. */
    @Volatile var enabled = true
        private set
    @Volatile var updatedAt = 0L
        private set
    @Volatile var updating = false
        private set
    /** Called (on any thread) when the engine, the date or the updating state changes. */
    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { runCatching { onChange?.invoke() } }

    /** Use this engine (tests). */
    fun use(e: AdEngine?) { engine = e }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "filters").apply { mkdirs() }

    /** Read settings (fast; main thread at launch). */
    fun init(c: Context) {
        val p = prefs(c)
        enabled = p.getBoolean(K_ON, true)
        updatedAt = p.getLong(K_UPDATED, 0L)
    }

    fun setEnabled(c: Context, on: Boolean) { enabled = on; prefs(c).edit().putBoolean(K_ON, on).apply() }


    /** Build the engine from the saved lists; downloads them first when there are none yet. Background thread. */
    fun load(c: Context) {
        val d = dir(c)
        val have = AdLists.ALL.filter { File(d, it.file).length() > 0 }
        if (have.isEmpty()) { if (update(c)) return }
        if (engine == null) build(c)
        FilterJob.schedule(c)
        if (System.currentTimeMillis() - updatedAt > WEEK * 2) update(c) // the weekly job hasn't run for a long time
    }

    private fun build(c: Context): Boolean {
        val d = dir(c)
        val e = AdEngine()
        var n = 0
        for (l in AdLists.ALL) {
            val f = File(d, l.file)
            if (f.length() == 0L) continue
            runCatching { e.addList(f.readText(), l.privacy); n++ }
        }
        if (n == 0) return false
        engine = e.build()
        changed()
        return true
    }

    /** Download every list, keep the old copy of any that fails, then rebuild. Background thread. True when the engine was rebuilt. */
    @Synchronized fun update(c: Context): Boolean {
        updating = true; changed()
        try {
            val d = dir(c)
            var got = 0
            for (l in AdLists.ALL) {
                val text = download(l.url) ?: continue
                if (!AdLists.looksValid(text)) continue
                val tmp = File(d, l.file + ".tmp")
                tmp.writeText(AdLists.slim(text))
                if (tmp.renameTo(File(d, l.file))) got++
            }
            if (got == 0) return false
            updatedAt = System.currentTimeMillis()
            prefs(c).edit().putLong(K_UPDATED, updatedAt).apply()
            return build(c)
        } finally { updating = false; changed() }
    }

    private fun download(url: String): String? = runCatching {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", "Browser/$APP_VERSION (Android)")
        try {
            if (con.responseCode != 200) return null
            con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }.getOrNull()

    // ------------------------------------------------------------------ used by the web view
    /** Should this request from a page on [pageHost] be blocked? Falls back to the built-in host list until lists load. */
    fun blockRequest(url: String, host: String, pageHost: String, accept: String?): Boolean {
        val e = engine
        if (e == null) {
            if (pageHost.isNotEmpty() && (host == pageHost || host.endsWith(".$pageHost"))) return false
            return Blocker.blocked(host)
        }
        return e.match(url, pageHost, RT.guess(url, accept, false)) != null
    }

    /** A page or pop-up going to an ad or scam address. */
    fun adNavigation(url: String, pageHost: String): Boolean {
        if (!url.startsWith("http")) return false
        val e = engine ?: return Blocker.blocked(hostOfUrl(url))
        return e.isAdNavigation(url, pageHost)
    }

    fun pageCss(host: String): String = engine?.pageCss(host).orEmpty()
    fun genericCss(host: String, names: String): String = engine?.genericCss(host, names).orEmpty()

    private fun hostOfUrl(u: String) = u.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('?').lowercase()
}

/** 1.0.4: refreshes the filter lists about once a week, on Wi-Fi (unmetered) and when the battery isn't low. */
class FilterJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread = Thread {
            val ok = runCatching { AdBlock.update(app) }.getOrDefault(false)
            jobFinished(params, !ok && AdBlock.updatedAt == 0L)
        }.apply { priority = Thread.MIN_PRIORITY; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { thread?.interrupt(); return true }

    companion object {
        private const val JOB_ID = 7301
        fun schedule(c: Context) {
            runCatching {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(c, FilterJob::class.java))
                        .setPeriodic(7L * 24 * 60 * 60 * 1000, 24L * 60 * 60 * 1000)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .setPersisted(true)
                        .build()
                )
            }
        }
    }
}
