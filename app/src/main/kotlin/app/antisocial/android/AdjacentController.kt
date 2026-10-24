/* anti social, GPL-3.0-or-later. One cancellable, progressively enriched discovery deck. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AdjacentBridgeMethod(val key:String,val page:BridgePage,val owner:Profile,
    val resolved:Map<String,List<Profile>> = emptyMap(),val processing:Set<String> = emptySet(),val checked:Set<String> = emptySet(),val failed:Set<String> = emptySet())
data class AdjacentUiState(val seed:Profile?=null,val snapshot:AdjacentSnapshot=AdjacentSnapshot(),val preparing:Boolean=false,
    val findingBridges:Boolean=false,val bridges:List<Profile> = emptyList(),val identities:Map<String,AccountDiscovery> = emptyMap(),
    val processing:Set<String> = emptySet(),val error:String?=null,val bridgeMethods:List<AdjacentBridgeMethod> = emptyList(),
    val methodOrder:List<String> = emptyList()) {val loading get()=preparing||findingBridges||snapshot.loading||processing.isNotEmpty()||bridgeMethods.any {it.processing.isNotEmpty()}}

internal fun discoveryMethod(e:AdjacentEvidence)=e.owner.platform.id+"|"+e.kind.name
internal class AdjacentController(private val repo:Repository,private val scope:CoroutineScope,
    private val create:suspend (Profile,Page?,Boolean)->AdjacentRuntime=repo::adjacentSession,
    private val identities:(suspend (Profile)->AccountDiscovery)?=null,
    private val next:suspend (AdjacentRuntime,(AdjacentSnapshot)->Unit)->AdjacentSnapshot=repo::adjacentNext,
    private val roots:suspend (Target)->List<Target> =repo::linkedSubscriptionAccounts,
    private val bridge:suspend (PublicAccount,Boolean,Boolean)->BridgePage={a,related,force->repo.discoveryBridge(a,related,force)}) {
    private val mutable=MutableStateFlow(AdjacentUiState());val state=mutable.asStateFlow()
    private var owner:Job?=null;private var graph:Job?=null;private var bound=0;private var runtime:AdjacentRuntime?=null
    private var originalPage:Page?=null;private val visible=linkedSetOf<String>();private val checked=HashSet<String>()
    private val aliasJobs=linkedMapOf<String,Job>();private val gate=Semaphore(2)
    private var identityChecks=0;private var externalChecks=0
    private val externalResults=HashMap<String,List<Profile>>()
    private suspend fun identity(p:Profile,onUpdate:(AccountDiscovery)->Unit={}):AccountDiscovery = identities?.invoke(p)?.also(onUpdate) ?: repo.discoverRoutingAccounts(p.target,p,onUpdate)
    private fun publish(snapshot:AdjacentSnapshot) {
        mutable.update {s->val methods=snapshot.cards.flatMap {c->c.evidence.map(::discoveryMethod)}.distinct()
            s.copy(snapshot=snapshot,methodOrder=(s.methodOrder+methods).distinct(),error=null)}
    }
    fun start(profile:Profile,page:Page?=null,force:Boolean=false) {
        val previous=state.value.takeIf {force&&it.seed?.target?.let(AdjacentDiscovery::key)==AdjacentDiscovery.key(profile.target)}
        val previousKey=runtime?.key
        stop();originalPage=page;val generation=bound
        mutable.value=AdjacentUiState(profile,preparing=true,findingBridges=true)
        owner=scope.launch {
            try {
                val opened=create(profile,page,force);if(generation!=bound)return@launch;runtime=opened
                if(previous!=null&&opened.key==previousKey) {
                    val oldest=System.currentTimeMillis()-DiscoverySnapshots.RETAIN_MS
                    mutable.update {it.copy(identities=previous.identities,
                        bridgeMethods=previous.bridgeMethods.filter {method->method.page.checkedAt>=oldest}.map {method->method.copy(processing=emptySet(),checked=emptySet(),failed=emptySet())},
                        methodOrder=previous.methodOrder)}
                }
                val local=(listOf(profile.target)+roots(profile.target)).filter(AdjacentDiscovery::valid).distinctBy(AdjacentDiscovery::key).take(40)
                for(target in local.filter {it!=profile.target})opened.session.addBridge(Profile(target,profile.name,displayNameSupplied=false))
                mutable.update {it.copy(preparing=false,bridges=local.filter {it!=profile.target}.map {Profile(it,profile.name,displayNameSupplied=false)})}
                publish(opened.session.current())
                supervisorScope {
                    val rootScope=this;val providers=hashSetOf<String>();val publicSeen=hashSetOf<String>();val rootGate=Semaphore(2)
                    var pending=0
                    lateinit var enqueue:suspend (PublicAccount)->Unit
                    enqueue={account->
                        val target=PublicAccounts.target(account,repo.routes)
                        if(target!=null&&AdjacentDiscovery.valid(target)&&local.none {AdjacentDiscovery.key(it)==AdjacentDiscovery.key(target)}) {
                            val p=Profile(target,account.name.ifBlank {target.identity},displayNameSupplied=account.name.isNotBlank())
                            opened.session.addBridge(p){s->if(generation==bound)publish(s.copy(loading=state.value.snapshot.loading))}
                            mutable.update {it.copy(bridges=(it.bridges+p).distinctBy {AdjacentDiscovery.key(it.target)})}
                        }
                        // One identity expansion per provider. All local accounts still
                        // enter graph discovery; broad directories need not be reread
                        // for each of NASA's seven X departments.
                        if(target!=null&&(target.platform!=Platform.YOUTUBE||account.id!=null)&&AccountLinkage.validIdentity(target)&&providers.size<12&&providers.add(account.provider)) {
                            pending++
                            rootScope.launch {
                                try {rootGate.withPermit {
                                    val p=if(target==profile.target)profile else Profile(target,account.name.ifBlank {target.identity},displayNameSupplied=account.name.isNotBlank())
                                    coroutineScope {
                                        val updates=Channel<AccountDiscovery>(Channel.CONFLATED)
                                        val consumer=launch {for(found in updates) {
                                            val candidates=found.candidates.filter {it.kind!=AccountLinkKind.USERNAME}.map {c->PublicAccounts.from(c.target,kind=c.kind).copy(sourceUrl=c.sourceUrl)}+found.publicAccounts.orEmpty().filter {it.kind!=AccountLinkKind.USERNAME}
                                            for(a in candidates.distinctBy(PublicAccounts::key).take(40))if(generation==bound)enqueue(a)
                                        }}
                                        try {identity(p){updates.trySend(it)}}finally {updates.close();consumer.join()}
                                    }
                                }}catch(e:CancellationException){throw e}catch(_:Exception){}finally {pending--;if(generation==bound)mutable.update {it.copy(findingBridges=pending>0)}}
                            }
                        }
                        if(DiscoveryBridges.supported(account)&&publicSeen.size<8&&publicSeen.add(PublicAccounts.key(account))) {
                            pending++
                            rootScope.launch {
                                try {rootGate.withPermit {
                                    val result=bridge(account,true,force);if(generation!=bound)return@withPermit
                                    publicSeen+=PublicAccounts.key(result.account)
                                    enqueue(result.account)
                                    for(publication in result.publications)enqueue(publication)
                                    for(link in result.links)PublicAccounts.parse(link,repo.routes,source=result.account.url)?.let {enqueue(it)}
                                    for((kind,neighbors) in result.neighbors.groupBy {it.kind}) {
                                        val id="bridge:"+account.provider+"|"+kind.name
                                        mutable.update {s->val old=s.bridgeMethods.firstOrNull {it.key==id}
                                            val merged=if(old==null)AdjacentBridgeMethod(id,result.copy(neighbors=neighbors),profile)else old.copy(page=old.page.copy(neighbors=(old.page.neighbors+neighbors).distinctBy {PublicAccounts.key(it.account)}.take(60)))
                                            s.copy(bridgeMethods=s.bridgeMethods.filterNot {it.key==id}+merged,methodOrder=(s.methodOrder+id).distinct())}
                                        resolveExternal(id,neighbors.take(4),generation)
                                    }
                                }}catch(e:CancellationException){throw e}catch(_:Exception){}finally {pending--;if(generation==bound)mutable.update {it.copy(findingBridges=pending>0)}}
                            }
                        }
                    }
                    for(target in local)enqueue(PublicAccounts.from(target,if(target==profile.target)profile.name else "",AccountLinkKind.LOCAL))
                    mutable.update {it.copy(findingBridges=pending>0)}
                    graph=launch {
                        // Keep draining independent primary routes even when the
                        // first platform already supplied many rows. New roots can
                        // arrive while a block is in flight.
                        var blocks=0
                        do {
                            if(opened.session.current().hasMore&&blocks<12){load(generation);blocks++}
                            else if(pending>0)delay(100)else break
                        }while(generation==bound&&currentCoroutineContext().isActive&&(pending>0||opened.session.current().hasMore&&blocks<12))
                    }
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){if(generation==bound)mutable.update {it.copy(error=e.message ?: "Public connections are temporarily unavailable")}}
            finally {if(generation==bound){mutable.update {it.copy(preparing=false,findingBridges=false)};resolveVisible()}}
        }
    }
    private suspend fun load(generation:Int) {
        val opened=runtime ?: return
        try {val result=next(opened){s->if(generation==bound)publish(s)};if(generation==bound)publish(result)}
        catch(e:CancellationException){throw e}catch(e:Exception){if(generation==bound)mutable.update {it.copy(snapshot=it.snapshot.copy(loading=false),error=e.message ?: "This public lookup paused")}}
    }
    fun more() {if(state.value.snapshot.loading)return;val generation=bound;graph=scope.launch {load(generation);resolveVisible()}}
    fun visible(keys:List<String>) {visible.clear();visible+=keys.take(24);resolveVisible()}
    private fun resolveVisible() {
        val generation=bound
        for(method in state.value.bridgeMethods) {
            val neighbors=method.page.neighbors.filter {PublicAccounts.key(it.account) in visible&&PublicAccounts.key(it.account) !in method.checked&&PublicAccounts.key(it.account) !in method.processing}
            if(neighbors.isNotEmpty())resolveExternal(method.key,neighbors.take(4),generation)
        }
        if(state.value.snapshot.loading)return
        for(key in visible.filter {it !in checked&&it !in aliasJobs}.take(4)) {
            val card=state.value.snapshot.cards.firstOrNull {it.key==key} ?: continue
            if(!AccountLinkage.valid(card.profile.target)){checked+=key;continue}
            aliasJobs[key]=scope.launch {
                try {gate.withPermit {
                    if(generation!=bound||key !in visible||identityChecks>=16)return@withPermit
                    identityChecks++;mutable.update {it.copy(processing=it.processing+key)}
                    try {val found=identity(card.profile);if(generation==bound){checked+=key;mutable.update {it.copy(identities=it.identities+(key to found))}}}
                    catch(e:CancellationException){throw e}catch(_:Exception){if(generation==bound)checked+=key}
                    finally {if(generation==bound)mutable.update {it.copy(processing=it.processing-key)}}
                }}finally {if(generation==bound)aliasJobs.remove(key)}
            }
        }
    }
    private fun resolveExternal(method:String,neighbors:List<BridgeNeighbor>,generation:Int) {
        for(neighbor in neighbors) {
            val key=PublicAccounts.key(neighbor.account)
            externalResults[key]?.let {profiles->mutable.update {s->s.copy(bridgeMethods=s.bridgeMethods.map {if(it.key==method)it.copy(resolved=it.resolved+(key to profiles),checked=it.checked+key)else it})}}
            if(key in externalResults||key in aliasJobs||state.value.bridgeMethods.firstOrNull {it.key==method}?.checked?.contains(key)==true||externalChecks>=24)continue
            externalChecks++
            mutable.update {s->s.copy(bridgeMethods=s.bridgeMethods.map {if(it.page.neighbors.any {n->PublicAccounts.key(n.account)==key})it.copy(processing=it.processing+key)else it})}
            aliasJobs[key]=scope.launch {
                var finished=false;var failed=false
                try {gate.withPermit {
                    val page=bridge(neighbor.account,false,false)
                    var direct=page.links.mapNotNull {repo.routes.recognize(it)?.takeIf(AdjacentDiscovery::valid)}.distinctBy(AdjacentDiscovery::key)
                    fun delivered(targets:List<Target>) {
                        if(generation!=bound)return
                        val profiles=targets.filter {t->state.value.bridges.none {AdjacentDiscovery.key(it.target)==AdjacentDiscovery.key(t)}&&state.value.seed?.target?.let {AdjacentDiscovery.key(it)!=AdjacentDiscovery.key(t)}!=false}.distinctBy(AdjacentDiscovery::key).take(20).map {Profile(it,it.identity,displayNameSupplied=false)}
                        mutable.update {s->s.copy(bridgeMethods=s.bridgeMethods.map {if(it.page.neighbors.any {n->PublicAccounts.key(n.account)==key}) {
                            val retained=(it.resolved[key].orEmpty()+profiles).distinctBy {p->AdjacentDiscovery.key(p.target)}.take(20)
                            it.copy(resolved=it.resolved+(key to retained))
                        }else it})}
                    }
                    delivered(direct)
                    val target=PublicAccounts.target(page.account,repo.routes)
                    if(target!=null&&AccountLinkage.validIdentity(target)) {
                        identity(Profile(target,page.account.name.ifBlank {target.identity},page.description,links=page.links,displayNameSupplied=page.account.name.isNotBlank())) {found->delivered(direct+found.candidates.filter {it.kind!=AccountLinkKind.USERNAME&&AdjacentDiscovery.valid(it.target)}.map {it.target})}
                    }else {
                        if(direct.isEmpty()){direct=repo.bridgeWebsiteAccounts(page);delivered(direct)}
                        direct.firstOrNull()?.let {t->val found=identity(Profile(t,t.identity,displayNameSupplied=false));delivered(direct+safeAccounts(found).map {it.target})}
                    }
                };finished=true}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;failed=true;finished=true}catch(_:Exception){failed=true;finished=true}finally {
                    if(generation==bound&&finished) {
                        if(!failed)externalResults[key]=state.value.bridgeMethods.firstNotNullOfOrNull {it.resolved[key]}.orEmpty()
                        mutable.update {s->s.copy(bridgeMethods=s.bridgeMethods.map {if(it.page.neighbors.any {n->PublicAccounts.key(n.account)==key})it.copy(processing=it.processing-key,checked=it.checked+key,failed=if(failed)it.failed+key else it.failed-key)else it})}
                    }
                    if(generation==bound)aliasJobs.remove(key)
                }
            }
        }
    }
    fun refresh(){state.value.seed?.let {start(it,originalPage,true)}}
    fun stop() {
        bound++;owner?.cancel();graph?.cancel();aliasJobs.values.forEach(Job::cancel);aliasJobs.clear();visible.clear();checked.clear();runtime=null;identityChecks=0;externalChecks=0;externalResults.clear()
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
