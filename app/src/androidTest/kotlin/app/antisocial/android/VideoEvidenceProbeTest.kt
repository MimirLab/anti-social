/* anti social, GPL-3.0-or-later. Explicit isolated-device diagnostics only. */
package app.antisocial.android

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** This is not a foreground/background product acquisition path. It measures
 * externally supplied public recordings on an explicitly disposable device.
 * No account, URL, film or captured frame is bundled. Full downloads stay under
 * a shared 64 MiB/eight-request allowance and never enter Room or saved copies.
 * Frame decoding here is a diagnostic, not qualification of parser isolation.
 */
class VideoEvidenceProbeTest {
    /** Existing Gateway events only: no alternate transport, decoder or fetch
     * shape. Diagnostic hosts/receipts remain in the supplied private output. */
    private suspend fun rangeSample(repo:Repository,post:Post,media:Media,trace:Boolean,row:JsonObject,gson:Gson,start:Long):GatewayVideoSampler.Result {
        if(!trace)return GatewayVideoSampler(repo).sample(media,post.contentSourceUrl)
        val events=CopyOnWriteArrayList<Map<String,Any>>()
        val old=repo.gateway.onNetworkEvent
        repo.gateway.onNetworkEvent={event->
            old?.invoke(event)
            if(events.size<512)events+=mapOf("atMs" to SystemClock.elapsedRealtime()-start,"host" to event.host,
                "stage" to event.stage,"callElapsedMs" to event.elapsedMs)
        }
        try {return GatewayVideoSampler(repo).sample(media,post.contentSourceUrl)}
        finally {repo.gateway.onNetworkEvent=old;row.add("rangeTrace",gson.toJsonTree(events))}
    }
    private fun sha256(file:File)=file.inputStream().use {stream->val digest=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=stream.read(b);if(n<0)break;digest.update(b,0,n)};digest.digest().joinToString(""){"%02x".format(it)}}
    @Test fun inspectSuppliedRecordingsAndTheirBoundedLocalFrames():Unit=runBlocking(Dispatchers.IO) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialVideoEvidenceProbe")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty())
        val input=File(context.filesDir,"video-evidence-probe/input.json")
        require(input.isFile&&input.length() in 1..1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject
        val label=raw["label"].asString;require(label.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        val posts=raw.getAsJsonArray("posts").also {require(it.size() in 1..8)}.map {repo.gson.fromJson(it,Post::class.java)}
        val clips=posts.flatMap {post->post.media.filter {it.type=="video"}.map {post to it}}.distinctBy {it.second.url}
        require(clips.size in 1..4)
        val output=File(context.getExternalFilesDir("validation"),"video-evidence-$label")
        require(!output.exists());require(output.mkdirs())
        val records=JsonArray();val gson=GsonBuilder().setPrettyPrinting().create()
        fun save()=File(output,"report.json").writeText(gson.toJson(records))
        var acceptedBytes=0L
        val inspect:suspend ()->Unit={
            clips.forEachIndexed {index,(post,media)->
                val row=JsonObject().apply {addProperty("platform",post.platform.id);addProperty("source",post.canonicalUrl);addProperty("url",media.url)}
                records.add(row)
                try {
                    val start=SystemClock.elapsedRealtime()
                    if(raw["ranges"]?.asBoolean==true) {
                        if(raw["rangeHeaders"]?.asBoolean==true) {
                            val headers=repo.gateway.extractorDocument(media.url,sourceUrl=post.contentSourceUrl,headers=mapOf("Range" to "bytes=0-0","Accept-Encoding" to "identity"),maxBytes=1024)
                            row.addProperty("rangeProbeStatus",headers.status)
                            row.add("rangeHeaders",gson.toJsonTree(headers.headers.filterKeys {it.lowercase() in setOf("etag","last-modified","date","content-range","content-encoding","content-length")}));save()
                        }
                        val result=rangeSample(repo,post,media,raw["rangeTrace"]?.asBoolean==true,row,gson,start)
                        row.add("rangeFrames",gson.toJsonTree(result.frames));row.addProperty("rangeBytes",result.fetchedBytes);row.addProperty("rangeBlocks",result.fetchedBlocks)
                        row.addProperty("rangeMs",SystemClock.elapsedRealtime()-start);save()
                        return@forEachIndexed
                    }
                    val allowance=minOf(32L*1024*1024,64L*1024*1024-acceptedBytes);require(allowance>0)
                    val local=raw.getAsJsonObject("files")?.getAsJsonObject(media.url)
                    val file=if(local==null)File(output,"clip-$index.bin")else {
                        val name=local["name"].asString;require(name.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
                        File(input.parentFile,name).also {require(it.canonicalFile.parentFile==requireNotNull(input.parentFile).canonicalFile&&it.isFile&&it.length() in 1..allowance)}
                    }
                    val bytes=if(local==null)withTimeout(90_000){repo.gateway.download(media.url,post.contentSourceUrl,file,type="video",maxBytes=allowance)}else file.length()
                    acceptedBytes+=bytes
                    row.addProperty("downloadMs",SystemClock.elapsedRealtime()-start);row.addProperty("bytes",bytes);row.addProperty("file",file.name);row.addProperty("usedLocalCopy",local!=null)
                    val sha=sha256(file);if(local!=null)require(sha==local["sha256"].asString)
                    row.addProperty("sha256",sha);save()
                    if(raw["isolated"]?.asBoolean==true) {
                        val isolatedStart=SystemClock.elapsedRealtime()
                        try {row.add("isolatedFrames",gson.toJsonTree(repo.contentSandbox.sampledVideo(file)))}
                        catch(e:CancellationException){throw e}
                        catch(e:Exception){row.addProperty("isolatedError",e.toString())}
                        row.addProperty("isolatedMs",SystemClock.elapsedRealtime()-isolatedStart);save()
                    }
                    val retriever=MediaMetadataRetriever();val decodeStart=SystemClock.elapsedRealtime()
                    try {FileInputStream(file).use {source->
                        retriever.setDataSource(source.fd)
                        fun value(key:Int)=retriever.extractMetadata(key)?.toLongOrNull()
                        val duration=value(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val width=value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);val height=value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        row.addProperty("durationMs",duration);row.addProperty("width",width);row.addProperty("height",height)
                        require(duration!=null&&duration in 1..30*60_000&&width!=null&&height!=null&&width in 1..8192&&height in 1..8192)
                        val w=if(width>=height)320 else maxOf(1,(width*320/height).toInt());val h=if(height>=width)320 else maxOf(1,(height*320/width).toInt())
                        val frames=JsonArray();row.add("frames",frames)
                        (listOf(0L)+(1..7).map {duration*it/8}+listOf(maxOf(0,duration-250))).distinct().forEachIndexed {frame,at->
                            val bitmap=if(Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST,w,h)
                                else retriever.getFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST)?.let {original->Bitmap.createScaledBitmap(original,w,h,true).also {if(it!==original)original.recycle()}}
                            if(bitmap!=null)try {
                                val name="clip-$index-frame-$frame.png";File(output,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                                val url="https://frame.fixture.invalid/$index/$frame";val bank=MediaIdentityStore();bank.observe(url,bitmap)
                                val captured=bank.captured(post.copy(media=listOf(Media(url)))).media.single()
                                frames.add(JsonObject().apply {addProperty("requestedAtMs",at);addProperty("file",name);addProperty("visualFingerprint",captured.visualFingerprint);addProperty("attachmentFingerprint",captured.attachmentFingerprint)})
                            }finally {bitmap.recycle()}
                        }
                    }}finally {retriever.release()}
                    row.addProperty("decodeMs",SystemClock.elapsedRealtime()-decodeStart)
                }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();row.addProperty("error","Recording inspection timed out")}
                catch(e:CancellationException){throw e}
                catch(e:Exception){row.addProperty("error",e.message ?: e.javaClass.simpleName)}
                finally {save()}
            }
        }
        withTimeout(240_000) {
            if(raw["ranges"]?.asBoolean==true) {
                require(clips.size==1) {"A range probe inspects one recording under its own fixed Gateway allowance"}
                inspect()
            }else repo.gateway.boundedMediaRead(maxBytes=64L*1024*1024,maxRequests=8){inspect()}
        }
    }
}
