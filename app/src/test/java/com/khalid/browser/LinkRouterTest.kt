package com.khalid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.0.3: every web link stays in Browser; only real app links leave, and only when tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LinkRouterTest {
    private fun r(url: String, tapped: Boolean = true, redirect: Boolean = false, main: Boolean = true) =
        LinkRouter.route(url, tapped, redirect, main)

    @Test fun webLinksAlwaysStay() {
        assertEquals(LinkRoute.Stay, r("https://example.com/page"))
        assertEquals(LinkRoute.Stay, r("http://example.com", tapped = false))
        assertEquals(LinkRoute.Stay, r("HTTPS://EXAMPLE.COM"))
        assertEquals(LinkRoute.Stay, r("about:blank"))
        assertEquals(LinkRoute.Stay, r("blob:https://example.com/123"))
        assertEquals(LinkRoute.Stay, r("data:text/html,hi"))
    }

    @Test fun intentLinksToWebPagesOpenHere() {
        // "Open in Chrome" style links
        assertEquals(LinkRoute.InApp("https://example.com/a?b=1"),
            r("intent://example.com/a?b=1#Intent;scheme=https;package=com.android.chrome;end"))
        assertEquals(LinkRoute.InApp("http://example.com/x"),
            r("intent://example.com/x#Intent;scheme=http;end", tapped = false))
    }

    @Test fun intentLinksToOtherBrowsersUseTheWebFallback() {
        assertEquals(LinkRoute.InApp("https://example.com/fb"),
            r("intent://x#Intent;package=org.mozilla.firefox;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffb;end"))
        assertEquals(LinkRoute.Block, r("intent://x#Intent;package=com.android.chrome;end"))
        assertEquals(LinkRoute.InApp("https://news.site/story"),
            r("intent://news.site/story#Intent;scheme=googlechromes;end"))
    }

    @Test fun appIntentLinksLeaveOnlyWhenTapped() {
        val url = "intent://open/item#Intent;scheme=myapp;package=com.example.app;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fitem;end"
        assertEquals(LinkRoute.External(url, "https://example.com/item"), r(url))
        assertEquals(LinkRoute.External(url, "https://example.com/item"), r(url, tapped = false, redirect = true))
        // a page that tries to open an app on its own gets the web version instead
        assertEquals(LinkRoute.InApp("https://example.com/item"), r(url, tapped = false))
        assertEquals(LinkRoute.Block, r("intent://open#Intent;scheme=myapp;end", tapped = false))
        // a javascript: fallback is never used
        assertEquals(LinkRoute.External("intent://a#Intent;scheme=x;S.browser_fallback_url=javascript%3Aalert(1);end", null),
            r("intent://a#Intent;scheme=x;S.browser_fallback_url=javascript%3Aalert(1);end"))
    }

    @Test fun otherBrowserSchemesComeBackHere() {
        assertEquals(LinkRoute.InApp("http://example.com/a"), r("googlechrome://example.com/a"))
        assertEquals(LinkRoute.InApp("https://example.com/a"), r("googlechromes://example.com/a"))
        assertEquals(LinkRoute.InApp("https://example.com/p"), r("microsoft-edge:https://example.com/p"))
        assertEquals(LinkRoute.InApp("https://example.com/q"), r("firefox://open-url?url=https%3A%2F%2Fexample.com%2Fq"))
        assertEquals(LinkRoute.InApp("https://example.com/s"), r("x-safari-https://example.com/s", tapped = false))
        assertEquals(LinkRoute.Block, r("googlechrome://"))
        assertEquals(LinkRoute.Block, r("opera://open"))
    }

    @Test fun realAppSchemesLeaveWhenTapped() {
        for (u in listOf("tel:+212600000000", "mailto:a@b.com", "sms:123", "geo:33.5,-7.6", "market://details?id=x", "whatsapp://send?text=hi", "spotify:track:1")) {
            assertEquals(u, LinkRoute.External(u, null), r(u))
            assertEquals(u, LinkRoute.Block, r(u, tapped = false))
        }
        // a frame inside the page (often an ad) can't open apps without a tap
        assertEquals(LinkRoute.Block, r("market://details?id=x", tapped = false, redirect = true, main = false))
    }

    @Test fun intentParsing() {
        val i = LinkRouter.parseIntent("intent://maps.example.com/@1,2#Intent;scheme=https;package=com.google.android.apps.maps;S.browser_fallback_url=https%3A%2F%2Fmaps.example.com;end")
        assertEquals("https", i.scheme)
        assertEquals("com.google.android.apps.maps", i.pkg)
        assertEquals("https://maps.example.com", i.fallback)
        assertEquals("https://maps.example.com/@1,2", i.webUrl)
        assertNull(LinkRouter.parseIntent("intent:#Intent;scheme=myapp;end").webUrl)
    }

    @Test fun updateBannerAndNotificationRules() {
        val u = UpdateInfo("1.0.4", "Bug fixes", "Bug fixes", "u", "a.apk", 1, null, "p")
        assertTrue(Updater.showBanner(u, "1.0.3", null))
        assertFalse(Updater.showBanner(u, "1.0.3", "1.0.4")) // closed for this version
        assertTrue(Updater.showBanner(u, "1.0.3", "1.0.3"))  // closed for an older one: comes back
        assertFalse(Updater.showBanner(u, "1.0.4", null))
        assertFalse(Updater.showBanner(null, "1.0.3", null))
        assertTrue(Updater.shouldNotify("1.0.4", "1.0.3", null))
        assertFalse(Updater.shouldNotify("1.0.4", "1.0.3", "1.0.4"))
        assertFalse(Updater.shouldNotify("1.0.3", "1.0.3", null))
        // release titles
        assertEquals("Bug fixes", Updater.releaseName("Bug fixes", "v1.0.3"))
        assertEquals("", Updater.releaseName("Browser 1.0.2", "v1.0.2"))
        assertEquals("", Updater.releaseName("v1.0.2", "v1.0.2"))
        assertEquals("", Updater.releaseName("", "v1.0.2"))
        // notes that only repeat the title get no separate box
        assertEquals("", Updater.extraNotes(u))
        // the 1.0.3 release itself: title "Bug fixes", notes "Bug fixes" plus the SHA-256 line
        val json = """{"tag_name":"v1.0.3","name":"Bug fixes","draft":false,"prerelease":false,"html_url":"p",
            "body":"Bug fixes\n\nSHA-256: `abc123`","assets":[{"name":"Browser-1.0.3.apk","size":2000000,
            "browser_download_url":"https://github.com/Walidkhalid12/Browser/releases/download/v1.0.3/Browser-1.0.3.apk"}]}"""
        val r = Updater.parse(json)!!
        assertEquals("1.0.3", r.version)
        assertEquals("Bug fixes", r.name)
        assertEquals("Bug fixes", r.notes)
        assertEquals("", Updater.extraNotes(r))
    }
}
