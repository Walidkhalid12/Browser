package com.khalid.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =====================================================================
// 1.0.2: Browser's own download engine (the part with no Android in it, so it is tested on the computer).
// Several ranged connections per file, pause and resume from the exact byte, checks that the file on the
// site did not change before resuming, and a clean restart when a site can't resume.
// =====================================================================

/** Download states. Same numbers as the old system downloads, so the screens read both. */
object DlState { const val QUEUED = 1; const val RUNNING = 2; const val PAUSED = 4; const val DONE = 8; const val FAILED = 16 }

/** Why a download is paused or failed. */
object DlWhy {
    const val RETRY = 1
    const val NETWORK = 2
    const val USER = 10
    const val INTERRUPTED = 11
    const val SYSTEM_LIMIT = 12
    const val CANT_SAVE = 1001
    const val CONNECTION = 1004
    const val DISK_FULL = 1006
    const val SIZE = 1010
    const val CHANGED = 1012
}

/** One byte range of a file: start..end (inclusive), pos = the next byte to write. end = MAX when the size is unknown. */
class Seg(val start: Long, end: Long, pos: Long) {
    @Volatile var end = end
    @Volatile var pos = pos
    @Volatile var busy = false
    val left: Long get() = if (end == Long.MAX_VALUE) Long.MAX_VALUE else (end - pos + 1).coerceAtLeast(0)
    val finished: Boolean get() = end != Long.MAX_VALUE && pos > end
    val got: Long get() = pos - start
}

/** Range math, kept apart so it can be tested on its own. */
object DlMath {
    const val MB = 1024L * 1024L
    const val MAX_CONN = 6
    /** A busy range is only split when it has at least this much left (smaller splits waste more than they win). */
    const val MIN_STEAL = 2 * MB

    /** How many connections a file of this size gets: small files one, big files up to six. */
    fun conns(bytes: Long): Int = when {
        bytes < 0 -> 1
        bytes < 2 * MB -> 1
        bytes < 8 * MB -> 2
        bytes < 32 * MB -> 4
        else -> MAX_CONN
    }

    /** Splits a file into n back-to-back ranges that cover every byte once. */
    fun plan(total: Long, n: Int): List<Seg> {
        if (total <= 0) return listOf(Seg(0, if (total == 0L) -1 else Long.MAX_VALUE, 0))
        val k = n.coerceIn(1, MAX_CONN).toLong().coerceAtMost(total)
        val size = total / k
        return (0 until k).map { i ->
            val s = i * size
            val e = if (i == k - 1) total - 1 else s + size - 1
            Seg(s, e, s)
        }
    }

    /** "bytes 0-99/1000" -> [0, 99, 1000]; "bytes *\/1000" -> [-1, -1, 1000]; unknown total -> -1. */
    fun contentRange(h: String?): LongArray? {
        if (h == null) return null
        Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(h)?.let { m ->
            return longArrayOf(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull() ?: -1L)
        }
        Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE).find(h)?.let { m ->
            return longArrayOf(-1, -1, m.groupValues[1].toLong())
        }
        return null
    }

    /**
     * Work stealing: takes the second half of a busy range for an idle connection.
     * The busy one keeps at least [keep] bytes so the data already in its buffer is not thrown away.
     */
    fun split(seg: Seg, minLeft: Long = MIN_STEAL, keep: Long = 512 * 1024L): Seg? = synchronized(seg) {
        if (seg.end == Long.MAX_VALUE) return null
        val left = seg.left
        if (left < minLeft) return null
        val mid = seg.pos + maxOf(left / 2, keep)
        if (mid > seg.end) return null
        val n = Seg(mid, seg.end, mid)
        seg.end = mid - 1
        n
    }

    /** Wait before retry number [attempt]: 2 s, 4 s, 8 s ... up to a minute. */
    fun backoffMs(attempt: Int): Long = (2000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)

    /** True when the ranges cover 0..total-1 exactly once, with no gaps and no overlaps. */
    fun covers(segs: List<Seg>, total: Long): Boolean {
        if (total == 0L) return true
        var at = 0L
        segs.sortedBy { it.start }.forEach { s -> if (s.start != at) return false; at = s.end + 1 }
        return at == total
    }
}

