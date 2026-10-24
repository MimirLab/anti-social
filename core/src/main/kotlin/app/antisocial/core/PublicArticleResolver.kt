/* AntiSocial, GPL-3.0-or-later. Public article recovery adapted from Darwin.
 * No credentials, archive submissions, page scripts, or model-selected tools. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal interface ArticleTransport {
    val originalAllowed:Boolean
    val session:NetworkSession
    val mode:NetworkPolicy get()=session.policy
    suspend fun document(url:String,source:String,headers:Map<String,String>,head:Boolean=false,strict:Boolean=true,purpose:FetchPurpose=FetchPurpose.SUPPLEMENTAL,expectedSession:NetworkSession):GatewayDocument
    suspend fun index(url:String,source:String,origins:Set<String>,expectedSession:NetworkSession):Payload
}
private class GatewayArticleTransport(private val gateway:NetworkGateway):ArticleTransport {
    override val originalAllowed get()=gateway.originalFallback
    override val session get()=gateway.currentSession()
    override suspend fun document(url:String,source:String,headers:Map<String,String>,head:Boolean,strict:Boolean,purpose:FetchPurpose,expectedSession:NetworkSession)=gateway.extractorDocument(url,
        method=if(head)"HEAD"else "GET",headers=headers,sourceUrl=source,sameOrigin=strict,purpose=purpose,expectedSession=expectedSession)
    override suspend fun index(url:String,source:String,origins:Set<String>,expectedSession:NetworkSession)=gateway.text(url,source,maxBytes=1024L*1024,allowedOrigins=origins,expectedSession=expectedSession)
}

/** One bounded acquisition policy shared by native reading and AI preparation.
 * Health contains provider/publisher hints, never preference or model decisions. */
class PublicArticleResolver internal constructor(private val transport:ArticleTransport,private val routes:DarwinArticleRoutes=DarwinArticleRoutes(),private val clock:()->Long=System::currentTimeMillis,private val extract:suspend (Payload,String,Long)->ContextArticleEvidence={payload,url,at->ContextArticles.extract(payload,url,at,readerChecks=true)}) {
    constructor(gateway:NetworkGateway,clock:()->Long=System::currentTimeMillis,extract:suspend (Payload,String,Long)->ContextArticleEvidence={payload,url,at->ContextArticles.extract(payload,url,at,readerChecks=true)}):this(GatewayArticleTransport(gateway),clock=clock,extract=extract)
    private data class Health(val failures:Int=0,val until:Long=0,val latency:Long=0)
    private data class Hint(val at:Long,val path:ArticlePath)
    private val health=linkedMapOf<String,Health>()
    private val hints=linkedMapOf<String,Hint>()
    private val rejected=linkedMapOf<String,Long>()
    private data class ValidatorRecord(val session:NetworkSession,val at:Long,val validators:ContextArticleValidators,val bytes:Int)
    private val validators=linkedMapOf<String,ValidatorRecord>()
    private var validationGeneration=0L
    private fun representation(url:String)=url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()?.takeIf {it.length<=4096}
    @Synchronized internal fun clearValidationMemory(){validationGeneration++;validators.clear()}
    @Synchronized private fun validationGeneration()=validationGeneration
    @Synchronized private fun allowedValidators(url:String,session:NetworkSession,generation:Long,supplied:ContextArticleValidators):ValidatorRecord? {
        validators.entries.removeAll {it.value.session!==session||clock()-it.value.at !in 0..30*60_000L}
        if(session.policy.tor||transport.session!==session||validationGeneration!=generation)return null
        val record=validators[representation(url)] ?: return null
        if(supplied.etag==null&&supplied.lastModified==null)return null
        if(supplied.etag!=null&&supplied.etag!=record.validators.etag||supplied.lastModified!=null&&supplied.lastModified!=record.validators.lastModified)return null
        return record.copy(validators=supplied)
    }
    @Synchronized private fun rememberValidators(url:String,session:NetworkSession,generation:Long,value:ContextArticleValidators,at:Long=clock()) {
        if(transport.session!==session||session.policy.tor||validationGeneration!=generation)return
        val key=representation(url) ?: return
        validators.remove(key)
        if(value.etag!=null||value.lastModified!=null)validators[key]=ValidatorRecord(session,at,value,128+(key.length+(value.etag?.length ?: 0)+(value.lastModified?.length ?: 0))*2)
        validators.entries.removeAll {it.value.session!==session||clock()-it.value.at !in 0..30*60_000L}
        while(validators.size>128||validators.values.sumOf {it.bytes}>256*1024)validators.remove(validators.keys.first())
    }
    private fun key(url:String,mode:NetworkPolicy):String {
        val host=url.toHttpUrlOrNull()?.host.orEmpty()
        return host+"|"+mode.tor+if(host=="www.ebi.ac.uk"&&JatsArticles.endpointId(url)!=null)"|jats"else ""
    }
    @Synchronized private fun eligible(url:String,mode:NetworkPolicy)=(health[key(url,mode)]?.until?.let {it<=clock()} ?: true)&&
        (rejected[mode.tor.toString()+"|"+url]?.let {it<=clock()} ?: true)
    @Synchronized private fun report(url:String,mode:NetworkPolicy,started:Long,success:Boolean) {
        val k=key(url,mode);val previous=health.remove(k) ?: Health()
        val failures=if(success)0 else previous.failures+1
        health[k]=Health(failures,if(success)0 else clock()+minOf(6*60*60_000L,30_000L*(1L shl failures.coerceAtMost(9))),if(success)(previous.latency*3+(clock()-started).coerceAtLeast(0))/4 else previous.latency)
        while(health.size>128)health.remove(health.keys.first())
    }
    @Synchronized private fun hint(key:String):ArticlePath?=hints[key]?.takeIf {clock()-it.at in 0..30*60_000L}?.path
    @Synchronized private fun remember(key:String,path:ArticlePath) {hints.remove(key);hints[key]=Hint(clock(),path);while(hints.size>128)hints.remove(hints.keys.first())}
    @Synchronized private fun rejectUrl(url:String,mode:NetworkPolicy) {rejected.remove(mode.tor.toString()+"|"+url);rejected[mode.tor.toString()+"|"+url]=clock()+5*60_000;while(rejected.size>128)rejected.remove(rejected.keys.first())}
    @Synchronized fun reject(original:String,retrieved:String) {hints.entries.removeAll {it.key.endsWith("|$original")&&it.value.path.url==retrieved};rejectUrl(retrieved,transport.mode)}

