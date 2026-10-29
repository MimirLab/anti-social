/* anti social, GPL-3.0-or-later. Explicit private full-window qualification. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Debug
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Mounts the real application, including Room windows and the global matcher.
 * No public fixture, fabricated scene or alternate transport is supplied. */
class FullFeedVideoLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun capturedWindowUsesTheActualProgressiveFeedAndGatewaySampler() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialFullFeedVideoLive")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val input=File(ui.activity.getExternalFilesDir("validation"),"private-captured-video-plan.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject
        val posts=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}
        val follows=raw.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        val members=raw.getAsJsonObject("memberships").entrySet().associate {(k,v)->k to v.asJsonArray.map {it.asString}.toSet()}
        val focus=raw.getAsJsonArray("focusKeys").map {it.asString}.toSet()
        val requiredBody=raw.getAsJsonArray("expectedBodyPassages")?.map {it.asString}.orEmpty()
        require(requiredBody.size<=8&&requiredBody.all {it.length in 1..500&&it.isNotBlank()})
        require(posts.size in 1..1000&&follows.size in 1..2000&&focus.size in 2..CrossPosts.MAX_EDITIONS)
        require(posts.map(repo::key).distinct().size==posts.size&&posts.map(repo::key).containsAll(focus))
        val output=File(ui.activity.getExternalFilesDir("validation"),"full-feed-video-live").apply {mkdirs()}
        val prefs=repo.prefs.export().toString();val oldMemory=repo.feedMemory
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val memory=File(ui.activity.cacheDir,"full-feed-video-memory.json")
        val sampler=repo.videos.sample;val metadata=repo.videos.metadata
        val events=CopyOnWriteArrayList<Map<String,Any?>>()
        var vm:AntiSocialViewModel?=null;var imported=false;var tracing=false
        val started=SystemClock.elapsedRealtime()
        fun event(vararg fields:Pair<String,Any?>) {
            synchronized(events) {
                events+=mapOf("atMs" to SystemClock.elapsedRealtime()-started)+fields.toMap()
                File(output,"events.json").writeText(repo.gson.toJson(events))
            }
        }
        fun screenshot(name:String) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {File(output,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
        }
        try {
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                ui.activity.setContent {Text("Preparing private full-window replay")}
            }
            runBlocking {withTimeout(20_000){repo.feedRefresh.first {!it.running}}}
            runBlocking {require(repo.dao.allFollows().isEmpty()&&repo.dao.allPosts().isEmpty()&&repo.dao.allBookmarks().isEmpty()) {"A disposable empty installation is required"}}
            repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false;repo.prefs.communityComments=false
            repo.prefs.originalFallback=true
            args.getString("antisocialAllowMeteredVideoSamples")?.let {repo.prefs.videoDownloadWifiOnly=it!="true"}
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            repo.feedMemory=FeedMemoryStore(ui.activity,scope,memory)
            runBlocking {repo.feedMemory.awaitReady();repo.videos.cleanup(true)}
            event("stage" to "resource-policy","metered" to ui.activity.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered,
                "automaticAllowed" to runBlocking {repo.videos.automaticAllowed()},"wifiOnlyVideoDownloads" to repo.prefs.videoDownloadWifiOnly)
            repo.videos.sample={media,source->
                val key=Repository.hash(media.url);event("stage" to "sample-start","urlHash" to key)
                try {sampler(media,source).also {r->event("stage" to "sample-end","urlHash" to key,"bytes" to r.fetchedBytes,"blocks" to r.fetchedBlocks,"scenes" to r.frames.scenes.size)}}
                catch(e:Exception){event("stage" to "sample-failed","urlHash" to key,"error" to e.toString().take(500));throw e}
            }
            repo.videos.metadata={post->event("stage" to "metadata-start","key" to repo.key(post));metadata(post).also {event("stage" to "metadata-end","key" to repo.key(post),"found" to (it!=null))}}
            runBlocking {repo.db.withTransaction {
                follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
                posts.forEach {post->
                    val ids=members[repo.key(post)].orEmpty();require(ids.isNotEmpty()&&ids.all {id->follows.any {it.id==id}})
                    val json=repo.gson.toJson(post)
                    repo.dao.putPost(PostRow(repo.key(post),post.platform.id,json,post.publishedAt,System.currentTimeMillis(),null,json.toByteArray().size.toLong(),ids.first()))
                    ids.forEach {repo.dao.putPostFollow(PostFollowRow(repo.key(post),it))}
                }
            };imported=true}
            val top=posts.filter {repo.key(it)in focus}.maxBy {it.publishedAt ?: 0}
            repo.feedMemory.reconcile("all",posts.map(repo::key));repo.feedMemory.anchor("all",repo.key(top),0)
            val model=AntiSocialViewModel(repo).also {vm=it}
            if(args.getString("antisocialFeedMethodTrace")=="true"||args.getString("antisocialFeedWorkTrace")=="true") {
                scope.launch {
                    repo.cardWork.active.map {tasks->tasks.filter {it.label=="Matching editions"}.map {it.id to it.keys.size}}
                        .distinctUntilChanged().collect {event("stage" to "matching-work","jobs" to it)}
                }
                scope.launch {repo.images.identities.revision.collect {event("stage" to "image-revision","revision" to it)}}
                scope.launch {repo.videos.revision.collect {event("stage" to "video-revision","revision" to it)}}
                scope.launch {repo.follows.map {Repository.hash(repo.gson.toJson(it))}.distinctUntilChanged().collect {event("stage" to "follow-snapshot","hash" to it)}}
                scope.launch {repo.followedSnapshot().map {Repository.hash(repo.gson.toJson(it))}.distinctUntilChanged().collect {event("stage" to "source-snapshot","hash" to it)}}
            }
            if(args.getString("antisocialFeedMethodTrace")=="true") {
                Debug.startMethodTracingSampling(File(output,"feed-sampling.trace").path,32*1024*1024,5_000)
                tracing=true;event("stage" to "profiling-enabled","intervalUs" to 5_000)
            }
            event("stage" to "open","posts" to posts.size)
            ui.runOnUiThread {model.combined();ui.activity.setContent {AntiSocialApp(model)}}
            val rows=SemanticsMatcher("focused real feed card"){it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Feed row ")in focus}
            fun visibleRows():List<androidx.compose.ui.semantics.SemanticsNode> {
                val viewport=ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return emptyList()
                return ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().filter {node->
                    val area=node.boundsInRoot.intersect(viewport);area.width>0&&area.height>=80
                }
            }
            ui.waitUntil(60_000){visibleRows().isNotEmpty()};ui.waitForIdle()
            event("stage" to "first-visible");screenshot("first-visible.png")
            // Observe the shipping dwell, worker and catalogue. This does not
            // invoke enrich() or insert observations from the test itself.
            ui.waitUntil(150_000){
                val captured=posts.filter {repo.key(it)in focus}.map(repo.videos::captured)
                captured.count {p->p.media.any {it.videoFootage?.scenes?.size?.let {n->n>=3}==true}}>=3
            }
            event("stage" to "three-recordings-observed")
            fun words(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}+node.children.flatMap(::words)
            ui.waitUntil(60_000){visibleRows().any {node->
                words(node).any {it.startsWith("${focus.size} originals ·")||it.startsWith("${focus.size} platforms ·")}
            }}
            event("stage" to "full-family-visible")
            if(tracing){Debug.stopMethodTracing();tracing=false}
            ui.waitForIdle()
            File(output,"semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            screenshot("settled.png")
            if(args.getString("antisocialFeedVerifyAdoptedBody")=="true") {
                require(requiredBody.isNotEmpty())
                fun compact(text:String)=text.lowercase(java.util.Locale.ROOT).filterNot {it.isWhitespace()||Character.isSpaceChar(it)}
                fun contents()=visibleRows().firstOrNull {node->words(node).any {it.startsWith("${focus.size} originals ·")||it.startsWith("${focus.size} platforms ·")}}
                fun occurrences(text:String,needle:String):Int {var count=0;var from=0;while(true){val at=text.indexOf(needle,from);if(at<0)return count;count++;from=at+needle.length}}
                fun complete()=contents()?.let {node->val text=compact(words(node).joinToString(" "));requiredBody.all {occurrences(text,compact(it))==1}}==true
                if(!complete()) {
                    val activity=SemanticsMatcher("target family captured-details offer") {node->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Card activity ")in focus&&
                            node.config.getOrNull(SemanticsProperties.StateDescription)=="New details ready"
                    }
                    ui.waitUntil(60_000){ui.onAllNodes(activity,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                    val activityTag=requireNotNull(ui.onAllNodes(activity,useUnmergedTree=true).fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.TestTag))
                    val before=requireNotNull(contents()).boundsInRoot.top
                    event("stage" to "captured-body-offered")
                    // Independent work can switch Ready to Updating between
                    // observation and touch. The card's identity stays stable.
                    ui.onNodeWithTag(activityTag,useUnmergedTree=true).performClick()
                    ui.waitUntil(10_000){ui.onAllNodesWithText("Show details").fetchSemanticsNodes().isNotEmpty()}
                    ui.onNodeWithText("Show details").performClick()
                    ui.waitUntil(30_000){complete()};ui.waitForIdle()
                    assertTrue("Captured details must preserve the established row's pixel anchor",kotlin.math.abs(requireNotNull(contents()).boundsInRoot.top-before)<=2f)
                }
                event("stage" to "complete-body-visible")
                File(output,"adopted-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString());screenshot("adopted.png")
                val rowTag=requireNotNull(requireNotNull(contents()).config.getOrNull(SemanticsProperties.TestTag))
                val passage=hasText(requiredBody.first(),substring=true,ignoreCase=true) and hasAnyAncestor(hasTestTag(rowTag))
                ui.onNode(passage,useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                ui.waitForIdle();Thread.sleep(100);screenshot("adopted-captions.png")
            }
            val observed=posts.map(repo.videos::captured)
            val groups=runBlocking {withContext(Dispatchers.Default){CrossPosts.groupWithVideoFootage(observed,follows,members,sharedFootage={a,b->repo.videos.related(a,b){}})}}
            val family=groups.filter {g->g.posts.any {repo.key(it)in focus}}
            File(output,"result.json").writeText(repo.gson.toJson(mapOf("focusGroups" to family,"work" to repo.cardWork.active.value,"events" to events)))
            assertEquals("Captured counterparts must finish as one full-window family",1,family.size)
            assertTrue(family.single().posts.map(repo::key).containsAll(focus))
        }catch(e:Throwable) {
            runCatching {File(output,"failure-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString());screenshot("failure.png")}
            event("stage" to "failed","error" to e.toString().take(500),"work" to repo.cardWork.active.value)
            throw e
        }finally {
            if(tracing){Debug.stopMethodTracing();tracing=false}
            ui.runOnIdle {vm?.viewModelScope?.cancel();ui.activity.setContent {Text("Private full-window replay complete")}}
            repo.videos.sample=sampler;repo.videos.metadata=metadata
            runBlocking {
                repo.feedMemory.flush();scope.coroutineContext[Job]?.cancelAndJoin()
                if(imported)repo.db.withTransaction {
                    follows.forEach {repo.dao.removeFollow(it.id)}
                    posts.map(repo::key).chunked(400).forEach {repo.dao.removePosts(it)}
                    posts.forEach {post->for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))}
                }
            }
            repo.feedMemory=oldMemory
            listOf(memory,File(memory.path+".bak"),File(memory.path+".new")).forEach {it.delete()}
            ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings()}
        }
    }
}
