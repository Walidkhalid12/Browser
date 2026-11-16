package com.khalid.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {
    private val samples = listOf(
        "https://example.com/",
        "https://www.google.com/search?q=kotlin+compose",
        "https://en.wikipedia.org/wiki/QR_code#Design_and_structure_of_the_symbol_and_how_it_is_read",
        "https://github.com/search?q=" + "a".repeat(180),
        "https://x.com/" + "b".repeat(420),
    )

    @Test fun sizesAndFinders() {
        val m = Qr.encode("https://example.com/")
        assertNotNull(m)
        val n = m!!.size
        assertEquals(0, (n - 17) % 4)
        // finder corners are dark, the ring inside is light
        assertTrue(m[0][0] && m[0][n - 1] && m[n - 1][0])
        assertTrue(!m[1][1] && m[3][3])
    }

    @Test fun dumpForDecoder() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "qr_dump"); dir.mkdirs()
        samples.forEachIndexed { i, s ->
            val m = Qr.encode(s)!!
            java.io.File(dir, "$i.txt").writeText(s + "\n" + m.joinToString("\n") { r -> r.joinToString("") { if (it) "1" else "0" } })
        }
    }
}
