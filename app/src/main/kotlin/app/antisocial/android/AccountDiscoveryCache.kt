/* anti social, GPL-3.0-or-later. Ordinary, bounded public-link cache. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.Gson
import java.io.File

internal class AccountDiscoveryCache(private val directory:File,private val clock:()->Long=System::currentTimeMillis) {
    private val gson=Gson();private var generation=0L
    private data class Warm(val expires:Long,val links:AccountWebLinks,val bytes:Int)
    private val websites=linkedMapOf<String,Warm>()
    @Synchronized fun website(key:String):AccountWebLinks? {
        val entry=websites[key] ?: return null
        if(entry.expires<=clock()){websites.remove(key);return null}
        return entry.links
    }
    @Synchronized fun rememberWebsite(key:String,value:AccountWebLinks,epoch:Long) {
        if(epoch!=generation)return
        val bounded=value.copy(accounts=value.accounts.take(20),websites=value.websites.take(2),nameHints=value.nameHints.take(40))
        val bytes=gson.toJson(bounded).toByteArray().size
        if(bytes>32*1024)return
        websites.remove(key);websites[key]=Warm(clock()+30*60_000L,bounded,bytes)
        while(websites.size>64||websites.values.sumOf {it.bytes}>256*1024)websites.remove(websites.keys.first())
    }
    private data class Entry(val expires:Long,val discovery:AccountDiscovery)
    @Synchronized fun epoch()=generation
    @Synchronized fun get(key:String):AccountDiscovery? {
        val file=File(directory,Repository.hash(key)+".json")
        if(!file.isFile||file.length()>64*1024)return null
        val entry=runCatching {gson.fromJson(file.readText(),Entry::class.java)}.getOrNull()
        if(entry==null||runCatching {entry.expires<=clock()||entry.discovery.nameHints.size>40||entry.discovery.candidates.size !in 1..48||entry.discovery.candidates.any {!AccountLinkage.validIdentity(it.target)||it.kind !in AccountLinkKind.entries}||entry.discovery.publicAccounts.orEmpty().size>48||entry.discovery.publicAccounts.orEmpty().any {it.provider.length !in 1..32||it.url.length>8192||RssSources.feedUrl(it.url)==null||it.anchors.size>24||it.links.size>24||it.kind !in AccountLinkKind.entries}}.getOrDefault(true)){file.delete();return null}
        file.setLastModified(clock());return entry.discovery
    }
    @Synchronized fun put(key:String,value:AccountDiscovery,epoch:Long) {
        if(epoch!=generation||!value.complete)return
        val ttl=if(value.candidates.size>1)6*60*60_000L else 15*60_000L
        val bytes=gson.toJson(Entry(clock()+ttl,value)).toByteArray()
        if(bytes.size>64*1024)return
        Repository.atomic(File(directory,Repository.hash(key)+".json"),bytes);cleanup(false)
    }
    @Synchronized fun cleanup(clear:Boolean) {
        if(clear){generation++;websites.clear()}else websites.entries.removeAll {it.value.expires<=clock()}
        var total=0L;var count=0
        directory.listFiles().orEmpty().sortedByDescending {it.lastModified()}.forEach {f->
            val stale=clock()-f.lastModified()>2*CachePolicy.DAY
            if(clear||stale||!f.name.endsWith(".json")||count>=128||total+f.length()>1024*1024)f.delete()
            else {total+=f.length();count++}
        }
    }
    @Synchronized fun bytes()=directory.listFiles().orEmpty().sumOf {it.length()}
}
