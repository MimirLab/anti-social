/* AntiSocial, GPL-3.0-or-later. A reading view never replaces its original evidence. */
package app.antisocial.core

import java.security.MessageDigest

/** Half-open UTF-16 ranges. Translated ranges deliberately map to complete
 * source passages, not guessed word alignments from a translation model. */
data class ContextReadingSpan(
    val sourceStart: Int,
    val sourceEnd: Int,
    val readingStart: Int,
    val readingEnd: Int,
    val language: String,
    val translated: Boolean
)

/** A prepared view preferring English; unconfirmed source fragments remain explicit.
 * recipe identifies the detector, translation engine and pinned language pack
 * revisions. Neither neural language detection nor translation is infallible. */
data class ContextReadingText(
    val sourceHash: String,
    val text: String,
    val spans: List<ContextReadingSpan>,
    val recipe: String,
    /** Preparation was attempted, but some original prose remains because
     * detection/translation could not establish an English working passage. */
    val languageUnconfirmed: Boolean = false
) {
    val fingerprint: String get() = digest("reading-v2\n$sourceHash\n$recipe\n$languageUnconfirmed\n$text\n$spans")

    fun validate(source: String) {
        require(source.length <= MAX_CHARACTERS && text.length <= MAX_CHARACTERS)
        require(sourceHash == digest(source) && recipe.length in 1..4096)
        require(spans.size <= 32_768)
        var a = 0; var b = 0
        for (span in spans) {
            require(span.sourceStart == a && span.readingStart == b)
            require(span.sourceEnd in a + 1..source.length && span.readingEnd in b + 1..text.length)
            require(boundary(source, a) && boundary(source, span.sourceEnd) && boundary(text, b) && boundary(text, span.readingEnd))
            require(span.language.matches(Regex("[a-z]{2,3}([_-][A-Za-z]{2,4})?")))
            if (!span.translated) require(source.substring(a, span.sourceEnd) == text.substring(b, span.readingEnd))
            a = span.sourceEnd; b = span.readingEnd
        }
        require(a == source.length && b == text.length) { "A reading view omitted source material" }
    }

    /** Untranslated runs preserve exact offsets. A translated partial sentence
     * cites its whole source passage; it never claims exact word alignment. */
    fun sourceRange(start: Int, end: Int): Pair<Int, Int> {
        require(start in 0..text.length && end in start..text.length && boundary(text, start) && boundary(text, end))
        if (start == end) {
            if (start == text.length) return (spans.lastOrNull()?.sourceEnd ?: 0).let { it to it }
            val span = spans.first { start >= it.readingStart && start < it.readingEnd }
            return (if (span.translated) span.sourceStart else span.sourceStart + start - span.readingStart).let { it to it }
        }
        val first = spans.first { it.readingEnd > start }
        val last = spans.last { it.readingStart < end }
        return (if (first.translated) first.sourceStart else first.sourceStart + start - first.readingStart) to
            (if (last.translated) last.sourceEnd else last.sourceStart + end - last.readingStart)
    }

    fun sourceQuote(source: String, quote: String, start: Int, end: Int): String {
        require(quote.isNotBlank() && quote.length <= 8192)
        // Ambiguous repeated phrases cover every matching passage in this
        // unit. Choosing a convenient occurrence would invent precision.
        var at = text.indexOf(quote, start)
        var first = Int.MAX_VALUE; var last = -1
        while (at >= start && at + quote.length <= end) {
            val range = sourceRange(at, at + quote.length)
            first = minOf(first, range.first); last = maxOf(last, range.second)
            at = text.indexOf(quote, at + 1)
        }
        if (last < 0 || last - first > 8192) throw ContextProcessingException("A translated quotation has no bounded original passage")
        return source.substring(first, last).takeIf { it.isNotBlank() }
            ?: throw ContextProcessingException("A translated quotation has no original evidence")
    }

    companion object {
        const val MAX_CHARACTERS = 4 * 1024 * 1024
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun identity(text: String, recipe: String = "english-v1", languageUnconfirmed: Boolean = false) = ContextReadingText(digest(text), text,
            if (text.isEmpty()) emptyList() else listOf(ContextReadingSpan(0, text.length, 0, text.length, if(languageUnconfirmed)"und"else "en", false)), recipe,languageUnconfirmed)
        private fun boundary(text: String, at: Int) = at == 0 || at == text.length || !(text[at - 1].isHighSurrogate() && text[at].isLowSurrogate())
    }
}

/** Prepared before token planning, relevance, extraction or embedding lookup.
 * Proper names and URLs remain identifiers; only readable prose is translated. */
data class ContextReadingInput(val body: ContextReadingText, val title: String = "", val parentText: String = "",
    val titleLanguageUnconfirmed:Boolean=false,val parentLanguageUnconfirmed:Boolean=false) {
    fun document(source: ContextDocument): ContextDocument {
        body.validate(source.text)
        require(title.length <= 4096 && parentText.length <= ContextReadingText.MAX_CHARACTERS)
        return source.copy(text = body.text, title = title, parentText = parentText,
            metadata = source.metadata + ("readingView" to body.fingerprint) + ("readingLanguage" to if(body.languageUnconfirmed)"unconfirmed"else "en")+
                (if(body.languageUnconfirmed||titleLanguageUnconfirmed||parentLanguageUnconfirmed)mapOf("uncertain" to "true")else emptyMap())+
                (if(body.languageUnconfirmed)mapOf("languageUnconfirmed" to "true")else emptyMap())+
                (if(titleLanguageUnconfirmed)mapOf("titleLanguageUnconfirmed" to "true")else emptyMap())+
                (if(parentLanguageUnconfirmed)mapOf("parentLanguageUnconfirmed" to "true")else emptyMap()))
    }
}
