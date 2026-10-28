/* anti social, GPL-3.0-or-later. Public observations, bounded ordinary cache. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.Gson
import java.io.File

internal class AdjacentStore(private val directory:File,private val clock:()->Long=System::currentTimeMillis) {
    private val gson=Gson();private var generation=0L
    data class Cached(val snapshot:AdjacentSnapshot,val fresh:Boolean)
    private data class Entry(val schema:Int=1,val at:Long,val snapshot:AdjacentSnapshot)
    @Synchronized fun epoch()=generation
    @Synchronized fun get(key:String):Cached? {
        val file=File(directory,Repository.hash(key)+".json")
        if(!file.isFile||file.length()>384*1024)return null
        val entry=runCatching {PublicRelations.json(file.readText()).let {gson.fromJson(it,Entry::class.java)}}.getOrNull()
        val valid=runCatching {
            entry!=null&&entry.schema==1&&entry.at in (clock()-6*60*60_000L)..(clock()+60_000)&&entry.snapshot.cards.size<=AdjacentDiscovery.MAX_CARDS&&entry.snapshot.coverage.size<=40&&
                entry.snapshot.cards.all {c->c.key.length<=1024&&AdjacentDiscovery.valid(c.profile.target)&&c.profile.name.length<=160&&c.evidence.size in 1..12&&c.score.isFinite()&&c.evidence.all {e->
                    e.kind in AdjacentKind.entries&&AdjacentDiscovery.valid(e.owner)&&e.count in 1..100_000&&e.sampleSize in 0..1000&&e.detail.length<=1000&&e.sourceUrl.length<=8192&&e.records.size<=8&&e.witnesses.size<=12&&e.witnesses.all(AdjacentDiscovery::valid)
                }}&&entry.snapshot.coverage.all {it.status in HealthStatus.entries&&it.detail.length<=1000}
        }.getOrDefault(false)
        if(!valid){file.delete();return null}
        file.setLastModified(clock());return Cached(DiscoverySnapshots.retained(entry!!.snapshot,clock()),clock()-entry.at<15*60_000L)
    }
    @Synchronized fun put(key:String,snapshot:AdjacentSnapshot,epoch:Long) {
        if(epoch!=generation||snapshot.loading||!snapshot.complete)return
        val value=DiscoverySnapshots.merge(get(key)?.snapshot,snapshot,clock())
        val bytes=gson.toJson(Entry(at=clock(),snapshot=value)).toByteArray()
        if(bytes.size>384*1024)return
        val file=File(directory,Repository.hash(key)+".json")
        Repository.atomic(file,bytes);cleanup(false,file.name)
    }
    @Synchronized fun cleanup(clear:Boolean,retain:String?=null) {
        if(clear)generation++
        var bytes=0L;var count=0
        // Some Android filesystems expose coarse modification times. Keep the
        // just-written snapshot even when all entries share the same timestamp.
        directory.listFiles().orEmpty().sortedWith(compareByDescending<File> {it.name==retain}.thenByDescending {it.lastModified()}.thenBy {it.name}).forEach {f->
            if(clear||!f.name.endsWith(".json")||clock()-f.lastModified()>2*CachePolicy.DAY||count>=48||bytes+f.length()>2*1024*1024)f.delete()
            else {bytes+=f.length();count++}
        }
    }
    @Synchronized fun bytes()=directory.listFiles().orEmpty().sumOf {it.length()}
}

internal data class AdjacentRuntime(val session:AdjacentSession,val key:String,val epoch:Long,val policy:NetworkPolicy,val fresh:Boolean,val named:MutableSet<String> = HashSet(),val followIds:Map<String,List<String>> = emptyMap())
