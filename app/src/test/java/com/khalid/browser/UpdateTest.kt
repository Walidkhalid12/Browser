package com.khalid.browser

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** 1.0.5: the in-app update check, its fallback, when it runs, and where the announcement shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UpdateTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var base: String
    /** User-Agent of the last request the fake GitHub saw. */
    @Volatile private var lastUa: String? = null

    private val live: String by lazy { javaClass.classLoader!!.getResource("github_latest_v1.0.4.json")!!.readText() }
    /** The real GitHub answer for v1.0.4, turned into a v1.0.5 release. */
    private val json105: String by lazy { live.replace("1.0.4", "1.0.5").replace("\"Ad blocker\"", "\"Update fixes\"") }

    /** A tiny fake GitHub: one answer per path (code, headers, body). */
    private inner class Fake {
        val routes = HashMap<String, () -> Triple<Int, Map<String, String>, String>>()
        val sock = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        init {
            Thread {
                while (!sock.isClosed) {
                    val c = runCatching { sock.accept() }.getOrNull() ?: break
                    Thread { runCatching { c.use { one(it) } } }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }
        private fun one(c: java.net.Socket) {
            val r = c.getInputStream().bufferedReader()
            val path = (r.readLine() ?: return).split(" ")[1]
            while (true) {
                val l = r.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("User-Agent:", true)) lastUa = l.substringAfter(':').trim()
            }
            val (code, hs, body) = routes[path]?.invoke() ?: Triple(404, emptyMap(), "")
            val b = body.toByteArray()
            val sb = StringBuilder("HTTP/1.1 $code X\r\n")
            hs.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Content-Length: ${b.size}\r\nConnection: close\r\n\r\n")
            c.getOutputStream().apply { write(sb.toString().toByteArray()); write(b); flush() }
        }
        fun stop() = sock.close()
    }
    private lateinit var fake: Fake

    @Before fun start() {
        fake = Fake()
        base = "http://127.0.0.1:${fake.sock.localPort}"
        val r = fake.routes
        r["/ok/latest"] = { Triple(200, mapOf("Content-Type" to "application/json"), json105) }
        r["/moved/latest"] = { Triple(301, mapOf("Location" to "$base/ok/latest"), "") }
        r["/limited/latest"] = { Triple(403, mapOf("X-RateLimit-Remaining" to "0",
            "X-RateLimit-Reset" to ((System.currentTimeMillis() / 1000) + 600).toString()), """{"message":"API rate limit exceeded"}""") }
        r["/down/latest"] = { Triple(503, emptyMap(), "busy") }
        r["/web/releases/latest"] = { Triple(302, mapOf("Location" to "$base/web/releases/tag/v1.0.5"), "") }
        r["/webnone/releases/latest"] = { Triple(302, mapOf("Location" to "$base/webnone/releases"), "") }
        r["/webbroken/releases/latest"] = { Triple(500, emptyMap(), "oops") }
        ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun stop() { fake.stop() }

    // ---------------- reading GitHub's answer ----------------

    @Test fun parsesTheRealGithubAnswer() {
        val u = Updater.parse(live)!!
        assertEquals("1.0.4", u.version)
        assertEquals("Ad blocker", u.name)
        assertEquals("Browser-1.0.4.apk", u.apkName)
        assertEquals(2038362L, u.apkSize)
        assertEquals("ca5f032a8ad1af1832310a49fea9d703e1902cea448c0a54c9f2d408464b9098", u.sha256)
        assertEquals("https://github.com/Walidkhalid12/Browser/releases/download/v1.0.4/Browser-1.0.4.apk", u.apkUrl)
        assertFalse(u.notes.contains("SHA-256"))
    }

    @Test fun versionOrder() {
        assertTrue(Updater.compare("1.0.10", "1.0.9") > 0)
        assertTrue(Updater.compare("v1.0.5", "1.0.4") > 0)
        assertEquals(0, Updater.compare("1.0.5", "v1.0.5"))
        assertTrue(Updater.compare("1.0.4", "1.0.5") < 0)
        assertTrue(Updater.compare("1.1.0", "1.0.99") > 0)
        assertTrue(Updater.compare("2", "1.9.9") > 0)
        // The release title never matters, only the tag
        assertEquals("", Updater.releaseName("Browser 1.0.5", "v1.0.5"))
        assertEquals("Update fixes", Updater.releaseName("Update fixes", "v1.0.5"))
    }

    // ---------------- talking to a fake GitHub ----------------

    @Test fun apiAnswerFindsTheNewVersion() {
        val r = Updater.check("1.0.4", "$base/ok/latest", "$base/webbroken")
        assertTrue(r is Updater.Check.Newer)
        r as Updater.Check.Newer
        assertEquals("1.0.5", r.info.version)
        assertEquals("Update fixes", r.info.name)
        assertTrue(lastUa!!.startsWith("Browser/1.0.4"))
        assertTrue(Updater.check("1.0.5", "$base/ok/latest", "$base/webbroken") is Updater.Check.UpToDate)
    }

    @Test fun apiRedirectIsFollowed() {
        val r = Updater.check("1.0.4", "$base/moved/latest", "$base/webbroken")
        assertEquals("1.0.5", (r as Updater.Check.Newer).info.version)
    }

    @Test fun rateLimitFallsBackToTheReleasesPage() {
        val r = Updater.check("1.0.4", "$base/limited/latest", "$base/web")
        assertTrue(r is Updater.Check.Newer)
        val u = (r as Updater.Check.Newer).info
        assertEquals("1.0.5", u.version)
        assertEquals("Browser-1.0.5.apk", u.apkName)
        assertEquals("$base/web/releases/download/v1.0.5/Browser-1.0.5.apk", u.apkUrl)
        // what the fallback found is saved and read back like an API answer
        assertEquals("1.0.5", Updater.parse(r.json)!!.version)
        assertTrue(Updater.check("1.0.5", "$base/down/latest", "$base/web") is Updater.Check.UpToDate)
        assertTrue(Updater.check("1.0.4", "$base/down/latest", "$base/webnone") is Updater.Check.UpToDate)
    }

    @Test fun bothFailingGivesAClearReason() {
        val r = Updater.check("1.0.4", "$base/limited/latest", "$base/webbroken")
        assertTrue((r as Updater.Check.Failed).why, r.why.startsWith("GitHub's limit was reached. Try again in"))
        val d = Updater.check("1.0.4", "$base/down/latest", "$base/webbroken") as Updater.Check.Failed
        assertEquals("GitHub didn't answer (error 503). Try again later", d.why)
        fake.stop()
        val off = Updater.check("1.0.4", "$base/ok/latest", "$base/web") as Updater.Check.Failed
        assertEquals("Couldn't connect to GitHub. Check your internet", off.why)
        assertEquals("No internet connection", Updater.reason(java.net.UnknownHostException("api.github.com")))
    }

    // ---------------- when it checks ----------------

    @Test fun throttleIsShortAndOnlyCountsSuccess() {
        val now = 10_000_000_000L
        assertTrue(Updater.due(0L, now))
        assertFalse(Updater.due(now - 10 * 60_000L, now))
        assertTrue(Updater.due(now - 30 * 60_000L, now))
        assertTrue(Updater.due(now + 60_000L, now)) // clock went back
        val p = ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE)
        Updater.saveResult(ctx, Updater.Check.Failed("x"), now)
        assertEquals(0L, p.getLong(Updater.K_CHECKED, 0L))
        val newer = Updater.check("1.0.4", "$base/ok/latest", "$base/web") as Updater.Check.Newer
        Updater.saveResult(ctx, newer, now)
        assertEquals(now, p.getLong(Updater.K_CHECKED, 0L))
        assertEquals("1.0.5", Updater.cached(ctx, "1.0.4")!!.version)
        assertNull(Updater.cached(ctx, "1.0.5"))
        Updater.saveResult(ctx, Updater.Check.UpToDate, now + 5)
        assertNull(p.getString(Updater.K_JSON, null))
        assertEquals(now + 5, p.getLong(Updater.K_CHECKED, 0L))
    }

    @Test fun notifiesOncePerVersion() {
        assertTrue(Updater.shouldNotify("1.0.6", "1.0.5", null))
        assertTrue(Updater.shouldNotify("1.0.6", "1.0.5", "1.0.5"))
        assertFalse(Updater.shouldNotify("1.0.6", "1.0.5", "1.0.6"))
        assertFalse(Updater.shouldNotify("1.0.5", "1.0.5", null))
    }

    @Test fun backgroundJobIsSetUpWithoutBatteryLimit() {
        val js = ctx.getSystemService(JobScheduler::class.java)
        // a phone coming from 1.0.3/1.0.4 still has the old job
        js.schedule(JobInfo.Builder(7201, android.content.ComponentName(ctx, UpdateJob::class.java))
            .setPeriodic(12L * 3600_000).setRequiresBatteryNotLow(true).build())
        UpdateJob.schedule(ctx)
        assertNull(js.getPendingJob(7201))
        val j = js.getPendingJob(UpdateJob.JOB_ID)
        assertNotNull(j); j!!
        assertEquals(UpdateJob.EVERY_MS, j.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, j.networkType)
        assertFalse(j.isRequireBatteryNotLow)
        assertTrue(j.isPersisted)
        // after an app update or a restart of the phone, the receiver sets it up again
        js.cancelAll()
        UpdateBootReceiver().onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNotNull(js.getPendingJob(UpdateJob.JOB_ID))
    }

    // ---------------- where the announcement shows ----------------

    @Test fun bannerRules() {
        val u = UpdateInfo("1.0.6", "", "", "u", "a.apk", 1, null, "p")
        assertTrue(Updater.showBanner(u, "1.0.5", null))
        assertFalse(Updater.showBanner(u, "1.0.5", "1.0.6"))
        assertTrue(Updater.showBanner(u, "1.0.5", "1.0.4"))
        assertFalse(Updater.showBanner(u, "1.0.6", null))
        assertFalse(Updater.showBanner(null, "1.0.5", null))
    }

    /** A release found earlier shows on Home at launch, even if the card was closed for it before (1.0.4 kept that forever). */
    @Test fun bannerComesBackOnNextLaunch() {
        val newer = Updater.check("1.0.5", "$base/ok/latest", "$base/web")
        assertTrue(newer is Updater.Check.UpToDate) // the fake release is 1.0.5 = this app
        val json = json105.replace("1.0.5", "9.9.9")
        ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).edit()
            .putString(Updater.K_JSON, json).putString(Updater.K_CLOSED, "9.9.9").putBoolean("asked_default", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.advanceTimeBy(2500); compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithText("Browser 9.9.9 is available").fetchSemanticsNodes().size)
            // closing it hides it for now
            compose.runOnUiThread { currentState!!.closeUpdateBanner() }
            compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
            assertEquals(0, compose.onAllNodesWithText("Browser 9.9.9 is available").fetchSemanticsNodes().size)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.advanceTimeBy(2500); compose.waitForIdle()
            assertEquals(1, compose.onAllNodesWithText("Browser 9.9.9 is available").fetchSemanticsNodes().size)
        }
    }

    // ---------------- ad blocker ----------------

    @Test fun adBlockerNeverBlocksGithub() {
        for (h in listOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github.githubassets.com")) {
            assertTrue(h, AdBlock.neverBlock(h))
            assertFalse(h, AdBlock.blockRequest("https://$h/x", h, "example.com", null))
            assertFalse(h, AdBlock.adNavigation("https://$h/x", "example.com"))
        }
        assertFalse(AdBlock.neverBlock("notgithub.com"))
        assertFalse(AdBlock.neverBlock("github.com.evil.io"))
    }

    // ---------------- the real GitHub (run with -Dlive=1 / -PliveGithub) ----------------

    @Test fun liveGithub() {
        assumeTrue(System.getProperty("live.github") == "1")
        val r = Updater.check("1.0.3")
        println("LIVE check(1.0.3) -> " + when (r) { is Updater.Check.Newer -> "Newer ${r.info.version} ${r.info.apkUrl}"; is Updater.Check.UpToDate -> "UpToDate"; is Updater.Check.Failed -> "Failed ${r.why}" })
        assertTrue(r is Updater.Check.Newer)
        // the fallback on its own, against github.com
        val w = Updater.check("1.0.3", "http://127.0.0.1:1/latest", "https://github.com/Walidkhalid12/Browser")
        println("LIVE fallback -> " + (if (w is Updater.Check.Newer) "Newer ${w.info.version} ${w.info.apkUrl}" else w.toString()))
    }
}
