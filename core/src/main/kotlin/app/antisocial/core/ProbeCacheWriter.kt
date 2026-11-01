/* anti social, GPL-3.0-or-later. Advisory persistence never owns delivery. */
package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One bounded writer for discardable probe metadata. SourcePool has already
 * updated current health and retry admission before offering this observation.
 * Overflow discards the oldest pending key; neither a cache failure nor this
 * queue can become evidence that a publisher failed. No network work is owned
 * here. The caller's durable store must reject an older observation too. */
class ProbeCacheWriter(scope:CoroutineScope,private val maximum:Int=128,
    private val write:suspend (ProbeResult)->Unit) {
    private val gate=Any()
    private val pending=linkedMapOf<String,ProbeResult>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private var active:ProbeResult?=null
    private var closed=false
    private val worker:Job
    private fun key(result:ProbeResult)="${result.source.key}|${result.capability}|${result.tor}"
    init {
        require(maximum in 1..4096)
        worker=scope.launch(Dispatchers.IO) {
            try {
                for(signal in wake)while(true) {
                    currentCoroutineContext().ensureActive()
                    val next=synchronized(gate) {
                        pending.entries.firstOrNull()?.let {entry->pending.remove(entry.key);entry.value.also {active=it}}
                    } ?: break
                    try {write(next)}catch(e:CancellationException){throw e}catch(_:Exception){/* Ordinary cache: try again on a later observation. */}
                    finally {synchronized(gate){active=null}}
                }
            }finally {synchronized(gate){closed=true;active=null;pending.clear();wake.close()}}
        }
        worker.invokeOnCompletion {synchronized(gate){closed=true;active=null;pending.clear();wake.close()}}
    }
    /** Small immutable source metadata only; no serialization, disk or waiting. */
    fun offer(result:ProbeResult):Boolean=synchronized(gate) {
        if(closed||!worker.isActive||result.source.root.length>8192||result.source.frontend.length>128||result.detail.length>1024)return false
        val name=key(result)
        val prior=pending[name] ?: active?.takeIf {key(it)==name}
        if(prior!=null&&prior.checkedAt>result.checkedAt)return false
        pending.remove(name);pending[name]=result
        while(pending.size>maximum)pending.remove(pending.keys.first())
        wake.trySend(Unit).isSuccess
    }
}
