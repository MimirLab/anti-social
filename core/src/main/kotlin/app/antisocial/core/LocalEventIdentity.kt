/* AntiSocial, GPL-3.0-or-later. Candidate keys are not proof of occurrence identity. */
package app.antisocial.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalEventIdentity {
    private fun title(e:LocalEvent)=LocalDiscoveryIdentity.words(e.title).filterNot {it==e.start.date()?.year.toString()}.joinToString(" ")
    private fun wall(time:LocalEventTime):LocalDateTime? = runCatching {OffsetDateTime.parse(time.value).toLocalDateTime()}.getOrNull()
        ?: runCatching {LocalDateTime.parse(time.value)}.getOrNull()
    private fun links(e:LocalEvent)=e.urls.mapNotNull {LocalDiscoveryIdentity.url(it)?.toHttpUrlOrNull()}.filter {u->
        val path=u.encodedPath.trimEnd('/').lowercase()
        path.isNotBlank()&&!path.endsWith(".ics")&&u.queryParameterNames.none {it in setOf("ical","icalendar")}&&
            path.substringAfterLast('/') !in setOf("events","events-calendar","calendar","feed","kids")
    }.map {u->u.newBuilder().host(u.host.removePrefix("www.")).encodedPath(u.encodedPath.trimEnd('/')).fragment(null).build().toString()}.toSet()
    fun keys(e:LocalEvent):Set<String> {
        val dates=listOfNotNull(e.start.date()?.toString(),e.start.instant()?.toString()?.take(10)).distinct()
        return dates.flatMap {date->listOf("title:${title(e)}|$date")+links(e).map {"url:$it|$date"}}.toSet()
    }
    fun same(a:LocalEvent,b:LocalEvent):Boolean {
        if(a.id==b.id||a.sourceIds.intersect(b.sourceIds.toSet()).isNotEmpty())return true
        if(title(a)!=title(b)||a.online!=b.online)return false
        val shared=links(a).intersect(links(b)).isNotEmpty()
        val exact=a.start.allDay==b.start.allDay&&(a.start.instant()?.let {left->b.start.instant()?.let {left==it}}
            ?: (a.start.value==b.start.value&&(a.start.zoneId==null||b.start.zoneId==null||a.start.zoneId==b.start.zoneId)))
        // A calendar can publish the correct wall clock with the wrong TZID.
        // Require the same specific event URL and wall clock, never a fuzzy
        // one-hour tolerance that would swallow a separate performance.
        val calendarMismatch=shared&&!a.start.allDay&&!b.start.allDay&&wall(a.start)!=null&&wall(a.start)==wall(b.start)&&
            (a.evidence.any {it.field=="start"&&it.kind==LocalEvidenceKind.CALENDAR}||b.evidence.any {it.field=="start"&&it.kind==LocalEvidenceKind.CALENDAR})
        if(!exact&&!calendarMismatch)return false
        val distance=if(a.point!=null&&b.point!=null)a.point.distanceKm(b.point)else null
        if(distance!=null&&distance>.2)return false
        val av=LocalDiscoveryMerge.businessName(a.venue.substringBefore(','));val bv=LocalDiscoveryMerge.businessName(b.venue.substringBefore(','))
        val venue=av.isNotBlank()&&av==bv
        val address=LocalDiscoveryMerge.street(a.address)?.let {it==LocalDiscoveryMerge.street(b.address)}==true
        val locality=a.locality.isNotBlank()&&LocalDiscoveryIdentity.name(a.locality)==LocalDiscoveryIdentity.name(b.locality)
        if(shared&&(venue||address||a.venue.isBlank()||b.venue.isBlank()||distance!=null&&distance<.1))return true
        return exact&&(distance!=null&&distance<.1||locality&&(venue||address))
    }
}
