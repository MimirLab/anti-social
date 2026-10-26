/* anti social, GPL-3.0-or-later. Compiled public category vocabulary;
 * iD Contributors, ISC. See place-search.json and search-notices.txt. */
package app.antisocial.core

import com.google.gson.JsonParser

data class PlaceConcept(val id:String,val name:String,val aliases:List<String>,val related:List<String>,val tags:List<Map<String,String>>) {
    val cuisine get()=id.startsWith("cuisine:")
    val normalizedAliases=aliases.map(SearchText::normalized)
}
data class PlaceTerm(val words:List<String>,val concepts:List<PlaceConcept> = emptyList(),val quoted:Boolean=false,val excluded:Boolean=false)
data class PlaceSearch(val original:String,val terms:List<PlaceTerm>) {
    val literal=SearchIntent.parse(original)
    val concepts get()=terms.filterNot {it.excluded}.flatMap {it.concepts}.distinctBy {it.id}
    val onlyCategories get()=terms.filterNot {it.excluded}.all {it.concepts.isNotEmpty()}
    val fingerprint get()="place-v1:"+terms.joinToString("|"){"${it.quoted}:${it.excluded}:${it.words.joinToString(" ")}:${it.concepts.joinToString(","){c->c.id}}"}
    fun matches(business:LocalBusiness):Boolean {
        val content=LocalBusinessRouting.text(business)
        val full=SearchText.normalized(content);val category=SearchText.normalized(business.category+" "+business.keywords.joinToString(" "))
        val details=SearchText.normalized(business.description+" "+LocalBusinessDetails.facts(business).joinToString(" "){it.value})
        fun contains(text:String,words:List<String>)=(" $text ").contains(" "+words.joinToString(" ")+" ")
        fun match(term:PlaceTerm):Boolean {
            if(term.quoted)return SearchIntent("",listOf(SearchClause(term.words,true))).matches(content)
            if(term.concepts.isEmpty())return contains(full,term.words)
            return term.concepts.any {c->
                val aliases=c.normalizedAliases
                if(c.cuisine) {
                    // Names such as Indian Example Coffee do not establish cuisine.
                    val cuisine=c.id.substringAfter(':').replace('_',' ')
                    contains(category,SearchText.words(cuisine))||(term.words!=SearchText.words(cuisine)&&contains(full,term.words))||aliases.any {it.contains(" ")&&(" $full ").contains(" $it ")}||
                        (contains(category,listOf("restaurant"))&&contains(details,SearchText.words(cuisine)))
                }else aliases.any {(" $category ").contains(" $it ")||(" $full ").contains(" $it ")}
            }
        }
        return terms.filterNot {it.excluded}.all(::match)&&terms.filter {it.excluded}.none(::match)
    }
    fun predicates():List<String> {
        if(!onlyCategories)return emptyList()
        val selected=if(concepts.any {it.cuisine})concepts.filter {it.cuisine}else concepts
        return selected.flatMap {it.tags}.distinct().take(8).map {tags->tags.entries.sortedBy {it.key}.joinToString(""){(k,v)->
            if(k=="cuisine")"[cuisine~\"(^|;)(${if(v=="indian")"indian|north_indian|south_indian"else v})(;|$)\",i]"else "[$k=\"$v\"]"
        }}
    }
    fun alternative():String?=if(concepts.isEmpty())null else terms.filterNot {it.excluded}.joinToString(" "){term->
        term.concepts.firstOrNull()?.name ?: if(term.quoted)"\"${term.words.joinToString(" ")}\""else term.words.joinToString(" ")
    }.let {positive->positive+terms.filter {it.excluded}.joinToString(""){" -\""+it.words.joinToString(" ")+"\""}}.take(256).takeIf {SearchText.normalized(it)!=SearchText.normalized(original)}
}
object PlaceSearchVocabulary {
    private val concepts:List<PlaceConcept> by lazy {
        val text=requireNotNull(javaClass.getResourceAsStream("/place-search.json")).bufferedReader().use {it.readText()};require(text.length<=256*1024)
        JsonParser.parseString(text).asJsonObject["concepts"].asJsonArray.map {item->val o=item.asJsonObject
            fun strings(key:String)=o[key].asJsonArray.map {it.asString}.take(64)
            PlaceConcept(o["id"].asString,o["name"].asString,strings("aliases"),strings("related"),o["tags"].asJsonArray.take(4).map {tag->tag.asJsonObject.entrySet().associate {e->
                require(e.key.matches(Regex("[a-z:_]+"))&&e.value.asString.matches(Regex("[a-zA-Z0-9_-]+")));e.key to e.value.asString}})
        }
    }
    private val aliases by lazy {buildMap<String,MutableList<PlaceConcept>> {
        concepts.forEach {c->c.aliases.forEach {alias->getOrPut(SearchText.normalized(alias)){mutableListOf()}.add(c)}}
        // Reader-language equivalences, not the upstream related-terms field.
        mapOf("food" to "amenity/restaurant","dining" to "amenity/restaurant","eatery" to "amenity/restaurant","coffee shop" to "amenity/cafe","grocery store" to "shop/supermarket","grocery" to "shop/supermarket","taqueria" to "cuisine:mexican","taco" to "cuisine:mexican","hamburger" to "cuisine:burger").forEach {(alias,id)->concepts.firstOrNull {it.id==id}?.let {getOrPut(alias){mutableListOf()}.add(it)}}
        listOf("amenity/cafe","shop/supermarket","shop/convenience","shop/greengrocer","amenity/marketplace").forEach {id->concepts.firstOrNull {it.id==id}?.let {getOrPut("food"){mutableListOf()}.add(it)}}
    }}
    private val correctionWords by lazy {aliases.keys.filter {" " !in it&&it.length in 4..32}.flatMap {listOf(it,it+"s",it+"es")}.toSet()}
    private val corrections=linkedMapOf<String,Pair<Long,String?>>()
    private const val MEMO_MS=15*60_000L
    private fun expired(at:Long)=System.currentTimeMillis()-at !in 0..MEMO_MS
    @Synchronized fun correction(word:String):String? {
        if(word.length !in 5..32||word in aliases||word.any {it !in 'a'..'z'})return null
        corrections[word]?.takeUnless {expired(it.first)}?.let {return it.second}
        return correctionWords.asSequence().filter {SearchText.oneEdit(word,it)}.map(SearchText::plural).distinct().take(2).toList().singleOrNull().also {corrections[word]=System.currentTimeMillis() to it;while(corrections.size>128)corrections.remove(corrections.keys.first())}
    }
    private val plans=linkedMapOf<String,Pair<Long,PlaceSearch>>()
    @Synchronized fun cleanup(clear:Boolean=false) {
        if(clear){plans.clear();corrections.clear()}else {plans.entries.removeAll {expired(it.value.first)};corrections.entries.removeAll {expired(it.value.first)}}
    }
    @Synchronized fun parse(value:String):PlaceSearch {
        val key=value.take(512)
        return plans[key]?.takeUnless {expired(it.first)}?.second ?: build(key).also {plans[key]=System.currentTimeMillis() to it;while(plans.size>32)plans.remove(plans.keys.first())}
    }
    private fun build(value:String):PlaceSearch {
        val intent=SearchIntent.parse(value);var negative=false
        val clauses=intent.clauses.mapNotNull {c->when {
            !c.quoted&&c.words.singleOrNull() in setOf("without","excluding","exclude","except")-> {negative=true;null}
            !c.quoted&&c.words.singleOrNull()=="but"->{negative=false;null}
            else->c.copy(excluded=c.excluded||negative)
        }}
        val terms=mutableListOf<PlaceTerm>();var i=0
        while(i<clauses.size) {
            val clause=clauses[i]
            if(clause.quoted||clause.protected){terms+=PlaceTerm(clause.words,quoted=clause.quoted,excluded=clause.excluded);i++;continue}
            val window=clauses.drop(i).takeWhile {!it.quoted&&!it.protected&&it.excluded==clause.excluded}.take(6)
            val match=(window.size downTo 1).firstNotNullOfOrNull {size->val words=SearchText.words(window.take(size).flatMap {it.words}.joinToString(" ")).map(SearchText::plural)
                aliases[words.joinToString(" ")]?.let {Triple(size,words,it.distinctBy {c->c.id})}}
            if(match!=null){terms+=PlaceTerm(match.second,match.third,excluded=clause.excluded);i+=match.first}
            else {val raw=SearchText.words(clause.words.joinToString(" ")).map(SearchText::plural);val corrected=raw.singleOrNull()?.let(::correction)
                terms+=PlaceTerm(raw,corrected?.let {aliases[it]?.distinctBy {c->c.id}}.orEmpty(),excluded=clause.excluded);i++}
        }
        val conversational=setOf("near","best","good","great","some","something","place","places","spot","spots","eat","eating","get","want","would","like")
        val selected=if(terms.any {it.concepts.isNotEmpty()})terms.filterNot {!it.quoted&&!it.excluded&&it.concepts.isEmpty()&&it.words.singleOrNull() in conversational}else terms
        return PlaceSearch(value.take(512),selected.take(12))
    }
    /** Strong category aliases only. Related indexing terms are not synonyms. */
    fun phrases(value:String):List<String>? {
        val found=aliases[SearchText.normalized(value)]?.distinctBy {it.id} ?: return null
        if(found.size>3||SearchText.words(value).size==1&&found.all {it.cuisine})return null
        return found.flatMap {concept->concept.aliases.filter {alias->!concept.cuisine||SearchText.words(alias).size>=2}}
            .distinctBy(SearchText::normalized).take(5).takeIf {it.isNotEmpty()}
    }
    fun remoteAlternative(value:String):String? {
        val intent=SearchIntent.parse(value)
        if(intent.clauses.any {it.quoted||it.excluded||it.protected||it.words.size!=1}||value.any {it in ":/"})return null
        val alternative=intent.clauses.joinToString(" "){clause->val word=clause.words.single();val canonical=SearchText.plural(word)
            if(canonical in aliases)canonical else word}
        return alternative.takeIf {it.isNotBlank()&&it!=value.lowercase()}
    }
}
