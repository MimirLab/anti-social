/* anti social, GPL-3.0-or-later. Bounded public announcement extraction with inspectable evidence. */
package app.antisocial.core

import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class LocalAnnouncementCandidate(val id:String,val title:String,val text:String,val sourceUrl:String,
    val publisherId:String,val missing:List<String>,val evidence:List<LocalFieldEvidence>,val checkedAt:Long,
    val date:String?=null,val image:String?=null)
/** Transient work inventory, never persisted as a second copy of a social feed. */
data class LocalPosterSource(val post:Post,val business:LocalBusiness,val publisherLocationVerified:Boolean=false,val refresh:Boolean=false)

object LocalAnnouncements {
    private val months=(1..12).associateBy {Month.of(it).name.lowercase()}
    private val monthPattern="(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val named=Regex("\\b($monthPattern)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(20\\d{2}))?\\b",RegexOption.IGNORE_CASE)
    private val dayFirst=Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($monthPattern)\\.?(?:,?\\s+(20\\d{2}))?\\b",RegexOption.IGNORE_CASE)
    private val numeric=Regex("\\b(\\d{1,2})/(\\d{1,2})/(20\\d{2})\\b")
    private val iso=Regex("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b")
    private val clock=Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(?:[-–]\\s*\\d{1,2}(?::\\d{2})?\\s*)?([ap])\\.?m\\.?\\b",RegexOption.IGNORE_CASE)
    private val eventWords=Regex("\\b(?:join us|event|workshop|storytime|festival|concert|market|class|trivia|party|celebration|show|meetup|meet us|open house|register|tickets|admission)\\b",RegexOption.IGNORE_CASE)
    fun likely(post:Post)=eventWords.containsMatchIn(post.body+" "+post.title)||named.containsMatchIn(post.body)||iso.containsMatchIn(post.body)||post.body.isBlank()&&post.media.any {it.type=="image"}
    fun posterPriority(task:LocalPosterSource)=if(eventWords.containsMatchIn(task.post.body))0 else if(named.containsMatchIn(task.post.body)||iso.containsMatchIn(task.post.body))1 else 2
    private data class DateHit(val date:LocalDate,val start:Int,val end:Int,val evidence:String)
    private fun dates(text:String,publishedAt:Long?,zone:ZoneId,country:String?):List<DateHit> {
        val publication=publishedAt?.let {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
        val found=mutableListOf<DateHit>()
        iso.findAll(text).take(12).forEach {m->runCatching {LocalDate.of(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt())}.getOrNull()?.let {found+=DateHit(it,m.range.first,m.range.last+1,m.value)}}
        named.findAll(text).take(12).forEach {m->
            val month=months.entries.firstOrNull {it.key.startsWith(m.groupValues[1].lowercase().take(3))}?.value ?: return@forEach
            val year=m.groupValues[3].toIntOrNull() ?: publication?.year ?: return@forEach
            var date=runCatching {LocalDate.of(year,month,m.groupValues[2].toInt())}.getOrNull() ?: return@forEach
            if(m.groupValues[3].isBlank()&&publication!=null&&publication.monthValue==12&&month==1)date=date.plusYears(1)
            found+=DateHit(date,m.range.first,m.range.last+1,m.value)
        }
        dayFirst.findAll(text).take(12).forEach {m->
            val month=months.entries.firstOrNull {it.key.startsWith(m.groupValues[2].lowercase().take(3))}?.value ?: return@forEach
            val year=m.groupValues[3].toIntOrNull() ?: publication?.year ?: return@forEach
            runCatching {LocalDate.of(year,month,m.groupValues[1].toInt())}.getOrNull()?.let {found+=DateHit(it,m.range.first,m.range.last+1,m.value)}
        }
        numeric.findAll(text).take(12).forEach {m->
            val a=m.groupValues[1].toInt();val b=m.groupValues[2].toInt()
            val monthFirst=when {a>12->false;b>12->true;country=="US"->true;country in setOf("GB","AU","NZ","IE")->false;else->return@forEach}
            runCatching {LocalDate.of(m.groupValues[3].toInt(),if(monthFirst)a else b,if(monthFirst)b else a)}.getOrNull()?.let {found+=DateHit(it,m.range.first,m.range.last+1,m.value)}
        }
        if(found.isEmpty()&&publication!=null) {
            Regex("\\b(today|tomorrow|this (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\b",RegexOption.IGNORE_CASE).findAll(text).take(8).forEach {m->
                val key=m.value.lowercase();val date=when(key){"today"->publication;"tomorrow"->publication.plusDays(1);else->publication.with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(key.substringAfter(' ').uppercase())))}
                found+=DateHit(date,m.range.first,m.range.last+1,m.value)
            }
        }
        return found.filter {hit->
            val prefix=text.substring(maxOf(0,hit.start-48),hit.start)
            val deadline=Regex("(?:deadline|register by|registration closes|book by|apply by|tickets? until)\\s*:?\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(prefix)
            val weekday=Regex("(monday|tuesday|wednesday|thursday|friday|saturday|sunday)[,\\s]*$",RegexOption.IGNORE_CASE).find(prefix)?.groupValues?.get(1)
            !deadline&&(weekday==null||DayOfWeek.valueOf(weekday.uppercase())==hit.date.dayOfWeek)
        }.sortedBy {it.start}.distinctBy {it.date}.take(8)
    }
    fun extract(post:Post,business:LocalBusiness,area:LocalArea,at:Long,ocr:OcrResult?=null,imageUrl:String?=null,publisherLocationVerified:Boolean=false):LocalExtraction {
        val source=LocalDiscoveryIdentity.url(post.canonicalUrl) ?: return LocalExtraction()
        val body=LocalDocumentWashing.lines(post.title+"\n"+post.body)
        val text=(body+if(ocr!=null)"\n"+ocr.text.take(24000)else "").trim()
        if(text.isBlank()||!likely(post)&&ocr==null)return LocalExtraction()
        val zone=runCatching {ZoneId.of(area.zoneId ?: "UTC")}.getOrDefault(ZoneOffset.UTC)
        val scan=Regex("https?://\\S+",RegexOption.IGNORE_CASE).replace(text){" ".repeat(it.value.length)}
        val hits=dates(scan,post.publishedAt,zone,LocalGeography.country(area))
        val kind=if(ocr==null)LocalEvidenceKind.PUBLIC_POST else LocalEvidenceKind.POSTER
        fun evidence(field:String,value:String,excerpt:String):LocalFieldEvidence {
            val line=ocr?.lines?.firstOrNull {value.isNotBlank()&&it.text.contains(value,true)||excerpt.isNotBlank()&&it.text.contains(excerpt,true)}
            val region=if(line!=null&&imageUrl!=null)LocalImageRegion(imageUrl,ocr.sourceSha256,line.left,line.top,line.right,line.bottom,ocr.width,ocr.height)else null
            return LocalFieldEvidence(field,value.take(4000),source,at,kind,excerpt.take(500),region=region)
        }
        val name=LocalDiscoveryIdentity.name(business.name)
        val normalized=LocalDiscoveryIdentity.name(text)
        val explicitAddress=business.address.isNotBlank()&&business.locality.isNotBlank()&&normalized.contains(LocalDiscoveryIdentity.name(business.locality))&&LocalDiscoveryMerge.street(business.address)?.let {normalized.contains(it)}==true
        val atBusiness=name.isNotBlank()&&normalized.contains("at $name")||Regex("\\b(?:at our (?:shop|store|venue|brewery|library|location)|here at|join us at our)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)
        val venueKnown=(explicitAddress||atBusiness&&publisherLocationVerified)&&LocalGeography.inside(area,business.point,business.locality,business.address,business.evidence)
        val title=text.lineSequence().map(String::trim).firstOrNull {it.length in 4..180&& !it.startsWith("http")}?.take(160) ?: "${business.name} announcement"
        val price=when {Regex("\\b(?:free admission|free entry|admission is free|no admission charge|free event)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)->"Free";else->Regex("(?:admission|tickets?|entry)\\s*:?\\s*\\$\\d+(?:\\.\\d{2})?",RegexOption.IGNORE_CASE).find(text)?.value.orEmpty()}
        val age=Regex("\\b(?:ages?\\s*:?\\s*\\d{1,2}\\s*(?:[-–]\\s*\\d{1,2}|and up|\\+)|all ages|adults only)\\b",RegexOption.IGNORE_CASE).find(text)?.value.orEmpty()
        val registration=post.links.orEmpty().mapNotNull {LocalDiscoveryIdentity.url(it)}.firstOrNull {Regex("register|ticket|signup|eventbrite|booking",RegexOption.IGNORE_CASE).containsMatchIn(it)}
        val status=when {Regex("\\b(?:cancelled|canceled)\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)->"cancelled";Regex("\\bpostponed\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)->"postponed";else->"scheduled"}
        val events=mutableListOf<LocalEvent>();val candidates=mutableListOf<LocalAnnouncementCandidate>()
        val dateHits=hits.ifEmpty {listOf<DateHit?>(null)}
        dateHits.forEachIndexed {index,hit->
            val end=hits.getOrNull(index+1)?.start ?: text.length
            val passage=if(hit==null)scan else scan.substring(hit.start,end)
            val timeMatch=clock.find(passage)
            val time=timeMatch?.let {m->val hour=m.groupValues[1].toInt();val minute=m.groupValues[2].toIntOrNull() ?: 0
                if(hour !in 1..12||minute !in 0..59)null else LocalTime.of(hour%12+if(m.groupValues[3].equals("p",true))12 else 0,minute)}
            val allDay=Regex("\\ball day\\b",RegexOption.IGNORE_CASE).containsMatchIn(passage)
            val missing=buildList {if(hit==null)add("Date needs confirmation");if(time==null&&!allDay)add("Time not stated");if(!venueKnown)add("Venue needs confirmation");if(ocr?.uncertain==true||ocr?.truncated==true)add("Image text needs confirmation")
                if(!allDay&&area.zoneId==null)add("Timezone needs confirmation")
                if(hit!=null&&time!=null&&zone.rules.getValidOffsets(LocalDateTime.of(hit.date,time)).size!=1)add("Clock change needs confirmation")}
            val fields=listOf(evidence("announcement",text,text),evidence("title",title,title))+listOfNotNull(hit?.let {evidence("start",it.date.toString(),it.evidence)},timeMatch?.let {evidence("time",it.value,it.value)})+
                if(venueKnown)listOf(evidence("venue",business.name,if(explicitAddress)business.address else "at ${business.name}"))+business.evidence.filter {it.field in setOf("location","address","country","region")}.take(4)else emptyList()
            val id="announcement:"+LocalDiscoveryIdentity.hash(source+"|"+(hit?.date?.toString() ?: "unknown"))
            if(missing.isNotEmpty())candidates+=LocalAnnouncementCandidate(id,title,text.take(16000),source,business.id,missing,fields,at,hit?.date?.toString(),imageUrl)
            else if(hit!=null) {
                val start=if(allDay)LocalEventTime(hit.date.toString(),area.zoneId,true)else LocalEventTime(LocalDateTime.of(hit.date,time!!).toString(),area.zoneId)
                events+=LocalEvent(id,title,start,description=text.take(8000),venue=business.name,address=business.address,locality=business.locality,point=business.point,
                    organizer=business.name,organizerUrl=business.websites.firstOrNull(),urls=listOf(source),registrationUrl=registration,price=price,age=age,status=status,
                    sourceIds=listOf("social-event:$source:${hit.date}"),evidence=fields,checkedAt=at,modifiedAt=post.publishedAt,image=imageUrl)
            }
        }
        return LocalExtraction(events=events,announcements=candidates)
    }
}