/** One download, saved to disk so it survives app restarts and reboots. */
class DlTask(
    val id: Long, val url: String, @Volatile var name: String, @Volatile var mime: String,
    val ua: String, @Volatile var cookie: String, val referer: String, val created: Long
) {
    @Volatile var status = DlState.QUEUED
    @Volatile var reason = 0
    @Volatile var total = -1L
    @Volatile var etag = ""
    @Volatile var lastMod = ""
    @Volatile var ranges = false
    @Volatile var probed = false
    @Volatile var finalUrl = ""
    val segs = CopyOnWriteArrayList<Seg>()
    /** The ranges as they were when the data was last flushed to disk; what gets saved while running. */
    @Volatile var saved: List<LongArray>? = null
    @Volatile var uri = ""
    @Volatile var path = ""
    @Volatile var finished = 0L
    @Volatile var note = ""
    @Volatile var restarts = 0
    @Volatile var attempts = 0
    @Volatile var speed = 0.0
    @Volatile var conns = 0
    @Volatile var wasActive = false
    @Volatile var retryNow = false

    val done: Long get() = if (status == DlState.DONE && total >= 0) total else segs.sumOf { it.got }
    /** Can this download continue from where it stopped? Unknown until the site answered once. */
    val resumable: Boolean get() = !probed || ranges

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("name", name); put("mime", mime); put("ua", ua); put("cookie", cookie)
        put("ref", referer); put("created", created); put("status", status); put("reason", reason); put("total", total)
        put("etag", etag); put("lastMod", lastMod); put("ranges", ranges); put("probed", probed); put("finalUrl", finalUrl)
        put("uri", uri); put("path", path); put("finished", finished); put("note", note); put("restarts", restarts)
        val a = JSONArray()
        (saved ?: segs.map { longArrayOf(it.start, it.end, it.pos) }).forEach { s -> a.put(JSONArray().put(s[0]).put(s[1]).put(s[2])) }
        put("segs", a)
    }

    companion object {
        fun fromJson(o: JSONObject): DlTask = DlTask(
            o.getLong("id"), o.getString("url"), o.optString("name", "download"), o.optString("mime"),
            o.optString("ua"), o.optString("cookie"), o.optString("ref"), o.optLong("created")
        ).apply {
            status = o.optInt("status", DlState.PAUSED); reason = o.optInt("reason"); total = o.optLong("total", -1)
            etag = o.optString("etag"); lastMod = o.optString("lastMod"); ranges = o.optBoolean("ranges")
            probed = o.optBoolean("probed"); finalUrl = o.optString("finalUrl"); uri = o.optString("uri")
            path = o.optString("path"); finished = o.optLong("finished"); note = o.optString("note"); restarts = o.optInt("restarts")
            o.optJSONArray("segs")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { s -> segs.add(Seg(s.getLong(0), s.getLong(1), s.getLong(2))) } }
        }

        fun listToJson(list: Collection<DlTask>, boot: Int): String =
            JSONObject().put("v", 1).put("boot", boot).put("tasks", JSONArray().apply { list.forEach { put(it.toJson()) } }).toString()

        /** Reads the saved list. Downloads that were running when the app died become "Stopped" and are marked to continue. */
        fun listFromJson(s: String): Pair<List<DlTask>, Int> {
            val o = JSONObject(s)
            val a = o.optJSONArray("tasks") ?: JSONArray()
            val out = (0 until a.length()).mapNotNull { runCatching { fromJson(a.getJSONObject(it)) }.getOrNull() }
            out.forEach { t ->
                val active = t.status == DlState.RUNNING || t.status == DlState.QUEUED ||
                    (t.status == DlState.PAUSED && (t.reason == DlWhy.RETRY || t.reason == DlWhy.NETWORK))
                if (active) { t.wasActive = true; t.status = DlState.PAUSED; t.reason = DlWhy.INTERRUPTED }
            }
            return out to o.optInt("boot", -1)
        }
    }
}

/**
 * One run of a download, until it is done, paused, or hits a problem. Blocking: call it on its own thread.
 * Several connections write straight into their own part of one file, so nothing is merged or copied at the end.
 */
