/* AntiSocial, GPL-3.0-or-later. Local public places and event occurrences; Darwin routes social records. */
package app.antisocial.core

import java.security.MessageDigest
import java.text.Normalizer
import java.time.*
import java.util.Locale
import kotlin.math.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LocalPoint(val latitude:Double,val longitude:Double) {
    fun valid()=latitude.isFinite()&&longitude.isFinite()&&latitude in -90.0..90.0&&longitude in -180.0..180.0
    fun distanceKm(other:LocalPoint):Double {
        if(!valid()||!other.valid())return Double.POSITIVE_INFINITY
        val a=Math.toRadians(latitude);val b=Math.toRadians(other.latitude)
        val d=Math.toRadians(other.latitude-latitude);val l=Math.toRadians(other.longitude-longitude)
        return 6371.0088*2*asin(sqrt((sin(d/2).pow(2)+cos(a)*cos(b)*sin(l/2).pow(2)).coerceIn(0.0,1.0)))
    }
}
data class LocalArea(val id:String,val name:String,val label:String,val point:LocalPoint,val radiusKm:Int=10,
    val zoneId:String?=null,val aliases:List<String> = emptyList(),val countryCode:String?=null) {
    fun valid()=id.length in 1..256&&name.length in 1..200&&label.length in 1..500&&point.valid()&&radiusKm in 1..50&&
        aliases.size<=16&&aliases.all {it.length<=200}&&(zoneId==null||runCatching {ZoneId.of(zoneId)}.isSuccess)
    fun today(now:Long=System.currentTimeMillis())=Instant.ofEpochMilli(now).atZone(zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()).toLocalDate()
    val cacheKey:String get()=LocalDiscoveryIdentity.hash("${point.latitude},${point.longitude}|$radiusKm")
}
enum class LocalEvidenceKind { PUBLISHER, CALENDAR, DIRECTORY, PUBLIC_POST, POSTER }
data class LocalImageRegion(val imageUrl:String,val sha256:String,val left:Int,val top:Int,val right:Int,val bottom:Int,val width:Int,val height:Int)
data class LocalFieldEvidence(val field:String,val value:String,val sourceUrl:String,val checkedAt:Long,
    val kind:LocalEvidenceKind,val excerpt:String="",val license:String?=null,val region:LocalImageRegion?=null)
/** A source publisher is never silently promoted to a business owner. */
data class LocalRelatedEntity(val name:String,val role:String,val accounts:List<String>,val sourceUrl:String)
data class LocalBusiness(val id:String,val name:String,val category:String="",val description:String="",val address:String="",
    val locality:String="",val point:LocalPoint?=null,val websites:List<String> = emptyList(),val socials:List<String> = emptyList(),
    val phones:List<String> = emptyList(),val hours:String="",val status:String="",val sourceIds:List<String> = emptyList(),
    val evidence:List<LocalFieldEvidence> = emptyList(),val checkedAt:Long=0,val keywords:List<String> = emptyList(),val emails:List<String>?=null,val related:List<LocalRelatedEntity>?=null,val menus:List<LocalMenu>?=null)

/** Source-local date/time is retained even when an offset is unavailable. Publication time is never an event start. */
data class LocalEventTime(val value:String,val zoneId:String?=null,val allDay:Boolean=false) {
    fun date():LocalDate?=runCatching {LocalDate.parse(value.take(10))}.getOrNull()
    fun instant():Instant?=if(allDay)null else runCatching {Instant.parse(value)}.getOrNull()
        ?:runCatching {OffsetDateTime.parse(value).toInstant()}.getOrNull()
        ?:zoneId?.let {runCatching {LocalDateTime.parse(value).atZone(ZoneId.of(it)).toInstant()}.getOrNull()}
    fun valid()=value.length<=64&&date()!=null&&(allDay&&value.length==10||!allDay&&
        (instant()!=null||runCatching {LocalDateTime.parse(value)}.isSuccess))&&
        (zoneId==null||runCatching {ZoneId.of(zoneId)}.isSuccess)
    val identity:String get()=instant()?.toString() ?: value
}
data class LocalEvent(val id:String,val title:String,val start:LocalEventTime,val end:LocalEventTime?=null,val description:String="",
    val venue:String="",val address:String="",val locality:String="",val point:LocalPoint?=null,val organizer:String="",val organizerUrl:String?=null,
    val urls:List<String> = emptyList(),val registrationUrl:String?=null,val price:String="",val age:String="",val status:String="scheduled",
    val online:Boolean=false,val sourceIds:List<String> = emptyList(),val evidence:List<LocalFieldEvidence> = emptyList(),
    val checkedAt:Long=0,val modifiedAt:Long?=null,val sequence:Int=0,val occurrence:String?=null,val image:String?=null)
