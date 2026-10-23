/* anti social, GPL-3.0-or-later. Reuse public discussions already requested by the reader. */
package app.antisocial.android

import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.Gson
import java.io.File
import kotlin.math.ln
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class PublicParticipantBank(private val file:File,private val routes:DarwinRoutes,private val clock:()->Long=System::currentTimeMillis) {
    private data class Edge(val participant:Target,val owner:Target,val source:String,val at:Long)
    private val gson=Gson();private val edges=linkedMapOf<String,Edge>();private var generation=0L
    private var loaded=false
    private fun load() {if(loaded)return;loaded=true
      if(file.isFile&&file.length()>512*1024){file.delete();return}
      if(file.isFile&&file.length()<=512*1024)runCatching {
        val values=PublicRelations.json(file.readText()).asJsonArray
        values.take(2048).forEach {v->val edge=gson.fromJson(v,Edge::class.java)
            if(AdjacentDiscovery.valid(edge.owner)&&AdjacentDiscovery.valid(edge.participant)&&RssSources.feedUrl(edge.source)!=null&&edge.at in clock()-7*CachePolicy.DAY..clock()+60_000)edges[key(edge)]=edge
        }
      }.onFailure {edges.clear();file.delete()};trim()
    }
    private fun key(e:Edge)=AdjacentDiscovery.key(e.participant)+"|"+AdjacentDiscovery.key(e.owner)+"|"+e.source
    @Synchronized fun epoch()=generation
    @Synchronized fun observe(root:Post,page:Page,epoch:Long=generation) {
        if(epoch!=generation)return
        load()
        val owner=AdjacentDiscovery.author(root,routes) ?: return
        page.posts.take(120).filter {it.parentId!=null||ContentIdentity.key(it.platform,it.canonicalUrl)!=ContentIdentity.key(root.platform,root.canonicalUrl)}.forEach {post->
            val participant=AdjacentDiscovery.author(post,routes) ?: return@forEach
            if(AdjacentDiscovery.key(participant)==AdjacentDiscovery.key(owner))return@forEach
            val source=post.canonicalUrl.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString() ?: return@forEach
            if(source.length>2048)return@forEach
            val edge=Edge(participant,owner,source,clock());edges.remove(key(edge));edges[key(edge)]=edge
        }
        trim();persist()
    }
    @Synchronized fun related(profile:Profile):List<AdjacentObservation> {
        load();trim();val owners=mutableSetOf(AdjacentDiscovery.key(profile.target))
        val did=profile.persistentId
        if(profile.target.platform==Platform.BLUESKY&&did?.startsWith("did:")==true)owners+=AdjacentDiscovery.key(Target(Platform.BLUESKY,TargetKind.PROFILE,did,"https://bsky.app/profile/$did"))
        val seed=edges.values.filter {AdjacentDiscovery.key(it.owner) in owners}.map {AdjacentDiscovery.key(it.participant)}.toSet()
        if(seed.isEmpty())return emptyList()
        val degree=edges.values.groupBy {AdjacentDiscovery.key(it.participant)}.mapValues {(_,rows)->rows.map {AdjacentDiscovery.key(it.owner)}.distinct().size}
        return edges.values.filter {AdjacentDiscovery.key(it.participant) in seed&&AdjacentDiscovery.key(it.owner) !in owners&&(degree[AdjacentDiscovery.key(it.participant)] ?: 0)<=20}
            .distinctBy {AdjacentDiscovery.key(it.participant)+"|"+AdjacentDiscovery.key(it.owner)}.take(160).map {edge->
                AdjacentObservation(Profile(edge.owner,edge.owner.identity,displayNameSupplied=false),AdjacentEvidence(AdjacentKind.SHARED_PARTICIPANTS,profile.target,edge.source,edge.at,via=edge.participant,
                    detail="Shared participants in public threads already captured on this device; checked within seven days. Participation does not imply agreement.",records=listOf(edge.source),weight=1.0/ln((degree[AdjacentDiscovery.key(edge.participant)]!!+2).toDouble())))
            }
    }
    private fun trim(){edges.entries.removeAll {clock()-it.value.at>7*CachePolicy.DAY||it.value.at>clock()+60_000};while(edges.size>2048)edges.remove(edges.keys.first())}
    private fun persist(){var bytes=gson.toJson(edges.values).toByteArray();while(bytes.size>512*1024&&edges.isNotEmpty()){repeat(maxOf(1,edges.size/4)){edges.remove(edges.keys.first())};bytes=gson.toJson(edges.values).toByteArray()};if(edges.isEmpty())file.delete()else Repository.atomic(file,bytes)}
    @Synchronized fun cleanup(clear:Boolean){if(clear){generation++;loaded=true;edges.clear();file.delete()}else {load();val before=edges.size;trim();if(before!=edges.size)persist()}}
    @Synchronized fun bytes()=file.length().coerceAtLeast(0)
}
