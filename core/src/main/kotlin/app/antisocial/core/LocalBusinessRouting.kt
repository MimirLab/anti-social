/* AntiSocial, GPL-3.0-or-later. Deterministic query plans, not model calls. */
package app.antisocial.core

import java.util.Locale
import kotlin.math.roundToInt

/** Units affect presentation and radius entry only; stored geographic radii stay in km. */
enum class LocalDistanceUnit(val id:String,val symbol:String) {
    KILOMETRES("km","km"), MILES("mi","mi");
    fun fromKm(km:Double)=if(this==MILES)km/1.609344 else km
    fun toKm(value:Int)=(if(this==MILES)value*1.609344 else value.toDouble()).roundToInt().coerceIn(1,50)
    fun radius(km:Int)=format(fromKm(km.toDouble()))+" "+symbol
    fun away(km:Double,locale:Locale=Locale.getDefault())=String.format(locale,"%.1f %s away",fromKm(km),symbol)
    private fun format(n:Double)=if(kotlin.math.abs(n-n.roundToInt())<.04)n.roundToInt().toString()else String.format(Locale.getDefault(),"%.1f",n)
    val presets:List<Int> get()=if(this==MILES)listOf(1,3,5,10,15,25,30)else listOf(2,5,10,25,50)
    companion object {fun fromId(id:String)=entries.firstOrNull {it.id==id}}
}

object LocalBusinessRouting {
    /** Keep state/country context. A shared city name must never silently move to another country. */
    fun queries(query:LocalDiscoveryQuery):List<String> {
        val area=query.area.label.replace(Regex(",?\\s*United States(?: of America)?$",RegexOption.IGNORE_CASE),"").trim()
        val topic=(if(query.mode==LocalSearchMode.EVENTS)LocalDiscoveryFilter.eventQuery(query.text)else LocalDiscoveryFilter.normalizedQuery(query.text)).ifBlank {if(query.mode==LocalSearchMode.EVENTS)"community"else "local businesses"}
        if(query.mode==LocalSearchMode.BUSINESSES&&SearchIntent.parse(query.text).clauses.any {it.quoted})return listOf("${query.text.trim()} $area","${query.text.trim()} $area website address contact").map {it.take(512)}
        return if(query.mode==LocalSearchMode.EVENTS)listOf("$topic $area events ${query.dates().first} calendar",
            LocalEventIntent.parse(query.text).discoveryAlternative?.let {"$it $area public calendar"} ?: LocalOpportunityRouting.focus(query)?.let {"$topic $area $it"} ?: "$topic $area public calendar")
        else when(topic) {
            "taco"->listOf("tacos $area","Mexican restaurants $area menus")
            "burger"->listOf("burgers $area","burger restaurants $area")
            "coffee"->listOf("coffee shops $area","cafes $area")
            "organic food"->listOf("organic food $area","organic grocery stores $area")
            else->{val plan=PlaceSearchVocabulary.parse(query.text)
                val original=query.text.trim().take(256)
                listOf("$original $area",plan.alternative()?.let {"$it $area"} ?: "$topic $area website address contact")
            }
        }.distinct().map {it.take(512)}
    }
    fun text(business:LocalBusiness)=listOf(business.name,business.category,business.locality,business.address,business.keywords.joinToString(" "),business.description,LocalBusinessDetails.facts(business).filter {it.field in LocalBusinessDetails.text}.joinToString(" "){it.value}).joinToString(" ")
    fun matches(query:String,business:LocalBusiness)=relevance(query,business)<9
    fun relevance(query:String,business:LocalBusiness):Int {
        if(query.isBlank())return 0
        val plan=PlaceSearchVocabulary.parse(query);val literal=plan.literal
        val named=plan.terms.none {it.excluded}&&literal.positive.isNotEmpty()&&literal.matches(business.name)
        if(!named&&!plan.matches(business))return 9
        return when {
            named->0
            literal.matches(business.category+" "+business.keywords.joinToString(" "))->1
            else->2
        }
    }
    /** Favour the current query's known owners and indexed pages, not old searches' queues. */
    fun sourcePriority(source:LocalPublisherSource,query:LocalDiscoveryQuery,owner:LocalBusiness?,current:Set<String>):Int {
        val relevance=owner?.let {relevance(query.text,it)} ?: if(source.url in current)0 else 9
        val kind=if(query.mode==LocalSearchMode.EVENTS&&LocalCalendarPlatforms.isCalendar(source))-2 else if(source.kind==LocalSourceKind.SOCIAL)2 else 0
        return relevance*10+kind+if(source.url in current)-3 else 0
    }
}
