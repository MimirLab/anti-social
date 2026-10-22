/* AntiSocial, GPL-3.0-or-later. Discover advertised public calendar families without executing embeds. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object LocalCalendarPlatforms {
    fun sources(body:String,url:String,owner:String?):List<LocalPublisherSource> {
        val page=Jsoup.parse(body,url);val root=url.toHttpUrlOrNull() ?: return emptyList();val found=mutableListOf<LocalPublisherSource>()
        val scripts=page.select("script[src]").mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("src"),url)}
        val localist=scripts.any {it.toHttpUrlOrNull()?.host?.let {h->h=="localist.com"||h.endsWith(".localist.com")||h=="localist-images.azureedge.net"}==true}||page.select("meta[name=generator],meta[name=application-name]").any {it.attr("content").contains("localist",true)}||page.select("a[href]").any {it.attr("href").contains("/api/2/events")}
        if(localist)found+=LocalPublisherSource(root.newBuilder().encodedPath("/api/2/events").query(null).fragment(null).build().toString(),LocalSourceKind.LOCALIST,owner,"Public calendar")
        val trumba=scripts.mapNotNull {it.toHttpUrlOrNull()?.host}.firstOrNull {it=="www.trumba.com"||it=="trumba.com"||it=="25livepub.collegenet.com"}
        if(trumba!=null)page.select("script:not([src])").take(32).forEach {script->
            Regex("\\bwebName\\s*:\\s*['\"]([A-Za-z0-9_-]{1,150})['\"]").findAll(script.data().take(64000)).take(4).forEach {m->
                found+=LocalPublisherSource("https://$trumba/calendars/${m.groupValues[1]}.ics",LocalSourceKind.CALENDAR,owner,"Public calendar")
            }
        }
        // Offered feed links are already handled generically. Only use the
        // conventional Events Manager export when the installed plugin is visible.
        scripts.firstOrNull {it.contains("/wp-content/plugins/events-manager/") }?.let {script->
            val origin=script.substringBefore("/wp-content/")
            found+=LocalPublisherSource("$origin/events.ics",LocalSourceKind.CALENDAR,owner,"Public calendar")
        }
        return found.distinctBy {it.url}.take(8)
    }
    fun request(source:LocalPublisherSource,query:LocalDiscoveryQuery):String {
        val u=source.url.toHttpUrlOrNull() ?: return source.url;val (first,last)=query.dates()
        val build=u.newBuilder()
        if(source.kind==LocalSourceKind.LOCALIST)return build.setQueryParameter("start",first.toString()).setQueryParameter("end",last.toString()).setQueryParameter("pp","50").setQueryParameter("page",u.queryParameter("page")?.takeIf {it in setOf("1","2")} ?: "1").build().toString()
        if(u.host in setOf("trumba.com","www.trumba.com","25livepub.collegenet.com")&&u.encodedPath.endsWith(".ics"))return build.setQueryParameter("startdate",first.format(DateTimeFormatter.BASIC_ISO_DATE)).setQueryParameter("enddate",last.format(DateTimeFormatter.BASIC_ISO_DATE)).setQueryParameter("previousweeks","0").build().toString()
        return source.url
    }
    fun window(source:LocalPublisherSource,query:LocalDiscoveryQuery):String? {
        val host=source.url.toHttpUrlOrNull()?.host
        return if(source.kind==LocalSourceKind.LOCALIST||source.kind==LocalSourceKind.CALENDAR&&host in setOf("trumba.com","www.trumba.com","25livepub.collegenet.com"))query.dates().let {"${it.first}/${it.second}"}else null
    }
    fun localist(body:String,url:String,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val root=JsonParser.parseString(body).asJsonObject
        require(root["events"]?.isJsonArray==true){"This source did not return a public events feed"}
        val result=mutableListOf<LocalEvent>()
        fun str(o:JsonObject,key:String)=o[key]?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        root["events"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(100)?.forEach {raw->
            val e=raw.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("event")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
            if(str(e,"private")=="true"||str(e,"rejected")=="true")return@forEach
            val id=str(e,"id");val title=LocalDocumentWashing.text(str(e,"title"),400);if(id.isBlank()||title.isBlank())return@forEach
            val geo=e["geo"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val point=geo?.let {g->runCatching {LocalPoint(str(g,"latitude").toDouble(),str(g,"longitude").toDouble()).takeIf(LocalPoint::valid)}.getOrNull()}
            val address=str(e,"address");val city=LocalDiscoveryFilter.addressLocality(area,geo?.let {str(it,"city")}.orEmpty(),address)
            val link=LocalDiscoveryIdentity.url(str(e,"localist_url"),url) ?: LocalDiscoveryIdentity.url(str(e,"url"),url) ?: url
            val audiences=e["filters"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("event_audiences")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(8)?.mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {a->str(a,"name")}.takeUnless {it.isNullOrBlank()}}
            val description=(LocalDocumentWashing.text(str(e,"description_text").ifBlank {str(e,"description")},7000)+audiences?.takeIf {it.isNotEmpty()}?.joinToString(", ",prefix="\nAudience: ").orEmpty()).take(8000)
            val price=LocalDocumentWashing.text(str(e,"ticket_cost"),1000).ifBlank {if(str(e,"free")=="true")"Free"else ""}
            e["event_instances"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(100)?.forEach instances@{rawInstance->
                val i=rawInstance.asJsonObject["event_instance"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@instances
                val allDay=str(i,"all_day")=="true";val startLiteral=str(i,"start");val endLiteral=str(i,"end")
                val start=LocalEventTime(if(allDay)startLiteral.take(10)else startLiteral,area.zoneId,allDay).takeIf(LocalEventTime::valid) ?: return@instances
                val end=if(endLiteral.isBlank())null else LocalEventTime(if(allDay)endLiteral.take(10)else endLiteral,area.zoneId,allDay).takeIf(LocalEventTime::valid)
                val instance=str(i,"id").ifBlank {start.identity};val identity="localist:"+url.toHttpUrlOrNull()?.host+":"+id+":"+instance
                result+=LocalEvent("event:"+LocalDiscoveryIdentity.hash(identity),title,start,end,description,str(e,"location_name").ifBlank {str(e,"location")},address,city,point,urls=listOf(link),registrationUrl=LocalDiscoveryIdentity.url(str(e,"ticket_url"),url),price=price,
                    status=if(str(e,"status").contains("cancel",true))"cancelled"else "scheduled",online=str(e,"experience")=="virtual",sourceIds=listOf(identity),checkedAt=at,
                    modifiedAt=LocalEventTime(str(e,"updated_at").replace(' ','T'),area.zoneId).instant()?.toEpochMilli(),
                    evidence=listOf(LocalDiscoveryIdentity.evidence("occurrence",start.value,link,at,LocalEvidenceKind.CALENDAR),LocalDiscoveryIdentity.evidence("description",description,link,at,LocalEvidenceKind.CALENDAR)))
            }
        }
        val next=root["page"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("next_page")?.takeIf(JsonElement::isJsonPrimitive)?.asString
        val following=if(next=="2")listOfNotNull(url.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/api/2/events")?.query(null)?.setQueryParameter("page","2")?.build()?.toString()?.let {LocalPublisherSource(it,LocalSourceKind.LOCALIST,label="Public calendar, next page")})else emptyList()
        return LocalExtraction(events=result.take(512),sources=following,validEmpty=true,notice=if(next!=null&&next!="2")"This calendar contains more entries than this bounded check; open its source for the full listing."else null)
    }
}
