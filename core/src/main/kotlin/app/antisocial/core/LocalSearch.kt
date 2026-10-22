/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import java.text.Normalizer
import java.util.Locale

/** A small literal-word query for the local SQLite FTS4 index. No remote service,
 * query operators, semantic claims or language-dependent stemming are involved. */
object LocalSearch {
    const val MAX_INPUT=512
    const val MAX_TERMS=12
    const val MAX_TERM=64
    private val word=Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")
    private val conversational=setOf("find","that","post","about","a","the","and","or","to","of","on","it","for","how","me","please","this","show","search","an","in","at","by","with","from","i","my")
    fun query(value: String): String? {
        val normalized=Normalizer.normalize(value.take(MAX_INPUT),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val found=word.findAll(normalized).map {it.value.take(MAX_TERM).let {part->if(part.lastOrNull()?.isHighSurrogate()==true)part.dropLast(1)else part}}
            .filter(String::isNotEmpty).distinct().toList()
        val meaningful=found.filterNot(conversational::contains)
        val terms=meaningful.ifEmpty {found}.take(MAX_TERMS)
        if(terms.isEmpty())return null
        // Implicit AND works with both standard and enhanced FTS3/4 builds.
        // Android's standard syntax treats an explicit AND as the word "and".
        return terms.joinToString(" "){term->"\""+term+(if(term.length>=2)"*"else "")+"\""}
    }
}
