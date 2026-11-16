package com.khalid.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CrashTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun settle(ms: Long = 1200) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tapBar(fx: Float) {
        rule.onRoot().performTouchInput { click(Offset(width * fx, height - 50f * 2.75f + 0f)) }
        // step through the morph frame by frame
        repeat(40) { rule.mainClock.advanceTimeByFrame() }
        settle()
    }
    private fun swipeBar(dir: Int) {
        rule.onRoot().performTouchInput {
            val y = height - 50f * 2.75f
            val a = Offset(width * (if (dir < 0) 0.85f else 0.15f), y); val b = Offset(width * (if (dir < 0) 0.1f else 0.9f), y)
            swipe(a, b, 180)
        }
        repeat(30) { rule.mainClock.advanceTimeByFrame() }; settle()
    }
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; repeat(30) { rule.mainClock.advanceTimeByFrame() }; settle() }
    private fun text(t: String) = rule.onAllNodesWithText(t)[0]

    @Test fun tapEverything() {
        rule.mainClock.autoAdvance = false
        settle(1500)
        tapBar(0.08f)            // tabs
        text("New tab").performClick(); settle()
        tapBar(0.08f)
        text("Close all").performClick(); settle()
        text("Done").performClick(); repeat(30) { rule.mainClock.advanceTimeByFrame() }; settle()
        tapBar(0.5f)             // search
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("wiki"); settle()
        text("Cancel").performClick(); settle()
        // open a real page: the WebView takes over Home
        tapBar(0.5f)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("example.com"); settle()
        rule.onAllNodes(hasSetTextAction())[0].performImeAction(); settle()
        // swipe the bar: past the last tab opens a new tab, then back and forth
        repeat(3) { swipeBar(-1); swipeBar(1); swipeBar(1); swipeBar(-1); swipeBar(-1) }
        tapBar(0.08f)
        rule.onRoot().performTouchInput { swipe(Offset(width * 0.25f, height * 0.3f), Offset(width * 0.95f, height * 0.3f), 150) }
        settle(); back()
        tapBar(0.92f)
        text("Bookmarks").performClick(); settle()
        back()
        text("History").performClick(); settle()
        back()
        text("Downloads").performClick(); settle()
        text("Open file"); back(); back()
        tapBar(0.08f)
        text("Done").performClick(); settle()
        tapBar(0.92f)            // more
        text("Dark mode").performClick(); repeat(40) { rule.mainClock.advanceTimeByFrame() }; settle()
        text("Dark mode").performClick(); repeat(40) { rule.mainClock.advanceTimeByFrame() }; settle()
        text("Private tab").performClick(); settle()
        rule.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f) }
        repeat(30) { rule.mainClock.advanceTimeByFrame() }; settle()
        tapBar(0.92f)
        back()
        tapBar(0.5f)
        back()
        // fast taps: open and close mid-flight
        tapBar(0.08f); back(); tapBar(0.92f)
        text("New tab").performClick(); settle()
    }

    /** 1.1.0: cards, groups, recently closed, reader, permission ask, private lock, dark pages, search shortcuts. */
    @Test fun features110() {
        rule.mainClock.autoAdvance = false
        settle(1500)
        val st = currentState!!
        fun ui(b: () -> Unit) { rule.runOnUiThread(b); repeat(30) { rule.mainClock.advanceTimeByFrame() }; settle() }
        // search shortcuts + autocomplete
        tapBar(0.5f)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("@"); settle()
        text("@yt   YouTube").performClick(); settle()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("cats"); settle()
        text("Search YouTube for cats"); back()
        ui { st.open("example.com") }
        ui { st.newTab("https://example.org/") }
        // tab menu card from long-press state, then recently closed
        ui { st.card = Card.TabMenu }
        text("Close this tab").performClick(); settle()
        text("Undo").performClick(); settle()
        ui { st.close(st.activeId, undo = false) }
        ui { st.card = Card.TabMenu }
        text("Recently closed").performClick(); settle()
        back()
        // groups
        ui { st.addGroup("Work", st.active) }
        tapBar(0.08f)
        text("Work  1").performClick(); settle()
        text("All").performClick(); settle()
        ui { st.card = Card.Group(st.activeId) }
        text("No group").performClick(); settle()
        ui { st.deleteGroup("Work"); st.screen = Screen.Home }
        // site info, QR, history cards
        ui { st.card = Card.SiteInfo }
        text("Block ads & trackers").performClick(); settle()
        text("QR code").performClick(); settle()
        text("Copy link").performClick(); settle()
        ui { st.card = Card.History(st.activeId) }
        back()
        // permission ask
        var answer: Boolean? = null
        ui { sitePermission(st, "example.com", listOf("camera")) { answer = it } }
        text("Block").performClick(); settle()
        check(answer == false)
        // reader with a fake article
        ui { st.article = Article("https://example.com/a", "Title here", "Example", "Someone",
            listOf("p" to "A".repeat(300), "h" to "Head", "li" to "Item", "q" to "Quote", "pre" to "code()")); st.screen = Screen.Reader }
        text("Aa  18").performClick(); settle()
        back()
        // dark pages rebuilds views; lock private tabs overlay
        ui { st.setForceDarkPages(true) }
        ui { st.setForceDarkPages(false) }
        ui { st.newTab(private = true); st.privateLocked = true }
        text("Private tabs are locked")
        text("Go to normal tabs").performClick(); settle()
        ui { st.privateLocked = false }
        tapBar(0.92f)
        text("Recently closed").performClick(); settle()
        back(); back()
        // state survives to disk
        ui { st.persistState(rule.activity.filesDir); st.loadState(rule.activity.filesDir) }
    }
}