    suspend fun conditional(url:String,source:String,validators:ContextArticleValidators=ContextArticleValidators(),knownRetrieval:String?=null,purpose:FetchPurpose=FetchPurpose.SUPPLEMENTAL):ContextArticleFetch=
        withTimeoutOrNull(if(transport.mode.tor)120_000L else 45_000L){resolve(url,source,validators,knownRetrieval,purpose)}
            ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"Public copy lookup reached its time allowance")
    private suspend fun resolve(url:String,source:String,validators:ContextArticleValidators,knownRetrieval:String?,purpose:FetchPurpose):ContextArticleFetch {
        require(url.toHttpUrlOrNull()?.let {it.username.isEmpty()&&it.password.isEmpty()}==true){"Invalid article URL"}
        if(purpose==FetchPurpose.SUPPLEMENTAL&&!AutomaticLinks.mayRead(url))throw SourceFailure(HealthStatus.UNSUPPORTED,"Action links require deliberate opening")
        val session=transport.session;val mode=session.policy;val generation=validationGeneration()
        if(mode.tor&&!mode.ready)throw SourceFailure(HealthStatus.UNAVAILABLE,"Tor is not ready")
        val original=routes.identity(url) ?: url
        val cacheKey=mode.tor.toString()+"|"+original
        val attempted=mutableSetOf<String>();var requests=0;var last:Exception?=null
        fun checkMode(){if(transport.session!==session)throw CancellationException("Network mode changed")}
        suspend fun attempt(path:ArticlePath):ContextArticleFetch? {
            checkMode();if(requests>=10||requests>=9&&path.route!="Original publisher"||!attempted.add(path.url)||!eligible(path.url,mode))return null
            requests++;val started=clock()
            try {
                val result=withTimeout(if(mode.tor)40_000L else 15_000L) {
                    val exact=knownRetrieval!=null&&representation(knownRetrieval)==representation(path.url)||knownRetrieval==null&&path.url==url
                    val permitted=if(exact)allowedValidators(path.url,session,generation,validators)else null
                    val headers=permitted?.validators?.let {v->buildMap {v.etag?.let {put("If-None-Match",it)};v.lastModified?.let {put("If-Modified-Since",it)}}}.orEmpty()
                    val response=transport.document(path.url,source,headers,path.pdf,path.route!="Original publisher"||headers.isNotEmpty(),purpose,session)
                    checkMode()
                    if(path.route!="Original publisher"&&!routes.sameArticle(path.url,response.finalUrl))
                        throw SourceFailure(HealthStatus.EMPTY,"The reader route did not return its requested article")
                    if(path.route=="Original publisher"&&url.toHttpUrlOrNull()?.encodedPath!="/"&&response.finalUrl.toHttpUrlOrNull()?.encodedPath=="/")
                        throw SourceFailure(HealthStatus.EMPTY,"The publisher returned its home page instead of this article")
                    fun header(name:String)=response.headers.entries.firstOrNull {it.key.equals(name,true)}?.value?.firstOrNull()
                    fun clean(raw:String?,limit:Int)=raw?.takeIf {it.length<=limit&&it.all {c->c.code in 32..126}}
                    // Tor circuits can change without a policy/port change. Do
                    // not echo opaque re-identifiers across those circuits.
                    val next=if(mode.tor)ContextArticleValidators()else ContextArticleValidators(clean(header("ETag"),2048),clean(header("Last-Modified"),256))
                    if(response.status==304) {
                        if(permitted==null||!response.conditional||representation(response.finalUrl)!=representation(path.url))throw SourceFailure(HealthStatus.EMPTY,"Unexpected unchanged article response")
                        val unchanged=ContextArticleValidators(next.etag ?: validators.etag,next.lastModified ?: validators.lastModified)
                        rememberValidators(response.finalUrl,session,generation,unchanged,permitted.at)
                        ContextArticleFetch.NotModified(response.finalUrl,unchanged,clock())
                    }else {
                        if(response.status !in 200..299)throw SourceFailure(when(response.status){401->HealthStatus.LOGIN_REQUIRED;429->HealthStatus.RATE_LIMITED;else->HealthStatus.UNAVAILABLE},"Article source returned ${response.status}",httpStatus=response.status)
                        if(purpose==FetchPurpose.SUPPLEMENTAL&&!AutomaticLinks.mayRead(response.finalUrl))throw SourceFailure(HealthStatus.UNSUPPORTED,"Action links require deliberate opening")
                        val type=header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                        if(path.jats&&type !in setOf("application/xml","text/xml","application/jats+xml"))
                            throw SourceFailure(HealthStatus.EMPTY,"The structured source returned no article XML")
                        val access=ArticleAccess(path.route,original,response.finalUrl,archiveTime(response.finalUrl,response.text),path.version,path.license,path.retracted)
                        if(path.pdf||type=="application/pdf") {
                            if(type !in setOf("application/pdf","application/octet-stream"))throw SourceFailure(HealthStatus.EMPTY,"The public document route returned no PDF")
                            ContextArticleFetch.PublicPdf(original,response.finalUrl,access)
                        }else {
                            if(!path.jats)HtmlAdmission.check(response.text)
                            if(path.route=="Existing archive capture"&&!matchingCapture(response.finalUrl,response.text,original))throw SourceFailure(HealthStatus.EMPTY,"The capture did not identify this exact article")
                            val body=extract(Payload(response.text,response.finalUrl,type,response.status),original,clock())
                            if(path.jats&&(JatsArticles.endpointId(response.finalUrl)==null||path.doi!=null&&body.publicationDoi!=path.doi))
                                throw SourceFailure(HealthStatus.EMPTY,"The structured copy did not match the discovered publication")
                            val abstractOnly=response.finalUrl.toHttpUrlOrNull()?.let {it.host=="arxiv.org"&&it.encodedPath.startsWith("/abs/")}==true
                            val site=when {
                                path.route in setOf("scribe","libMedium","small","freedium")->"Medium"
                                path.route=="Publisher text edition"->when(original.toHttpUrlOrNull()?.host?.removePrefix("www.")?.removePrefix("edition.")){"npr.org"->"NPR";"cnn.com"->"CNN";"cbc.ca"->"CBC";else->body.siteName}
                                body.siteName?.lowercase() in setOf("archive.ph","archive.is","archive.today","web.archive.org")->original.toHttpUrlOrNull()?.host?.removePrefix("www.")
                                else->body.siteName
                            }
                            checkMode();rememberValidators(response.finalUrl,session,generation,next)
                            ContextArticleFetch.Ready(body.copy(siteName=site,etag=next.etag,lastModified=next.lastModified,access=access.copy(license=access.license ?: body.access?.license),truncated=body.truncated||abstractOnly))
                        }
                    }
                }
                checkMode();report(path.url,mode,started,true);remember(cacheKey,path);return result
            }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();last=e}
            catch(e:CancellationException){throw e}
            catch(e:Exception){last=e}
            if((last as? SourceFailure)?.httpStatus in setOf(404,410))rejectUrl(path.url,mode)else report(path.url,mode,started,false)
            checkMode();return null
        }
        suspend fun json(endpoint:String):com.google.gson.JsonObject? {
            if(!eligible(endpoint,mode))return null
            val started=clock()
            return try {
            checkMode();val p=withTimeout(if(mode.tor)25_000L else 8_000L){transport.index(endpoint,source,setOf(origin(endpoint)),session)}
            checkMode();val value=JsonParser.parseString(p.text).asJsonObject;report(endpoint,mode,started,true);value
        }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();last=e;report(endpoint,mode,started,false);null}
         catch(e:CancellationException){throw e}catch(e:Exception){last=e;report(endpoint,mode,started,false);null}
        }
        suspend fun archives():ContextArticleFetch? {
            if(!routes.recoverable(original))return null
            for(root in listOf("https://archive.ph","https://archive.is","https://archive.today")) {
                if(!eligible(root,mode))continue
                val started=clock()
                try {
                    val query=root.toHttpUrlOrNull()!!.newBuilder().encodedPath("/search/").addQueryParameter("q",original).build().toString()
                    val p=withTimeout(if(mode.tor)25_000L else 8_000L){transport.index(query,source,ARCHIVE_TODAY,session)};checkMode()
                    ArchiveParsing.wall(p.text)?.let {throw SourceFailure(it,"Archive requires a browser challenge")}
                    val links=archiveTodayCaptures(p,original)
                    report(root,mode,started,true)
                    for(link in links.take(2))attempt(ArticlePath(link,"Existing archive capture"))?.let {return it}
                }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();last=e;report(root,mode,started,false)}
                 catch(e:CancellationException){throw e}catch(e:Exception){last=e;report(root,mode,started,false)}
                break // One selected archive.today mirror per lookup; another can be selected after backoff.
            }
            val endpoint="https://web.archive.org/cdx/search/cdx".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("url",original).addQueryParameter("matchType","exact")
                .addQueryParameter("output","json").addQueryParameter("filter","statuscode:200").addQueryParameter("filter","mimetype:text/html")
                .addQueryParameter("fl","timestamp,original").addQueryParameter("collapse","digest").addQueryParameter("limit","-3").build().toString()
            if(eligible(endpoint,mode)) {
                val started=clock()
                try {
                    val p=withTimeout(if(mode.tor)25_000L else 8_000L){transport.index(endpoint,source,setOf("https://web.archive.org"),session)};checkMode()
                    for(link in waybackCaptures(p.text,original))attempt(ArticlePath(link,"Existing archive capture"))?.let {return it}
                    report(endpoint,mode,started,true)
                }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();last=e;report(endpoint,mode,started,false)}
                 catch(e:CancellationException){throw e}catch(e:Exception){last=e;report(endpoint,mode,started,false)}
            }
            return null
        }
        hint(cacheKey)?.let {if(it.url!=original||transport.originalAllowed)attempt(it)?.let {result->return result}}
        routes.publisherEdition(original)?.let {attempt(it)?.let {result->return result}}
        val medium=routes.medium(original).groupBy {it.route}.values.toList()
        mediumLoop@for(i in 0 until (medium.maxOfOrNull {it.size} ?: 0))for(group in medium) {
            if(requests>=6)break@mediumLoop
            group.getOrNull(i)?.let {attempt(it)?.let {result->return result}}
        }
        var science=routes.scientific(original)
        if(science==null&&routes.metadataPublisher(original)&&transport.originalAllowed) {
            try {val p=transport.document(original,source,emptyMap(),expectedSession=session);checkMode();if(p.status in 200..299)science=routes.metadataDoi(p.text)}
            catch(e:CancellationException){throw e}catch(e:Exception){last=e}
        }
        science?.let {article->
            article.pmcid?.let {id->
                // A stable PMC identifier already gives an exact structured
                // route. No metadata search or publisher visit is necessary.
                attempt(ArticlePath(JatsArticles.endpoint(id),"Europe PMC full text",jats=true))?.let {return it}
                val html="https://europepmc.org/articles/$id"
                if(transport.originalAllowed||!routes.sameArticle(original,html))attempt(ArticlePath(html,"Europe PMC public copy"))?.let {return it}
            }
            var paths=emptyList<ArticlePath>()
            article.doi?.let {doi->json("https://api.openalex.org/works/https://doi.org/"+encode(doi)+"?select=doi,title,is_retracted,locations,best_oa_location")?.let {paths+=runCatching {routes.openAlex(it,doi)}.getOrDefault(emptyList())}}
            fun expanded(values:List<ArticlePath>)=values.flatMap {path->
                val u=path.url.toHttpUrlOrNull()
                val pmc=if(!path.pdf)JatsArticles.pageId(path.url)else null
                when {
                    // A landing page's edition/rights describe that location.
                    // The structured endpoint must supply its own license; an
                    // unknown edition must not inherit a preprint label.
                    pmc!=null&&JatsArticles.endpointId(path.url)==null->listOf(path.copy(url=JatsArticles.endpoint(pmc),route="Europe PMC full text",version=null,license=null,jats=true,doi=path.doi ?: article.doi),path)
                    !path.pdf&&u?.host=="arxiv.org"&&u.encodedPath.startsWith("/abs/")->listOf(path.copy(url=path.url.replace("arxiv.org/abs/","arxiv.org/html/")),path)
                    else->listOf(path)
                }
            }.distinctBy {it.url}
            fun abstract(path:ArticlePath)=path.url.toHttpUrlOrNull()?.let {it.host=="arxiv.org"&&it.encodedPath.startsWith("/abs/")}==true
            // Try already discovered full HTML immediately; another metadata
            // request cannot improve latency when this route already works.
            for(path in expanded(paths).filterNot {it.pdf||abstract(it)}) {
                if(routes.sameArticle(original,path.url)&&!transport.originalAllowed)continue
                attempt(path)?.let {return it}
            }
            val query=article.pmid?.let {"EXT_ID:$it AND SRC:MED"} ?: article.doi?.let {"DOI:\"$it\""}
            if(query!=null)json("https://www.ebi.ac.uk/europepmc/webservices/rest/search?query="+encode(query)+"&format=json&resultType=core&pageSize=5")?.let {pmc->
                paths+=runCatching {routes.europePmc(pmc,article)}.getOrDefault(emptyList())
                if(paths.isEmpty()&&article.pmid!=null)routes.pmidDoi(pmc,article.pmid)?.let {doi->
                    json("https://api.openalex.org/works/https://doi.org/"+encode(doi)+"?select=doi,title,is_retracted,locations,best_oa_location")?.let {paths+=runCatching {routes.openAlex(it,doi)}.getOrDefault(emptyList())}
                }
            }
            for(path in expanded(paths).sortedWith(compareBy<ArticlePath>{abstract(it)}.thenBy {it.pdf})) {
                if(routes.sameArticle(original,path.url)&&!transport.originalAllowed)continue
                attempt(path)?.let {return it}
            }
        }
        val prefer=routes.preferArchives(original)
        if(prefer)archives()?.let {return it}
        if(transport.originalAllowed)attempt(ArticlePath(original,"Original publisher",pdf=original.substringBefore('?').endsWith(".pdf",true)))?.let {return it}
        if(!prefer)archives()?.let {return it}
        throw SourceFailure((last as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,
            "No readable public copy was available"+(if(!transport.originalAllowed)" · original access is off"else "")).also {if(last!=null)it.initCause(last)}
    }
    internal fun waybackCaptures(raw:String,original:String):List<String> {
        val rows=JsonParser.parseString(raw).asJsonArray;require(rows.size()<=64)
        val header=rows.firstOrNull()?.asJsonArray?.map {it.asString} ?: return emptyList()
        val ti=header.indexOf("timestamp");val oi=header.indexOf("original");require(ti>=0&&oi>=0)
        return rows.drop(1).mapNotNull {entry->runCatching {val row=entry.asJsonArray;val stamp=row[ti].asString;val url=row[oi].asString
            if(!routes.sameArticle(original,url)||timestamp(stamp)==null)null else "https://web.archive.org/web/${stamp}id_/$url"
        }.getOrNull()}.distinct().sortedDescending().take(3)
    }
    internal fun archiveTodayCaptures(payload:Payload,original:String):List<String> {
        val u=payload.finalUrl.toHttpUrlOrNull() ?: return emptyList()
        if(origin(payload.finalUrl) !in ARCHIVE_TODAY||u.encodedPath!="/search/"||!routes.sameArticle(u.queryParameter("q").orEmpty(),original))return emptyList()
        HtmlAdmission.check(payload.text)
        return Jsoup.parse(payload.text,payload.finalUrl).select("a[href]").mapNotNull {a->a.absUrl("href").toHttpUrlOrNull()?.takeIf {
            origin(it.toString()) in ARCHIVE_TODAY&&Regex("/[A-Za-z0-9]{5}").matches(it.encodedPath)&&a.text().trim().length>=8
        }?.toString()}.distinct().takeLast(2).reversed()
    }
    internal fun matchingCapture(url:String,html:String,original:String):Boolean {
        ArchiveUrls.original(url)?.let {return routes.sameArticle(original,it)}
        if(origin(url) !in ARCHIVE_TODAY)return false
        HtmlAdmission.check(html)
        val d=Jsoup.parse(html,url)
        val values=d.select("input[name=q],input[name=url],input#url,#SHARE_LONGLINK,meta[property=og:url],link[rel=canonical],#HEADER a[href]").take(64).flatMap {e->listOf(e.attr("value"),e.attr("content"),e.absUrl("href"))}
        return values.any {value->routes.sameArticle(original,value)||Regex("^https://archive\\.(?:ph|is|today)/[0-9.:-]+/(https?://.+)$").matchEntire(value)?.groupValues?.get(1)?.let {routes.sameArticle(original,it)}==true}
    }
    private fun archiveTime(url:String,html:String):Long? {
        ArchiveUrls.original(url)?.let {return url.substringAfter("/web/").substringBefore('/').take(14).let(::timestamp)}
        if(origin(url) !in ARCHIVE_TODAY)return null
        HtmlAdmission.check(html)
        val long=Jsoup.parse(html).selectFirst("#SHARE_LONGLINK")?.attr("value") ?: return null
        val stamp=Regex("^https://archive\\.(?:ph|is|today)/([0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-[0-9]{6})/").find(long)?.groupValues?.get(1) ?: return null
        return timestamp(stamp.filter(Char::isDigit))
    }
    private fun timestamp(raw:String):Long?=runCatching {LocalDateTime.parse(raw,DATE).takeIf {it.year>=1990}?.toInstant(ZoneOffset.UTC)?.toEpochMilli()?.takeIf {it<=clock()+24*60*60_000L}}.getOrNull()
    companion object {
        private val ARCHIVE_TODAY=setOf("https://archive.ph","https://archive.is","https://archive.today")
        private val DATE=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
        private fun origin(raw:String)=raw.toHttpUrlOrNull()?.let {"${it.scheme}://${it.host}"}.orEmpty()
        private fun encode(s:String)=java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20")
    }
}
