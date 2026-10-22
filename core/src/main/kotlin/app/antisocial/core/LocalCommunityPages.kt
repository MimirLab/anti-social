/* anti social, GPL-3.0-or-later. Explicit public calendar fields; prose is not a date source. */
package app.antisocial.core

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

object LocalCommunityPages {
    fun events(body:String,url:String,at:Long,area:LocalArea):List<LocalEvent> {
        val page=Jsoup.parse(body,url)
        val section=page.selectFirst(".f4us_descr:has(.f4us_time)") ?: return emptyList()
        val title=section.selectFirst("h2")?.text()?.take(400).orEmpty();if(title.isBlank())return emptyList()
        val literal=section.selectFirst(".f4us_time")?.text().orEmpty().replace('\u00a0',' ').replace(Regex("\\s+")," ")
        val date=Regex("\\b(January|February|March|April|May|June|July|August|September|October|November|December) (\\d{1,2}), (\\d{4})\\b").find(literal) ?: return emptyList()
        val day=runCatching {LocalDate.parse(date.value,DateTimeFormatter.ofPattern("MMMM d, uuuu",Locale.US))}.getOrNull() ?: return emptyList()
        val formatter=DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.US)
        val clocks=Regex("\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)\\b",RegexOption.IGNORE_CASE).findAll(literal.substring(date.range.last+1)).take(2).mapNotNull {m->runCatching {LocalTime.parse(m.value.replace(Regex("(?i)(\\d)(am|pm)"),"$1 $2"),formatter)}.getOrNull()}.toList()
        if(clocks.isEmpty()&&!literal.contains("all day",true))return emptyList()
        val start=LocalEventTime(if(clocks.isEmpty())day.toString()else day.atTime(clocks[0]).toString(),area.zoneId,clocks.isEmpty())
        val end=clocks.getOrNull(1)?.takeIf {it>=clocks[0]}?.let {LocalEventTime(day.atTime(it).toString(),area.zoneId)}
        val address=section.selectFirst(".f4usk-ev-location")?.ownText()?.trim().orEmpty().take(1000)
        val price=section.selectFirst(".f4us_cost")?.text()?.removePrefix("Cost:")?.trim().orEmpty().take(1000)
        val description=section.select("p").take(12).joinToString("\n"){it.text()}.take(8000)
        val venue=address.substringBefore(',').take(1000)
        val age=Regex("(?i)\\bAges?:\\s*([0-9]+(?:\\s*[-–]\\s*[0-9]+)?)").find(description)?.groupValues?.get(1).orEmpty()
        val identity="community:$url"
        return listOf(LocalEvent("event:"+LocalDiscoveryIdentity.hash(identity),title,start,end,description,venue,address,
            LocalDiscoveryFilter.addressLocality(area,"",address),urls=listOf(url),price=price,age=age,sourceIds=listOf(identity),checkedAt=at,
            evidence=mapOf("title" to title,"start" to start.value,"venue" to venue,"address" to address,"description" to description,"price" to price,"age" to age).filterValues(String::isNotBlank).map {(key,value)->LocalDiscoveryIdentity.evidence(key,value,url,at,LocalEvidenceKind.PUBLISHER)}))
    }
}
