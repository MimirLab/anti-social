/* anti social, GPL-3.0-or-later. Public connections, independent of ownership. */
package app.antisocial.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.ln

enum class AdjacentKind { FEATURED, FOLLOWS, SHARED_FOLLOWERS, SHARED_PARTICIPANTS, SECOND_HOP, INTERACTION, COMMUNITY, FORWARDED, MENTIONED, READING_LIST, COLLABORATION }
data class AdjacentEvidence(val kind:AdjacentKind,val owner:Target,val sourceUrl:String,val checkedAt:Long,
    val via:Target?=null,val count:Int=1,val sampleSize:Int=0,val detail:String="",val records:List<String> = emptyList(),val weight:Double=1.0,val witnesses:List<Target> = emptyList(),val method:String="")
data class AdjacentObservation(val profile:Profile,val evidence:AdjacentEvidence)
data class AdjacentCard(val key:String,val profile:Profile,val evidence:List<AdjacentEvidence>,val score:Double)
data class AdjacentCoverage(val source:String,val status:HealthStatus,val checkedAt:Long,val detail:String,val sampled:Int=0,val more:Boolean=false)
data class AdjacentSnapshot(val cards:List<AdjacentCard> = emptyList(),val coverage:List<AdjacentCoverage> = emptyList(),val loading:Boolean=false,
    val hasMore:Boolean=false,val requests:Int=0,val complete:Boolean=false)
enum class AdjacentOperation { FOLLOWING, FOLLOWERS, FEATURED, LOOKUP, BACKLINKS, INTERACTIONS, COMMUNITIES, AUTHORS, WEB, OPML, RECOMMENDATIONS }
data class AdjacentEndpoint(val source:Source,val url:String)
data class AdjacentRequest(val owner:Profile,val actor:Profile=owner,val operation:AdjacentOperation,val endpoints:List<AdjacentEndpoint>,
    val depth:Int=0,val audience:Boolean=false,val sampleSize:Int=0,val cursor:String?=null,val context:String="") {
    val key get()=operation.name+"|"+actor.target.canonicalUrl+"|"+owner.target.canonicalUrl+"|"+depth+"|"+audience+"|"+cursor.orEmpty()+"|"+context+"|"+endpoints.firstOrNull()?.url.orEmpty()
}
data class AdjacentDocument(val payload:Payload,val headers:Map<String,List<String>> = emptyMap(),val cachedPublisher:PublisherLinks?=null,val checkedAt:Long?=null)
data class AdjacentBatch(val observations:List<AdjacentObservation> = emptyList(),val next:List<AdjacentRequest> = emptyList(),val sampled:Int=0,
    val more:Boolean=false,val detail:String="Public relationship sample")

