/* anti social, GPL-3.0-or-later. Reader-language event queries; no model required. */
package app.antisocial.core

data class LocalEventMatch(val matches:Boolean,val unknowns:List<String> = emptyList(),val reasons:List<String> = emptyList())

/** Facets guide discovery, not claims about a venue's unpublished suitability. */
data class LocalEventIntent(val topics:List<String>,val audience:String?=null,val outdoors:Boolean=false,
    val free:Boolean=false,val exclusions:List<String> = emptyList(),val window:LocalEventWindow?=null,val phrases:List<SearchClause> = emptyList()) {
    val family:Boolean get()=audience in setOf("baby","toddler","preschool","children","teen")
    val discoveryText:String get()=(topics+phrases.map {(if(it.excluded)"-"else "")+"\""+it.words.joinToString(" ")+"\""}+listOfNotNull(if(family)"children"else audience,if(outdoors)"outdoors"else null,if(free)"free"else null)).joinToString(" ")
    fun publisherAffinity(business:LocalBusiness):Int {
        val category=LocalDiscoveryIdentity.name(business.category)
        return when {
            family&&Regex("library|museum|park|community|school").containsMatchIn(category)->-4
            outdoors&&Regex("park|garden|nature").containsMatchIn(category)->-3
            topics.any {it in setOf("music","concert","trivia")}&&Regex("theatre|bar|brew|pub|arts").containsMatchIn(category)->-3
            topics.any {it in setOf("workshop","class","repair","craft")}&&Regex("library|maker|community|museum|arts").containsMatchIn(category)->-3
            else->0
        }
    }
    fun match(event:LocalEvent)=matchText(listOf(event.title,event.description,event.venue,event.organizer,event.age,event.price).joinToString(" "),event.age,event.price)
    fun matchText(content:String,age:String="",price:String="",linkOnly:Boolean=false):LocalEventMatch {
        if(phrases.any {!SearchIntent("",listOf(it)).matches(content)})return LocalEventMatch(false)
        val text=LocalDiscoveryIdentity.name(content);val words=LocalDiscoveryIdentity.words(text).toSet()
        if(exclusions.any {it in words||it.removeSuffix("s") in words||(it+"s") in words})return LocalEventMatch(false)
        if(topics.any {!topic(it,words)})return LocalEventMatch(false)
        val unknown=mutableListOf<String>();val reasons=mutableListOf<String>()
        if(audience!=null) {
            val range=ageRange(if(age.isNotBlank())age else content,age.isNotBlank())
            val wanted=when(audience){"baby"->0..1;"toddler"->1..3;"preschool"->3..5;"children"->0..12;"teen"->13..17;else->18..120}
            if(range!=null&&range.intersect(wanted).isEmpty())return LocalEventMatch(false)
            val familyWords=words.any {it in setOf("child","children","childrens","kids","kid","family","families","toddler","toddlers","baby","babies","preschool","storytime","puppet","allages")}
            if(range==null&&!linkOnly&&family&&!familyWords)return LocalEventMatch(false)
            if(range==null)unknown+="Age range not stated" else reasons+="Published age range overlaps your search"
        }
        if(outdoors) {
            val outside=words.any {it in setOf("outdoor","outdoors","outside","hike","hiking","trail","walk","walking","stargazing","birdwatching","beach","garden","gardening")}
            if(!outside&&Regex("\\b(?:indoors?|inside only)\\b").containsMatchIn(text))return LocalEventMatch(false)
            if(!outside)unknown+="Outdoor setting not confirmed" else reasons+="Outdoor activity"
        }
        if(free&&!linkOnly) {
            val admission=if(price.isNotBlank())Regex("\\b(?:free|no admission charge|no cost)\\b").containsMatchIn(LocalDiscoveryIdentity.name(price))
                else Regex("\\b(?:free (?:admission|entry|event|class|workshop|concert|activity)|admission (?:is )?free|no admission charge|no cost)\\b").containsMatchIn(text)
            if(!admission)return LocalEventMatch(false)
            if(Regex("\\bnot free\\b").containsMatchIn(text))return LocalEventMatch(false)
        }
        if(topics.isNotEmpty())reasons+="Matches ${topics.joinToString(", ")}"
        return LocalEventMatch(true,unknown,reasons)
    }
    companion object {
        private val filler=setOf("my","our","us","fun","near","me","in","at","the","a","an","for","and","with","things","thing","to","do","something","anything","some","any","find","show","looking","want","would","like","please","events","event","activities","activity","local","nearby","this","today","weekend","week","next","days","month","s")
        private val audiences=mapOf("baby" to "baby","babies" to "baby","infant" to "baby","infants" to "baby","toddler" to "toddler","toddlers" to "toddler","preschool" to "preschool","preschoolers" to "preschool","kids" to "children","kid" to "children","child" to "children","children" to "children","childrens" to "children","family" to "children","families" to "children","teen" to "teen","teens" to "teen","teenager" to "teen","teenagers" to "teen","adults" to "adult","adult" to "adult")
        private val groups=listOf(setOf("nature","wildlife","birdwatching","ecology","environment"),setOf("walk","walks","walking","hike","hikes","hiking"),setOf("art","arts","craft","crafts","crafting"),setOf("music","concert","concerts","gig","gigs"),setOf("workshop","workshops","class","classes","lesson","lessons","training"),setOf("repair","repairs","fix","fixing"))
        private fun topic(term:String,words:Set<String>)=(groups.firstOrNull {term in it} ?: setOf(term,term+"s")).any {it in words}||LocalDiscoveryFilter.matches(term,words.joinToString(" "))
        fun parse(value:String):LocalEventIntent {
            val phrases=SearchIntent.parse(value,conversational=false).clauses.filter {it.quoted}
            val unquoted=value.replace(Regex("[\"“][^\"”]+[\"”]")," ")
            val words=LocalDiscoveryIdentity.words(LocalDiscoveryFilter.normalizedQuery(unquoted.replace(Regex("['’]s\\b"),"")))
            val negative=mutableListOf<String>();val positive=mutableListOf<String>();var exclude=false
            words.forEach {word->when {word in setOf("not","without","exclude","excluding","except","no")->exclude=true;word=="but"->exclude=false;exclude&&word !in filler->negative+=word;!exclude->positive+=word}}
            val audience=positive.firstNotNullOfOrNull {audiences[it]}
            return LocalEventIntent(positive.filterNot {it in filler||it in audiences||it in setOf("outdoor","outdoors","outside","free")}.distinct().take(12),audience,
                positive.any {it in setOf("outdoor","outdoors","outside")},"free" in positive,negative.distinct().take(8),when {
                    "weekend" in positive->LocalEventWindow.WEEKEND;"today" in positive->LocalEventWindow.TODAY
                    "month" in positive->LocalEventWindow.MONTH;"week" in positive->LocalEventWindow.WEEK;else->null},phrases)
        }
        internal fun ageRange(value:String,knownAgeField:Boolean=false):IntRange? {
            val text=value.lowercase()
            if(Regex("\\badults?[\\s-]+only\\b|\\b(?:18|21)\\s*\\+\\s*(?:only|event|admission)\\b").containsMatchIn(text))return 18..120
            if(knownAgeField)Regex("^(\\d{1,2})\\s*\\+$").matchEntire(text.trim())?.let {return it.groupValues[1].toInt()..120}
            Regex("(?:ages?\\s*:?\\s*)?(\\d{1,2})\\s*(?:-|–|to)\\s*(\\d{1,2})(?:\\s*(?:years?|yrs?))?",RegexOption.IGNORE_CASE).find(text)?.let {
                val first=it.groupValues[1].toInt();val last=it.groupValues[2].toInt()
                // Unlabelled clock ranges and dates are not ages.
                if((knownAgeField||Regex("\\bages?\\b").containsMatchIn(text)||Regex("\\b(?:years?|yrs?)\\b").containsMatchIn(it.value))&&first<=last)return first..last
            }
            Regex("\\bages?\\s*(\\d{1,2})\\s*(?:and (?:up|over)|\\+)").find(text)?.let {return it.groupValues[1].toInt()..120}
            if(Regex("\\ball ages\\b").containsMatchIn(text))return 0..120
            return null
        }
    }
}
