package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex

data class SearchFeedState(val page: Page=Page(emptyList()),val loading: Boolean=false,val hasMore: Boolean=true,val error: String?=null,val unavailable: Int=0,val limited: Boolean=false,val paused: Boolean=false,val revision: Long=0)

/** A bounded, resumable search session. Index snippets never become synthetic posts.
 * Discovery pages stay bound to their issuing source; only requested post identities
 * and actual profile metadata survive hydration. Results publish as each read finishes. */
class SearchFeed(
    private val target: Target,
    private val discover: suspend (Page?)->Page,
    private val read: suspend (Target)->Page,
    private val blockSize: Int=6,
    private val maxPosts: Int=200,
    private val maxAccounts: Int=40,
    private val maxPages: Int=20,
    private val maxCharacters: Int=1024*1024,
    private val allowEmptyDiscoveryPages: ()->Boolean={false},
    private val maxConcurrentReads: Int=2,
    private val maxCandidates: Int=240,
    private val directory: (suspend () -> Page)?=null
) {
    @Volatile private var contentState=SearchFeedState()
    @Volatile private var directoryAccounts: List<Profile> = emptyList()
    /** Directory metadata overlays the main snapshot without writing its posts,
     * loading flags or cursor. Separate jobs cannot overwrite one another. */
    val state: SearchFeedState get() {
        val current=contentState
        val extra=directoryAccounts
        if(extra.isEmpty())return current
        val rows=current.page.accounts.toMutableList()
        val identities=rows.map {key(it.target)}.toMutableSet()
        var remaining=maxCharacters-current.page.posts.sumOf {it.body.length+it.title.length+512}-rows.sumOf {it.name.length+it.description.length+512}
        for(entity in extra) {
            val size=entity.name.length+entity.description.length+512
            if(rows.size>=maxAccounts)break
            if(key(entity.target) !in identities&&size<=remaining) {rows+=entity;identities+=key(entity.target);remaining-=size}
        }
        return current.copy(page=current.page.copy(accounts=rows))
    }
    private val mutex=Mutex()
    private val pending=ArrayDeque<Collection>()
    private val seen=mutableSetOf<String>()
    private val cursors=mutableSetOf<String>()
    private var discovery: Page?=null
    private var blocks=0
    private var characters=0
    private var started=false
    private var ended=false
    private var exhaustedBudget=false
    @Volatile private var directoryAttempted=false
    private val directoryMutex=Mutex()
    private sealed interface Event {
        data class Discovered(val result: Result<Page>): Event
        data class Read(val candidate: Collection,val result: Result<Page>): Event
    }
    init {require(blockSize>0&&maxPosts>0&&maxAccounts>=0&&maxPages>0&&maxCharacters>0&&maxCandidates>0&&maxConcurrentReads in 1..4)}
    /** The caller cancels its load job first; a paused session resumes only by user action. */
    fun pause() {contentState=contentState.copy(loading=false,error=null,paused=true)}
    private fun key(t: Target): String {
        val identity=ContentIdentity.key(t.platform,t.canonicalUrl)
        return if(t.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY))PublicSearchAdapter.entityKey(t)else "${t.kind}:$identity"
    }
    private fun more()=!contentState.limited&&(!started||pending.isNotEmpty()||!ended)
    private fun merge(posts: List<Post> = emptyList(),accounts: List<Profile> = emptyList()) {
        val old=contentState.page
        val postKeys=old.posts.map {ContentIdentity.key(it.platform,it.canonicalUrl)}.toMutableSet()
        val accountKeys=old.accounts.map {key(it.target)}.toMutableSet()
        val additions=posts.filter {it.platform==target.platform&&!it.preview&&(it.body.isNotBlank()||it.title.isNotBlank()||it.media.isNotEmpty())&&(!SearchScopes.nativeSearch(target)||SearchScopes.accepts(target,it))}.filter {post->
            if(!postKeys.add(ContentIdentity.key(post.platform,post.canonicalUrl)))false
            else if(postKeys.size>maxPosts||characters+post.body.length+post.title.length+512>maxCharacters){contentState=contentState.copy(limited=true);false}
            else {characters+=post.body.length+post.title.length+512;true}
        }
        val people=accounts.filter {it.target.platform==target.platform&&PublicSearchAdapter.entityAllowed(it.target)&&it.name.isNotBlank()}.filter {profile->
            if(!accountKeys.add(key(profile.target)))false
            // Filling the People shelf must not stop discovery of actual posts.
            else if(accountKeys.size>maxAccounts)false
            else if(characters+profile.name.length+profile.description.length+512>maxCharacters){contentState=contentState.copy(limited=true);false}
            else {characters+=profile.name.length+profile.description.length+512;true}
        }
        contentState=contentState.copy(page=old.copy(posts=(old.posts+additions).sortedWith(compareByDescending<Post> {it.publishedAt ?: Long.MIN_VALUE}.thenBy {it.id}),accounts=old.accounts+people))
    }
    /** Run from a caller-owned side job. Cancellation leaves an unfinished lookup
     * retryable; successful/failed completed lookups run only once per session. */
    suspend fun loadDirectory(publish: suspend (SearchFeedState)->Unit) {
        val lookup=directory ?: return
        if(!directoryMutex.tryLock())return
        try {
            if(directoryAttempted)return
            if(SearchScopes.from(target)!=null) {directoryAttempted=true;return}
            val page=try {lookup()}catch(e: CancellationException){throw e}catch(_: Exception){null}
            val rows=mutableListOf<Profile>();val identities=mutableSetOf<String>();var size=0
            for(entity in page?.accounts.orEmpty()) {
                if(entity.target.platform!=target.platform||!PublicSearchAdapter.entityAllowed(entity.target)||entity.name.isBlank()||!identities.add(key(entity.target)))continue
                val length=entity.name.length+entity.description.length+512
                if(rows.size>=maxAccounts)break
                if(size+length<=maxCharacters) {rows+=entity;size+=length}
            }
            directoryAccounts=rows.toList();directoryAttempted=true
            publish(state)
        }finally {directoryMutex.unlock()}
    }
    suspend fun loadNext(publish: suspend (SearchFeedState)->Unit) {
        if(!mutex.tryLock())return
        try {
            if(!more()) {
                contentState=contentState.copy(loading=false,paused=false);publish(state)
                return
            }
            val postsAtStart=contentState.page.posts.size
            contentState=contentState.copy(loading=true,error=null,paused=false);publish(state)
            var failure: Exception?=null
            coroutineScope {
                val replies=Channel<Event>(Channel.UNLIMITED)
                val active=mutableMapOf<String,Job>()
                var finding: Job?=null
                var discoveryReads=0
                var contentReads=0
                fun pump() {
                    // One continuation may overlap destination reads. Its cursor
                    // comes from the real previous response, never a guessed offset.
                    // Two discovery blocks per call bound empty-page scanning and
                    // prefetch; a full post backlog needs no speculative page yet.
                    val backlog=pending.count {it.target.kind==TargetKind.POST}
                    val nativeBuffered=contentState.page.posts.size-postsAtStart
                    if(finding==null&&!ended&&!contentState.limited&&failure==null&&discoveryReads<2&&backlog<=blockSize&&nativeBuffered<blockSize*2) {
                        val previous=discovery;discoveryReads++
                        finding=launch {
                            val result=try {Result.success(discover(previous))}catch(e: CancellationException){throw e}catch(e: Exception){Result.failure(e)}
                            replies.send(Event.Discovered(result))
                        }
                    }
                    while(active.size<maxConcurrentReads&&contentReads<blockSize&&!contentState.limited) {
                        val candidate=pending.asSequence().filter {key(it.target) !in active}
                            .filter {it.target.kind==TargetKind.POST||contentState.page.accounts.size<maxAccounts}
                            .minByOrNull {if(it.target.kind==TargetKind.POST)0 else 1} ?: break
                        val identity=key(candidate.target);contentReads++
                        active[identity]=launch {
                            val result=try {Result.success(read(candidate.target))}catch(e: CancellationException){throw e}catch(e: Exception){Result.failure(e)}
                            replies.send(Event.Read(candidate,result))
                        }
                    }
                    if(contentState.page.accounts.size>=maxAccounts)pending.removeAll {it.target.kind!=TargetKind.POST&&key(it.target) !in active}
                }
                try {
                    pump()
                    while(finding!=null||active.isNotEmpty()) {
                        when(val event=replies.receive()) {
                            is Event.Discovered->{
                                finding=null
                                val incoming=event.result.getOrNull()
                                if(incoming==null)failure=event.result.exceptionOrNull() as? Exception ?: Exception("Search is unavailable")
                                else {
                                    started=true;blocks++
                                    val next=incoming.next?.takeIf {it.isNotBlank()&&it.length<=24*1024&&cursors.add(it)}
                                    exhaustedBudget=exhaustedBudget||blocks>=maxPages
                                    // A native scoped search's empty continuation
                                    // still ends that native source. Explicit feed
                                    // scanners and validated public-index cursors
                                    // retain their own bounded navigation progress.
                                    val nativeScopeEnd=discovery!=null&&SearchScopes.from(target)!=null&&!allowEmptyDiscoveryPages()&&
                                        incoming.provenance?.source?.frontend !in PublicSearchAdapter.frontends&&
                                        incoming.posts.isEmpty()&&incoming.accounts.isEmpty()&&incoming.collections.isEmpty()
                                    ended=next==null||exhaustedBudget||nativeScopeEnd
                                    discovery=incoming.copy(next=if(ended)null else next)
                                    contentState=contentState.copy(page=contentState.page.copy(provenance=incoming.provenance,notice=incoming.notice))
                                    merge(incoming.posts,incoming.accounts)
                                    val candidates=incoming.collections.filter {it.target.platform==target.platform&&(it.target.kind==TargetKind.POST||PublicSearchAdapter.entityAllowed(it.target))}
                                        .sortedBy {if(it.target.kind==TargetKind.POST)0 else 1}.take(60)
                                    for(candidate in candidates) {
                                        if(seen.size>=maxCandidates) {exhaustedBudget=true;ended=true;break}
                                        if(seen.add(key(candidate.target)))pending.addLast(candidate)
                                    }
                                }
                            }
                            is Event.Read->{
                                val candidate=event.candidate;active.remove(key(candidate.target));pending.remove(candidate)
                                val page=event.result.getOrNull()
                                if(candidate.target.kind==TargetKind.POST) {
                                    val wanted=ContentIdentity.key(candidate.target.platform,candidate.target.canonicalUrl)
                                    val posts=page?.posts.orEmpty().filter {ContentIdentity.key(it.platform,it.canonicalUrl)==wanted&&!it.preview}
                                    if(posts.isEmpty())contentState=contentState.copy(unavailable=contentState.unavailable+1)else merge(posts)
                                }else {
                                    val profile=page?.profile?.takeIf {key(it.target)==key(candidate.target)}
                                    if(profile==null)contentState=contentState.copy(unavailable=contentState.unavailable+1)else merge(accounts=listOf(profile))
                                }
                            }
                        }
                        contentState=contentState.copy(hasMore=more());publish(state)
                        if(contentState.limited)break
                        pump()
                    }
                }finally {finding?.cancel();active.values.forEach {it.cancel()};replies.cancel()}
            }
            // Empty/duplicate content is not proof that a later valid cursor has
            // no posts. Distinct cursor, page, candidate and text budgets bound it.
            contentState=contentState.copy(loading=false,error=failure?.message,limited=contentState.limited||(exhaustedBudget&&pending.isEmpty()))
            // Even a fast empty batch must have an observable completion. UI
            // frames may coalesce loading=true/false and otherwise strand a
            // remaining valid candidate behind an unchanged empty result list.
            contentState=contentState.copy(hasMore=more(),revision=contentState.revision+1)
            publish(state)
        }catch(e: CancellationException){throw e}catch(e: Exception) {
            contentState=contentState.copy(loading=false,hasMore=more(),error=e.message ?: "Search is unavailable",revision=contentState.revision+1);publish(state)
        }finally {contentState=contentState.copy(loading=false);mutex.unlock()}
    }
}
