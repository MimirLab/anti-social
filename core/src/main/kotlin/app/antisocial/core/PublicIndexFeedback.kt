/* anti social, GPL-3.0-or-later. Caller-owned public-index availability evidence. */
package app.antisocial.core

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Separate a provider refusal from useful destinations returned in the same
 * operation. No Page/import fields or detached result registry are involved. */
internal class PublicIndexFeedback(val source:Source,val session:NetworkSession):AbstractCoroutineContextElement(Key) {
    private val began=System.nanoTime()
    @Volatile var refusal:SourceFailure?=null
        private set
    val elapsedMs:Long get()=(System.nanoTime()-began)/1_000_000
    @Synchronized fun observe(errors:List<Throwable>) {
        refusal=(errors.filterIsInstance<SourceFailure>().filter(::isRefusal)+listOfNotNull(refusal)).maxWithOrNull(compareBy<SourceFailure> {
            maxOf(it.retryAfterMs ?: 0,if(it.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED))60_000L else 15_000L)
        }.thenBy {when(it.status){HealthStatus.RATE_LIMITED->3;HealthStatus.CHALLENGE->2;HealthStatus.LOGIN_REQUIRED->1;else->0}}) ?: refusal
    }
    fun check(gateway:NetworkGateway) {
        if(gateway.sessionFor(source.root)!==session)throw CancellationException("Search connection changed; refresh this search")
    }
    companion object Key:CoroutineContext.Key<PublicIndexFeedback> {
        fun isRefusal(error:Throwable?):Boolean=(error as? SourceFailure)?.let {
            it.status in setOf(HealthStatus.RATE_LIMITED,HealthStatus.CHALLENGE,HealthStatus.LOGIN_REQUIRED)||it.status==HealthStatus.UNAVAILABLE&&it.retryAfterMs!=null
        }==true
    }
}
