/* anti social, GPL-3.0-or-later. A delayed, bounded first-result lane.
 * Admission races, never network reads; every permit stays inside withPermit. */
package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

class LocalDiscoveryReadAdmission(parallelism:Int=2,rescueParallelism:Int=1,private val hedgeDelayMs:Long=750) {
    private val normal=Semaphore(parallelism)
    private val rescue=Semaphore(rescueParallelism)
    init {require(hedgeDelayMs>=0)}
    suspend fun <T> read(allowRescue:()->Boolean,action:suspend ()->T):T {
        if(!allowRescue())return normal.withPermit {action()}
        return coroutineScope {
            val claimed=AtomicBoolean()
            val outcome=CompletableDeferred<Result<T>>()
            suspend fun perform() {
                if(claimed.compareAndSet(false,true))outcome.complete(try {Result.success(action())}catch(e:Throwable){Result.failure(e)})
            }
            val ordinary=launch {normal.withPermit {perform()}}
            val alternative=launch {
                delay(hedgeDelayMs)
                if(!claimed.get()&&allowRescue())rescue.withPermit {if(allowRescue())perform()}
            }
            try {outcome.await().getOrThrow()}
            finally {ordinary.cancel();alternative.cancel()}
        }
    }
}
