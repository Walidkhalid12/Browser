package com.khalid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** 1.0.4: the ad blocker's filter engine (pure Kotlin, no phone needed). */
class AdEngineTest {
    private val list = """
        [Adblock Plus 2.0]
        ! comment
        ||doubleclick.net^
        ||ads.example.com^
        /banner/ads/*
        -ad-300x250.
        ||cdn.site.com/ads/
        ||tracker.io^${'$'}third-party
        ||video.io/preroll^${'$'}script,domain=news.com|~sports.news.com
        @@||doubleclick.net/allowed/${'$'}script
        ||important.net^${'$'}important
        @@||important.net^
        /adframe.${'$'}subdocument
        |https://start.com/ad
        /\/pop[0-9]+\.js/${'$'}script
        ||popads.net^${'$'}popup
        ||badfilter.com^
        ||badfilter.com^${'$'}badfilter
        ||nothing.com^${'$'}csp=script-src 'none'
        ##.ad-banner
        ##[id^="div-gpt-ad"]
        ###sponsored
        news.com##.news-promo
        ~sports.news.com,news.com##.promo2
        example.*##.entity-ad
        site.org#@#.ad-banner
        news.com#?#div:has-text(Sponsored)
        @@||allowed-site.com^${'$'}elemhide
    """.trimIndent()

    private val e = AdEngine().apply { addList(list); addList("||priv-tracker.net^", privacy = true) }.build()

    private fun blocks(url: String, page: String = "page.com", type: Int = RT.SCRIPT) = e.match(url, page, type) != null

    @Test fun hostRules() {
        assertTrue(blocks("https://doubleclick.net/x.js"))
        assertTrue(blocks("https://stats.g.doubleclick.net/pixel?x=1", type = RT.IMAGE))
        assertTrue(blocks("https://ads.example.com/a"))
        assertFalse(blocks("https://notdoubleclick.net/x.js"))
        assertFalse(blocks("https://example.com/a"))
    }

    @Test fun urlRules() {
        assertTrue(blocks("https://cdn.x.org/banner/ads/1.png", type = RT.IMAGE))
        assertTrue(blocks("https://img.org/a-ad-300x250.gif", type = RT.IMAGE))
        assertTrue(blocks("https://cdn.site.com/ads/x.js"))
        assertFalse(blocks("https://cdn.site.com/adsx/x.js"), )
        assertTrue(blocks("https://start.com/ad/1"))
        assertFalse(blocks("https://x.com/?u=https://start.com/ad/1"))
        assertTrue(blocks("https://x.org/js/pop123.js"))
        assertFalse(blocks("https://x.org/js/popular.js"))
    }

    @Test fun optionsAndExceptions() {
        assertTrue(blocks("https://tracker.io/t.js", page = "page.com"))
        assertFalse("first-party is fine", blocks("https://tracker.io/t.js", page = "tracker.io"))
        assertTrue(blocks("https://video.io/preroll?v=1", page = "www.news.com"))
        assertFalse(blocks("https://video.io/preroll?v=1", page = "sports.news.com"))
        assertFalse(blocks("https://video.io/preroll?v=1", page = "other.com"))
        assertFalse("wrong type", blocks("https://video.io/preroll?v=1", page = "news.com", type = RT.IMAGE))
        assertFalse("@@ exception wins", blocks("https://doubleclick.net/allowed/x.js"))
        assertTrue("exception is script-only", blocks("https://doubleclick.net/allowed/x.png", type = RT.IMAGE))
        assertTrue("important beats @@", blocks("https://important.net/a.js"))
        assertTrue(blocks("https://a.com/adframe.html", type = RT.SUBDOC))
        assertFalse(blocks("https://a.com/adframe.html", type = RT.SCRIPT))
        assertFalse("\$badfilter cancels a rule", blocks("https://badfilter.com/a.js"))
        assertFalse("unsupported option: rule skipped", blocks("https://nothing.com/a.js"))
    }

    @Test fun popupsAndRedirects() {
        assertTrue(e.isAdNavigation("https://popads.net/x", "movie.com"))
        assertTrue("plain ad server counts for a pop-up", e.isAdNavigation("https://ads.example.com/landing", "movie.com"))
        assertFalse(e.isAdNavigation("https://wikipedia.org/", "movie.com"))
        assertFalse("\$popup rule doesn't block scripts", blocks("https://popads.net/x.js"))
        // Tracker list: blocks loads, never a page the user goes to.
        assertTrue(blocks("https://priv-tracker.net/p.gif", type = RT.IMAGE))
        assertFalse(e.isAdNavigation("https://priv-tracker.net/go", "movie.com"))
    }

    @Test fun cosmetic() {
        val news = e.pageCss("www.news.com")
        assertTrue(news.contains(".news-promo")); assertTrue(news.contains(".promo2"))
        assertTrue(news.contains("[id^=\"div-gpt-ad\"]"))
        assertTrue(news.contains("{display:none!important}"))
        assertFalse("procedural rules are skipped", news.contains("has-text"))
        val sports = e.pageCss("sports.news.com")
        assertTrue(sports.contains(".news-promo")); assertFalse(sports.contains(".promo2"))
        assertTrue(e.pageCss("example.co.uk").contains(".entity-ad"))
        assertFalse(e.pageCss("other.com").contains(".news-promo"))
        // Generic class/id rules only come back for names the page actually uses.
        assertTrue(e.genericCss("other.com", ".ad-banner .menu #sponsored").let { it.contains(".ad-banner") && it.contains("#sponsored") })
        assertEquals("", e.genericCss("other.com", ".menu .footer"))
        assertEquals("#@# exception", "", e.genericCss("site.org", ".ad-banner"))
        assertEquals("\$elemhide exception", "", e.pageCss("allowed-site.com"))
    }

