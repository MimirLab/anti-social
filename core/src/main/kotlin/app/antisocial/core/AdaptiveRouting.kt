package app.antisocial.core

import java.security.MessageDigest

/** Local observations contain routing metadata only, never post/search text. */
data class RouteStats(val source: Source,val capability: Capability,val tor: Boolean,val samples: Int=0,val successes: Int=0,
    val deliveryMs: Double=1500.0,val deviationMs: Double=500.0,val lastObservedAt: Long=0,val lastSuccessAt: Long=0,
    val cooldownUntil: Long=0,val consecutiveFailures: Int=0,val deliverySamples: Int=0) {
    val successRate get()=(successes+2.0)/(samples+3.0)
}

/** Bounded, clock-injectable policy; privacy/source tiers are applied by SourcePool
 * before this policy ranks their eligible data pathways. */
class AdaptiveRouting(private val clock: ()->Long=System::currentTimeMillis) {
    private val stats=linkedMapOf<String,RouteStats>()
    private data class TargetFailure(val until: Long,val status: HealthStatus,val detail: String)
    private val targetFailures=linkedMapOf<String,TargetFailure>()
    private fun key(s: Source,c: Capability,t: Boolean)="${s.key}|$c|$t"
    private fun scope(s: Source,c: Capability,t: Boolean,target: Target): String {
        val value=if(target.kind==TargetKind.SEARCH)target.canonicalUrl else target.canonicalUrl.substringBefore('#')
        val hash=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
        return key(s,c,t)+"|"+hash
    }
    @Synchronized fun restore(values: List<RouteStats>) {
        val now=clock()
        values.takeLast(512).filter {it.lastObservedAt in (now-7*DAY)..(now+60_000)&&it.samples in 1..1_000_000&&it.successes in 0..it.samples&&it.deliverySamples in 0..it.successes&&it.deliveryMs.isFinite()&&it.deviationMs.isFinite()&&it.deliveryMs in 0.0..180_000.0&&it.deviationMs in 0.0..180_000.0}.forEach {stats[key(it.source,it.capability,it.tor)]=it.copy(cooldownUntil=it.cooldownUntil.coerceIn(0,now+DAY),consecutiveFailures=it.consecutiveFailures.coerceIn(0,100))}
        trim()
    }
    @Synchronized fun snapshot(): List<RouteStats> {trim();return stats.values.toList()}
    @Synchronized fun get(s: Source,c: Capability,t: Boolean)=stats[key(s,c,t)]
    @Synchronized fun eligible(s: Source,c: Capability,t: Boolean,target: Target?=null): Boolean {
        return eligibleAt(s,c,t,target)<=clock()
    }
    /** Local earliest retry only; no probe is necessary to discover a cooldown. */
    @Synchronized fun eligibleAt(s: Source,c: Capability,t: Boolean,target: Target?=null): Long {
        return maxOf(clock(),stats[key(s,c,t)]?.cooldownUntil ?: 0,
            if(target==null)0 else targetFailures[scope(s,c,t,target)]?.until ?: 0)
    }
    @Synchronized fun failure(s: Source,c: Capability,t: Boolean,target: Target): SourceFailure? {
        val value=targetFailures[scope(s,c,t,target)]?.takeIf {it.until>clock()} ?: return null
        return SourceFailure(value.status,value.detail)
    }
    @Synchronized fun score(s: Source,c: Capability,t: Boolean): Double {
        val v=stats[key(s,c,t)] ?: return 2500.0
        val stale=if(clock()-v.lastSuccessAt>DAY)1200.0 else 0.0
        return ((v.deliveryMs+v.deviationMs*.5)/v.successRate.coerceAtLeast(.15))+stale
    }
    @Synchronized fun hedgeDelay(s: Source,c: Capability,t: Boolean): Long {
        val v=stats[key(s,c,t)]?.takeIf {it.deliverySamples>0}
        return ((v?.deliveryMs ?: if(t)1400.0 else 500.0)+(v?.deviationMs ?: 100.0)).toLong().coerceIn(if(t)700L else 250L,if(t)3500L else 1500L)
    }
    @Synchronized fun observe(result: ProbeResult,target: Target?=null,delivery: Boolean=true): RouteStats {
        val now=clock();val k=key(result.source,result.capability,result.tor)
        val old=stats[k] ?: RouteStats(result.source,result.capability,result.tor)
        val success=result.status==HealthStatus.HEALTHY
        val sizeFailure=result.detail.contains("health-check budget",true)
        if(sizeFailure)return old // A bounded sounding is inconclusive, never a source outage.
        val global=isGlobalFailure(result)
        val targetOnly=target!=null&&isTargetSpecific(result)
        if(targetOnly) {
            val sk=scope(result.source,result.capability,result.tor,target!!)
            val ttl=if(result.status==HealthStatus.EMPTY)20_000L else 60_000L
            targetFailures.remove(sk);targetFailures[sk]=TargetFailure(now+ttl,result.status,result.detail)
            trim()
            // An empty/private/deleted account says nothing about another account
            // on the same endpoint; preserve its successful routing evidence.
            return old
        }
        val failures=if(success)0 else old.consecutiveFailures+1
        val serverDelay=result.retryAfterMs?.coerceIn(0,DAY) ?: 0L
        val cooldown=if(global)maxOf(old.cooldownUntil,now+(if(result.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE))60_000L else 15_000L)*(1L shl (failures-1).coerceIn(0,3)),now+serverDelay) else if(success)0 else old.cooldownUntil
        val sample=result.latencyMs.coerceIn(1,180_000).toDouble()
        val delivered=success&&delivery
        val deliveryMs=if(delivered){if(old.deliverySamples==0)sample else old.deliveryMs*.75+sample*.25}else old.deliveryMs
        val deviation=if(delivered){if(old.deliverySamples==0)sample*.25 else old.deviationMs*.75+kotlin.math.abs(sample-old.deliveryMs)*.25}else old.deviationMs
        val next=old.copy(samples=old.samples.coerceAtMost(999_999)+1,successes=old.successes.coerceAtMost(999_999)+(if(success)1 else 0),deliveryMs=deliveryMs,deviationMs=deviation,lastObservedAt=now,lastSuccessAt=if(success)now else old.lastSuccessAt,cooldownUntil=cooldown,consecutiveFailures=failures.coerceAtMost(100),deliverySamples=old.deliverySamples.coerceAtMost(999_999)+(if(delivered)1 else 0))
        stats.remove(k);stats[k]=next
        if(target!=null) {
            val sk=scope(result.source,result.capability,result.tor,target)
            if(success)targetFailures.remove(sk)
            else if(!global&&result.status!=HealthStatus.UNKNOWN) {
                val ttl=if(result.status==HealthStatus.EMPTY)20_000L else 60_000L
                targetFailures.remove(sk);targetFailures[sk]=TargetFailure(now+ttl,result.status,result.detail)
            }
        }
        trim();return next
    }
    @Synchronized private fun trim() {
        val now=clock();stats.entries.removeAll {now-it.value.lastObservedAt>7*DAY};while(stats.size>512)stats.remove(stats.keys.first())
        targetFailures.entries.removeAll {it.value.until<=now};while(targetFailures.size>1024)targetFailures.remove(targetFailures.keys.first())
    }
    companion object {
        private const val DAY=86_400_000L
        private fun isGlobalFailure(result: ProbeResult)=result.status==HealthStatus.RATE_LIMITED||result.status==HealthStatus.CHALLENGE||
            result.status==HealthStatus.UNAVAILABLE&&(result.detail.contains("resolve",true)||result.detail.contains("timeout",true)||result.detail.contains("timed out",true)||result.detail.contains("connect",true)||Regex("\\b5[0-9]{2}\\b").containsMatchIn(result.detail))
        fun isTargetSpecific(result: ProbeResult)=!isGlobalFailure(result)&&(result.status in setOf(HealthStatus.EMPTY,HealthStatus.LOGIN_REQUIRED)||
            result.status==HealthStatus.UNAVAILABLE&&Regex("\\b(?:404|410)\\b").containsMatchIn(result.detail))
    }
}
