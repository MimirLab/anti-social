/* anti social, GPL-3.0-or-later. All discovery bridge traffic uses the protected gateway. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Typed results only; no publisher HTML, tracking scripts or raw graph retained. */
private object BridgeMemory {
    private val entries=linkedMapOf<String,BridgePage>()
    @Synchronized fun get(key:String)=entries[key]?.takeIf {System.currentTimeMillis()-it.checkedAt<15*60_000}
    private fun size(value:BridgePage)=2L*(value.account.url.length+value.description.length+value.links.sumOf {it.length}+value.neighbors.sumOf {it.account.url.length+it.account.name.length+it.detail.length+it.sourceUrl.length})
    @Synchronized fun put(key:String,value:BridgePage){
        if(size(value)>128*1024)return
        entries.entries.removeAll {System.currentTimeMillis()-it.value.checkedAt>=15*60_000}
        entries.remove(key);entries[key]=value
        while(entries.size>32||entries.values.sumOf(::size)>1024*1024)entries.remove(entries.keys.first())
    }
}
internal suspend fun Repository.discoveryBridge(account:PublicAccount,related:Boolean=true,force:Boolean=false):BridgePage=withContext(Dispatchers.IO) {
    awaitReady();require(DiscoveryBridges.supported(account)&&PublicAccounts.parse(account.url,routes)?.provider==account.provider);gateway.originalFallback=prefs.originalFallback
    val policy=gateway.currentPolicy();val originals=prefs.originalFallback
    val key=System.identityHashCode(this@discoveryBridge).toString()+"|"+adjacentStore.epoch()+"|"+policy+"|"+originals+"|"+PublicAccounts.key(account)+"|"+related
    if(!force)BridgeMemory.get(key)?.let {return@withContext it}
    suspend fun read(url:String,original:Boolean,limit:Int=DiscoveryBridges.MAX_DOCUMENT):Payload {
        check(policy==gateway.currentPolicy()&&originals==prefs.originalFallback){"Connection settings changed"}
        if(policy.tor&&!policy.ready)throw SourceFailure(HealthStatus.UNKNOWN,"Waiting for the selected Tor connection")
        if(original&&!originals)throw SourceFailure(HealthStatus.UNKNOWN,"Original-site fallback is disabled")
        if(original)gateway.registerOriginalUrl(url)
        val document=withTimeout(if(policy.tor)15_000 else 8_000) {gateway.extractorDocument(url,sourceUrl=account.url,maxBytes=limit.toLong(),headers=mapOf("Accept" to "text/html,application/json;q=0.9"),purpose=FetchPurpose.API)}
        return Payload(document.text,document.finalUrl,document.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty(),document.status)
    }
    val result=gateway.boundedMediaRead(maxBytes=8L*1024*1024,maxRequests=5) {
        when(account.provider) {
            "youtube"->{
                val target=PublicAccounts.target(account,routes) ?: error("Not a public channel")
                var result:BridgePage?=null
                if(account.id!=null)for(source in pool.sourcesFor(target,Capability.PROFILE,false).filter {it.frontend=="invidious"&&it.role!=SourceRole.ORIGINAL}.take(2)) {
                    try {val page=withTimeout(if(policy.tor)5000 else 2500){DiscoveryBridges.youtube(account,read(source.root.trimEnd('/')+"/api/v1/channels/"+account.id,false,512*1024),routes,System.currentTimeMillis())}
                        if(page.neighbors.isNotEmpty()||!related){result=page;break}
                    }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e}catch(_:Exception){}
                }
                result ?: DiscoveryBridges.youtube(account,read(account.url.trimEnd('/')+"/about",true),routes,System.currentTimeMillis())
            }
            "soundcloud"->{
                val identity=DiscoveryBridges.soundcloudIdentity(account,read(account.url,true,512*1024),routes,System.currentTimeMillis())
                val base="https://api-v2.soundcloud.com/users/"
                suspend fun api(path:String)=read(base+path+"?client_id="+identity.clientId+"&limit=30",true,512*1024)
                val links=try {DiscoveryBridges.soundcloudLinks(api(identity.urn+"/web-profiles"),account.url)}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;emptyList()}catch(_:Exception){emptyList()}
                val neighbors=if(!related)emptyList()else coroutineScope {
                    val featured=async {try {DiscoveryBridges.soundcloudNeighbors(identity.page.account,api(identity.userId+"/featured-profiles"),routes,true)}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;emptyList()}catch(_:Exception){emptyList()}}
                    val following=async {try {DiscoveryBridges.soundcloudNeighbors(identity.page.account,api(identity.userId+"/followings"),routes,false)}catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;emptyList()}catch(_:Exception){emptyList()}}
                    featured.await()+following.await()
                }
                identity.page.copy(links=(identity.page.links+links).distinct().take(24),neighbors=neighbors.distinctBy {PublicAccounts.key(it.account)}.take(30),detail="Public featured profiles and follows")
            }
            "mixcloud"->{
                val handle=account.url.toHttpUrlOrNull()!!.pathSegments.first {it.isNotBlank()}
                val root="https://api.mixcloud.com/$handle/"
                val profile=DiscoveryBridges.mixcloud(account,read(root+"?metadata=1",true,512*1024),routes,System.currentTimeMillis())
                if(!related)profile else {
                    val follows=DiscoveryBridges.mixcloud(profile.account,read(root+"following/?limit=30",true,512*1024),routes,System.currentTimeMillis(),true)
                    profile.copy(neighbors=follows.neighbors,detail=follows.detail)
                }
            }
            "twitch"->if(related)DiscoveryBridges.twitch(account,read("https://twitchmap.com/data.json",false,1024*1024),System.currentTimeMillis())
                else {
                    // TwitchMap supplies participation edges. Only declared public
                    // profile links can turn a Twitch neighbor into another identity.
                    val p=read(account.url,true,512*1024);val web=AccountLinkage.website(p,routes,System.currentTimeMillis())
                    BridgePage(account,links=web.publicAccounts.orEmpty().map {it.url},checkedAt=System.currentTimeMillis())
                }
            else->error("No qualified public discovery route for this provider")
        }
    }
    currentCoroutineContext().ensureActive();check(policy==gateway.currentPolicy())
    BridgeMemory.put(key,result);result
}

/** A provider's own declared website is a route back into readable social
 * accounts. Only the homepage's explicit social identity fields are retained. */
internal suspend fun Repository.bridgeWebsiteAccounts(page:BridgePage):List<Target> = withContext(Dispatchers.IO) {
    val policy=gateway.currentPolicy();val originals=prefs.originalFallback
    val website=page.links.firstOrNull {PublicAccounts.parse(it,routes)==null&&AccountLinkage.webUrl(it)!=null} ?: return@withContext emptyList()
    val u=website.toHttpUrlOrNull() ?: return@withContext emptyList()
    val root=u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    if(!originals)return@withContext emptyList()
    gateway.registerOriginalUrl(root)
    val d=withTimeout(if(policy.tor)12_000 else 7000){gateway.extractorDocument(root,sourceUrl=page.account.url,maxBytes=AccountLinkage.MAX_DOCUMENT.toLong(),purpose=FetchPurpose.SUPPLEMENTAL)}
    check(policy==gateway.currentPolicy()&&originals==prefs.originalFallback)
    require(d.status in 200..299){"The declared website did not return usable public content"}
    AccountLinkage.website(Payload(d.text,d.finalUrl,"text/html",d.status),routes,System.currentTimeMillis()).accounts.map {it.target}.filter(AdjacentDiscovery::valid).distinctBy(AdjacentDiscovery::key).take(20)
}