object AdjacentDiscovery {
    const val MAX_CARDS=160
    const val MAX_REQUESTS=24
    fun valid(target:Target)=ReaderScope.isFeedSource(target.platform)&&target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.FEED)&&
        RssSources.feedUrl(target.canonicalUrl)!=null
    fun key(target:Target):String {
        val u=target.canonicalUrl.toHttpUrlOrNull()
        val fold=target.platform in setOf(Platform.X,Platform.INSTAGRAM,Platform.THREADS,Platform.REDDIT,Platform.MASTODON,Platform.PIXELFED,Platform.TRUTH_SOCIAL,Platform.GAB)||
            target.platform==Platform.BLUESKY&&u?.pathSegments?.getOrNull(1)?.startsWith("did:")!=true
        val url=u?.newBuilder()?.fragment(null)?.apply {if(fold)encodedPath(u.encodedPath.lowercase(java.util.Locale.ROOT))}?.build()?.toString() ?: target.canonicalUrl.substringBefore('#')
        return target.platform.id+":"+url.trimEnd('/')
    }
    fun profileKey(profile:Profile)=if(profile.target.platform==Platform.BLUESKY&&profile.persistentId?.startsWith("did:")==true)"bluesky:"+profile.persistentId else key(profile.target)
    /** Exact author locations only. A display name is not an account identifier. */
    fun author(post:Post,routes:DarwinRoutes):Target? {
        val u=post.canonicalUrl.toHttpUrlOrNull() ?: return null;val parts=u.pathSegments.filter(String::isNotBlank)
        if(u.queryParameterNames.any {it in setOf("comment_id","reply_comment_id","cid")})return null
        val raw=when(post.platform) {
            Platform.X->parts.takeIf {it.size>=3&&it[1]=="status"&&it[0]!="i"}?.first()?.let {"https://x.com/$it"}
            Platform.BLUESKY->parts.takeIf {it.size>=4&&it[0]=="profile"&&it[2]=="post"}?.get(1)?.let {"https://bsky.app/profile/$it"}
            Platform.MASTODON,Platform.PIXELFED,Platform.TRUTH_SOCIAL->parts.firstOrNull()?.takeIf {it.startsWith('@')}?.let {"https://${u.host}/$it"}
            Platform.THREADS->parts.firstOrNull()?.takeIf {it.startsWith('@')}?.let {"https://www.threads.com/$it"}
            Platform.REDDIT->post.author.removePrefix("u/").takeIf {it.matches(Regex("[A-Za-z0-9_-]{3,20}"))&&it !in setOf("deleted","removed","AutoModerator") }?.let {"https://www.reddit.com/user/$it"}
            else->null
        } ?: return null
        return runCatching {routes.target(post.platform,raw)}.getOrNull()?.takeIf(::valid)
    }
    fun reason(e:AdjacentEvidence):String=when(e.kind) {
        AdjacentKind.FEATURED->"Featured by this account"
        AdjacentKind.FOLLOWS->"Followed by this account"
        AdjacentKind.SHARED_FOLLOWERS->"Followed by ${e.count} sampled followers"
        AdjacentKind.SHARED_PARTICIPANTS->"Shares ${e.count} observed commenters"
        AdjacentKind.SECOND_HOP->"Followed by ${e.count} accounts this account follows"
        AdjacentKind.INTERACTION->"${e.count} observed interactions"
        AdjacentKind.COMMUNITY->if(e.via==null)"Active in this community"else "Shares ${e.count} sampled participants"
        AdjacentKind.FORWARDED->"Shared from this account"
        AdjacentKind.MENTIONED->"Linked in ${e.count} public ${if(e.count==1)"post"else "posts"}"
        AdjacentKind.READING_LIST->e.detail.ifBlank {"Included in a published reading list"}
        AdjacentKind.COLLABORATION->"Named as a collaborator"
    }
    /** Passive seeds use exact public links and mention syntax, never display-name similarity. */
    fun observed(owner:Profile,page:Page,routes:DarwinRoutes,at:Long):List<AdjacentObservation> {
        val result=ArrayList<AdjacentObservation>()
        page.posts.take(80).forEach {post->
            val targets=(post.links.orEmpty().mapNotNull {routes.recognize(it)}.filter(::valid).map {AccountCandidate(it,"")}+MentionNames.find(post.body.take(16_384),post.platform,post.canonicalUrl,routes,post.links.orEmpty()).map {AccountCandidate(it.target,"")})
                .map {it.target}.filter {valid(it)&&key(it)!=key(owner.target)}.distinctBy(::key).take(24)
            targets.forEach {target->result+=AdjacentObservation(Profile(target,target.identity,displayNameSupplied=false),AdjacentEvidence(AdjacentKind.MENTIONED,owner.target,post.canonicalUrl,at,records=listOf(post.canonicalUrl),detail="An explicit account link; this does not imply agreement"))}
            post.publicRelations.orEmpty().filter {it.kind in PublicRelationKind.entries}.take(16).forEach {relation->
                val target=routes.recognize(relation.url)?.takeIf(::valid) ?: return@forEach
                if(key(target)!=key(owner.target))result+=AdjacentObservation(Profile(target,relation.name.ifBlank {target.identity},displayNameSupplied=relation.name.isNotBlank()),AdjacentEvidence(when(relation.kind){PublicRelationKind.FORWARD->AdjacentKind.FORWARDED;PublicRelationKind.COLLABORATOR->AdjacentKind.COLLABORATION;else->AdjacentKind.MENTIONED},owner.target,post.canonicalUrl,at,records=listOf(post.canonicalUrl)))
            }
        }
        return result.take(256)
    }
}

