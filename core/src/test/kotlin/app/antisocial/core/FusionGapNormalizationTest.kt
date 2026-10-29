/* anti social, GPL-3.0-or-later. Preserve literal collation semantics. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class FusionGapNormalizationTest {
    @Test fun whitespaceAndQuoteNormalizationKeepsEveryOtherCharacterAndClaimBoundary() {
        val spaces=Regex("[\\p{Z}\\s]+")
        fun previous(value:String)=spaces.replace(value," ").map {when(it){'’','‘'->'\'';'“','”'->'"';else->it}}.joinToString("")
        // Include lone UTF-16 surrogates and control/noncharacters: changing
        // them into whitespace would change literal passage/claim boundaries.
        for(code in 0..0xffff) {
            val value="‘${code.toChar()}\t’\u00a0“${code.toChar()}”"
            assertEquals("UTF-16 code unit $code",previous(value),CrossPostFusion.normalizeGap(value))
        }
        for(value in listOf(""," ","\u001c\u001d\u001e\u001f\u0085\ufeff\u200b"," >= 40% "," != 400% "," isn't ","https://example.org/Part?key=A"))
            assertEquals(previous(value),CrossPostFusion.normalizeGap(value))
        val unchanged=" <= 40% isn't "
        assertSame(unchanged,CrossPostFusion.normalizeGap(unchanged))
    }
}
