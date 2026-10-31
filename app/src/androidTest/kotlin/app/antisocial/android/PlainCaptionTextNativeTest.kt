/* anti social, GPL-3.0-or-later. Native parser-equivalence controls. */
package app.antisocial.android

import app.antisocial.core.*
import java.util.Random
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PlainCaptionTextNativeTest {
    private val method=PostPresentation::class.java.declaredMethods.single {
        it.name.substringBefore('$')=="comparisonPlainText"&&it.parameterCount==1
    }.apply {isAccessible=true}
    private fun same(value:String) {
        val previous=if('\uffff' in value)value else Jsoup.parseBodyFragment(value).apply {select("script,style,noscript").remove()}.text()
        assertEquals(previous,method.invoke(PostPresentation,value))
    }
    @Test fun plainAndSupplementaryTextMatchesTheActualPlatformParser() {
        for(code in 0..255){same(" ${code.toChar()} A ");same("A${code.toChar()}B")}
        listOf("A > B","A\u00adB\u200bC\ufeffD","\u00a0A\u2028B\u2029C\u2007D\u202fE\u3000",
            "عربي\u200f עברית\u200e","👩\u200d🔬 \u2066English\u2069","\ud800 A \udc00").forEach(::same)
        val random=Random(773)
        repeat(2048){same(buildString {repeat(32){appendCodePoint(random.nextInt(0x110000))}})}
    }
    @Test fun unsupportedTextRetainsMarkupEntityAndLiteralTailRules() {
        listOf("<p>Alpha&nbsp;Beta</p><script>hidden</script><style>hidden</style>",
            "<noscript>hidden</noscript>Visible<br>tail","<3 and A > B","&lt;script&gt;literal&lt;/script&gt;",
            "A &amp;amp; B","A\u0000B","<b>Alpha</b>\uffff changed tail").forEach(::same)
        val source=Post("730001",Platform.X,"https://x.com/publicstudio/status/730001","Public studio","A\uffff changed tail",title="A complete heading")
        assertEquals(PostTextPresentation(source.title,source.body),PostPresentation.text(source))
    }
}