    @Test fun wildcardMatcher() {
        assertTrue(AdEngine.glob("ad*.js", "x/ad-big.js", 0, false, true))
        assertTrue(AdEngine.glob("/ads^", "https://a.b/ads?x", 0, false, true))
        assertTrue(AdEngine.glob("/ads^", "https://a.b/ads", 0, false, true))
        assertFalse(AdEngine.glob("/ads^", "https://a.b/adsx", 0, false, true))
        assertEquals("example.co.ma", AdEngine.baseDomain("a.b.example.co.ma"))
        assertEquals("example.com", AdEngine.baseDomain("x.example.com"))
    }

    @Test fun requestTypeGuess() {
        assertEquals(RT.SCRIPT, RT.guess("https://a.com/x.js?v=1", "*/*", false))
        assertEquals(RT.IMAGE, RT.guess("https://a.com/pixel", "image/webp,*/*", false))
        assertEquals(RT.SUBDOC, RT.guess("https://a.com/frame", "text/html,application/xhtml+xml", false))
        assertEquals(RT.DOCUMENT, RT.guess("https://a.com/", null, true))
    }

    /**
     * Speed and memory with the real lists (EasyList, EasyPrivacy, AdGuard Mobile Ads, Peter Lowe, EasyList Cookie).
     * Runs when -Dfilters.dir points at a folder with them (the lists aren't kept in the repo).
     */
    @Test fun realListsSpeedAndMemory() {
        val dir = System.getProperty("filters.dir")?.let(::File)
        Assume.assumeTrue(dir != null && dir.isDirectory)
        val texts = AdLists.ALL.associateWith { File(dir, it.file).takeIf(File::exists)?.readText() }
        Assume.assumeTrue(texts.values.all { it != null })
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(80) }; return rt.totalMemory() - rt.freeMemory() }
        // The app saves the lists already slimmed, so it only builds from slim text.
        val slim = texts.map { (l, t) -> l.privacy to AdLists.slim(t!!) }
        val before = used()
        val keep = AdEngine().apply { slim.forEach { (p, t) -> addList(t, p) } }.build()
        val mem = (used() - before) / 1024.0 / 1024.0
        // Time a second build, after the JIT has warmed up (closer to how a phone runs a compiled app).
        val t0 = System.nanoTime()
        val eng = AdEngine().apply { slim.forEach { (p, t) -> addList(t, p) } }.build()
        val buildMs = (System.nanoTime() - t0) / 1e6
        val t00 = System.nanoTime(); AdEngine().apply { slim.forEach { (p, t) -> addList(t, p) } }.build(); val build2 = (System.nanoTime() - t00) / 1e6
        println("ADENGINE keep=${keep.networkRules} build3=%.0f ms".format(build2))
        val urls = listOf(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://www.googletagmanager.com/gtag/js?id=G-XYZ",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://connect.facebook.net/en_US/fbevents.js",
            "https://www.google-analytics.com/analytics.js", "https://static.hespress.com/wp-content/themes/hespress/js/main.js",
            "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Mu4mxK.woff2",
            "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js", "https://example.com/assets/app.bundle.js?v=12",
            "https://ads.pubmatic.com/AdServer/js/pwt/123/pwt.js", "https://c.amazon-adsystem.com/aax2/apstag.js",
            "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js", "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://example.org/images/banner-ad-300x250.png", "https://news.site.ma/api/v1/articles?page=2",
        )
        val expectBlocked = setOf(0, 1, 2, 3, 4, 10, 11)
        urls.forEachIndexed { i, u ->
            val hit = eng.match(u, "news.site.ma", RT.guess(u, null, false)) != null
            assertEquals("$u blocked?", i in expectBlocked, hit)
        }
        repeat(20_000) { for (u in urls) eng.match(u, "news.site.ma", RT.SCRIPT) } // warm up
        val n = 200_000
        val t1 = System.nanoTime()
        var hits = 0
        for (k in 0 until n) if (eng.match(urls[k % urls.size], "news.site.ma", RT.SCRIPT) != null) hits++
        val perUs = (System.nanoTime() - t1) / 1e3 / n
        val t2 = System.nanoTime(); val css = eng.pageCss("www.hespress.com"); val cssMs = (System.nanoTime() - t2) / 1e6
        val t3 = System.nanoTime(); eng.pageCss("www.hespress.com"); val css2Ms = (System.nanoTime() - t3) / 1e6
        val gen = eng.genericCss("www.hespress.com", ".ad-banner .adsbygoogle #ad_300 .menu .header .post .content")
        println("ADENGINE network=${eng.networkRules} hosts=${eng.hostCount} cosmetic=${eng.cosmeticRules} skipped=${eng.skipped} " +
            "untokenized=${eng.untokenized} genericKeys=${eng.genericKeys} genericAlways=${eng.genericAlways}")
        println("ADENGINE build=%.0f ms memory=%.1f MB match=%.2f us/request pageCss=%.1f ms (cached %.2f ms, %d chars) genericCss=%d chars"
            .format(buildMs, mem, perUs, cssMs, css2Ms, css.length, gen.length))
        assertNotNull(gen); assertTrue(gen.contains("#ad_300"))
        assertTrue("under 20 microseconds a request on a computer", perUs < 20)
        assertTrue(eng.untokenized < 400)
        assertNull(eng.match("https://en.wikipedia.org/wiki/Morocco", "en.wikipedia.org", RT.DOCUMENT))
    }
}
