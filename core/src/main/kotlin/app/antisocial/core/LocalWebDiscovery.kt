/* AntiSocial, GPL-3.0-or-later. Public index links discover publishers; snippets are not facts. */
package app.antisocial.core

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** Public links share endpoint learning with social search, without asserting
 * that a link is a hydrated post or training latency on later publisher work. */
class LocalWebDiscovery(private val gateway:NetworkGateway,private val routes:DarwinRoutes,
    private val learning:AdaptiveRouting=AdaptiveRouting(),private val clock:()->Long=System::currentTimeMillis,
    private val onLearning:(List<RouteStats>)->Unit={}) {
    private val permits=Semaphore(3)
    private val htmlOnly=linkedMapOf<String,Long>()
    private data class Candidate(val source:Source,val api:Boolean)
    @Synchronized private fun prefersHtml(source:Source)=(htmlOnly[source.root] ?: 0)>clock()
    @Synchronized private fun rememberHtml(source:Source) {
        htmlOnly.remove(source.root);htmlOnly[source.root]=clock()+6*3_600_000L
        while(htmlOnly.size>64)htmlOnly.remove(htmlOnly.keys.first())
    }
    private data class Trial(val index:Int,val hits:List<LocalWebHit>?=null,val failure:Exception?=null)
    private fun sources(provider:SearchProvider,originalFallback:Boolean)=when(provider) {
        SearchProvider.BING,SearchProvider.DUCKDUCKGO->if(originalFallback)listOf(Source(Platform.X,provider.frontend!!,provider.root!!,true,SourceRole.ORIGINAL))else emptyList()
        SearchProvider.DARWIN->routes.searchSources.filter {it.frontend=="librey"}.distinctBy {it.root.trimEnd('/')}.take(64)
    }
    /** Local scheduling information; checking it sends no probe and does not
     * renew a deadline. Callers can skip a known pause before starting Tor. */
    fun pause(provider:SearchProvider,originalFallback:Boolean):SourceFailure? {
        val sources=sources(provider,originalFallback)
        if(sources.isEmpty())return SourceFailure(HealthStatus.UNSUPPORTED,"Enable a public search source in Settings to discover additional local publishers")
        if(sources.any {learning.eligible(it,Capability.SEARCH,true)})return null
        val wait=sources.minOf {learning.eligibleAt(it,Capability.SEARCH,true)}-clock()
        return SourceFailure(HealthStatus.UNAVAILABLE,"The selected public search endpoints are paused; cached results remain available",wait.coerceAtLeast(0))
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun search(query:String,provider:SearchProvider,originalFallback:Boolean,stillAllowed:()->Boolean={true}):List<LocalWebHit> {
        require(query.length in 2..512)
        val sources=sources(provider,originalFallback)
        if(sources.isEmpty())throw SourceFailure(HealthStatus.UNSUPPORTED,"Enable a public search source in Settings to discover additional local publishers")
        val session=gateway.currentSession(NetworkRoute.TOR);val selection=gateway.currentSelection()
        fun current() {
            if(gateway.currentSession(NetworkRoute.TOR)!==session||gateway.currentSelection()!==selection||!stillAllowed()||provider.torOnly&&!gateway.originalFallback)
                throw CancellationException("The selected search policy changed")
        }
        current()
        pause(provider,originalFallback)?.let {throw it}
        fun address(candidate:Candidate):String {
            val source=candidate.source
            return source.root.trimEnd('/')+when(source.frontend){"bing-rss"->"/search?format=rss&q=";"duckduckgo"->"/html/?q=";else->if(candidate.api)"/api.php?q="else "/search.php?q="}+DarwinRoutes.encode(query)+
                if(source.frontend=="librey"){if(candidate.api)"&t=0&nf=1&ns=1&nfb=1"else "&t=0&nf=1&ns=1"}else ""
        }
        fun target(candidate:Candidate)=Target(candidate.source.platform,TargetKind.SEARCH,query,address(candidate))
        val ordered=sources.sortedWith(
            compareBy<Source> {learning.get(it,Capability.SEARCH,true)?.lastSuccessAt?.let {at->clock()-at in 0..86_400_000L}!=true}
                .thenBy {learning.score(it,Capability.SEARCH,true)})
        // Cheap structured reads precede a retained HTML/server-assisted path.
        // Both representations spend the SAME three-attempt allowance.
        val primary=ordered.map {Candidate(it,it.root.trimEnd('/') in routes.searchJsonApis&&!prefersHtml(it))}
        val available=primary+primary.filter {it.api}.map {it.copy(api=false)}
        val candidates=available.filter {learning.eligible(it.source,Capability.SEARCH,true,target(it))}
        var failure:Exception?=available.firstNotNullOfOrNull {learning.failure(it.source,Capability.SEARCH,true,target(it))}
        if(candidates.isEmpty())throw failure ?: SourceFailure(HealthStatus.EMPTY,"No publisher links were available")
        if(!session.policy.ready||session.policy.socksPort !in 1..65535)throw SourceFailure(HealthStatus.UNAVAILABLE,"Waiting for a private search connection. No direct fallback was used.")
        return coroutineScope {
            val replies=Channel<Trial>(3);val running=linkedMapOf<Int,Job>();val attempts=AtomicInteger()
            var next=0;var latest=-1;var started=0L;var hedgeMs=0L;var advance=true
            fun observe(candidate:Candidate,status:HealthStatus,elapsed:Long,detail:String,retry:Long?=null) {
                current();learning.observe(ProbeResult(candidate.source,Capability.SEARCH,status,elapsed,clock(),true,detail,retry),target(candidate));onLearning(learning.snapshot())
            }
            fun claim():Boolean {while(true){val count=attempts.get();if(count>=3)return false;if(attempts.compareAndSet(count,count+1))return true}}
            fun start() {
                val index=next++;val candidate=candidates[index];val source=candidate.source
                latest=index;started=System.nanoTime();hedgeMs=learning.hedgeDelay(source,Capability.SEARCH,true);advance=false
                running[index]=launch {
                    val result=try {permits.withPermit {
                        current();currentCoroutineContext().ensureActive()
                        if(candidate.api&&prefersHtml(source)||!learning.eligible(source,Capability.SEARCH,true,target(candidate))||!claim())return@withPermit Trial(index)
                        val begin=System.nanoTime()
                        try {
                            val payload=gateway.text(address(candidate),maxBytes=1024*1024,route=NetworkRoute.TOR,allowedOrigins=setOf(source.root),expectedSession=session)
                            val hits=parse(payload.text,payload.finalUrl)
                            if(candidate.api&&!json(payload.text)) {
                                rememberHtml(source)
                                if(hits.isEmpty())return@withPermit Trial(index,failure=SourceFailure(HealthStatus.UNSUPPORTED,"This index does not offer readable API results; trying its public HTML route"))
                            }
                            if(hits.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The selected index supplied no publisher links for this query")
                            current();observe(candidate,HealthStatus.HEALTHY,(System.nanoTime()-begin)/1_000_000,"Public publisher links extracted")
                            Trial(index,hits)
                        }catch(e:CancellationException) {
                            currentCoroutineContext().ensureActive();current()
                            if(e !is TimeoutCancellationException)throw e
                            val error=SourceFailure(HealthStatus.UNAVAILABLE,"The selected search endpoint timed out")
                            observe(candidate,error.status,0,error.message.orEmpty());Trial(index,failure=error)
                        }catch(e:Exception) {
                            currentCoroutineContext().ensureActive();current()
                            if(candidate.api&&(e as? SourceFailure)?.httpStatus in setOf(404,405,410)) {
                                rememberHtml(source);return@withPermit Trial(index,failure=e)
                            }
                            observe(candidate,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,0,e.message.orEmpty().take(180),(e as? SourceFailure)?.retryAfterMs)
                            Trial(index,failure=e)
                        }
                    }}catch(e:CancellationException){currentCoroutineContext().ensureActive();Trial(index,failure=e)}
                    // Policy cancellation while this child remains active is a
                    // completion too; the parent must not wait on a lost event.
                    replies.send(result)
                }
            }
            try {
                while(next<candidates.size&&attempts.get()<3||running.isNotEmpty()) {
                    currentCoroutineContext().ensureActive();current()
                    if(running.isEmpty()){start();continue}
                    val canStart=next<candidates.size&&running.size<3&&attempts.get()<3
                    val remaining=((hedgeMs*1_000_000-(System.nanoTime()-started)+999_999)/1_000_000).coerceAtLeast(0)
                    val result=if(canStart)select<Trial?> {replies.onReceive {it};onTimeout(if(advance)0 else remaining){null}}else replies.receive()
                    if(result==null)start()else {
                        running.remove(result.index)
                        result.failure?.let {if(it is CancellationException)throw it;failure=it}
                        if(!result.hits.isNullOrEmpty()){current();return@coroutineScope result.hits}
                        if(result.index==latest)advance=true
                    }
                }
                current();throw failure ?: SourceFailure(HealthStatus.EMPTY,"No publisher links were available")
            }finally {running.values.forEach {it.cancel()};replies.cancel()}
        }
    }
    companion object {
        private fun json(body:String)=body.trimStart().firstOrNull() in setOf('{','[')
        fun parse(body:String,url:String):List<LocalWebHit> {
            require(body.length<=1024*1024)
            if(json(body))return normalize(api(body),url)
            HtmlAdmission.check(body)
            val page=Jsoup.parse(body,url,if(body.trimStart().startsWith("<?xml")||body.contains("<rss"))Parser.xmlParser()else Parser.htmlParser())
            if(page.select("form[action*=challenge],#challenge-form,.anomaly-modal,input[name=captcha]").isNotEmpty()||
                Regex("verify (?:that )?you are human|unusual traffic|automated queries|bots use duckduckgo|challenge-form",RegexOption.IGNORE_CASE).containsMatchIn(page.text().take(12000)))
                throw SourceFailure(HealthStatus.CHALLENGE,"The selected search source requested verification. Cached results remain available.")
            val raw=mutableListOf<LocalWebHit>()
            page.select("item").take(40).forEach {item->val link=item.selectFirst("link")?.text().orEmpty();if(link.isNotBlank())raw+=LocalWebHit(link,item.selectFirst("title")?.text().orEmpty())}
            page.select("a.result__a,.text-result-wrapper a:has(h2),li.b_algo h2 a,a.result-link").take(40).forEach {a->raw+=LocalWebHit(a.absUrl("href"),a.text())}
            val hits=normalize(raw,url)
            if(hits.isEmpty()&&page.select("input[type=password]").isNotEmpty())throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"The selected search source requires login")
            return hits
        }
        private fun normalize(raw:List<LocalWebHit>,source:String)=raw.mapNotNull {hit->val decoded=unwrap(hit.url) ?: return@mapNotNull null
                val link=LocalDiscoveryIdentity.url(decoded) ?: return@mapNotNull null
                val host=link.toHttpUrlOrNull()?.host.orEmpty()
                if(host=="bing.com"||host.endsWith(".bing.com")||host=="duckduckgo.com"||host.endsWith(".duckduckgo.com"))return@mapNotNull null
                val parsed=link.toHttpUrlOrNull()
                if(parsed?.host==source.toHttpUrlOrNull()?.host&&parsed?.encodedPath in setOf("/search.php","/api.php","/settings.php","/instances.php"))return@mapNotNull null
                LocalWebHit(link,hit.title.take(400))
            }.distinctBy {it.url}.take(24)
        private fun api(body:String):List<LocalWebHit> {
            val rows=mutableListOf<LocalWebHit>();var count=0;var error=false
            fun invalid():Nothing=throw SourceFailure(HealthStatus.UNSUPPORTED,"This public-index response exceeds its supported JSON structure")
            return try {JsonReader(body.reader()).use {reader->
                reader.strictness=Strictness.STRICT;reader.nestingLimit=16
                fun row() {
                    if(++count>256)invalid()
                    if(reader.peek()!=JsonToken.BEGIN_OBJECT){reader.skipValue();return}
                    reader.beginObject();var title:String?=null;var url:String?=null;var fields=0
                    while(reader.hasNext()) {
                        if(++fields>64)invalid()
                        when(val name=reader.nextName()) {
                            "url","title"->if(reader.peek()==JsonToken.STRING){val value=reader.nextString();if(value.length<=8192){if(name=="url")url=value else title=value}}else reader.skipValue()
                            else->reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if(url!=null&&!title.isNullOrBlank()&&rows.size<40) {
                        HtmlAdmission.check(title!!);rows+=LocalWebHit(url!!,Extraction.plain(title!!).take(400))
                    }
                }
                when(reader.peek()) {
                    JsonToken.BEGIN_ARRAY->{reader.beginArray();while(reader.hasNext())row();reader.endArray()}
                    JsonToken.BEGIN_OBJECT->{reader.beginObject();var fields=0
                        while(reader.hasNext()) {
                            if(++fields>272)invalid()
                            val name=reader.nextName()
                            if(name=="error"&&reader.peek()!=JsonToken.NULL){error=true;reader.skipValue()}
                            else if(name.isNotEmpty()&&name.all {it in '0'..'9'})row()else reader.skipValue()
                        };reader.endObject()}
                    else->invalid()
                }
                if(reader.peek()!=JsonToken.END_DOCUMENT)invalid()
                if(error)throw SourceFailure(HealthStatus.UNAVAILABLE,"The public index reported an upstream search failure")
                rows
            }}catch(e:SourceFailure){throw e}catch(_:Exception){invalid()}
        }
        private fun unwrap(value:String):String? {
            val parsed=value.toHttpUrlOrNull() ?: return null
            parsed.queryParameter("uddg")?.takeIf {parsed.host.endsWith("duckduckgo.com")}?.let {return it}
            if(parsed.host.endsWith("bing.com")&&parsed.encodedPath.startsWith("/ck/")) {
                val token=parsed.queryParameter("u") ?: return null
                return if(token.startsWith("a1"))runCatching {String(Base64.getUrlDecoder().decode(token.drop(2)))}.getOrNull()else token
            }
            return value
        }
    }
}
