package com.khalid.browser

/**
 * Small pure-Kotlin QR code encoder (byte mode, error correction M, versions 1–40).
 * Follows ISO/IEC 18004 the same way Project Nayuki's reference encoder does. No libraries.
 * [encode] returns a square of modules: true = dark.
 */
object Qr {
    // index: 0 = L, 1 = M, 2 = Q, 3 = H
    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )
    private val NUM_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )
    private val FORMAT_BITS = intArrayOf(1, 0, 3, 2)

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }
    private fun dataCodewords(ver: Int, ecl: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ecl][ver] * NUM_BLOCKS[ecl][ver]

    /** Encode text as UTF-8 bytes. Returns null when it is too long for a QR code. */
    fun encode(text: String, ecl: Int = 1): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var ver = 1
        while (true) {
            val cc = if (ver <= 9) 8 else 16
            if (4 + cc + bytes.size * 8 <= dataCodewords(ver, ecl) * 8) break
            ver++
            if (ver > 40) return null
        }
        val cc = if (ver <= 9) 8 else 16
        val bits = ArrayList<Boolean>()
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((v ushr i) and 1 != 0) }
        put(4, 4); put(bytes.size, cc)
        for (b in bytes) put(b.toInt() and 0xFF, 8)
        val cap = dataCodewords(ver, ecl) * 8
        put(0, minOf(4, cap - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < cap) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
        val data = IntArray(bits.size / 8)
        for (i in bits.indices) if (bits[i]) data[i ushr 3] = data[i ushr 3] or (1 shl (7 - (i and 7)))
        return Matrix(ver, ecl).build(interleave(data, ver, ecl))
    }

    private fun mul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }
    private fun divisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in r.indices) {
                r[j] = mul(r[j], root)
                if (j + 1 < r.size) r[j] = r[j] xor r[j + 1]
            }
            root = mul(root, 0x02)
        }
        return r
    }
    private fun remainder(data: IntArray, div: IntArray): IntArray {
        val r = IntArray(div.size)
        for (b in data) {
            val f = b xor r[0]
            System.arraycopy(r, 1, r, 0, r.size - 1); r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor mul(div[i], f)
        }
        return r
    }
    private fun interleave(data: IntArray, ver: Int, ecl: Int): IntArray {
        val numBlocks = NUM_BLOCKS[ecl][ver]
        val eccLen = ECC_PER_BLOCK[ecl][ver]
        val raw = rawModules(ver) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val div = divisor(eccLen)
        val blocks = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val len = shortLen - eccLen + if (i < numShort) 0 else 1
            val dat = data.copyOfRange(k, k + len); k += len
            val ecc = remainder(dat, div)
            val block = IntArray(shortLen + 1)
            System.arraycopy(dat, 0, block, 0, dat.size)
            // short blocks get one placeholder so all blocks line up
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen)
            blocks.add(block)
        }
        val out = ArrayList<Int>(raw)
        for (i in 0..shortLen) for (j in blocks.indices) {
            if (i != shortLen - eccLen || j >= numShort) out.add(blocks[j][i])
        }
        return out.toIntArray()
    }

    private class Matrix(val ver: Int, val ecl: Int) {
        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }
        fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

        fun build(codewords: IntArray): Array<BooleanArray> {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            format(0); version()
            // data, in the zigzag order
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val up = ((right + 1) and 2) == 0
                    val y = if (up) size - 1 - vert else vert
                    if (!fn[y][x] && i < codewords.size * 8) {
                        m[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
            var best = 0; var bestScore = Int.MAX_VALUE
            for (mask in 0 until 8) {
                mask(mask); format(mask)
                val p = penalty()
                if (p < bestScore) { bestScore = p; best = mask }
                mask(mask)
            }
            mask(best); format(best)
            return m
        }

        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        fun alignPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val r = IntArray(n); r[0] = 6
            var p = size - 7
            for (i in n - 1 downTo 1) { r[i] = p; p -= step }
            return r
        }

        fun format(mask: Int) {
            val data = (FORMAT_BITS[ecl] shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun b(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, b(i))
            set(8, 7, b(6)); set(8, 8, b(7)); set(7, 8, b(8))
            for (i in 9 until 15) set(14 - i, 8, b(i))
            for (i in 0 until 8) set(size - 1 - i, 8, b(i))
            for (i in 8 until 15) set(8, size - 15 + i, b(i))
            set(8, size - 8, true)
        }

        fun version() {
            if (ver < 7) return
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3; val b = i / 3
                set(a, b, bit); set(b, a, bit)
            }
        }

        fun mask(k: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val inv = when (k) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inv && !fn[y][x]) m[y][x] = !m[y][x]
            }
        }

        /** Simplified standard penalty: runs, 2x2 blocks, finder-like runs, dark balance. */
        fun penalty(): Int {
            var p = 0
            fun at(x: Int, y: Int, horiz: Boolean) = if (horiz) m[y][x] else m[x][y]
            for (horiz in listOf(true, false)) for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    if (at(b, a, horiz) == at(b - 1, a, horiz)) { run++; if (run == 5) p += 3 else if (run > 5) p++ }
                    else run = 1
                }
                for (b in 0..size - 11) {
                    var f1 = true; var f2 = true
                    val pat = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
                    for (k in 0 until 11) {
                        if (at(b + k, a, horiz) != pat[k]) f1 = false
                        if (at(b + k, a, horiz) != pat[10 - k]) f2 = false
                    }
                    if (f1) p += 40; if (f2) p += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
            }
            var dark = 0
            for (row in m) for (c in row) if (c) dark++
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            p += k * 10
            return p
        }
    }
}
