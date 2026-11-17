package com.khalid.browser

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 1.0.2 download engine: range math, pause and continue, saved state, ranges joined into one file,
 * against a real little HTTP server on this computer.
 */
class DlEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A tiny file server: byte ranges (on/off), ETag + If-Range, optional slow speed and a connection limit. */
    class TestServer {
        @Volatile var data: ByteArray = ByteArray(0)
        @Volatile var etag = "\"v1\""
        @Volatile var ranges = true
        @Volatile var delayPer64k = 0L
        @Volatile var maxConns = 100
        val requests = AtomicInteger()
        val rangedRequests = AtomicInteger()
        val sent = AtomicLong()
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val lastHeaders = java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>>()
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val url: String get() = "http://127.0.0.1:${server.localPort}/file.bin"
        private val pool = java.util.concurrent.Executors.newCachedThreadPool()

        init {
            Thread {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    sock.sendBufferSize = 128 * 1024 // like a real line, not a giant local pipe
                    pool.execute { runCatching { sock.use { conn(it) } } }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun readLine(i: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }

        /** One connection; several requests one after another (keep-alive), like a real server. */
        private fun conn(sock: java.net.Socket) {
            val i = java.io.BufferedInputStream(sock.getInputStream())
            val out = java.io.BufferedOutputStream(sock.getOutputStream(), 65536)
            while (true) {
                val first = readLine(i) ?: return
                if (first.isBlank()) continue
                val path = first.split(" ")[1]
                val h = HashMap<String, String>()
                while (true) { val l = readLine(i) ?: return; if (l.isEmpty()) break; h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                fun head(code: Int, extra: Map<String, String>, len: Long) {
                    val sb = StringBuilder("HTTP/1.1 $code X\r\n")
                    extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                    sb.append("Content-Length: $len\r\n\r\n")
                    out.write(sb.toString().toByteArray()); out.flush()
                }
                if (path == "/redirect") { head(302, mapOf("Location" to "/file.bin"), 0); continue }
                if (path != "/file.bin") { head(404, emptyMap(), 0); continue }
                requests.incrementAndGet()
                lastHeaders.add(h)
                val now = live.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    if (now > maxConns) { head(503, emptyMap(), 0); continue }
                    val d = data
                    var start = 0L; var end = d.size - 1L; var partial = false
                    val r = h["range"]; val ifRange = h["if-range"]
                    if (ranges && r != null && (ifRange == null || ifRange == etag)) {
                        val m = Regex("bytes=(\\d+)-(\\d*)").find(r)!!
                        start = m.groupValues[1].toLong()
                        if (m.groupValues[2].isNotEmpty()) end = minOf(end, m.groupValues[2].toLong())
                        if (start >= d.size) { head(416, mapOf("Content-Range" to "bytes */${d.size}"), 0); continue }
                        partial = true; rangedRequests.incrementAndGet()
                    }
                    val hs = LinkedHashMap<String, String>()
                    hs["ETag"] = etag
                    if (ranges) hs["Accept-Ranges"] = "bytes"
                    if (partial) hs["Content-Range"] = "bytes $start-$end/${d.size}"
                    head(if (partial) 206 else 200, hs, maxOf(0, end - start + 1))
                    var at = start
                    while (at <= end) {
                        val n = minOf(65536L, end - at + 1).toInt()
                        out.write(d, at.toInt(), n); out.flush()
                        sent.addAndGet(n.toLong()); at += n
                        if (delayPer64k > 0) Thread.sleep(delayPer64k)
                    }
                } finally { live.decrementAndGet() }
            }
        }

        fun stop() { server.close(); pool.shutdownNow() }
    }

    private lateinit var srv: TestServer
    @Before fun up() { srv = TestServer() }
    @After fun down() { srv.stop() }

    private fun bytes(n: Int, seed: Long = 7) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun task(url: String = srv.url) = DlTask(1, url, "file.bin", "", "BrowserTest/1.0", "a=b", "https://page.example/", 0)

    // ---------------- range math ----------------

    @Test fun planCoversEveryByteOnce() {
        for (total in listOf(1L, 2L, 5L, 999L, 1_000_003L, 64L * DlMath.MB + 7)) for (n in 1..6) {
            val p = DlMath.plan(total, n)
            assertTrue("total=$total n=$n", DlMath.covers(p, total))
            assertEquals(total, p.sumOf { it.end - it.start + 1 })
        }
        assertEquals(1, DlMath.conns(500_000)); assertEquals(2, DlMath.conns(5 * DlMath.MB))
        assertEquals(4, DlMath.conns(20 * DlMath.MB)); assertEquals(6, DlMath.conns(500 * DlMath.MB)); assertEquals(1, DlMath.conns(-1))
    }

    @Test fun contentRangeAndSplitMath() {
        assertArrayEquals(longArrayOf(0, 99, 1000), DlMath.contentRange("bytes 0-99/1000"))
        assertArrayEquals(longArrayOf(5, 9, -1), DlMath.contentRange("bytes 5-9/*"))
        assertArrayEquals(longArrayOf(-1, -1, 1000), DlMath.contentRange("bytes */1000"))
        assertNull(DlMath.contentRange("nonsense"))
        val s = Seg(0, 10 * DlMath.MB - 1, 2 * DlMath.MB)
        val n = DlMath.split(s)!!
        // the two halves meet exactly, nothing lost, nothing twice
        assertEquals(s.end + 1, n.start); assertEquals(10 * DlMath.MB - 1, n.end)
        assertTrue(DlMath.covers(listOf(Seg(0, s.end, 0), n), 10 * DlMath.MB))
        assertNull(DlMath.split(Seg(0, DlMath.MB, 0))) // too small to be worth it
        assertEquals(2000, DlMath.backoffMs(0)); assertEquals(4000, DlMath.backoffMs(1)); assertEquals(60_000, DlMath.backoffMs(9))
    }

    // ---------------- real downloads ----------------

    @Test fun bigFileUsesSeveralConnectionsAndArrivesIntact() {
        srv.data = bytes(40 * 1024 * 1024 + 123); srv.delayPer64k = 1
        val t = task(); val part = File(tmp.root, "a.part")
        val end = DlRun(t, part).execute()
        assertTrue("$end", end is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
        assertTrue("peak connections ${srv.peak.get()}", srv.peak.get() >= 4)
        // no data wasted: the server sent about the file's size, not more
        // (what is counted is what the server pushed into the socket, so a little read-ahead from the split ranges shows up)
        println("big file: sent ${srv.sent.get()} for ${srv.data.size}, peak ${srv.peak.get()} connections, ${srv.requests.get()} requests")
        assertTrue("sent ${srv.sent.get()}", srv.sent.get() <= srv.data.size * 105L / 100)
        // the page's identity goes with every request
        srv.lastHeaders.forEach { h ->
            assertEquals("BrowserTest/1.0", h["user-agent"]); assertEquals("https://page.example/", h["referer"])
            assertEquals("a=b", h["cookie"]); assertEquals("identity", h["accept-encoding"])
        }
    }

    @Test fun pauseThenContinueFromTheSameByte() {
        srv.data = bytes(12 * 1024 * 1024)
        srv.delayPer64k = 25
        val t = task(); val part = File(tmp.root, "b.part")
        val run1 = DlRun(t, part)
        val th = Thread { Thread.sleep(400); run1.stop() }.apply { start() }
        val end1 = run1.execute(); th.join()
        assertTrue("$end1", end1 is DlRun.End.Stopped)
        val got1 = t.done
        assertTrue("got $got1", got1 > 0 && got1 < srv.data.size)
        assertTrue(t.ranges && t.probed)
        val sentBefore = srv.sent.get()
        srv.delayPer64k = 0
        val end2 = DlRun(t, part).execute()
        assertTrue("$end2", end2 is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
        // the second run only fetched what was missing (plus at most the unflushed tail of each connection)
        val second = srv.sent.get() - sentBefore
        assertTrue("second run $second, missing ${srv.data.size - got1}", second <= srv.data.size - got1 + 4L * 1024 * 1024)
        assertTrue(srv.lastHeaders.drop(1).any { (it["if-range"] ?: "") == "\"v1\"" })
    }

    @Test fun fileChangedOnTheSiteStartsOverCleanly() {
        srv.data = bytes(6 * 1024 * 1024, seed = 1)
        srv.delayPer64k = 25
        val t = task(); val part = File(tmp.root, "c.part")
        val run1 = DlRun(t, part)
        Thread { Thread.sleep(300); run1.stop() }.start()
        run1.execute()
        assertTrue(t.done < srv.data.size)
        srv.data = bytes(5 * 1024 * 1024 + 9, seed = 2); srv.etag = "\"v2\""; srv.delayPer64k = 0
        val end = DlRun(t, part).execute()
        assertTrue("$end", end is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
        assertTrue(t.note.contains("changed"))
    }

    @Test fun siteWithoutRangesRestartsAndSaysSo() {
        srv.data = bytes(3 * 1024 * 1024); srv.ranges = false; srv.delayPer64k = 25
        val t = task(); val part = File(tmp.root, "d.part")
        val run1 = DlRun(t, part)
        Thread { Thread.sleep(300); run1.stop() }.start()
        run1.execute()
        assertTrue(!t.resumable)
        assertEquals(1, t.segs.size)
        srv.delayPer64k = 0
        val end = DlRun(t, part).execute()
        assertTrue("$end", end is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
        assertTrue(t.note.contains("can't resume"))
        assertEquals(0, srv.rangedRequests.get())
    }

    @Test fun busySiteGetsFewerConnectionsButFileStillCompletes() {
        srv.data = bytes(40 * 1024 * 1024); srv.maxConns = 2
        val t = task(); val part = File(tmp.root, "e.part")
        val end = DlRun(t, part).execute()
        assertTrue("$end", end is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
    }

    @Test fun redirectsSmallAndEmptyFiles() {
        srv.data = bytes(1000)
        val t = task(srv.url.replace("/file.bin", "/redirect")); val part = File(tmp.root, "f.part")
        assertTrue(DlRun(t, part).execute() is DlRun.End.Done)
        assertArrayEquals(srv.data, part.readBytes())
        assertEquals(srv.url, t.finalUrl)
        srv.data = ByteArray(0)
        val t2 = task(); val p2 = File(tmp.root, "g.part")
        assertTrue(DlRun(t2, p2).execute() is DlRun.End.Done)
        assertEquals(0, p2.length())
    }

    @Test fun missingFileFails() {
        val t = task(srv.url.replace("/file.bin", "/nothing"))
        val end = DlRun(t, File(tmp.root, "h.part")).execute()
        assertTrue("$end", end is DlRun.End.Failed && end.code == 404)
    }
}

/** Saved downloads come back after the app (or the phone) restarts. Needs Android's JSON, so it runs in Robolectric. */
@RunWith(RobolectricTestRunner::class)
class DlStateTest {
    @Test fun savedStateSurvivesARestart() {
        val t = DlTask(42, "https://x.example/a.zip", "a.zip", "application/zip", "UA", "c=1", "https://x.example/", 5)
        t.status = DlState.RUNNING; t.total = 1000; t.etag = "\"e\""; t.ranges = true; t.probed = true
        t.segs.addAll(listOf(Seg(0, 499, 300), Seg(500, 999, 700)))
        // only what was flushed to disk is saved, not what is still in memory
        t.saved = listOf(longArrayOf(0, 499, 250), longArrayOf(500, 999, 650))
        val p = DlTask(43, "https://x.example/b.bin", "b.bin", "", "", "", "", 6).apply { status = DlState.PAUSED; reason = DlWhy.USER }
        val d = DlTask(44, "https://x.example/c.bin", "c.bin", "", "", "", "", 7).apply { status = DlState.DONE; total = 10; uri = "content://media/1" }
        val (list, boot) = DlTask.listFromJson(DlTask.listToJson(listOf(t, p, d), 3))
        assertEquals(3, boot)
        val r = list[0]
        assertEquals(DlState.PAUSED, r.status); assertEquals(DlWhy.INTERRUPTED, r.reason); assertTrue(r.wasActive)
        assertEquals(400, r.done); assertEquals("\"e\"", r.etag); assertEquals(1000, r.total); assertTrue(r.resumable)
        assertEquals(listOf(250L, 650L), r.segs.map { it.pos })
        assertEquals("c=1", r.cookie); assertEquals("https://x.example/", r.referer)
        // paused by you stays paused; finished stays finished
        assertEquals(DlState.PAUSED, list[1].status); assertEquals(DlWhy.USER, list[1].reason); assertTrue(!list[1].wasActive)
        assertEquals(DlState.DONE, list[2].status); assertEquals(10, list[2].done); assertEquals("content://media/1", list[2].uri)
    }

    @Test fun resumeAfterRestartUsesSavedRanges() {
        val data = ByteArray(5 * 1024 * 1024).also { Random(3).nextBytes(it) }
        val srv = DlEngineTest.TestServer().apply { this.data = data; delayPer64k = 25 }
        try {
            val dir = kotlin.io.path.createTempDirectory().toFile()
            val part = File(dir, "x.part")
            val t = DlTask(9, srv.url, "x.bin", "", "", "", "", 0)
            val run = DlRun(t, part)
            Thread { Thread.sleep(400); run.stop() }.start()
            run.execute()
            // "restart the app": save, read back, carry on with a new run
            val back = DlTask.listFromJson(DlTask.listToJson(listOf(t), 1)).first[0]
            assertTrue(back.done in 1 until data.size)
            srv.delayPer64k = 0
            assertTrue(DlRun(back, part).execute() is DlRun.End.Done)
            assertArrayEquals(data, part.readBytes())
        } finally { srv.stop() }
    }
}
