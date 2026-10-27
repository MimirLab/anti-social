/* anti social, GPL-3.0-or-later. Shared bounded lexical interpretation.
 * plural() adapts Lucene EnglishMinimalStemmer's Harman S-Stemmer under
 * Apache-2.0; see search-notices.txt and licenses/search-NOTICE.txt. */
package app.antisocial.core

import java.text.Normalizer
import java.util.Locale

object SearchText {
    const val VERSION=1
    private val word=Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")
    private val combiningMarks=Regex("\\p{M}+")
    fun words(value:String,foldAccents:Boolean=true,maxWords:Int=256,maxWordLength:Int=64):List<String> {
        val text=Normalizer.normalize(value,if(foldAccents)Normalizer.Form.NFKD else Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .let {if(foldAccents)it.replace(combiningMarks,"")else it}
        return word.findAll(text).take(maxWords).map {it.value.take(maxWordLength).let {w->if(w.lastOrNull()?.isHighSurrogate()==true)w.dropLast(1)else w}}.toList()
    }
    private val irregular=mapOf("children" to "child","people" to "person","men" to "man","women" to "woman","feet" to "foot","teeth" to "tooth","mice" to "mouse","classes" to "class","buses" to "bus","dishes" to "dish","businesses" to "business","addresses" to "address","watches" to "watch","boxes" to "box","churches" to "church","launches" to "launch","branches" to "branch","beaches" to "beach","sandwiches" to "sandwich","searches" to "search","patches" to "patch","batches" to "batch","matches" to "match","coaches" to "coach","speeches" to "speech")
    private val protected=setOf("news","series","species","physics","economics","mathematics","politics","outdoors","indoors","this","his","was","yes","gas")
    fun plural(value:String):String {
        irregular[value]?.let {return it}
        if(value in protected||value.length<3||!value.endsWith('s')||value.any {it !in 'a'..'z'})return value
        val n=value.length
        return when(value[n-2]) {
            'u','s'->value
            'e'->when {n>3&&value[n-3]=='i'&&value[n-4]!='a'&&value[n-4]!='e'->value.dropLast(3)+"y";value[n-3] in "iaoe"->value;else->value.dropLast(1)}
            else->value.dropLast(1)
        }
    }
    fun normalized(value:String)=words(value).joinToString(" ",transform=::plural)
    fun forms(value:String):List<String> {
        val root=plural(value)
        val plurals=irregular.filterValues {it==root}.keys
        val regular=when {root.endsWith('y')&&root.length>2&&root[root.length-2] !in "aeiou"->root.dropLast(1)+"ies";root.endsWith("ss")||root.endsWith("sh")||root.endsWith("ch")||root.endsWith('x')->root+"es";else->root+"s"}
        return (listOf(value,root)+plurals+listOfNotNull(regular.takeIf {root.endsWith('y')||value!=root})).distinct().take(4)
    }
    /** Single-edit corrections are candidates, never destructive substitutions. */
    fun oneEdit(a:String,b:String):Boolean {
        if(kotlin.math.abs(a.length-b.length)>1)return false
        if(a.length==b.length) {
            val different=a.indices.filter {a[it]!=b[it]}
            return different.size<=1||different.size==2&&different[1]==different[0]+1&&a[different[0]]==b[different[1]]&&a[different[1]]==b[different[0]]
        }
        val short=if(a.length<b.length)a else b;val long=if(a.length<b.length)b else a;var i=0;var j=0
        while(i<short.length&&j<long.length){if(short[i]==long[j])i++ else if(j>i)return false;j++};return true
    }
}

/** Tokenize a bounded document once for every interpretation of the query. */
internal class SearchDocument(content:String) {
    val words=SearchText.words(content.take(1024*1024),maxWords=131072,maxWordLength=1024*1024)
    val literal=words.toSet()
    val normalized=literal.map(SearchText::plural).toSet()
}

data class SearchClause(val words:List<String>,val quoted:Boolean=false,val excluded:Boolean=false,val protected:Boolean=false)
/** Original words/phrases remain available; equivalence and exclusions are not
 * provider syntax. Callers compile only the capabilities their backend supports. */
data class SearchIntent(val original:String,val clauses:List<SearchClause>) {
    val positive get()=clauses.filterNot {it.excluded}
    val excluded get()=clauses.filter {it.excluded}
    val fingerprint get()="search-v${SearchText.VERSION}:"+clauses.joinToString("|"){"${it.quoted}:${it.excluded}:${it.protected}:"+it.words.joinToString(" ")}
    fun matches(content:String,prefix:Boolean=false):Boolean=matches(SearchDocument(content),prefix)
    internal fun matches(document:SearchDocument,prefix:Boolean=false):Boolean {
        fun has(c:SearchClause):Boolean {
            val wantedWords=SearchText.words(c.words.joinToString(" "),maxWordLength=if(c.quoted)512 else 64)
            if(c.quoted||c.words.size>1)return document.words.indices.any {start->start+wantedWords.size<=document.words.size&&wantedWords.indices.all {document.words[start+it]==wantedWords[it]}}
            val wanted=wantedWords.singleOrNull() ?: return false
            val canonical=SearchText.plural(wanted)
            return wanted in document.literal||!c.protected&&canonical in document.normalized||prefix&&wanted.length>=2&&
                (document.literal.any {it.startsWith(wanted)}||!c.protected&&document.normalized.any {it.startsWith(canonical)})
        }
        return positive.all(::has)&&excluded.none(::has)
    }
    companion object {
        private val parts=Regex("(-?)[\"“]([^\"”]+)[\"”]|(-?)([@#]?[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_]*)")
        val filler=setOf("find","that","post","about","a","the","and","or","to","of","on","it","for","how","me","please","this","show","search","an","in","at","by","with","from","i","my","nearby","looking")
        fun parse(value:String,conversational:Boolean=true):SearchIntent {
            val original=value.take(512)
            // An unmatched quote is punctuation, never an instruction to FTS.
            val parsed=parts.findAll(original).mapNotNull {m->
                val quote=m.groups[2]?.value;val token=m.groups[4]?.value
                val words=SearchText.words(quote ?: token.orEmpty(),foldAccents=false,maxWordLength=if(quote!=null)512 else 64).take(12)
                if(words.isEmpty())null else SearchClause(words,quote!=null,(m.groupValues[1]=="-"||m.groupValues[3]=="-")&&(m.range.first==0||original[m.range.first-1].isWhitespace()),token?.startsWith('@')==true||token?.startsWith('#')==true||original.getOrNull(m.range.last+1)==':'||original.getOrNull(m.range.first-1)==':'||words.any {w->w.any(Char::isDigit)})
            }.distinct().toList()
            val meaningful=if(conversational)parsed.filterNot {!it.quoted&&!it.excluded&&!it.protected&&it.words.singleOrNull() in filler}else parsed
            return SearchIntent(original,meaningful.ifEmpty {parsed}.take(20))
        }
    }
}