class DlRun(
    private val t: DlTask,
    private val part: File,
    private val cookieFor: (String) -> String? = { null },
    maxConn: Int = DlMath.MAX_CONN,
    private val onTick: () -> Unit = {},
    private val onSave: () -> Unit = {},
) {
    sealed class End {
        object Done : End()
        object Stopped : End()
        class Retry(val why: String) : End()
        class Failed(val code: Int) : End()
    }

    @Volatile private var stop = false
    @Volatile private var retry = false
    @Volatile private var changed = false
    @Volatile private var fatal = 0
    @Volatile private var lastErr = ""
    @Volatile private var limit = maxConn.coerceIn(1, DlMath.MAX_CONN)
    /** Bytes received in this run. */
    val bytes = AtomicLong()
    private val live = AtomicInteger()
    private val open = CopyOnWriteArrayList<HttpURLConnection>()
    private lateinit var ch: FileChannel

    /** Pause: stop reading now, keep what already arrived. retry = true when the network dropped (comes back by itself). */
    fun stop(retry: Boolean = false) {
        this.retry = retry; stop = true
        open.forEach { runCatching { it.disconnect() } }
    }

    fun execute(): End {
        part.parentFile?.mkdirs()
        return try {
            RandomAccessFile(part, "rw").use { raf -> ch = raf.channel; loop(raf) }
        } catch (e: IOException) {
            if (stop) End.Stopped else if (isDiskFull(e)) End.Failed(DlWhy.DISK_FULL) else End.Failed(DlWhy.CANT_SAVE)
        } finally { t.speed = 0.0; t.conns = 0 }
    }

    private fun loop(raf: RandomAccessFile): End {
        while (true) {
            if (stop) return stopped()
            var first: HttpURLConnection? = null
            if (!t.probed || t.segs.isEmpty() || !t.ranges) {
                if (t.probed && !t.ranges && t.segs.sumOf { it.got } > 0) { t.note = "This site can't resume, so it started over"; t.restarts++ }
                raf.setLength(0); t.segs.clear(); t.saved = null
                val c = try { connect(t.url, "bytes=0-", null) } catch (e: IOException) { return if (stop) stopped() else End.Retry(e.message ?: "no connection") }
                val code = try { c.responseCode } catch (e: IOException) { drop(c); return if (stop) stopped() else End.Retry(e.message ?: "") }
                when (code) {
                    206 -> {
                        val cr = DlMath.contentRange(c.getHeaderField("Content-Range"))
                        t.ranges = true
                        t.total = cr?.get(2) ?: -1L
                    }
                    200 -> { t.ranges = false; t.total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L }
                    416 -> {
                        val cr = DlMath.contentRange(c.getHeaderField("Content-Range")); drop(c)
                        if (cr != null && cr[2] == 0L) { t.ranges = false; t.total = 0; t.probed = true; t.segs.add(Seg(0, -1, 0)); return finish() }
                        return End.Failed(416)
                    }
                    else -> { drop(c); return httpEnd(code) }
                }
                t.etag = c.getHeaderField("ETag").orEmpty()
                t.lastMod = c.getHeaderField("Last-Modified").orEmpty()
                if (t.mime.isBlank()) t.mime = c.contentType.orEmpty()
                t.finalUrl = c.url.toString()
                t.probed = true
                t.segs.addAll(if (t.ranges && t.total > 0) DlMath.plan(t.total, minOf(limit, DlMath.conns(t.total)))
                    else listOf(Seg(0, if (t.total >= 0) t.total - 1 else Long.MAX_VALUE, 0)))
                t.segs.first().busy = true
                first = c
                save()
            }
            changed = false
            val end = work(first)
            if (changed && !stop) {
                if (++t.restarts > 3) return End.Failed(DlWhy.CHANGED)
                t.note = "The file changed on the site, so it started over"
                t.probed = false
                continue
            }
            return end
        }
    }

    private fun stopped(): End = if (retry) End.Retry("network changed") else End.Stopped

    private fun work(first: HttpURLConnection?): End {
        val left = t.segs.filter { !it.finished }.sumOf { if (it.left == Long.MAX_VALUE) Long.MAX_VALUE / 8 else it.left }
        val n = if (!t.ranges || t.total < 0) 1 else minOf(limit, DlMath.conns(left)).coerceAtLeast(1)
        if (t.segs.all { it.finished }) return finish()
        val workers = (0 until n).map { i -> Thread({ worker(if (i == 0) first else null) }, "dl-${t.id}-$i").apply { start() } }
        var lastTick = System.nanoTime(); var lastSave = lastTick; var lastBytes = 0L
        while (workers.any { it.isAlive }) {
            workers.firstOrNull { it.isAlive }?.join(200)
            val now = System.nanoTime()
            if (now - lastTick >= 500_000_000L) {
                val b = bytes.get()
                val inst = (b - lastBytes) * 1e9 / (now - lastTick)
                // moving average: steady numbers instead of jumpy ones
                t.speed = if (t.speed <= 0.0) inst else t.speed * 0.75 + inst * 0.25
                t.conns = live.get()
                lastBytes = b; lastTick = now
                onTick()
            }
            if (now - lastSave >= 3_000_000_000L) { save(); lastSave = now }
        }
        save()
        if (stop) return stopped()
        if (changed) return End.Stopped
        if (fatal != 0) return End.Failed(fatal)
        if (!t.segs.all { it.finished }) return End.Retry(lastErr.ifBlank { "connection lost" })
        return finish()
    }

    /** Last check: every byte arrived once and the file is exactly the size the site said. */
    private fun finish(): End {
        val got = t.segs.sumOf { it.got }
        if (t.total < 0) t.total = got
        if (got != t.total || !DlMath.covers(t.segs, t.total)) return End.Failed(DlWhy.SIZE)
        if (ch.size() > t.total) ch.truncate(t.total)
        if (ch.size() != t.total) return End.Failed(DlWhy.SIZE)
        ch.force(true)
        t.saved = null
        return End.Done
    }

    /** Remember the progress, but only bytes that are really on disk. */
    private fun save() {
        val snap = t.segs.map { longArrayOf(it.start, it.end, it.pos) }
        runCatching { ch.force(false) }
        t.saved = snap
        onSave()
    }

    private fun claim(): Seg? = synchronized(t) {
        t.segs.firstOrNull { !it.busy && !it.finished }?.let { it.busy = true; return it }
        if (!t.ranges || t.total < 0) return null
        val victim = t.segs.filter { it.busy && !it.finished }.maxByOrNull { it.left } ?: return null
        val s = DlMath.split(victim) ?: return null
        s.busy = true
        t.segs.add(s)
        s
    }

    private fun worker(first: HttpURLConnection?) {
        var conn = first
        var fails = 0
        live.incrementAndGet()
        try {
            while (!stop && !changed && fatal == 0) {
                val seg = if (conn != null) t.segs.first() else claim() ?: break
                val before = seg.pos
                var quit = false
                try {
                    quit = fetch(seg, conn)
                    fails = 0
                } catch (e: IOException) {
                    lastErr = e.message ?: "connection lost"
                    if (seg.pos > before) fails = 0 else fails++
                } finally {
                    conn = null
                    seg.busy = false
                }
                if (quit || stop || changed || fatal != 0) break
                if (fails >= 3) break
                if (fails > 0) sleep(minOf(500L * fails, 2000L))
            }
        } finally { live.decrementAndGet() }
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { } }

    /** Download one range. Returns true when this connection should stop (the site wants fewer connections). */
    private fun fetch(seg: Seg, given: HttpURLConnection?): Boolean {
        if (seg.finished) return false
        // the last byte this answer will send: when the range ends there too, the answer is fully read and the connection is reused
        val askedEnd = if (given != null) Long.MAX_VALUE else seg.end
        val c = given ?: run {
            val range = "bytes=${seg.pos}-" + if (seg.end == Long.MAX_VALUE) "" else "${seg.end}"
            connect(t.finalUrl.ifBlank { t.url }, range, validator())
        }
        var eof = false
        try {
            if (given == null) {
                when (val code = c.responseCode) {
                    206 -> {
                        val cr = DlMath.contentRange(c.getHeaderField("Content-Range"))
                        if (cr != null && cr[2] >= 0 && t.total >= 0 && cr[2] != t.total) { changed = true; return true }
                        if (cr != null && cr[0] != seg.pos) throw IOException("wrong range")
                    }
                    200 -> {
                        if (t.ranges && seg.pos == 0L && t.segs.size == 1 && validator() == null) { /* full file, fine */ }
                        else { changed = true; return true } // the site sent the whole file again: it changed
                    }
                    416 -> { if (seg.finished) return false; changed = true; return true }
                    429, 503 -> {
                        // too many connections for this site: this one steps back, the others carry on
                        if (live.get() > 1) { limit = (live.get() - 1).coerceAtLeast(1); return true }
                        throw IOException("the site is busy")
                    }
                    401, 403, 404, 410 -> {
                        if (t.finalUrl.isNotBlank() && t.finalUrl != t.url) { t.finalUrl = ""; throw IOException("link expired") }
                        fatal = code; return true
                    }
                    in 400..499 -> { fatal = code; return true }
                    else -> throw IOException("HTTP $code")
                }
            }
            eof = copy(c.inputStream, seg)
            if (eof) {
                if (seg.end == Long.MAX_VALUE) synchronized(seg) { seg.end = seg.pos - 1; t.total = seg.pos }
                else if (!seg.finished) throw IOException("connection cut short")
            }
            return false
        } finally {
            open.remove(c)
            // a fully read answer goes back to the pool and is reused; a cut one is closed
            // (a range that was split, or a cut answer, is closed at once so no extra bytes are pulled in)
            if (eof || (seg.finished && askedEnd == seg.end)) runCatching { c.inputStream.close() } else drop(c)
        }
    }

    /** Reads into a big buffer and writes it in one go at the right place in the file. True = the site finished sending. */
    private fun copy(input: InputStream, seg: Seg): Boolean {
        val batch = ByteBuffer.allocate(BATCH)
        val arr = batch.array()
        var eof = false
        try {
            while (!stop && !changed && fatal == 0) {
                val n = input.read(arr, batch.position(), minOf(READ, batch.remaining()))
                if (n < 0) { eof = true; break }
                batch.position(batch.position() + n)
                if (batch.remaining() < READ / 4 && !flush(batch, seg)) break
            }
        } finally {
            flush(batch, seg)
        }
        return eof
    }

    /** Writes the buffer, never past the end of the range. False when the range is complete. */
    private fun flush(batch: ByteBuffer, seg: Seg): Boolean = synchronized(seg) {
        val have = batch.position().toLong()
        if (have > 0) {
            val allow = if (seg.end == Long.MAX_VALUE) have else minOf(have, seg.end - seg.pos + 1).coerceAtLeast(0)
            if (allow > 0) {
                batch.flip(); batch.limit(allow.toInt())
                var at = seg.pos
                try {
                    while (batch.hasRemaining()) at += ch.write(batch, at)
                } catch (e: IOException) {
                    fatal = if (isDiskFull(e)) DlWhy.DISK_FULL else DlWhy.CANT_SAVE
                    throw e
                }
                seg.pos += allow
                bytes.addAndGet(allow)
            }
            batch.clear()
        }
        !seg.finished
    }

    private fun validator(): String? {
        if (!t.ranges) return null
        return t.etag.takeIf { it.isNotBlank() && !it.startsWith("W/") } ?: t.lastMod.takeIf { it.isNotBlank() }
    }

    private fun httpEnd(code: Int): End = when (code) {
        408, 429, in 500..599 -> End.Retry("HTTP $code")
        else -> End.Failed(code)
    }

    private fun drop(c: HttpURLConnection) { open.remove(c); runCatching { c.disconnect() } }

    /** Opens a request, following redirects (also http to https), with the page's cookies, user agent and referrer. */
    private fun connect(start: String, range: String?, ifRange: String?): HttpURLConnection {
        var u = URL(start)
        var hops = 0
        while (true) {
            if (stop) throw IOException("stopped")
            val c = (u.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = false
                // no gzip: byte ranges and sizes must match the real file
                setRequestProperty("Accept-Encoding", "identity")
                if (t.ua.isNotBlank()) setRequestProperty("User-Agent", t.ua)
                if (t.referer.isNotBlank()) setRequestProperty("Referer", t.referer)
                val ck = runCatching { cookieFor(u.toString()) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: t.cookie.takeIf { it.isNotBlank() && u.host == runCatching { URL(t.url).host }.getOrNull() }
                if (ck != null) setRequestProperty("Cookie", ck)
                range?.let { setRequestProperty("Range", it) }
                ifRange?.let { setRequestProperty("If-Range", it) }
            }
            open.add(c)
            val code = try { c.responseCode } catch (e: IOException) { drop(c); throw e }
            if (code in REDIRECTS && hops < 10) {
                val loc = c.getHeaderField("Location"); drop(c)
                if (loc.isNullOrBlank()) throw IOException("redirect with no address")
                u = URL(u, loc); hops++
                continue
            }
            return c
        }
    }

    companion object {
        private const val READ = 64 * 1024
        private const val BATCH = 512 * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        fun isDiskFull(e: IOException): Boolean = (e.message ?: "").let { it.contains("ENOSPC") || it.contains("No space", true) }
    }
}
