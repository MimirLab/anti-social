/* anti social, GPL-3.0-or-later. Public structured opportunities, without a browser or model. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.ZoneId

object LocalOpportunityDocuments {
    private fun s(o:JsonObject?,key:String)=o?.get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
    private fun obj(o:JsonObject,key:String)=o[key]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun rows(o:JsonObject,key:String)=o[key]?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(100).orEmpty()
    private fun language(v:JsonElement?):String=when {v==null||v.isJsonNull->"";v.isJsonPrimitive->v.asString;v.isJsonObject->listOf("en","fi","sv").firstNotNullOfOrNull {s(v.asJsonObject,it).takeIf(String::isNotBlank)} ?: "";else->""}
    private fun clean(v:String,limit:Int=8000)=LocalDocumentWashing.text(v,limit)
    private fun yes(v:String)=v in setOf("true","1")
    private fun time(value:String,zone:String?,allDay:Boolean=false):LocalEventTime?=value.takeIf(String::isNotBlank)?.let {LocalEventTime(if(allDay)it.take(10)else it,zone,allDay).takeIf(LocalEventTime::valid)}
    private fun point(lat:String,lon:String)=runCatching {LocalPoint(lat.toDouble(),lon.toDouble()).takeIf(LocalPoint::valid)}.getOrNull()
    private fun fields(values:Map<String,String>,url:String,at:Long,kind:LocalEvidenceKind=LocalEvidenceKind.CALENDAR)=values.filterValues(String::isNotBlank).map {(key,value)->LocalDiscoveryIdentity.evidence(key,value,url,at,kind)}

    fun livewhale(body:String,url:String,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val root=JsonParser.parseString(body)
        val data=when {root.isJsonArray->root.asJsonArray.toList();root.isJsonObject->(root.asJsonObject["data"] ?: root.asJsonObject["results"])?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList();else->null}
            ?: throw SourceFailure(HealthStatus.EMPTY,"This source did not provide a public LiveWhale calendar")
        val events=data.take(100).mapNotNull {raw->
            val e=raw.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id=s(e,"id");val title=clean(s(e,"title"),400)
            if(id.isBlank()||title.isBlank()||s(e,"status")=="2")return@mapNotNull null
            val zone=s(e,"timezone").takeIf {runCatching {ZoneId.of(it)}.isSuccess}
            val allDay=yes(s(e,"is_all_day"));val start=time(s(e,"date_iso"),zone,allDay) ?: return@mapNotNull null
            val end=time(s(e,"date2_iso"),zone,allDay)
            val location=clean(s(e,"location"),1000);val venue=clean(s(e,"location_title"),1000).ifBlank {location}
            val geo=point(s(e,"location_latitude"),s(e,"location_longitude"))
            val locality=LocalDiscoveryFilter.addressLocality(area,"",location)
            val link=LocalDiscoveryIdentity.url(s(e,"url"),url) ?: url
            val description=clean(s(e,"description").ifBlank {s(e,"summary")})
            val identity="livewhale:${url.toHttpUrlOrNull()?.host}:$id:${start.date()}"
            val status=if(yes(s(e,"is_canceled")))"cancelled"else "scheduled"
            LocalEvent("event:"+LocalDiscoveryIdentity.hash(identity),title,start,end,description,venue,location,locality,geo,organizer=clean(s(e,"group_title"),300),urls=listOf(link),price=clean(s(e,"cost"),1000),status=status,online=yes(s(e,"is_online")),sourceIds=listOf(identity),checkedAt=at,
                modifiedAt=s(e,"last_modified").toLongOrNull()?.takeIf {it in 1..253402300799L}?.times(1000),
                evidence=fields(mapOf("title" to title,"start" to start.value,"venue" to venue,"address" to location,"description" to description,"status" to status),url,at)+listOfNotNull(geo?.let {LocalDiscoveryIdentity.evidence("location","${it.latitude},${it.longitude}",url,at,LocalEvidenceKind.CALENDAR)}))
        }
        val total=if(root.isJsonObject)obj(root.asJsonObject,"meta")?.let {s(it,"total_results").toIntOrNull()} ?: s(root.asJsonObject,"total_results").toIntOrNull()else null
        return LocalExtraction(events=events,validEmpty=true,notice=if(data.size>=50||(total ?: 0)>data.size)"This calendar check is limited; open its source for the complete programme."else null)
    }
    fun isSpaceApi(body:String):Boolean=runCatching {val o=JsonParser.parseString(body).asJsonObject;o.has("space")&&(o.has("api")||o.has("api_compatibility"))}.getOrDefault(false)
    fun space(body:String,url:String,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=512*1024&&isSpaceApi(body))
        val e=JsonParser.parseString(body).asJsonObject
        val name=clean(s(e,"space"),300);require(name.isNotBlank())
        val loc=obj(e,"location");val geo=point(s(loc,"lat"),s(loc,"lon"));val address=clean(s(loc,"address"),1000)
        val evidence=fields(mapOf("name" to name,"address" to address,"country" to s(loc,"country_code"),"visit notes" to clean(s(loc,"hint"),2000)),url,at,LocalEvidenceKind.PUBLISHER)
        if(!LocalGeography.inside(area,geo,LocalDiscoveryFilter.addressLocality(area,"",address),address,evidence))return LocalExtraction(validEmpty=true)
        val website=LocalDiscoveryIdentity.url(s(e,"url"),url) ?: throw SourceFailure(HealthStatus.EMPTY,"This community space did not identify its website")
        val identity="spaceapi:"+website.trimEnd('/');val contact=obj(e,"contact")
        val socials=buildList {
            LocalDiscoveryIdentity.url(s(contact,"facebook"))?.let(::add)
            s(contact,"twitter").removePrefix("@").takeIf {it.matches(Regex("[A-Za-z0-9_]{1,30}"))}?.let {add("https://x.com/$it")}
            s(contact,"mastodon").removePrefix("@").split('@').takeIf {it.size==2}?.let {p->LocalDiscoveryIdentity.url("https://${p[1]}/@${p[0]}")?.let(::add)}
        }.filter(LocalStructuredData::accountLink).take(8)
        val services=rows(e,"areas").mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject}.map {clean(s(it,"description").ifBlank {s(it,"name")},500)}.filter(String::isNotBlank).joinToString(" · ").take(2000)
        val business=LocalBusiness("place:"+LocalDiscoveryIdentity.hash(identity),name,"makerspace",address=address,locality=LocalDiscoveryFilter.addressLocality(area,"",address),point=geo,websites=listOf(website),socials=socials,
            phones=listOf(s(contact,"phone")).filter {LocalBusinessContact.phone(it)!=null},emails=listOfNotNull(LocalBusinessContact.email(s(contact,"email"))).takeIf {it.isNotEmpty()},sourceIds=listOf(identity),checkedAt=at,
            evidence=evidence+fields(mapOf("services" to services),url,at,LocalEvidenceKind.PUBLISHER)+listOfNotNull(geo?.let {LocalDiscoveryIdentity.evidence("location","${it.latitude},${it.longitude}",url,at,LocalEvidenceKind.PUBLISHER)}))
        val feeds=obj(e,"feeds")
        val sources=listOf("calendar","blog").mapNotNull {key->feeds?.let {obj(it,key)}?.let {feed->
            LocalDiscoveryIdentity.url(s(feed,"url"),url)?.let {link->LocalPublisherSource(link,if(s(feed,"type").lowercase() in setOf("ical","ics"))LocalSourceKind.CALENDAR else LocalSourceKind.WEB,business.id,"${business.name} public calendar",discoveredFrom=url)}
        }}
        // SpaceAPI's `events` are sensor/access logs, not public activities.
        // State.open is an ephemeral observation, not a durable closure claim.
        return LocalExtraction(businesses=listOf(business),sources=sources,validEmpty=true)
    }
    fun linkedEvents(body:String,url:String,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val root=JsonParser.parseString(body).asJsonObject
        require(root["data"]?.isJsonArray==true){"This source did not supply public event data"}
        val events=rows(root,"data").mapNotNull {raw->
            val e=raw.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            if(yes(s(e,"deleted")))return@mapNotNull null
            val id=s(e,"id");val title=clean(language(e["name"]),400)
            val start=time(s(e,"start_time"),null) ?: return@mapNotNull null
            if(id.isBlank()||title.isBlank())return@mapNotNull null
            val loc=obj(e,"location") ?: return@mapNotNull null
            val coords=obj(loc,"position")?.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            val geo=runCatching {if(coords?.size()==2)point(coords[1].asString,coords[0].asString)else null}.getOrNull()
            val city=clean(language(loc["address_locality"]),300)
            val address=listOf(language(loc["street_address"]),s(loc,"postal_code"),city).filter(String::isNotBlank).joinToString(", ").take(1000)
            val link=LocalDiscoveryIdentity.url(language(e["info_url"]),url) ?: LocalDiscoveryIdentity.url(s(e,"@id"),url) ?: url
            val venue=clean(language(loc["name"]),1000);val description=clean(language(e["description"]).ifBlank {language(e["short_description"])})
            val offer=rows(e,"offers").firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val price=if(yes(s(offer,"is_free")))"Free"else clean(language(offer?.get("price")),1000)
            val status=when(s(e,"event_status")){"EventCancelled"->"cancelled";"EventPostponed"->"postponed";"EventRescheduled"->"rescheduled";else->"scheduled"}
            val minAge=s(e,"audience_min_age").toIntOrNull()?.takeIf {it in 0..125};val maxAge=s(e,"audience_max_age").toIntOrNull()?.takeIf {it in 0..125}
            val age=when {minAge!=null&&maxAge!=null&&maxAge>=minAge->"$minAge–$maxAge";minAge!=null&&maxAge==null->"$minAge+";maxAge!=null&&minAge==null->"Up to $maxAge";else->""}
            val identity="linkedevents:${url.toHttpUrlOrNull()?.host}:$id"
            val fields=fields(mapOf("title" to title,"start" to start.value,"venue" to venue,"address" to address,"country" to s(loc,"address_country"),"description" to description,"age" to age,"status" to status),url,at)
            val event=LocalEvent("event:"+LocalDiscoveryIdentity.hash(identity),title,start,time(s(e,"end_time"),null),description,venue,address,city,geo,urls=listOf(link),price=price,age=age,status=status,sourceIds=listOf(identity),checkedAt=at,
                modifiedAt=runCatching {Instant.parse(s(e,"last_modified_time")).toEpochMilli()}.getOrNull(),
                evidence=fields+listOfNotNull(geo?.let {LocalDiscoveryIdentity.evidence("location","${it.latitude},${it.longitude}",url,at,LocalEvidenceKind.CALENDAR)}))
            event.takeIf {LocalDiscoveryFilter.geographic(area,it)}
        }
        return LocalExtraction(events=events,validEmpty=true,notice=if(obj(root,"meta")?.get("next")?.isJsonPrimitive==true)"More events are available from this regional source; this check reads one bounded page."else null)
    }
}
