package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class ReadingOrder(val label: String) { CHRONOLOGICAL("Newest"), SLOP("Slop") }
data class ThreadRead(val post: Post,val page: Page?=null,val loading: Boolean=false,val error: String?=null)
data class AnywhereThreadRead(val post: Post,val url: String?=null,val page: AnywherePage?=null,val loading: Boolean=false,val error: String?=null)
data class DiscussionState(val posts: List<Post>,val selected: String,val order: ReadingOrder=ReadingOrder.CHRONOLOGICAL,val threads: Map<String,ThreadRead> = emptyMap(),val edition: String=posts.first().platform.id,val communities: Map<String,AnywhereThreadRead> = emptyMap(),val composeRequest: Int=0) {
    /** The first original keeps its historical platform key. Further originals
     * use their own canonical identity, so pagination, drafts and failures cannot collide. */
    fun threadKey(post: Post): String=if(posts.firstOrNull {it.platform==post.platform}?.let(::postKey)==postKey(post))post.platform.id else postKey(post)
    fun original(key: String): Post?=posts.firstOrNull {threadKey(it)==key||postKey(it)==key} ?: posts.firstOrNull {it.platform.id==key}
    fun thread(post: Post): ThreadRead?=threads[threadKey(post)]?.takeIf {postKey(it.post)==postKey(post)}
    fun shared(post: Post): AnywhereThreadRead?=communities[threadKey(post)]?.takeIf {postKey(it.post)==postKey(post)}
    fun originalNumber(post: Post): Int=posts.filter {it.platform==post.platform}.indexOfFirst {postKey(it)==postKey(post)}+1
    fun originalLabel(post: Post): String=post.platform.label+if(posts.count {it.platform==post.platform}>1)" · original ${originalNumber(post)}"else ""
    companion object {private fun postKey(post: Post)="${post.platform.id}:${ContentIdentity.key(post.platform,post.canonicalUrl)}"}
}

