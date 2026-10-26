/* anti social, GPL-3.0-or-later. Bounded regional batches through NetworkGateway. */
package app.antisocial.core

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

/** Query allowance is distinct from an endpoint outage. No provider text escapes. */
internal class LocalPlaceQueryAllowance:Exception()

/** A single repository owns this reader. Geographic queries remain Tor-only;
 * independent operators are tried serially, never hedged or status-probed. */
class LocalPlaceDirectory(
    private val gateway:NetworkGateway,
    private val cached:(String)->String?,
    private val cache:(String,String,Long)->Unit,
    private val clock:()->Long=System::currentTimeMillis,
    private val endpoints:List<String> = ENDPOINTS,
    private val timeoutMs:Long=24_000
) {
    private data class State(val at:Long=0,val successAt:Long=0,val latency:Double=2500.0,val failures:Int=0,val until:Long=0,val explicitUntil:Long=0)
    private val gson=Gson()
    private val admission=Mutex()
    init {require(endpoints.size in 1..2&&endpoints.distinct().size==endpoints.size);require(timeoutMs in 1..24_000)}
    private fun key(endpoint:String)="directory-route:v1:"+LocalDiscoveryIdentity.hash(endpoint)
    private fun queryKey(endpoint:String,query:String)="directory-query:v1:"+LocalDiscoveryIdentity.hash(endpoint+"\n"+query)
    private fun state(endpoint:String):State {
        val now=clock()
        return cached(key(endpoint))?.let {raw->runCatching {gson.fromJson(raw,State::class.java)}.getOrNull()}
            ?.takeIf {it.at in (now-7*DAY)..(now+60_000)&&it.latency.isFinite()&&it.latency in 0.0..180_000.0&&it.failures in 0..100&&it.until in 0..(now+DAY)&&it.explicitUntil in 0..(now+DAY)} ?: State()
    }
    private fun wait(endpoint:String,query:String):Long {
        val now=clock();val s=state(endpoint)
        val target=cached(queryKey(endpoint,query))?.toLongOrNull()?.takeIf {it in now..(now+DAY)} ?: 0
        return (maxOf(s.until,s.explicitUntil,target)-now).coerceAtLeast(0)
    }
    private fun legacy(endpoint:String)=cached("directory-rest:"+endpoint)!=null
    private fun paused(query:String):SourceFailure? {
        val waits=endpoints.map {if(legacy(it))null else wait(it,query)}
        if(waits.any {it==0L})return null
        return SourceFailure(HealthStatus.UNAVAILABLE,"Place directories are resting; cached discoveries remain available",waits.filterNotNull().minOrNull())
    }
    private fun record(endpoint:String,error:SourceFailure?,latencyMs:Long=0) {
        val now=clock();val old=state(endpoint)
        val next=if(error==null)old.copy(at=now,successAt=now,latency=if(old.successAt==0L)latencyMs.toDouble() else old.latency*.7+latencyMs*.3,failures=0,until=old.explicitUntil.takeIf {it>now} ?: 0)
        else {
            val failures=(old.failures+1).coerceAtMost(100)
            val base=if(error.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED))60_000L else 15_000L
            val explicit=maxOf(old.explicitUntil,now+(error.retryAfterMs ?: 0).coerceIn(0,DAY))
            old.copy(at=now,failures=failures,until=maxOf(old.until,explicit,now+base*(1L shl (failures-1).coerceAtMost(3))),explicitUntil=explicit)
        }
        cache(key(endpoint),gson.toJson(next),7*DAY)
    }
    private fun status(document:GatewayDocument):SourceFailure? {
        if(document.status in 200..299)return null
        val retry=document.headers.entries.filter {it.key.equals("Retry-After",true)}.flatMap {it.value}.take(16)
            .mapNotNull {HttpRetryAfter.parse(it,clock())}.maxOrNull()
        val health=when(document.status){429->HealthStatus.RATE_LIMITED;401->HealthStatus.LOGIN_REQUIRED;403->HealthStatus.CHALLENGE;else->HealthStatus.UNAVAILABLE}
        return SourceFailure(health,when(document.status){429->"The place directory requested a pause";504->"The place directory has no capacity for this query";else->"The place directory is temporarily unavailable"},retry,document.status)
    }
    suspend fun directory(query:LocalDiscoveryQuery,ready:suspend ()->Unit):LocalExtraction {
        val selection=gateway.currentSelection()
        val code=LocalPlaceSources.overpassUrl(query.area,endpoints.first(),hubs=query.mode==LocalSearchMode.EVENTS,topic=query.text).toHttpUrl().queryParameter("data")!!
        paused(code)?.let {throw it} // Known pauses do not start/wait for transport.
        return admission.withLock {
            currentCoroutineContext().ensureActive()
            if(gateway.currentSelection()!==selection)throw CancellationException("Search connection changed")
            paused(code)?.let {throw it}
            ready()
            val session=gateway.currentSession(NetworkRoute.TOR)
            fun current() {
                if(gateway.currentSelection()!==selection||gateway.currentSession(NetworkRoute.TOR)!==session)throw CancellationException("Search connection changed")
                if(!session.policy.tor||!session.policy.ready)throw SourceFailure(HealthStatus.UNAVAILABLE,"Waiting for a private search connection. No direct fallback was used.")
            }
            current()
            val ordered=endpoints.sortedBy {endpoint->val s=state(endpoint);if(s.successAt==0L)2500.0 else s.latency+if(clock()-s.successAt>DAY)1200 else 0}
            var last:Exception?=null
            for(endpoint in ordered) {
                currentCoroutineContext().ensureActive();current()
                if(legacy(endpoint)||wait(endpoint,code)>0)continue
                val begin=System.nanoTime()
                try {
                    val document=withTimeout(timeoutMs) {
                        gateway.extractorDocument(endpoint,method="POST",data=("data="+URLEncoder.encode(code,"UTF-8")).toByteArray(Charsets.UTF_8),
                            headers=mapOf("Accept" to "application/json","Content-Type" to "application/x-www-form-urlencoded","User-Agent" to "anti-social (+https://github.com/MimirLab/anti-social; bounded regional discovery)"),
                            sameOrigin=true,maxBytes=2L*1024*1024,route=NetworkRoute.TOR,expectedSession=session)
                    }
                    val elapsed=(System.nanoTime()-begin)/1_000_000
                    currentCoroutineContext().ensureActive();current()
                    status(document)?.let {throw it}
                    val data=LocalPlaceSources.overpass(document.text,document.finalUrl,clock())
                    currentCoroutineContext().ensureActive();current()
                    if(data.businesses.isNotEmpty())record(endpoint,null,elapsed)
                    return@withLock data
                }catch(e:CancellationException) {
                    currentCoroutineContext().ensureActive();current()
                    if(e !is TimeoutCancellationException)throw e
                    val error=SourceFailure(HealthStatus.UNAVAILABLE,"The place directory read timed out")
                    record(endpoint,error);last=error
                }catch(e:Exception) {
                    currentCoroutineContext().ensureActive();current()
                    val error=e as? SourceFailure ?: SourceFailure(HealthStatus.UNAVAILABLE,"The place directory returned an unreadable response")
                    if(error.cause is LocalPlaceQueryAllowance||error.httpStatus in setOf(400,414,422)) {
                        cache(queryKey(endpoint,code),(clock()+300_000).toString(),300_000)
                        if(error.retryAfterMs!=null)record(endpoint,error)
                    }else record(endpoint,error)
                    last=error
                    if(error.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.LOGIN_REQUIRED,HealthStatus.CHALLENGE))throw error
                }
            }
            throw last ?: paused(code) ?: SourceFailure(HealthStatus.UNAVAILABLE,"Place directories are temporarily unavailable")
        }
    }
    companion object {
        val ENDPOINTS=listOf("https://overpass.private.coffee/api/interpreter","https://overpass-api.de/api/interpreter")
        private const val DAY=86_400_000L
    }
}
