/* anti social, GPL-3.0-or-later. Exact plain-caption parser equivalence. */
package app.antisocial.core

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class PlainCaptionTextTest {
    private fun previous(value:String)=if('\uffff' in value)value else
        Jsoup.parseBodyFragment(value).apply {select("script,style,noscript").remove()}.text()
    private fun same(value:String)=assertEquals(previous(value),PostPresentation.comparisonPlainText(value))
    private fun post(body:String)=Post("730001",Platform.X,"https://x.com/publicstudio/status/730001","Public studio",body)

    @Test fun ordinaryProseUsesTheSameWhitespaceAndInvisibleCharacterRules() {
        listOf(""," \t\r\n ","Alpha\r\n\t Beta","A > B","\u00a0 Alpha\u00a0Beta \u00a0",
            "A\u00adB\u200bC\ufeffD","e\u0301 é","عربي\u200f עברית\u200e","👩\u200d🔬 \u2066English\u2069",
            "\ud800 A \udc00","\u2028 A\u2029B\u2007C\u202fD\u3000").forEach(::same)
    }
    @Test fun everyBmpUnitKeepsTheParsersExactTextInTwoContexts() {
        for(code in 0..0xffff) {
            same(" ${code.toChar()} A ")
            same("A${code.toChar()}B")
        }
    }
    @Test fun fixedSupplementarySequencesAgreeWithTheRealParser() {
        val random=Random(773)
        repeat(4096){same(buildString {repeat(32){appendCodePoint(random.nextInt(0x110000))}})}
    }
    @Test fun markupEntitiesNullAndParserEndMarkersKeepTheirExistingBoundaries() {
        listOf("<p>Alpha&nbsp;Beta</p><script>hidden</script><style>hidden</style>",
            "<noscript>hidden</noscript>Visible<br>tail","<3 and A > B","&lt;script&gt;literal&lt;/script&gt;",
            "A &amp;amp; B","A&#0;B","A\u0000B","\u0000 leading and trailing\u0000",
            "<b>Alpha</b>\uffff changed tail","Literal \uffff tail").forEach(::same)
        val source=post("A\uffff changed tail").copy(title="A complete heading")
        assertEquals(PostTextPresentation(source.title,source.body),PostPresentation.text(source))
        assertEquals(source.title+"\n"+source.body,PostPresentation.comparableContent(source))
    }
    @Test fun actualComparisonKeepsDestinationCaseClaimsAndLiteralSources() {
        val original=post("Approved 6 items at https://example.org/Report?Mode=A#Part")
        val before=original.copy()
        val key=PostPresentation.comparableContent(original)
        assertTrue(key.contains("example.org/Report?Mode=A#Part"))
        for(body in listOf(original.body.replace("/Report","/report"),original.body.replace("Mode=A","mode=A"),
            original.body.replace("Approved","Not approved"),original.body.replace("6 items","9 items")))
            assertNotEquals(key,PostPresentation.comparableContent(original.copy(body=body)))
        assertEquals(before,original)
    }
    @Test fun longPlainInputRetainsItsFinalClause() {
        val value="  "+"A complete sentence. e\u0301 👩\u200d🔬\t".repeat(2048)+"Important final clause.  "
        same(value)
        assertTrue(PostPresentation.comparisonPlainText(value).endsWith("Important final clause."))
    }
}
