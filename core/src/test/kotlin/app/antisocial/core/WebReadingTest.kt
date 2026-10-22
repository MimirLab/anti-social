/* AntiSocial, GPL-3.0-or-later. Static extraction retains meaning without page execution. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class WebReadingTest {
    private val prose="A replacement battery must match the exact model and electrical requirements. Disconnect the power supply before opening the case, keep the original screws organized, and photograph the connector before moving it."
    private fun read(extra:String)=ContextArticles.extract(Payload("<html><head><title>Repair guide</title></head><body><article><h1>Repair guide</h1><p>$prose</p>$extra<p>$prose</p></article></body></html>","https://publisher.example/guide","text/html",200),"https://publisher.example/guide")
    @Test fun reversedAndLargeListNumbersKeepTheSourceSequence() {
        val text=read("<ol reversed><li>First result</li><li>Second result</li></ol><ol start=10000><li>Large sequence</li><li>Next entry</li></ol>").readingText
        assertTrue(text.contains("2. First result"));assertTrue(text.contains("1. Second result"))
        assertTrue(text.contains("10000. Large sequence"));assertTrue(text.contains("10001. Next entry"))
    }
    @Test fun structuredTextRetainsOrderedStepsNestedListsQuotesAndCodeIndentation() {
        val article=read("<h2>Sequence</h2><ol start=3><li>Disconnect power<ul><li>Keep the cable label</li></ul></li><li value=8>Inspect connector</li></ol><blockquote><p>Never puncture a swollen cell.</p></blockquote><pre>if (ready) {\n    disconnect();\n}</pre>")
        val text=article.readingText
        assertTrue(text.contains("## Sequence"));assertTrue(text.contains("3. Disconnect power"));assertTrue(text.contains("  - Keep the cable label"));assertTrue(text.contains("8. Inspect connector"))
        assertEquals(1,Regex("Keep the cable label").findAll(text).count())
        assertTrue(text.contains("> Never puncture a swollen cell."));assertTrue(text.contains("        disconnect();"))
        assertFalse(text.startsWith("# Repair guide"))
    }
    @Test fun simpleTablesKeepColumnRelationshipsInsteadOfReturningUnlabelledCells() {
        val article=read("<table><tr><th>Part</th><th>Capacity</th></tr><tr><td>Battery A</td><td>56 Wh</td></tr><tr><td>Battery B</td><td>42 Wh</td></tr></table>")
        assertTrue(article.text.contains("Part: Battery A; Capacity: 56 Wh"));assertTrue(article.text.contains("Part: Battery B; Capacity: 42 Wh"))
    }
    @Test fun spanningTablesKeepVisibleValuesWithoutInventingColumnAssignments() {
        val article=read("<table><tr><th>Part</th><th>Capacity</th></tr><tr><td colspan=2>Combined specification: 56 Wh</td></tr></table>")
        assertTrue(article.text.contains("Combined specification: 56 Wh"));assertFalse(article.text.contains("Part: Combined"))
    }
    @Test fun hiddenWidgetsAreRemovedWhileNamedContentAndSourceLinksSurvive() {
        val article=read("<div class=cookie-banner><p>COOKIE NOISE</p></div><p style='DISPLAY: none !important'>HIDDEN NOISE</p><h2>Reader safeguards</h2><p>The <a href='/manual'>service manual</a> documents safe cable replacement. Read the safety notice before proceeding.</p><script>fetch('/never-run')</script><iframe src='/never-load'></iframe>")
        assertFalse(article.text.contains("NOISE"));assertFalse(article.text.contains("never-"));assertTrue(article.readingText.contains("## Reader safeguards"));assertTrue(article.blocks.flatMap {it.links}.contains("https://publisher.example/manual"))
    }
}
