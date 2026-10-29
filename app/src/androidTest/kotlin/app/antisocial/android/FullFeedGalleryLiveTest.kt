/* anti social, GPL-3.0-or-later. Explicit private full-window qualification. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
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

/** Explicit missing-child acquisition and cached-byte restart replays. Neither
 * fixture supplies fingerprints; the cold replay already contains its child. */
class FullFeedGalleryLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun capturedWindowAcquiresAndJoinsItsPublisherContinuationBeforePublication()=replay(false)
    @Test fun capturedGalleryRebuildsItsEvidenceFromRetainedBytesWithoutRefetchingItsChild()=replay(true)
    private fun replay(cold:Boolean) {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString(if(cold)"antisocialFullFeedGalleryCold"else "antisocialFullFeedGalleryLive")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val directory=File(ui.activity.filesDir,if(cold)"full-gallery-cold-packet"else "full-gallery-private-packet")
        val input=File(directory,"input.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject
        val posts=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)})}
        val follows=raw.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        val members=raw.getAsJsonObject("memberships").entrySet().associate {(k,v)->k to v.asJsonArray.map {it.asString}.toSet()}
        val focus=raw.getAsJsonArray("focusKeys").map {it.asString}.toSet()
        val root=posts.single {repo.key(it)==raw["rootKey"].asString}
        val requiredCaption=raw["requiredCaption"]?.asString
        val childUrl=raw["expectedChildUrl"].asString
        val expectedOriginals=raw["expectedOriginals"].asInt;val expectedPictures=raw["expectedPictures"].asInt
        require(posts.size in 1..1000&&follows.size in 1..2000&&focus.size in 2..CrossPosts.MAX_EDITIONS)
        require(posts.map(repo::key).distinct().size==posts.size&&posts.map(repo::key).containsAll(focus))
        require(posts.any {it.canonicalUrl==childUrl}==cold)
        val files=raw.getAsJsonObject("files").entrySet().associate {(url,v)->
            url to File(directory,v.asString).also {require(it.canonicalFile.parentFile==directory.canonicalFile&&it.isFile)}
        }
        val aliases=files.entries.mapNotNull {(url,file)->ImageAssetIdentity.key(url)?.let {it to file}}.groupBy({it.first},{it.second})
        val output=File(ui.activity.getExternalFilesDir("validation"),if(cold)"full-feed-gallery-cold"else "full-feed-gallery-live").apply {mkdirs()}
        val prefs=repo.prefs.export().toString();val oldMemory=repo.feedMemory
        val policy=repo.gateway.currentPolicy()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val memory=File(ui.activity.cacheDir,"full-feed-gallery-memory.json")
        val reader=repo.galleries.read;val decoder=repo.galleries.image
        val cached=linkedMapOf<String,MediaRow?>();val staged=mutableListOf<File>()
        val events=CopyOnWriteArrayList<Map<String,Any?>>()
        var vm:AntiSocialViewModel?=null;var imported=false;var threadReads=0
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
                ui.activity.setContent {Text("Preparing private gallery replay")}
            }
            runBlocking {withTimeout(20_000){repo.feedRefresh.first {!it.running}}}
            runBlocking {require(repo.dao.allFollows().isEmpty()&&repo.dao.allPosts().isEmpty()&&repo.dao.allBookmarks().isEmpty()) {"A disposable empty installation is required"}}
            repo.prefs.intervalMinutes=0;repo.prefs.mentionLookups=false;repo.prefs.archiveChecks=false;repo.prefs.communityComments=false
            if(cold)ui.runOnIdle {repo.tor.enable(false);repo.gateway.configure(NetworkPolicy(tor=true,ready=false))}
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            repo.feedMemory=FeedMemoryStore(ui.activity,scope,memory)
            runBlocking {repo.feedMemory.awaitReady();repo.galleries.clear();repo.images.clear()}
            // Restore only initial editions, using actual archived files. A
            // changed signed URL needs an unambiguous qualified object ID.
            runBlocking {
                for(post in posts.filter {repo.key(it)in focus})for(media in post.media) {
                    val file=files[media.url] ?: ImageAssetIdentity.key(media.url)?.let {key->
                        aliases[key]?.distinctBy {java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}?.singleOrNull()
                    }
                    if(file!=null) {
                        val key=Repository.hash(media.url);cached.putIfAbsent(key,repo.dao.media(key))
                        val destination=File(ui.activity.cacheDir,"gallery-replay-$key").also {file.copyTo(it,overwrite=true);staged+=it}
                        repo.dao.putMedia(MediaRow(key,destination.path,destination.length(),System.currentTimeMillis()))
                    }
                    if(cold)require(file!=null){"Cold replay requires exact retained bytes for every focused picture"}
                    else repo.images.load(media.url,post.contentSourceUrl,1600)
                }
                repo.db.withTransaction {
                    follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
                    posts.forEach {post->
                        val ids=members[repo.key(post)].orEmpty();require(ids.isNotEmpty()&&ids.all {id->follows.any {it.id==id}})
                        val json=repo.gson.toJson(post)
                        repo.dao.putPost(PostRow(repo.key(post),post.platform.id,json,post.publishedAt,System.currentTimeMillis(),null,json.toByteArray().size.toLong(),ids.first()))
                        ids.forEach {repo.dao.putPostFollow(PostFollowRow(repo.key(post),it))}
                    }
                };imported=true
            }
            repo.galleries.read={post->
                threadReads++
                event("stage" to "thread-start")
                check(!cold){"An already acquired continuation must not be refetched"}
                reader(post).also {page->event("stage" to "thread-returned","posts" to page.posts.size,"childPresent" to page.posts.any {it.canonicalUrl==childUrl})}
            }
            repo.galleries.image={url,source->
                event("stage" to "child-picture-start","urlHash" to Repository.hash(url))
                decoder(url,source);event("stage" to "child-picture-decoded","urlHash" to Repository.hash(url))
            }
            val top=raw["anchorKey"]?.asString?.let {key->posts.single {repo.key(it)==key}} ?: posts.filter {repo.key(it)in focus}.maxBy {it.publishedAt ?: 0}
            repo.feedMemory.reconcile("all",posts.map(repo::key));repo.feedMemory.anchor("all",repo.key(top),0)
            val model=AntiSocialViewModel(repo).also {vm=it}
            event("stage" to "open","posts" to posts.size)
            ui.runOnUiThread {model.combined();ui.activity.setContent {AntiSocialApp(model)}}
            val rows=SemanticsMatcher("focused real gallery card"){it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Feed row ")in focus}
            fun words(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}+node.children.flatMap(::words)
            fun visibleRows():List<androidx.compose.ui.semantics.SemanticsNode> {
                val viewport=ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().singleOrNull()?.boundsInRoot ?: return emptyList()
                return ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().filter {node->
                    val area=node.boundsInRoot.intersect(viewport);area.width>0&&area.height>=80
                }
            }
            ui.waitUntil(60_000){visibleRows().isNotEmpty()};ui.waitForIdle()
            // Semantics can settle before the screenshot compositor has adopted
            // the restored viewport. Give two real display frames time to paint.
            Thread.sleep(100);ui.waitForIdle()
            val first=visibleRows().map {words(it)}
            event("stage" to "first-visible","familyRows" to first.size,"completeFamily" to first.any {list->list.any {it.startsWith("$expectedOriginals originals ·")}});screenshot("first-visible.png")
            // Do not invoke enrich(): the shipping card dwell owns acquisition.
            ui.waitUntil(90_000){runBlocking {repo.dao.allPosts().any {repo.gson.fromJson(it.payload,Post::class.java).canonicalUrl==childUrl}}}
            event("stage" to if(cold)"retained-child-present"else "child-committed")
            val added=runBlocking {repo.dao.allPosts().filter {it.key !in posts.map(repo::key)}}
            assertEquals("Only an actually missing publisher child can be added",if(cold)0 else 1,added.size)
            val child=if(cold)posts.single {it.canonicalUrl==childUrl}else repo.gson.fromJson(added.single().payload,Post::class.java)
            assertEquals(childUrl,child.canonicalUrl);assertEquals(root.id,child.parentId)
            assertEquals(ExtractionStatus.PRESENT,child.extractionCoverage?.replyParent)
            assertNotNull(child.sourceParentId);assertNotNull(child.provenance)
            assertEquals(members[repo.key(root)].orEmpty(),runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            ui.waitUntil(60_000){visibleRows().any {node->words(node).any {it.startsWith("$expectedOriginals originals ·")}}}
            if(requiredCaption!=null)ui.waitUntil(60_000){visibleRows().any {node->words(node).any {requiredCaption in it}}}
            event("stage" to "full-family-visible");ui.waitForIdle();screenshot("settled.png")
            File(output,"semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            val observed=(posts+child).distinctBy(repo::key).map(repo.images.identities::captured)
            val bindings=members+(repo.key(child) to runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            val family=runBlocking {withContext(Dispatchers.Default){CrossPosts.group(observed,follows,bindings)}}.filter {g->g.posts.any {repo.key(it)in focus}}
            assertEquals(1,family.size);assertEquals(expectedOriginals,family.single().posts.size)
            val plan=collectionPlan(family.single().posts,repo.images.identities)
            assertEquals(expectedPictures,plan.attachments.size)
            if(cold&&args.getString("antisocialFeedVerifyWarmReentry")=="true") {
                fun quiet():Boolean=repo.cardWork.active.value.none {it.label in setOf("Matching editions","Images")}
                fun awaitQuiet() {
                    var since=0L
                    ui.waitUntil(60_000){val now=SystemClock.elapsedRealtime();if(!quiet())since=0L else if(since==0L)since=now;since>0&&now-since>=1000}
                }
                awaitQuiet()
                val watching=CompletableDeferred<Unit>()
                val observer=scope.launch {
                    val active=mutableMapOf<Long,Long>()
                    repo.cardWork.active.map {tasks->tasks.filter {it.label=="Matching editions"}.map {it.id}.toSet()}.distinctUntilChanged().collect {tasks->
                        val now=SystemClock.elapsedRealtime()
                        for(id in active.keys.toList())if(id !in tasks){event("stage" to "warm-matching-finished","job" to id,"durationMs" to now-active.getValue(id));active.remove(id)}
                        tasks.filterNot(active::containsKey).forEach {id->active[id]=now;event("stage" to "warm-matching-started","job" to id)}
                        watching.complete(Unit)
                    }
                }
                try {
                    runBlocking {withTimeout(5000){watching.await()}}
                    ui.onNodeWithTag("Primary destination follows").performClick()
                    ui.waitUntil(10_000){model.screen.value.section=="follows"};ui.waitForIdle()
                    event("stage" to "warm-reentry-start")
                    ui.onNodeWithTag("Primary destination feed").performClick()
                    ui.waitUntil(30_000){visibleRows().any {node->val text=words(node);text.any {it.startsWith("$expectedOriginals originals ·")}&&(requiredCaption==null||text.any {requiredCaption in it})}}
                    ui.waitForIdle();Thread.sleep(100)
                    event("stage" to "warm-first-complete");screenshot("warm-reentry.png")
                    assertTrue("Returning to Following retains All",model.screen.value.combined)
                    awaitQuiet();event("stage" to "warm-reentry-quiet")
                    assertEquals("Navigation must not refetch the retained publisher continuation",0,threadReads)
                }finally {observer.cancel()}
            }
            File(output,"result.json").writeText(repo.gson.toJson(mapOf("family" to family.single(),"events" to events,"work" to repo.cardWork.active.value,"threadReads" to threadReads)))
            if(cold)assertEquals("Retained source data must not require a new thread read",0,threadReads)
        }catch(e:Throwable) {
            runCatching {File(output,"failure-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString());screenshot("failure.png")}
            event("stage" to "failed","error" to e.toString().take(500),"work" to repo.cardWork.active.value)
            throw e
        }finally {
            ui.runOnIdle {vm?.viewModelScope?.cancel();ui.activity.setContent {Text("Private gallery replay complete")}}
            repo.galleries.clear();repo.galleries.read=reader;repo.galleries.image=decoder
            runBlocking {
                repo.feedMemory.flush();scope.coroutineContext[Job]?.cancelAndJoin()
                if(imported)repo.db.withTransaction {
                    follows.forEach {repo.dao.removeFollow(it.id)}
                    val keys=repo.dao.allPosts().filter {it.key in posts.map(repo::key)||repo.gson.fromJson(it.payload,Post::class.java).canonicalUrl==childUrl}.map {it.key}
                    repo.dao.removePosts(keys)
                    keys.forEach {key->for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))}
                }
                cached.forEach {(key,old)->if(old==null)repo.dao.removeMedia(listOf(key))else repo.dao.putMedia(old)}
            }
            repo.images.clear();staged.forEach {it.delete()};repo.feedMemory=oldMemory
            listOf(memory,File(memory.path+".bak"),File(memory.path+".new")).forEach {it.delete()}
            ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings();repo.gateway.configure(policy)}
        }
    }
}
