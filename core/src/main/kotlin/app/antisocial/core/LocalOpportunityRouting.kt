/* anti social, GPL-3.0-or-later. Small, geographic routes into useful local publishers. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos

data class LocalCoverageBox(val south:Double,val west:Double,val north:Double,val east:Double,val country:String) {
    fun overlaps(area:LocalArea):Boolean = area.valid()&&(LocalGeography.country(area)?.let {it==country} ?: true)&&
        area.point.distanceKm(LocalPoint(area.point.latitude.coerceIn(south,north),area.point.longitude.coerceIn(west,east)))<=area.radiusKm
}

object LocalOpportunityRouting {
    private val brevard=LocalCoverageBox(27.82,-81.01,28.88,-80.35,"US")
    private val orlando=LocalCoverageBox(28.33,-81.68,28.82,-81.03,"US")
    private val helsinki=LocalCoverageBox(60.08,24.45,60.43,25.35,"FI")
    private val family=setOf("kids","children","child","family","families","homeschool","storytime","puppet")
    private val practical=setOf("garden","gardening","farm","farming","food","agriculture","nutrition","marine","workshop","workshops","class","classes")
    private fun words(query:LocalDiscoveryQuery)=LocalDiscoveryIdentity.words(LocalDiscoveryFilter.eventQuery(query.text)).toSet()

    /** These are publisher coverage areas, not invented event locations. An
     * occurrence still needs its own venue inside the requested radius. */
    fun seeds(query:LocalDiscoveryQuery):List<LocalPublisherSource> {
        if(query.mode!=LocalSearchMode.EVENTS||query.savedOnly)return emptyList()
        val terms=words(query);val broad=terms.isEmpty()
        return buildList {
            if(brevard.overlaps(query.area)) {
                if(broad||terms.any(family::contains))add(LocalPublisherSource("https://fun4spacecoastkids.com/calendar/",label="Space Coast family calendar"))
                if(broad||terms.any(practical::contains))add(LocalPublisherSource("https://sfyl.ifas.ufl.edu/brevard/",label="Brevard Extension classes"))
            }
            if(helsinki.overlaps(query.area))add(LocalPublisherSource("https://api.hel.fi/linkedevents/v1/event/",LocalSourceKind.LINKED_EVENTS,label="Helsinki public events"))
        }.take(2)
    }
    /** Reject known out-of-area families before fetching, including linked
     * sister sites. Unknown publishers still get the existing bounded read. */
    fun allowed(source:LocalPublisherSource,query:LocalDiscoveryQuery):Boolean {
        val u=source.url.toHttpUrlOrNull() ?: return false
        if(u.pathSegments.any {it.lowercase() in setOf("login","sign-in","signin","wp-admin","icalevent_edit","icals.icalevent_edit","eventsubmission","submit-event")})return false
        if(u.host.removePrefix("www.") in setOf("fun4spacecoastkids.com","fun4orlandokids.com")) {
            if(u.encodedPath.contains("/eventsbyyear/"))return false
            Regex("/eventsby(day|week|month)/(\\d{4})/(\\d{1,2})/(\\d{1,2})(?:/|$)").find(u.encodedPath)?.let {m->
                val date=runCatching {LocalDate.of(m.groupValues[2].toInt(),m.groupValues[3].toInt(),m.groupValues[4].toInt())}.getOrNull() ?: return false
                val start=if(m.groupValues[1]=="month")date.withDayOfMonth(1)else date
                val end=when(m.groupValues[1]){"month"->start.plusMonths(1).minusDays(1);"week"->start.plusDays(6);else->start}
                val (first,last)=query.dates()
                if(start>last||end<first)return false
            }
        }
        return when(u.host.removePrefix("www.")) {
            "fun4spacecoastkids.com"->brevard.overlaps(query.area)
            "fun4orlandokids.com"->orlando.overlaps(query.area)
            "api.hel.fi"->!u.encodedPath.startsWith("/linkedevents/")||helsinki.overlaps(query.area)
            "sfyl.ifas.ufl.edu"->u.queryParameter("location")!="brevard"&&!u.encodedPath.startsWith("/brevard/")||brevard.overlaps(query.area)
            else->true
        }
    }
    /** One wrong search hit may be worth inspecting; its explicitly distant
     * address must not lead to further contact/calendar/pagination requests. */
    fun scoped(source:LocalPublisherSource,query:LocalDiscoveryQuery,data:LocalExtraction):LocalExtraction {
        val kept=data.sources.filter {allowed(it,query)}
        val local=data.businesses.any {LocalDiscoveryFilter.business(query,it)}||data.events.any {LocalDiscoveryFilter.geographic(query.area,it)}
        val distant=data.businesses.any {it.point!=null||!LocalGeography.compatible(query.area,it.address,it.evidence)}||data.events.any {it.point!=null||!LocalGeography.compatible(query.area,it.address,it.evidence)}
        val regional=seeds(query).any {seed->source.url.startsWith(seed.url)}
        return data.copy(sources=if(distant&&!local&&!regional)emptyList()else kept)
    }
    fun focus(query:LocalDiscoveryQuery):String? {
        if(query.mode!=LocalSearchMode.EVENTS)return null
        val terms=words(query)
        return when {
            terms.any(family::contains)->"library parks family activities calendar"
            terms.any {it in setOf("maker","makerspace","hackerspace","repair","workshop","workshops") }->"makerspace repair workshops community calendar"
            terms.any {it in setOf("astronomy","stars","stargazing","science") }->"astronomy science museum public activities calendar"
            terms.any {it in setOf("volunteer","volunteering") }->"local volunteer organizations events calendar"
            terms.any {it in setOf("garden","gardening","farm","farming") }->"extension gardening farmers market classes calendar"
            terms.isEmpty()->"library parks community workshops calendar"
            else->null
        }
    }
    /** Follow real links in known publisher formats, never navigation to every
     * city or the national directories behind them. No snippets become events. */
    fun sources(body:String,url:String,query:LocalDiscoveryQuery,at:Long):List<LocalPublisherSource> {
        val page=Jsoup.parse(body,url);val root=url.toHttpUrlOrNull() ?: return emptyList()
        val found=mutableListOf<LocalPublisherSource>()
        val (first,last)=query.dates(at)
        if(page.select("script[src*='/com_jevents/']").isNotEmpty()) {
            page.select("a.cal_titlelink[href*='/eventdetail/']").take(300).forEach {a->
                val stamp=a.parents().firstNotNullOfOrNull {node->Regex("(?:^|\\s)jevstart_(\\d{9,12})(?:\\s|$)").find(node.className())?.groupValues?.get(1)?.toLongOrNull()}
                if(stamp!=null) {
                    val day=runCatching {Instant.ofEpochSecond(stamp).atZone(ZoneId.of(query.area.zoneId ?: "UTC")).toLocalDate()}.getOrNull() ?: return@forEach
                    if(day<first||day>last)return@forEach
                }
                if(!LocalEventIntent.parse(query.text).matchText(a.text(),linkOnly=true).matches)return@forEach
                val target=LocalDiscoveryIdentity.url(a.absUrl("href"),url) ?: return@forEach
                if(target.toHttpUrlOrNull()?.host==root.host)found+=LocalPublisherSource(target,label=a.text().take(120),discoveredFrom=url)
            }
        }
        if(root.host=="sfyl.ifas.ufl.edu"&&root.encodedPath.startsWith("/brevard/"))Regex("https://[a-z0-9-]+\\.eventbrite\\.com(?=[/\\s<]|$)",RegexOption.IGNORE_CASE).findAll(page.text()).take(2).forEach {found+=LocalPublisherSource(it.value,label="Published Extension classes",discoveredFrom=url)}
        // Restrict hosted calendars to a published tenant/event, not a provider
        // homepage. These links often connect Extension offices and libraries.
        page.select("a[href],link[href]").take(1000).forEach {a->
            val target=LocalDiscoveryIdentity.url(a.absUrl("href"),url) ?: return@forEach
            val u=target.toHttpUrlOrNull() ?: return@forEach
            val label=a.text()+" "+a.attr("title")
            val calendar=Regex("calendar|events|classes|workshops|programs",RegexOption.IGNORE_CASE).containsMatchIn(label)
            val hosted=(u.host.endsWith(".eventbrite.com")&&u.host !in setOf("www.eventbrite.com","api.eventbrite.com"))||
                (u.host=="www.eventbrite.com"&&u.encodedPath.startsWith("/e/"))||
                (u.host.endsWith(".tidyhq.com")&&u.encodedPath.startsWith("/public/events"))||
                (u.host.endsWith(".libcal.com")&&u.encodedPath.length>1)||
                (u.host in setOf("www.mobilize.us","mobilize.us")&&u.pathSegments.filter(String::isNotBlank).isNotEmpty())
            if(calendar&&hosted)found+=LocalPublisherSource(target,label=a.text().take(120),discoveredFrom=url)
        }
        return found.distinctBy {it.url}.take(8)
    }
    fun linkedRequest(source:LocalPublisherSource,query:LocalDiscoveryQuery):String {
        require(source.kind==LocalSourceKind.LINKED_EVENTS&&allowed(source,query))
        val p=query.area.point;val d=query.area.radiusKm/111.195;val lon=d/cos(Math.toRadians(p.latitude)).coerceAtLeast(.1)
        val (first,last)=query.dates()
        // Server bounding box precedes the exact local circular distance test.
        return requireNotNull(source.url.toHttpUrlOrNull()).newBuilder().query(null)
            .setQueryParameter("bbox",listOf(p.longitude-lon,p.latitude-d,p.longitude+lon,p.latitude+d).joinToString(","))
            .setQueryParameter("start",first.toString()).setQueryParameter("end",last.plusDays(1).toString())
            .setQueryParameter("include","location").setQueryParameter("page_size","50").build().toString()
    }
}
