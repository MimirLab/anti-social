/* AntiSocial, GPL-3.0-or-later. Field-directed routing; no model or speculative facts. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class LocalBusinessField(val key:String,val weight:Int) {
    WEBSITE("website",8), PHONE("phone",8), ACCOUNTS("accounts",7), HOURS("hours",5),
    EMAIL("email",2), VISIT("visit",3), ABOUT("about",1), MENU("menu",4)
}
data class LocalRouteYield(val fields:List<String> = emptyList(),val fingerprint:String="",val stable:Int=0,
    val failures:Int=0,val elapsedMs:Long=0,val checkedAt:Long=0,val nextAt:Long=0)

object LocalBusinessEnrichment {
    fun snapshot(b:LocalBusiness):Map<LocalBusinessField,String> = linkedMapOf(
        LocalBusinessField.WEBSITE to b.websites.filterNot {LocalBusinessDetails.directorySite(b,it)}.sorted().joinToString("|"),
        LocalBusinessField.PHONE to b.phones.mapNotNull(LocalBusinessContact::phoneKey).distinct().sorted().joinToString("|"),
        LocalBusinessField.ACCOUNTS to b.socials.map {it.trimEnd('/')}.distinct().sorted().joinToString("|"),
        LocalBusinessField.HOURS to b.hours,
        LocalBusinessField.EMAIL to b.emails.orEmpty().sorted().joinToString("|"),
        LocalBusinessField.VISIT to LocalBusinessDetails.facts(b).joinToString("|"){it.field+":"+it.value},
        LocalBusinessField.ABOUT to b.description,
        LocalBusinessField.MENU to b.menus.orEmpty().filterNot {it.title=="Menu highlights"&&it.partial}.joinToString("|"){it.fingerprint}
    )
    fun missing(b:LocalBusiness)=snapshot(b).filterValues(String::isBlank).keys.filterNot {it==LocalBusinessField.MENU&&!LocalMenuRouting.restaurant(b)}.toSet()
    fun purpose(source:LocalPublisherSource):Set<LocalBusinessField> {
        val text=(source.url+" "+source.label).lowercase()
        return when {
            source.kind==LocalSourceKind.MENU->setOf(LocalBusinessField.MENU)
            source.kind==LocalSourceKind.SOCIAL->setOf(LocalBusinessField.ACCOUNTS,LocalBusinessField.WEBSITE,LocalBusinessField.ABOUT,LocalBusinessField.PHONE,LocalBusinessField.EMAIL,LocalBusinessField.HOURS,LocalBusinessField.MENU)
            Regex("contact|about|location|hours").containsMatchIn(text)->setOf(LocalBusinessField.PHONE,LocalBusinessField.EMAIL,LocalBusinessField.HOURS,LocalBusinessField.ACCOUNTS,LocalBusinessField.ABOUT)
            Regex("menu|reserv|booking|services|accessib|order").containsMatchIn(text)->setOf(LocalBusinessField.VISIT,LocalBusinessField.HOURS)
            else->LocalBusinessField.entries.toSet()
        }
    }
    fun priority(source:LocalPublisherSource,owner:LocalBusiness?,history:LocalRouteYield?):Int {
        if(owner==null)return 0 // discovery pages still get a chance to establish identity
        val need=missing(owner)
        val known=history?.fields.orEmpty().mapNotNull {key->LocalBusinessField.entries.firstOrNull {it.key==key}}.toSet()
        val possible=if(known.isNotEmpty())known else purpose(source)
        return -need.intersect(possible).sumOf {it.weight}+(history?.failures ?: 0)*8+(history?.stable ?: 0)*2+
            if(source.kind==LocalSourceKind.MENU)LocalMenuRouting.priority(source,owner)else 0
    }
    fun query(business:LocalBusiness,area:LocalArea):String? {
        val needs=missing(business)
        val terms=when {
            LocalBusinessField.WEBSITE in needs||LocalBusinessField.PHONE in needs->"website contact phone"
            LocalBusinessField.ACCOUNTS in needs->"official social accounts"
            LocalBusinessField.HOURS in needs->"opening hours"
            LocalBusinessField.VISIT in needs->"menu services reservations"
            LocalBusinessField.MENU in needs->"menu "+LocalDiscoveryMerge.street(business.address).orEmpty()
            else->return null
        }
        return ("\""+business.name.replace('"',' ').take(160)+"\" "+area.label+" "+terms).take(512)
    }
    fun observe(old:LocalRouteYield?,data:LocalExtraction,elapsed:Long,at:Long,calendar:Boolean=false):LocalRouteYield {
        val fields=data.businesses.flatMap {snapshot(it).filterValues(String::isNotBlank).keys}.map {it.key}.distinct().sorted()
        val content=data.businesses.sortedBy {it.id}.joinToString("\n") {it.id+snapshot(it).values.joinToString("|")}+
            data.events.sortedBy {it.id}.joinToString("\n"){listOf(it.id,it.title,it.start.identity,it.end?.identity,it.status,it.description,it.venue,it.address,it.locality,it.point,it.price,it.age,it.registrationUrl,it.organizer,it.organizerUrl).joinToString("|")}+
            data.sources.map {it.url}.sorted().joinToString("|")
        val fingerprint=LocalDiscoveryIdentity.hash(content)
        val stable=if(old?.fingerprint==fingerprint)old.stable+(if(at-old.checkedAt>=15*60000)1 else 0)else 0
        // Prices/specials can change faster than a stable identity/contact page.
        val menu=data.businesses.any {it.menus.orEmpty().isNotEmpty()}
        val base=if(calendar)30L*60000 else 6L*3600000
        val delay=(base*(1L shl stable.coerceIn(0,5))).coerceAtMost(if(calendar)6L*3600000 else if(menu)86400000 else 7L*86400000)
        return LocalRouteYield(fields,fingerprint,stable.coerceAtMost(12),0,elapsed.coerceIn(0,120000),at,at+delay)
    }
    fun failed(old:LocalRouteYield?,at:Long):LocalRouteYield {
        val failures=((old?.failures ?: 0)+1).coerceAtMost(12)
        return (old ?: LocalRouteYield()).copy(failures=failures,checkedAt=at,nextAt=at+(15L*60000*(1L shl (failures-1).coerceAtMost(7))).coerceAtMost(86400000))
    }
    fun key(source:LocalPublisherSource)="local-yield:v1:"+LocalDiscoveryIdentity.hash(source.url)
}

/** Visit details use the existing field-evidence record, including saved/exported copies. */
object LocalBusinessDetails {
    fun verifiedWebsite(b:LocalBusiness,url:String):Boolean {
        val host=url.toHttpUrlOrNull()?.host ?: return false
        val street=LocalDiscoveryMerge.street(b.address) ?: return false
        return !LocalMenuRouting.directory(url)&&b.related.orEmpty().none {it.role=="source publisher"&&it.sourceUrl.toHttpUrlOrNull()?.host==host}&&
            b.evidence.any {it.field=="address"&&it.sourceUrl.toHttpUrlOrNull()?.host==host&&LocalDiscoveryMerge.street(it.value)==street}
    }
    fun websitePriority(b:LocalBusiness,url:String)=when {LocalMenuRouting.directory(url)->30;verifiedWebsite(b,url)->0;LocalBusinessOwnership.firstParty(b.name,url)->4;LocalBusinessOwnership.directoryLink(b,url)->20;else->8}
    fun directorySite(b:LocalBusiness,url:String)=LocalMenuRouting.directory(url)||LocalBusinessOwnership.directoryLink(b,url)&&!verifiedWebsite(b,url)
    val links=linkedMapOf("menu" to "Menu","branch menu" to "Menu","booking" to "Reservations","ordering" to "Order","services page" to "Services","calendar" to "Events")
    val text=linkedMapOf("cuisine" to "Cuisine","price range" to "Price range","services" to "Services","visit notes" to "Visiting","accessibility" to "Accessibility","payment" to "Payment","special hours" to "Special hours")
    fun facts(b:LocalBusiness):List<LocalFieldEvidence> = b.evidence.filter {(it.field in links||it.field in text)&&(it.field !in setOf("menu","branch menu","ordering")||LocalMenuRouting.admissible(it,b))}.sortedWith(compareBy<LocalFieldEvidence>{it.kind.ordinal}.thenByDescending {it.checkedAt})
        .distinctBy {if(it.field in links)it.field+":"+it.value else it.field}.take(20)
    fun trustedHost(b:LocalBusiness,url:String):Boolean {
        val host=url.toHttpUrlOrNull()?.host ?: return false
        return b.websites.any {it.toHttpUrlOrNull()?.host==host&&!LocalBusinessOwnership.directoryLink(b,it)}&&LocalBusinessOwnership.firstParty(b.name,url)
    }
}
