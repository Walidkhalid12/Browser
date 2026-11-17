package com.khalid.browser

/**
 * 1.0.4: the ad blocker's engine. It reads Adblock Plus / uBlock Origin style filter lists (EasyList,
 * EasyPrivacy, AdGuard Mobile Ads, Peter Lowe's list, EasyList Cookie) and answers two questions fast:
 * "block this request?" and "which page elements should be hidden on this site?".
 *
 * How it stays fast on a phone:
 * - Plain ad servers ("||ads.example.com^") go in one hash set, checked by walking the host's parents.
 * - Every other rule is filed under one word from its pattern (the rarest one), so a request only looks at
 *   rules whose word appears in its address, a handful instead of tens of thousands.
 * - Patterns are matched with a small wildcard matcher (no regular expressions for normal rules).
 * Pure Kotlin, no Android, so it is unit tested and timed on a computer.
 */
object RT {
    const val SCRIPT = 1; const val IMAGE = 2; const val CSS = 4; const val OBJECT = 8; const val XHR = 16
    const val SUBDOC = 32; const val PING = 64; const val MEDIA = 128; const val FONT = 256; const val OTHER = 512
    const val WS = 1024
    /** Every request type a page loads (not page navigations or pop-ups). */
    const val ANY = 2047
    /** The whole page (a navigation of the tab). */
    const val DOCUMENT = 1 shl 12
    /** A new window. */
    const val POPUP = 1 shl 13

    /** A good guess at what a request is, from its address and Accept header (WebView doesn't say). */
    fun guess(url: String, accept: String?, mainFrame: Boolean): Int {
        if (mainFrame) return DOCUMENT
        val a = accept.orEmpty()
        if (a.startsWith("text/html")) return SUBDOC
        if (a.startsWith("text/css")) return CSS
        if (a.startsWith("image/")) return IMAGE
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, q).substringAfterLast('/').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> SCRIPT
            "css" -> CSS
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif" -> IMAGE
            "mp4", "webm", "m3u8", "mp3", "ogg", "m4a", "ts", "mpd" -> MEDIA
            "woff", "woff2", "ttf", "otf", "eot" -> FONT
            "html", "htm" -> SUBDOC
            else -> OTHER
        }
    }
}

class NetRule(
    @JvmField val pat: String,
    @JvmField val flags: Int,
    @JvmField val types: Int,
    @JvmField val inc: Array<String>?,
    @JvmField val exc: Array<String>?,
    @JvmField val regex: Regex?,
) {
    companion object {
        const val HOST = 1; const val START = 2; const val END = 4; const val EXCEPTION = 8; const val IMPORTANT = 16
        const val P3 = 32; const val P1 = 64; const val CASE = 128; const val PURE_HOST = 256
        // page-level exceptions
        const val EHIDE = 1 shl 10; const val GHIDE = 1 shl 11; const val SHIDE = 1 shl 12; const val GBLOCK = 1 shl 13
    }
}

class AdEngine {
    // ---- network ----
    private var hostSet = HashSet<String>()
    private var block = HashMap<Int, Array<NetRule>>()
    private var allow = HashMap<Int, Array<NetRule>>()
    private var important = HashMap<Int, Array<NetRule>>()
    private var pageExc: Array<NetRule> = emptyArray()
    // ---- cosmetic ----
    private val genericHigh = LinkedHashSet<String>()
    /** ".name"/"#name" -> its selector (a String, the same object as the key when they are equal) or several (ArrayList). */
    private val genericByKey = HashMap<String, Any>()
    private val specific = HashMap<String, ArrayList<String>>()
    private val specificExc = HashMap<String, HashSet<String>>()
    private val genericExc = HashSet<String>()
    private val selectorExcluded = HashMap<String, Array<String>>()

    /** Rules kept, for the "N rules" line and tests. */
    var networkRules = 0; private set
    var cosmeticRules = 0; private set
    var skipped = 0; private set
    private var highCss: String? = null
    private var highExcl: List<String>? = null

