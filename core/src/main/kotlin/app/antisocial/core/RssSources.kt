/* AntiSocial, GPL-3.0-or-later. Ordinary feeds are explicit sources, never social aliases. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/** Local feed URL validation. DNS and redirects remain subject to NetworkGateway. */
object RssSources {
    private const val ATOM="http://www.w3.org/2005/Atom"
    private const val RSS1="http://purl.org/rss/1.0/"
    private const val RDF="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val MAX_ITEMS=200
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun publicHost(host: String): Boolean {
        if(host=="localhost"||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal"))return false
        if(host.contains(':')||host.matches(Regex("[0-9.]+"))) {
            val ip=runCatching {InetAddress.getByName(host)}.getOrNull() ?: return false
            if(ip.isAnyLocalAddress||ip.isLoopbackAddress||ip.isLinkLocalAddress||ip.isSiteLocalAddress||ip.isMulticastAddress||ip.hostAddress.orEmpty().startsWith("fc",true)||ip.hostAddress.orEmpty().startsWith("fd",true))return false
        }
        return true
    }
    /** HTTPS only: importing a feed never silently enables insecure requests. */
    fun feedUrl(input: String): String? {
        val value=input.trim().takeIf {it.length in 1..8192&&!it.any(Char::isISOControl)} ?: return null
        val url=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null&&publicHost(it.host)} ?: return null
        return url.toString()
    }
    fun target(input: String): Target {
        val url=requireNotNull(feedUrl(input)){"Use a public HTTPS RSS or Atom feed URL"}
        return Target(Platform.RSS,TargetKind.FEED,url,url)
    }
    fun followId(input: String): String="rss:"+hash(requireNotNull(feedUrl(input)){"Use a public HTTPS RSS or Atom feed URL"})
    fun itemTarget(post: Post): Target {
        require(post.platform==Platform.RSS){"This is not a feed item"}
        return Target(Platform.RSS,TargetKind.POST,ContentIdentity.key(Platform.RSS,post.canonicalUrl),post.canonicalUrl)
    }
    fun source(feed: String): Source {
        val url=requireNotNull(feedUrl(feed)){"Use a public HTTPS RSS or Atom feed URL"}.toHttpUrlOrNull()!!
        val root=url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        return Source(Platform.RSS,"rss",root,role=SourceRole.ORIGINAL)
    }
    private fun local(e: Element)=e.normalName().substringAfter(':')
    private fun namespace(e: Element): String {
        val prefix=e.normalName().substringBefore(':',"")
        val field=if(prefix.isEmpty())"xmlns"else "xmlns:$prefix"
        return generateSequence(e){it.parent()}.map {it.attr(field)}.firstOrNull {it.isNotEmpty()}.orEmpty()
    }
    private fun children(e: Element,vararg names: String)=e.children().filter {it.normalName() in names||local(it) in names}
    private fun text(e: Element,vararg names: String)=names.firstNotNullOfOrNull {name->
        val normalized=name.lowercase(java.util.Locale.ROOT)
        e.children().firstOrNull {if(normalized.contains(':'))it.normalName()==normalized else local(it)==normalized}?.wholeText()?.trim()?.takeIf(String::isNotEmpty)
    }.orEmpty()
    private fun base(e: Element,fallback: String): String {
        var value=fallback
        generateSequence(e){it.parent()}.toList().asReversed().forEach {node->
            node.attr("xml:base").takeIf(String::isNotBlank)?.let {raw->resolve(raw,value)?.let {value=it}}
        }
        return value
    }
    private fun resolve(raw: String,base: String): String?=raw.trim().takeIf {it.isNotEmpty()&&it.length<=8192&&!it.any(Char::isISOControl)}?.let {value->
        runCatching {URI(base).resolve(value).toString()}.getOrNull()?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()&&publicHost(it.host)}?.toString()
    }
    private fun markup(e: Element?): String {
        if(e==null)return ""
        return if(e.attr("type").equals("xhtml",true)||e.children().isNotEmpty())e.html()else e.wholeText()
    }
    private fun atomText(element: Element?): String {
        if(element==null)return ""
        return when(element.attr("type").ifBlank {"text"}.lowercase()){
            "html","text/html"->Extraction.plain(element.wholeText())
            "xhtml","application/xhtml+xml"->Extraction.plain(element.html())
            else->element.wholeText().trim()
        }
    }
    /** No external entities, discovery crawl, pagination request, scripts or article scraping. */
    fun parse(payload: Payload,target: Target,now: Long=System.currentTimeMillis()): Page {
        require(target.platform==Platform.RSS&&target.kind==TargetKind.FEED){"Expected an ordinary feed target"}
        val feed=requireNotNull(feedUrl(target.canonicalUrl)){"Use a public HTTPS RSS or Atom feed URL"}
        val finalUrl=requireNotNull(feedUrl(payload.finalUrl)){"Feed redirected to an unsupported URL"}
        val body=payload.text.trimStart('\uFEFF').trim()
        if(body.length>4*1024*1024||body.contains('\u0000')||Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b",RegexOption.IGNORE_CASE).containsMatchIn(body))throw SourceFailure(HealthStatus.UNSUPPORTED,"Feed declarations and external entities are not supported")
        val document=Jsoup.parse(body,finalUrl,Parser.xmlParser())
        val root=document.children().singleOrNull() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This URL did not return an RSS or Atom feed")
        val atom=local(root)=="feed"&&namespace(root)==ATOM
        val rss=local(root)=="rss"&&root.children().count {local(it)=="channel"}==1
        val rdf=local(root)=="rdf"&&namespace(root)==RDF&&root.children().any {local(it)=="channel"&&namespace(it)==RSS1}
        if(!atom&&!rss&&!rdf)throw SourceFailure(HealthStatus.UNSUPPORTED,"This URL did not return an RSS or Atom feed")
        val channel=if(atom)root else root.children().first {local(it)=="channel"}
        val feedName=(if(atom)atomText(children(channel,"title").firstOrNull())else Extraction.plain(text(channel,"title"))).ifBlank {URI(feed).host}
        val feedAuthor=children(channel,"author").firstOrNull()?.let {text(it,"name").ifBlank {it.wholeText().trim()}}.orEmpty()
        val description=if(atom)atomText(children(channel,"subtitle").firstOrNull())else Extraction.plain(text(channel,"description"))
        val provenance=Provenance(source(finalUrl),now,finalUrl)
        val nodes=if(rdf)root.children().filter {local(it)=="item"&&namespace(it)==RSS1}else children(channel,if(atom)"entry"else "item")
        val posts=nodes.take(MAX_ITEMS).mapNotNull {item->
            val itemBase=base(item,finalUrl)
            val content=if(atom)children(item,"content").firstOrNull()?.takeUnless {it.hasAttr("src")||it.attr("type").ifBlank {"text"}.lowercase() !in setOf("text","html","xhtml","text/html","application/xhtml+xml")} ?: children(item,"summary").firstOrNull()else item.children().firstOrNull {it.normalName()=="content:encoded"} ?: children(item,"description").firstOrNull()
            val rich=markup(content)
            val contentBase=content?.let {base(it,finalUrl)} ?: itemBase
            val literalAtom=atom&&content?.attr("type").orEmpty().ifBlank {"text"}.lowercase()=="text"
            val html=(if(literalAtom)Jsoup.parse("",contentBase).apply {body().appendText(content?.wholeText().orEmpty())}else Jsoup.parse(rich,contentBase)).apply {select("script,style,iframe,form,object,embed").remove()}
            val title=if(atom)atomText(children(item,"title").firstOrNull())else Extraction.plain(text(item,"title"))
            val plain=if(literalAtom)content?.wholeText()?.trim().orEmpty()else html.text()
            val links=children(item,"link")
            val linkNode=if(atom)links.firstOrNull {it.attr("rel").ifBlank {"alternate"}=="alternate"&&it.attr("type").let {type->type.isBlank()||type=="text/html"||type=="application/xhtml+xml"}}else links.firstOrNull()
            val rawLink=if(atom)linkNode?.attr("href").orEmpty()else linkNode?.wholeText().orEmpty()
            val guid=if(atom)text(item,"id")else text(item,"guid")
            val permalink=if(!atom)children(item,"guid").firstOrNull()?.takeUnless {it.attr("isPermaLink").equals("false",true)}?.wholeText().orEmpty()else ""
            val direct=resolve(rawLink,linkNode?.let {base(it,finalUrl)} ?: itemBase) ?: resolve(permalink,itemBase)
            val published=Extraction.timestamp(text(item,if(atom)"published"else "pubDate","dc:date",if(atom)"updated"else "date"))
            val canonical=direct ?: feed+"#antisocial-entry="+hash(guid.ifBlank {listOf(title,plain,published?.toString().orEmpty()).joinToString("\u0000")})
            val media=mutableListOf<Media>()
            html.select("img[src]").take(32).forEach {m->resolve(m.attr("src"),contentBase)?.let {media+=Media(it,description=m.attr("alt"))}}
            media+=VideoExtraction.html(html,contentBase)
            val mediaNodes=item.getAllElements().filter {it!=item&&((local(it)=="enclosure")||(it.normalName() in setOf("media:content","media:thumbnail"))||(atom&&local(it)=="link"&&it.attr("rel")=="enclosure"))}
            mediaNodes.take(32).forEach {m->
                val url=resolve(if(atom&&local(m)=="link")m.attr("href")else m.attr("url"),base(m,finalUrl)) ?: return@forEach
                val type=m.attr("type").lowercase().substringBefore(';')
                val kind=when {type.startsWith("image/")||m.normalName()=="media:thumbnail"->"image";type.startsWith("video/")||VideoExtraction.mime(type)!=null->"video";type.startsWith("audio/")->"audio";else->null} ?: return@forEach
                media+=Media(url,kind,mimeType=type.takeIf(String::isNotBlank),durationMs=if(kind=="video"&&m.normalName()=="media:content")MediaDuration.seconds(m.attr("duration"))else null)
            }
            val selected=MediaDuration.distinctMedia(media)
            if(title.isBlank()&&plain.isBlank()&&selected.isEmpty())return@mapNotNull null
            val author=if(atom)children(item,"author").firstOrNull()?.let {text(it,"name").ifBlank {it.wholeText().trim()}}.orEmpty().ifBlank {feedAuthor}else text(item,"dc:creator","author")
            Post(ContentIdentity.key(Platform.RSS,canonical),Platform.RSS,canonical,author.ifBlank {feedName},plain,title,published,media=selected,provenance=provenance,links=html.select("a[href]").mapNotNull {resolve(it.attr("href"),contentBase)}.filter {it!=canonical}.distinct().take(24))
        }.distinctBy {it.id}.sortedWith(compareByDescending<Post>{it.publishedAt ?: Long.MIN_VALUE}.thenBy {it.id})
        if(posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"This feed has no readable entries yet")
        return Page(posts,profile=Profile(target,feedName,description),provenance=provenance,notice="Showing the text and media supplied by this feed; full articles may contain more.")
    }
}
