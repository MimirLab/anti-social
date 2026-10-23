/* AntiSocial, GPL-3.0-or-later. Candidate keys are not proof of occurrence identity. */
package app.antisocial.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Duration
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
        val distance=if(a.point!=null&&b.point!=null)a.point.distanceKm(b.point)else null
        if(districtOccurrence(a,b,distance))return true
        if(!exact&&!calendarMismatch)return false
        if(distance!=null&&distance>.2)return false
        val av=LocalDiscoveryMerge.businessName(a.venue.substringBefore(','));val bv=LocalDiscoveryMerge.businessName(b.venue.substringBefore(','))
        val venue=av.isNotBlank()&&av==bv
        val address=LocalDiscoveryMerge.street(a.address)?.let {it==LocalDiscoveryMerge.street(b.address)}==true
        val locality=a.locality.isNotBlank()&&LocalDiscoveryIdentity.name(a.locality)==LocalDiscoveryIdentity.name(b.locality)
        if(shared&&(venue||address||a.venue.isBlank()||b.venue.isBlank()||distance!=null&&distance<.1))return true
        return exact&&(distance!=null&&distance<.1||locality&&(venue||address))
    }
    /** District-wide celebrations are published under both the district and a
     * participating venue. A common title alone cannot merge two performances. */
    private fun districtOccurrence(a:LocalEvent,b:LocalEvent,distance:Double?):Boolean {
        val name=title(a)
        if(a.online||a.start.allDay||b.start.allDay||name.length<18||name.split(' ').size<4)return false
        if(a.start.date()!=b.start.date()||a.locality.isBlank()||LocalDiscoveryIdentity.name(a.locality)!=LocalDiscoveryIdentity.name(b.locality))return false
        if(distance!=null&&distance>1)return false
        val corridorA=LocalDiscoveryMerge.street(a.address)?.substringAfter(' ')
        val corridorB=LocalDiscoveryMerge.street(b.address)?.substringAfter(' ')
        if(corridorA==null||corridorA!=corridorB)return false
        val regional=setOf("district","downtown","neighborhood","neighbourhood")
        if(listOf(a.venue,b.venue).none {LocalDiscoveryIdentity.words(it).any {w->w in regional}})return false
        val left=a.start.instant() ?: return false;val right=b.start.instant() ?: return false
        if(kotlin.math.abs(Duration.between(left,right).seconds)>1800)return false
        val end=a.end?.instant() ?: return false
        if(end!=b.end?.instant()||end<=maxOf(left,right))return false
        val ignored=setOf("the","and","for","with","from","this","that","some","include","includes","activities","event","halloween","district","trick","treating")
        fun detail(e:LocalEvent)=LocalDiscoveryIdentity.words(e.description.take(12_000)).filter {it.length>=4&&it !in ignored}.toSet()
        return detail(a).intersect(detail(b)).size>=4
    }
}