    // build-time holding area
    private val pending = ArrayList<Pair<NetRule, List<String>>>()
    private val badfilter = HashSet<String>()
    private val lines = ArrayList<String>()
    private var curPriv = false
    private var privHosts = HashSet<String>()

    /** Add a whole list (the text of a filter list file). */
    fun addList(text: String, privacy: Boolean = false) { text.lineSequence().forEach { addLine(it, privacy) } }

    /** [privacy]: a tracker list (EasyPrivacy). Its rules block loads in pages but never a page or a pop-up. */
    fun addLine(raw: String, privacy: Boolean = false) {
        val line = raw.trim()
        if (line.length < 3 || line[0] == '!' || line[0] == '[' || line[0] == '#' && !line.startsWith("##") && !line.startsWith("#@#")) return
        if (line.endsWith("\$badfilter") || line.contains(",badfilter") || line.contains("\$badfilter,")) {
            badfilter.add(line.replace(",badfilter", "").replace("\$badfilter,", "\$").removeSuffix("\$badfilter")); return
        }
        lines.add(if (privacy) "\u0001" + line else line)
    }

    /** Turn everything added into the fast lookup structures. Call once, after the last addList. */
    fun build(): AdEngine {
        for (l in lines) {
            val priv = l[0] == '\u0001'
            val x = if (priv) l.substring(1) else l
            if (x !in badfilter) { curPriv = priv; parse(x) }
        }
        lines.clear(); lines.trimToSize()
        // Count how often each candidate word is used, then file every rule under its rarest word.
        val freq = HashMap<String, Int>()
        for ((_, toks) in pending) for (t in toks) freq[t] = (freq[t] ?: 0) + 1
        val b = HashMap<Int, ArrayList<NetRule>>(); val a = HashMap<Int, ArrayList<NetRule>>(); val im = HashMap<Int, ArrayList<NetRule>>()
        for ((r, toks) in pending) {
            var best: String? = null; var bestScore = Int.MAX_VALUE
            for (t in toks) {
                var s = (freq[t] ?: 0) * 8 - t.length
                if (t in BAD_TOKENS) s += 1_000_000
                if (s < bestScore) { bestScore = s; best = t }
            }
            val key = best?.hashCode() ?: 0
            val m = when { r.flags and NetRule.EXCEPTION != 0 -> a; r.flags and NetRule.IMPORTANT != 0 -> im; else -> b }
            m.getOrPut(key) { ArrayList(2) }.add(r)
        }
        pending.clear(); pending.trimToSize(); badfilter.clear()
        hostHashes = hashes(hostSet); privHashes = hashes(privHosts); hostTotal = hostSet.size + privHosts.size
        hostSet = HashSet(0); privHosts = HashSet(0)
        block = HashMap<Int, Array<NetRule>>(b.size * 2).apply { b.forEach { (k, v) -> put(k, v.toTypedArray()) } }
        allow = HashMap<Int, Array<NetRule>>(a.size * 2).apply { a.forEach { (k, v) -> put(k, v.toTypedArray()) } }
        important = HashMap<Int, Array<NetRule>>(im.size * 2).apply { im.forEach { (k, v) -> put(k, v.toTypedArray()) } }
        genericByKey.values.forEach { (it as? ArrayList<*>)?.trimToSize() }; specific.values.forEach { it.trimToSize() }
        return this
    }

    /** Rules filed under no word at all (checked for every request); kept small. */
    val untokenized: Int get() = (block[0]?.size ?: 0) + (allow[0]?.size ?: 0) + (important[0]?.size ?: 0)
    val hostCount: Int get() = hostTotal
    private var hostTotal = 0
    // Ad servers as sorted 64-bit fingerprints: 8 bytes each instead of ~150 for a set of strings.
    private var hostHashes = LongArray(0)
    private var privHashes = LongArray(0)
    private fun hashes(set: Set<String>): LongArray {
        val a = LongArray(set.size); var i = 0
        for (h in set) a[i++] = fnv(h, 0, h.length)
        a.sort(); return a
    }

