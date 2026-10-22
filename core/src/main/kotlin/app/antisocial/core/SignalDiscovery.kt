/* AntiSocial, GPL-3.0-or-later. Bounded deterministic paths to substantive sources. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

data class SignalReference(val url:String,val label:String,val reason:String,val passage:String,val priority:Int)

/** Candidate selection only. A citation is a relationship, not verification of
 * its contents. Nothing here asks a model to invent a URL or choose a tool. */
object SignalDiscovery {
    const val POLICY="signal-trails-v1"
    const val MAX_DEPTH=2
    const val MAX_FOLLOW_UPS=6
    const val PER_ARTICLE=2
    private val evidence=Regex("\\b(?:study|paper|research|report|dataset|data set|source code|manual|documentation|specification|release notes|changelog|security advisory|court (?:order|filing|ruling)|filing|judgment|judgement|document|findings|full (?:text|report|recording|video|interview|speech|transcript)|original (?:source|research|study|report))\\b",RegexOption.IGNORE_CASE)
    private val noise=Regex("\\b(?:subscribe|sign in|log in|privacy|terms|advertis(?:e|ing|ement)|sponsored|related (?:article|post)|you may (?:also|like)|share (?:on|this))\\b",RegexOption.IGNORE_CASE)
    private val badPath=Regex("/(?:search|login|signin|signup|share|intent|privacy|terms|subscribe)(?:/|$)",RegexOption.IGNORE_CASE)
    fun identity(raw:String):String? {
        val original=ArchiveUrls.original(raw) ?: raw
        val u=original.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.port==443} ?: return null
        if(u.encodedPath=="/"||badPath.containsMatchIn(u.encodedPath))return null
        PublicSourceRecords.linkedDoi(u.toString())?.let {return "https://doi.org/$it"}
        YouTubeUrls.videoId(u.toString())?.let {return "https://www.youtube.com/watch?v=$it"}
        return u.newBuilder().fragment(null).build().toString()
    }
    fun references(body:Element,originalUrl:String):List<SignalReference> {
        val self=identity(originalUrl)
        return body.select("a[href]").take(2048).mapNotNull {anchor->
            if(anchor.parents().any {it.normalName() in setOf("nav","aside","footer","form")||Regex("(?:^|[ _-])(?:related|recommend|share|advert|promo)(?:[ _-]|$)",RegexOption.IGNORE_CASE).containsMatchIn(it.className())})return@mapNotNull null
            val raw=anchor.absUrl("href");val url=identity(raw) ?: return@mapNotNull null
            if(url==self||raw.substringBefore('#')==originalUrl.substringBefore('#'))return@mapNotNull null
            val label=anchor.text().replace(Regex("\\s+")," ").trim().take(240)
            if(noise.containsMatchIn(label))return@mapNotNull null
            val parent=anchor.parents().firstOrNull {it.normalName() in setOf("p","li","figcaption","blockquote","td")} ?: anchor
            val passage=parent.text().replace(Regex("\\s+")," ").trim().take(1000)
            val host=url.toHttpUrlOrNull()!!.host
            val paper=PublicSourceRecords.paper(url)!=null
            val structure=url.startsWith("https://doi.org/10.2210/pdb",true)
            val named=evidence.containsMatchIn(label)
            val cited=anchor.attr("rel").split(Regex("\\s+")).any {it.equals("cite",true)}||anchor.parents().any {it.normalName()=="cite"||it.attr("role")=="doc-biblioentry"||it.attr("itemprop")=="citation"}
            val caption=YouTubeUrls.videoId(url)!=null&&Regex("full|recording|video|interview|speech|transcript",RegexOption.IGNORE_CASE).containsMatchIn(label)
            val primary=host.endsWith(".gov")||host.endsWith(".gov.uk")||host=="data.europa.eu"||host=="github.com"||host=="zenodo.org"||host=="osf.io"||host=="arxiv.org"
            val context=evidence.containsMatchIn(passage)&&label.length>=8
            val score=when {structure->70;paper->100;caption->95;cited->92;named&&primary->90;named->85;primary&&context->75;else->return@mapNotNull null}
            SignalReference(url,label.ifBlank {host},when {structure->"Structural data";paper->"Cited research";caption->"Linked recording";cited->"Cited source";primary->"Linked primary material";else->"Linked evidence"},passage,score)
        }.distinctBy {it.url}.sortedByDescending {it.priority}.distinctBy {it.reason+"|"+it.passage.ifBlank {it.url}}.take(12)
    }
    fun follow(references:List<SignalReference>,depth:Int,seen:Set<String>,remaining:Int):List<SignalReference> {
        if(depth>=MAX_DEPTH||remaining<=0)return emptyList()
        val identities=seen.mapNotNull(::identity).toSet()
        return references.filter {it.priority>=75&&identity(it.url)!=null&&identity(it.url) !in identities}
            .distinctBy {identity(it.url)}.sortedByDescending {it.priority}.distinctBy {it.passage.ifBlank {it.url}}.take(minOf(PER_ARTICLE,remaining,MAX_FOLLOW_UPS))
    }
}
