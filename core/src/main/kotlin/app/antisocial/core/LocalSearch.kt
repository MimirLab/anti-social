/* anti social, GPL-3.0-or-later. Safe FTS4 compilation of shared intent. */
package app.antisocial.core

object LocalSearch {
    const val MAX_INPUT=512
    const val MAX_TERMS=12
    const val MAX_TERM=64
    private fun compile(intent:SearchIntent,form:Int=0):String? {
        var count=0
        val terms=intent.positive.mapNotNull {clause->
            val words=clause.words.take((MAX_TERMS-count).coerceAtLeast(0));count+=words.size
            if(words.isEmpty())null else if(clause.quoted||words.size>1)"\""+words.joinToString(" ")+"\""else words.joinToString(" "){raw->
                val term=if(clause.protected||form==0)raw else SearchText.forms(raw).let {if(form==1)SearchText.plural(raw)else it.last()}
                "\""+term.take(MAX_TERM)+(if(term.length>=2)"*"else "")+"\""}
        }
        return terms.joinToString(" ").takeIf {it.isNotBlank()}
    }
    /** One portable MATCH expression. Multiple complete paths are combined by
     * ordinary SQL UNION, never by FTS's platform-dependent OR precedence. */
    fun query(value:String):String?=compile(SearchMeaning.literal(SearchIntent.parse(value.take(MAX_INPUT))),1)
    fun literalQueries(value:String):List<String> {
        val intent=SearchMeaning.literal(SearchIntent.parse(value.take(MAX_INPUT)))
        return (0..1).mapNotNull {compile(intent,it)}.distinct()
    }
    fun expandedQueries(value:String):List<String> {
        val intent=SearchMeaning.literal(SearchIntent.parse(value.take(MAX_INPUT)));val originals=literalQueries(value).toSet()
        return (SearchMeaning.variants(intent).drop(1).flatMap {variant->(0..1).mapNotNull {compile(variant,it)}}+listOfNotNull(compile(intent,2)))
            .filterNot {it in originals}.distinct().take(8)
    }
    fun excludedQueries(value:String):List<String> = SearchIntent.parse(value.take(MAX_INPUT)).excluded.flatMap {clause->
        (0..1).mapNotNull {compile(SearchIntent("",listOf(clause.copy(excluded=false))),it)}
    }.distinct().take(8)
}