    // =================================================================================== parsing
    private fun parse(line: String) {
        if (line.contains("\$\$") || line.contains("\$@\$")) { skipped++; return } // AdGuard HTML filtering
        var i = line.indexOf('#')
        while (i >= 0) {
            when {
                line.startsWith("##", i) -> { cosmetic(line.substring(0, i), line.substring(i + 2), false); return }
                line.startsWith("#@#", i) -> { cosmetic(line.substring(0, i), line.substring(i + 3), true); return }
                line.startsWith("#?#", i) || line.startsWith("#$#", i) || line.startsWith("#%#", i) ||
                    line.startsWith("#@?#", i) || line.startsWith("#@$#", i) || line.startsWith("#@%#", i) || line.startsWith("#$?#", i) -> { skipped++; return }
            }
            i = line.indexOf('#', i + 1)
        }
        network(line)
    }

    private fun cosmetic(domains: String, sel0: String, exception: Boolean) {
        val sel = sel0.trim()
        if (sel.isEmpty() || sel.startsWith("+js(") || sel.startsWith("^") || PROCEDURAL.any { sel.contains(it) } ||
            sel.contains('{') || sel.contains('}') || sel.contains("</") || sel.length > 400) { skipped++; return }
        val inc = ArrayList<String>(); val exc = ArrayList<String>()
        if (domains.isNotEmpty()) for (d in domains.split(',')) {
            val t = d.trim().lowercase(); if (t.isEmpty()) continue
            if (t.startsWith("~")) exc.add(t.substring(1)) else inc.add(t)
        }
        cosmeticRules++
        if (exception) {
            if (inc.isEmpty()) genericExc.add(sel) else for (d in inc) specificExc.getOrPut(d) { HashSet() }.add(sel)
            return
        }
        if (inc.isNotEmpty()) {
            for (d in inc) specific.getOrPut(d) { ArrayList(4) }.add(sel)
            for (d in exc) specificExc.getOrPut(d) { HashSet() }.add(sel) // "~sub.site.com,site.com##x"
            return
        }
        if (exc.isNotEmpty()) selectorExcluded[sel] = exc.toTypedArray()
        val key = genericKey(sel)
        if (key == null) { genericHigh.add(sel); return }
        when (val cur = genericByKey[key]) {
            null -> genericByKey[key] = if (sel == key) key else sel
            is String -> if (cur != sel) genericByKey[key] = arrayListOf(cur, sel)
            is ArrayList<*> -> @Suppress("UNCHECKED_CAST") (cur as ArrayList<String>).let { if (sel !in it) it.add(sel) }
        }
    }

    /** ".ad-box" for ".ad-box > a", "#banner" for "#banner", null when the selector has no leading class or id. */
    private fun genericKey(sel: String): String? {
        if (sel.length < 2 || (sel[0] != '.' && sel[0] != '#')) return null
        var e = 1
        while (e < sel.length) { val c = sel[e]; if (c.isLetterOrDigit() || c == '-' || c == '_') e++ else break }
        if (e == 1) return null
        if (e < sel.length && sel[e] == '\\') return null
        return sel.substring(0, e)
    }

