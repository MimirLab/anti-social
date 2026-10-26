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

data class LocalBusinessGroup(val id:String,val locations:List<LocalBusiness>,val brandName:String?=null) {
    val primary:LocalBusiness get()=locations.first()
    val title:String get()=if(locations.size==1)primary.name else brandName ?: LocalBusinessDeck.label(primary)
}
/** Reading groups preserve every branch record. Brand declarations are scoped
 * evidence; neither a shared operator nor an unqualified name establishes one. */
object LocalBusinessDeck {
    private val atSuffix=Regex("(?i)\\s+at\\s+.+$")
    private val brandId=Regex("Q[1-9][0-9]{0,18}")
    private val legacyTags=setOf("cuisine","organic","diet:vegan","diet:vegetarian","wheelchair","brand","operator")
    fun label(b:LocalBusiness)=b.name.substringBefore(" - ").substringBefore(" – ").substringBefore(" — ").replace(atSuffix,"")
    private data class Identity(val business:LocalBusiness,val name:String,val ids:Set<String>,val brands:Map<String,String>,val domains:Set<String>) {
        val ambiguous:Boolean get()=ids.size>1||ids.isEmpty()&&brands.size>1
    }
    private fun identity(b:LocalBusiness):Identity {
        val declared=b.evidence.filter {it.kind in setOf(LocalEvidenceKind.DIRECTORY,LocalEvidenceKind.PUBLISHER)}
        val ids=declared.filter {it.field=="brand id"&&brandId.matches(it.value)}.map {it.value}.toSet()
        // The prior OSM reader stored strict tag/value pairs as keywords. Reuse
        // an intact scoped brand pair in cached rows; never guess from free text.
        val legacy=if(b.sourceIds.any {it.startsWith("osm:")}&&b.keywords.size in 2..32&&b.keywords.size%2==0&&
            b.keywords.chunked(2).all {it[0] in legacyTags})b.keywords.chunked(2).firstOrNull {it[0]=="brand"}?.get(1)else null
        val brands=(declared.filter {it.field=="brand"}.sortedByDescending {it.checkedAt}.map {it.value}+
            b.related.orEmpty().filter {it.role=="brand"}.map {it.name}+listOfNotNull(legacy)).map {it.trim().take(300)}.filter {it.isNotBlank()}
            .distinctBy {LocalDiscoveryMerge.businessName(it)}.associateBy {LocalDiscoveryMerge.businessName(it)}.filterKeys(String::isNotBlank)
        val label=label(b)
        val domains=b.websites.mapNotNull {raw->raw.toHttpUrlOrNull()?.host?.removePrefix("www.")?.takeIf {host->
            !LocalBusinessDetails.directorySite(b,raw)&&(LocalBusinessOwnership.firstParty(label,raw)||LocalDiscoveryIdentity.words(label).any {word->word.length>=5&&host.split('.').any {it==word}})
        }}.toSet()
        return Identity(b,LocalDiscoveryMerge.businessName(label),ids,brands,domains)
    }
    /** Prepare URL/name keys once, then join bounded index buckets. Weak links
     * with conflicting brand IDs never attach an ambiguous record to either one. */
    fun groups(values:List<LocalBusiness>,origin:LocalPoint?=null):List<LocalBusinessGroup> {
        val identities=values.map(::identity)
        val parent=IntArray(values.size){it}
        fun root(parents:IntArray,value:Int):Int {var n=value;while(parents[n]!=n){parents[n]=parents[parents[n]];n=parents[n]};return n}
        fun join(parents:IntArray,a:Int,b:Int){val x=root(parents,a);val y=root(parents,b);if(x!=y)parents[maxOf(x,y)]=minOf(x,y)}
        val strong=mutableMapOf<String,Int>()
        val buckets=linkedMapOf<String,MutableSet<Int>>()
        identities.forEachIndexed {i,item->
            item.ids.singleOrNull()?.let {id->strong.putIfAbsent(id,i)?.let {join(parent,i,it)}}
            if(!item.ambiguous) {
                if(item.name.isNotBlank()) {
                    item.domains.forEach {domain->buckets.getOrPut("site:$domain:${item.name}"){linkedSetOf()}.add(i)}
                    buckets.getOrPut("brand:${item.name}"){linkedSetOf()}.add(i)
                }
                item.brands.keys.forEach {brand->buckets.getOrPut("brand:$brand"){linkedSetOf()}.add(i)}
            }
        }
        val candidates=mutableListOf<Set<Int>>()
        for((key,rows) in buckets) {
            if(rows.size<2)continue
            if(key.startsWith("brand:")) {
                val brand=key.removePrefix("brand:")
                if(rows.none {brand in identities[it].brands})continue
                // Different official sites can be unrelated businesses sharing a
                // name. A missing-site record must not bridge those sites either.
                if(rows.flatMap {identities[it].domains}.distinct().size>1)continue
            }
            candidates+=rows
        }
        // Inspect whole potential weak components before joining any of them.
        // Pairwise checks alone permit A -> unknown -> B through two buckets.
        val potential=parent.copyOf()
        candidates.forEach {rows->val first=rows.first();rows.drop(1).forEach {join(potential,first,it)}}
        val claims=identities.indices.groupBy {root(potential,it)}.mapValues {(_,rows)->rows.flatMap {identities[it].ids}.toSet()}
        candidates.forEach {rows->val first=rows.first();if(claims[root(potential,first)].orEmpty().size<=1)rows.drop(1).forEach {join(parent,first,it)}}
        val used=mutableSetOf<String>()
        return identities.indices.groupBy {root(parent,it)}.values.map {indexes->
            val entries=indexes.map {identities[it]}
            val ids=entries.flatMap {it.ids}.distinct()
            val brand=entries.flatMap {it.brands.entries}.firstOrNull()
            val first=entries.first()
            val key=ids.singleOrNull()?.let {"brand:$it"}
                ?: first.domains.sorted().firstOrNull()?.let {"site:$it:${first.name}"} ?: brand?.let {"brand-name:${it.key}"}
            val ambiguous=claims[root(potential,indexes.first())].orEmpty().size>1||first.ambiguous
            val proposed=if(ambiguous&&indexes.size==1)first.business.id else key?.let {"chain:"+LocalDiscoveryIdentity.hash(it)} ?: first.business.id
            val id=proposed.takeIf(used::add) ?: (proposed+":"+LocalDiscoveryIdentity.hash(first.business.id)).also {used.add(it)}
            val locations=entries.map {it.business}.let {rows->if(origin==null)rows else rows.sortedWith(
                compareBy<LocalBusiness>{it.point?.let(origin::distanceKm) ?: Double.POSITIVE_INFINITY}.thenBy {it.address}.thenBy {it.id})}
            LocalBusinessGroup(id,locations,brand?.value)
        }
    }
}
