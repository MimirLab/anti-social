/* anti social, GPL-3.0-or-later. Official non-script search representations. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class DuckSearchForm(val url:String,val fields:Map<String,String>)
internal data class DuckSearchLink(val url:String,val title:String,val snippet:String)
internal data class DuckSearchPage(val links:List<DuckSearchLink>,val next:String?)
internal enum class DuckSearchFormat { HTML, FORM, LITE }
/** Admission changed while queued; this is not a newly observed server failure. */
internal class IndexAdmissionPause(val failure:SourceFailure):Exception(failure.message,failure)

/** A continuation retains its representation. The selected provider's canonical
 * source stays unchanged; finalUrl records the actual representation origin. */
internal object DuckDuckGoProtocol {
    const val HTML_ROOT="https://html.duckduckgo.com"
    const val LITE_ROOT="https://lite.duckduckgo.com"
    private const val HTML_CURSOR="search-form:"
    private const val LITE_CURSOR="search-lite-form:"
    private val fields=setOf("q","s","nextParams","v","o","dc","api","vqd","kl","df")
    fun liteRoot(source:Source):String?=when {
        source.root.trimEnd('/')==HTML_ROOT->LITE_ROOT
        // Owned HTTP fixtures still pass Gateway's explicit test-only admission.
        source.root.startsWith("http://")->source.root.trimEnd('/')
        else->null
    }
    fun address(source:Source,query:String,format:DuckSearchFormat):String=when(format) {
        DuckSearchFormat.HTML->source.root.trimEnd('/')+"/html/?q="+DarwinRoutes.encode(query)
        DuckSearchFormat.FORM->source.root.trimEnd('/')+"/html/"
        DuckSearchFormat.LITE->requireNotNull(liteRoot(source))+"/lite/?q="+DarwinRoutes.encode(query)
    }
    fun continuation(source:Source,query:String,cursor:String):DuckSearchForm {
        require(cursor.length<=24*1024){"Invalid search continuation"}
        val lite=cursor.startsWith(LITE_CURSOR)
        val prefix=if(lite)LITE_CURSOR else HTML_CURSOR
        require(cursor.startsWith(prefix)){"Invalid search continuation"}
        val value=runCatching {JsonParser.parseString(cursor.removePrefix(prefix)).asJsonObject}.getOrNull()
        require(value!=null&&value.keySet()==setOf("url","fields")&&value["url"].isJsonPrimitive&&value["url"].asJsonPrimitive.isString&&value["fields"].isJsonObject){"Invalid search continuation"}
        require(value["fields"].asJsonObject.entrySet().all {it.value.isJsonPrimitive&&it.value.asJsonPrimitive.isString}){"Invalid search continuation fields"}
        val form=runCatching {Gson().fromJson(value,DuckSearchForm::class.java)}.getOrNull()
        require(form!=null&&valid(form,if(lite)liteRoot(source)else source.root,if(lite)"/lite/"else "/html/",query)){"Search continuation changed its provider, representation or query"}
        return form
    }
    private fun valid(form:DuckSearchForm,root:String?,path:String,query:String):Boolean {
        val url=form.url.toHttpUrlOrNull() ?: return false
        val base=root?.toHttpUrlOrNull() ?: return false
        return url.isHttps&&url.host==base.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()&&url.encodedPath==path&&url.query==null&&url.fragment==null&&
            form.fields.size<=16&&form.fields.keys.all {it in fields}&&form.fields["q"]==query&&form.fields["s"]?.toIntOrNull() in 1..1000&&form.fields.entries.sumOf {it.key.length+it.value.length}<16*1024
    }
    fun page(document:Document,query:String):DuckSearchPage {
        if(document.selectFirst("form#challenge-form,form#img-form,.anomaly-modal,input[name=captcha]")!=null)
            throw SourceFailure(HealthStatus.CHALLENGE,"DuckDuckGo requires a human challenge on this connection. Try another provider in Settings.")
        val raw=buildList {
            document.select(".result:not(.result--ad)").take(256).forEach {node->
                val a=node.selectFirst("a.result__a[href]") ?: return@forEach
                add(DuckSearchLink(a.absUrl("href"),a.text(),node.selectFirst(".result__snippet")?.text().orEmpty()))
            }
            document.select("a.result-link[href]").take(256).forEach {a->
                if(a.parents().any {it.hasClass("result--ad")||it.hasClass("result-sponsored")})return@forEach
                val u=a.absUrl("href").toHttpUrlOrNull()
                if(u?.queryParameterNames?.any {it in setOf("ad_domain","ad_provider","ad_type")}==true)return@forEach
                add(DuckSearchLink(a.absUrl("href"),a.text(),a.closest("tr")?.nextElementSibling()?.selectFirst(".result-snippet")?.text().orEmpty()))
            }
        }.mapNotNull {row->val u=row.url.toHttpUrlOrNull() ?: return@mapNotNull null
            if(u.host=="duckduckgo.com"||u.host.endsWith(".duckduckgo.com"))u.queryParameter("uddg")?.let {row.copy(url=it)}else row
        }.distinctBy {it.url}.take(256)
        if(raw.isEmpty()) {
            val text=document.text()
            if(text.contains("bots use DuckDuckGo",true))throw SourceFailure(HealthStatus.CHALLENGE,"DuckDuckGo requires a human challenge on this connection. Try another provider in Settings.")
            if(text.contains("The server encountered an error",true))throw SourceFailure(HealthStatus.UNAVAILABLE,"DuckDuckGo returned a server error. Your search was not sent to another provider.")
        }
        val base=document.location().toHttpUrlOrNull()
        val lite=base?.encodedPath=="/lite/"
        val form=document.select("form").firstOrNull {node->
            node.select("input[type=submit],button").any {a->val label=if(a.normalName()=="input")a.attr("value")else a.text()
                label.trim().equals("Next",true)||label.trim().equals("Next Page >",true)}
        }
        val next=form?.let {node->
            val controls=node.select("input[type=hidden][name]").filter {it.attr("name") in fields}
            if(controls.groupBy {it.attr("name")}.any {it.value.size!=1})return@let null
            // Resolve against the acquired URL, never an untrusted <base> tag.
            val destination=base?.resolve(node.attr("action")) ?: return@let null
            val saved=DuckSearchForm(destination.toString(),controls.associate {it.attr("name") to it.attr("value")})
            if(valid(saved,base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString(),if(lite)"/lite/"else "/html/",query))
                (if(lite)LITE_CURSOR else HTML_CURSOR)+Gson().toJson(saved)else null
        }
        return DuckSearchPage(raw,next)
    }
}

