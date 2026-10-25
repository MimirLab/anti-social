package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class TextWhitespaceTest {
    private val previous=Regex("[\\s\\p{Z}]+")
    @Test fun everyUnicodeCodePointAndUnpairedSurrogateRetainsTheRegexContract() {
        val value=buildString {for(point in 0..Character.MAX_CODE_POINT){append('a');appendCodePoint(point);append('b')}}
        assertTrue("Whitespace replacement changed for a Unicode code point",previous.replace(value," ")==TextWhitespace.collapse(value))
    }
    @Test fun mixedRunsKeepControlsThatAreNotPartOfTheExistingExpression() {
        val value="\t\n  repair\u2003\u2028\u2029\u00a0tools\u001c\u001d\u001e\u001f\u0085\ufeff\u200b \r\n"
        assertEquals(" repair tools\u001c\u001d\u001e\u001f\u0085\ufeff\u200b ",TextWhitespace.collapse(value))
        assertEquals(previous.replace(value," "),TextWhitespace.collapse(value))
        assertEquals("",TextWhitespace.collapse(""));assertEquals(" ",TextWhitespace.collapse("\t\r\n\u2000\u202f"))
    }
    @Test fun replacementsNeverJoinDistinctWordsOrChangeAstralText() {
        for(value in listOf("one two","🛠\t🔋","a\ud800\t\udfffz","one\u2003two","  one \ttwo  three  ","a\u001cb")) {
            assertEquals(previous.replace(value," "),TextWhitespace.collapse(value))
        }
    }
    @Test fun persistedContentSignatureVectorsKeepLeadingZerosAndControlBoundaries() {
        val cases=mapOf(
            "HELLO\u2003WORLD" to "c1557be7b5d839af8e0506acab56eadc679212de3261b152868e3a627b44400d",
            "a\u001cb" to "536ea47e07f2e2cc45ac2c95e4129fd065f75c6f726423d21aed2bdea5a33c93",
            "🛠\u2009REPAIR\u2009🔋" to "4306c06475a016b20304cb2c6b489b823ae758ae145bb7e0aaac35bab32ff2b8",
            "Repair bulletin 358" to "0068f94982da80bd5cd069dd41d6b434f928fb6d7cf48ab8a6b3d4cd7a82d84c",
            "" to "96a296d224f285c67bee93c30f8a309157f0daa35dc5b87e410b78630a09cfc7",
        )
        cases.forEach {(body,expected)->assertEquals(expected,ContentRevisions.signature(Post("vector",Platform.X,"https://x.com/workshop/status/123","Workshop",body)))}
    }
}