enum class LocalSourceKind { WEB, CALENDAR, TRIBE, SOCIAL, LOCALIST, MENU, LIVEWHALE, SPACEAPI, LINKED_EVENTS }
data class LocalPublisherSource(val url:String,val kind:LocalSourceKind=LocalSourceKind.WEB,val businessId:String?=null,val label:String="",
    val checkedAt:Long=0,val nextCheckAt:Long=0,val failures:Int=0,val etag:String?=null,val lastModified:String?=null,val window:String?=null,val discoveredFrom:String?=null,val menuBranch:Boolean?=null)
data class LocalExtraction(val businesses:List<LocalBusiness> = emptyList(),val events:List<LocalEvent> = emptyList(),
    val sources:List<LocalPublisherSource> = emptyList(),val notice:String?=null,val validEmpty:Boolean=false,
    val announcements:List<LocalAnnouncementCandidate> = emptyList(),val posters:List<LocalPosterSource> = emptyList())
data class LocalWebHit(val url:String,val title:String) // Discovery only. A snippet is never a business/event fact.
enum class LocalSearchMode { POSTS, BUSINESSES, EVENTS }
enum class LocalEventWindow(val label:String) { WEEK("Next 7 days"), WEEKEND("This weekend"), MONTH("Next 30 days"), TODAY("Today") }
data class LocalDiscoveryQuery(val area:LocalArea,val text:String="",val mode:LocalSearchMode=LocalSearchMode.BUSINESSES,
    val window:LocalEventWindow=LocalEventWindow.WEEK,val savedOnly:Boolean=false,val freeOnly:Boolean=false) {
    fun dates(now:Long=System.currentTimeMillis()):Pair<LocalDate,LocalDate> {
        val today=area.today(now)
        return when(window) {
            LocalEventWindow.TODAY->today to today
            LocalEventWindow.WEEK->today to today.plusDays(6)
            LocalEventWindow.MONTH->today to today.plusDays(29)
            LocalEventWindow.WEEKEND->{val first=when(today.dayOfWeek){DayOfWeek.SUNDAY->today;DayOfWeek.SATURDAY->today;else->today.plusDays((6-today.dayOfWeek.value).toLong())};first to first.plusDays(if(first.dayOfWeek==DayOfWeek.SUNDAY)0 else 1)}
        }
    }
}
object LocalDiscoveryIdentity {
    private val combiningMarks=Regex("\\p{M}+")
    private val lexicalWords=Regex("[\\p{L}\\p{N}]+")
    fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    fun words(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKD).replace(combiningMarks,"").lowercase(Locale.ROOT)
        .let {lexicalWords.findAll(it).take(256).map {m->m.value}.toList()}
    fun name(value:String)=words(value).joinToString(" ")
    fun url(value:String?,base:String?=null):String? {
        if(value.isNullOrBlank()||value.length>8192||value.any {it<' '||it=='\\'})return null
        val literal=when {value.startsWith("//")->"https:$value";value.startsWith("webcal://",true)->"https://"+value.substring(9);value.startsWith("http://",true)->"https://"+value.substring(7);else->value}
        val parsed=literal.toHttpUrlOrNull() ?: base?.toHttpUrlOrNull()?.resolve(literal) ?: return null
        if(!parsed.isHttps||parsed.username.isNotEmpty()||parsed.password.isNotEmpty())return null
        return (SocialLinkHosts.canonical(parsed.toString()) ?: parsed.toString()).toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()?.takeIf {it.length<=8192}
    }
    fun urls(value:String,base:String?=null):List<String> = value.take(16384).split(Regex("[,;](?:\\s|%20)*(?=https?://)",RegexOption.IGNORE_CASE)).take(16).mapNotNull {url(it.trim(),base)}.distinct()
    fun evidence(field:String,value:String,url:String,at:Long,kind:LocalEvidenceKind,license:String?=null)=LocalFieldEvidence(field,value.take(16000),url,at,kind,value.take(500),license)
}
object LocalDiscoveryFilter {
    private val stop=setOf("near","me","in","at","the","a","for","and","with","things","to","do")
    private val alternatives=mapOf("kids" to setOf("kids","children","child","storytime","puppet","family","families"),
        "children" to setOf("kids","children","child","storytime","puppet","family","families"),
        "food" to setOf("food","restaurant","restaurants","cafe","grocery","groceries","market","markets","dining"),
        "coffee" to setOf("coffee","cafe","cafes","café"),"music" to setOf("music","concert","concerts","gig","gigs"),
        "organic" to setOf("organic","organics"),"release" to setOf("release","releases"),
        "taco" to setOf("taco","tacos","taqueria","taquerias","mexican"),
        "burger" to setOf("burger","burgers","hamburger","hamburgers"),
        "trampoline" to setOf("trampoline","trampolines"),
        "workshop" to setOf("workshop","workshops","class","classes","training"),
        "class" to setOf("class","classes","workshop","workshops","lesson","lessons"),
        "makerspace" to setOf("makerspace","makerspaces","hackerspace","hackerspaces","fab","fabrication"),
        "astronomy" to setOf("astronomy","stargazing","telescope","observing"),
        "volunteer" to setOf("volunteer","volunteers","volunteering"))
    fun normalizedQuery(value:String):String=SearchText.words(value.take(256)).joinToString(" "){word->
        val canonical=SearchText.plural(word)
        if(canonical in alternatives||word.length<5)canonical else (alternatives.keys+alternatives.values.flatten()).asSequence().filter {SearchText.oneEdit(word,it)}.map(SearchText::plural).distinct().take(2).toList().singleOrNull() ?: canonical
    }
    fun matches(query:String,content:String):Boolean {
        val intent=SearchIntent.parse(query)
        val available=SearchText.words(content.take(32000)).map(SearchText::plural).toSet()
        fun has(clause:SearchClause):Boolean {
            if(clause.quoted||clause.protected||clause.words.size!=1)return SearchIntent("",listOf(clause.copy(excluded=false))).matches(content.take(32000))
            val word=clause.words.single();val canonical=SearchText.plural(word)
            val corrected=PlaceSearchVocabulary.correction(canonical)
            return (alternatives[word] ?: alternatives[canonical] ?: setOf(word,canonical)).any {SearchText.plural(it) in available}||corrected in available
        }
        return SearchMeaning.variants(intent).any {variant->variant.clauses.filterNot {!it.quoted&&it.words.singleOrNull() in stop}.all {if(it.excluded)!has(it)else has(it)}}
    }
    fun eventQuery(value:String)=LocalEventIntent.parse(value).discoveryText
    fun business(query:LocalDiscoveryQuery,business:LocalBusiness)=LocalGeography.inside(query.area,business.point,business.locality,business.address,business.evidence)
    fun geographic(area:LocalArea,event:LocalEvent)=!event.online&&LocalGeography.inside(area,event.point,event.locality,event.address,event.evidence)
    fun event(query:LocalDiscoveryQuery,event:LocalEvent,now:Long=System.currentTimeMillis()):Boolean {
        if(!geographic(query.area,event))return false
        fun date(time:LocalEventTime)=time.instant()?.let {instant->query.area.zoneId?.let {instant.atZone(ZoneId.of(it)).toLocalDate()}} ?: time.date()
        val (from,to)=query.dates(now);val start=date(event.start) ?: return false;val end=event.end?.let(::date) ?: start
        return start<=to&&end>=from&&(!query.freeOnly||event.price.equals("Free",true))
    }
    fun locality(area:LocalArea,value:String):Boolean {
        if(value.isBlank())return false
        val key=LocalDiscoveryIdentity.name(value)
        return (listOf(area.name)+area.aliases).any {LocalDiscoveryIdentity.name(it)==key}
    }
    /** Some publisher schemas swap city/state fields. Only an exact component
     * of the event's own venue address may supply the chosen city. */
    fun addressLocality(area:LocalArea?,given:String,address:String):String {
        if(area==null||locality(area,given))return given
        return if(address.split(Regex("[,;\\n]")).any {component->
            val words=LocalDiscoveryIdentity.words(component)
            (listOf(area.name)+area.aliases).any {name->val city=LocalDiscoveryIdentity.words(name);city.isNotEmpty()&&words.takeLast(city.size)==city}
        })area.name else given
    }
}
/** Explicit publisher corrections beat directory snapshots. A chain's branches and recurring sessions stay distinct. */
object LocalDiscoveryMerge {
    private val corporateWords=setOf("llc","inc","ltd","co","company","corp","corporation")
    fun businessName(value:String)=businessName(LocalBusinessOwnership.nameWords(value))
    internal fun businessName(words:List<String>)=words.dropWhile {it=="the"}.filterNot(corporateWords::contains).joinToString(" ")
    /** Geographic cells find candidates only. Distance, name, address and
     * branch/suite checks still decide whether two records are the same. */
    fun locationKeys(point:LocalPoint?):List<String> {
        if(point==null)return emptyList()
        val lat=floor(point.latitude*1000).toInt();val lon=floor(point.longitude*1000).toInt()
        return (-1..1).flatMap {a->(-1..1).map {b->"${lat+a}:${((lon+b+180000)%360000+360000)%360000-180000}"}}
    }
    private val streetWords=mapOf("north" to "n","south" to "s","east" to "e","west" to "w","northeast" to "ne","northwest" to "nw","southeast" to "se","southwest" to "sw","street" to "st","avenue" to "ave","boulevard" to "blvd","road" to "rd","drive" to "dr","lane" to "ln","court" to "ct","highway" to "hwy","parkway" to "pkwy")
    private val endings=setOf("st","ave","blvd","rd","dr","ln","ct","hwy","pkwy","way","terrace","trail","place","pl","circle","cir")
    fun street(value:String):String? {
        // A published address can begin with the shopping centre or building
        // name. Compare its numbered street component, not that premise label.
        for(component in value.split(Regex("[,;\\n]")).take(12)) {
            val words=LocalDiscoveryIdentity.words(component).map {streetWords[it] ?: it}
            if(words.firstOrNull()?.matches(Regex("[0-9]+[a-z]?"))!=true)continue
            val end=words.indexOfFirst {it in endings}
            if(end>=2)return words.take(end+1+if(words.getOrNull(end+1) in setOf("n","s","e","w","ne","nw","se","sw"))1 else 0).joinToString(" ")
        }
        return null
    }
    fun sameBusiness(a:LocalBusiness,b:LocalBusiness)=LocalBusinessIdentity.same(a,b)
    private fun rank(evidence:List<LocalFieldEvidence>,field:String,value:String?=null)=evidence.filter {it.field==field&&(value==null||it.value==value)}.minOfOrNull {it.kind.ordinal} ?: 99
    private fun pointRank(business:LocalBusiness):Int {
        val p=business.point ?: return Int.MAX_VALUE
        return business.evidence.filter {it.field=="location"&&it.value=="${p.latitude},${p.longitude}"}.minOfOrNull {field->
            field.kind.ordinal*100+if(field.kind!=LocalEvidenceKind.DIRECTORY)0 else when {
                field.sourceUrl.toHttpUrlOrNull()?.host?.let {it=="openstreetmap.org"||it.endsWith(".openstreetmap.org")}==true->0
                field.license?.contains("Overture Maps")==true->10
                else->40
            }
        } ?: 9900
    }
    fun business(a:LocalBusiness,b:LocalBusiness):LocalBusiness {
        fun value(field:String,x:String,y:String)=if(x.isBlank()||y.isNotBlank()&&(rank(b.evidence,field,y)<rank(a.evidence,field,x)||rank(b.evidence,field,y)==rank(a.evidence,field,x)&&b.checkedAt>a.checkedAt))y else x
        // A directory's SEO headline is not a business rename. Between
        // equally authoritative observations, prefer the undecorated name;
        // keep both observations and their source text in the evidence.
        fun headline(value:String)=Regex("^(?:highly|top|best) rated\\b|\\s[–—|]\\s.*\\b(?:in|near)\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)
        val name=when {
            rank(a.evidence,"name",a.name)!=rank(b.evidence,"name",b.name)->value("name",a.name,b.name)
            headline(a.name)&&!headline(b.name)&&b.name.isNotBlank()->b.name
            headline(b.name)&&!headline(a.name)&&a.name.isNotBlank()->a.name
            else->value("name",a.name,b.name)
        }
        val point=if(a.point==null||b.point!=null&&(pointRank(b)<pointRank(a)||pointRank(b)==pointRank(a)&&b.checkedAt>a.checkedAt))b.point else a.point
        val generic=setOf("","LocalBusiness","Organization","Place")
        val category=when {a.category !in generic&&b.category in generic->a.category;b.category !in generic&&a.category in generic->b.category;else->value("category",a.category,b.category)}
        return a.copy(name=name,category=category,description=value("description",a.description,b.description),
            address=value("address",a.address,b.address),locality=value("locality",a.locality,b.locality),point=point,
            websites=(a.websites+b.websites).distinct().take(12),socials=(a.socials+b.socials).distinct().take(24),phones=(a.phones+b.phones).distinct().take(8),
            hours=value("hours",a.hours,b.hours),status=value("status",a.status,b.status),sourceIds=(a.sourceIds+b.sourceIds).distinct().take(32),
            evidence=evidence(a.evidence+b.evidence),checkedAt=maxOf(a.checkedAt,b.checkedAt),keywords=(a.keywords+b.keywords).distinct().take(64),emails=(a.emails.orEmpty()+b.emails.orEmpty()).distinct().take(8).takeIf {it.isNotEmpty()},related=(a.related.orEmpty()+b.related.orEmpty()).distinct().take(16).takeIf {it.isNotEmpty()},menus=LocalMenus.merge(a.menus.orEmpty()+b.menus.orEmpty()).takeIf {it.isNotEmpty()})
    }
    fun businesses(values:List<LocalBusiness>):List<LocalBusiness> {
        val records=linkedMapOf<String,LocalBusiness>()
        val prepared=mutableMapOf<String,LocalBusinessIdentity.Prepared>()
        val names=mutableMapOf<String,MutableSet<String>>();val streets=mutableMapOf<String,MutableSet<String>>();val cells=mutableMapOf<String,MutableSet<String>>()
        fun index(place:LocalBusiness,identity:LocalBusinessIdentity.Prepared){prepared[place.id]=identity;names.getOrPut(identity.name){mutableSetOf()}.add(place.id);identity.street?.let {streets.getOrPut(it){mutableSetOf()}.add(place.id)};identity.cells.forEach {cells.getOrPut(it){mutableSetOf()}.add(place.id)}}
        values.forEach {incoming->
            val identity=LocalBusinessIdentity.prepare(incoming)
            val matches=(names[identity.name].orEmpty()+streets[identity.street].orEmpty()+identity.cells.flatMap {cells[it].orEmpty()}).distinct().mapNotNull(records::get).filter {LocalBusinessIdentity.same(prepared.getValue(it.id),identity)}
            val result=if(matches.isEmpty())incoming else matches.drop(1).fold(business(matches.first(),incoming)){left,right->business(left,right)}
            matches.forEach {records.remove(it.id)};records[result.id]=result;index(result,if(matches.isEmpty())identity else LocalBusinessIdentity.prepare(result))
        }
        return records.values.toList()
    }
    fun sameEvent(a:LocalEvent,b:LocalEvent)=LocalEventIdentity.same(a,b)
    fun events(values:List<LocalEvent>):List<LocalEvent> {
        val records=linkedMapOf<String,LocalEvent>();val keys=mutableMapOf<String,MutableSet<String>>();val aliases=mutableMapOf<String,String>()
        fun index(e:LocalEvent){LocalEventIdentity.keys(e).forEach {keys.getOrPut(it){mutableSetOf()}.add(e.id)};e.sourceIds.forEach {aliases[it]=e.id}}
        values.forEach {incoming->
            val candidates=(listOf(incoming.id)+incoming.sourceIds.mapNotNull {aliases[it]}+LocalEventIdentity.keys(incoming).flatMap {keys[it].orEmpty()}).distinct()
            val matches=candidates.mapNotNull(records::get).filter {sameEvent(it,incoming)}
            val result=matches.fold(incoming){acc,old->event(old,acc)}
            matches.forEach {records.remove(it.id)};records[result.id]=result;index(result)
        }
        return records.values.toList()
    }
    fun event(a:LocalEvent,b:LocalEvent):LocalEvent {
        val shared=a.sourceIds.firstOrNull()!=null&&a.sourceIds.firstOrNull()==b.sourceIds.firstOrNull()
        val better=when {
            shared&&b.sequence!=a.sequence->b.sequence>a.sequence
            (b.modifiedAt ?: b.checkedAt)!=(a.modifiedAt ?: a.checkedAt)->(b.modifiedAt ?: b.checkedAt)>(a.modifiedAt ?: a.checkedAt)
            else->rank(b.evidence,"start")<rank(a.evidence,"start")
        }
        val first=if(better)b else a;val other=if(better)a else b
        val current=LocalDocumentWashing.text(first.description);val previous=LocalDocumentWashing.text(other.description)
        val prefix=current.replace(Regex("(?:\\.{3}|…)\\s*$"),"").trim()
        val description=if(current.isBlank()||prefix.length>=60&&previous.startsWith(prefix)&&previous.length>current.length)previous else current
        return first.copy(id=a.id,description=description,venue=first.venue.ifBlank {other.venue},address=first.address.ifBlank {other.address},
            locality=first.locality.ifBlank {other.locality},point=first.point ?: other.point,organizer=first.organizer.ifBlank {other.organizer},organizerUrl=first.organizerUrl ?: other.organizerUrl,
            urls=(first.urls+other.urls).distinct().take(24),registrationUrl=first.registrationUrl ?: other.registrationUrl,price=first.price.ifBlank {other.price},age=first.age.ifBlank {other.age},
            sourceIds=(first.sourceIds+other.sourceIds).distinct().take(32),evidence=evidence(first.evidence+other.evidence),checkedAt=first.checkedAt,image=first.image ?: other.image)
    }
    private fun evidence(values:List<LocalFieldEvidence>):List<LocalFieldEvidence> {
        var size=0
        val recent=values.sortedByDescending {it.checkedAt}.distinctBy {listOf(it.field,it.value,it.sourceUrl)}
        val representatives=recent.sortedWith(compareBy<LocalFieldEvidence>{it.kind.ordinal}.thenByDescending {it.checkedAt}).distinctBy {it.field}
        return (representatives+recent).distinctBy {listOf(it.field,it.value,it.sourceUrl)}.take(96).filter {field->
            val cost=field.field.length+field.value.length+field.sourceUrl.length+field.excerpt.length+(field.license?.length ?: 0)
            if(size+cost<=32*1024){size+=cost;true}else false
        }
    }
}

/** Documented, local-only area preferences. GPS permission is not needed. */
object LocalAreaCodec {
    private val gson=com.google.gson.Gson()
    fun encode(areas:List<LocalArea>):String {require(areas.size<=12&&areas.all(LocalArea::valid));return gson.toJson(areas)}
    fun decode(raw:String):List<LocalArea> {
        require(raw.length<=32000)
        val json=com.google.gson.JsonParser.parseString(raw);require(json.isJsonArray&&json.asJsonArray.size()<=12)
        return json.asJsonArray.map {value->require(value.isJsonObject)
            val o=value.asJsonObject
            require(setOf("id","name","label","point").all {o.has(it)&&!o[it].isJsonNull})
            fun string(key:String,max:Int,optional:Boolean=false):String? {val v=o[key];if(v==null||v.isJsonNull){require(optional);return null};require(v.isJsonPrimitive&&v.asJsonPrimitive.isString&&v.asString.length<=max&&!v.asString.any(Char::isISOControl));return v.asString}
            require(o.keySet().all {it in setOf("id","name","label","point","radiusKm","zoneId","aliases","countryCode")})
            val p=o["point"].asJsonObject;require(p.keySet()==setOf("latitude","longitude"));require(p.entrySet().all {it.value.isJsonPrimitive&&it.value.asJsonPrimitive.isNumber})
            val radius=o["radiusKm"];require(radius==null||radius.isJsonPrimitive&&radius.asJsonPrimitive.isNumber&&radius.asString.toIntOrNull() in 1..50)
            val aliases=o["aliases"]?.let {a->require(a.isJsonArray&&a.asJsonArray.size()<=16);a.asJsonArray.map {v->require(v.isJsonPrimitive&&v.asJsonPrimitive.isString);v.asString}}.orEmpty()
            LocalArea(string("id",256)!!,string("name",200)!!,string("label",500)!!,LocalPoint(p["latitude"].asDouble,p["longitude"].asDouble),radius?.asInt ?: 10,string("zoneId",100,true),aliases,string("countryCode",2,true)).also {require(it.valid())}
        }.also {require(it.map {a->a.id}.distinct().size==it.size)}
    }
}
