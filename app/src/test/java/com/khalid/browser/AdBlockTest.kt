package com.khalid.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.4: what the web view asks the ad blocker (requests, pop-ups and redirects), and list handling. */
class AdBlockTest {
    @After fun reset() { AdBlock.use(null) }

    @Test fun builtInListUntilFilterListsLoad() {
        AdBlock.use(null)
        Blocker.use(listOf("doubleclick.net"))
        assertTrue(AdBlock.blockRequest("https://ad.doubleclick.net/x.js", "ad.doubleclick.net", "news.com", null))
        assertFalse("a site's own files load", AdBlock.blockRequest("https://doubleclick.net/x.js", "doubleclick.net", "doubleclick.net", null))
        assertTrue(AdBlock.adNavigation("https://doubleclick.net/click", "news.com"))
    }

    @Test fun filterListsDecideOnceLoaded() {
        AdBlock.use(AdEngine().apply {
            addList("||adserver.net^\n/banners/*\$image\n||popcash.net^\$popup\n@@||adserver.net/ok.js\n")
            addList("||stats.net^", privacy = true)
        }.build())
        assertTrue(AdBlock.blockRequest("https://adserver.net/a.js", "adserver.net", "news.com", "*/*"))
        assertFalse(AdBlock.blockRequest("https://adserver.net/ok.js", "adserver.net", "news.com", "*/*"))
        assertTrue(AdBlock.blockRequest("https://cdn.com/banners/1", "cdn.com", "news.com", "image/webp,*/*"))
        assertFalse("\$image rule, script request", AdBlock.blockRequest("https://cdn.com/banners/1.js", "cdn.com", "news.com", "*/*"))
        assertTrue(AdBlock.blockRequest("https://stats.net/p", "stats.net", "news.com", null))
        // Pop-ups and forced redirects
        assertTrue(AdBlock.adNavigation("https://popcash.net/go?id=1", "movies.com"))
        assertTrue(AdBlock.adNavigation("https://adserver.net/landing", "movies.com"))
        assertFalse("tracker list never blocks a page", AdBlock.adNavigation("https://stats.net/", "movies.com"))
        assertFalse(AdBlock.adNavigation("https://www.wikipedia.org/", "movies.com"))
        assertFalse("only web addresses", AdBlock.adNavigation("tel:123", "movies.com"))
    }

    @Test fun listsAreSlimmedAndChecked() {
        val raw = "[Adblock Plus 2.0]\n! Title: x\n||ads.net^\nsite.com##+js(nowebrtc)\nsite.com#?#div:has-text(Ad)\n##.ad\n"
        assertEquals("||ads.net^\n##.ad\n", AdLists.slim(raw))
        assertFalse("an error page is not a list", AdLists.looksValid("<html><body>502 Bad Gateway</body></html>".repeat(100)))
        assertTrue(AdLists.looksValid((1..200).joinToString("\n") { "||ad$it.net^" }))
        assertEquals(5, AdLists.ALL.size)
        assertTrue(AdLists.ALL.all { it.url.startsWith("https://") })
    }
}
