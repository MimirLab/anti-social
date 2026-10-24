/* anti social, GPL-3.0-or-later. Bounded, typed public Substack identity routes. */
package app.antisocial.core

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The caller supplies NetworkGateway transport. Aliases/short-lived HTML are
 * washed into typed records; requests never need cookies, scripts or tool calls. */
class SubstackResolver(private val read:suspend (String,Boolean)->Payload,
    private val originals:Boolean=true,private val learning:AdaptiveRouting=AdaptiveRouting(),private val tor:Boolean=false,
    private val clock:()->Long=System::currentTimeMillis,private val onLearning:()->Unit={},
    private val timeoutMs:Long=if(tor)8_000L else 3_500L) {
    private var requests=0
    private suspend fun <T> fetch(owner:PublicAccount,native:String,publication:Boolean=false,parse:(Payload)->T):T {
        val proxy=(if(publication)"https://api.substacktools.com/free-tools/downloader/publication/metadata"else "https://proxy.pullsy.online/")
            .toHttpUrlOrNull()!!.newBuilder().addQueryParameter("url",native).build().toString()
        val target=RssSources.target(owner.url)
        val sources=listOf(Source(Platform.RSS,if(publication)"substacktools-identity"else "pullsy-identity",proxy.toHttpUrlOrNull()!!.let {"https://${it.host}"}) to proxy)+
            if(originals)listOf(Source(Platform.RSS,"substack-identity",native.toHttpUrlOrNull()!!.let {"https://${it.host}"},role=SourceRole.ORIGINAL) to native)else emptyList()
        var failure:Exception?=null
        for((source,url) in sources) {
            if(requests>=5||!learning.eligible(source,Capability.PROFILE,tor,target))continue
            requests++;val started=System.nanoTime()
            try {
                val value=withTimeout(timeoutMs){parse(read(url,source.role==SourceRole.ORIGINAL))}
                learning.observe(ProbeResult(source,Capability.PROFILE,HealthStatus.HEALTHY,(System.nanoTime()-started)/1_000_000,clock(),tor),target);onLearning()
                return value
            }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e
                currentCoroutineContext().ensureActive()
                failure=SourceFailure(HealthStatus.UNAVAILABLE,"Public Substack route timed out")
                learning.observe(ProbeResult(source,Capability.PROFILE,HealthStatus.UNAVAILABLE,1,clock(),tor,"Public identity route timed out"),target);onLearning()
            }catch(e:Exception){failure=e
                learning.observe(ProbeResult(source,Capability.PROFILE,(e as? SourceFailure)?.status ?: HealthStatus.EMPTY,
                    (System.nanoTime()-started)/1_000_000,clock(),tor,(e.message ?: "Unusable public identity").take(180)),target);onLearning()
            }
        }
        throw failure ?: SourceFailure(HealthStatus.UNKNOWN,"Public Substack routes are paused; try again later")
    }
    suspend fun identity(account:PublicAccount):SubstackIdentity {
        require(account.provider=="substack")
        return if(account.url.toHttpUrlOrNull()!!.host.removePrefix("www.") in setOf("substack.com","open.substack.com"))
            fetch(account,SubstackPublicData.profileRequest(account)){SubstackPublicData.profile(account,it)}
        else fetch(account,account.url,publication=true){SubstackPublicData.publication(account,it)}
    }
    private suspend fun <T> optional(block:suspend ()->T):T?=try {block()}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;null}catch(_:Exception){null}
    suspend fun bridge(account:PublicAccount,related:Boolean,at:()->Long=clock):BridgePage {
        var value=identity(account)
        // A publication's literal author link is its route back to a user and
        // other platforms. Failure leaves the already acquired feed usable.
        if(value.account.id?.startsWith("publication:")==true) {
            val author=value.links.mapNotNull(SubstackPublicData::account).firstOrNull {it.url.contains("/@")}
            val user=author?.let {optional {identity(it)}}
            if(user!=null&&user.publications.any {it.id==value.account.id})value=value.copy(links=(value.links+user.links).distinct().take(24))
            // The author's profile link enters its own discovery bridge. Do not
            // relabel that person's subscriptions as the publication's follows.
        }
        val owner=value.publications.firstOrNull()
        val recs=if(related&&owner!=null)optional {
            fetch(owner,owner.url.trimEnd('/')+"/api/v1/recommendations/from/"+owner.id!!.removePrefix("publication:")){
                SubstackPublicData.recommendations(owner,it)
            }
        }.orEmpty()else emptyList()
        return SubstackPublicData.bridge(if(related)value else value.copy(readingList=emptyList()),at(),recs)
    }
}
