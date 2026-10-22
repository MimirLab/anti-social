package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImageSearchUiState(
    val image: Media?=null,
    val source: String="",
    val feed: ImageSearchState=ImageSearchState(),
    val preparing: Boolean=false,
    val message: String="",
    val error: String?=null,
    val stopped: Boolean=false
) {val loading get()=preparing||feed.loading}

/** Owns one bounded, in-memory image search. No uploaded copy, provider cursor or
 * discovery index is added to the user's saved library or reading history. */
class ImageSearchController(
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val prepare: suspend (Media,String)->ImageSearchInput={media,source->prepareImageSearch(repo,media,source)},
    private val create: (ImageSearchInput)->ImageSearchSession=repo::imageSearchSession,
    private val connect: suspend ()->Unit={
        repo.awaitReady()
        check(repo.prefs.originalFallback){"Public image search needs original-site access. Enable it in Privacy & connection to search."}
        repo.imageSearchConnection(true)
        withTimeout(120_000){repo.tor.state.first {it.enabled&&it.ready}}
    },
    private val release: ()->Unit={repo.imageSearchConnection(false)}
) {
    private val mutable=MutableStateFlow(ImageSearchUiState())
    val state: StateFlow<ImageSearchUiState> = mutable.asStateFlow()
    private var session: ImageSearchSession?=null
    private var job: Job?=null
    private var generation=0
    fun start(image: Media,source: String) {
        job?.cancel();val bound=++generation;session=null
        mutable.value=ImageSearchUiState(image,source,preparing=true,message="Preparing image…")
        job=scope.launch {
            try {
                val input=prepare(image,source)
                if(bound!=generation)return@launch
                mutable.update {it.copy(message="Connecting privately…")}
                connect()
                if(bound!=generation)return@launch
                val next=create(input);session=next
                mutable.update {it.copy(preparing=false,message="")}
                next.loadNext {update->if(bound==generation)mutable.update {it.copy(feed=update)}}
            }catch(e: CancellationException){
                if(e is TimeoutCancellationException&&bound==generation)mutable.update {it.copy(preparing=false,error="The private connection did not become ready. No direct search was sent.")}
                else throw e
            }catch(e: Exception){if(bound==generation)mutable.update {it.copy(preparing=false,error=e.message ?: "Image search is unavailable")}}
        }
    }
    private fun run(action: suspend (ImageSearchSession,suspend (ImageSearchState)->Unit)->Unit) {
        val current=session ?: return
        if(job?.isActive==true)return
        val previous=job
        val bound=generation
        mutable.update {it.copy(error=null,stopped=false,preparing=true,message="Connecting privately…")}
        job=scope.launch {
            try {previous?.join();if(bound!=generation)return@launch;connect();if(bound!=generation)return@launch;mutable.update {it.copy(preparing=false,message="")};action(current){update->if(bound==generation)mutable.update {it.copy(feed=update)}}}
            catch(e: CancellationException){if(e is TimeoutCancellationException&&bound==generation)mutable.update {it.copy(preparing=false,error="The private connection did not become ready. No direct search was sent.")}else throw e}
            catch(e: Exception){if(bound==generation)mutable.update {it.copy(preparing=false,error=e.message ?: "Image search is unavailable")}}
        }
    }
    fun next()=run {session,publish->session.loadNext(publish)}
    fun retryEngine(engine: ImageSearchEngine)=run {session,publish->session.retryEngine(engine,publish)}
    fun retryMatch(target: Target)=run {session,publish->session.retryMatch(target,publish)}
    fun retry() {val current=state.value;if(session==null)current.image?.let {start(it,current.source)}else next()}
    fun stop() {
        job?.cancel();generation++
        mutable.update {it.copy(preparing=false,feed=it.feed.copy(loading=false),message="",stopped=true)}
    }
    fun close() {stop();session=null;mutable.value=ImageSearchUiState();release()}
}
