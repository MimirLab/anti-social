/* anti social, GPL-3.0-or-later. Opt-in private file/route diagnostics. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** No bundled accounts or reproduction material. Run only on an isolated device
 * with an explicit private packet; public reads use the production gateway. */
class CollectedMediaPacketTest {
    @Test fun suppliedEditionsRetainOneAttachmentPerObservedPictureOrClip()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialCollectedMediaPacket")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.filesDir,"collected-media-packet/input.json")
        require(input.length() in 1..4*1024*1024)
        val packet=JsonParser.parseReader(input.reader()).asJsonObject
        val cases=packet.getAsJsonArray("cases");require(cases.size() in 1..8)
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val output=File(context.getExternalFilesDir("validation"),"collected-media-packet").apply {mkdirs()}
        val report=JsonArray();val originals=mutableMapOf<String,MediaRow?>()
        repo.images.identities.clear()
        try {
            for((index,raw)in cases.withIndex()) {
                val spec=raw.asJsonObject;val posts=spec.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}
                require(posts.size in 1..12)
                val imageEdge=spec["imageEdge"]?.asInt ?: 1600
                require(imageEdge in 64..1600)
                val row=JsonObject();report.add(row);val reads=JsonArray();row.add("reads",reads)
                for(post in posts)for(media in post.media) {
                    val url=if(media.type=="video")media.preview else media.url
                    if(url==null||repo.images.identities.knows(url))continue
                    val read=JsonObject().apply {addProperty("url",url)};reads.add(read)
                    try {
                        spec.getAsJsonObject("files")?.get(url)?.asString?.let {name->
                            require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
                            val file=File(input.parentFile,name);require(file.isFile)
                            val key=Repository.hash(url);if(key !in originals)originals[key]=repo.dao.media(key)
                            repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
                        }
                        withTimeout(35_000){repo.images.load(url,post.contentSourceUrl,imageEdge)}
                        val captured=repo.images.identities.captured(post.copy(media=listOf(media))).media.single()
                        read.addProperty("fingerprint",captured.visualFingerprint);read.addProperty("attachmentFingerprint",captured.attachmentFingerprint)
                        repo.availableMedia(url)?.let {source->
                            val name="$index-${reads.size()}.image";source.copyTo(File(output,name),overwrite=true);read.addProperty("file",name)
                        }
                    }catch(e:TimeoutCancellationException){currentCoroutineContext().ensureActive();read.addProperty("error","Timed out")}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){read.addProperty("error",e.message ?: e.javaClass.simpleName)}
                    File(output,"report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
                }
                // Replay a source refresh after inspecting the earlier bytes.
                // This exposes signed-URL churn without downloading replacements
                // or treating an alias observation as file authentication.
                val refreshed=spec.getAsJsonArray("refreshedPosts")?.map {repo.gson.fromJson(it,Post::class.java)}
                require(refreshed==null||refreshed.size==posts.size&&refreshed.map(CrossPosts::originalKey).toSet()==posts.map(CrossPosts::originalKey).toSet())
                val captured=(refreshed ?: posts).map(repo.images.identities::captured)
                row.add("posts",repo.gson.toJsonTree(captured));row.add("attachments",repo.gson.toJsonTree(collectedAttachments(captured)))
                File(output,"report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            }
            cases.forEachIndexed {index,raw->raw.asJsonObject["expectedAttachments"]?.asInt?.let {expected->
                assertEquals("Private case $index attachment count",expected,report[index].asJsonObject.getAsJsonArray("attachments").size())
            }}
        }finally {
            for((key,old)in originals)if(old==null)repo.dao.removeMedia(listOf(key))else repo.dao.putMedia(old)
            repo.images.identities.clear()
        }
    }
}
