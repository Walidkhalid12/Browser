package com.khalid.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** 1.0.1: Back works like Chrome, the updater reads GitHub correctly, dark colors keep their contrast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class V101Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun settle(ms: Long = 1200) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle(600) }

    // 1.0.3: the version the updater compares must be the app's real version (1.0.2 still said 1.0.1)
    @Test fun appVersionMatchesTheBuild() {
        val a = rule.activity
        assertEquals(a.packageManager.getPackageInfo(a.packageName, 0).versionName, APP_VERSION)
    }

    // 1.0.3: the update banner shows on Home, and closing it holds for that version only
    @Test fun updateBannerOnHome() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread {
            st.updateBannerClosed = null
            st.update = UpdateInfo("9.9.9", "Bug fixes", "Bug fixes", "u", "Browser-9.9.9.apk", 1, null, "p")
        }
        settle()
        assertEquals(1, rule.onAllNodesWithText("Browser 9.9.9 is available").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Bug fixes").fetchSemanticsNodes().size)
        rule.runOnUiThread { st.closeUpdateBanner() }; settle()
        assertEquals(0, rule.onAllNodesWithText("Browser 9.9.9 is available").fetchSemanticsNodes().size)
        rule.runOnUiThread { st.update = UpdateInfo("9.9.10", "", "", "u", "a.apk", 1, null, "p") }; settle()
        assertEquals(1, rule.onAllNodesWithText("Browser 9.9.10 is available").fetchSemanticsNodes().size)
    }

    // 1.0.3: a pop-up opens as a new tab in Browser, and when the page closes it the opener comes back
    @Test fun popupOpensAsTabAndClosesBack() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread { st.open("https://example.com/a") }; settle()
        val opener = st.active!!
        val before = st.tabs.size
        lateinit var pop: Tab
        rule.runOnUiThread { pop = st.openPopupTab(opener) }; settle()
        assertEquals(before + 1, st.tabs.size)
        assertEquals(pop.id, st.activeId)
        assertEquals(opener.id, pop.openerId)
        rule.runOnUiThread { st.closePopupTab(pop) }; settle()
        assertEquals(before, st.tabs.size)
        assertEquals(opener.id, st.activeId)
    }

    @Test fun backGoesOneStepLikeChrome() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread { st.open("https://example.com/a") }; settle()
        val tab = st.active!!
        val v = tab.view!!
        rule.runOnUiThread { shadowOf(v).pushEntryToHistory("https://example.com/a") }
        rule.runOnUiThread { v.loadUrl("https://example.com/b"); shadowOf(v).pushEntryToHistory("https://example.com/b") }; settle()
        rule.runOnUiThread { v.loadUrl("https://example.com/c"); shadowOf(v).pushEntryToHistory("https://example.com/c") }; settle()
        assertTrue(v.canGoBack())

        back() // c -> b, one step only
        assertEquals(tab.id, st.active!!.id)
        assertFalse(st.active!!.blank)
        assertEquals("exactly one step back", 1, shadowOf(v).goBackInvocations)
        assertTrue("still on the page after one Back", !rule.activity.isFinishing)

        back() // b -> a
        assertEquals(2, shadowOf(v).goBackInvocations)
        assertEquals(tab.id, st.active!!.id)
        assertFalse(st.active!!.blank)
        assertFalse(v.canGoBack())

        back() // a has no history left -> home, app stays open
        assertTrue(st.active!!.blank)
        assertEquals(Screen.Home, st.screen)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun backFromLinkOpenedInNewTabReturnsToFirstTab() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread { st.open("https://example.com/first") }; settle()
        val first = st.active!!.id
        rule.runOnUiThread { st.openLinkInNewTab("https://example.com/second", false); st.activeId = st.tabs.last().id }; settle()
        val second = st.active!!.id
        assertTrue(first != second)
        back()
        assertEquals(first, st.active!!.id)
        assertNull(st.tabs.firstOrNull { it.id == second })
    }

    @Test fun backClosesFullScreenVideoAndFindBarFirst() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread { st.open("https://example.com/a") }; settle()
        val v = st.active!!.view!!
        rule.runOnUiThread { shadowOf(v).pushEntryToHistory("https://example.com/a"); shadowOf(v).pushEntryToHistory("https://example.com/b") }; settle()
        rule.runOnUiThread { st.finding = true }; settle()
        back()
        assertFalse(st.finding)
        assertEquals("page history untouched", 0, shadowOf(v).goBackInvocations)
    }

    @Test fun backClosesOpenSheetFirst() {
        settle(2000)
        val st = currentState!!
        rule.runOnUiThread { st.open("https://example.com/a") }; settle()
        val v = st.active!!.view!!
        rule.runOnUiThread { shadowOf(v).pushEntryToHistory("https://example.com/a"); shadowOf(v).pushEntryToHistory("https://example.com/b") }; settle()
        rule.runOnUiThread { st.screen = Screen.Tabs }; settle()
        back()
        assertEquals(Screen.Home, st.screen)
        assertEquals("page history untouched", 0, shadowOf(v).goBackInvocations)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class UpdaterTest {
    @Test fun versions() {
        assertTrue(Updater.compare("1.0.1", "1.0.0") > 0)
        assertTrue(Updater.compare("v1.0.10", "1.0.9") > 0)
        assertTrue(Updater.compare("1.1", "1.0.9") > 0)
        assertEquals(0, Updater.compare("v1.0.1", "1.0.1"))
        assertTrue(Updater.compare("1.0.0", "1.0.1") < 0)
        assertEquals(0, Updater.compare("1.0.1-beta", "1.0.1"))
    }

    @Test fun parsesGithubRelease() {
        val json = """{"tag_name":"v1.0.1","draft":false,"prerelease":false,"html_url":"https://github.com/Walidkhalid12/Browser/releases/tag/v1.0.1",
            "body":"## What's new\n- **Faster opening**\n- Better dark mode\nSHA-256: abc",
            "assets":[{"name":"notes.txt","browser_download_url":"x","size":3},
                      {"name":"Browser-1.0.1.apk","size":2000000,"digest":"sha256:ABCDEF",
                       "browser_download_url":"https://github.com/Walidkhalid12/Browser/releases/download/v1.0.1/Browser-1.0.1.apk"}]}"""
        val u = Updater.parse(json)
        assertNotNull(u); u!!
        assertEquals("1.0.1", u.version)
        assertEquals("Browser-1.0.1.apk", u.apkName)
        assertEquals(2000000L, u.apkSize)
        assertEquals("abcdef", u.sha256)
        assertEquals("What's new\n• Faster opening\n• Better dark mode", u.notes)
    }

    @Test fun ignoresReleasesWithoutApkOrPrerelease() {
        assertNull(Updater.parse("""{"tag_name":"v2","assets":[]}"""))
        assertNull(Updater.parse("""{"tag_name":"v2","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
    }

    private fun lum(c: Long): Double {
        fun ch(v: Long): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
    }
    private fun ratio(a: Long, b: Long): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }

    @Test fun darkPaletteContrast() {
        val fg = 0xEDEDED; val bg = 0x0A0A0A; val raised = 0x141414; val soft = 0x1E1E1E
        assertTrue(ratio(fg.toLong(), bg.toLong()) >= 7.0)
        assertTrue(ratio(fg.toLong(), soft.toLong()) >= 7.0)
        assertTrue(ratio(fg.toLong(), raised.toLong()) >= 7.0)
        // secondary text = fg at 60% alpha over the page
        val sec = (0.6 * 0xED + 0.4 * 0x0A).toLong().let { (it shl 16) or (it shl 8) or it }
        assertTrue(ratio(sec, bg.toLong()) >= 4.5)
        val secSoft = (0.6 * 0xED + 0.4 * 0x1E).toLong().let { (it shl 16) or (it shl 8) or it }
        assertTrue(ratio(secSoft, soft.toLong()) >= 4.5)
    }
}
