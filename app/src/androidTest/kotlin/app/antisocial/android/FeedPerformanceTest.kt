/* anti social, GPL-3.0-or-later. Opt-in local performance evidence. */
package app.antisocial.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Same authored posts and gestures on both builds. Timings describe this
 * emulator/run only; they are not a threshold or a phone performance claim. */
@RunWith(AndroidJUnit4::class)
class FeedPerformanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    @Test fun measureOpeningScrollingAndUnchangedContentNotifications() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialFeedBenchmark")=="true")
        runBlocking {repo.awaitReady()}
        val preferences=repo.prefs.export().toString()
        val originalMemory=repo.feedMemory
        val suffix=System.nanoTime().toString().takeLast(10)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val memory=File(ui.activity.cacheDir,"feed-benchmark-$suffix.json")
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"feed-performance").apply {mkdirs()}
        val label=args.getString("antisocialBenchmarkLabel")?.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))} ?: "measurement"
        val frames=CopyOnWriteArrayList<Long>()
        val handler=HandlerThread("feed-benchmark-frames").apply {start()}
        val listener=Window.OnFrameMetricsAvailableListener {_,metric,_->frames+=metric.getMetric(FrameMetrics.TOTAL_DURATION)}
        var listening=false
        val records=mutableListOf<Post>()
        val pictures=args.getString("antisocialFeedBenchmarkPictures")=="true"
        val artwork=mutableListOf<File>()
        val mediaUrls=if(pictures)(0 until 120).map {"https://feed-art.invalid/fixture-$suffix-$it.jpg"}else emptyList()
        val follows=listOf(Platform.X,Platform.FACEBOOK).map {p->
            Follow("benchmark-$suffix:${p.id}",repo.routes.target(p,if(p==Platform.X)"perf_fixture"else "perf.fixture"),"Workshop",personId="benchmark-$suffix",personName="Public workshop")
        }
        var vm:AntiSocialViewModel?=null
        try {
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                ui.activity.setContent {Text("Preparing local feed benchmark")}
            }
            runBlocking {withTimeout(15_000){repo.feedRefresh.first {!it.running}}}
            // Recover only this opt-in fixture if a previous interrupted run
            // ended before its finally block completed. Never clear reader data.
            runBlocking {
                repo.dao.allFollows().filter {it.id.startsWith("benchmark-")}.forEach {repo.dao.removeFollow(it.id)}
                val previous=repo.dao.allPosts().filter {it.payload.contains("https://x.com/perf_fixture/status/")||it.payload.contains("https://www.facebook.com/perf.fixture/posts/")}.map {it.key}
                previous.chunked(400).forEach {repo.dao.removePosts(it)}
            }
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.communityComments=false
            repo.prefs.mentionLookups=false;repo.prefs.theme="dark"
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            repo.feedMemory=FeedMemoryStore(ui.activity,scope,memory)
            runBlocking {repo.feedMemory.awaitReady()}
            val body="The community workshop has published the new maintenance schedule for the public equipment library. Volunteers inspected every replacement battery and documented the compatible connectors, installation steps, repair tools and safety checks. The updated guidance explains when borrowing opens, which parts are supplied, how to reserve a workbench and where to find the full illustrated repair instructions."
            val now=System.currentTimeMillis()
            if(pictures) {
                repo.images.clear()
                repeat(8){index->
                    val bitmap=android.graphics.Bitmap.createBitmap(1280,960,android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas=android.graphics.Canvas(bitmap);val paint=android.graphics.Paint()
                    repeat(40){band->paint.color=android.graphics.Color.rgb((index*29+band*7)%256,(band*13+index*17)%256,(index*53+band*5)%256);canvas.drawRect(0f,band*24f,1280f,(band+1)*24f,paint)}
                    paint.color=android.graphics.Color.WHITE;paint.textSize=100f;canvas.drawText("Workshop $index",80f,500f,paint)
                    val file=File(ui.activity.cacheDir,"feed-art-$suffix-$index.jpg");artwork+=file
                    file.outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it))};bitmap.recycle()
                }
                runBlocking {mediaUrls.forEachIndexed {i,url->val file=artwork[i%artwork.size];repo.dao.putMedia(MediaRow(Repository.hash(url),file.path,file.length(),now))}}
            }
            repeat(240){i->
                val p=if(i%2==0)Platform.X else Platform.FACEBOOK
                val id="230000000000000${i.toString().padStart(4,'0')}"
                val url=if(p==Platform.X)"https://x.com/perf_fixture/status/$id"else "https://www.facebook.com/perf.fixture/posts/$id"
                records+=Post(id,p,url,"Public workshop","Workshop bulletin ${i/2}. $body",publishedAt=now-i/2*3_600_000L,
                    media=if(pictures)listOf(Media(mediaUrls[i/2]))else emptyList())
            }
            runBlocking {repo.db.withTransaction {
                follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
                records.forEachIndexed {i,post->
                    val json=repo.gson.toJson(post)
                    repo.dao.putPost(PostRow(repo.key(post),post.platform.id,json,post.publishedAt,now,null,json.toByteArray().size.toLong(),follows[i%2].id))
                }
            }}
            val grouping=mutableListOf<Double>()
            repeat(3){iteration->
                val trace=args.getString("antisocialTraceFeedPreparation")=="true"&&iteration==0
                if(trace)android.os.Debug.startMethodTracingSampling(File(dir,"$label-grouping.trace").path,32*1024*1024,2_000)
                try {val start=SystemClock.elapsedRealtimeNanos();val groups=CrossPosts.group(records,follows);grouping+=(SystemClock.elapsedRealtimeNanos()-start)/1e6;assertEquals(120,groups.size)}
                finally {if(trace)android.os.Debug.stopMethodTracing()}
            }
            repo.feedMemory.reconcile("all",records.map(repo::key))
            repo.feedMemory.anchor("all",repo.key(records.first()),0)
            val model=AntiSocialViewModel(repo).also {vm=it}
            val started=SystemClock.elapsedRealtimeNanos()
            val traceOpening=args.getString("antisocialTraceFeedOpening")=="true"
            if(traceOpening)android.os.Debug.startMethodTracingSampling(File(dir,"$label-opening.trace").path,32*1024*1024,2_000)
            // Equal-time originals choose Facebook's stable original key first.
            val first="Feed row ${repo.key(records[1])}"
            try {
                ui.runOnUiThread {model.combined();ui.activity.setContent {AntiSocialApp(model)}}
                val admission=args.getString("antisocialFeedFirstCardDeadlineMs")?.toLongOrNull()?.coerceIn(1_000,90_000) ?: 90_000
                ui.waitUntil(admission){ui.onAllNodesWithTag(first).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag(first).assertIsDisplayed();ui.waitForIdle()
                if(pictures)ui.waitUntil(20_000){repo.images.identities.knows(mediaUrls.first())}
            }finally {if(traceOpening)android.os.Debug.stopMethodTracing()}
            val openedMs=(SystemClock.elapsedRealtimeNanos()-started)/1e6
            ui.runOnIdle {ui.activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handler.looper));listening=true}
            fun visible()=ui.onAllNodes(SemanticsMatcher("feed card") {it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Feed row ")==true}).fetchSemanticsNodes().mapNotNull {it.config.getOrNull(SemanticsProperties.TestTag)}
            val initial=visible();val scrolledAt=SystemClock.elapsedRealtimeNanos()
            val tracing=args.getString("antisocialTraceFeed")=="true"
            if(tracing)android.os.Debug.startMethodTracingSampling(File(dir,"$label.trace").path,32*1024*1024,2_000)
            try {repeat(8){ui.onNodeWithTag("Combined feed").performTouchInput {swipeUp(durationMillis=300)};ui.waitForIdle()}}
            finally {if(tracing)android.os.Debug.stopMethodTracing()}
            val scrollMs=(SystemClock.elapsedRealtimeNanos()-scrolledAt)/1e6
            val after=visible();assertNotEquals("The native feed must actually scroll",initial,after)
            ui.runOnIdle {ui.activity.window.removeOnFrameMetricsAvailableListener(listener);listening=false}
            val notificationAt=SystemClock.elapsedRealtimeNanos()
            runBlocking {repeat(8){repo.dao.touchPost(repo.key(records[it]),now+it)}}
            ui.waitForIdle()
            val notificationMs=(SystemClock.elapsedRealtimeNanos()-notificationAt)/1e6
            val samples=frames.filter {it>0}.sorted()
            fun percentile(p:Double)=samples.getOrNull(((samples.size-1)*p).toInt().coerceAtLeast(0))?.div(1e6)
            val result=mapOf("fixturePosts" to records.size,"cachedPictures" to pictures,"groups" to 120,"groupingMs" to grouping,"openMs" to openedMs,"scrollWallMs" to scrollMs,"unchangedNotificationsMs" to notificationMs,
                "frames" to samples.size,"frameMedianMs" to percentile(.5),"frameP95Ms" to percentile(.95),"framesOver50Ms" to samples.count {it>50_000_000},"initialCards" to initial,"scrolledCards" to after)
            File(dir,"$label.json").writeText(repo.gson.toJson(result))
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir,"$label.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }finally {
            ui.runOnIdle {if(listening)ui.activity.window.removeOnFrameMetricsAvailableListener(listener);vm?.viewModelScope?.cancel();ui.activity.setContent {Text("Local benchmark complete")}}
            handler.quitSafely();handler.join(3000)
            runBlocking {repo.feedMemory.flush();scope.coroutineContext[Job]?.cancelAndJoin()}
            repo.feedMemory=originalMemory
            runBlocking {repo.db.withTransaction {
                if(mediaUrls.isNotEmpty())repo.dao.removeMedia(mediaUrls.map(Repository::hash))
                follows.forEach {repo.dao.removeFollow(it.id)}
                records.map(repo::key).chunked(400).forEach {repo.dao.removePosts(it)}
                records.forEach {post->for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))}
            }}
            listOf(memory,File(memory.path+".bak"),File(memory.path+".new")).forEach {it.delete()}
            artwork.forEach {it.delete()};if(pictures)repo.images.clear()
            ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)}
        }
    }
}
