/* AntiSocial, GPL-3.0-or-later. Inert structured extraction; no scripts, remote contexts or subresources. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalStructuredData {
    const val MAX_DOCUMENT=2*1024*1024
    private val brandWikidata=Regex("Q[1-9][0-9]{0,18}")
    private val businesses=setOf("LocalBusiness","Organization","Store","Restaurant","CafeOrCoffeeShop","FoodEstablishment","GroceryStore","Library","PerformingArtsTheater","CivicStructure","TouristAttraction","SportsActivityLocation","CommunityCenter","Place","HealthAndBeautyBusiness","ProfessionalService","MedicalBusiness","LodgingBusiness","ShoppingCenter","Church","School","ChildCare","FoodEvent",
        "AmusementPark","EntertainmentBusiness","BowlingAlley","MovieTheater","NightClub","Casino","ComedyClub","ArtGallery","Museum","Zoo","Aquarium","Park","SportsClub","SportsComplex","ExerciseGym","HealthClub","GolfCourse","TennisComplex","StadiumOrArena","SwimmingPool","SkiResort","TouristInformationCenter","TravelAgency","AutomotiveBusiness","AutoRepair","AutoDealer","AutoPartsStore","GasStation","CarWash","BikeStore","BookStore","ClothingStore","ComputerStore","ConvenienceStore","DepartmentStore","ElectronicsStore","Florist","FurnitureStore","GardenStore","HardwareStore","HomeGoodsStore","JewelryStore","LiquorStore","PetStore","Pharmacy","ShoeStore","SportingGoodsStore","ToyStore","WholesaleStore","Bakery","BarOrPub","Brewery","FastFoodRestaurant","IceCreamShop","Winery","FoodTruck","Deli","Dentist","Physician","Hospital","VeterinaryCare","Optician","BeautySalon","HairSalon","NailSalon","DaySpa","TattooParlor","RealEstateAgent","FinancialService","BankOrCreditUnion","InsuranceAgency","LegalService","Attorney","AccountingService","EmploymentAgency","HomeAndConstructionBusiness","Electrician","GeneralContractor","Plumber","RoofingContractor","HVACBusiness","Locksmith","MovingCompany","DryCleaningOrLaundry","Hotel","Motel","BedAndBreakfast","Resort","Campground")
    private val eventTypes=setOf("Event","BusinessEvent","ChildrensEvent","ComedyEvent","DanceEvent","EducationEvent","Festival","FoodEvent","LiteraryEvent","MusicEvent","SaleEvent","ScreeningEvent","SocialEvent","SportsEvent","TheaterEvent","VisualArtsEvent","ExhibitionEvent","CourseInstance")
    internal fun accountLink(url:String):Boolean {
        if(SocialLinkHosts.platform(url)==null)return false
        val parts=url.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        if(parts.isEmpty())return false
        return parts.none {it.lowercase() in setOf("sharer.php","share.php","share","intent","login","dialog","status","statuses","posts","reel","reels","p","watch","embed","shorts","video","videos","events","search","explore","hashtag","sharer")}
    }
    private fun str(value:JsonElement?):String=when {value==null||value.isJsonNull->"";value.isJsonPrimitive->value.asString;else->""}
    private fun arr(value:JsonElement?):List<JsonElement> = when {value==null||value.isJsonNull->emptyList();value.isJsonArray->value.asJsonArray.take(128);else->listOf(value)}
    private fun text(value:JsonElement?,limit:Int=8000)=LocalDocumentWashing.text(str(value),limit)
    private fun types(value:JsonObject)=arr(value["@type"]).map {str(it).substringAfterLast('/').substringAfterLast('#')}.toSet()
    private fun address(value:JsonElement?):Pair<String,String> {
        if(value==null)return "" to ""
        if(value.isJsonArray) {
            val locations=value.asJsonArray.take(16).map(::address).filter {it.first.isNotBlank()}.distinct()
            return locations.singleOrNull() ?: ("" to "")
        }
        if(!value.isJsonObject)return text(value,1000) to ""
        val a=value.asJsonObject
        return listOf("streetAddress","addressLocality","addressRegion","postalCode","addressCountry").mapNotNull {key->
            a[key]?.let {v->if(v.isJsonObject)text(v.asJsonObject["name"],200)else text(v,300)}?.takeIf(String::isNotBlank)
        }.joinToString(", ") to text(a["addressLocality"],200)
    }
    private fun point(value:JsonElement?):LocalPoint? {
        if(value?.isJsonObject!=true)return null
        val o=value.asJsonObject
        return LocalPoint(str(o["latitude"]).toDoubleOrNull() ?: return null,str(o["longitude"]).toDoubleOrNull() ?: return null).takeIf(LocalPoint::valid)
    }
    private fun moment(value:JsonElement?,zone:String?):LocalEventTime? {
        val literal=str(value).trim().replace(Regex("^(\\d{4}-\\d{2}-\\d{2}) "),"$1T")
        if(literal.isEmpty())return null
        val time=LocalEventTime(literal,zone?.takeIf {runCatching {ZoneId.of(it)}.isSuccess},literal.length==10)
        return time.takeIf(LocalEventTime::valid)
    }
    fun jsonld(body:String,url:String,at:Long,area:LocalArea?,business:LocalBusiness?=null):LocalExtraction =
        html("<script type=\"application/ld+json\">"+body.replace("</","<\\/")+"</script>",url,at,area,business)
    fun html(body:String,url:String,at:Long,area:LocalArea?=null,business:LocalBusiness?=null):LocalExtraction {
        require(body.length<=MAX_DOCUMENT){"This page exceeds the local discovery allowance"}
        HtmlAdmission.check(body)
        val xml=Regex("(?is)^\\s*(?:<\\?xml[^>]*>\\s*)?<(?:rss|feed)(?:\\s|>)").containsMatchIn(body)
        val page=Jsoup.parse(body,url,if(xml)Parser.xmlParser()else Parser.htmlParser())
        val records=mutableListOf<JsonObject>();var nodes=0
        fun visit(value:JsonElement,depth:Int=0) {
            if(depth>12||nodes++>=1024)return
            if(value.isJsonArray)value.asJsonArray.take(128).forEach {visit(it,depth+1)}
            if(value.isJsonObject) {
                val o=value.asJsonObject
                if(o.has("@type")||o.has("@id"))records+=o
                o.entrySet().filter {it.key!="@context"}.take(64).forEach {(_,v)->if(v.isJsonArray||v.isJsonObject)visit(v,depth+1)}
            }
        }
        page.select("script[type=application/ld+json]").take(24).forEach {script->
            if(script.data().length<=256*1024)runCatching {JsonParser.parseString(script.data())}.getOrNull()?.let {visit(it)}
        }
        // Microdata uses the same typed projection. Nested itemscopes belong to
        // their parent property rather than becoming flattened unrelated fields.
        fun micro(node:Element,depth:Int=0):JsonObject {
            val o=JsonObject();o.addProperty("@type",node.attr("itemtype").substringAfterLast('/'));node.attr("itemid").takeIf(String::isNotBlank)?.let {o.addProperty("@id",it)}
            if(depth>5)return o
            node.select("[itemprop]").take(128).filter {child->child.parents().firstOrNull {it.hasAttr("itemscope")}==node}.forEach {child->
                val value:JsonElement=if(child.hasAttr("itemscope"))micro(child,depth+1)else JsonPrimitive(when {
                    child.hasAttr("content")->child.attr("content");child.hasAttr("datetime")->child.attr("datetime");child.hasAttr("href")->child.absUrl("href");child.hasAttr("src")->child.absUrl("src");else->child.text()
                })
                child.attr("itemprop").split(' ').filter(String::isNotBlank).take(4).forEach {key->
                    val previous=o[key];if(previous==null)o.add(key,value)else {val values=if(previous.isJsonArray)previous.asJsonArray else JsonArray().apply {add(previous)};values.add(value);o.add(key,values)}
                }
            }
            return o
        }
        page.select("[itemscope][itemtype]").take(96).forEach {records+=micro(it)}
        page.select(".h-event").take(64).forEach {event->
            val o=JsonObject();o.addProperty("@type","Event");o.addProperty("name",event.selectFirst(".p-name")?.text())
            event.selectFirst(".dt-start")?.let {o.addProperty("startDate",it.attr("datetime").ifBlank {it.text()})}
            event.selectFirst(".dt-end")?.let {o.addProperty("endDate",it.attr("datetime").ifBlank {it.text()})}
            event.selectFirst(".p-location")?.let {o.addProperty("location",it.text())}
            event.selectFirst(".u-url")?.let {o.addProperty("url",it.absUrl("href"))};records+=o
        }
        val ids=records.filter {str(it["@id"]).isNotBlank()}.groupBy {str(it["@id"])}.mapValues {(_,rows)->rows.maxBy {it.size()}}
        fun recordId(o:JsonObject):String?=str(o["@id"]).takeIf {it.isNotBlank()&&it.length<=8192}?.let {url.toHttpUrlOrNull()?.resolve(it)?.toString()}
        fun resolve(value:JsonElement?):JsonObject? {
            if(value?.isJsonObject==true) {val obj=value.asJsonObject;return if(obj.size()==1&&obj.has("@id"))ids[str(obj["@id"])] ?: obj else obj}
            return ids[str(value)]
        }
        val places=mutableListOf<LocalBusiness>();val events=mutableListOf<LocalEvent>()
        fun place(o:JsonObject):LocalBusiness? {
            val originalName=text(o["name"],300);if(originalName.isBlank())return null
            val (address,locality)=address(o["address"]);val geo=point(resolve(o["geo"]) ?: o["geo"])
            val declaredCountry=o["address"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("addressCountry")?.let {if(it.isJsonObject)str(it.asJsonObject["name"])else str(it)}.orEmpty()
            if(geo==null&&area?.countryCode=="US"&&declaredCountry.isNotBlank()&&declaredCountry.uppercase() !in setOf("US","USA","UNITED STATES","UNITED STATES OF AMERICA"))return null
            val siteName=records.firstOrNull {"WebSite" in types(it)}?.let {text(it["name"],200)}.orEmpty().ifBlank {page.selectFirst("meta[property=og:site_name]")?.attr("content").orEmpty()}.substringBefore('|').trim()
            val regionOnly=geo!=null&&originalName.split(Regex("\\s*[-–—,]\\s*")).all {part->part.isNotBlank()&&(part.equals(locality,true)||area?.let {a->LocalDiscoveryIdentity.words(part).all {it in LocalDiscoveryIdentity.words(a.label)}}==true)}
            val heading=page.selectFirst("h1")?.text().orEmpty().trim().take(200)
            val name=when {
                regionOnly&&siteName.isNotBlank()&&!originalName.contains(siteName,true)->"$siteName · $originalName".take(300)
                originalName==page.title()&&heading.isNotBlank()&&heading.length<originalName.length&&siteName.isNotBlank()&&LocalDiscoveryIdentity.words(siteName).any {it.length>2&&heading.contains(it,true)}->heading
                else->originalName
            }
            val firstParty=LocalBusinessOwnership.firstParty(name,url,listOf(siteName).filter(String::isNotBlank))
            val evidenceKind=if(firstParty)LocalEvidenceKind.PUBLISHER else LocalEvidenceKind.DIRECTORY
            val urls=(arr(o["url"])+arr(o["sameAs"])).flatMap {LocalDiscoveryIdentity.urls(str(it),url)}.distinct().ifEmpty {if(address.isNotBlank()||geo!=null)listOf(url)else emptyList()}
            val hasSite=urls.isNotEmpty()
            if(address.isBlank()&&geo==null&&!hasSite)return null
            val sourceId=recordId(o) ?: url+"#"+LocalDiscoveryIdentity.hash(name+address)
            val hours=arr(o["openingHours"]).map {text(it,500)}.filter(String::isNotBlank).joinToString(" · ").ifBlank {
                arr(o["openingHoursSpecification"]).filter(JsonElement::isJsonObject).take(14).joinToString(" · ") {item->val h=item.asJsonObject
                    arr(h["dayOfWeek"]).joinToString(", "){str(it).substringAfterLast('/')}+" "+str(h["opens"])+"–"+str(h["closes"])
                }
            }
            val phones=arr(o["telephone"]).map {text(it,100)}.filter(String::isNotBlank).take(8)
            val emails=arr(o["email"]).mapNotNull {LocalBusinessContact.email(str(it))}.distinct().take(8)
            val related=listOf("owner","operator","founder","brand").flatMap {role->arr(o[role]).mapNotNull {value->
                val entity=resolve(value);val entityName=if(entity==null)text(value,300)else text(entity["name"],300)
                if(entityName.isBlank())null else LocalRelatedEntity(entityName,role,if(entity==null)emptyList()else (arr(entity["url"])+arr(entity["sameAs"])).mapNotNull {LocalDiscoveryIdentity.url(str(it),url)}.filter(::accountLink).distinct().take(8),url)
            }}.take(16)
            val fields=mapOf("name" to name,"category" to types(o).firstOrNull().orEmpty(),"address" to address,"locality" to locality,"country" to declaredCountry,"region" to text(o["address"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("addressRegion"),200),"hours" to hours,"description" to text(o["description"]),"phones" to phones.joinToString(" · "),"emails" to emails.joinToString(" · "))
            return LocalBusiness("place:"+LocalDiscoveryIdentity.hash(sourceId),name,types(o).firstOrNull {it!="Organization"&&it!="Place"}.orEmpty().take(400),text(o["description"]),address,locality,geo,
                urls.filter {SocialLinkHosts.platform(it)==null}.take(12),urls.filter(::accountLink).take(24),phones,hours.take(8000),
                sourceIds=listOf("web:$sourceId"),evidence=fields.filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,evidenceKind)}+
                    listOfNotNull(geo?.let {LocalDiscoveryIdentity.evidence("location","${it.latitude},${it.longitude}",url,at,evidenceKind)})+LocalBusinessFacts.schema(o,url,at,evidenceKind),checkedAt=at,
                keywords=(listOf(if(firstParty)siteName else "",originalName)+arr(o["alternateName"]).map {text(it,200)}+arr(o["servesCuisine"]).map {text(it,200)}+arr(o["keywords"]).map {text(it,400)}).filter(String::isNotBlank).take(32),emails=emails.takeIf {it.isNotEmpty()},related=related.takeIf {it.isNotEmpty()})
        }
        records.filter {types(it).any(businesses::contains)&&types(it).none(eventTypes::contains)}.take(128).mapNotNullTo(places,::place)
        for(o in records.filter {types(it).any(eventTypes::contains)}.take(256)) {
            val title=text(o["name"],400);val start=moment(o["startDate"],area?.zoneId) ?: continue;if(title.isBlank())continue
            val location=arr(o["location"]).firstOrNull();val venue=resolve(location)
            val venueName=if(venue==null)text(location,300)else text(venue["name"],300)
            val (givenAddress,givenLocality)=address(venue?.get("address") ?: location?.takeUnless {it.isJsonObject});val geo=point(venue?.get("geo")?.let {resolve(it) ?: it})
            val matchedVenue=(places+listOfNotNull(business)).firstOrNull {p->venueName.isNotBlank()&&LocalDiscoveryIdentity.name(p.name)==LocalDiscoveryIdentity.name(venueName)}
            val address=givenAddress.ifBlank {matchedVenue?.address.orEmpty()};val locality=LocalDiscoveryFilter.addressLocality(area,givenLocality.ifBlank {matchedVenue?.locality.orEmpty()},address)
            val organizer=arr(o["organizer"]).firstOrNull();val organizerObj=resolve(organizer)
            val organizerName=organizerObj?.let {text(it["name"],300)} ?: text(organizer,300)
            val links=arr(o["url"]).mapNotNull {LocalDiscoveryIdentity.url(str(it),url)}.take(24).ifEmpty {listOf(url)}
            val offers=arr(o["offers"]).firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val rawPrice=str(offers?.get("price")).take(100);val price=when {o["isAccessibleForFree"]?.let {str(it).equals("true",true)}==true||rawPrice.toDoubleOrNull()==0.0->"Free"
                rawPrice.isNotBlank()->(str(offers?.get("priceCurrency")).take(12)+" "+rawPrice).trim();else->""}
            val previous=moment(o["previousStartDate"],area?.zoneId)
            val identity=recordId(o) ?: links.first()+"#"+LocalDiscoveryIdentity.hash(title+venueName)
            val sourceId="event:$identity|${(previous ?: start).identity}"
            val status=str(o["eventStatus"]).substringAfterLast('/').let {when(it){"EventCancelled"->"cancelled";"EventPostponed"->"postponed";"EventRescheduled"->"rescheduled";else->"scheduled"}}
            val venueAddress=venue?.get("address")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val venueCountry=venueAddress?.get("addressCountry")?.let {if(it.isJsonObject)text(it.asJsonObject["name"],200)else text(it,200)}.orEmpty()
            val fields=mapOf("title" to title,"start" to start.value,"venue" to venueName,"address" to address,"locality" to locality,"country" to venueCountry,"region" to text(venueAddress?.get("addressRegion"),200),"price" to price,"status" to status,
                "organizer" to organizerName,"description" to text(o["description"]),"age" to text(o["typicalAgeRange"],100))
            val actualPoint=geo ?: matchedVenue?.point
            events+=LocalEvent("event:"+LocalDiscoveryIdentity.hash(sourceId),title,start,moment(o["endDate"],area?.zoneId),text(o["description"]),venueName,address,locality,actualPoint,
                organizerName,LocalDiscoveryIdentity.url(str(organizerObj?.get("url")),url),links,LocalDiscoveryIdentity.url(str(offers?.get("url")),url),price,text(o["typicalAgeRange"],100),status,
                str(o["eventAttendanceMode"]).endsWith("OnlineEventAttendanceMode")||venue?.let {"VirtualLocation" in types(it)}==true,
                listOf(sourceId),fields.filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.PUBLISHER)}+
                    listOfNotNull(actualPoint?.let {LocalDiscoveryIdentity.evidence("location","${it.latitude},${it.longitude}",url,at,LocalEvidenceKind.PUBLISHER)}),at,
                moment(o["dateModified"],area?.zoneId)?.instant()?.toEpochMilli(),occurrence=(previous ?: start).identity,
                image=arr(o["image"]).firstOrNull()?.let {v->LocalDiscoveryIdentity.url(if(v.isJsonObject)str(v.asJsonObject["url"])else str(v),url)})
        }
        val hints=mutableListOf<LocalPublisherSource>()
        val pageOwner=business ?: places.firstOrNull()
        val declaredSiteNames=records.filter {"WebSite" in types(it)}.map {text(it["name"],300)}.filter(String::isNotBlank)+listOfNotNull(page.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf(String::isNotBlank))
        val owned=pageOwner?.let {LocalBusinessOwnership.firstParty(it.name,url,declaredSiteNames)}==true
        val owner=pageOwner?.id?.takeIf {owned}
        hints+=LocalCalendarPlatforms.sources(body,url,owner)
        page.select("a[href],link[href]").take(1500).forEach {element->
            val link=LocalDiscoveryIdentity.url(element.absUrl("href"),url) ?: return@forEach
            val type=element.attr("type").lowercase();val label=element.text().take(120)
            val calendar=type=="text/calendar"||Regex("(?:\\.ics(?:[?#]|$)|[?&](?:ical|icalendar|format=ical))",RegexOption.IGNORE_CASE).containsMatchIn(link)
            val dataFeed=type in setOf("application/rss+xml","application/atom+xml","application/ld+json")
            val social=accountLink(link)
            val sameOrigin=link.toHttpUrlOrNull()?.host==url.toHttpUrlOrNull()?.host
            val linkedBusinessAccount=social&&(link in pageOwner?.socials.orEmpty()||owned&&element.parents().any {it.tagName() in setOf("header","footer")||it.id()=="footer"||it.className().contains("footer")})
            if(calendar||dataFeed||linkedBusinessAccount||sameOrigin&&Regex("calendar|events|what.?s.on|contact",RegexOption.IGNORE_CASE).containsMatchIn(element.attr("href")+" "+label))
                hints+=LocalPublisherSource(link,if(calendar)LocalSourceKind.CALENDAR else if(social)LocalSourceKind.SOCIAL else LocalSourceKind.WEB,owner,label)
        }
        if(page.select(".tribe-events,[class*=tribe-events],link[href*='/tribe/events/']").isNotEmpty()||page.select("script[src]").any {it.attr("src").contains("the-events-calendar")}) {
            url.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/wp-json/tribe/events/v1/events")?.query(null)?.fragment(null)?.build()?.toString()?.let {hints+=LocalPublisherSource(it,LocalSourceKind.TRIBE,owner,"Events calendar")}
        }
        // RSS publication dates are not occurrence dates. Its entries become
        // bounded candidates for actual announcement extraction instead.
        if(page.select("rss,feed").isNotEmpty())page.select("item,entry").take(30).forEach {item->
            val link=item.selectFirst("link")?.let {it.attr("href").ifBlank {it.text()}}
            LocalDiscoveryIdentity.url(link,url)?.let {hints+=LocalPublisherSource(it,LocalSourceKind.WEB,owner,item.selectFirst("title")?.text().orEmpty().take(120))}
        }
        // An owned site's header/footer and rel=me declarations connect its
        // identity to accounts even when a CMS omitted schema.org sameAs.
        // Article-body mentions and share-intent links cannot claim ownership.
        val ownerBusiness=business ?: places.firstOrNull()
        if(ownerBusiness!=null) {
            val site=records.firstOrNull {"WebSite" in types(it)}
            val siteNames=listOfNotNull(site?.let {text(it["name"],300)}?.takeIf(String::isNotBlank),page.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf(String::isNotBlank))
            val firstParty=LocalBusinessOwnership.firstParty(ownerBusiness.name,url,siteNames)
            val emails=page.select("a[href^=mailto:]").take(16).mapNotNull {LocalBusinessContact.email(it.attr("href"))}.distinct().take(8)
            val declared=page.select("footer a[href],header a[href],[id=footer] a[href],[class*=footer] a[href],a[rel~=me],[itemprop=sameAs][href],a[class*=social-icon][href]").take(128)
                .filterNot {node->node.parents().any {Regex("(?:^|[-_ ])(?:share|sharing|social-share)(?:$|[-_ ])",RegexOption.IGNORE_CASE).containsMatchIn(it.className())}}
                .mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("href"),url)}.filter(::accountLink).distinct().take(24)
            if(declared.isNotEmpty()||emails.isNotEmpty()) {
                places.removeAll {it.id==ownerBusiness.id}
                val publisher=site?.get("publisher")?.let(::resolve)?.let {text(it["name"],300)}.orEmpty().ifBlank {siteNames.firstOrNull().orEmpty()}.ifBlank {url.toHttpUrlOrNull()?.host.orEmpty()}
                val related=if(firstParty||declared.isEmpty())emptyList()else listOf(LocalRelatedEntity(publisher,"source publisher",declared,url))
                places+=ownerBusiness.copy(socials=(ownerBusiness.socials+if(firstParty)declared else emptyList()).distinct().take(24),emails=(ownerBusiness.emails.orEmpty()+if(firstParty)emails else emptyList()).distinct().take(8).takeIf {it.isNotEmpty()},checkedAt=at,
                    related=(ownerBusiness.related.orEmpty()+related).distinct().take(16).takeIf {it.isNotEmpty()},
                    evidence=LocalDiscoveryMerge.business(ownerBusiness,ownerBusiness.copy(evidence=listOfNotNull(declared.takeIf {it.isNotEmpty()}?.let {LocalDiscoveryIdentity.evidence(if(firstParty)"business accounts"else "source publisher accounts",it.joinToString(" · "),url,at,if(firstParty)LocalEvidenceKind.PUBLISHER else LocalEvidenceKind.DIRECTORY)},emails.takeIf {firstParty&&it.isNotEmpty()}?.let {LocalDiscoveryIdentity.evidence("emails",it.joinToString(" · "),url,at,LocalEvidenceKind.PUBLISHER)}))).evidence)
            }
        }
        return LocalExtraction(places.distinctBy {it.id}.take(128),events.distinctBy {it.id}.take(256),hints.distinctBy {it.url}.take(48))
    }
    fun tribe(body:String,url:String,at:Long,area:LocalArea?,business:LocalBusiness?=null):LocalExtraction {
        require(body.length<=MAX_DOCUMENT)
        val json=JsonParser.parseString(body).asJsonObject;val events=JsonArray()
        arr(json["events"]).filter(JsonElement::isJsonObject).take(100).forEach {value->
            val e=value.asJsonObject;val out=JsonObject();out.addProperty("@type","Event");out.add("name",e["title"]);out.add("description",e["description"])
            out.add("url",e["url"]);out.add("@id",e["url"])
            val allDay=str(e["all_day"]).equals("true",true)
            val zone=str(e["timezone"]).let {runCatching {ZoneId.of(it)}.getOrNull()}
            (moment(e["modified_utc"],"UTC")?.instant() ?: moment(e["modified"],zone?.id ?: area?.zoneId)?.instant())?.let {out.addProperty("dateModified",it.toString())}
            fun date(key:String):String {val raw=str(e[key]).replace(' ','T');return if(allDay)raw.take(10)else if(zone!=null)runCatching {LocalDateTime.parse(raw).atZone(zone).toOffsetDateTime().toString()}.getOrDefault(raw)else raw}
            out.addProperty("startDate",date("start_date"));out.addProperty("endDate",date("end_date"))
            e["venue"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {venue->val place=JsonObject();place.add("name",venue["venue"])
                val addr=JsonObject();addr.add("streetAddress",venue["address"]);addr.add("addressLocality",venue["city"]);addr.add("addressRegion",venue["state"] ?: venue["province"]);place.add("address",addr)
                val geo=JsonObject();geo.add("latitude",venue["geo_lat"]);geo.add("longitude",venue["geo_lng"]);place.add("geo",geo);out.add("location",place)
            }
            val offer=JsonObject();val cost=text(e["cost"],100);if(cost.equals("Free",true)||cost=="0")offer.addProperty("price",0)else if(cost.isNotBlank())offer.addProperty("price",cost);out.add("offers",offer)
            e["image"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("url")?.let {out.add("image",it)}
            if(str(e["status"]).contains("cancel",true))out.addProperty("eventStatus","EventCancelled")
            events.add(out)
        }
        return html("<script type=\"application/ld+json\">${events.toString().replace("</","<\\/")}</script>",url,at,area,business)
    }
    fun overture(body:String,url:String,at:Long):LocalExtraction {
        require(body.length<=8*1024*1024){"Choose a regional place pack smaller than 8 MB"}
        val root=JsonParser.parseString(body);val raw=if(root.isJsonObject)root.asJsonObject["features"]else root
        val features=raw?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.take(2000).orEmpty()
        // An imported regional pack is data only. URLs become candidates and
        // must still pass NetworkGateway when the reader chooses to retrieve.
        val places=features.take(2000).mapNotNull {value->
            val feature=value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val p=feature["properties"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id=str(p["id"]).ifBlank {str(feature["id"])};if(id.isBlank()||id.length>128)return@mapNotNull null
            val name=if(p["names"]?.isJsonObject==true)str(p["names"].asJsonObject["primary"])else str(p["name"]);if(name.isBlank())return@mapNotNull null
            if(p["confidence"]?.let {str(it).toDoubleOrNull()}?.let {it<.7}==true)return@mapNotNull null
            val coords=feature["geometry"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("coordinates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
            val point=if(coords.size()==2)LocalPoint(str(coords[1]).toDoubleOrNull() ?: return@mapNotNull null,str(coords[0]).toDoubleOrNull() ?: return@mapNotNull null).takeIf(LocalPoint::valid)else null
            if(point==null)return@mapNotNull null
            val address=arr(p["addresses"]).firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val street=listOf("freeform","locality","region","postcode","country").map {str(address?.get(it)).take(300)}.filter(String::isNotBlank).joinToString(", ")
            val category=str(p["basic_category"]).ifBlank {str(p["taxonomy"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("primary"))}.ifBlank {str(p["categories"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("primary"))}
            val brand=p["brand"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val brandName=str(brand?.get("names")?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("primary")).take(300)
            val brandId=str(brand?.get("wikidata")).takeIf(brandWikidata::matches).orEmpty()
            val licenses=arr(p["sources"]).filter(JsonElement::isJsonObject).map {it.asJsonObject}.map {str(it["dataset"])+" · "+str(it["license"])}.distinct().joinToString("; ").take(1000)
            val fields=mapOf("name" to name,"category" to category,"address" to street,"locality" to str(address?.get("locality")),"location" to "${point.latitude},${point.longitude}",
                "socials" to arr(p["socials"]).joinToString(" · "){str(it)},"websites" to arr(p["websites"]).joinToString(" · "){str(it)},"phones" to arr(p["phones"]).joinToString(" · "){str(it)},"brand" to brandName,"brand id" to brandId)
            LocalBusinessSocial.normalize(LocalBusiness("overture:$id",name.take(300),category.replace('_',' ').take(400),address=street,locality=str(address?.get("locality")).take(200),point=point,
                websites=arr(p["websites"]).flatMap {LocalDiscoveryIdentity.urls(str(it))}.distinct().take(12),socials=arr(p["socials"]).mapNotNull {LocalDiscoveryIdentity.url(str(it))}.take(24),
                phones=arr(p["phones"]).map {str(it).take(100)}.take(8),sourceIds=listOf("overture:$id"),checkedAt=at,keywords=listOf(brandName,brandId).filter(String::isNotBlank),
                evidence=fields.filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.DIRECTORY,"Overture Maps · $licenses")} ))
        }
        return LocalExtraction(businesses=places)
    }
}
