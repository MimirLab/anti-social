/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.DeflaterOutputStream

class YouTubeCursorWireTest {
    private fun compressed(raw: ByteArray): ByteArray=ByteArrayOutputStream().also {out->DeflaterOutputStream(out).use {it.write(raw)}}.toByteArray()
    private fun wire(bytes: ByteArray)="np2:"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    @Test fun largeNumericByteArrayJsonRoundTripsWithinFiniteWireAndInflatedLimits() {
        val json=("{\"body\":["+List(30_000){(it%127).toString()}.joinToString(",")+"]}").toByteArray()
        assertTrue(json.size>18*1024)
        val encoded=YouTubeCursorWire.encode(json)
        assertTrue(encoded.startsWith("np2:"));assertTrue(encoded.length<=24*1024)
        assertArrayEquals(json,YouTubeCursorWire.decode(encoded))
    }
    @Test fun bothRawAndWireBudgetsAreEnforcedWithoutTruncatingTheDocument() {
        val boundary=ByteArray(YouTubeCursorWire.MAX_RAW){32}
        assertArrayEquals(boundary,YouTubeCursorWire.decode(YouTubeCursorWire.encode(boundary)))
        try {YouTubeCursorWire.encode(ByteArray(YouTubeCursorWire.MAX_RAW+1));fail()}catch(_: YouTubeCursorWire.SizeLimit){}
        val random=ByteArray(40*1024).also {Random(7).nextBytes(it)}
        try {YouTubeCursorWire.encode(random);fail()}catch(_: YouTubeCursorWire.SizeLimit){}
        try {YouTubeCursorWire.decode("np2:"+"a".repeat(24*1024));fail()}catch(_: IllegalArgumentException){}
    }
    @Test fun aSmallCompressionBombCannotExceedTheInflatedBudget() {
        val bomb=wire(compressed(ByteArray(YouTubeCursorWire.MAX_RAW+4096){32}))
        assertTrue(bomb.length<24*1024)
        try {YouTubeCursorWire.decode(bomb);fail("Inflation must stop at the finite raw limit")}catch(_: YouTubeCursorWire.SizeLimit){}
    }
    @Test fun truncatedTrailingMalformedAndNonCanonicalCompressionAreRejected() {
        val packed=compressed("{\"page\":\"public\"}".toByteArray())
        listOf(wire(packed.dropLast(2).toByteArray()),wire(packed+byteArrayOf(0,1)),wire(byteArrayOf(1,2,3)),"np2:not+url/base64", "np2:").forEach {bad->
            try {YouTubeCursorWire.decode(bad);fail(bad)}catch(_: IllegalArgumentException){}
        }
    }
    @Test fun existingSmallLegacyCursorsCanStillBeReadButNewCursorsAreCompressed() {
        val json="{\"version\":1,\"mode\":\"search\"}".toByteArray()
        val old="np1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(json)
        assertArrayEquals(json,YouTubeCursorWire.decode(old))
        assertTrue(YouTubeCursorWire.encode(json).startsWith("np2:"))
    }
}
