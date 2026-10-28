/* anti social, GPL-3.0-or-later. Preserve public relationships before text washing. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PublicRelationKind { FORWARD, COLLABORATOR, TAGGED, SPONSOR, REPOSTED_BY }
data class PublicRelation(val kind:PublicRelationKind,val url:String,val name:String="")
data class PublisherLinks(val feeds:List<Pair<String,String>> = emptyList(),val blogrolls:List<String> = emptyList(),val recommendations:List<Pair<String,String>> = emptyList(),val ghost:Boolean=false)

/** Pure bounded extraction. No browser, scripting, assets, file retention or I/O. */
object PublicRelations {
    const val MAX_DOCUMENT=512*1024
    fun valid(post:Post)=post.publicRelations.orEmpty().let {items->items.size<=16&&items.all {it.kind in PublicRelationKind.entries&&it.name.length<=160&&it.url.length<=8192&&RssSources.feedUrl(it.url)!=null}&&
        (items.none {it.kind==PublicRelationKind.REPOSTED_BY}||NitterReposts.handle(post)!=null)}
    fun url(raw:String,base:String):String?=raw.takeIf {it.isNotBlank()&&it.length<=8192}?.let {runCatching {base.toHttpUrlOrNull()?.resolve(it)?.newBuilder()?.fragment(null)?.build()?.toString()?.let(RssSources::feedUrl)}.getOrNull()}
    fun json(body:String):JsonElement {
        if(body.length>MAX_DOCUMENT)throw SourceFailure(HealthStatus.UNSUPPORTED,"Relationship data exceeds its reading allowance")
        var depth=0;var quoted=false;var escaped=false
        for(c in body){if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else if(c=='"')quoted=true else if(c=='{'||c=='['){depth++;if(depth>32)throw SourceFailure(HealthStatus.UNSUPPORTED,"Relationship data is too deeply nested")}else if(c=='}'||c==']'){depth--;require(depth>=0)}}
        require(depth==0&&!quoted)
        return JsonParser.parseString(body)
    }
    fun publisher(payload:Payload,headers:Map<String,List<String>> = emptyMap()):PublisherLinks {
        if(payload.text.length>MAX_DOCUMENT)throw SourceFailure(HealthStatus.UNSUPPORTED,"Publisher data exceeds its reading allowance")
        HtmlAdmission.check(payload.text)
        val page=Jsoup.parse(payload.text,payload.finalUrl)
        return publisher(page,payload.finalUrl,headers)
    }
    fun publisher(page:Element,finalUrl:String,headers:Map<String,List<String>> = emptyMap()):PublisherLinks {
        val feeds=ArrayList<Pair<String,String>>();val rolls=ArrayList<String>();val recommendations=ArrayList<Pair<String,String>>()
        fun add(raw:String,label:String,type:String,rel:String) {
            val link=url(raw,finalUrl) ?: return
            if(!AutomaticLinks.mayRead(link))return
            if(rel.split(Regex("\\s+")).any {it in setOf("blogroll","subscriptions")}||type.substringBefore(';').trim() in setOf("text/x-opml","text/x-opml+xml","application/opml+xml"))rolls+=link
            else if(type.substringBefore(';').trim() in setOf("application/rss+xml","application/atom+xml","application/feed+json"))feeds+=link to label.take(160)
            else if(rel.split(Regex("\\s+")).any {it in setOf("friend","colleague","co-worker","muse")})recommendations+=link to label.take(160)
        }
        page.select("link[href],a[href]").take(1024).forEach {n->
            val rel=n.attr("rel").lowercase();val type=n.attr("type").lowercase()
            val labelled=n.text().trim().ifBlank {n.attr("title")}
            add(n.attr("href"),labelled,type,rel)
            if(n.normalName()=="a"&&labelled.matches(Regex("(?i)(?:blogroll|reading list|recommended (?:blogs|sites|reading)|subscriptions)")))url(n.attr("href"),finalUrl)?.let {if(it.substringBefore('?').endsWith(".opml",true))rolls+=it}
        }
        headers.entries.filter {it.key.equals("Link",true)}.flatMap {it.value}.take(8).forEach {line->
            Regex("<([^>]{1,8192})>((?:[^,<]|,[^<])*)").findAll(line.take(32_768)).take(16).forEach {m->
                fun attribute(key:String)=Regex("(?:^|;)\\s*$key\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))",RegexOption.IGNORE_CASE).find(m.groupValues[2])?.let {it.groupValues[1].ifBlank {it.groupValues[2]}}.orEmpty()
                add(m.groupValues[1],"",attribute("type").lowercase(),attribute("rel").lowercase())
            }
        }
        val ghost=page.select("meta[name=generator]").any {it.attr("content").startsWith("Ghost",true)}
        return PublisherLinks(feeds.distinctBy {it.first}.take(8),rolls.distinct().take(4),recommendations.distinctBy {it.first}.take(12),ghost)
    }
    fun opml(payload:Payload):List<Triple<String,String,String>> {
        require(payload.text.length<=MAX_DOCUMENT)
        if(Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(payload.text))throw SourceFailure(HealthStatus.UNSUPPORTED,"External feed-list declarations are not supported")
        HtmlAdmission.check(payload.text)
        val doc=Jsoup.parse(payload.text,payload.finalUrl,Parser.xmlParser());require(doc.children().singleOrNull()?.normalName()=="opml")
        return doc.select("outline[xmlUrl]").take(80).mapNotNull {n->url(n.attr("xmlUrl"),payload.finalUrl)?.let {feed->
            val category=n.parents().filter {it.normalName()=="outline"&&it.attr("xmlUrl").isBlank()}.reversed().map {it.attr("title").ifBlank {it.attr("text")}.take(80)}.filter(String::isNotBlank).joinToString(" / ").take(160)
            Triple(feed,n.attr("title").ifBlank {n.attr("text")}.take(160),category)
        }}.distinctBy {it.first}
    }
    /** Feed-level declarations only. Episode mentions, enclosures and arbitrary
     * links never become a publisher recommendation or trigger an audio fetch. */
    fun readingFeed(payload:Payload):PublisherLinks? {
        if(!payload.text.contains("<rss",true))return null
        require(payload.text.length<=MAX_DOCUMENT)
        if(Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(payload.text))return null
        HtmlAdmission.check(payload.text)
        val doc=Jsoup.parse(payload.text,payload.finalUrl,Parser.xmlParser())
        val root=doc.children().singleOrNull()?.takeIf {it.normalName()=="rss"} ?: return null
        val channel=root.children().singleOrNull {it.normalName()=="channel"} ?: return null
        val prefixes=root.attributes().associate {it.key.removePrefix("xmlns:") to it.value.trimEnd('/')}
        val podcast=prefixes.entries.firstOrNull {it.value=="https://podcastindex.org/namespace/1.0"}?.key
        val blog=prefixes.entries.firstOrNull {it.value=="http://backend.userland.com/blogChannelModule"}?.key
        val feeds=channel.children().filter {podcast!=null&&it.tagName()=="$podcast:podroll"}.flatMap {it.children()}.filter {it.tagName()=="$podcast:remoteItem"}.take(24).mapNotNull {n->
            url(n.attr("feedUrl"),payload.finalUrl)?.takeIf(AutomaticLinks::mayRead)?.let {it to n.attr("title").take(160)}
        }
        val rolls=channel.children().filter {blog!=null&&it.tagName() in setOf("$blog:blogRoll","$blog:mySubscriptions")}.take(4).mapNotNull {url(it.text(),payload.finalUrl)?.takeIf(AutomaticLinks::mayRead)}
        return PublisherLinks(feeds.distinctBy {it.first},rolls.distinct())
    }
    /** Only the actual work's citation fields; publisher identities and arbitrary
     * nested navigation are not new evidence sources. */
    fun citations(document:Element,original:String):List<SignalReference> {
        val result=ArrayList<SignalReference>()
        val works=ArrayList<JsonObject>()
        fun collect(value:JsonElement,depth:Int=0) {
            if(depth>3)return
            if(value.isJsonArray){value.asJsonArray.take(16).forEach {collect(it,depth+1)};return}
            if(!value.isJsonObject)return
            val o=value.asJsonObject
            val type=o["@type"]?.let {if(it.isJsonArray)it.asJsonArray.mapNotNull {v->v.takeIf(JsonElement::isJsonPrimitive)?.asString}else listOfNotNull(it.takeIf(JsonElement::isJsonPrimitive)?.asString)}.orEmpty()
            if(type.any {it.substringAfterLast('/') in setOf("Article","NewsArticle","BlogPosting","ScholarlyArticle","Report","TechArticle")})works+=o
            o["@graph"]?.let {collect(it,depth+1)}
        }
        document.select("script[type=application/ld+json]").take(8).forEach {node->if(node.data().length<=64*1024)runCatching {json(node.data())}.getOrNull()?.let {collect(it)}}
        for(o in works) {
            val identity=o["url"]?.takeIf {it.isJsonPrimitive}?.asString ?: o["@id"]?.takeIf {it.isJsonPrimitive}?.asString
            val same=identity==null&&works.size==1||identity?.let {url(it,original)?.trimEnd('/')==url(original,original)?.trimEnd('/')}==true
            if(!same)continue
            for(field in listOf("citation","isBasedOn")) {
                val raw=o[field] ?: continue;val entries=if(raw.isJsonArray)raw.asJsonArray.take(12)else listOf(raw)
                for(entry in entries){val target=if(entry.isJsonPrimitive&&entry.asJsonPrimitive.isString)entry.asString else if(entry.isJsonObject)entry.asJsonObject["url"]?.takeIf {it.isJsonPrimitive}?.asString ?: entry.asJsonObject["@id"]?.takeIf {it.isJsonPrimitive}?.asString else null
                    val resolved=target?.let {url(it,original)}?.let(SignalDiscovery::identity) ?: continue
                    if(resolved==SignalDiscovery.identity(original))continue
                    val label=if(entry.isJsonObject)entry.asJsonObject["name"]?.takeIf {it.isJsonPrimitive}?.asString?.take(240)else null
                    result+=SignalReference(resolved,label ?: "Published source reference","Cited source","Declared in the article's $field metadata",92)
                }
            }
        }
        return result.distinctBy {it.url}.take(8)
    }
}
