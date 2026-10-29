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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The actual feed must acquire its missing publisher continuation. No child,
 * parent relationship or fingerprint is supplied by the private fixture. */
class FullFeedGalleryLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun capturedWindowAcquiresAndJoinsItsPublisherContinuationBeforePublication() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString("antisocialFullFeedGalleryLive")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val directory=File(ui.activity.filesDir,"full-gallery-private-packet")
        val input=File(directory,"input.json")
        require(input.isFile&&input.length() in 1..8L*1024*1024)
        val raw=JsonParser.parseString(input.readText()).asJsonObject
        val posts=raw.getAsJsonArray("posts").map {repo.gson.fromJson(it,Post::class.java)}.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=null,videoFootage=null)})}
        val follows=raw.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        val members=raw.getAsJsonObject("memberships").entrySet().associate {(k,v)->k to v.asJsonArray.map {it.asString}.toSet()}
        val focus=raw.getAsJsonArray("focusKeys").map {it.asString}.toSet()
        val root=posts.single {repo.key(it)==raw["rootKey"].asString}
        val childUrl=raw["expectedChildUrl"].asString
        val expectedOriginals=raw["expectedOriginals"].asInt;val expectedPictures=raw["expectedPictures"].asInt
        require(posts.size in 1..1000&&follows.size in 1..2000&&focus.size in 2..CrossPosts.MAX_EDITIONS)
        require(posts.map(repo::key).distinct().size==posts.size&&posts.map(repo::key).containsAll(focus))
        require(posts.none {it.canonicalUrl==childUrl})
        val files=raw.getAsJsonObject("files").entrySet().associate {(url,v)->
            url to File(directory,v.asString).also {require(it.canonicalFile.parentFile==directory.canonicalFile&&it.isFile)}
        }
        val aliases=files.entries.mapNotNull {(url,file)->ImageAssetIdentity.key(url)?.let {it to file}}.groupBy({it.first},{it.second})
        val output=File(ui.activity.getExternalFilesDir("validation"),"full-feed-gallery-live").apply {mkdirs()}
        val prefs=repo.prefs.export().toString();val oldMemory=repo.feedMemory
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val memory=File(ui.activity.cacheDir,"full-feed-gallery-memory.json")
        val reader=repo.galleries.read;val decoder=repo.galleries.image
        val cached=linkedMapOf<String,MediaRow?>();val staged=mutableListOf<File>()
        val events=CopyOnWriteArrayList<Map<String,Any?>>()
        var vm:AntiSocialViewModel?=null;var imported=false
        val started=SystemClock.elapsedRealtime()
        fun event(vararg fields:Pair<String,Any?>) {
            events+=mapOf("atMs" to SystemClock.elapsedRealtime()-started)+fields.toMap()
            File(output,"events.json").writeText(repo.gson.toJson(events))
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
                    repo.images.load(media.url,post.contentSourceUrl,1600)
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
                event("stage" to "thread-start")
                reader(post).also {page->event("stage" to "thread-returned","posts" to page.posts.size,"childPresent" to page.posts.any {it.canonicalUrl==childUrl})}
            }
            repo.galleries.image={url,source->
                event("stage" to "child-picture-start","urlHash" to Repository.hash(url))
                decoder(url,source);event("stage" to "child-picture-decoded","urlHash" to Repository.hash(url))
            }
            val top=posts.filter {repo.key(it)in focus}.maxBy {it.publishedAt ?: 0}
            repo.feedMemory.reconcile("all",posts.map(repo::key));repo.feedMemory.anchor("all",repo.key(top),0)
            val model=AntiSocialViewModel(repo).also {vm=it}
            event("stage" to "open","posts" to posts.size)
            ui.runOnUiThread {model.combined();ui.activity.setContent {AntiSocialApp(model)}}
            val rows=SemanticsMatcher("focused real gallery card"){it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Feed row ")in focus}
            ui.waitUntil(60_000){ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            event("stage" to "first-visible");screenshot("first-visible.png")
            // Do not invoke enrich(): the shipping card dwell owns acquisition.
            ui.waitUntil(90_000){runBlocking {repo.dao.allPosts().any {repo.gson.fromJson(it.payload,Post::class.java).canonicalUrl==childUrl}}}
            event("stage" to "child-committed")
            val added=runBlocking {repo.dao.allPosts().filter {it.key !in posts.map(repo::key)}}
            assertEquals("Commenters must not become followed posts",1,added.size)
            val child=repo.gson.fromJson(added.single().payload,Post::class.java)
            assertEquals(childUrl,child.canonicalUrl);assertEquals(root.id,child.parentId)
            assertEquals(ExtractionStatus.PRESENT,child.extractionCoverage?.replyParent)
            assertNotNull(child.sourceParentId);assertNotNull(child.provenance)
            assertEquals(members[repo.key(root)].orEmpty(),runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            fun words(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}+node.children.flatMap(::words)
            ui.waitUntil(60_000){ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().any {node->words(node).any {it.startsWith("$expectedOriginals originals ·")}}}
            event("stage" to "full-family-visible");ui.waitForIdle();screenshot("settled.png")
            File(output,"semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            val observed=(posts+child).map(repo.images.identities::captured)
            val bindings=members+(repo.key(child) to runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            val family=runBlocking {withContext(Dispatchers.Default){CrossPosts.group(observed,follows,bindings)}}.filter {g->g.posts.any {repo.key(it)in focus}}
            assertEquals(1,family.size);assertEquals(expectedOriginals,family.single().posts.size)
            val plan=collectionPlan(family.single().posts,repo.images.identities)
            assertEquals(expectedPictures,plan.attachments.size)
            File(output,"result.json").writeText(repo.gson.toJson(mapOf("family" to family.single(),"events" to events,"work" to repo.cardWork.active.value)))
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
            ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings()}
        }
    }
}