/** On-demand reads: two original streams and one shared room can progress independently. */
class DiscussionReader(private val repo: Repository,private val scope: CoroutineScope,private val anywhere: AnywhereAccess=repo.commentAnywhere,private val read: suspend (Post,Page?,ReadingOrder)->Page={post,previous,order->repo.discussion(post,previous,order)}) {
    val state=MutableStateFlow<DiscussionState?>(null)
    private val permits=Semaphore(2)
    private val communityPermits=Semaphore(1)
    private val communityAccess=object: AnywhereAccess by anywhere {
        override suspend fun read(url: String,order: CommentOrder,force: Boolean,limit: Int,parentId: String?)=
            communityPermits.withPermit {withTimeout(45_000){anywhere.read(url,order,force,limit,parentId)}}
    }
    val community=AnywhereReader(repo.routes,scope,communityAccess)
    val communityEnabled get()=anywhere.enabled
    private var session: Job?=null
    private var generation=0
    private val memory=mapOf(ReadingOrder.CHRONOLOGICAL to PageMemory(12,2*1024*1024),ReadingOrder.SLOP to PageMemory(12,2*1024*1024))
    private val jobs=mutableMapOf<String,Job>()
    private val communityTokens=mutableMapOf<String,Any>()
    init {scope.launch {community.state.collectLatest {active->
        if(active!=null)state.update {current->
            if(current!=null&&current.selected in setOf("all",COMMENT_ANYWHERE)&&current.edition==current.threadKey(active.post)&&current.posts.any {repo.key(it)==repo.key(active.post)}&&current.order.communityOrder()==active.order) {
                val key=current.threadKey(active.post)
                val previous=current.shared(active.post)?.page
                    ?.takeIf {it.pageUrl==active.url&&it.order==active.order}
                current.copy(communities=current.communities+(key to AnywhereThreadRead(active.post,active.url,active.page ?: previous,active.loading,active.error)))
            }else current
        }
    }}}
    fun open(posts: List<Post>,selected: String?=null) {
        close();val chosen=posts.distinctBy(repo::key).take(CrossPosts.MAX_EDITIONS);if(chosen.isEmpty())return
        val initial=selected?.takeIf {it==COMMENT_ANYWHERE||it=="all"||chosen.any {post->post.platform.id==it}} ?: "all"
        session=SupervisorJob(scope.coroutineContext[Job]);val opened=DiscussionState(chosen,initial);state.value=opened.copy(threads=chosen.associate {opened.threadKey(it) to ThreadRead(it)})
        select(initial)
    }
    fun close(){generation++;session?.cancel();session=null;jobs.clear();communityTokens.clear();community.close();state.value=null}
    fun clear(){close();memory.values.forEach {it.clear()};community.clear()}
    fun select(platform: String){
        val current=state.value ?: return
        if(platform!=COMMENT_ANYWHERE&&platform!="all"&&current.posts.none {it.platform.id==platform})return
        state.value=current.copy(selected=platform,composeRequest=if(platform==current.selected)current.composeRequest else 0)
        if(platform in setOf("all",COMMENT_ANYWHERE)) {
            if(!communityEnabled&&platform=="all"){closeActiveCommunity();cancelSecondaryCommunities();current.posts.forEach {load(it)};return}
            val root=current.original(current.edition) ?: return
            if(platform==COMMENT_ANYWHERE)cancelSecondaryCommunities()
            // A secondary read becomes the editable active room. Cancel its old
            // job first so it cannot overwrite a later refresh or duplicate it.
            cancelSecondaryCommunity(current.threadKey(root))
            if(community.state.value?.let {repo.key(it.post)==repo.key(root)&&it.order==current.order.communityOrder()}!=true) {
                closeActiveCommunity();community.open(root,current.order.communityOrder())
            }
            if(platform==COMMENT_ANYWHERE)return
            current.posts.forEach {load(it);if(current.threadKey(it)!=current.edition)loadCommunity(it)}
        }else {closeActiveCommunity();cancelSecondaryCommunities();current.posts.filter {it.platform.id==platform}.forEach {load(it)}}
    }
    fun edition(original: String){val current=state.value ?: return;val post=current.original(original) ?: return;val key=current.threadKey(post);if(current.edition==key)return;state.value=current.copy(edition=key);select(current.selected.takeIf {it=="all"} ?: COMMENT_ANYWHERE)}
    fun reply(post: Post,comment: AnywhereComment){val current=state.value ?: return;if(current.posts.none {repo.key(it)==repo.key(post)})return;state.value=current.copy(edition=current.threadKey(post));select(if(current.selected=="all")"all"else COMMENT_ANYWHERE);community.target(comment);state.update {it?.copy(composeRequest=it.composeRequest+1)}}
    fun order(order: ReadingOrder){val current=state.value ?: return;if(current.order==order)return;val selected=current.selected;val posts=current.posts;close();session=SupervisorJob(scope.coroutineContext[Job]);state.value=current.copy(order=order,threads=posts.associate {current.threadKey(it) to ThreadRead(it)},communities=emptyMap(),composeRequest=0);select(selected)}
    fun retry(original: String){state.value?.original(original)?.let {load(it,force=true)}}
    fun more(original: String){state.value?.original(original)?.let {load(it,more=true)}}
    fun retryCommunity(original: String){val current=state.value ?: return;val post=current.original(original) ?: return;if(current.threadKey(post)==current.edition){community.refresh();return};loadCommunity(post,true)}
    fun communityCaptures(roots: List<Post>): List<CapturedThread> {
        val keys=roots.map(repo::key).toSet()
        val active=community.captures(roots)
        return (active+state.value?.communities?.values.orEmpty().filter {repo.key(it.post) in keys&&it.page!=null}.map {entry->val page=entry.page!!;CapturedThread(entry.post,emptyList(),page.order.name,page.fetchedAt,page.limited,commentAnywhere=page)}).distinctBy {repo.key(it.root)}
    }
    private fun resetPendingCommunity(platform: String) {
        state.update {current->
            val pending=current?.communities?.get(platform)
            if(current==null||pending?.loading!=true)current else current.copy(communities=
                if(pending.page==null&&pending.error==null)current.communities-platform
                else current.communities+(platform to pending.copy(loading=false)))
        }
    }
    private fun closeActiveCommunity() {
        val active=community.state.value
        community.close()
        if(active?.loading==true)state.value?.let {resetPendingCommunity(it.threadKey(active.post))}
    }
    private fun cancelSecondaryCommunity(platform: String) {
        val key="community:$platform"
        val pending=jobs.remove(key)
        communityTokens.remove(key)
        if(pending?.isActive==true){pending.cancel();resetPendingCommunity(platform)}
    }
    private fun cancelSecondaryCommunities() {
        jobs.keys.filter {it.startsWith("community:")}.map {it.removePrefix("community:")}.forEach(::cancelSecondaryCommunity)
    }
    private fun loadCommunity(post: Post,force: Boolean=false) {
        if(!communityEnabled)return
        val current=state.value ?: return;val original=current.threadKey(post);val key="community:$original";val previous=current.shared(post)
        if(jobs[key]?.isActive==true||!force&&previous!=null)return
        val bound=generation;val parent=session ?: return;val token=Any();communityTokens[key]=token
        val url=try {AnywhereUrls.root(post,repo.routes)}catch(e: Exception){state.update {it?.copy(communities=it.communities+(original to AnywhereThreadRead(post,error=e.message)))};return}
        state.update {it?.copy(communities=it.communities+(original to AnywhereThreadRead(post,url,previous?.page,true)))}
        jobs[key]=scope.launch(parent) {
            fun publish(value: AnywhereThreadRead) {state.update {active->
                if(bound==generation&&communityTokens[key]===token&&active?.order==current.order&&active.posts.any {repo.key(it)==repo.key(post)})
                    active.copy(communities=active.communities+(original to value))else active
            }}
            try {val page=communityAccess.read(url,current.order.communityOrder(),force);publish(AnywhereThreadRead(post,url,page))}
            catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;publish(AnywhereThreadRead(post,url,previous?.page,error="The community service timed out."))}
            catch(e: Exception){publish(AnywhereThreadRead(post,url,previous?.page,error=e.message ?: "Community discussion is unavailable."))}
        }
    }
    private fun load(post: Post,force: Boolean=false,more: Boolean=false) {
        val current=state.value ?: return;val key=current.threadKey(post);val previous=current.thread(post)
        if(jobs[key]?.isActive==true||!force&&!more&&(previous?.page!=null||previous?.error!=null))return
        if(more&&previous?.page?.next==null)return
        val target=repo.routes.recognize(post.canonicalUrl) ?: return
        val order=current.order;val bound=generation;val parent=session ?: return
        val cached=memory.getValue(order).get(target)
        if(!force&&!more&&cached!=null&&memory.getValue(order).fresh(target)){state.value=current.copy(threads=current.threads+(key to ThreadRead(post,cached)));return}
        state.value=current.copy(threads=current.threads+(key to ThreadRead(post,previous?.page,loading=true)))
        jobs[key]=scope.launch(parent) {
            try {
                val page=permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    if(bound!=generation)throw CancellationException("Discussion closed")
                    withTimeout(45_000){read(post,if(more)previous?.page else null,order)}
                }
                val old=previous?.page.takeIf {more};val posts=(old?.posts.orEmpty()+page.posts).distinctBy {repo.key(it)}
                var count=0;var characters=0
                val bounded=posts.takeWhile {count++;characters+=it.body.length+it.title.length+it.canonicalUrl.length+512;count<=200&&characters<=200_000}
                val added=old==null||bounded.any {new->old.posts.none {repo.key(it)==repo.key(new)}}
                val limited=bounded.size<posts.size
                val result=page.copy(posts=bounded,next=page.next?.takeIf {!limited&&added&&it!=old?.next},notice=if(limited)"Showing the first 200 entries or 200 KB. Open the post to read further."else page.notice)
                currentCoroutineContext().ensureActive()
                if(bound!=generation)return@launch
                memory.getValue(order).put(target,result)
                if(bound==generation)state.update {s->s?.copy(threads=s.threads+(key to ThreadRead(post,result)))}
            }catch(e: CancellationException){if(e !is TimeoutCancellationException)throw e;if(bound==generation)failure(key,post,previous?.page,"Public comments timed out. Try again.")}
            catch(e: Exception){if(bound==generation)failure(key,post,previous?.page,e.message ?: "Public comments are unavailable")}
        }
    }
    private fun failure(key: String,post: Post,page: Page?,message: String){state.update {it?.copy(threads=it.threads+(key to ThreadRead(post,page,error=message)))}}
}
private fun ReadingOrder.communityOrder()=if(this==ReadingOrder.SLOP)CommentOrder.SLOP else CommentOrder.NEWEST