/** One on-demand neighborhood, not a global social crawl. Published rows retain
 * their positions; new evidence enriches them and new candidates append. */
class AdjacentSession(private val seed:Profile,private val routes:DarwinRoutes,
    private val read:suspend (AdjacentEndpoint)->AdjacentDocument,
    private val learning:AdaptiveRouting=AdaptiveRouting(),private val tor:Boolean=false,
    private val clock:()->Long=System::currentTimeMillis,private val timeoutMs:Long=18_000,
    initial:List<AdjacentObservation> = emptyList(),cached:AdjacentSnapshot?=null,
    private val onLearning:()->Unit={},private val maxRequests:Int=AdjacentDiscovery.MAX_REQUESTS) {
    private val load=Mutex();private val lock=Mutex()
    private val queue=ArrayDeque<AdjacentRequest>();private val queued=HashSet<String>();private val visited=HashSet<String>()
    private val observations=linkedMapOf<String,MutableList<AdjacentObservation>>()
    private val order=ArrayList<String>();private val coverage=linkedMapOf<String,AdjacentCoverage>()
    private val owners=linkedSetOf(AdjacentDiscovery.key(seed.target));private var requests=0;private var started=false
    private val served=HashMap<Platform,Int>()
    private val ownerIds=linkedSetOf(AdjacentDiscovery.profileKey(seed))
    init {
        cached?.cards?.take(AdjacentDiscovery.MAX_CARDS)?.forEach {card->order+=card.key;observations[card.key]=card.evidence.take(12).map {AdjacentObservation(card.profile,it)}.toMutableList()}
        cached?.coverage?.take(40)?.forEach {coverage[it.source]=it}
        initial.forEach(::accept)
        AdjacentSources.plan(seed,routes).forEach(::enqueue)
    }
    private fun enqueue(request:AdjacentRequest) {
        if(queue.size<192&&request.depth<=2&&request.endpoints.isNotEmpty()&&queued.add(request.key))queue.addLast(request)
    }
    suspend fun addBridge(profile:Profile,observed:List<AdjacentObservation> = emptyList(),onUpdate:(AdjacentSnapshot)->Unit={})=lock.withLock {
        if(AdjacentDiscovery.valid(profile.target)&&owners.size<40&&owners.add(AdjacentDiscovery.key(profile.target))) {
            ownerIds+=AdjacentDiscovery.profileKey(profile)
            order.removeAll {observations[it]?.firstOrNull()?.profile?.let {p->AdjacentDiscovery.key(p.target) in owners||AdjacentDiscovery.profileKey(p) in ownerIds}==true}
            observed.forEach(::accept)
            AdjacentSources.plan(profile,routes).forEach(::enqueue);onUpdate(snapshot(false))
        }
    }
    private fun accept(value:AdjacentObservation) {
        if(!AdjacentDiscovery.valid(value.profile.target)||AdjacentDiscovery.key(value.profile.target) in owners||AdjacentDiscovery.profileKey(value.profile) in ownerIds)return
        val key=AdjacentDiscovery.profileKey(value.profile)
        if(key !in observations&&observations.size>=320)return
        val values=observations.getOrPut(key){mutableListOf()}
        val e=value.evidence
        if(!e.weight.isFinite()||e.weight<=0||e.count !in 1..100_000||e.sourceUrl.length>8192)return
        if(e.via!=null&&values.any {old->old.evidence.kind==e.kind&&old.evidence.owner==e.owner&&old.evidence.witnesses.any {it==e.via}})return
        val edge=e.kind.name+"|"+AdjacentDiscovery.key(e.owner)+"|"+(e.via?.let(AdjacentDiscovery::key) ?: "")+"|"+e.records.sorted().joinToString()
        if(values.none {old->val o=old.evidence;edge==o.kind.name+"|"+AdjacentDiscovery.key(o.owner)+"|"+(o.via?.let(AdjacentDiscovery::key) ?: "")+"|"+o.records.sorted().joinToString()}&&values.size<24)values+=value
        val visible=values.any {it.evidence.kind !in setOf(AdjacentKind.SHARED_FOLLOWERS,AdjacentKind.SECOND_HOP,AdjacentKind.SHARED_PARTICIPANTS)}||values.flatMap {it.evidence.witnesses+listOfNotNull(it.evidence.via)}.map(AdjacentDiscovery::key).distinct().size>=2
        if(visible&&key !in order&&order.size<AdjacentDiscovery.MAX_CARDS)order+=key
    }
    private fun card(key:String):AdjacentCard {
        val values=observations.getValue(key);val profile=values.map {it.profile}.maxByOrNull {if(it.displayNameSupplied)3 else if(it.avatar!=null)2 else 1}!!
        val merged=values.groupBy {it.evidence.kind.name+"|"+AdjacentDiscovery.key(it.evidence.owner)}.values.map {group->
            val first=group.first().evidence
            if(first.kind in setOf(AdjacentKind.SHARED_FOLLOWERS,AdjacentKind.SHARED_PARTICIPANTS,AdjacentKind.SECOND_HOP,AdjacentKind.COMMUNITY)&&first.via!=null) {
                val witnesses=group.flatMap {it.evidence.witnesses+listOfNotNull(it.evidence.via)}.distinctBy(AdjacentDiscovery::key).take(12)
                first.copy(count=witnesses.size,witnesses=witnesses,records=group.flatMap {it.evidence.records}.distinct().take(8),weight=group.sumOf {it.evidence.weight})
            }
            else if(first.kind==AdjacentKind.MENTIONED)first.copy(count=group.flatMap {it.evidence.records}.distinct().size.coerceAtLeast(1),records=group.flatMap {it.evidence.records}.distinct().take(8))else first
        }.take(12)
        val score=merged.sumOf {e->when(e.kind){AdjacentKind.FEATURED,AdjacentKind.COLLABORATION->100.0;AdjacentKind.READING_LIST->85.0;AdjacentKind.SHARED_FOLLOWERS,AdjacentKind.SHARED_PARTICIPANTS->65.0+e.weight*8;AdjacentKind.SECOND_HOP->45.0+e.weight*6;AdjacentKind.INTERACTION->35.0+ln(e.count.toDouble()+1)*5;AdjacentKind.COMMUNITY->40.0;AdjacentKind.FOLLOWS->30.0;AdjacentKind.FORWARDED->25.0;AdjacentKind.MENTIONED->10.0+ln(e.count.toDouble()+1)}}
        return AdjacentCard(key,profile,merged,score)
    }
    private fun snapshot(loading:Boolean)=AdjacentSnapshot(order.map(::card),coverage.values.toList().takeLast(40),loading,queue.isNotEmpty()&&requests<maxRequests,requests,started&&!loading)
    suspend fun current()=lock.withLock {snapshot(false)}
    suspend fun loadNext(onUpdate:(AdjacentSnapshot)->Unit={}):AdjacentSnapshot=load.withLock {
        started=true
        var claimed=0
        lock.withLock {onUpdate(snapshot(true))}
        try {
            withTimeoutOrNull(timeoutMs) {supervisorScope {
                // Follow-ups enter the same two live lanes immediately. A slow
                // independent website never holds a completed lookup at a join.
                List(2) {launch {
                    while(currentCoroutineContext().isActive) {
                        val query=lock.withLock {
                            var next:AdjacentRequest?=null
                            while(queue.isNotEmpty()&&claimed<6&&requests<maxRequests&&next==null) {
                                // Breadth across providers precedes pagination and sampled
                                // audience expansion. Seven X roots cannot starve Reddit.
                                val candidate=queue.minWith(compareBy<AdjacentRequest> {it.depth}.thenBy {served[it.actor.target.platform] ?: 0}.thenBy {if(it.cursor==null)0 else 1})
                                queue.remove(candidate)
                                if(visited.add(candidate.key)){next=candidate;claimed++;served[candidate.actor.target.platform]=(served[candidate.actor.target.platform] ?: 0)+1}
                            };next
                        } ?: break
                        val candidates=query.endpoints.sortedWith(compareBy<AdjacentEndpoint> {if(it.source.role==SourceRole.ORIGINAL)1 else 0}.thenBy {learning.score(it.source,Capability.PROFILE,tor)})
                        var done=false
                        for(endpoint in candidates) {
                            if(!learning.eligible(endpoint.source,Capability.PROFILE,tor,query.actor.target))continue
                            val admitted=lock.withLock {if(requests>=maxRequests)false else {requests++;true}}
                            if(!admitted)break
                            val start=clock()
                            try {
                                // Archive aggregation is the deferred, slower lane. It can
                                // outlive a quick profile read without extending the block.
                                val deadline=if(endpoint.source.role==SourceRole.CACHE){if(tor)16_000L else 14_000L}else if(tor)12_000L else 7_000L
                                val document=withTimeout(deadline){read(endpoint)}
                                val observedAt=document.checkedAt ?: clock()
                                val batch=AdjacentSources.parse(query,endpoint,document,routes,observedAt)
                                val health=if(batch.sampled>0||batch.observations.isNotEmpty())HealthStatus.HEALTHY else HealthStatus.EMPTY
                                if(document.cachedPublisher==null)learning.observe(ProbeResult(endpoint.source,Capability.PROFILE,health,(clock()-start).coerceAtLeast(1),clock(),tor,batch.detail),query.actor.target)
                                lock.withLock {
                                    batch.observations.sortedWith(compareByDescending<AdjacentObservation> {when(it.evidence.kind){AdjacentKind.FEATURED,AdjacentKind.COLLABORATION->3;AdjacentKind.SHARED_FOLLOWERS,AdjacentKind.READING_LIST->2;else->1}}.thenBy {AdjacentDiscovery.key(it.profile.target)}).map {it.copy(evidence=it.evidence.copy(method=endpoint.source.frontend))}.forEach(::accept)
                                    batch.next.forEach(::enqueue)
                                    coverage[endpoint.source.key]=AdjacentCoverage(endpoint.source.frontend,health,observedAt,if(document.cachedPublisher!=null)"Cached publisher declarations. "+batch.detail else batch.detail,batch.sampled,batch.more)
                                    currentCoroutineContext().ensureActive();onUpdate(snapshot(true))
                                };done=true;break
                            }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e
                                failure(endpoint,query,HealthStatus.UNAVAILABLE,"Connection timed out",start,onUpdate)
                            }catch(e:Exception){failure(endpoint,query,(e as? SourceFailure)?.status ?: HealthStatus.UNAVAILABLE,e.message?.take(180) ?: "Source unavailable",start,onUpdate)}
                        }
                        if(!done)lock.withLock {if(query.endpoints.all {!learning.eligible(it.source,Capability.PROFILE,tor,query.actor.target)})coverage["cooldown:"+query.key]=AdjacentCoverage(query.actor.target.platform.label,HealthStatus.UNAVAILABLE,clock(),"A public route is cooling down; existing results remain available")}
                    }
                }}.joinAll()
            }}
        }finally {
            val active=currentCoroutineContext().isActive
            withContext(NonCancellable){lock.withLock {onLearning();if(active)onUpdate(snapshot(false))}}
        }
        lock.withLock {snapshot(false)}
    }
    private suspend fun failure(endpoint:AdjacentEndpoint,q:AdjacentRequest,status:HealthStatus,detail:String,start:Long,publish:(AdjacentSnapshot)->Unit) {
        if(status!=HealthStatus.UNKNOWN)learning.observe(ProbeResult(endpoint.source,Capability.PROFILE,status,(clock()-start).coerceAtLeast(1),clock(),tor,detail),q.actor.target)
        lock.withLock {coverage[endpoint.source.key]=AdjacentCoverage(endpoint.source.frontend,status,clock(),detail);currentCoroutineContext().ensureActive();publish(snapshot(true))}
    }
}
