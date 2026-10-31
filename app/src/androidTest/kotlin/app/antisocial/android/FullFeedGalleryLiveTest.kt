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

/** Explicit continuation acquisition and cached-byte restart replays. No
 * injected fingerprints; complete root albums use a separate private opt-in. */
class FullFeedGalleryLiveTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun capturedWindowAcquiresAndJoinsItsPublisherContinuationBeforePublication()=replay(false)
    @Test fun capturedGalleryRebuildsItsEvidenceFromRetainedBytesWithoutRefetchingItsChild()=replay(true)
    @Test fun capturedCompleteAlbumsRebuildTheirEvidenceFromRetainedBytesWithoutRefetching()=replay(true,completeAlbums=true)
    @Test fun capturedCompleteAlbumsKeepTheirProjectionWhileDisplayNamesChange()=replay(true,completeAlbums=true,verifyRename=true)
    @Test fun capturedCompleteAlbumsKeepTheirReadCardWhileALateImageTriggersFreshComparison()=replay(true,completeAlbums=true,verifyLateImage=true)
    @Test fun capturedCompleteAlbumsAdoptALatePlatformWithoutRepeatingTheirMedia()=replay(true,completeAlbums=true,verifyLateOriginal=true)
    private fun replay(cold:Boolean,completeAlbums:Boolean=false,verifyRename:Boolean=false,verifyLateImage:Boolean=false,verifyLateOriginal:Boolean=false) {
        val args=InstrumentationRegistry.getArguments()
        val option=if(completeAlbums)"antisocialFullFeedCompleteAlbums"else if(cold)"antisocialFullFeedGalleryCold"else "antisocialFullFeedGalleryLive"
        Assume.assumeTrue(args.getString("antisocialIsolatedFixtureDevice")=="true"&&args.getString(option)=="true")
        if(verifyRename)Assume.assumeTrue(args.getString("antisocialFeedVerifyDisplayRename")=="true")
        if(verifyLateImage)Assume.assumeTrue(args.getString("antisocialFeedVerifyLateImage")=="true")
        if(verifyLateOriginal)Assume.assumeTrue(args.getString("antisocialFeedVerifyLateOriginal")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val directory=File(ui.activity.filesDir,if(completeAlbums)"full-album-cold-packet"else if(cold)"full-gallery-cold-packet"else "full-gallery-private-packet")
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
        val familyLabel=raw["expectedFamilyLabel"]?.asString ?: "$expectedOriginals originals ·"
        require(familyLabel.length in 8..128)
        val sharedCaption=if(completeAlbums)requireNotNull(raw["sharedCaption"]?.asString).also {require(it.length in 12..4096)}else null
        require(posts.size in 1..1000&&follows.size in 1..2000&&focus.size in 2..CrossPosts.MAX_EDITIONS)
        require(posts.map(repo::key).distinct().size==posts.size&&posts.map(repo::key).containsAll(focus))
        if(completeAlbums)require(focus.size==expectedOriginals&&posts.filter {repo.key(it)in focus}.all {it.parentId==null})
        val lateOriginal=if(verifyLateOriginal)posts.single {repo.key(it)==raw["lateOriginalKey"].asString}.also {
            require(cold&&completeAlbums&&repo.key(it)in focus&&repo.key(it)!=repo.key(root))
        }else null
        val initialPosts=posts.filter {it!=lateOriginal}
        require(posts.any {it.canonicalUrl==childUrl}==cold)
        val files=raw.getAsJsonObject("files").entrySet().associate {(url,v)->
            url to File(directory,v.asString).also {require(it.canonicalFile.parentFile==directory.canonicalFile&&it.isFile)}
        }
        val aliases=files.entries.mapNotNull {(url,file)->ImageAssetIdentity.key(url)?.let {it to file}}.groupBy({it.first},{it.second})
        val lateUrls=if(verifyLateImage)raw.getAsJsonArray("lateImageUrls")?.map {it.asString}
            ?: listOf(requireNotNull(raw["lateImageUrl"]?.asString))else emptyList()
        val lateAssets=lateUrls.map {requireNotNull(ImageAssetIdentity.key(it))}.toSet()
        val latePosts=lateUrls.associateWith {url->posts.first {post->post.media.any {it.type=="image"&&it.url==url}}}
        if(verifyLateImage) {
            require(lateUrls.size in 1..3&&lateAssets.size==lateUrls.size&&completeAlbums&&cold)
            require(latePosts.values.all {repo.key(it) !in focus}&&lateUrls.all {it in files})
            require(posts.filter {repo.key(it)in focus}.flatMap {it.media}.none {ImageAssetIdentity.key(it.url) in lateAssets})
        }
        val output=File(ui.activity.getExternalFilesDir("validation"),if(completeAlbums)"full-feed-album-cold"else if(cold)"full-feed-gallery-cold"else "full-feed-gallery-live").apply {mkdirs()}
        val prefs=repo.prefs.export().toString();val oldMemory=repo.feedMemory
        val policy=repo.gateway.currentPolicy()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val memory=File(ui.activity.cacheDir,"full-feed-gallery-memory.json")
        val reader=repo.galleries.read;val decoder=repo.galleries.image
        val cached=linkedMapOf<String,MediaRow?>();val staged=mutableListOf<File>()
        val events=CopyOnWriteArrayList<Map<String,Any?>>()
        var vm:AntiSocialViewModel?=null;var imported=false;var threadReads=0;var tracing=false
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
                if(lateAssets.isNotEmpty()) {
                    val withheld=(posts.flatMap {p->p.media.flatMap {listOfNotNull(it.url,it.preview)}}+files.keys)
                        .filter {ImageAssetIdentity.key(it) in lateAssets}.distinct()
                    for(url in withheld) {
                        val key=Repository.hash(url);val old=repo.dao.media(key)
                        require(old?.saved!=true){"Late-image qualification uses ordinary fixture cache only"}
                        if(!cached.containsKey(key))cached[key]=old
                        repo.dao.removeMedia(listOf(key))
                    }
                }
                // Retain real bytes without pre-decoding them. The late-source
                // control changes publication timing, not network availability.
                val staging=if(cold&&raw["stageAllCachedPictures"]?.asBoolean==true)posts else posts.filter {repo.key(it)in focus}
                for(post in staging)for(media in post.media) {
                    if(ImageAssetIdentity.key(media.url) in lateAssets)continue
                    val file=files[media.url] ?: ImageAssetIdentity.key(media.url)?.let {key->
                        aliases[key]?.distinctBy {java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}?.singleOrNull()
                    }
                    if(file!=null) {
                        val key=Repository.hash(media.url);repo.lease(key,true)
                        try {
                            if(!cached.containsKey(key))cached[key]=repo.dao.media(key)
                            val destination=File(ui.activity.cacheDir,"gallery-replay-$key").also {file.copyTo(it,overwrite=true);staged+=it}
                            repo.dao.putMedia(MediaRow(key,destination.path,destination.length(),System.currentTimeMillis()))
                        }finally {repo.lease(key,false)}
                    }
                    if(cold) {
                        if(repo.key(post)in focus)require(file!=null){"Cold replay requires exact retained bytes for every focused picture"}
                    }else repo.images.load(media.url,post.contentSourceUrl,1600)
                }
                repo.db.withTransaction {
                    follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
                    initialPosts.forEach {post->
                        val ids=members[repo.key(post)].orEmpty();require(ids.isNotEmpty()&&ids.all {id->follows.any {it.id==id}})
                        val json=repo.gson.toJson(post)
                        repo.dao.putPost(PostRow(repo.key(post),post.platform.id,json,post.publishedAt,System.currentTimeMillis(),null,json.toByteArray().size.toLong(),ids.first()))
                        ids.forEach {repo.dao.putPostFollow(PostFollowRow(repo.key(post),it))}
                    }
                };imported=true
            }
            if(completeAlbums)assertTrue("No focused fingerprint may be injected or decoded before opening",posts.filter {repo.key(it)in focus}.flatMap {it.media}.none {repo.images.identities.knows(it.url)})
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
            if(cold&&args.getString("antisocialFeedWorkTrace")=="true") {
                scope.launch {
                    repo.cardWork.active.map {tasks->tasks.filter {it.label=="Matching editions"}.map {it.id to it.keys.size}}
                        .distinctUntilChanged().collect {event("stage" to "matching-work","jobs" to it)}
                }
                scope.launch {repo.images.identities.revision.collect {event("stage" to "image-revision","revision" to it)}}
                scope.launch {repo.videos.revision.collect {event("stage" to "video-revision","revision" to it)}}
            }
            if(cold&&args.getString("antisocialTracePrivateFeed")=="true") {
                Debug.startMethodTracingSampling(File(output,"cold-feed.trace").path,32*1024*1024,2_000);tracing=true
            }
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
            requireNativeFeedVisible(ui.activity)
            event("stage" to "first-visible","familyRows" to first.size,"completeFamily" to first.any {list->list.any {it.startsWith(familyLabel)}});screenshot("first-visible.png")
            if(lateOriginal!=null) {
                assertFalse("The withheld edition must not be present in the initial card",first.any {text->text.any {it.startsWith(familyLabel)}})
                val sourceBefore=runBlocking {repo.dao.allPosts().associate {it.key to it.payload}}
                assertFalse(sourceBefore.containsKey(repo.key(lateOriginal)))
                val initialFocus=focus-repo.key(lateOriginal)
                repo.feedMemory.markRead("all",initialFocus)
                ui.waitForIdle()
                val topBefore=visibleRows().single().boundsInRoot.top
                event("stage" to "late-original-start")
                runBlocking {
                    repo.db.withTransaction {
                        val ids=members.getValue(repo.key(lateOriginal));require(ids.isNotEmpty())
                        val json=repo.gson.toJson(lateOriginal)
                        repo.dao.putPost(PostRow(repo.key(lateOriginal),lateOriginal.platform.id,json,lateOriginal.publishedAt,System.currentTimeMillis(),null,json.toByteArray().size.toLong(),ids.first()))
                        ids.forEach {repo.dao.putPostFollow(PostFollowRow(repo.key(lateOriginal),it))}
                    }
                }
                ui.waitUntil(60_000){val visible=visibleRows();visible.size==1&&visible.any {node->words(node).any {it.startsWith(familyLabel)}}}
                ui.waitUntil(60_000){visibleRows().any {node->words(node).any {it.startsWith("Image from ")&&lateOriginal.platform.label in it}}}
                ui.waitForIdle();Thread.sleep(100);requireNativeFeedVisible(ui.activity)
                assertTrue("Late source admission preserves the visible card's pixel anchor",kotlin.math.abs(visibleRows().single().boundsInRoot.top-topBefore)<=2f)
                assertTrue("Late source admission preserves previously read originals",repo.feedMemory.get("all").readKeys.containsAll(initialFocus))
                assertEquals("A new edition never rewrites the existing originals",sourceBefore,runBlocking {repo.dao.allPosts().filter {it.key!=repo.key(lateOriginal)}.associate {it.key to it.payload}})
                event("stage" to "late-original-visible","readOriginalsPreserved" to true,"existingSourcesPreserved" to true)
                screenshot("late-original-visible.png")
            }
            // Do not invoke enrich(): the shipping card dwell owns acquisition.
            ui.waitUntil(90_000){runBlocking {repo.dao.allPosts().any {repo.gson.fromJson(it.payload,Post::class.java).canonicalUrl==childUrl}}}
            event("stage" to if(cold)"retained-child-present"else "child-committed")
            val added=runBlocking {repo.dao.allPosts().filter {it.key !in posts.map(repo::key)}}
            assertEquals("Only an actually missing publisher child can be added",if(cold)0 else 1,added.size)
            val child=if(cold)posts.single {it.canonicalUrl==childUrl}else repo.gson.fromJson(added.single().payload,Post::class.java)
            assertEquals(childUrl,child.canonicalUrl)
            if(!completeAlbums) {
                assertEquals(root.id,child.parentId)
                assertEquals(ExtractionStatus.PRESENT,child.extractionCoverage?.replyParent)
                assertNotNull(child.sourceParentId);assertNotNull(child.provenance)
                assertEquals(members[repo.key(root)].orEmpty(),runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            }else assertTrue("Complete albums are captured root originals, never invented replies",posts.filter {repo.key(it)in focus}.all {it.parentId==null})
            ui.waitUntil(60_000){val visible=visibleRows();(!completeAlbums||visible.size==1)&&visible.any {node->words(node).any {it.startsWith(familyLabel)}}}
            if(requiredCaption!=null)ui.waitUntil(60_000){visibleRows().any {node->words(node).any {requiredCaption in it}}}
            requireNativeFeedVisible(ui.activity)
            event("stage" to "full-family-visible");ui.waitForIdle();screenshot("settled.png")
            if(tracing){Debug.stopMethodTracing();tracing=false}
            File(output,"semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            val observed=(posts+child).distinctBy(repo::key).map(repo.images.identities::captured)
            val bindings=members+(repo.key(child) to runBlocking {repo.dao.postFollowIds(repo.key(child))}.toSet())
            val groups=runBlocking {withContext(Dispatchers.Default){CrossPosts.group(observed,follows,bindings)}}
            assertEquals("Every retained original survives grouping",observed.map(repo::key).toSet(),groups.flatMap {it.posts}.map(repo::key).toSet())
            assertEquals("An original cannot occupy two reading groups",observed.size,groups.sumOf {it.posts.size})
            val family=groups.filter {g->g.posts.any {repo.key(it)in focus}}
            assertEquals(1,family.size);assertEquals(expectedOriginals,family.single().posts.size)
            val plan=collectionPlan(family.single().posts,repo.images.identities)
            assertEquals(expectedPictures,plan.attachments.size)
            if(sharedCaption!=null) {
                fun literal(node:androidx.compose.ui.semantics.SemanticsNode):List<String> {
                    val children=node.children.flatMap(::literal)
                    return children.ifEmpty {node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}}
                }
                val text=literal(visibleRows().single()).joinToString("\n")
                assertEquals("The complete visible card shares its heading once",1,Regex(Regex.escape(sharedCaption)).findAll(text).count())
            }
            if(verifyLateOriginal) {
                fun pictures(node:androidx.compose.ui.semantics.SemanticsNode):List<String> =
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf {it.startsWith("Post image ")})+node.children.flatMap(::pictures)
                val tags=pictures(visibleRows().single())
                assertEquals("The native card contains every unique picture once",expectedPictures,tags.size)
                assertEquals(tags.size,tags.distinct().size)
                tags.forEachIndexed {index,tag->
                    ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag(tag))
                    ui.waitForIdle();Thread.sleep(100);requireNativeFeedVisible(ui.activity)
                    screenshot("late-original-picture-$index.png")
                }
            }
            for((step,lateUrl)in lateUrls.withIndex()) {
                val latePost=latePosts.getValue(lateUrl)
                fun lateEvent(vararg fields:Pair<String,Any?>)=event("step" to step,*fields)
                fun quiet()=repo.cardWork.active.value.none {it.label in setOf("Matching editions","Images")}
                fun awaitQuiet() {
                    var since=0L
                    ui.waitUntil(60_000){val now=SystemClock.elapsedRealtime();if(!quiet())since=0 else if(since==0L)since=now;since>0&&now-since>=1000}
                }
                awaitQuiet()
                assertFalse("Every late object's cached alias must remain undecoded initially",repo.images.identities.knows(lateUrl))
                val beforeTop=visibleRows().single().boundsInRoot.top
                val sourceBefore=runBlocking {repo.dao.allPosts().associate {it.key to it.payload}}
                repo.feedMemory.markRead("all",focus)
                assertTrue(repo.feedMemory.get("all").readKeys.containsAll(focus))
                val watching=CompletableDeferred<Unit>()
                val observer=scope.launch {
                    val active=mutableMapOf<Long,Long>()
                    repo.cardWork.active.map {tasks->tasks.filter {it.label=="Matching editions"}.map {it.id}.toSet()}.distinctUntilChanged().collect {tasks->
                        val now=SystemClock.elapsedRealtime()
                        for(id in active.keys.toList())if(id !in tasks){lateEvent("stage" to "late-matching-finished","job" to id,"durationMs" to now-active.getValue(id));active.remove(id)}
                        tasks.filterNot(active::containsKey).forEach {id->active[id]=now;lateEvent("stage" to "late-matching-started","job" to id)}
                        watching.complete(Unit)
                    }
                }
                try {
                    runBlocking {withTimeout(5000){watching.await()}}
                    lateEvent("stage" to "late-image-start","imageRevision" to repo.images.identities.revision.value)
                    runBlocking {
                        val key=Repository.hash(lateUrl);repo.lease(key,true)
                        try {
                            if(!cached.containsKey(key))cached[key]=repo.dao.media(key)
                            val destination=File(ui.activity.cacheDir,"gallery-replay-late-$key").also {files.getValue(lateUrl).copyTo(it,overwrite=true);staged+=it}
                            repo.dao.putMedia(MediaRow(key,destination.path,destination.length(),System.currentTimeMillis()))
                        }finally {repo.lease(key,false)}
                        withTimeout(30_000){repo.images.restoreCachedIdentities(listOf(requireNotNull(latePost).copy(media=latePost.media.filter {it.url==lateUrl}))) }
                    }
                    assertTrue("The shipping decoder must supply the late observation",repo.images.identities.knows(lateUrl))
                    lateEvent("stage" to "late-image-decoded","imageRevision" to repo.images.identities.revision.value)
                    ui.waitUntil(60_000){events.any {it["stage"]=="late-matching-started"&&it["step"]==step}}
                    val activity=SemanticsMatcher("target activity") {node->
                        node.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Card activity ")in focus
                    }
                    ui.waitUntil(10_000){ui.onAllNodes(activity,useUnmergedTree=true).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="Updating"}}
                    lateEvent("stage" to "late-updating-visible");requireNativeFeedVisible(ui.activity);screenshot("late-$step-updating.png")
                    awaitQuiet();ui.waitForIdle();Thread.sleep(100)
                    ui.waitUntil(10_000){ui.onAllNodes(activity,useUnmergedTree=true).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="Ready"}}
                    requireNativeFeedVisible(ui.activity)
                    assertEquals("Late evidence keeps one target card",1,visibleRows().size)
                    assertTrue("Late evidence preserves the target's pixel anchor",kotlin.math.abs(visibleRows().single().boundsInRoot.top-beforeTop)<=2f)
                    assertTrue("An unrelated image cannot unread the viewed originals",repo.feedMemory.get("all").readKeys.containsAll(focus))
                    assertEquals("Image observations never rewrite the acquired source payloads",sourceBefore,runBlocking {repo.dao.allPosts().associate {it.key to it.payload}})
                    lateEvent("stage" to "late-image-visible","readOriginalsPreserved" to true)
                    screenshot("late-$step-ready.png")
                    val current=posts.map(repo.images.identities::captured)
                    val latest=runBlocking {withContext(Dispatchers.Default){CrossPosts.group(current,follows,members)}}
                    assertEquals(current.size,latest.sumOf {it.posts.size})
                    assertEquals(current.map(repo::key).toSet(),latest.flatMap {it.posts}.map(repo::key).toSet())
                    val target=latest.single {g->g.posts.any {repo.key(it)in focus}}
                    assertEquals(focus,target.posts.map(repo::key).toSet())
                    assertEquals(expectedPictures,collectionPlan(target.posts,repo.images.identities).attachments.size)
                    lateEvent("stage" to "late-evidence-verified","matchingJobs" to events.count {it["stage"]=="late-matching-started"&&it["step"]==step},"readOriginalsPreserved" to true,"allSourcesPreserved" to true)
                }finally {observer.cancel()}
            }
            if(cold&&(verifyRename||args.getString("antisocialFeedVerifyWarmReentry")=="true")) {
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
                    if(verifyRename) {
                        val revision=repo.images.identities.revision.value
                        val renamed=follows.mapIndexed {index,follow->follow.copy(name="Local display $index",personName="Local display only")}
                        val renameAt=SystemClock.elapsedRealtime()-started
                        event("stage" to "display-rename-start")
                        runBlocking {
                            repo.db.withTransaction {renamed.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}}
                            withTimeout(5000){repo.follows.first {values->renamed.all {new->values.any {it.id==new.id&&it.name==new.name&&it.personName==new.personName}}}}
                        }
                        ui.waitForIdle();Thread.sleep(250);awaitQuiet()
                        val comparisons=events.count {it["stage"]=="warm-matching-started"&&(it["atMs"]as? Number)?.toLong()?.let {at->at>=renameAt}==true}
                        event("stage" to "display-rename-complete","matchingJobs" to comparisons,"imageRevisionUnchanged" to (revision==repo.images.identities.revision.value))
                        screenshot("display-rename.png")
                        assertEquals("Display-only subscription edits must reuse completed edition analysis",0,comparisons)
                        assertEquals("The rename control must not introduce new picture evidence",revision,repo.images.identities.revision.value)
                        // A real local account-group change has the same posts
                        // and pictures too, but changes ownership admission.
                        // It must retire the old source analysis despite the
                        // already accepted complete album staying readable.
                        val original=renamed.first {it.id in members[repo.key(root)].orEmpty()}
                        val rebound=original.copy(personId=(original.personId ?: original.id)+":binding-control")
                        val beforeBinding=events.count {it["stage"]=="warm-matching-started"}
                        event("stage" to "identity-binding-start")
                        runBlocking {
                            repo.dao.putFollow(FollowRow(rebound.id,rebound.target.platform.id,repo.gson.toJson(rebound)))
                            withTimeout(5000){repo.follows.first {values->values.any {it.id==rebound.id&&it.personId==rebound.personId}}}
                        }
                        ui.waitForIdle();Thread.sleep(250);awaitQuiet()
                        val bindingJobs=events.count {it["stage"]=="warm-matching-started"}-beforeBinding
                        event("stage" to "identity-binding-complete","matchingJobs" to bindingJobs)
                        assertTrue("A changed account-group binding must invalidate source analysis",bindingJobs>0)
                        assertEquals("The binding control changes no picture evidence",revision,repo.images.identities.revision.value)
                    }
                    ui.onNodeWithTag("Primary destination follows").performClick()
                    ui.waitUntil(10_000){model.screen.value.section=="follows"};ui.waitForIdle()
                    if(verifyRename) {
                        ui.waitUntil(10_000){ui.onAllNodesWithText("Local display only").fetchSemanticsNodes().isNotEmpty()}
                        screenshot("display-rename-subscriptions.png")
                    }
                    event("stage" to "warm-reentry-start")
                    ui.onNodeWithTag("Primary destination feed").performClick()
                    ui.waitUntil(30_000){visibleRows().any {node->val text=words(node);text.any {it.startsWith(familyLabel)}&&(requiredCaption==null||text.any {requiredCaption in it})}}
                    ui.waitForIdle();Thread.sleep(100)
                    requireNativeFeedVisible(ui.activity)
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
            if(tracing){Debug.stopMethodTracing();tracing=false}
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
