package app.antisocial.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScreenState(val platform: Platform=Platform.X,val section: String="feed",val target: Target?=null,val page: Page?=null,val loading: Boolean=false,val error: String?=null,val progress: String="",val sourceCapability: Capability=Capability.TIMELINE,val searchFeed: SearchFeedState?=null,val settingsPage: SettingsPage=SettingsPage.HOME,val combined: Boolean=false,val subscriptionId: String?=null,val sourceTarget: Target?=null,val notice: String?=null)
class AntiSocialViewModel(val repo: Repository,discussionReader: ((CoroutineScope)->DiscussionReader)?=null,imageSearchFactory: ((CoroutineScope)->ImageSearchController)?=null,private val createSearch: (Target)->SearchFeed=repo::searchFeed): ViewModel() {
    private val initialReadingView=repo.prefs.readingView
    private val initialReadingPerson=repo.prefs.readingPerson
    val screen=MutableStateFlow(when(initialReadingView) {
        "all"->ScreenState(repo.prefs.activePlatform,combined=true)
        "person"->ScreenState(repo.prefs.activePlatform,section="subscription",combined=true,subscriptionId=initialReadingPerson)
        else->ScreenState(repo.prefs.activePlatform)
    })
    private val externalRequests=Channel<String>(Channel.BUFFERED)
    val externalLinks=externalRequests.receiveAsFlow()
    private val browserRequests=Channel<String>(Channel.BUFFERED)
    val browserLinks=browserRequests.receiveAsFlow()
    internal val articles=ArticleReaderController(repo,viewModelScope)
    internal val localSearch=LocalDiscoveryController(repo,viewModelScope)
    internal val adjacent=AdjacentController(repo,viewModelScope)
    fun openBrowser(url:String){viewModelScope.launch {browserRequests.send(url)}}
    val discussions=discussionReader?.invoke(viewModelScope) ?: DiscussionReader(repo,viewModelScope)
    val imageSearch=imageSearchFactory?.invoke(viewModelScope) ?: ImageSearchController(repo,viewModelScope)
    private var imageSearchReturn: ScreenState?=null
    private val stacks=Platform.entries.associateWith {mutableListOf<Target>()}
    private val pages=PageMemory()
    private val searches=java.util.LinkedHashMap<String,SearchFeed>(4,.75f,true)
    private fun searchKey(target: Target)=target.canonicalUrl+":"+repo.prefs.searchProvider.id
    private fun searchSession(target: Target,reset: Boolean=false): SearchFeed {
        val key=searchKey(target)
        if(reset)searches.remove(key)
        return searches.getOrPut(key){createSearch(target)}.also {while(searches.size>3)searches.remove(searches.keys.first())}
    }
    private var request: Job?=null
    private var directoryRequest: Job?=null
    private var directoryOwner: SearchFeed?=null
    private val searchPublication=Mutex()
    private fun cancelRequests(){request?.cancel();directoryRequest?.cancel()}
    private val settingsStack=mutableListOf(SettingsPage.HOME)
    private var settingsReturnSection="feed"
    private var settingsReturnState: ScreenState?=null
    private var sourceSettingsReturn: List<SettingsPage>?=null
    private var generation=0
    private var linkedVideoReturn: ScreenState?=null
    private var combinedReturn: ScreenState?=null
    private var combinedEntry: Target?=null
    private fun fresh(target: Target): Boolean {
        if(target.kind==TargetKind.SEARCH&&!searches.containsKey(searchKey(target)))return false
        if(!pages.fresh(target))return false
        val frontend=pages.get(target)?.provenance?.source?.frontend
        return target.kind!=TargetKind.SEARCH||frontend !in PublicSearchAdapter.frontends||frontend==repo.prefs.searchProvider.frontend||(frontend=="librey"&&repo.prefs.searchProvider==SearchProvider.DARWIN)
    }
    init {
        val restoration=viewModelScope.launch {
            val restored=withContext(Dispatchers.Default){Platform.entries.map {p->val url=repo.prefs.lastUrl(p);Triple(p,url,runCatching {repo.routes.recognize(url,p)}.getOrNull())}}
            restored.forEach {(p,url,target)->if(target!=null&&repo.prefs.lastUrl(p)==url&&stacks.getValue(p).isEmpty())stacks.getValue(p).add(target)}
            val initial=stacks.getValue(screen.value.platform).lastOrNull()
            if(generation==0&&initial!=null&&initialReadingView in setOf("","target")){stacks.getValue(initial.platform).clear();open(initial)}
            if(initialReadingView=="person") {
                repo.awaitReady()
                val exists=withContext(Dispatchers.IO){repo.dao.allFollows().any {row->runCatching {repo.gson.fromJson(row.payload,Follow::class.java)}.getOrNull()?.let {ReaderScope.active(it)&&Subscriptions.id(it)==initialReadingPerson}==true}}
                if(generation==0&&!exists)following()
            }
        }
        viewModelScope.launch {try {restoration.join();repo.awaitReady();screen.first {!it.loading};if(repo.prefs.intervalMinutes>0)repo.refresh()}catch(e: CancellationException){throw e}catch(_: Exception){}}
    }
    /** Save reader navigation at the user action, never from delayed composition effects. */
    private fun rememberReading(state: ScreenState=screen.value) {
        if(state.target?.platform==Platform.YOUTUBE){linkedVideoReturn?.let {rememberReading(it)};return}
        when {
            state.target!=null->{repo.prefs.readingView="target";repo.prefs.readingPerson=""}
            state.section=="subscription"->{repo.prefs.readingPerson=state.subscriptionId.orEmpty();repo.prefs.readingView="person"}
            state.section=="feed"->{repo.prefs.readingView=if(state.combined)"all"else "following";repo.prefs.readingPerson=""}
        }
    }
    private fun retainCurrent(){val state=screen.value;state.target?.let {target->state.page?.let {if(!state.loading||pages.get(target)==null)pages.put(target,it,complete=!state.loading)}}}
    fun openInput(platform: Platform,value: String,searching: Boolean=false,scope: SearchScope?=null) {
        if(value.trim().startsWith("https://",true)||value.trim().startsWith("http://",true)) {openLink(value,explicit=true,hint=platform);return}
        if(!ReaderScope.isFeedSource(platform)){videoOnlyNotice();return}
        cancelRequests();val bound=++generation
        screen.value=screen.value.copy(loading=true,error=null,progress="Opening link…")
        request=viewModelScope.launch {
            try {val target=withContext(Dispatchers.Default){repo.routes.openInput(platform,value,searching,scope)};if(generation==bound){request=null;open(target)}}
            catch(e: CancellationException){throw e}catch(e: Exception){if(generation==bound)screen.value=screen.value.copy(loading=false,progress="",error=e.message)}
        }
    }
    /** Incoming intents and pasted URLs use the same cancellable Darwin entry path. */
    fun openLink(value: String,explicit: Boolean=false,hint: Platform?=null) {
        articles.close()
        retainCurrent();cancelRequests();val bound=++generation
        screen.value=screen.value.copy(loading=true,error=null,progress="Opening shared link…")
        request=viewModelScope.launch {
            try {
                val result=repo.resolveIncoming(value,explicit,hint)
                if(generation!=bound)return@launch
                when(result) {
                    is IncomingLinkResult.Open -> {
                        if(!explicit&&result.target.platform!=Platform.YOUTUBE&&!ReaderScope.handles(result.target,repo.prefs.selected)) {
                            screen.value=screen.value.copy(loading=false,progress="")
                            externalRequests.send(result.target.canonicalUrl)
                        }else {request=null;open(result.target)}
                    }
                    is IncomingLinkResult.External -> {screen.value=screen.value.copy(loading=false,progress="");externalRequests.send(result.url)}
                    is IncomingLinkResult.Unavailable -> if(result.message==ReaderScope.VIDEO_ONLY)videoOnlyNotice()else screen.value=screen.value.copy(loading=false,progress="",error=result.message)
                }
            }catch(e: CancellationException){throw e}
            catch(e: Exception){if(generation==bound)screen.value=screen.value.copy(loading=false,progress="",error=e.message ?: "This link could not be opened")}
        }
    }
    private fun videoOnlyNotice(){screen.value=screen.value.copy(loading=false,progress="",error=null,notice=ReaderScope.VIDEO_ONLY)}
    fun dismissNotice(){screen.value=screen.value.copy(notice=null)}
    fun platform(p: Platform,restore: Boolean=true) {
        if(!ReaderScope.isFeedSource(p)){videoOnlyNotice();return}
        if(restore)linkedVideoReturn=null
        if(restore){leaveImageSearch();combinedReturn=null;combinedEntry=null}
        retainCurrent();cancelRequests();generation++;repo.prefs.activePlatform=p
        val target=stacks.getValue(p).lastOrNull()
        screen.value=ScreenState(p,target=target,page=target?.let {pages.get(it)},searchFeed=target?.takeIf {it.kind==TargetKind.SEARCH}?.let {searches[searchKey(it)]?.state})
        rememberReading()
        if(restore&&target!=null) {
            if(pages.get(target)==null||!fresh(target))open(target)
            else if(target.kind==TargetKind.SEARCH)searches[searchKey(target)]?.takeIf {!it.state.paused}?.let {startSearchDirectory(target,it,generation)}
        }
    }
    /** A channel's platform tab narrows its feed, never restores an old page. */
    fun feedPlatform(p: Platform) {
        if(!ReaderScope.isFeedSource(p)){videoOnlyNotice();return}
        linkedVideoReturn=null
        leaveImageSearch();combinedReturn=null;combinedEntry=null
        retainCurrent();cancelRequests();generation++;repo.prefs.activePlatform=p
        screen.value=ScreenState(p)
        rememberReading()
    }
    fun section(name: String) {
        if(name !in setOf("settings","pools")&&(screen.value.section=="image-search"||combinedReturn?.section=="image-search")) {
            leaveImageSearch();combinedReturn=null;combinedEntry=null
        }
        if(name=="pools")sourceSettingsReturn=settingsStack.toList().takeIf {screen.value.section=="settings"}
        if(name=="settings"){
            if(screen.value.section !in setOf("settings","pools")){settingsReturnSection=screen.value.section;settingsReturnState=screen.value.takeIf {it.section in setOf("subscription","image-search","discover")||it.section=="feed"&&it.target==null&&(it.combined||repo.channels.selected.value!=null)}}
            settingsStack.clear();settingsStack.add(SettingsPage.HOME)
        }
        retainCurrent();cancelRequests();generation++
        val capability=when(screen.value.target?.kind){TargetKind.SEARCH->Capability.SEARCH;TargetKind.POST,TargetKind.THREAD->Capability.POST;TargetKind.MEDIA->Capability.MEDIA;else->Capability.TIMELINE}
        screen.value=screen.value.copy(section=name,target=null,page=null,loading=false,error=null,progress="",searchFeed=null,sourceCapability=if(name=="pools")capability else screen.value.sourceCapability,settingsPage=if(name=="settings")SettingsPage.HOME else screen.value.settingsPage,sourceTarget=if(name=="pools")screen.value.target else null)
        if(name=="feed")rememberReading()
    }
    fun settings(page: SettingsPage) {
        if(screen.value.section!="settings")section("settings")
        if(settingsStack.lastOrNull()!=page)settingsStack.add(page)
        screen.value=screen.value.copy(settingsPage=page)
    }
    fun following() {
        if(screen.value.platform==Platform.YOUTUBE){stacks.getValue(Platform.YOUTUBE).clear();repo.prefs.lastUrl(Platform.YOUTUBE,"");screen.value=ScreenState(repo.prefs.activePlatform)}
        linkedVideoReturn=null
        stacks.getValue(screen.value.platform).clear();repo.prefs.lastUrl(screen.value.platform,"");section("feed");screen.value=screen.value.copy(combined=false,subscriptionId=null);rememberReading()}
    private fun leaveImageSearch() {if(imageSearchReturn!=null){imageSearch.close();imageSearchReturn=null}}
    fun combined() {leaveImageSearch();combinedReturn=null;combinedEntry=null;retainCurrent();cancelRequests();generation++;screen.value=ScreenState(screen.value.platform,combined=true);rememberReading()}
    fun subscription(id: String) {section("subscription");screen.value=screen.value.copy(subscriptionId=id,combined=true);rememberReading()}
    fun searchImage(image: Media,source: String) {
        if(screen.value.section!="image-search")imageSearchReturn=screen.value.copy(loading=false,error=null)
        retainCurrent();cancelRequests();generation++;combinedReturn=null;combinedEntry=null
        screen.value=ScreenState(screen.value.platform,section="image-search")
        imageSearch.start(image,source)
    }
    fun open(target: Target,force: Boolean=false) {
        if(!ReaderScope.canOpen(target)){videoOnlyNotice();return}
        if(target.platform==Platform.YOUTUBE&&screen.value.target?.platform!=Platform.YOUTUBE)linkedVideoReturn=screen.value.copy(loading=false,progress="",error=null,notice=null)
        if(screen.value.target==null&&(screen.value.section in setOf("subscription","image-search","discover")||screen.value.section=="feed"&&(screen.value.combined||repo.channels.selected.value!=null))){combinedReturn=screen.value.copy(loading=false,error=null);combinedEntry=target}
        val currentPage=if(screen.value.section=="image-search")imageSearch.state.value.feed.page else screen.value.page
        val inline=if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))currentPage?.posts?.firstOrNull {it.platform==target.platform&&ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}?.let {Page(listOf(it),provenance=it.provenance)}else if(target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY))currentPage?.accounts?.firstOrNull {it.target.canonicalUrl.equals(target.canonicalUrl,true)}?.let {Page(emptyList(),profile=it,provenance=currentPage.provenance)}else null
        retainCurrent();cancelRequests();generation++
        if(target.platform!=screen.value.platform&&target.platform!=Platform.YOUTUBE){val returning=combinedReturn;val entry=combinedEntry;platform(target.platform,false);combinedReturn=returning;combinedEntry=entry}
        val stack=stacks.getValue(target.platform);if(stack.lastOrNull()!=target)stack.add(target);repo.prefs.lastUrl(target.platform,target.canonicalUrl)
        val cached=pages.get(target) ?: inline
        val fetch=force||!fresh(target)
        screen.value=ScreenState(target.platform,target=target,page=cached,loading=fetch,searchFeed=target.takeIf {it.kind==TargetKind.SEARCH}?.let {searches[searchKey(it)]?.state})
        rememberReading()
        if(!fetch) {
            if(target.kind==TargetKind.SEARCH)searches[searchKey(target)]?.takeIf {!it.state.paused}?.let {startSearchDirectory(target,it,generation)}
            return
        }
        val boundGeneration=generation
        request=viewModelScope.launch {
            try {
                if(cached==null)repo.cachedPage(target)?.let {local->if(generation==boundGeneration){pages.put(target,local);screen.value=screen.value.copy(page=local)}}
                if(target.kind==TargetKind.SEARCH){readSearch(target,force,boundGeneration);return@launch}
                val page=repo.open(target,onPage={partial->if(generation==boundGeneration){val retained=if(partial.posts.isEmpty()&&!screen.value.page?.posts.isNullOrEmpty())partial.copy(posts=screen.value.page!!.posts)else partial;pages.put(target,retained,complete=false);screen.value=screen.value.copy(page=retained)}}){message->viewModelScope.launch {if(generation==boundGeneration)screen.value=screen.value.copy(progress=message)}}
                if(generation==boundGeneration){pages.put(target,page);screen.value=screen.value.copy(page=page,loading=false,progress="")}
            }catch(e: CancellationException){throw e}catch(e: Exception){if(generation==boundGeneration)screen.value=screen.value.copy(loading=false,error=e.message ?: "Content unavailable",progress="")}
        }
    }
    private suspend fun readSearch(target: Target,reset: Boolean,bound: Int) {
        val session=searchSession(target,reset)
        startSearchDirectory(target,session,bound)
        session.loadNext {publishSearch(target,session,bound)}
    }
    private fun startSearchDirectory(target: Target,session: SearchFeed,bound: Int) {
        if(directoryOwner!==session||directoryRequest?.isActive!=true) {
            val previous=directoryRequest
            previous?.cancel();directoryOwner=session
            directoryRequest=viewModelScope.launch {
                previous?.join()
                session.loadDirectory {publishSearch(target,session,bound)}
            }
        }
    }
    private suspend fun publishSearch(target: Target,session: SearchFeed,bound: Int)=searchPublication.withLock {
        if(generation!=bound||screen.value.target!=target)return@withLock
        var update=session.state
        val hasData=update.page.posts.isNotEmpty()||update.page.accounts.isNotEmpty()
        if(hasData&&!update.loading&&update.error==null) {
            repo.store(update.page,allowPurged=true);repo.rememberPage(target,update.page)
        }
        if(generation!=bound||screen.value.target!=target)return@withLock
        // Directory and post jobs can advance during database work. Publish the
        // current combined snapshot, never the callback's older copy.
        update=session.state
        val page=if(update.page.posts.isNotEmpty()||update.page.accounts.isNotEmpty())update.page else screen.value.page?.takeIf {it.posts.isNotEmpty()||it.accounts.isNotEmpty()}
        screen.value=screen.value.copy(page=page,searchFeed=update,loading=update.loading,error=update.error,progress=if(update.loading)"Loading your search feed…"else "")
        if(page!=null)pages.put(target,page,complete=!update.loading&&update.error==null)
    }
    fun stopLoading(){
        cancelRequests();generation++
        val current=screen.value
        val search=current.target?.takeIf {it.kind==TargetKind.SEARCH}
        val session=search?.let {searches[searchKey(it)]}
        session?.pause()
        screen.value=current.copy(loading=false,searchFeed=if(search!=null)session?.state ?: current.searchFeed?.copy(loading=false,paused=true,error=null)else current.searchFeed?.copy(loading=false),progress="",error=if(search!=null||current.page!=null)null else "Loading stopped")
    }
    suspend fun clearCache(){pages.clear();searches.clear();discussions.clear();repo.cleanup(true)}
    fun back(): Boolean {
        val state=screen.value
        if(state.section=="feed"&&state.target?.platform==Platform.YOUTUBE&&linkedVideoReturn!=null&&stacks.getValue(Platform.YOUTUBE).size<=1) {
            cancelRequests();generation++;stacks.getValue(Platform.YOUTUBE).clear();repo.prefs.lastUrl(Platform.YOUTUBE,"")
            screen.value=linkedVideoReturn!!;linkedVideoReturn=null;combinedReturn=null;combinedEntry=null;rememberReading();return true
        }
        if(state.section=="feed"&&state.target==combinedEntry&&combinedReturn!=null){cancelRequests();generation++;val stack=stacks.getValue(state.platform);if(stack.lastOrNull()==state.target)stack.removeAt(stack.lastIndex);repo.prefs.lastUrl(state.platform,stack.lastOrNull()?.canonicalUrl.orEmpty());screen.value=combinedReturn!!;combinedReturn=null;combinedEntry=null;rememberReading();return true}
        if(state.section=="image-search"){imageSearch.close();screen.value=imageSearchReturn ?: ScreenState(state.platform);imageSearchReturn=null;return true}
        if(state.section=="subscription"){section("follows");return true}
        if(state.section=="settings"&&state.settingsPage!=SettingsPage.HOME){if(settingsStack.size>1)settingsStack.removeAt(settingsStack.lastIndex);screen.value=state.copy(settingsPage=settingsStack.last());return true}
        if(state.section=="pools"&&sourceSettingsReturn!=null){val stack=sourceSettingsReturn!!;sourceSettingsReturn=null;section("settings");settingsStack.clear();settingsStack.addAll(stack);screen.value=screen.value.copy(settingsPage=settingsStack.last());return true}
        if(state.section=="settings"&&settingsReturnState!=null){screen.value=settingsReturnState!!.copy(platform=state.platform,loading=false,error=null);settingsReturnState=null;rememberReading();return true}
        if(state.section=="settings"&&settingsReturnSection in setOf("saved","follows")){section(settingsReturnSection);return true}
        if(state.section!="feed"){val previous=stacks.getValue(state.platform).lastOrNull();if(previous!=null)open(previous)else section("feed");return true}
        val stack=stacks.getValue(state.platform)
        if(stack.isNotEmpty()){stack.removeAt(stack.lastIndex);val previous=stack.lastOrNull();if(previous==null)following() else {stack.removeAt(stack.lastIndex);open(previous)};return true}
        return false
    }
    fun refresh(group: String="") {
        val target=screen.value.target
        if(target!=null){open(target,true);return}
        val channelId=repo.channels.selected.value.takeIf {screen.value.section=="feed"}
        val channel=channelId?.let {id->repo.channels.channels.value.firstOrNull {it.id==id}}
        if(channelId!=null&&(channel==null||repo.channels.error.value!=null)) {
            screen.value=screen.value.copy(error="Choose a valid channel before refreshing.")
            return
        }
        cancelRequests();val boundGeneration=++generation;val platform=screen.value.platform
        screen.value=screen.value.copy(loading=true,error=null)
        val all=screen.value.combined;val subscription=screen.value.subscriptionId.takeIf {screen.value.section=="subscription"}
        request=viewModelScope.launch {try {repo.refresh(if(all)null else platform,personId=subscription,group=if(channel==null)group else "",channel=channel)}catch(e: CancellationException){throw e}catch(e: Exception){if(generation==boundGeneration)screen.value=screen.value.copy(error=e.message)}finally {if(generation==boundGeneration)screen.value=screen.value.copy(loading=false)}}
    }
    fun loadMore() {
        val current=screen.value
        val target=current.target ?: return
        if(target.kind==TargetKind.SEARCH) {
            if(current.loading||current.searchFeed?.let {it.hasMore||it.paused}!=true)return
            val bound=generation
            val previous=request
            screen.value=current.copy(loading=true,error=null,searchFeed=current.searchFeed.copy(loading=true,error=null,paused=false))
            request=viewModelScope.launch {try {previous?.join();readSearch(target,false,bound)}catch(e: CancellationException){throw e}catch(e: Exception){if(generation==bound)screen.value=screen.value.copy(loading=false,error=e.message)}}
            return
        }
        val page=current.page ?: return
        val cursor=page.next ?: return
        if(current.loading)return
        val boundGeneration=generation
        screen.value=current.copy(loading=true,error=null)
        request=viewModelScope.launch {
            try {
                val next=repo.pool.continuePage(target,page,originalFallback=repo.prefs.originalFallback)
                repo.store(next)
                if(generation==boundGeneration) {
                    val added=next.posts.any {post->page.posts.none {repo.key(it)==repo.key(post)}}||next.collections.any {item->page.collections.none {it.target==item.target}}||next.accounts.any {account->page.accounts.none {it.target==account.target}}
                    val merged=page.copy(posts=(page.posts+next.posts).distinctBy {repo.key(it)},collections=(page.collections+next.collections).distinctBy {it.target.canonicalUrl},accounts=(page.accounts+next.accounts).distinctBy {it.target.canonicalUrl},next=next.next?.takeIf {it!=cursor&&added},notice=if(!added&&next.next!=null)"This source supplied no new results."else page.notice)
                    pages.put(target,merged)
                    screen.value=screen.value.copy(page=merged,loading=false)
                    repo.rememberPage(target,merged)
                }
            }catch(e: CancellationException){throw e}catch(e: Exception){if(generation==boundGeneration)screen.value=screen.value.copy(loading=false,error=e.message)}
        }
    }
    fun launch(action: suspend ()->Unit) {viewModelScope.launch {try {action()}catch(e: CancellationException){throw e}catch(e: Exception){screen.value=screen.value.copy(error=e.message)}}}
    override fun onCleared(){localSearch.close();imageSearch.close();adjacent.stop();super.onCleared()}
    class Factory(private val repo: Repository): ViewModelProvider.Factory {override fun <T: ViewModel> create(modelClass: Class<T>): T { @Suppress("UNCHECKED_CAST") return AntiSocialViewModel(repo) as T }}
}
