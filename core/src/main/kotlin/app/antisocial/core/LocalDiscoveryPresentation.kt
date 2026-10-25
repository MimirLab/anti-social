/* anti social, GPL-3.0-or-later. Read projections keep locations and source records intact. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalDiscoveryPresentation {
    private val spaces=Regex("[\\s\\u00a0]+")
    fun text(value:String)=value.trim().replace(spaces," ")
    fun url(value:String,clean:(String)->String={it}):String?=runCatching {LocalDiscoveryIdentity.url(clean(value))}.getOrNull()
    data class Source(val url:String,val fields:List<String>,val excerpts:List<String>,val licenses:List<String>,val images:List<String>,val checkedAt:Long)
    /** Refresh timestamps and tracking variants are not new evidence. Different
     * values and image regions remain in the stored source record. */
    fun sources(evidence:List<LocalFieldEvidence>,clean:(String)->String={it}):List<Source> {
        val shown=mutableSetOf<String>()
        return evidence.groupBy {url(it.sourceUrl,clean) ?: it.sourceUrl}.entries.map {(url,rows)->
            val latest=rows.sortedByDescending {it.checkedAt}
            val excerpts=latest.filter {it.kind in setOf(LocalEvidenceKind.PUBLIC_POST,LocalEvidenceKind.POSTER)}
                .map {text(it.excerpt)}.filter {it.isNotBlank()&&shown.add(it)}.take(5)
            Source(url,latest.map {text(it.field)}.distinctBy {it.lowercase()}.sorted(),excerpts,
                latest.mapNotNull {it.license?.let(::text)?.takeIf(String::isNotBlank)}.distinct(),
                latest.mapNotNull {it.region?.imageUrl}.distinct(),latest.maxOf {it.checkedAt})
        }
    }
    data class EventLink(val url:String,val registration:Boolean)
    fun eventLinks(event:LocalEvent,clean:(String)->String={it}):List<EventLink> =
        (listOfNotNull(event.registrationUrl?.let {it to true})+event.urls.map {it to false}).mapNotNull {(raw,registration)->
            url(raw,clean)?.let {EventLink(it,registration)}
        }.distinctBy {it.url}.take(12)
}

data class LocalBusinessGroup(val id:String,val locations:List<LocalBusiness>) {
    val primary:LocalBusiness get()=locations.first()
    val title:String get()=if(locations.size==1)primary.name else LocalBusinessDeck.label(primary)
}
/** Group the reading deck, never merge branch facts into another location.
 * Shared names need a declared brand or a compatible first-party website. */
object LocalBusinessDeck {
    fun label(b:LocalBusiness)=b.name.substringBefore(" - ").substringBefore(" – ").substringBefore(" — ").replace(Regex("(?i)\\s+at\\s+.+$"),"")
    private fun name(b:LocalBusiness)=LocalDiscoveryMerge.businessName(label(b))
    private fun brand(b:LocalBusiness)=b.evidence.firstOrNull {it.field=="brand id"&&it.value.matches(Regex("Q[0-9]+"))}?.value
    private fun domains(b:LocalBusiness)=b.websites.mapNotNull {raw->raw.toHttpUrlOrNull()?.host?.removePrefix("www.")?.takeIf {host->
        val label=b.name.substringBefore(" - ").substringBefore(" – ").substringBefore(" — ").replace(Regex("(?i)\\s+at\\s+.+$"),"")
        !LocalBusinessDetails.directorySite(b,raw)&&(LocalBusinessOwnership.firstParty(label,raw)||LocalDiscoveryIdentity.words(label).any {word->word.length>=5&&host.split('.').any {it==word}})
    }}.toSet()
    private fun key(b:LocalBusiness):String?=brand(b)?.let {"brand:$it"} ?: domains(b).sorted().firstOrNull()?.let {"site:$it:${name(b)}"}
    fun groups(values:List<LocalBusiness>):List<LocalBusinessGroup> {
        val groups=mutableListOf<MutableList<LocalBusiness>>()
        values.forEach {business->
            val match=groups.firstOrNull {locations->(brand(business)==null||locations.mapNotNull(::brand).none {it!=brand(business)})&&locations.any {other->
                val a=brand(business);val b=brand(other)
                if(a!=null&&b!=null)a==b else name(business).isNotBlank()&&name(business)==name(other)&&domains(business).intersect(domains(other)).isNotEmpty()
            }}
            if(match==null)groups+=mutableListOf(business)else match+=business
        }
        return groups.map {locations->LocalBusinessGroup(key(locations.first())?.let {"chain:"+LocalDiscoveryIdentity.hash(it)} ?: locations.first().id,locations)}
    }
}
