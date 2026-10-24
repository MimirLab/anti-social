/* anti social, GPL-3.0-or-later. Validated feed reuse; no disk history or background work. */
package app.antisocial.core

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Writer
import java.security.MessageDigest

/** Every read still contacts its source. Direct validators require an exact,
 * readable representation in this session; Tor never retains or echoes them. */
internal class SyndicationReader(private val gateway:NetworkGateway,private val clock:()->Long=System::currentTimeMillis,
    private val maxEntries:Int=32,private val maxBytes:Int=4*1024*1024,private val ttl:Long=30*60_000L) {
    private data class Stamp(val generation:Long,val sequence:Long)
    private data class Entry(val requested:String,val finalUrl:String,val session:NetworkSession,val fullAt:Long,val sequence:Long,
        val hash:String,val type:String,val page:Page,val etag:String?,val modified:String?,val bytes:Int)
    private val entries=linkedMapOf<String,Entry>()
    private var generation=0L
    private var sequence=0L
    private val gson=Gson()
    private val tagPattern=Regex("(?:W/)?\"[!#-~]*\"")
    init {require(maxEntries in 1..128&&maxBytes in 1024..16*1024*1024&&ttl in 1..30*60_000L)}
    @Synchronized fun clear(){generation++;entries.clear()}
    @Synchronized fun cleanup(){trim()}
    @Synchronized internal fun retainedBytes():Int {trim();return entries.values.sumOf {it.bytes}}
    @Synchronized private fun stamp()=Stamp(generation,++sequence)
    @Synchronized private fun get(key:String,session:NetworkSession,stamp:Stamp):Entry? {
        if(generation!=stamp.generation)return null
        trim()
        return entries[key]?.takeIf {it.session===session}
    }
    @Synchronized private fun remove(key:String,stamp:Stamp) {
        if(generation==stamp.generation&&entries[key]?.sequence?.let {it<=stamp.sequence}!=false)entries.remove(key)
    }
    @Synchronized private fun put(key:String,value:Entry,stamp:Stamp) {
        if(generation!=stamp.generation||gateway.sessionFor(value.requested)!==value.session||entries[key]?.sequence?.let {it>stamp.sequence}==true)return
        entries.remove(key)
        if(value.bytes<=maxBytes)entries[key]=value
        trim()
    }
    private fun trim() {
        val now=clock()
        entries.entries.removeAll {now-it.value.fullAt !in 0..ttl||gateway.sessionFor(it.value.requested)!==it.value.session}
        while(entries.size>maxEntries||entries.values.sumOf {it.bytes}>maxBytes)entries.remove(entries.keys.first())
    }
    private class TooLarge:RuntimeException()
    private fun weight(key:String,page:Page,extra:Int):Int? {
        var count=0
        val bound=(maxBytes-key.length*2-extra)/2
        if(bound<=0)return null
        val writer=object:Writer(){
            override fun write(buffer:CharArray,offset:Int,length:Int){if(length>bound-count)throw TooLarge();count+=length}
            override fun flush(){}
            override fun close(){}
        }
        return try {gson.toJson(page,Page::class.java,writer);key.length*2+count*2+extra}catch(_:TooLarge){null}
    }
    private fun uri(value:String)=value.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
    private fun header(response:GatewayDocument,name:String):String?=response.headers.entries.filter {it.key.equals(name,true)}.flatMap {it.value}.singleOrNull()?.trim()
    private fun fields(response:GatewayDocument,name:String)=response.headers.entries.filter {it.key.equals(name,true)}.flatMap {it.value}.joinToString(",")
    private fun etag(value:String?)=value?.takeIf {it.length in 2..2048&&tagPattern.matches(it)}
    private fun modified(value:String?)=value?.takeIf {it.length in 1..256&&it.all {c->c.code in 32..126}}
    private fun mayRetain(response:GatewayDocument):Boolean {
        if(fields(response,"Cache-Control").split(',').any {it.trim().substringBefore('=').equals("no-store",true)})return false
        return fields(response,"Vary").split(',').map {it.trim().lowercase()}.filter(String::isNotBlank).all {it in setOf("accept","accept-language","accept-encoding","user-agent")}
    }
    private fun fresh(page:Page,at:Long)=page.copy(provenance=page.provenance?.copy(fetchedAt=at),posts=page.posts.map {post->post.copy(provenance=post.provenance?.copy(fetchedAt=at))})
    suspend fun read(url:String,target:Target,source:Source,maxBodyBytes:Long,headers:Map<String,String>,parse:suspend (Payload)->Page):Page {
        require(maxBodyBytes in 1..4L*1024*1024)
        require(headers.all {(name,value)->name.lowercase() in setOf("accept","accept-language")&&value.length<=2048&&value.all {it.code in 32..126}})
        val requested=requireNotNull(uri(url)){"Invalid feed address"}
        val session=gateway.sessionFor(requested);val originals=gateway.originalFallback;val stamp=stamp()
        fun check(){if(gateway.sessionFor(requested)!==session||gateway.originalFallback!=originals)throw CancellationException("Feed connection changed; retry this read")}
        check()
        val key=listOf(source.key,source.role.name,target.platform.id,target.kind.name,target.canonicalUrl,requested,maxBodyBytes.toString(),headers.entries.sortedBy {it.key.lowercase()}.joinToString("\n"){it.key.lowercase()+":"+it.value}).joinToString("\n")
        val old=get(key,session,stamp)
        val approved=old?.takeIf {!session.policy.tor&&it.finalUrl==requested&&(it.etag!=null||it.modified!=null)}
        val requestHeaders=headers+buildMap {
            put("Cache-Control","no-cache")
            approved?.etag?.let {put("If-None-Match",it)};approved?.modified?.let {put("If-Modified-Since",it)}
        }
        val response=gateway.feedDocument(requested,target.canonicalUrl,maxBodyBytes,requestHeaders,session)
        check();currentCoroutineContext().ensureActive()
        val observedAt=clock()
        val finalUrl=requireNotNull(uri(response.finalUrl)){"Invalid feed response address"}
        val keep=mayRetain(response)
        val tag=if(session.policy.tor)null else etag(header(response,"ETag"))
        val date=if(session.policy.tor)null else modified(header(response,"Last-Modified"))
        if(response.status==304) {
            if(approved==null||!response.conditional||finalUrl!=approved.finalUrl||
                approved.etag!=null&&tag!=null&&approved.etag.removePrefix("W/")!=tag.removePrefix("W/")||
                approved.etag==null&&date!=null&&date!=approved.modified) {
                remove(key,stamp)
                throw SourceFailure(HealthStatus.EMPTY,"The source returned an unvalidated unchanged feed")
            }
            val page=fresh(approved.page,observedAt)
            val nextTag=tag ?: approved.etag;val nextDate=date ?: approved.modified
            val bytes=approved.bytes+2*(nextTag.orEmpty().length+nextDate.orEmpty().length-approved.etag.orEmpty().length-approved.modified.orEmpty().length)
            if(keep)put(key,approved.copy(sequence=stamp.sequence,page=page,etag=nextTag,modified=nextDate,bytes=bytes),stamp)else remove(key,stamp)
            return page
        }
        if(response.status==206)throw SourceFailure(HealthStatus.EMPTY,"A partial feed response cannot confirm a complete refresh")
        // A replacement body invalidates the old validator even if extraction fails.
        remove(key,stamp)
        val type=response.headers.entries.firstOrNull {it.key.equals("Content-Type",true)}?.value?.firstOrNull().orEmpty()
        val hash=MessageDigest.getInstance("SHA-256").digest(response.text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val page=if(keep&&old!=null&&old.finalUrl==finalUrl&&old.type==type&&old.hash==hash)fresh(old.page,observedAt)
            else parse(Payload(response.text,response.finalUrl,type,response.status))
        check();currentCoroutineContext().ensureActive()
        val extra=1024+2*(requested.length+finalUrl.length+type.length+hash.length+tag.orEmpty().length+date.orEmpty().length)
        if(keep&&response.status==200&&FeedRefresh.pendingReason(page,target.platform)==null)weight(key,page,extra)?.let {bytes->
            put(key,Entry(requested,finalUrl,session,observedAt,stamp.sequence,hash,type,page,tag,date,bytes),stamp)
        }
        return page
    }
}
