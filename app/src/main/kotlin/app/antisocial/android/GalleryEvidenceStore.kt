/* anti social, GPL-3.0-or-later. Existing routes, demand-scoped gallery evidence. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class GalleryEvidenceStore(private val repo:Repository,private val clock:()->Long=System::currentTimeMillis) {
    private val lock=Any();private val lane=Mutex()
    private val checked=linkedMapOf<String,Long>();private val running=linkedSetOf<Job>();private var generation=0L
    private val changes=MutableStateFlow(0L);val revision=changes.asStateFlow()
    internal var read:suspend (Post)->Page=repo::publisherGalleryPage
    internal var image:suspend (String,String)->Unit={url,source->repo.images.load(url,source,1600);Unit}
    private fun input(request:GalleryContinuationRequest)=Repository.hash(listOf(request.root,request.peer).joinToString("\u0000") {post->
        post.identityKey+"\u0000"+post.publishedAt+"\u0000"+post.title+"\u0000"+post.body+"\u0000"+post.media.joinToString {ImageAssetIdentity.key(it.url) ?: it.url}
    }+request.editions.filter {it.platform==Platform.X}.map {it.identityKey}.sorted().joinToString()+request.bindings.sortedBy {it.id}.joinToString {it.id+it.target.canonicalUrl+Subscriptions.id(it)})
    fun available(request:GalleryContinuationRequest):Boolean=synchronized(lock) {checked[input(request)]?.let {clock()-it !in 0 until 20*60_000L} ?: true}
    suspend fun allowed():Boolean=withContext(Dispatchers.IO) {
        val context=repo.context
        !(repo.prefs.wifiOnly&&context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered!=false)&&
            !context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
    }
    private fun captured(post:Post)=repo.images.identities.captured(post.copy(media=post.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null)}))
    suspend fun enrich(request:GalleryContinuationRequest,automatic:Boolean=true):Unit=withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job]!!;val epoch=synchronized(lock){running+=job;generation}
        fun current()=synchronized(lock){generation==epoch&&job.isActive}
        try {repo.cardWork.track(request.editions.map(::postWorkKey),"Finding gallery continuation") {lane.withLock {
            repo.awaitReady()
            if(!current()||!available(request)||automatic&&!allowed())return@withLock
            withTimeout(25_000) {repo.gateway.boundedMediaRead(maxBytes=8L*1024*1024,maxRequests=8) {
                var attempts=0
                while(current())try {
                    while(repo.gateway.hasForegroundRequests)delay(300)
                    repo.gateway.deferredRead {
                        val page=read(request.root)
                        val candidates=GalleryContinuations.candidates(request,page)
                        for(child in candidates)for(media in child.media) {
                            currentCoroutineContext().ensureActive()
                            image(media.url,child.contentSourceUrl)
                        }
                        val proof=request.copy(root=captured(request.root),peer=captured(request.peer),editions=request.editions.map(::captured))
                        val children=candidates.map(::captured)
                        if(!current())throw CancellationException("Gallery work was retired")
                        if(GalleryContinuations.proven(proof,children))repo.storeGalleryContinuation(request,candidates,::current)
                    }
                    synchronized(lock){if(generation==epoch){checked[input(request)]=clock();while(checked.size>128)checked.remove(checked.keys.first());changes.value+=1}}
                    break
                }catch(e:CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if(++attempts>=3)throw e
                    delay(700)
                }
            }}
        }}}catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive()}
        catch(e:CancellationException){throw e}
        catch(_:Exception){synchronized(lock){if(generation==epoch){checked[input(request)]=clock();changes.value+=1}}}
        finally {synchronized(lock){running-=job};repo.scheduleCleanup()}
    }
    fun retry()=synchronized(lock){checked.clear();changes.value+=1}
    fun clear()=synchronized(lock){generation++;running.toList().forEach {it.cancel(CancellationException("Ordinary gallery evidence cleared"))};checked.clear();changes.value+=1}
}
