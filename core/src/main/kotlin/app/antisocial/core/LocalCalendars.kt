/* AntiSocial, GPL-3.0-or-later. Bounded inert iCalendar ingestion; biweekly is BSD-licensed. */
package app.antisocial.core

import biweekly.io.text.ICalReader
import biweekly.component.VEvent
import biweekly.property.DateOrDateTimeProperty
import biweekly.util.Frequency
import java.time.*
import java.util.Date
import java.util.TimeZone
import org.jsoup.Jsoup

object LocalCalendars {
    fun parse(body:String,url:String,at:Long,area:LocalArea,business:LocalBusiness?=null):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        require(Regex("(?im)^BEGIN:VCALENDAR\\s*$").containsMatchIn(body)){"This source did not return a calendar"}
        val reader=ICalReader(body)
        val fallback=TimeZone.getTimeZone(area.zoneId ?: "UTC")
        reader.defaultTimezone=fallback
        // Only the operating system's timezone database and embedded VTIMEZONE
        // definitions may resolve dates. Never download TZURLs or Olson files.
        reader.setGlobalTimezoneIdResolver {id->runCatching {TimeZone.getTimeZone(ZoneId.of(id.removePrefix("/")))}.getOrNull()}
        val output=mutableListOf<LocalEvent>();var limited=false
        reader.use {
            repeat(4) {
                val calendar=reader.readNext() ?: return@repeat
                val rows=calendar.events.take(512);if(calendar.events.size>512)limited=true
                val grouped=rows.groupBy {it.uid?.value ?: "missing:"+LocalDiscoveryIdentity.hash(it.summary?.value.orEmpty()+it.dateStart?.value.toString())}
                val from=area.today(at).minusDays(7).atStartOfDay(ZoneId.of(area.zoneId ?: "UTC")).toInstant()
                val until=area.today(at).plusDays(61).atStartOfDay(ZoneId.of(area.zoneId ?: "UTC")).toInstant()
                for((uid,entries) in grouped) {
                    if(output.size>=512){limited=true;break}
                    fun newest(values:List<VEvent>)=values.maxWithOrNull(compareBy<VEvent> {it.sequence?.value ?: 0}.thenBy {it.lastModified?.value?.time ?: it.dateTimeStamp?.value?.time ?: 0})
                    val base=newest(entries.filter {it.recurrenceId==null})
                    val overrides=entries.filter {it.recurrenceId!=null}.groupBy {it.recurrenceId.value.time}.mapValues {newest(it.value)!!}
                    val seeds=mutableListOf<Pair<VEvent,Date>>()
                    if(base?.dateStart!=null) {
                        val rule=base.recurrenceRule?.value
                        val unsafe=rule!=null&&(rule.frequency in setOf(Frequency.SECONDLY,Frequency.MINUTELY,Frequency.HOURLY)||rule.bySecond.size>1||rule.byMinute.size>1||rule.byHour.size>4)
                        if(unsafe){limited=true;seeds+=base to base.dateStart.value}
                        else if(rule!=null||base.recurrenceDates.isNotEmpty()) {
                            val prop=base.dateStart;val zone=calendar.timezoneInfo.getTimezone(prop)?.timeZone ?: fallback
                            val iterator=base.getDateIterator(zone)
                            iterator.advanceTo(Date.from(from))
                            var count=0
                            while(iterator.hasNext()&&count++<90&&output.size+seeds.size<512) {
                                val date=iterator.next();if(date.toInstant()>=until)break
                                seeds+=base to date
                            }
                            if(count>=90)limited=true
                        }else seeds+=base to base.dateStart.value
                    }
                    // Explicit moved/cancelled occurrences can survive EXDATE or
                    // lie outside the original session's window.
                    overrides.values.forEach {override->if(seeds.none {it.second.time==override.recurrenceId.value.time})
                        seeds+=override to override.recurrenceId.value}
                    for((seed,occurrence) in seeds) {
                        val e=overrides[occurrence.time] ?: seed
                        if(e.recurrenceId?.range?.toString()?.contains("FUTURE")==true){limited=true;continue}
                        val startProp=e.dateStart ?: base?.dateStart ?: continue
                        val assignment=calendar.timezoneInfo.getTimezone(startProp)
                        val tz=assignment?.timeZone ?: if(calendar.timezoneInfo.isFloating(startProp))fallback else TimeZone.getTimeZone("UTC")
                        val zone=runCatching {ZoneId.of(startProp.parameters.timezoneId)}.getOrNull()?.id ?: if(calendar.timezoneInfo.isFloating(startProp))area.zoneId else null
                        val actual=if(e.recurrenceId!=null)e.dateStart?.value ?: occurrence else occurrence
                        val allDay=!startProp.value.hasTime()
                        fun time(date:Date,exclusiveEnd:Boolean=false):LocalEventTime {
                            // Embedded VTIMEZONE identifiers need not be IANA
                            // names. Use their calculated offset, not toZoneId.
                            val z=date.toInstant().atOffset(ZoneOffset.ofTotalSeconds(tz.getOffset(date.time)/1000))
                            return if(allDay)LocalEventTime(z.toLocalDate().minusDays(if(exclusiveEnd)1 else 0).toString(),zone,true)
                            else LocalEventTime(z.toString(),zone)
                        }
                        val start=time(actual)
                        val duration=e.dateEnd?.value?.let {it.time-startProp.value.time} ?: e.duration?.value?.toMillis() ?: base?.let {b->b.dateEnd?.value?.let {it.time-b.dateStart.value.time} ?: b.duration?.value?.toMillis()}
                        val end=duration?.takeIf {it>0&&it<366L*86400000}?.let {time(Date(actual.time+it),allDay)}
                        val title=(e.summary?.value ?: base?.summary?.value).orEmpty().take(400);if(title.isBlank())continue
                        val location=(e.location?.value ?: base?.location?.value).orEmpty().take(1000)
                        val known=business?.takeIf {p->LocalDiscoveryIdentity.name(location)==LocalDiscoveryIdentity.name(p.name)||LocalDiscoveryIdentity.name(location)==LocalDiscoveryIdentity.name(p.address)}
                        val rawGeo=e.geo ?: base?.geo
                        val point=rawGeo?.let {LocalPoint(it.latitude.toDouble(),it.longitude.toDouble()).takeIf(LocalPoint::valid)} ?: known?.point
                        val address=known?.address ?: location
                        val locality=known?.locality.orEmpty().ifBlank {if(LocalDiscoveryIdentity.words(location).windowed(LocalDiscoveryIdentity.words(area.name).size.coerceAtLeast(1)).any {it==LocalDiscoveryIdentity.words(area.name)})area.name else ""}
                        val description=Jsoup.parseBodyFragment((e.description?.value ?: base?.description?.value).orEmpty().take(16000)).text().take(8000)
                        val links=listOfNotNull(LocalDiscoveryIdentity.url(e.url?.value ?: base?.url?.value,url),url).distinct()
                        val status=if(e.status?.isCancelled==true||base?.status?.isCancelled==true)"cancelled"else if(e.status?.isTentative==true)"tentative"else "scheduled"
                        val identity="ical:$url|$uid|${occurrence.time}"
                        val fields=mapOf("title" to title,"start" to start.value,"venue" to location,"address" to address,"locality" to locality,"description" to description,"status" to status)
                        output+=LocalEvent("event:"+LocalDiscoveryIdentity.hash(identity),title,start,end,description,known?.name ?: location,address,locality,point,
                            (e.organizer?.commonName ?: base?.organizer?.commonName ?: business?.name.orEmpty()).take(300),urls=links,status=status,sourceIds=listOf(identity),
                            evidence=fields.filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.CALENDAR)},checkedAt=at,
                            modifiedAt=e.lastModified?.value?.time,sequence=(e.sequence?.value ?: 0).coerceAtLeast(0),occurrence=occurrence.time.toString())
                    }
                }
            }
        }
        return LocalExtraction(events=output.distinctBy {it.id}.take(512),notice=if(limited)"This calendar contains sessions beyond the supported reading allowance."else null)
    }
}
