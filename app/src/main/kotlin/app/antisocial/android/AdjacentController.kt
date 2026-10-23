/* anti social, GPL-3.0-or-later. One cancellable, progressively enriched neighborhood. */
package app.antisocial.android

import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AdjacentUiState(val seed:Profile?=null,val snapshot:AdjacentSnapshot=AdjacentSnapshot(),val preparing:Boolean=false,
    val findingBridges:Boolean=false,val bridges:List<Profile> = emptyList(),val identities:Map<String,AccountDiscovery> = emptyMap(),
    val processing:Set<String> = emptySet(),val error:String?=null) {val loading get()=preparing||findingBridges||snapshot.loading}

internal class AdjacentController(private val repo:Repository,private val scope:CoroutineScope,
    private val create:suspend (Profile,Page?,Boolean)->AdjacentRuntime=repo::adjacentSession,
    private val identities:suspend (Profile)->AccountDiscovery={repo.discoverAccounts(it.target,it)},
    private val next:suspend (AdjacentRuntime,(AdjacentSnapshot)->Unit)->AdjacentSnapshot=repo::adjacentNext) {
    private val mutable=MutableStateFlow(AdjacentUiState());val state=mutable.asStateFlow()
    private var owner:Job?=null;private var graph:Job?=null;private var bound=0;private var runtime:AdjacentRuntime?=null
    private var originalPage:Page?=null;private val visible=linkedSetOf<String>();private val checked=HashSet<String>()
    private val aliasJobs=linkedMapOf<String,Job>();private val gate=Semaphore(1)
    private var identityChecks=0
    fun start(profile:Profile,page:Page?=null,force:Boolean=false) {
        stop();originalPage=page;val generation=bound
        mutable.value=AdjacentUiState(profile,preparing=true)
        owner=scope.launch {
            try {
                val opened=create(profile,page,force);if(generation!=bound)return@launch;runtime=opened
                val initial=opened.session.current()
                mutable.update {it.copy(preparing=false,snapshot=initial)}
                if(opened.fresh&&!force)return@launch
                supervisorScope {
                    graph=launch {load(generation)}
                    launch {
                        if(!AccountLinkage.valid(profile.target))return@launch
                        mutable.update {it.copy(findingBridges=true)}
                        try {
                            val found=identities(profile)
                            for(candidate in safeAccounts(found).filter {it.target!=profile.target&&it.target.platform in setOf(Platform.X,Platform.BLUESKY,Platform.REDDIT,Platform.MASTODON,Platform.PIXELFED,Platform.TRUTH_SOCIAL,Platform.GAB)}.sortedBy {bridgeRank(it.target.platform)}.take(2)) {
                                if(generation!=bound)return@launch
                                val other=Profile(candidate.target,found.name,displayNameSupplied=false)
                                opened.session.addBridge(other){s->if(generation==bound)mutable.update {it.copy(snapshot=s.copy(loading=it.snapshot.loading,complete=s.complete&&!it.snapshot.loading))}}
                                mutable.update {it.copy(bridges=it.bridges+other)}
                            }
                        }catch(e:CancellationException){throw e}catch(_:Exception){/* The original neighborhood remains useful. */}
                        finally {if(generation==bound)mutable.update {it.copy(findingBridges=false)}}
                    }
                }
                // An identity result can arrive after the initial cheap block.
                if(generation==bound&&mutable.value.bridges.isNotEmpty()&&mutable.value.snapshot.cards.size<8)load(generation)
            }catch(e:CancellationException){throw e}catch(e:Exception){if(generation==bound)mutable.update {it.copy(preparing=false,findingBridges=false,error=e.message ?: "Public connections are temporarily unavailable")}}
            finally {if(generation==bound)resolveVisible()}
        }
    }
    fun more() {
        if(state.value.loading)return
        val generation=bound
        aliasJobs.values.forEach(Job::cancel);aliasJobs.clear();mutable.update {it.copy(processing=emptySet())}
        graph=scope.launch {try {load(generation)}catch(e:CancellationException){throw e}catch(e:Exception){if(generation==bound)mutable.update {it.copy(error=e.message ?: "This public lookup paused")}}finally {if(generation==bound)resolveVisible()}}
    }
    private suspend fun load(generation:Int) {
        val opened=runtime ?: return
        try {
            val result=next(opened){s->if(generation==bound)mutable.update {it.copy(snapshot=s,error=null)}}
            if(generation==bound)mutable.update {it.copy(snapshot=result)}
        }catch(e:CancellationException){throw e}catch(e:Exception){if(generation==bound)mutable.update {it.copy(snapshot=it.snapshot.copy(loading=false),error=e.message ?: "This public lookup paused")}}
    }
    fun visible(keys:List<String>) {
        visible.clear();visible+=keys.take(8)
        resolveVisible()
    }
    private fun resolveVisible() {
        if(state.value.loading)return
        val generation=bound
        for(key in visible.filter {it !in checked&&it !in aliasJobs}.take(3)) {
            val card=state.value.snapshot.cards.firstOrNull {it.key==key} ?: continue
            if(!AccountLinkage.valid(card.profile.target)){checked+=key;continue}
            aliasJobs[key]=scope.launch {
                try {gate.withPermit {
                    if(generation!=bound||key !in visible)return@withPermit
                    if(identityChecks>=6)return@withPermit
                    identityChecks++
                    mutable.update {it.copy(processing=it.processing+key)}
                    try {
                        val found=identities(card.profile)
                        if(generation==bound){checked+=key;mutable.update {it.copy(identities=it.identities+(key to found))}}
                    }catch(e:CancellationException){throw e}catch(_:Exception){if(generation==bound)checked+=key}
                    finally {if(generation==bound)mutable.update {it.copy(processing=it.processing-key)}}
                }}finally {if(generation==bound)aliasJobs.remove(key)}
            }
        }
    }
    fun refresh(){state.value.seed?.let {start(it,originalPage,true)}}
    fun stop() {
        bound++;owner?.cancel();graph?.cancel();aliasJobs.values.forEach(Job::cancel);aliasJobs.clear();visible.clear();checked.clear();runtime=null;identityChecks=0
        mutable.value=AdjacentUiState()
    }
    companion object {
        private fun bridgeRank(p:Platform)=when(p){Platform.BLUESKY->0;Platform.MASTODON,Platform.PIXELFED->1;Platform.X->2;Platform.REDDIT->3;else->4}
        /** A shared handle or an arbitrary bio link is never ownership evidence. */
        fun safeAccounts(value:AccountDiscovery):List<AccountCandidate> {
            val anchors=value.candidates.filter {it.kind in setOf(AccountLinkKind.ENTERED,AccountLinkKind.LOCAL)}.map {AdjacentDiscovery.key(it.target)}.toSet()
            val declarations=value.nameHints.filter {it.identityGroup?.matches(Regex("[a-f0-9]{32}"))==true}
            val groups=declarations.filter {AdjacentDiscovery.key(it.target) in anchors}.map {it.sourceUrl to it.identityGroup}.toSet()
            val linked=declarations.filter {(it.sourceUrl to it.identityGroup) in groups}.map {AdjacentDiscovery.key(it.target) to it.sourceUrl}.toSet()
            return value.candidates.filter {c->c.kind in setOf(AccountLinkKind.ENTERED,AccountLinkKind.LOCAL)||
                c.kind==AccountLinkKind.DIRECTORY&&c.sourceUrl?.let {it.startsWith("https://www.wikidata.org/wiki/")||it.startsWith("https://keybase.io/")}==true||
                c.kind==AccountLinkKind.WEBSITE&&(AdjacentDiscovery.key(c.target) to c.sourceUrl) in linked}
        }
    }
}
