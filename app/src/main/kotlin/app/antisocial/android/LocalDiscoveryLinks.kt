/* anti social, GPL-3.0-or-later. Bounded, cached public announcement checks. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal enum class LocalLinkState { UNCHECKED, CHECKING, AVAILABLE, UNAVAILABLE }
internal data class LocalLinkCheck(val url:String,val state:LocalLinkState,val destination:String=url,val title:String="",val checkedAt:Long=0,val recovered:Boolean=false,val archivedAt:Long?=null)

/** Source acquisition remains outside Compose. Opening a sheet checks at most
 * six unique links, with two workers and a shared deadline. Saved evidence and
 * source URLs are never deleted when a check fails. */
internal class LocalDiscoveryLinks(private val repo:Repository) {
    val state=MutableStateFlow<Map<String,LocalLinkCheck>>(emptyMap())
    private val permits=Semaphore(2)
    private val pending=ConcurrentHashMap<String,CompletableDeferred<LocalLinkCheck>>()
    @Volatile private var generation=0L
    internal var probe:suspend (String,LocalEvent)->LocalLinkCheck={url,event->
        val target=repo.routes.recognize(url)?.takeIf {it.platform!=Platform.RSS}
        if(target!=null) {
            val page=repo.pool.load(target,originalFallback=repo.prefs.originalFallback,background=true)
            if(!LocalAccountAvailability.usable(target,page))throw SourceFailure(HealthStatus.EMPTY,"This public page is unavailable")
            LocalLinkCheck(url,LocalLinkState.AVAILABLE,target.canonicalUrl,checkedAt=System.currentTimeMillis())
        }else {
          // Keep Darwin's existing alternate/original order, learned route
          // health and public-copy recovery. Only extraction is event-aware.
          when(val result=repo.evidenceReader.articleConditional(url,url,ContextArticleValidators(),announcement=event)) {
            is ContextArticleFetch.Ready->{
                if(!LocalAnnouncementReading.acceptable(result.document,event))throw SourceFailure(HealthStatus.EMPTY,"The announcement is no longer present")
                val document=result.document
                LocalLinkCheck(url,LocalLinkState.AVAILABLE,document.finalUrl,document.siteName.orEmpty(),System.currentTimeMillis(),
                    recovered=ArchiveUrls.original(document.finalUrl)!=null||document.access?.archivedAt!=null,archivedAt=document.access?.archivedAt)
            }
            // The public route reached this file. Its actual PDF pages are
            // validated by the existing sandboxed reader when deliberately opened.
            is ContextArticleFetch.PublicPdf->LocalLinkCheck(url,LocalLinkState.AVAILABLE,result.url,"Published document",System.currentTimeMillis())
            is ContextArticleFetch.NotModified->LocalLinkCheck(url,LocalLinkState.UNCHECKED)
          }
        }
    }
    private fun key(url:String,event:LocalEvent)="local-link:v1:${repo.prefs.tor}:${repo.prefs.originalFallback}:${repo.prefs.archiveRecovery}:"+LocalDiscoveryIdentity.hash(url+"|"+LocalAnnouncementReading.key(event))
    private fun fresh(value:LocalLinkCheck):Boolean=
        when(value.state){LocalLinkState.AVAILABLE->System.currentTimeMillis()-value.checkedAt in 0..30L*60000;LocalLinkState.UNAVAILABLE->System.currentTimeMillis()-value.checkedAt in 0..5L*60000;else->true}
    fun check(url:String,event:LocalEvent,values:Map<String,LocalLinkCheck> = state.value):LocalLinkCheck?=values[key(url,event)]?.takeIf(::fresh)
    @Synchronized fun cleanup(clear:Boolean) {
        if(clear){generation++;state.update {old->old.filterValues {it.state==LocalLinkState.CHECKING}}}
        else state.update {old->old.filterValues(::fresh)}
    }
    private fun show(key:String,value:LocalLinkCheck) {state.update {old->LinkedHashMap(old).apply {remove(key);put(key,value);while(size>128)remove(keys.first())}}}
    private fun cached(key:String)=repo.localDiscovery.cache(key)?.let {runCatching {repo.gson.fromJson(it,LocalLinkCheck::class.java).takeIf(::fresh)}.getOrNull()}
    internal fun rememberReadable(url:String,title:String,event:LocalEvent) {
        val canonical=LocalDiscoveryPresentation.url(url,repo.cleaner::clean) ?: return
        val value=LocalLinkCheck(canonical,LocalLinkState.AVAILABLE,canonical,title.take(120),System.currentTimeMillis());val key=key(canonical,event)
        repo.localDiscovery.cache(key,repo.gson.toJson(value),30*60000);show(key,value)
    }
    suspend fun inspect(event:LocalEvent,force:Boolean=false,includeSources:Boolean=false)=withContext(Dispatchers.IO) {
        val all=LocalDiscoveryPresentation.eventLinks(if(includeSources)event.copy(urls=(event.urls+event.evidence.map {it.sourceUrl}).distinct())else event,repo.cleaner::clean)
        val links=all.filter {link->
            val key=key(link.url,event);val existing=cached(key)
            if(existing!=null&&(!force||existing.state==LocalLinkState.AVAILABLE)){show(key,existing);false}else true
        }.take(6)
        val mode=repo.gateway.currentPolicy()
        val bound=generation
        repo.cardWork.track(listOf(eventWorkKey(event.id)),"Announcement links") {
            withTimeoutOrNull(20_000) {coroutineScope {links.map {link->launch {
                val key=key(link.url,event)
                val existing=if(force)null else cached(key)
                if(existing!=null){show(key,existing);return@launch}
                if(!AutomaticLinks.mayRead(link.url)) {show(key,LocalLinkCheck(link.url,LocalLinkState.UNCHECKED));return@launch}
                val deferred=CompletableDeferred<LocalLinkCheck>();val owner=pending.putIfAbsent(key,deferred)
                if(owner!=null){show(key,owner.await());return@launch}
                try {
                    show(key,LocalLinkCheck(link.url,LocalLinkState.CHECKING))
                    val value=permits.withPermit {
                        try {withTimeout(10_000){repo.gateway.boundedHealthRead(maxBytes=1024L*1024,maxRequests=4){probe(link.url,event)}}}
                        catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();LocalLinkCheck(link.url,LocalLinkState.UNCHECKED)}
                        catch(e:CancellationException){throw e}
                        catch(e:Exception){
                            // Transport failure, a rate limit or an exhausted allowance
                            // says nothing about whether the announcement still exists.
                            val failures=generateSequence<Throwable>(e){it.cause}.take(8).filterIsInstance<SourceFailure>().toList()
                            val absent=failures.any {it.status in setOf(HealthStatus.EMPTY,HealthStatus.LOGIN_REQUIRED)||it.httpStatus in setOf(404,410)}
                            LocalLinkCheck(link.url,if(absent)LocalLinkState.UNAVAILABLE else LocalLinkState.UNCHECKED,checkedAt=System.currentTimeMillis())
                        }
                    }
                    ensureActive()
                    if(repo.gateway.currentPolicy()!=mode||generation!=bound)throw CancellationException("Announcement read was superseded")
                    val safe=value.copy(url=link.url,destination=LocalDiscoveryPresentation.url(value.destination,repo.cleaner::clean) ?: link.url,title=value.title.take(120))
                    repo.localDiscovery.cache(key,repo.gson.toJson(safe),when(safe.state){LocalLinkState.AVAILABLE->30*60000;LocalLinkState.UNAVAILABLE->5*60000;else->60000})
                    show(key,safe);deferred.complete(safe)
                }finally {
                    pending.remove(key,deferred)
                    if(!deferred.isCompleted){val retry=LocalLinkCheck(link.url,LocalLinkState.UNCHECKED);show(key,retry);deferred.complete(retry)}
                }
            }}.joinAll()}}
        }
    }
}
