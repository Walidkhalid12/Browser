package com.khalid.browser

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 2.0.0 (29): the real activity checks GitHub by itself when it opens and when it comes back, and the Home card
 * and the "Browser X is available" notification appear with no tap on "Check for updates".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AutoUpdateTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val live: String by lazy { javaClass.classLoader!!.getResource("github_latest_v1.0.4.json")!!.readText() }
    private fun release(v: String) = live.replace("1.0.4", v)

    /** What the fake GitHub answers right now, and how many times it was asked. */
    @Volatile private var latest = "9.9.9"
    private val hits = AtomicInteger()
    private lateinit var sock: java.net.ServerSocket

    @Before fun start() {
        sock = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!sock.isClosed) {
                val c = runCatching { sock.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        c.use {
                            val r = it.getInputStream().bufferedReader()
                            r.readLine() ?: return@use
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            hits.incrementAndGet()
                            val b = release(latest).toByteArray()
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(b); flush()
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        val base = "http://127.0.0.1:${sock.localPort}"
        Updater.apiUrl = "$base/repos/latest"
        Updater.webUrl = "$base/web"
        appCheckOkAt = 0L
        // The real-phone case: the background job (or an earlier check) said "up to date" a minute ago. In 1.0.5
        // that pushed every automatic check back 30 minutes, so the new release stayed hidden.
        ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).edit().clear()
            .putLong(Updater.K_CHECKED, System.currentTimeMillis() - 60_000L)
            .putBoolean("asked_default", true).commit()
    }

    @After fun stop() {
        sock.close()
        Updater.apiUrl = "https://api.github.com/repos/${Updater.REPO}/releases/latest"
        Updater.webUrl = "https://github.com/${Updater.REPO}"
    }

    private val main get() = shadowOf(Looper.getMainLooper())

    /** Lets the app's timers run, then waits (real time) for the network thread and its answer on the main thread. */
    private fun runUntil(ms: Long = 2500, cond: () -> Boolean) {
        main.idleFor(Duration.ofMillis(ms))
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { Thread.sleep(30); main.idle() }
        main.idle()
    }

    private fun updateNotifications(): List<Notification> =
        shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications
            .filter { it.extras.getString(Notification.EXTRA_TITLE)?.startsWith("Browser ") == true && it.channelId == UpdateJob.CH_UPDATES }

    private fun bannerShown(v: String): Boolean {
        compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
        return compose.onAllNodesWithText("Browser $v is available").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun coldStartShowsHomeCardAndNotificationByItself() {
        ActivityScenario.launch(MainActivity::class.java).use {
            runUntil { currentState?.update != null }
            val st = currentState!!
            assertEquals(1, hits.get())
            assertEquals("9.9.9", st.update!!.version)
            assertFalse(st.updateChecking) // nobody tapped "Check for updates"
            assertTrue(Updater.showBanner(st.update, APP_VERSION, st.updateBannerClosed))
            assertTrue(bannerShown("9.9.9"))
            val n = updateNotifications()
            assertEquals(1, n.size)
            assertEquals("Browser 9.9.9 is available", n[0].extras.getString(Notification.EXTRA_TITLE))
            assertEquals("9.9.9", ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).getString(Updater.K_NOTIFIED, null))
        }
    }

    @Test fun comingBackToTheAppChecksAgain() {
        latest = APP_VERSION // nothing new when the app opens
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            runUntil { hits.get() >= 1 && appCheckOkAt > 0 }
            assertNull(currentState!!.update)
            assertTrue(updateNotifications().isEmpty())

            // A release comes out while the app sits in the background (MIUI keeps it alive).
            latest = "9.9.9"
            sc.moveToState(Lifecycle.State.CREATED)
            main.idleFor(Duration.ofMinutes(6))
            sc.moveToState(Lifecycle.State.RESUMED)
            runUntil { currentState?.update != null }
            assertEquals(2, hits.get())
            assertEquals("9.9.9", currentState!!.update!!.version)
            assertTrue(bannerShown("9.9.9"))
            assertEquals(1, updateNotifications().size)

            // Back again a minute later: the light throttle skips GitHub, the card stays.
            sc.moveToState(Lifecycle.State.CREATED)
            main.idleFor(Duration.ofMinutes(1))
            sc.moveToState(Lifecycle.State.RESUMED)
            runUntil(1500) { false.also { Thread.sleep(300) } }
            assertEquals(2, hits.get())
            assertTrue(bannerShown("9.9.9"))

            // Later still: it asks again, and the notification is not posted twice for the same version.
            sc.moveToState(Lifecycle.State.CREATED)
            main.idleFor(Duration.ofMinutes(6))
            sc.moveToState(Lifecycle.State.RESUMED)
            runUntil { hits.get() >= 3 }
            assertEquals(3, hits.get())
            assertEquals(1, updateNotifications().size)
        }
    }

    @Test fun closedHomeCardComesBackWhenTheAppReturns() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            runUntil { currentState?.update != null }
            compose.runOnUiThread { currentState!!.closeUpdateBanner() }
            assertFalse(bannerShown("9.9.9"))
            sc.moveToState(Lifecycle.State.CREATED)
            sc.moveToState(Lifecycle.State.RESUMED)
            main.idleFor(Duration.ofSeconds(2))
            assertTrue(bannerShown("9.9.9"))
        }
    }

    @Test fun firstLaunchWithDefaultBrowserCardStillChecks() {
        ctx.getSharedPreferences(Updater.PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked_default", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.runOnUiThread { currentState!!.card = Card.DefaultBrowser }
            runUntil { currentState?.update != null }
            val st = currentState!!
            assertEquals("9.9.9", st.update!!.version)
            assertEquals(Card.DefaultBrowser, st.card) // the update doesn't push the first-launch card away
            assertEquals(1, updateNotifications().size)
        }
    }

    @Test fun manualCheckStillOpensTheUpdateCard() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            runUntil { currentState?.update != null }
            sc.onActivity { it.checkUpdates(manual = true) }
            runUntil(500) { hits.get() >= 2 && !currentState!!.updateChecking }
            assertEquals(Card.Update, currentState!!.card)
            assertEquals("Browser 9.9.9 is available", currentState!!.updateStatus)
            assertEquals(1, updateNotifications().size)
        }
    }

    @Test fun backgroundJobStillNotifies() {
        assertTrue(UpdateJob.checkAndNotify(ctx))
        assertEquals(1, updateNotifications().size)
        assertTrue(UpdateJob.checkAndNotify(ctx))
        assertEquals(1, updateNotifications().size) // once per version
    }
}
