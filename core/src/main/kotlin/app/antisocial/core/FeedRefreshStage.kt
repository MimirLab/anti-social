/* anti social, GPL-3.0-or-later. Bounded disposable source batches, never saved material. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.security.DigestOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keep network waits outside the reader database's publication transaction.
 * Only one bounded page is decoded at a time; no complete refresh lives in RAM.
 * Files are private scratch and cannot outlive close or a subsequent startup. */
class FeedRefreshStage(private val root:File,private val maxBytes:Long=64L*1024*1024,
    private val maxEntryBytes:Long=minOf(maxBytes,32L*1024*1024),private val maxEntries:Int=2048):Closeable {
    data class Entry internal constructor(val sourceId:String,val pendingReason:String?,internal val file:File,
        internal val bytes:Long,internal val sha256:String)
    private val gson=Gson()
    private val lock=Any();private var closed=false;private var reserved=0L;private var writers=0
    private var exhausted=false;private val pages=mutableListOf<Entry>()
    private val directory=File(root,"batch-${UUID.randomUUID()}")
    init {
        require(maxBytes in 0..64L*1024*1024&&maxEntryBytes in 0..maxBytes&&maxEntries in 1..2048)
        synchronized(active){cleanup(root);require(directory.mkdirs()){ "Cannot prepare the feed refresh cache" };active+=directory.canonicalPath}
    }
    val bytes:Long get()=synchronized(lock){if(closed)0 else reserved}
    val acceptsMore:Boolean get()=synchronized(lock){!closed&&!exhausted&&pages.size+writers<maxEntries&&reserved<maxBytes}
    fun entries():List<Entry> = synchronized(lock){check(!closed);pages.toList()}
    fun write(sourceId:String,page:Page,pendingReason:String?,checkCancellation:()->Unit={}):Entry {
        require(sourceId.length in 1..8192&&!sourceId.any(Char::isISOControl))
        synchronized(lock){check(!closed);if(pages.size+writers>=maxEntries)throw IOException("Feed refresh has reached its staging limit");writers++}
        val partial=File(directory,"page-${UUID.randomUUID()}.part");val complete=File(directory,partial.name.removeSuffix(".part")+".json")
        var written=0L;var retained=false
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            val output=object:FilterOutputStream(FileOutputStream(partial)) {
                override fun write(value:Int)=write(byteArrayOf(value.toByte()),0,1)
                override fun write(buffer:ByteArray,offset:Int,length:Int) {
                    checkCancellation()
                    synchronized(lock) {
                        check(!closed)
                        if(written+length>maxEntryBytes)throw IOException("This feed source exceeds the staging allowance")
                        if(reserved+length>maxBytes){exhausted=true;throw IOException("Feed refresh staging space is full")}
                        reserved+=length;written+=length
                    }
                    out.write(buffer,offset,length)
                }
            }
            OutputStreamWriter(DigestOutputStream(output,digest),Charsets.UTF_8).use {gson.toJson(page,it)}
            checkCancellation();check(partial.length()==written)
            if(!partial.renameTo(complete))throw IOException("Cannot finalize the feed refresh cache")
            val entry=Entry(sourceId,pendingReason?.take(300),complete,written,digest.digest().joinToString(""){"%02x".format(it)})
            synchronized(lock){check(!closed);pages+=entry;retained=true}
            return entry
        }finally {
            synchronized(lock){writers--;if(!retained)reserved-=written}
            if(!retained){partial.delete();complete.delete()}
        }
    }
    fun read(entry:Entry):Page=synchronized(lock) {
        check(!closed&&pages.any {it===entry})
        if(entry.file.length()!=entry.bytes)throw IOException("The staged feed source is incomplete")
        val digest=MessageDigest.getInstance("SHA-256")
        JsonReader(InputStreamReader(DigestInputStream(entry.file.inputStream(),digest),Charsets.UTF_8)).use {reader->
            reader.strictness=Strictness.STRICT;reader.nestingLimit=64
            val page=gson.fromJson<Page>(reader,Page::class.java)
            require(reader.peek()==JsonToken.END_DOCUMENT&&page.posts.size<=300)
            if(digest.digest().joinToString(""){"%02x".format(it)}!=entry.sha256)throw IOException("The staged feed source changed before publication")
            page
        }
    }
    override fun close() {
        synchronized(lock){if(closed)return;closed=true;pages.clear()}
        synchronized(active){prune(directory);active-=directory.canonicalPath}
    }
    companion object {
        private val active=ConcurrentHashMap.newKeySet<String>()
        private val batch=Regex("batch-[a-f0-9-]{36}");private val page=Regex("page-[a-f0-9-]{36}\\.(json|part)")
        private fun prune(directory:File) {directory.listFiles().orEmpty().filter {it.name.matches(page)&&!it.isDirectory}.forEach {it.delete()};directory.delete()}
        /** Never traverse arbitrary directories or touch an active batch. */
        fun cleanup(root:File)=synchronized(active) {root.listFiles().orEmpty().filter {it.name.matches(batch)&&it.isDirectory&&it.canonicalPath !in active}.forEach(::prune)}
    }
}