/** Shared by social and local publisher search. Ordinary successes need one
 * read; a slow first read can coexist with a Lite read after a conservative
 * grace period. Reserve its slot so held ordinary queries cannot starve it. */
internal class DuckDuckGoSearch(private val gateway:NetworkGateway,private val clock:()->Long=System::currentTimeMillis) {
    private val ordinary=Semaphore(2)
    private val alternate=Semaphore(1)
    private data class Preference(val session:NetworkSession,val format:DuckSearchFormat,val until:Long)
    private val preferences=linkedMapOf<String,Preference>()
    @Synchronized private fun preferred(source:Source,session:NetworkSession)=preferences[source.root]?.takeIf {it.session===session&&it.until>clock()}?.format ?: DuckSearchFormat.HTML
    @Synchronized private fun remember(source:Source,session:NetworkSession,format:DuckSearchFormat) {
        preferences.remove(source.root);preferences[source.root]=Preference(session,format,clock()+6*3_600_000L)
        while(preferences.size>16)preferences.remove(preferences.keys.first())
    }
    private data class Reply<T>(val index:Int,val format:DuckSearchFormat,val result:Result<T>)
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> read(source:Source,query:String,sourceUrl:String,session:NetworkSession,maxBytes:Long=4L*1024*1024,
        localPermits:Semaphore?=null,check:()->Unit={},admit:()->Unit={},productive:(T)->Boolean,parse:(Payload)->T):T=gateway.indexRead {coroutineScope {
        require(source.frontend=="duckduckgo")
        if(session.policy.tor&&(!session.policy.ready||session.policy.socksPort !in 1..65535))
            throw SourceFailure(HealthStatus.UNAVAILABLE,"Waiting for a private search connection. No direct fallback was used.")
        val selection=gateway.currentSelection();val originals=gateway.originalFallback
        val feedback=currentCoroutineContext()[PublicIndexFeedback]?.takeIf {it.source==source}
        fun policy() {
            if(gateway.currentSelection()!==selection||gateway.sessionFor(source.root)!==session||gateway.originalFallback!=originals)
                throw CancellationException("The selected search connection changed")
            check()
        }
        fun validate() {policy();admit();feedback?.refusal?.let {throw it}}
        val kinds=listOf(DuckSearchFormat.HTML,DuckSearchFormat.LITE,DuckSearchFormat.FORM).filter {it!=DuckSearchFormat.LITE||DuckDuckGoProtocol.liteRoot(source)!=null}.toMutableList()
        val first=preferred(source,session);if(kinds.remove(first))kinds.add(0,first)
        val replies=Channel<Reply<T>>(3);val jobs=linkedMapOf<Int,Job>()
        val finished=java.util.concurrent.ConcurrentHashMap<Int,Reply<T>>()
        var next=0;var began=0L;var latest=-1;var advance=true;var last:Throwable?=null
        fun start() {
            val format=kinds.removeAt(0);val index=next++;latest=index;began=System.nanoTime();advance=false
            jobs[index]=launch {
                val result=try {
                    val permit=if(format==DuckSearchFormat.LITE)alternate else ordinary
                    permit.withPermit {
                        suspend fun perform():T {
                            currentCoroutineContext().ensureActive();validate()
                            val url=DuckDuckGoProtocol.address(source,query,format)
                            val root=if(format==DuckSearchFormat.LITE)requireNotNull(DuckDuckGoProtocol.liteRoot(source))else source.root
                            val route=if(gateway.currentSession(NetworkRoute.TOR)===session)NetworkRoute.TOR else NetworkRoute.DEFAULT
                            val payload=if(format==DuckSearchFormat.FORM)gateway.formInSession(url,mapOf("q" to query),sourceUrl,expectedSession=session,maxBytes=maxBytes,route=route,allowedOrigins=setOf(root))
                                else gateway.text(url,sourceUrl,maxBytes=maxBytes,route=route,allowedOrigins=setOf(root),expectedSession=session)
                            // A later admission pause prevents new calls, not
                            // interpretation of a response already requested.
                            currentCoroutineContext().ensureActive();policy();return parse(payload)
                        }
                        Result.success(if(localPermits==null)perform()else localPermits.withPermit {perform()})
                    }
                }catch(e:Exception) {
                    currentCoroutineContext().ensureActive()
                    val error=try {policy();e}catch(changed:Exception){changed}
                    // Record a completed refusal immediately, even if a sibling
                    // answer is being acknowledged. Queued work sees the pause.
                    if(error !is CancellationException)feedback?.observe(listOf(error))
                    Result.failure(error)
                }
                val reply=Reply(index,format,result);finished[index]=reply;replies.send(reply)
            }
        }
        suspend fun accepted(reply:Reply<T>):T {
            finished.values.forEach {feedback?.observe(listOfNotNull(it.result.exceptionOrNull()))}
            val value=reply.result.getOrThrow();policy()
            val useful=productive(value);currentCoroutineContext().ensureActive();policy()
            if(useful)remember(source,session,reply.format)
            return value
        }
        try {
            while(kinds.isNotEmpty()||jobs.isNotEmpty()) {
                currentCoroutineContext().ensureActive();policy()
                feedback?.refusal?.let {error->finished.values.firstOrNull {it.result.isSuccess}?.let {return@coroutineScope accepted(it)};throw error}
                if(jobs.isEmpty()){start();continue}
                val delayMs=((3_500_000_000L-(System.nanoTime()-began)+999_999)/1_000_000).coerceAtLeast(0)
                val reply=if(kinds.isNotEmpty())select<Reply<T>?> {replies.onReceive {it};onTimeout(if(advance)0 else delayMs){null}}else replies.receive()
                if(reply==null){start();continue}
                jobs.remove(reply.index)
                if(reply.result.isSuccess)return@coroutineScope accepted(reply)
                val error=reply.result.exceptionOrNull()!!
                if(error is CancellationException)throw error
                if(error is IndexAdmissionPause)throw error
                last=error
                if(PublicIndexFeedback.isRefusal(error)) {
                    feedback?.observe(listOf(error))
                    finished.values.firstOrNull {it.result.isSuccess}?.let {return@coroutineScope accepted(it)}
                    throw (feedback?.refusal ?: error)
                }
                if(reply.format==DuckSearchFormat.HTML&&(error as? SourceFailure)?.message.orEmpty().startsWith("DuckDuckGo returned a server error")) {
                    val at=kinds.indexOf(DuckSearchFormat.FORM);if(at>0)kinds.add(0,kinds.removeAt(at))
                }
                if(reply.index==latest)advance=true
            }
            throw last ?: SourceFailure(HealthStatus.EMPTY,"The selected search provider supplied no readable destinations")
        }finally {jobs.values.forEach {it.cancel()};replies.cancel()}
    }}
}
