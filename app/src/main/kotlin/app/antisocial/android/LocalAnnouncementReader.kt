/* anti social, GPL-3.0-or-later. Passive media acquisition; no browser or model calls. */
package app.antisocial.android

import app.antisocial.core.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal class LocalAnnouncementReader(private val repo:Repository) {
    private val lane=Mutex();private val gson=Gson()
    internal data class Receipt(val result:OcrResult,val checkedAt:Long)
    suspend fun read(task:LocalPosterSource,query:LocalDiscoveryQuery):LocalExtraction=lane.withLock {
        if(!repo.prefs.localFlyerReading||repo.ai.processing||repo.ai.state.value.working||repo.mediaTools.state.value.working)return@withLock LocalExtraction()
        val image=task.post.media.firstOrNull {it.type=="image"} ?: return@withLock LocalExtraction()
        val directory=File(repo.context.cacheDir,"discovery-flyers").apply {mkdirs()}
        // Crash remnants have no durable value. Never touch ordinary or saved media.
        directory.listFiles()?.filter {it.lastModified()<System.currentTimeMillis()-3600000}?.forEach {it.delete()}
        val file=File.createTempFile("flyer-",".image",directory)
        try {
            val receipt="flyer-url:v2:"+Repository.hash(image.url+"|"+task.post.body+"|"+task.post.publishedAt)
            val previous=if(task.refresh)null else repo.localDiscovery.cache(receipt)?.let {repo.localDiscovery.cache(it)}?.let {decode(it)}
            val result=previous ?: withTimeout(25_000) {
                repo.gateway.download(image.url,task.post.contentSourceUrl,file,type="image",maxBytes=8L*1024*1024)
                val hash=repo.mediaTools.sourceSha256(file)
                val key="flyer-ocr:tesseract5-fast-eng-v2:$hash"
                val cached=repo.localDiscovery.cache(key)?.let {decode(it)}
                val read=cached?.result ?: repo.mediaTools.discoveryOcr(file)
                check(read.sourceSha256==hash){"Flyer changed while reading"}
                val observation=Receipt(read,System.currentTimeMillis())
                val payload=gson.toJson(observation)
                if(payload.length<=256*1024) {
                    repo.localDiscovery.cache(key,payload,7L*86400000)
                    repo.localDiscovery.cache(receipt,key,6*3600000)
                }
                observation
            }
            LocalAnnouncements.extract(task.post,task.business,query.area,result.checkedAt,result.result,image.url,task.publisherLocationVerified)
        }finally {file.delete()}
    }
    private fun decode(value:String):Receipt?=runCatching {gson.fromJson(value,Receipt::class.java).takeIf {
        it.checkedAt in 1..System.currentTimeMillis()&&it.result.sourceSha256.matches(Regex("[a-f0-9]{64}"))
    }}.getOrNull()
}
