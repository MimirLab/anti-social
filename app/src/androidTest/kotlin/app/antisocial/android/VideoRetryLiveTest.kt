/* anti social, GPL-3.0-or-later. Opt-in actual Gateway/decoder interruption. */
package app.antisocial.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** No recording, account or capture is bundled. Only an explicitly supplied
 * public recording on a disposable empty installation may run this journey. */
class VideoRetryLiveTest {
    @Test fun aForegroundReadPreemptsTheActualDecoderAndItsRetryReusesValidatedBlocks():Unit=runBlocking(Dispatchers.IO) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialVideoRetryLive")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty())
        val input=File(context.getExternalFilesDir("validation"),"private-captured-video-plan.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject
        val focus=raw.getAsJsonArray("focusKeys").map {it.asString}.toSet()
        val post=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}
            .first {it.platform==Platform.TIKTOK&&CrossPosts.originalKey(it)in focus&&it.media.singleOrNull {m->m.type=="video"}?.let(VideoAssetIdentity::validSampling)==true}
        val media=post.media.single {it.type=="video"};val url=VideoAssetIdentity.sampleUrl(media)
        val host=java.net.URI(url).host
        val output=File(context.getExternalFilesDir("validation"),"video-retry-live")
        require(!output.exists());require(output.mkdirs())
        val events=CopyOnWriteArrayList<Map<String,Any?>>()
        val started=SystemClock.elapsedRealtime()
        fun event(vararg fields:Pair<String,Any?>){events+=mapOf("atMs" to SystemClock.elapsedRealtime()-started)+fields.toMap()}
        val oldObserver=repo.gateway.onNetworkEvent
        val starts=AtomicInteger();val trigger=CompletableDeferred<Unit>();val foregroundDone=CompletableDeferred<Unit>()
        val attempt=VideoSamplingAttempt();var interrupted=0;var retainedAtInterruption=0
        try {
            repo.gateway.onNetworkEvent={network->
                oldObserver?.invoke(network)
                if(network.host==host&&network.stage=="start"&&starts.incrementAndGet()==3)trigger.complete(Unit)
            }
            coroutineScope {
                val foreground=launch {
                    trigger.await();event("stage" to "foreground-start")
                    repo.gateway.sample(url,post.contentSourceUrl,type="video",limit=1)
                    event("stage" to "foreground-finished");foregroundDone.complete(Unit)
                }
                try {withTimeout(45_000){repo.gateway.boundedVideoComparison {withContext(attempt) {
                    var result:GatewayVideoSampler.Result?=null
                    while(result==null)try {
                        event("stage" to "sample-start")
                        result=repo.gateway.deferredRead {GatewayVideoSampler(repo).sample(media,post.contentSourceUrl)}
                    }catch(e:CancellationException) {
                        currentCoroutineContext().ensureActive();interrupted++;retainedAtInterruption=attempt.retainedBlocks
                        event("stage" to "preempted","retainedBlocks" to retainedAtInterruption)
                        require(interrupted<3);foregroundDone.await();delay(700)
                    }
                    event("stage" to "sample-finished","blocks" to result.fetchedBlocks,"bytes" to result.fetchedBytes,"scenes" to result.frames.scenes.size)
                    assertTrue(result.frames.scenes.size>=3);assertEquals(1,interrupted);assertTrue(retainedAtInterruption>=2)
                    assertTrue(foregroundDone.isCompleted)
                }}}}finally {foreground.cancelAndJoin()}
            }
        }finally {
            attempt.close();repo.gateway.onNetworkEvent=oldObserver
            File(output,"report.json").writeText(repo.gson.toJson(mapOf("events" to events,"interrupted" to interrupted,"retainedAtInterruption" to retainedAtInterruption,"closedRetainedBlocks" to attempt.retainedBlocks)))
        }
        assertEquals(0,attempt.retainedBlocks)
    }
}
