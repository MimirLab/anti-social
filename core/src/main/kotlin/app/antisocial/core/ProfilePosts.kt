package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

/** Exact-author discovery for sites that expose a profile but withhold a public
 * timeline. Only actual reads of that author's indexed posts become cards. */
object ProfilePosts {
    private fun handle(target: Target)=target.identity.trim('/').removePrefix("@").takeIf {it.matches(Regex("[A-Za-z0-9._]{1,64}"))}
    fun supported(target: Target)=target.kind==TargetKind.PROFILE&&target.platform in setOf(Platform.TIKTOK,Platform.THREADS)&&handle(target)!=null
    fun search(routes: DarwinRoutes,target: Target): Target {
        require(supported(target))
        val path=if(target.platform==Platform.TIKTOK)"video"else "post"
        return routes.search(target.platform,"site:${target.platform.searchDomain}/@${handle(target)}/$path/")
    }
    private fun owns(profile: Target,post: Target): Boolean {
        if(post.platform!=profile.platform||post.kind!=TargetKind.POST)return false
        val path=URI(post.canonicalUrl).path.trim('/').split('/')
        return path.size>=3&&path[0].removePrefix("@").equals(handle(profile),true)&&path[1]==if(profile.platform==Platform.TIKTOK)"video"else "post"
    }
    suspend fun resolve(target: Target,initial: Page,discovery: Page,routes: DarwinRoutes,read: suspend (Target)->Page): Page=coroutineScope {
        require(supported(target))
        val direct=discovery.posts.filter {post->routes.recognize(post.canonicalUrl)?.let {owns(target,it)}==true}
        val candidates=discovery.collections.map {it.target}.filter {owns(target,it)}.distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}.take(6)
        val permits=Semaphore(2)
        val resolved=candidates.map {post->async {
            try {permits.withPermit {read(post)}.posts.filter {it.platform==post.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(post.platform,post.canonicalUrl)}}catch(e: CancellationException){throw e}catch(_: Exception){emptyList()}
        }}.awaitAll().flatten()
        val posts=(direct+resolved).filter {it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty()}.distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}.take(12).sortedByDescending {it.publishedAt ?: Long.MIN_VALUE}
        initial.copy(posts=posts,provenance=discovery.provenance,notice=if(posts.isNotEmpty())"Public posts found through search; coverage may be incomplete or older."else "Profile details are available, but public sources did not supply readable posts.")
    }
}
