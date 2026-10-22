/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.Gson
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Ordinary, expiring action evidence. It is neither a saved post nor exported
 * reader state; clearing cache may remove it without touching protected copies. */
class ShareRouteStore(private val repo: Repository,private val clock: ()->Long=System::currentTimeMillis) {
    private val evidence=DiskShareEvidence(File(repo.context.cacheDir,"share-route-evidence.json"),repo.gson,clock)
    private val reads=Semaphore(2)
    private val reader=ShareRouteReader(repo.gateway,repo.routes,repo.cleaner,clock)
    internal var validate: suspend (ShareRouteCandidate)->ShareRouteCheck=reader::check
    internal var discoveredSources: (Post)->List<Source> = {post->repo.pool.results(post.platform).filter {it.status==HealthStatus.HEALTHY&&it.source.role==SourceRole.ALTERNATE&&it.capability in setOf(Capability.POST,Capability.TIMELINE)}.map {it.source}.distinctBy {it.key}.take(2)}
    private val resolver=ShareRouteResolver(repo.routes,repo.cleaner,{validate(it)},evidence,
        originalAllowed={repo.prefs.originalFallback&&repo.gateway.originalFallback},tor={repo.gateway.currentPolicy().tor},clock=clock,
        alternatives={discoveredSources(it)})
    fun remember(page: Page)=resolver.remember(page)
    fun clear()=evidence.clear()
    suspend fun resolve(post: Post,editions: List<Post> = emptyList(),action: ShareLinkAction=ShareLinkAction.COPY): ShareRouteResult=withContext(Dispatchers.IO) {
        repo.awaitReady();repo.applyNetworkSettings(startTransport=false)
        val version=repo.archiveVersion(post) ?: post.archivedVersion?.takeIf {RecoveredArchives.validVersion(post,it,clock())}
        reads.withPermit {repo.gateway.boundedHealthRead(maxBytes=1024L*1024,maxRequests=3) {resolver.resolve(post.copy(archivedVersion=version),editions,action)}}
    }
    suspend fun resolve(url: String,platform: Platform?=null,action: ShareLinkAction=ShareLinkAction.OPEN): ShareRouteResult=withContext(Dispatchers.IO) {
        val canonical=repo.publicUrl(url,platform)
        val target=repo.routes.recognize(canonical,platform)
        val selected=target?.platform ?: Platform.RSS
        val key="${selected.id}:${ContentIdentity.key(selected,canonical)}"
        val payload=repo.dao.bookmarksByKeys(listOf(key)).firstOrNull()?.payload ?: repo.dao.post(key)?.payload
        val known=payload?.let {runCatching {repo.gson.fromJson(it,Post::class.java)}.getOrNull()}?.takeIf {it.platform==selected&&ContentIdentity.key(selected,it.canonicalUrl)==ContentIdentity.key(selected,canonical)}
        val source=target?.let {repo.pool.sourcesFor(it,Capability.POST).firstOrNull {candidate->candidate.role==SourceRole.ALTERNATE&&runCatching {DarwinRoutes.matches(URI(url),candidate.root)}.getOrDefault(false)}}
        val fallback=Post(ContentIdentity.key(selected,canonical),selected,canonical,"","",provenance=source?.let {Provenance(it,0,url)})
        resolve(known ?: fallback,action=action)
    }
}

private class DiskShareEvidence(private val file: File,private val gson: Gson,private val clock: ()->Long): ShareRouteMemory {
    private val records=linkedMapOf<String,ShareRouteEvidence>()
    init {runCatching {
        if(file.isFile&&file.length()<=256*1024)gson.fromJson(file.readText(),Array<ShareRouteEvidence>::class.java)?.take(128)?.forEach {entry->if(valid(entry))records[key(entry)]=entry}
    }}
    private fun key(value: ShareRouteEvidence)="${value.tor}|${value.originalUrl}|${value.url}"
    private fun valid(value: ShareRouteEvidence)=runCatching {value.status in ShareRouteStatus.entries&&value.checkedAt in (clock()-24*60*60_000L)..clock()&&ShareRouteUrls.safe(value.originalUrl)!=null&&ShareRouteUrls.safe(value.url)!=null}.getOrDefault(false)
    @Synchronized override fun get(originalUrl: String,url: String,tor: Boolean): ShareRouteEvidence? {
        records.entries.removeIf {!valid(it.value)}
        return records["$tor|$originalUrl|$url"]
    }
    @Synchronized override fun put(value: ShareRouteEvidence) {
        if(!valid(value))return
        records.entries.removeIf {!valid(it.value)};records[key(value)]=value
        while(records.size>128)records.remove(records.keys.first())
        var json=gson.toJson(records.values)
        while(json.toByteArray().size>256*1024&&records.size>1){records.remove(records.keys.first());json=gson.toJson(records.values)}
        runCatching {file.parentFile?.mkdirs();val temporary=File(file.parentFile,file.name+".tmp");temporary.writeText(json);if(!temporary.renameTo(file)){temporary.copyTo(file,overwrite=true);temporary.delete()}}
    }
    @Synchronized fun clear(){records.clear();file.delete();File(file.parentFile,file.name+".tmp").delete()}
}
