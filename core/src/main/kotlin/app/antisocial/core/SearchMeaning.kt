/* anti social, GPL-3.0-or-later. Explicit, inspectable concept alternatives.
 * Related wording helps retrieval; it never creates facts in a saved record. */
package app.antisocial.core

object SearchMeaning {
    fun mayInterpret(value:String)=value.none {it in "+\\*^|<>{}[]=:#/"}
    private val groups=listOf(
        listOf("kids","children","child"),listOf("job","employment","hiring","career"),
        listOf("rocket launch","spaceflight","space mission"),listOf("astronomy","stargazing","star party"),
        listOf("software bug","software defect","software regression"),listOf("release notes","changelog","software update"),
        listOf("computer repair","pc repair","laptop repair"),listOf("car repair","auto repair","vehicle repair"),
        listOf("opening hours","business hours","store hours"),listOf("temporary closure","temporarily closed"),
        listOf("public transport","public transit","mass transit"),listOf("road closure","street closure"),
        listOf("weather warning","severe weather","storm alert"),listOf("power outage","power failure","electricity outage"),
        listOf("public meeting","council meeting","town hall"),listOf("public hearing","community hearing"),
        listOf("live music","concert","gig"),listOf("class","workshop","lesson","training"),
        listOf("volunteer","volunteering","volunteer opportunity"),listOf("food bank","food pantry","food assistance"),
        listOf("farmers market","farm market","growers market"),listOf("farm stand","farm shop","produce stand"),
        listOf("yard sale","garage sale","rummage sale"),listOf("flea market","swap meet"),
        listOf("movie","film","cinema"),listOf("photograph","photo","picture"),
        listOf("research paper","scientific paper","research study"),listOf("retraction","retracted paper"),
        listOf("data breach","security breach","data leak"),listOf("security update","security patch"),
        listOf("product recall","safety recall"),listOf("healthcare","health care","medical care"),
        listOf("bike repair","bicycle repair"),listOf("walking trail","hiking trail","nature trail"),
        listOf("trampoline","trampoline park"),listOf("burger","hamburger"),listOf("coffee shop","cafe"),
        listOf("takeaway","takeout"),listOf("ice cream","gelato"),listOf("vegetarian","meat free")
    )
    private val index by lazy {buildMap<String,List<String>> {groups.forEach {group->group.forEach {put(SearchText.normalized(it),group)}}}}
    private val compounds=setOf("ice cream","hot dog","food bank","farm stand","farm shop","coffee shop","flea market","dry cleaning","car wash","gas station","urgent care","health care","public transport","public transit")
    fun literal(intent:SearchIntent):SearchIntent {
        val result=mutableListOf<SearchClause>();var i=0
        while(i<intent.clauses.size) {
            val window=intent.clauses.drop(i).takeWhile {!it.quoted&&!it.protected&&it.excluded==intent.clauses[i].excluded}.take(3)
            val count=(window.size downTo 2).firstOrNull {n->window.take(n).flatMap {it.words}.joinToString(" ") in compounds}
            if(count==null){result+=intent.clauses[i];i++}else {result+=SearchClause(window.take(count).flatMap {it.words},excluded=intent.clauses[i].excluded);i+=count}
        }
        return intent.copy(clauses=result)
    }
    /** At most six complete paths, rather than independent synonym tokens.
     * Phrase boundaries, negatives and protected identifiers survive each path. */
    fun variants(intent:SearchIntent):List<SearchIntent> {
        val seed=literal(intent)
        val result=mutableListOf(seed)
        val clauses=seed.clauses
        if(!mayInterpret(intent.original)||clauses.none {!it.quoted&&!it.protected&&!it.excluded})return result
        val placeOnly=PlaceSearchVocabulary.parse(intent.original).onlyCategories
        var i=0
        while(i<clauses.size&&result.size<6) {
            val c=clauses[i]
            if(c.quoted||c.excluded||c.protected){i++;continue}
            val window=clauses.drop(i).takeWhile {!it.quoted&&!it.excluded&&!it.protected}.take(4)
            val found=(window.size downTo 1).firstNotNullOfOrNull {size->
                val phrase=window.take(size).flatMap {it.words}.joinToString(" ")
                val alternatives=index[SearchText.normalized(phrase)] ?: if(size>1||placeOnly)PlaceSearchVocabulary.phrases(phrase)else null
                alternatives?.let {size to it}
            }
            if(found==null){i++;continue}
            val (size,alternatives)=found
            alternatives.take(5).forEach {alternative->
                if(result.size<6){val words=SearchText.words(alternative,foldAccents=false)
                    val replacement=if(words.joinToString(" ") in compounds)listOf(SearchClause(words))else words.map {SearchClause(listOf(it))}
                    val next=SearchIntent(intent.original,clauses.take(i)+replacement+clauses.drop(i+size))
                    if(result.none {it.fingerprint==next.fingerprint})result+=next}
            }
            i+=size
        }
        return result
    }
    fun matches(query:String,content:String,prefix:Boolean=false):Boolean {
        val document=SearchDocument(content)
        return variants(SearchIntent.parse(query)).any {it.matches(document,prefix)}
    }
    /** A bounded provider expansion; original search remains the first branch. */
    fun remoteAlternative(value:String):String? {
        val intent=SearchIntent.parse(value)
        if(intent.clauses.any {it.quoted||it.protected}||!mayInterpret(value))return null
        val alternate=variants(intent).drop(1).map {variant->variant.clauses.joinToString(" "){c->(if(c.excluded)"-"else "")+c.words.joinToString(" ")}}
            .firstOrNull {SearchText.normalized(it)!=SearchText.normalized(value)}
        return alternate ?: PlaceSearchVocabulary.remoteAlternative(value)
    }
}
