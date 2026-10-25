/* anti social, GPL-3.0-or-later. Short factual event pages are not empty articles. */
package app.antisocial.core

import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalAnnouncementReading {
    fun key(event:LocalEvent)=LocalDiscoveryIdentity.hash(listOf(event.id,LocalDiscoveryIdentity.name(event.title),event.start.identity,event.modifiedAt).joinToString("|"))
    private val missing=Regex("(?i)^(?:(?:the )?(?:page|event|article) (?:was )?not found\\b|(?:404|410)(?:$|\\s*[-:|–—]|\\s+(?:not found|gone|page not found)\\b)|this (?:page|event) (?:is unavailable|no longer exists)\\b)")
    fun acceptable(document:ContextArticleEvidence,event:LocalEvent):Boolean {
        if(missing.containsMatchIn(document.title.trim())||document.blocks.take(3).any {it.kind==ContextArticleBlockKind.HEADING&&missing.containsMatchIn(it.text.trim())})return false
        val expected=LocalDiscoveryIdentity.name(event.title)
        val destination=document.finalUrl.toHttpUrlOrNull() ?: return false;val requested=document.originalUrl.toHttpUrlOrNull() ?: return false
        return destination.encodedPath.trim('/') !in setOf("","events","calendar")||destination.encodedPath==requested.encodedPath||expected.length<12||LocalDiscoveryIdentity.name(document.text).contains(expected)
    }
    fun extract(payload:Payload,original:String,event:LocalEvent,at:Long=System.currentTimeMillis()):ContextArticleEvidence=extract(payload,original,event.title,at)
    fun extract(payload:Payload,original:String,title:String,at:Long=System.currentTimeMillis()):ContextArticleEvidence {
        if(payload.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"This announcement is unavailable",httpStatus=payload.status)
        require(payload.text.length<=LocalStructuredData.MAX_DOCUMENT)
        HtmlAdmission.check(payload.text)
        val page=Jsoup.parse(payload.text,payload.finalUrl)
        if((listOf(page.title())+page.select("h1").take(3).map {it.text()}).any {missing.containsMatchIn(it.trim())})
            throw SourceFailure(HealthStatus.EMPTY,"This announcement is no longer present")
        val expected=LocalDiscoveryIdentity.name(title)
        val destination=payload.finalUrl.toHttpUrlOrNull();val requested=original.toHttpUrlOrNull()
        if(destination?.encodedPath?.trim('/') in setOf("","events","calendar")&&destination?.encodedPath!=requested?.encodedPath&&
            expected.length>=12&&!LocalDiscoveryIdentity.name(page.body().text()).contains(expected))
            throw SourceFailure(HealthStatus.EMPTY,"This link returned a general page instead of its announcement")
        try {return ContextArticles.extract(payload,original,at,readerChecks=true)}
        catch(e:SourceFailure) {
            // Login/challenge/refusal pages never qualify through metadata.
            if(e.status!=HealthStatus.EMPTY)throw e
            val notice=LocalStructuredData.html(payload.text,payload.finalUrl,at).events.firstOrNull {
                expected.length>=6&&LocalDiscoveryIdentity.name(it.title)==expected&&it.start.valid()
            } ?: throw e
            val details=listOf(notice.start.value+(notice.start.zoneId?.let {" · $it"} ?: ""),notice.venue,notice.address,notice.price,notice.age.takeIf(String::isNotBlank)?.let {"Ages $it"}.orEmpty(),notice.status.takeUnless {it=="scheduled"}.orEmpty(),notice.description)
                .filter(String::isNotBlank).distinct()
            return ContextArticleEvidence(original,payload.finalUrl,notice.title,notice.organizer.takeIf(String::isNotBlank),null,null,
                listOf(ContextArticleBlock(notice.title,ContextArticleBlockKind.HEADING,1))+details.map {ContextArticleBlock(it)},at)
        }
    }
}