    private fun network(line0: String) {
        var s = line0
        var flags = 0
        if (s.startsWith("@@")) { flags = flags or NetRule.EXCEPTION; s = s.substring(2) }
        // options
        var opts: String? = null
        val rk = if (s.startsWith("/") && s.length > 2) s.lastIndexOf("/\$") else -1
        if (rk > 0 && OPT_CHARS.matches(s.substring(rk + 2))) { opts = s.substring(rk + 2); s = s.substring(0, rk + 1) }
        else if (!(s.startsWith("/") && s.endsWith("/") && s.length > 2)) {
            val k = s.lastIndexOf('$')
            if (k >= 0 && k < s.length - 1 && OPT_CHARS.matches(s.substring(k + 1))) { opts = s.substring(k + 1); s = s.substring(0, k) }
        }
        var types = 0; var notTypes = 0; var inc: ArrayList<String>? = null; var exc: ArrayList<String>? = null
        var pageFlags = 0
        if (opts != null) for (o0 in opts.split(',')) {
            val o = o0.trim(); if (o.isEmpty()) continue
            val neg = o.startsWith("~"); val name = (if (neg) o.substring(1) else o).substringBefore('=').lowercase()
            val value = o.substringAfter('=', "")
            val t = TYPE_BITS[name]
            when {
                t != null -> if (neg) notTypes = notTypes or t else types = types or t
                name == "third-party" || name == "3p" || name == "strict3p" -> flags = flags or (if (neg) NetRule.P1 else NetRule.P3)
                name == "first-party" || name == "1p" || name == "strict1p" -> flags = flags or (if (neg) NetRule.P3 else NetRule.P1)
                name == "domain" || name == "from" -> for (d in value.split('|')) {
                    val x = d.trim().lowercase(); if (x.isEmpty()) continue
                    if (x.startsWith("~")) (exc ?: ArrayList<String>().also { exc = it }).add(x.substring(1))
                    else (inc ?: ArrayList<String>().also { inc = it }).add(x)
                }
                name == "important" -> flags = flags or NetRule.IMPORTANT
                name == "match-case" -> flags = flags or NetRule.CASE
                name == "all" -> types = types or RT.ANY or RT.DOCUMENT or RT.POPUP
                name == "popunder" -> types = types or RT.POPUP
                name == "elemhide" || name == "ehide" -> pageFlags = pageFlags or NetRule.EHIDE
                name == "generichide" || name == "ghide" -> pageFlags = pageFlags or NetRule.GHIDE
                name == "specifichide" || name == "shide" -> pageFlags = pageFlags or NetRule.SHIDE
                name == "genericblock" -> pageFlags = pageFlags or NetRule.GBLOCK
                name == "redirect" || name == "empty" || name == "mp4" -> {} // block (we answer empty anyway)
                name == "redirect-rule" -> { skipped++; return }
                IGNORED_OPTS.contains(name) -> {}
                else -> { skipped++; return } // csp, removeparam, replace, header, denyallow... not supported: skip the rule
            }
        }
        if (pageFlags != 0) {
            if (flags and NetRule.EXCEPTION == 0) { skipped++; return }
            if (types == 0 && notTypes == 0) { addPattern(s, flags or pageFlags, 0, inc, exc, pageLevel = true); return }
        }
        val t = if (types != 0) types and notTypes.inv() else RT.ANY and notTypes.inv()
        if (t == 0) { skipped++; return }
        addPattern(s, flags, t, inc, exc, pageLevel = false)
    }

    private fun addPattern(p0: String, flags0: Int, types: Int, inc: List<String>?, exc: List<String>?, pageLevel: Boolean) {
        var p = p0; var flags = flags0
        var regex: Regex? = null
        if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) {
            regex = runCatching { Regex(p.substring(1, p.length - 1), if (flags and NetRule.CASE != 0) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
                ?: run { skipped++; return }
            p = ""
        } else {
            if (p.startsWith("||")) { flags = flags or NetRule.HOST; p = p.substring(2) }
            else if (p.startsWith("|")) { flags = flags or NetRule.START; p = p.substring(1) }
            if (p.endsWith("|")) { flags = flags or NetRule.END; p = p.dropLast(1) }
            while (p.contains("**")) p = p.replace("**", "*")
            if (p.startsWith("*")) { p = p.substring(1); flags = flags and (NetRule.HOST or NetRule.START).inv() }
            if (p.endsWith("*")) { p = p.dropLast(1); flags = flags and NetRule.END.inv() }
            if (flags and NetRule.CASE == 0) p = p.lowercase()
        }
        if (p.isEmpty() && regex == null && inc.isNullOrEmpty() && !pageLevel) { skipped++; return }
        if (p.isNotEmpty() && p.length < 3 && flags and (NetRule.HOST or NetRule.START) == 0 && inc.isNullOrEmpty()) { skipped++; return }
        val pure = flags and NetRule.HOST != 0 && HOSTLIKE.matches(p)
        if (pure && !curPriv) flags = flags or NetRule.PURE_HOST
        networkRules++
        // A plain ad server with no options: the hash set.
        if (pure && !pageLevel && flags and (NetRule.EXCEPTION or NetRule.IMPORTANT or NetRule.P1 or NetRule.P3 or NetRule.CASE) == 0 &&
            types == RT.ANY && inc == null && exc == null) {
            (if (curPriv) privHosts else hostSet).add(p.removeSuffix("^")); return
        }
        val r = NetRule(p, flags, types, inc?.toTypedArray(), exc?.toTypedArray(), regex)
        if (pageLevel) { pageExc += r; return }
        pending.add(r to (if (regex != null) emptyList() else tokens(p, flags)))
    }

    /** Whole words in a pattern that must also appear as whole words in a matching address. */
    private fun tokens(p: String, flags: Int): List<String> {
        val out = ArrayList<String>(4)
        var i = 0; val n = p.length
        while (i < n) {
            if (!isTok(p[i])) { i++; continue }
            val a = i
            while (i < n && isTok(p[i])) i++
            val okLeft = if (a == 0) flags and (NetRule.HOST or NetRule.START) != 0 else p[a - 1] != '*'
            val okRight = if (i == n) flags and NetRule.END != 0 else p[i] != '*'
            if (okLeft && okRight && i - a >= 2) out.add(p.substring(a, i).lowercase())
        }
        return out
    }

    // =================================================================================== matching
    /**
     * The rule that blocks [url] loaded by a page on [pageHost] as request type [type], or null to let it load.
     * Exceptions (@@) win over normal rules; \$important rules win over exceptions.
     */
    fun match(url: String, pageHost: String, type: Int): NetRule? {
        val lu = url.lowercase()
        val hs = lu.indexOf("://").let { if (it < 0) 0 else it + 3 }
        var he = hs
        while (he < lu.length) { val c = lu[he]; if (c == '/' || c == ':' || c == '?' || c == '#') break; he++ }
        if (he == hs) return null
        val host = lu.substring(hs, he).let { h -> h.substringAfterLast('@') }
        val third = pageHost.isNotEmpty() && baseDomain(host) != baseDomain(pageHost.lowercase())
        val page = pageHost.lowercase()
        val ctx = Ctx(lu, url, hs, he, page, third, type)
        find(important, ctx)?.let { return it }
        if (pageExc.isNotEmpty() && pageFlags(page, NetRule.GBLOCK)) return null // generic blocking off: rare, simple
        var hit: NetRule? = if (type and (RT.ANY or RT.POPUP or RT.DOCUMENT) != 0 && hostHit(hostHashes, host)) HOST_RULE
            else if (type and RT.ANY != 0 && hostHit(privHashes, host)) HOST_RULE else null
        if (hit == null) hit = find(block, ctx)
        if (hit == null) return null
        if (find(allow, ctx) != null) return null
        return hit
    }

    /** True when [url] is an ad or tracker address (an ad server or a rule for pop-ups/pages), for pop-ups and forced redirects. */
    fun isAdNavigation(url: String, pageHost: String): Boolean = match(url, pageHost, RT.POPUP) != null

    /** The host or any parent domain ("a.b.doubleclick.net" -> "doubleclick.net") is a listed ad server. */
    private fun hostHit(set: LongArray, host0: String): Boolean {
        if (set.isEmpty()) return false
        val host = host0.trimEnd('.')
        var i = 0; val n = host.length
        while (true) {
            if (java.util.Arrays.binarySearch(set, fnv(host, i, n)) >= 0) return true
            val dot = host.indexOf('.', i); if (dot < 0) return false
            i = dot + 1
            if (host.indexOf('.', i) < 0) return false
        }
    }

    private class Ctx(val lu: String, val url: String, val hs: Int, val he: Int, val page: String, val third: Boolean, val type: Int)

    private fun find(map: HashMap<Int, Array<NetRule>>, c: Ctx): NetRule? {
        if (map.isEmpty()) return null
        map[0]?.let { for (r in it) if (check(r, c)) return r }
        val s = c.lu; val n = s.length
        var i = 0
        while (i < n) {
            if (!isTok(s[i])) { i++; continue }
            var h = 0; val a = i
            while (i < n && isTok(s[i])) { h = 31 * h + s[i].code; i++ }
            if (i - a < 2) continue
            val bucket = map[h] ?: continue
            for (r in bucket) if (check(r, c)) return r
        }
        return null
    }

    private fun check(r: NetRule, c: Ctx): Boolean {
        val f = r.flags
        if (r.types and c.type == 0) {
            // Pop-ups and forced redirects: a plain ad-server rule counts too.
            if (c.type and (RT.POPUP or RT.DOCUMENT) == 0 || f and NetRule.PURE_HOST == 0 || r.types != RT.ANY) return false
        }
        if (f and NetRule.P3 != 0 && !c.third) return false
        if (f and NetRule.P1 != 0 && c.third) return false
        if (!domainsOk(r.inc, r.exc, c.page)) return false
        return patternMatch(r, if (f and NetRule.CASE != 0) c.url else c.lu, c.hs, c.he)
    }

    private fun patternMatch(r: NetRule, s: String, hs: Int, he: Int): Boolean {
        r.regex?.let { return it.containsMatchIn(s) }
        val p = r.pat
        if (p.isEmpty()) return true
        val end = r.flags and NetRule.END != 0
        if (r.flags and NetRule.HOST != 0) {
            var i = hs
            while (i < he) {
                if (glob(p, s, i, end, false)) return true
                val d = s.indexOf('.', i); if (d < 0 || d >= he) return false
                i = d + 1
            }
            return false
        }
        if (r.flags and NetRule.START != 0) return glob(p, s, 0, end, false)
        return glob(p, s, 0, end, true)
    }

    // =================================================================================== cosmetic
    /** Page-level exception flags (no hiding, no generic hiding...) for a page on [host]. */
    fun pageFlags(host: String, flag: Int): Boolean {
        if (pageExc.isEmpty() || host.isEmpty()) return false
        val u = "https://$host/"
        val c = Ctx(u, u, 8, 8 + host.length, host, false, RT.DOCUMENT)
        for (r in pageExc) if (r.flags and flag != 0 && domainsOk(r.inc, r.exc, host) && patternMatch(r, u, c.hs, c.he)) return true
        return false
    }

    /** CSS for the start of a page on [host]: this site's own hiding rules plus the always-on generic ones. */
    fun pageCss(host0: String): String {
        val host = host0.lowercase().removeSuffix(".")
        if (pageFlags(host, NetRule.EHIDE)) return ""
        val exc = excFor(host)
        val sb = StringBuilder()
        if (!pageFlags(host, NetRule.SHIDE)) {
            val sels = ArrayList<String>()
            forEachSuffix(host) { k -> specific[k]?.let { for (s in it) if (s !in exc) sels.add(s) } }
            css(sels, sb)
        }
        if (!pageFlags(host, NetRule.GHIDE)) {
            if (exc.isEmpty()) {
                sb.append(highCss ?: StringBuilder().also { css(genericHigh.filter { it !in genericExc && it !in selectorExcluded }, it) }.toString().also { highCss = it })
                val ex = highExcl ?: genericHigh.filter { it in selectorExcluded && it !in genericExc }.also { highExcl = it }
                if (ex.isNotEmpty()) css(ex.filter { allowedOn(it, host) }, sb)
            } else css(genericHigh.filter { it !in exc && it !in genericExc && allowedOn(it, host) }, sb)
        }
        return sb.toString()
    }

    /** CSS for the classes and ids a page uses (".name" / "#name" separated by spaces). */
    fun genericCss(host0: String, names: String): String {
        val host = host0.lowercase()
        if (genericByKey.isEmpty() || pageFlags(host, NetRule.EHIDE) || pageFlags(host, NetRule.GHIDE)) return ""
        var exc: Set<String>? = null
        val sels = ArrayList<String>()
        for (n in names.split(' ')) {
            if (n.length < 2) continue
            val v = genericByKey[n] ?: continue
            if (exc == null) exc = excFor(host)
            if (v is String) { if (v !in exc && v !in genericExc && allowedOn(v, host)) sels.add(v) }
            else for (s in v as ArrayList<*>) { s as String; if (s !in exc && s !in genericExc && allowedOn(s, host)) sels.add(s) }
        }
        if (sels.isEmpty()) return ""
        return StringBuilder().also { css(sels, it) }.toString()
    }

    /** All class and id names that have a generic hiding rule (for tests and sizing). */
    val genericKeys: Int get() = genericByKey.size
    val genericAlways: Int get() = genericHigh.size

    private fun allowedOn(sel: String, host: String): Boolean {
        val ex = selectorExcluded[sel] ?: return true
        return ex.none { hostIs(host, it) }
    }

    private fun excFor(host: String): Set<String> {
        if (specificExc.isEmpty()) return emptySet()
        var out: HashSet<String>? = null
        forEachSuffix(host) { k -> specificExc[k]?.let { (out ?: HashSet<String>().also { o -> out = o }).addAll(it) } }
        return out ?: emptySet()
    }

    /** example.co.ma -> "example.co.ma", "co.ma", plus "example.*" style entity names. */
    private inline fun forEachSuffix(host: String, f: (String) -> Unit) {
        var h = host
        val base = baseDomain(host)
        val suffix = base.substringAfter('.')
        while (h.isNotEmpty()) {
            f(h)
            if (h.length > suffix.length + 1 && h.endsWith(".$suffix")) f(h.removeSuffix(suffix) + "*")
            val d = h.indexOf('.'); if (d < 0) break
            h = h.substring(d + 1)
        }
    }

    private fun css(sels: Collection<String>, sb: StringBuilder) {
        // A selector the web engine doesn't understand drops its whole rule, so the ones with
        // pseudo-classes (":has(") get a rule each and the plain ones go in groups.
        var inGroup = 0
        for (s in sels) {
            if (s.indexOf(':') >= 0) { sb.append(s).append(HIDE); continue }
            if (inGroup > 0) sb.append(',')
            sb.append(s); inGroup++
            if (inGroup == 24) { sb.append(HIDE); inGroup = 0 }
        }
        if (inGroup > 0) sb.append(HIDE)
    }

    companion object {
        private const val HIDE = "{display:none!important}\n"
        private val HOST_RULE = NetRule("", NetRule.HOST or NetRule.PURE_HOST, RT.ANY, null, null, null)
        private val OPT_CHARS = Regex("[~a-zA-Z0-9_\\-=|.,*:/\\[\\]%]+")
        private val HOSTLIKE = Regex("[a-z0-9.\\-]+\\^?")
        private val BAD_TOKENS = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "jpg", "png", "gif", "css", "min", "static", "cdn", "api")
        private val PROCEDURAL = listOf(":-abp-", ":has-text(", ":contains(", ":upward(", ":xpath(", ":matches-css", ":min-text-length(",
            ":remove(", ":style(", ":watch-attr(", ":matches-path(", ":others(", ":matches-attr(", ":matches-prop(", ":if(", ":if-not(",
            ":nth-ancestor(", ":remove-attr(", ":remove-class(", ":spath(", ":matches-media(", ":shadow")
        private val TYPE_BITS = mapOf(
            "script" to RT.SCRIPT, "image" to RT.IMAGE, "stylesheet" to RT.CSS, "css" to RT.CSS, "object" to RT.OBJECT,
            "object-subrequest" to RT.OBJECT, "xmlhttprequest" to RT.XHR, "xhr" to RT.XHR, "subdocument" to RT.SUBDOC,
            "frame" to RT.SUBDOC, "ping" to RT.PING, "beacon" to RT.PING, "media" to RT.MEDIA, "font" to RT.FONT,
            "other" to RT.OTHER, "websocket" to RT.WS, "document" to RT.DOCUMENT, "doc" to RT.DOCUMENT, "popup" to RT.POPUP,
        )
        private val IGNORED_OPTS = setOf("stealth", "cookie", "jsinject", "content", "urlblock", "network", "extension", "rewrite-noop", "webrtc", "genericblock")

        /** 64-bit FNV-1a fingerprint of s[from, to). */
        fun fnv(s: String, from: Int, to: Int): Long {
            var h = -0x340d631b7bdddcdbL
            for (k in from until to) { h = h xor s[k].code.toLong(); h *= 0x100000001b3L }
            return h
        }

        fun isTok(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%' || c in 'A'..'Z'
        private fun isSep(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        /** Wildcard match: '*' any text, '^' a separator or the end. [floating]: may start anywhere at or after [start]. */
        fun glob(p: String, s: String, start: Int, anchoredEnd: Boolean, floating: Boolean): Boolean {
            val n = s.length; val m = p.length
            var i = start; var j = 0
            var starJ = if (floating) 0 else -1; var starI = start
            while (true) {
                if (j == m) { if (!anchoredEnd || i == n) return true }
                else {
                    val c = p[j]
                    if (c == '*') { starJ = j + 1; starI = i; j++; continue }
                    if (c == '^') {
                        if (i < n && isSep(s[i])) { i++; j++; continue }
                        if (i == n) { j++; continue }
                    } else if (i < n && s[i] == c) { i++; j++; continue }
                }
                if (starJ < 0) return false
                starI++
                if (starI > n) return false
                i = starI; j = starJ
            }
        }

        private val SLD = setOf("co", "com", "net", "org", "gov", "ac", "edu", "or", "ne", "go", "gob", "mil", "nic", "press", "gen", "ltd", "plc", "me")

        /** The site part of a host: "a.b.example.com" -> "example.com", "x.example.co.ma" -> "example.co.ma". */
        fun baseDomain(h: String): String {
            val host = h.trimEnd('.')
            val last = host.lastIndexOf('.'); if (last <= 0) return host
            if (host[last + 1].isDigit() && host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }) return host
            val prev = host.lastIndexOf('.', last - 1)
            if (prev < 0) return host
            val sld = host.substring(prev + 1, last)
            val tld = host.substring(last + 1)
            if (tld.length == 2 && sld in SLD) {
                val p2 = host.lastIndexOf('.', prev - 1)
                return if (p2 < 0) host else host.substring(p2 + 1)
            }
            return host.substring(prev + 1)
        }

        /** True when [host] is [d] or a subdomain of it; "example.*" matches example on any ending. */
        fun hostIs(host: String, d: String): Boolean {
            if (d.endsWith(".*")) {
                val name = d.dropLast(2)
                val base = baseDomain(host)
                val label = base.substringBefore('.')
                val rest = host.removeSuffix(base).let { if (it.isEmpty()) label else it + label }
                return rest == name || rest.endsWith(".$name")
            }
            return host == d || (host.length > d.length && host.endsWith(d) && host[host.length - d.length - 1] == '.')
        }

        fun domainsOk(inc: Array<String>?, exc: Array<String>?, page: String): Boolean {
            if (inc != null) {
                if (page.isEmpty()) return false
                var any = false
                for (d in inc) if (hostIs(page, d)) { any = true; break }
                if (!any) return false
            }
            if (exc != null) for (d in exc) if (hostIs(page, d)) return false
            return true
        }
    }
}
