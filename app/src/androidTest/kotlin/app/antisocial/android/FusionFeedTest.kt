/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sin

/** Native All/person feeds use actual cache decoding and the production grouping pipeline.
 * All media bytes are local fixtures; fail-closed networking prevents upstream dependence.
 */
@RunWith(AndroidJUnit4::class)
class FusionFeedTest {
    // Queue recomposition on the controlled UI clock, as in production. The
    // legacy unconfined rule can resume a recomposer on an IO/Default publisher
    // when a saved-copy or image observation arrives during a frame.
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val person="fusion-$suffix"
    private val handle="asfusion${suffix.takeLast(6)}"
    private val now=System.currentTimeMillis()
    private val records=mutableListOf<Post>()
    private val accounts=mutableListOf<Follow>()
    private val models=mutableListOf<AntiSocialViewModel>()
    private val mediaKeys=mutableSetOf<String>()
    private val packetMedia=mutableMapOf<String,MediaRow?>()
    private val packetFiles=mutableListOf<File>()
    private val calls=CopyOnWriteArrayList<String>()
    private var originalVideoSampler:(suspend (Media,String)->GatewayVideoSampler.Result)?=null
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalLoader: suspend (Target,(String)->Unit)->Page
    private lateinit var originalPolicy: NetworkPolicy
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope
    private val shared="Securing the border around our neighborhood garden begins with a stronger fence, clear signs, and volunteers who know every gate. This weekend the team repaired damaged panels, checked the greenhouse, planted native flowers, organized shared tools, and prepared safe walking routes for families. Everyone can read the workshop notes, inspect the materials list, and help keep the public grounds welcoming throughout the coming season."
    private val beforeX="Saturday crew update: 12 neighbors joined us."
    private val afterX="Bring gloves to the next workday."
    private val beforeInstagram="Photo diary from the greenhouse volunteers."
    private val afterInstagram="We shared 8 seed trays with families."

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory
        originalLoader=repo.directLoader;originalPolicy=repo.gateway.currentPolicy()
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].let {it.following();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Preparing local fusion fixtures")}
        }
        runBlocking {withTimeout(10_000){repo.feedRefresh.first {!it.running}}}
        repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.theme="dark"
        repo.prefs.activePlatform=Platform.X;repo.prefs.readingGroup(Platform.X,"")
        Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
        memoryFile=File(ui.activity.cacheDir,"fusion-reading-$suffix.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
        repo.directLoader={target,_->Page(records.filter {repo.key(it)=="${target.platform.id}:${ContentIdentity.key(target.platform,target.canonicalUrl)}"})}
        repo.gateway.configure(NetworkPolicy(tor=true))
    }

    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Fusion fixture complete")}
        }
        runBlocking {repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin()}
        repo.feedMemory=originalMemory;repo.directLoader=originalLoader
        runBlocking {
            accounts.forEach {repo.dao.removeFollow(it.id)}
            val keys=records.map(repo::key).distinct()
            keys.forEach {key->
                repo.library.cancel(key);repo.dao.removeBookmark(key)
                repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
                for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            }
            repo.dao.removeRetainedHistory(keys);repo.dao.removePosts(keys)
            mediaKeys.forEach {key->repo.dao.media(key)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(key))}
            packetMedia.forEach {(key,old)->if(old==null)repo.dao.removeMedia(listOf(key))else repo.dao.putMedia(old);repo.lease(key,false)}
            packetFiles.forEach {it.delete()}
        }
        repo.images.clear()
        originalVideoSampler?.let {repo.videos.sample=it};originalVideoSampler=null
        repo.videos.cleanup(true)
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(originalPolicy)
            scheduleRefresh(ui.activity,repo.prefs)
        }
        repo.library.notifyChanged()
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun follow(platform: Platform): Follow {
        val target=repo.routes.target(platform,handle)
        return Follow("$person:${platform.id}",target,"Fusion ${platform.label}",personId=person,personName="Fusion fixture").also {accounts+=it}
    }
    private fun pair(): Pair<Post,Post> {
        val xId="98${suffix}1001"
        val x=Post(xId,Platform.X,"https://x.com/$handle/status/$xId","@$handle","$beforeX\n\n$shared\n\n$afterX",publishedAt=now-60_000,
            media=listOf(Media("https://fusion-x-$suffix.invalid/original.jpg")))
        val instagram=Post("Fusion$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/Fusion$suffix/",handle,"$beforeInstagram\n\n$shared\n\n$afterInstagram",publishedAt=now-59_000,
            media=listOf(Media("https://fusion-instagram-$suffix.invalid/encoded.jpg")))
        records+=listOf(x,instagram)
        return x to instagram
    }
    private fun store(post: Post,follow: Follow)=runBlocking {
        repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
        repo.store(Page(listOf(post)),follow.id,true)
    }
    private fun seed(feed: String,posts: List<Post>,offset: Int=0) {
        val keys=posts.map(repo::key);repo.feedMemory.reconcile(feed,keys)
        repo.feedMemory.anchor(feed,keys.first(),offset)
    }
    private fun model()=AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope) {root,_,order->
        calls+="${root.platform.id}:${order.name}"
        val reply=root.copy(id=root.id+"9",canonicalUrl=if(root.platform==Platform.X)"https://x.com/$handle/status/${root.id}9"else "https://www.instagram.com/p/${root.id}9/",title="",body="${root.platform.label} fusion fixture reply",publishedAt=now+if(root.platform==Platform.X)1_000 else 2_000,parentId=root.id,media=emptyList())
        Page(listOf(root,reply))
    }}).also {it.following();models+=it}
    private fun show(vm: AntiSocialViewModel,all: Boolean) {
        ui.runOnUiThread {if(all)vm.combined()else vm.subscription(person);ui.activity.setContent {AntiSocialApp(vm)}}
    }
    private fun row(post: Post)=ui.onNodeWithTag("Feed row ${repo.key(post)}")
    private fun waitRow(post: Post) {
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
        row(post).assertIsDisplayed()
    }
    private fun top(post: Post)=row(post).fetchSemanticsNode().boundsInRoot.top
    private fun assertFusion() {
        ui.onAllNodesWithTag("Fused post text").assertCountEquals(1)
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(shared)
        listOf(0 to (beforeX to "X"),1 to (beforeInstagram to "Instagram"),3 to (afterX to "X"),4 to (afterInstagram to "Instagram")).forEach {(index,context)->
            ui.onNodeWithTag("Fusion context $index").assertTextEquals(context.first).assert(hasAnySibling(hasContentDescription(context.second,substring=true)))
        }
    }
    private fun cachePicture(post: Post,jpeg: Boolean=false,preload: Boolean=true) {
        val source=picture();val render=if(jpeg)Bitmap.createScaledBitmap(source,256,160,true)else source
        val bytes=ByteArrayOutputStream().use {out->
            assertTrue(render.compress(if(jpeg)Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,if(jpeg)83 else 100,out));out.toByteArray()
        }
        if(render!==source)render.recycle();source.recycle()
        val url=post.media.single().url;val key=Repository.hash(url);mediaKeys+=key
        // These direct fixture writes bypass Repository's atomic media writer.
        // Hold the reader's normal lease while publishing them so startup orphan
        // maintenance cannot sweep the file between its write and database row.
        repo.lease(key,true)
        try {
            val file=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs();writeBytes(bytes)}
            runBlocking {
                repo.dao.putMedia(MediaRow(key,file.path,bytes.size.toLong(),now))
                if(preload)repo.images.load(url,post.canonicalUrl,1024)
            }
        }finally {repo.lease(key,false)}
    }
    private fun assertCapturedSources(expected: List<Post>,actual: List<Post>) {
        assertEquals(expected.map(repo::key).toSet(),actual.map(repo::key).toSet())
        expected.forEach {original->
            val saved=actual.single {repo.key(it)==repo.key(original)}
            assertEquals(original.media.size,saved.media.size)
            saved.media.forEachIndexed {index,media->
                assertNull("These fixtures supply no pre-existing AI declaration",original.media[index].aiMetadata)
                val file=runBlocking {requireNotNull(repo.availableMedia(media.url))}
                val observation=requireNotNull(media.aiMetadata)
                assertTrue(observation.inspectedAt in 1..System.currentTimeMillis())
                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
                assertEquals("The saved annotation must describe these exact local fixture bytes without an AI verdict",AiMetadata(inspectedAt=observation.inspectedAt,sha256=sha),observation)
                // An exact caption can be saved before its picture is decoded.
                // A visual observation is optional; when present, verify its bytes.
                media.visualFingerprint?.let {fingerprint->
                    val bitmap=android.graphics.BitmapFactory.decodeFile(file.path)
                    try {
                        val comparison=MediaIdentityStore();comparison.observe(media.url,bitmap)
                        assertEquals("The captured visual observation must describe this actual decoded fixture",comparison.captured(original).media[index].visualFingerprint,fingerprint)
                        assertTrue(VisualFrames.valid(fingerprint))
                    }finally {bitmap.recycle()}
                }
            }
            assertEquals("Every original source field remains intact",original,saved.copy(media=saved.media.mapIndexed {index,media->media.copy(aiMetadata=original.media[index].aiMetadata,visualFingerprint=original.media[index].visualFingerprint)}))
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"fusion-feed").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    /** Private reproductions exercise the real reader without committing its
     * subscriptions, source text or pictures as public fixtures. */
    @Test fun suppliedPrivateEditionsRenderOneCardAndOneCommonCaptionPerAnnouncement() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("antisocialCollectedFeedPacket")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val input=File(ui.activity.filesDir,"fusion-private-packet/input.json")
        require(input.isFile&&input.length() in 1..4*1024*1024)
        val packet=com.google.gson.JsonParser.parseReader(input.reader()).asJsonObject
        val automatic=packet.get("automaticVideoEvidence")?.asBoolean==true
        if(automatic) {
            Assume.assumeTrue(args.getString("antisocialVideoSceneAutoLive")=="true")
            repo.prefs.videoDownloadWifiOnly=false;repo.prefs.mentionLookups=false
            repo.gateway.configure(NetworkPolicy())
            repo.videos.cleanup(true)
        }
        val cases=packet.getAsJsonArray("cases");require(cases.size() in 1..8)
        runBlocking {require(repo.dao.allFollows().isEmpty()&&repo.dao.allBookmarks().isEmpty()) {"Use a separate empty fixture device"}}
        accounts+=packet.getAsJsonArray("follows").map {repo.gson.fromJson(it,Follow::class.java)}
        require(accounts.size in 1..40)
        val memberships=packet.getAsJsonObject("memberships").entrySet().associate {(key,value)->key to value.asJsonArray.map {it.asString}.toSet()}
        cases.forEach {value->val posts=value.asJsonObject.getAsJsonArray("posts");require(posts.size() in 1..12);records+=posts.map {repo.gson.fromJson(it,Post::class.java)}}
        require(records.size<=64&&records.map(repo::key).distinct().size==records.size)
        val output=File(ui.activity.getExternalFilesDir("validation"),"collected-feed-packet").apply {mkdirs()}
        if(automatic) {
            val sample=repo.videos.sample;originalVideoSampler=sample
            val attempts=CopyOnWriteArrayList<Map<String,Any?>>()
            repo.videos.sample={media,source->
                val started=android.os.SystemClock.elapsedRealtime()
                fun record(vararg values:Pair<String,Any?>) {
                    attempts+=mapOf("urlHash" to Repository.hash(media.url),"platform" to records.firstOrNull {post->post.media.any {it.url==media.url}}?.platform?.id,
                        "elapsedMs" to android.os.SystemClock.elapsedRealtime()-started)+values.toMap()
                    File(output,"automatic-samples.json").writeText(repo.gson.toJson(attempts))
                }
                record("state" to "started")
                try {sample(media,source).also {result->record("state" to "completed","bytes" to result.fetchedBytes,"blocks" to result.fetchedBlocks,"scenes" to result.frames.scenes.size)}}
                catch(failure:Exception){record("state" to "failed","error" to failure.toString().take(500));throw failure}
            }
        }
        runBlocking {
            accounts.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
            for(post in records) {
                val sources=memberships[repo.key(post)].orEmpty();require(sources.isNotEmpty()&&sources.all {id->accounts.any {it.id==id}})
                sources.forEach {repo.store(Page(listOf(post)),it,true)}
            }
            repo.images.identities.clear()
            val files=packet.getAsJsonObject("files")
            files?.entrySet()?.forEach {(url,value)->
                val name=value.asString;require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
                val file=File(input.parentFile,name);require(file.isFile&&file.length() in 1..25L*1024*1024)
                val key=Repository.hash(url);packetMedia[key]=repo.dao.media(key);repo.lease(key,true)
                val cached=File(ui.activity.cacheDir,"fusion-packet-$suffix-$key");file.copyTo(cached);packetFiles+=cached
                repo.dao.putMedia(MediaRow(key,cached.path,cached.length(),System.currentTimeMillis()))
                repo.images.load(url,"https://packet.fixture.invalid/",1600)
            }
        }
        val presentationStart=android.os.SystemClock.elapsedRealtime()
        seed("all",records);val vm=model();show(vm,all=true)
        ui.waitUntil(20_000){ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().isNotEmpty()}
        val seen=linkedMapOf<String,String>()
        fun words(node:androidx.compose.ui.semantics.SemanticsNode):List<String> {
            val nested=node.children.flatMap(::words)
            return nested.ifEmpty {node.config.getOrNull(SemanticsProperties.Text).orEmpty().map {it.text}}
        }
        val originalKeys=records.mapTo(hashSetOf(),repo::key)
        val rows=SemanticsMatcher("Collected feed rows"){
            it.config.getOrNull(SemanticsProperties.TestTag)?.removePrefix("Feed row ") in originalKeys
        }
        // The deck container precedes its asynchronous first frozen batch.
        // Begin inspection at the first actual card, not the empty startup
        // placeholder; otherwise an early gesture can skip the newest row.
        ui.waitUntil(20_000){ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        val firstVisibleMs=android.os.SystemClock.elapsedRealtime()-presentationStart
        ui.onNodeWithTag("Combined feed").performScrollToIndex(0)
        if(automatic) {
            // Only the nominated representatives require new observations.
            // A caption-equivalent edition can have an opaque video URL too,
            // without needing another network read to join the collection.
            val initial=CrossPosts.group(records,accounts,memberships,repo.images.identities::same)
            val plans=CrossPosts.videoFootageRequests(initial,accounts,memberships)
            val clips=plans.flatMap {listOf(it.first,it.second)}.distinctBy(repo::key)
            File(output,"automatic-initial-plan.json").writeText(repo.gson.toJson(mapOf("groups" to initial,"requests" to plans,"clipCount" to clips.size)))
            require(clips.size in 2..4) {"Inspect the private initial plan: expected two to four nominated clips"}
            try {
                ui.waitUntil(90_000){clips.all {post->repo.videos.captured(post).media.any {it.videoFootage?.scenes?.size?.let {count->count>=3}==true}}}
                ui.waitUntil(30_000){ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes().size==1}
            }catch(failure:Throwable) {
                runCatching {
                    val observed=records.map(repo.videos::captured)
                    val groups=runBlocking {CrossPosts.groupWithVideoFootage(observed,accounts,memberships,repo.images.identities::same) {a,b->VideoFootage.related(a,b)}}
                    val memory=android.app.ActivityManager.MemoryInfo();ui.activity.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                    File(output,"automatic-state.json").writeText(repo.gson.toJson(mapOf("posts" to observed,"groups" to groups,"requests" to CrossPosts.videoFootageRequests(groups,accounts,memberships),
                        "activeWork" to repo.cardWork.active.value,"availableMemory" to memory.availMem,"lowMemory" to memory.lowMemory,
                        "powerSave" to ui.activity.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)))
                    File(output,"automatic-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
                    val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    try {File(output,"automatic-failure.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
                }
                throw failure
            }
        }
        File(output,"timings.json").writeText(repo.gson.toJson(mapOf("automaticVideoEvidence" to automatic,"firstVisibleMs" to firstVisibleMs,"settledMs" to (android.os.SystemClock.elapsedRealtime()-presentationStart))))
        var previous:Map<String,Int>?=null;var unchanged=0
        for(page in 0 until 32) {
            ui.waitForIdle();android.os.SystemClock.sleep(250)
            val visible=ui.onAllNodes(rows,useUnmergedTree=true).fetchSemanticsNodes()
            visible.forEach {node->seen[requireNotNull(node.config.getOrNull(SemanticsProperties.TestTag)).removePrefix("Feed row ")]=words(node).joinToString("\n")}
            val positions=visible.associate {requireNotNull(it.config.getOrNull(SemanticsProperties.TestTag)) to it.positionInRoot.y.toInt()}
            unchanged=if(positions==previous)unchanged+1 else 0;previous=positions
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"page-$page.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            if(unchanged>=3)break
            ui.onNodeWithTag("Combined feed").performTouchInput {swipeUp(durationMillis=200)}
        }
        val spaces=Regex("[\\s\\p{Z}]+")
        fun compact(value:String)=value.replace(spaces," ").trim().lowercase()
        val groups=runBlocking(Dispatchers.Default){CrossPosts.groupWithVideoFootage(records.map(repo.videos::captured).map(repo.images.identities::captured),accounts,memberships,repo.images.identities::same) {a,b->VideoFootage.related(a,b)}}
        val reports=cases.mapIndexed {index,value->
            val spec=value.asJsonObject;val keys=spec.getAsJsonArray("posts").map {repo.key(repo.gson.fromJson(it,Post::class.java))}.toSet()
            val shown=seen.filterKeys {it in keys};val passage=compact(spec["once"].asString)
            val count=shown.values.sumOf {Regex(Regex.escape(passage)).findAll(compact(it)).count()}
            val collected=groups.filter {it.posts.any {post->repo.key(post) in keys}}
            val captured=collected.flatMap {it.posts}.map(repo::key).toSet()
            val attachments=collected.flatMap {collectionPlan(it.posts).attachments}
            val expected=spec.get("expectedAttachments")?.asInt
            val save=runBlocking {runCatching {
                val roots=collected.single().posts
                repo.library.save(roots.first(),emptyList(),fusionRoots=roots)
                val retained=repo.library.details(repo.key(roots.first()))!!.fusionRoots.orEmpty()
                require(retained.map(repo::key).toSet()==keys)
                fun source(post:Post)=post.copy(archivedVersion=null,media=post.media.map {it.copy(aiMetadata=null,visualFingerprint=null,attachmentFingerprint=null)})
                roots.forEach {root->require(source(root)==source(retained.single {repo.key(it)==repo.key(root)})) {"A saved original lost source text or media"}}
            }}
            mapOf("case" to index,"rows" to shown.size,"passageOccurrences" to count,"shown" to shown,
                "originalsPreserved" to (captured==keys),"attachments" to attachments.size,
                "expectedAttachmentsPreserved" to (expected==null||expected==attachments.size),"savedOriginalsPreserved" to save.isSuccess,"saveError" to save.exceptionOrNull()?.message)
        }
        File(output,"report.json").writeText(repo.gson.toJson(reports))
        File(output,"groups.json").writeText(repo.gson.toJson(groups))
        // Capture each current card's beginning explicitly as well as the
        // continuous scroll. A fling can move past a short caption between two
        // full-screen media renditions even though semantics retain its text.
        groups.forEachIndexed {index,group->
            // Streaming preserves the visible row's existing anchor even when
            // a newly collected original has an earlier canonical primary.
            val aliases=group.posts.mapTo(hashSetOf(),repo::key)
            val visibleKey=seen.keys.firstOrNull {it in aliases} ?: repo.key(group.primary)
            ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row $visibleKey"))
            // A tall card can already intersect the viewport at its last
            // picture. ScrollToNode then does nothing; explicitly align its
            // beginning so this evidence actually includes the caption.
            val list=ui.onNodeWithTag("Combined feed")
            val row=ui.onNodeWithTag("Feed row $visibleKey",useUnmergedTree=true)
            repeat(3) {
                ui.waitForIdle()
                val delta=row.fetchSemanticsNode().positionInRoot.y-list.fetchSemanticsNode().positionInRoot.y
                if(kotlin.math.abs(delta)>2f)list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){scroll->scroll(0f,delta)}
            }
            ui.waitForIdle()
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output,"card-$index-top.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val spec=cases.map {it.asJsonObject}.firstOrNull {candidate->candidate.getAsJsonArray("posts").any {repo.key(repo.gson.fromJson(it,Post::class.java))==repo.key(group.primary)}}
            if(spec!=null) {
                // Keep the full-passage assertion above. A short prefix can
                // legitimately occur in the introduction and its heading;
                // locate one unique literal node inside this retained card.
                val passage=spec["once"].asString.trim();val words=passage.split(spaces)
                val hints=(listOf(passage)+listOf(12,8,5,3).map {words.take(it).joinToString(" ")}).distinct()
                val scope=hasAnyAncestor(hasTestTag("Feed row $visibleKey"))
                val caption=hints.map {hasText(it,substring=true,ignoreCase=true) and scope}.firstOrNull {
                    ui.onAllNodes(it).fetchSemanticsNodes().size==1
                } ?: error("The retained card has no unique literal passage for its screenshot")
                revealCombinedControl(caption)
                ui.waitForIdle()
                val captionBitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(output,"card-$index-caption.png").outputStream().use {captionBitmap.compress(Bitmap.CompressFormat.PNG,100,it)};captionBitmap.recycle()
            }
        }
        assertTrue("Inspect private native report and screenshots for each announcement",reports.all {it["rows"]==1&&it["passageOccurrences"]==1&&it["originalsPreserved"]==true&&it["expectedAttachmentsPreserved"]==true&&it["savedOriginalsPreserved"]==true})
    }
    private fun revealCombinedControl(matcher: SemanticsMatcher,listTag: String="Combined feed") {
        val list=ui.onNodeWithTag(listTag)
        list.performScrollToNode(matcher)
        // Expose the horizontal shelf once. Repeating performScrollTo on the
        // shelf can undo vertical progress through a tall native card.
        ui.onNode(matcher).performScrollTo()
        repeat(12) {
            val control=ui.onNode(matcher)
            // The nearest scroll parent exposes the icon horizontally; the
            // tall card can still need a real vertical feed gesture afterward.
            if(runCatching {control.assertIsDisplayed()}.isSuccess)return
            val node=control.fetchSemanticsNode()
            val viewport=list.fetchSemanticsNode().boundsInRoot
            val targetY=node.positionInRoot.y+node.size.height/2f
            val distance=(targetY-viewport.center.y).coerceIn(-viewport.height*.65f,viewport.height*.65f)
            list.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,distance)}
            ui.waitForIdle()
        }
        evidence("unreachable-native-control")
        ui.onNode(matcher).assertIsDisplayed()
    }

    private fun cacheGalleryPicture(url:String,index:Int) {
        val bitmap=picture();val canvas=Canvas(bitmap)
        val paint=Paint().apply {color=when(index){1->Color.RED;2->Color.GREEN;else->Color.BLUE}}
        canvas.drawRect(30f,20f,280f,150f,paint)
        val key=Repository.hash(url);mediaKeys+=key
        val file=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs()}
        try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally {bitmap.recycle()}
        runBlocking {repo.dao.putMedia(MediaRow(key,file.path,file.length(),now))}
    }

    @Test fun aColdGalleryScanCollectsItsShorterHeadingWithoutDiscardingUniquePicturesOrContext() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT";val intro="Work will continue."
        val x=oldX.copy(body=heading+" https://t.co/AbCd123",media=listOf(Media("https://fusion-x-$suffix.invalid/gallery-one.png",description="Cold gallery single picture")))
        val gallery=oldInstagram.copy(body="$intro $heading:",media=(1..3).map {Media("https://fusion-instagram-$suffix.invalid/gallery-$it.png",description="Cold gallery picture $it")})
        records+=listOf(x,gallery);store(x,follow(Platform.X));store(gallery,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(x.media.single().url,1);gallery.media.forEachIndexed {i,m->cacheGalleryPicture(m.url,i+1)}
        repo.images.identities.clear()
        assertEquals("Without decoded evidence, distinct CDN addresses cannot prove inclusion",2,CrossPosts.group(records,accounts).size)
        seed("all",records);val vm=model();show(vm,all=true)
        try {
            ui.waitUntil(15_000){ui.onAllNodesWithTag("Feed row ${repo.key(x)}").fetchSemanticsNodes().isNotEmpty()&&
                ui.onAllNodesWithTag("Feed row ${repo.key(gallery)}").fetchSemanticsNodes().isEmpty()&&
                ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
            // The existing reading contract freezes a visible caption while
            // late image evidence merges its sidecars. Deliberate adoption
            // reveals newly found wording without moving it under the finger.
            ui.onNodeWithTag("Jump to newest").performClick()
            ui.waitUntil(10_000){ui.onAllNodesWithTag("Fusion shared text").fetchSemanticsNodes().any {node->
                node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==heading}==true}}
        }catch(failure:Throwable) {
            evidence("failure-cold-gallery")
            val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"fusion-feed")
            File(dir,"failure-cold-gallery-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            File(dir,"failure-cold-gallery-proof.json").writeText(repo.gson.toJson(mapOf("posts" to records.map(repo.images.identities::captured),"groups" to CrossPosts.group(records.map(repo.images.identities::captured),accounts))))
            throw failure
        }
        waitRow(x);row(gallery).assertDoesNotExist()
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(heading)
        ui.onNodeWithTag("Fusion context 0").assertTextEquals(intro)
        ui.onAllNodes(SemanticsMatcher("all distinct gallery pictures"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(3)
        val captured=records.map(repo.images.identities::captured)
        assertEquals(records.map(repo::key).toSet(),repo.library.normalizedFusionRoots(captured.first(),captured).map(repo::key).toSet())
        assertEquals(3,collectedAttachments(captured).size)
        assertEquals(listOf(intro,heading),collectedPostFusion(captured)!!.segments.map {it.text})
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Cold gallery single picture").fetchSemanticsNodes().isNotEmpty()}
        evidence("cold-gallery-short-heading-and-source-context")
    }

    @Test fun complementaryGalleryEditionsCollectTheirChildrenAndSaveEverySource() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val heading="REPAIRS FROM THE COMMUNITY WORKSHOP IN WESTPORT";val intro="Work will continue."
        fun image(lane:String,index:Int)=Media("https://union-$lane-$suffix.invalid/picture-$index.png",description="Union gallery picture $index")
        val instagram=oldInstagram.copy(body="$intro $heading:",media=listOf(image("ig",1),image("ig",2)))
        val fbId="97${suffix}1002"
        val facebook=instagram.copy(id=fbId,platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/$handle/posts/$fbId",
            publishedAt=now-58_000,media=listOf(image("fb",2),image("fb",3)))
        val x=oldX.copy(body=heading,media=listOf(image("x",1),image("x",3)))
        records+=listOf(x,instagram,facebook)
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM));store(facebook,follow(Platform.FACEBOOK))
        for(post in records)post.media.forEach {cacheGalleryPicture(it.url,it.description.last().digitToInt())}
        repo.images.identities.clear();seed("all",records);val vm=model();show(vm,all=true)
        ui.waitUntil(15_000){
            ui.onAllNodesWithTag("Feed row ${repo.key(x)}").fetchSemanticsNodes().isNotEmpty()&&
                ui.onAllNodesWithTag("Feed row ${repo.key(instagram)}").fetchSemanticsNodes().isEmpty()&&
                ui.onAllNodesWithTag("Feed row ${repo.key(facebook)}").fetchSemanticsNodes().isEmpty()
        }
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Fusion shared text").fetchSemanticsNodes().any {node->
            node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==heading}==true}}
        ui.onNodeWithTag("Fusion shared text").assertTextEquals(heading)
        ui.onNodeWithTag("Fusion context 0").assertTextEquals(intro)
        val captured=records.map(repo.images.identities::captured)
        assertEquals(3,collectedAttachments(captured).size)
        runBlocking {repo.library.save(captured.first(),emptyList(),fusionRoots=captured)}
        val saved=runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!
        assertEquals(records.map(repo::key).toSet(),saved.map(repo::key).toSet())
        assertEquals(records.flatMap {it.media}.map {it.url}.toSet(),saved.flatMap {it.media}.map {it.url}.toSet())
        assertEquals(listOf(intro,heading),collectedPostFusion(saved)!!.segments.map {it.text})
        evidence("complementary-gallery-children-saved-with-all-originals")
    }

    @Test fun aShortCaptionWithAnOpaqueLinkKeepsEveryDistinctAttachmentInTheNativeCard() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val caption="Workshop repairs are ready. 🛠"
        val x=oldX.copy(body=caption+" https://t.co/AbCd123",media=oldX.media.map {it.copy(description="Short caption first picture")})
        val instagram=oldInstagram.copy(body=caption,media=oldInstagram.media.map {it.copy(description="Short caption second picture")})
        records+=listOf(x,instagram);store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(x.media.single().url,1);cacheGalleryPicture(instagram.media.single().url,2)
        seed("all",records);val vm=model();show(vm,all=true);waitRow(x)
        row(instagram).assertDoesNotExist();ui.onNodeWithTag("Fusion shared text").assertTextEquals(caption)
        ui.onAllNodes(SemanticsMatcher("both distinct source pictures"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(2)
        val captured=records.map(repo.images.identities::captured)
        assertEquals(2,repo.library.normalizedFusionRoots(captured.first(),captured).size)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Short caption first picture").fetchSemanticsNodes().isNotEmpty()}
        evidence("short-campaign-caption-with-both-distinct-pictures")
    }

    @Test fun aLateSameCaptionPhotographIsAdoptedOffscreenWithoutNeedingNewest() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val x=oldX.copy(body=shared)
        val extra=Media("https://fusion-instagram-$suffix.invalid/additional.png",description="New source photograph")
        val instagram=oldInstagram.copy(body=shared,media=x.media+extra)
        val tail=x.copy(id=x.id+"8",canonicalUrl=x.canonicalUrl+"8",body=(1..30).joinToString("\n"){"Unrelated reading-room notice $it: bring a book."},publishedAt=now-120000,media=emptyList())
        records+=listOf(x,instagram,tail);val xFollow=follow(Platform.X);store(x,xFollow);store(tail,xFollow);store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(x.media.single().url,1);cacheGalleryPicture(extra.url,2)
        val sourceWindow=listOf(x,tail);val feed="late-photo:$person";seed(feed,sourceWindow,offset=19)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x),repo.key(tail) to listOf(tail)));val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {ChronologicalFeed(vm,sourceWindow,accounts,emptySet(),feed,"Photo fixture","Bounded source window",grouped=true,onAdd={},counterparts=known.value)}}}
        waitRow(x);repo.feedMemory.markRead(feed,listOf(repo.key(x)));val before=top(x)
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram),repo.key(tail) to listOf(tail))}
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        assertFalse("The unseen new photograph must not inherit an old read mark",repo.key(instagram) in repo.feedMemory.get(feed).readKeys)
        assertTrue(abs(top(x)-before)<=2f)
        ui.onAllNodes(SemanticsMatcher("still frozen picture inventory"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(1)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(tail)}"))
        ui.onNodeWithTag("Combined feed").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,600f)}
        ui.waitUntil(10000){ui.onAllNodesWithTag("Feed row ${repo.key(x)}").fetchSemanticsNodes().isEmpty()}
        // Off-screen adoption may run once the existing reading work is idle.
        runBlocking {delay(1500)};ui.waitForIdle()
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(x)}"))
        ui.waitUntil(10000){ui.onAllNodesWithTag("Post image ${repo.key(x)} ${extra.url}").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodes(SemanticsMatcher("both adopted photographs"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(2)
        evidence("late-same-caption-new-photograph-adopted")
    }

    @Test fun aVisibleCardOffersCapturedDetailsWithoutMovingItsAnchorOrJumpingToANewerPost() {
        repo.prefs.mentionLookups=false
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val x=oldX.copy(body=shared)
        val extra=Media("https://fusion-instagram-$suffix.invalid/ready.png",description="Captured additional photograph")
        val instagram=oldInstagram.copy(body=shared,media=x.media+extra)
        val tail=x.copy(id=x.id+"6",canonicalUrl=x.canonicalUrl+"6",body=(1..24).joinToString("\n"){"Independent reading-room notice $it: return borrowed books."},publishedAt=now-120_000,media=emptyList())
        val newer=x.copy(id=x.id+"7",canonicalUrl=x.canonicalUrl+"7",body="A separate new workshop announcement is waiting above the reader.",publishedAt=now+60_000,media=emptyList())
        records+=listOf(x,instagram,tail,newer);val xFollow=follow(Platform.X)
        store(x,xFollow);store(tail,xFollow);store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(x.media.single().url,1);cacheGalleryPicture(extra.url,2)
        val window=mutableStateOf(listOf(x,tail));val feed="visible-details:$person";seed(feed,window.value,offset=19)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x),repo.key(tail) to listOf(tail)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,window.value,accounts,emptySet(),feed,"Details fixture","Captured sources",grouped=true,onAdd={},counterparts=known.value)
        }}}
        waitRow(x);repo.feedMemory.markRead(feed,listOf(repo.key(x)));val before=top(x)
        val activity="Card activity ${x.identityKey}"
        ui.waitUntil(10_000){ui.onAllNodesWithTag(activity).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="Ready"}}
        ui.runOnIdle {
            known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram),repo.key(tail) to listOf(tail),repo.key(newer) to listOf(newer))
            window.value=listOf(newer,x,tail)
        }
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        // The normal idle pipeline may accept the new prefix above the reader.
        // The local details action must preserve that established reading point.
        ui.waitUntil(10_000){repo.key(newer) in repo.feedMemory.get(feed).acceptedKeys}
        try {
            ui.waitUntil(5_000){ui.onAllNodesWithTag(activity).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="New details ready"}}
        }catch(failure:ComposeTimeoutException) {
            evidence("failure-visible-captured-details-offer")
            ui.onNodeWithTag(activity).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"New details ready"))
            throw failure
        }
        ui.onNodeWithTag(activity).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"New details ready"))
        assertTrue(abs(top(x)-before)<=2f)
        ui.onAllNodes(SemanticsMatcher("one frozen source photograph"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(1)
        assertFalse(repo.key(instagram) in repo.feedMemory.get(feed).readKeys)
        ui.onNodeWithTag(activity).performClick();ui.onNodeWithText("New details ready").assertIsDisplayed()
        evidence("visible-captured-details-offered")
        ui.onNodeWithText("Later").performClick()
        ui.onNodeWithTag("Post image ${repo.key(x)} ${extra.url}").assertDoesNotExist()
        assertTrue(abs(top(x)-before)<=2f)
        ui.onNodeWithTag(activity).performClick()
        ui.onNodeWithText("Show details").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Post image ${repo.key(x)} ${extra.url}").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("Adoption keeps the active row's pixel anchor",abs(top(x)-before)<=2f)
        assertEquals(repo.key(x),repo.feedMemory.get(feed).anchorKey)
        assertTrue(repo.key(newer) in repo.feedMemory.get(feed).acceptedKeys)
        row(newer).assertIsNotDisplayed()
        ui.onNodeWithTag(activity).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Ready"))
        ui.onAllNodes(SemanticsMatcher("both captured photographs"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(2)
        val captured=listOf(x,instagram).map(repo.images.identities::captured)
        runBlocking {repo.library.save(captured.first(),emptyList(),fusionRoots=captured)}
        assertEquals(captured.map(repo::key).toSet(),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!.map(repo::key).toSet())
        evidence("visible-captured-details-adopted-in-place")
    }

    @Test fun anotherSourceForAnExistingCaptionVariantDoesNotOfferNewMaterial() {
        repo.prefs.mentionLookups=false
        val (x,instagram)=pair();store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val blue=x.copy(id="Blue$suffix",platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/studio.example/post/copy$suffix",publishedAt=now-58_000)
        val blueFollow=Follow("$person:${Platform.BLUESKY.id}",Target(Platform.BLUESKY,TargetKind.PROFILE,"studio.example","https://bsky.app/profile/studio.example"),"Fusion Bluesky",personId=person,personName="Fusion fixture").also {accounts+=it}
        records+=blue;store(blue,blueFollow)
        val feed="source-only:$person";seed(feed,listOf(x),offset=19)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,listOf(x),accounts,emptySet(),feed,"Source fixture","Captured sources",grouped=true,onAdd={},counterparts=known.value)
        }}}
        waitRow(x);assertFusion();repo.feedMemory.markRead(feed,listOf(repo.key(x),repo.key(instagram)));val before=top(x)
        ui.runOnIdle {known.value=listOf(x,instagram,blue).associate {repo.key(it)to listOf(x,instagram,blue)}}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Bluesky original actions").fetchSemanticsNodes().isNotEmpty()&&repo.key(blue)in repo.feedMemory.get(feed).readKeys}
        val activity="Card activity ${x.identityKey}"
        ui.waitUntil(10_000){ui.onAllNodesWithTag(activity).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="Ready"}}
        assertTrue(abs(top(x)-before)<=2f);assertFusion()
        ui.onNodeWithTag(activity).performClick();ui.onNodeWithText("Ready to read").assertIsDisplayed()
        ui.onNodeWithText("Show details").assertDoesNotExist()
        evidence("source-only-attribution-keeps-ready-status")
        ui.onNodeWithText("Done").performClick()
    }

    @Test fun aCapturedCaptionRevisionCanBeShownHereWithoutRetainingItsSupersededWording() {
        repo.prefs.mentionLookups=false
        val (x,instagram)=pair();store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val feed="caption-ready:$person";seed(feed,listOf(x),offset=19)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,listOf(x),accounts,emptySet(),feed,"Revision fixture","Captured sources",grouped=true,onAdd={},counterparts=known.value)
        }}}
        waitRow(x);assertFusion();val before=top(x)
        val revised="We shared 14 seed trays with families."
        val updated=instagram.copy(body="$beforeInstagram\n\n$shared\n\n$revised")
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,updated),repo.key(updated) to listOf(x,updated))}
        val activity="Card activity ${x.identityKey}"
        ui.waitUntil(10_000){ui.onAllNodesWithTag(activity).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="New details ready"}}
        ui.onNodeWithText(afterInstagram).assertExists();ui.onNodeWithText(revised).assertDoesNotExist()
        ui.onNodeWithTag(activity).performClick()
        // A newer captured revision can arrive while the disclosure is open.
        // Confirming must use that generation, not the older dialog snapshot.
        val latest="We shared 16 seed trays with families."
        val newest=updated.copy(body="$beforeInstagram\n\n$shared\n\n$latest")
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,newest),repo.key(newest) to listOf(x,newest))}
        ui.waitUntil(10_000){ui.onAllNodesWithTag(activity).fetchSemanticsNodes().any {it.config.getOrNull(SemanticsProperties.StateDescription)=="New details ready"}}
        ui.onNodeWithText("Show details").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithText(latest).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(afterInstagram).assertDoesNotExist();ui.onNodeWithText(revised).assertDoesNotExist();ui.onAllNodesWithText(shared).assertCountEquals(1)
        assertTrue(abs(top(x)-before)<=2f);assertEquals(repo.key(x),repo.feedMemory.get(feed).anchorKey)
        ui.onNodeWithTag(activity).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Ready"))
        evidence("caption-revision-adopted-in-place")
    }

    @Test fun identicalVideoCoversUseOneSlotWithBothSourceRecordingsSelectableAndSaved() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val preview="https://fusion-x-$suffix.invalid/shared-cover.png"
        val first=Media("https://video-x-$suffix.invalid/first.mp4","video",preview,"Shared video cover")
        val second=Media("https://video-instagram-$suffix.invalid/second.mp4","video",preview,"Shared video cover")
        val x=oldX.copy(body=shared,media=listOf(first));val instagram=oldInstagram.copy(body=shared,media=listOf(second))
        records+=listOf(x,instagram);store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(preview,1)
        seed("all",records);val vm=model();show(vm,all=true);waitRow(x)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Media editions ${first.url}"))
        ui.onNodeWithTag("Media edition ${second.url}").performClick()
        ui.onNodeWithTag("Media sources ${second.url}").assertTextEquals("Video from Instagram")
        ui.onNodeWithTag("Media sources ${first.url}").assertDoesNotExist()
        val plan=collectionPlan(records,repo.images.identities)
        assertEquals(2,plan.attachments.size);assertEquals(1,collectedMediaGroups(plan.attachments).size)
        val snapshot=contextSnapshot(repo,x,records)
        assertEquals(setOf(first.url,second.url),snapshot.media.map {it.url}.toSet())
        runBlocking {repo.library.save(x,emptyList(),fusionRoots=records)}
        val captured=runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!
        assertEquals(setOf(first.url,second.url),captured.flatMap {it.media}.map {it.url}.toSet())
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasText("Play video"))
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("Shared video cover").fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("Play video").assertCountEquals(1)
        evidence("same-video-cover-distinct-recording-choices")
    }

    @Test fun differentVideoOpeningFramesUseOnePlayerWithBothSourceEditions() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val first=Media("https://video-x-$suffix.invalid/first.mp4","video","https://fusion-x-$suffix.invalid/first-cover.png","First source video cover")
        val second=Media("https://video-instagram-$suffix.invalid/second.mp4","video","https://fusion-instagram-$suffix.invalid/second-cover.png","Second source video cover")
        val x=oldX.copy(body=shared,media=listOf(first));val instagram=oldInstagram.copy(body=shared,media=listOf(second))
        records+=listOf(x,instagram);store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(first.preview!!,1);cacheGalleryPicture(second.preview!!,2)
        seed("all",records);val vm=model();show(vm,all=true);waitRow(x)
        revealCombinedControl(hasTestTag("Media edition ${second.url}"))
        ui.onNodeWithTag("Media edition ${second.url}").performClick()
        ui.onNodeWithTag("Media sources ${second.url}").assertTextEquals("Video from Instagram")
        ui.onAllNodesWithText("Play video").assertCountEquals(1)
        assertEquals(2,contextSnapshot(repo,x,records).media.size)
        runBlocking {repo.library.save(x,emptyList(),fusionRoots=records)}
        assertEquals(2,runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!.flatMap {it.media}.size)
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("Second source video cover").fetchSemanticsNodes().isNotEmpty()}
        evidence("different-video-opening-frames-one-player")
    }

    @Test fun anOlderLateCounterpartDoesNotMoveAReadCollectionBackBelowTheReader() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val x=oldX.copy(body=shared,publishedAt=now)
        val instagram=oldInstagram.copy(body=shared,publishedAt=now-2*24*60*60*1000)
        val middle=x.copy(id=x.id+"8",canonicalUrl=x.canonicalUrl+"8",body="A separate workshop update: the reading room is open to visitors this afternoon.",publishedAt=now-24*60*60*1000,media=emptyList())
        val tail=x.copy(id=x.id+"9",canonicalUrl=x.canonicalUrl+"9",body="The earlier volunteer meeting has finished. The next gathering will be announced separately.",publishedAt=now-3*24*60*60*1000,media=emptyList())
        records+=listOf(x,instagram,middle,tail);records.forEach {store(it,follow(it.platform))};cachePicture(x);cachePicture(instagram,jpeg=true)
        val feed="stable-late-collection:$person";seed(feed,records)
        val known=mutableStateOf(records.associate {repo.key(it) to listOf(it)});val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {ChronologicalFeed(vm,records,accounts,emptySet(),feed,"Late-source fixture","Actual reading order",grouped=true,onAdd={},counterparts=known.value)}}}
        waitRow(x);repo.feedMemory.markRead(feed,listOf(repo.key(x)))
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(middle)}"))
        val before=top(middle)
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram),repo.key(middle) to listOf(middle),repo.key(tail) to listOf(tail))}
        runBlocking {delay(1500)};ui.waitForIdle()
        assertTrue("The current reading point stays fixed",abs(top(middle)-before)<=2f)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(x)}"))
        ui.waitUntil(10000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        evidence("older-counterpart-retains-original-reading-position")
    }

    @Test fun shortLiteralVariantsRetainBothPicturesAndBothSourceCaptionsInContextAndSavedCopy() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val x=oldX.copy(body="Workshop repairs, ready for inspection.");val instagram=oldInstagram.copy(body="Workshop repairs ready for inspection.")
        records+=listOf(x,instagram);store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cacheGalleryPicture(x.media.single().url,1);cacheGalleryPicture(instagram.media.single().url,2)
        seed("all",records);val vm=model();show(vm,all=true);waitRow(x)
        ui.onAllNodesWithText(x.body).assertCountEquals(1);ui.onAllNodesWithText(instagram.body).assertCountEquals(1)
        ui.onAllNodes(SemanticsMatcher("complete short-variant media"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(2)
        val snapshot=contextSnapshot(repo,x,records)
        assertTrue(snapshot.text.contains(x.body));assertTrue(snapshot.text.contains(instagram.body));assertEquals(2,snapshot.media.size)
        runBlocking {repo.library.save(x,emptyList(),fusionRoots=records)}
        assertEquals(records.map {it.body}.toSet(),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!.map {it.body}.toSet())
        evidence("short-literal-source-blocks-preserve-text-and-media")
    }

    @Test fun sameBatchSnapshotSelectionRetainsTheCompleteGalleryBeforeStorageAndPageReplay() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val rich=oldX.copy(body=shared,media=(1..5).map {Media("https://fusion-x-$suffix.invalid/complete-$it.png")})
        val thin=rich.copy(title=shared.take(120)+"...",media=rich.media.take(1));records+=rich
        val account=follow(Platform.X);store(rich,account)
        mediaKeys+=repo.pageKey(account.target)
        for(input in listOf(listOf(rich,thin),listOf(thin,rich)))runBlocking {
            repo.store(Page(input),account.id,true)
            val stored=repo.gson.fromJson(requireNotNull(repo.dao.post(repo.key(rich))).payload,Post::class.java)
            assertEquals(rich,stored)
            repo.rememberPage(account.target,Page(input))
            val replay=requireNotNull(repo.cachedPage(account.target))
            assertEquals(1,replay.posts.size);assertEquals(5,replay.posts.single().media.size)
        }
    }

    @Test fun anObservedReencodedPictureAddsAttributionWithoutReopeningAnAlreadyReadAnnouncement() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val x=oldX.copy(body=shared);val instagram=oldInstagram.copy(body=shared)
        records+=listOf(x,instagram);store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val feed="read-alias:$person";seed(feed,listOf(x));val known=mutableStateOf(mapOf(repo.key(x) to listOf(x)));val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {ChronologicalFeed(vm,listOf(x),accounts,emptySet(),feed,"Read fixture","Known reencoding",grouped=true,onAdd={},counterparts=known.value)}}}
        waitRow(x);repo.feedMemory.markRead(feed,listOf(repo.key(x)))
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram))}
        ui.waitUntil(10000){repo.key(instagram) in repo.feedMemory.get(feed).readKeys}
        assertTrue(repo.key(x) in repo.feedMemory.get(feed).readKeys)
        ui.onAllNodes(SemanticsMatcher("one already-read photograph"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(1)
    }

    @Test fun oneVideoUrlWithConflictingTimingObservationsKeepsSelectableDistinctKeys() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val preview="https://fusion-x-$suffix.invalid/observed-cover.png"
        val media=Media("https://video-x-$suffix.invalid/observed.mp4","video",preview,durationMs=10000)
        val x=oldX.copy(body=shared,media=listOf(media,media.copy(durationMs=90000)))
        records+=x;store(x,follow(Platform.X));cacheGalleryPicture(preview,1)
        assertEquals(2,collectedAttachments(listOf(x)).size)
        seed("all",listOf(x));val vm=model();show(vm,all=true);waitRow(x)
        revealCombinedControl(hasText("Video 2"))
        ui.onNodeWithText("Video 2").performClick().assertIsSelected()
        ui.onAllNodesWithText("Play video").assertCountEquals(1)
        evidence("same-url-timing-observations-remain-selectable")
    }

    @Test fun attachmentLayoutAndCdnCopiesRenderOneCaptionTwoPicturesAndBothCommentStreams() {
        val (oldX,oldInstagram)=pair();val caption=shared.trimEnd('.')
        fun imagePath(index:Int)="/v/t39.30808-6/123456${suffix}_${234567890+index}_345678901_n.jpg"
        val first=Media("https://scontent-a.xx.fbcdn.net"+imagePath(0),description="First collected attachment")
        val alias=first.copy(url="https://scontent-b.cdninstagram.com"+imagePath(0)+"?stp=dst-jpg_e35_p1080x1080_tt6&oh=other")
        val second=Media("https://scontent-b.cdninstagram.com"+imagePath(1),description="Second collected attachment")
        val x=oldX.copy(body=caption+" ⬇️",media=listOf(first))
        val instagram=oldInstagram.copy(body=caption+":",media=listOf(alias,second))
        records[records.indexOf(oldX)]=x;records[records.indexOf(oldInstagram)]=instagram
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        for(media in listOf(first,alias,second)) {
            val key=Repository.hash(media.url);mediaKeys+=key
            val bitmap=Bitmap.createBitmap(256,160,Bitmap.Config.ARGB_8888).apply {eraseColor(if(media==second)Color.RED else Color.BLUE)}
            val file=File(repo.context.cacheDir,"content/$key").apply {parentFile?.mkdirs()}
            try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally {bitmap.recycle()}
            runBlocking {repo.dao.putMedia(MediaRow(key,file.path,file.length(),now))}
        }
        assertEquals(2,collectedMedia(listOf(x,instagram)).size)
        seed("all",listOf(x,instagram));val vm=model();show(vm,all=true);waitRow(x)
        row(instagram).assertDoesNotExist();ui.onAllNodesWithTag("Fused post text").assertCountEquals(1)
        ui.onNodeWithText(caption+":").assertExists()
        ui.onAllNodes(SemanticsMatcher("collected attachment nodes"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(2)
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Post image ${repo.key(x)} ${first.url}"))
        ui.onNodeWithTag("Post image ${repo.key(x)} ${first.url}").performClick()
        ui.onNodeWithText("1 / 2").assertIsDisplayed()
        val decoded=hasContentDescription(first.description) and hasAnyAncestor(isDialog())
        ui.waitUntil(10_000){ui.onAllNodes(decoded).fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(decoded).assertIsDisplayed();evidence("attachment-copies-viewer-two-pictures")
        ui.onNodeWithContentDescription("Close image").performClick()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val comments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(comments);ui.onNode(comments).performClick();ui.onNodeWithTag("Platform comments instagram").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab twitter").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.X.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        assertEquals(setOf(repo.key(x),repo.key(instagram)),vm.discussions.state.value!!.posts.map(repo::key).toSet())
        assertEquals(2,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
        evidence("attachment-copies-all-original-comment-streams")
        ui.onNodeWithContentDescription("Close discussions").performClick()
        assertEquals(x.body,runBlocking {repo.dao.post(repo.key(x))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
        assertEquals(instagram.media,runBlocking {repo.dao.post(repo.key(instagram))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.media)
    }

    @Test fun compoundHandleAndFullWorkNameUseOneCaptionOnePictureAndSaveBothOriginals() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val tail=" telescope recently recorded stars forming inside this distant cloud. This region contains bright young stars surrounded by large amounts of dust and gas. The observing team published the original picture and the instrument settings so readers can inspect the result and compare earlier observations."
        val x=oldX.copy(body="The @AsterDawn"+tail)
        val fb=oldInstagram.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/$handle/posts/98${suffix}1002",body="Aster's Maya Dawn Space"+tail)
        records+=listOf(x,fb);store(x,follow(Platform.X));store(fb,follow(Platform.FACEBOOK))
        cachePicture(x);cachePicture(fb,jpeg=true)
        assertTrue(repo.images.identities.same(x,fb))
        seed("all",listOf(x,fb));val vm=model();show(vm,all=true);waitRow(x)
        row(fb).assertDoesNotExist();ui.onAllNodesWithTag("Fused post text").assertCountEquals(1)
        ui.onNodeWithText(fb.body).assertExists()
        ui.onAllNodes(SemanticsMatcher("one collected picture"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(x)} ")==true}).assertCountEquals(1)
        val label=ui.onNodeWithTag("Media sources ${x.media.single().url}").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""){it.text}
        assertTrue(label.startsWith("Image from "))
        val platforms=label.removePrefix("Image from ").split(" · ")
        assertEquals(2,platforms.size);assertEquals(setOf("X","Facebook"),platforms.toSet())
        val save=hasContentDescription("Save post") and hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        assertCapturedSources(listOf(x,fb),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!)
        evidence("compound-reference-one-caption-one-picture")
    }

    @Test fun smallCaptionEditsWeaveInlineWithThreePlatformMarksAndSaveAllOriginals() {
        val (oldX,oldInstagram)=pair();records.removeAll(listOf(oldX,oldInstagram))
        val intro="Delivering the equipment for the community workshop. The published schedule covers the Saturday morning session and the afternoon demonstration."
        val end="These are the results of the training program in action for everyone attending the public workshop."
        val x=oldX.copy(title="",body="$intro $end")
        val instagram=oldInstagram.copy(title="",body="$intro ${end.replace("the results","the direct results")}")
        val fb=instagram.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/$handle/posts/98${suffix}1002",media=listOf(Media("https://fusion-fb-$suffix.invalid/original.png")))
        records+=listOf(x,fb,instagram)
        listOf(x,fb,instagram).forEach {store(it,follow(it.platform));cachePicture(it,jpeg=it.platform==Platform.INSTAGRAM)}
        seed("all",listOf(x,fb,instagram));val vm=model();show(vm,all=true);waitRow(x)
        row(fb).assertDoesNotExist();row(instagram).assertDoesNotExist()
        ui.onNodeWithTag("Fusion woven text").assertTextContains(intro,substring=true)
        listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM).forEach {platform->
            ui.onNodeWithContentDescription("${platform.label} wording source",useUnmergedTree=true).assertExists()
        }
        val captured=listOf(x,fb,instagram).map(repo.images.identities::captured)
        val fusion=collectedPostFusion(captured)!!
        assertEquals(1,fusion.weave!!.count {!it.shared});assertTrue(fusion.segments.single().text.contains("direct results"))
        assertEquals(1,collectedAttachments(captured).size)
        evidence("three-platform-inline-wording")
        ui.onNodeWithContentDescription("Facebook wording source",useUnmergedTree=true).performClick()
        ui.waitUntil(5_000){vm.screen.value.target?.canonicalUrl?.trimEnd('/')==fb.canonicalUrl.trimEnd('/')&&!vm.screen.value.loading}
        show(vm,all=true);waitRow(x)
        ui.runOnIdle {repo.prefs.theme="light";ui.activity.setContent {AntiSocialApp(vm)}}
        waitRow(x);ui.onNodeWithTag("Fusion woven text").assertExists();evidence("three-platform-inline-wording-light")
        ui.runOnIdle {repo.prefs.theme="dark";ui.activity.setContent {AntiSocialApp(vm)}}
        waitRow(x)
        val save=hasContentDescription("Save post") and hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==3}
        assertCapturedSources(listOf(x,fb,instagram),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!)
    }

    @Test fun settingsCachePurgeRefillsTheNativeFeedAndRetainsConfigurationAndSavedText() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val (x,instagram)=pair();store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val saved=runBlocking {repo.library.save(x,emptyList())}
        val loader=repo.followLoader;val next=repo.followPageLoader
        repo.prefs.wifiOnly=false
        repo.followLoader={account->
            val proof=Provenance(Source(account.target.platform,"native-fixture","https://fusion-feed-fixture.invalid"),System.currentTimeMillis(),account.target.canonicalUrl)
            Page(records.filter {it.platform==account.target.platform}.map {it.copy(provenance=proof)},provenance=proof)
        };repo.followPageLoader={_,_->Page(emptyList())}
        val vm=model();show(vm,all=true);waitRow(x)
        try {
            ui.runOnIdle {vm.settings(SettingsPage.STORAGE)}
            ui.onNodeWithTag("Settings list").performScrollToNode(hasText("Clear cache"))
            val config=repo.prefs.export().toString();val subscriptions=runBlocking {repo.dao.allFollows()}
            ui.onNodeWithText("Clear cache").performClick()
            ui.waitUntil(15_000){runBlocking {repo.dao.post(repo.key(x))==null&&repo.dao.post(repo.key(instagram))==null}}
            ui.waitUntil(15_000){ui.onAllNodesWithText("Clearing cache…").fetchSemanticsNodes().isEmpty()}
            assertEquals(config,repo.prefs.export().toString());assertEquals(subscriptions,runBlocking {repo.dao.allFollows()})
            assertEquals(x.body,runBlocking {repo.library.copy(saved)}!!.post.body)
            runBlocking {repo.refresh(personId=person)}
            assertEquals(2,runBlocking {repo.dao.followedPosts(accounts.map {it.id}).first()}.size)
            cachePicture(x);cachePicture(instagram,jpeg=true);show(vm,all=true);waitRow(x);assertFusion()
            evidence("cache-cleared-and-refilled")
        }finally {repo.followLoader=loader;repo.followPageLoader=next;runBlocking {repo.library.remove(saved)}}
    }

    @Test fun allFeedFusesSharedPictureAndCaptionPreservesContextAndEveryCommentStream() {
        val (x,instagram)=pair();store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        assertTrue("Actual decoded cached images must supply cross-host identity evidence",repo.images.identities.same(x,instagram))
        seed("all",listOf(x,instagram));val vm=model();show(vm,all=true);waitRow(x)
        assertFusion();row(instagram).assertDoesNotExist()
        val knownTags=setOf("Feed row ${repo.key(x)}","Feed row ${repo.key(instagram)}")
        ui.onAllNodes(SemanticsMatcher("fixture feed cards") {it.config.getOrNull(SemanticsProperties.TestTag) in knownTags}).assertCountEquals(1)
        ui.onNodeWithText("2 platforms · combined post").assertExists()
        evidence("all-fused-attributed-context")
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val save=hasContentDescription("Save post") and fixtureCard
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        assertCapturedSources(listOf(x,instagram),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!)
        val xComments=hasContentDescription("X original actions") and fixtureCard
        val instagramComments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed().performClick();ui.onNodeWithTag("Platform comments instagram").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab instagram").assertIsSelected()
        ui.onNodeWithTag("Discussion order CHRONOLOGICAL").assertIsSelected().assert(hasText("Newest"))
        ui.onNodeWithText("Instagram fusion fixture reply").assertIsDisplayed()
        ui.onNodeWithTag("Discussion tab twitter").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.X.id)?.page!=null}
        ui.onNodeWithText("X fusion fixture reply").assertIsDisplayed()
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        assertEquals(2,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
        ui.onNodeWithTag("Discussion order SLOP").performClick()
        ui.waitUntil(5_000){calls.containsAll(listOf("twitter:SLOP","instagram:SLOP"))&&vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        ui.onNodeWithTag("Discussion tab all").assertIsSelected()
        ui.onNodeWithTag("Discussion order SLOP").assertIsSelected()
        evidence("fused-all-platform-comment-streams")
        ui.onNodeWithTag("Discussion order CHRONOLOGICAL").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM),vm.discussions.state.value!!.posts.map {it.platform}.toSet())
        ui.onNodeWithContentDescription("Close discussions").performClick()
        assertEquals(x.body,runBlocking {repo.dao.post(repo.key(x))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
        assertEquals(instagram.body,runBlocking {repo.dao.post(repo.key(instagram))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
    }

    @Test fun aTitleOnlySubmissionAndItsPublishersExplanationReadOnceAndSaveEveryThread() {
        val target=follow(Platform.REDDIT)
        val parentId="ab${suffix}";val title="Public workshop opens a new community equipment room"
        val p=Provenance(Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL),now,"https://www.reddit.com/user/$handle/.rss")
        val parent=Post("post:$parentId",Platform.REDDIT,"https://www.reddit.com/user/$handle/comments/$parentId/workshop","u/$handle","",title,publishedAt=now-60_000,provenance=p)
        val comment=Post("comment:$parentId:cd$suffix",Platform.REDDIT,"https://www.reddit.com/r/u_$handle/comments/$parentId/workshop/cd$suffix","/u/$handle",shared,"/u/$handle on $title",publishedAt=now-40_000,provenance=p)
        val wave=comment.copy(id="comment:$parentId:ef$suffix",canonicalUrl=comment.canonicalUrl.replace("cd$suffix","ef$suffix"),body="👋",publishedAt=now-20_000)
        records+=listOf(parent,comment,wave);records.forEach {store(it,target)}
        seed("all",records);val vm=model();show(vm,all=true);waitRow(parent)
        row(comment).assertDoesNotExist();ui.onAllNodesWithText(title).assertCountEquals(1);ui.onAllNodesWithText(shared).assertCountEquals(1)
        val save=hasContentDescription("Save post") and hasAnyAncestor(hasTestTag("Feed row ${repo.key(parent)}"))
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.onAllNodesWithText("👋").assertCountEquals(1)
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(parent))}?.fusionRoots?.size==3}
        assertCapturedSources(records,runBlocking {repo.library.details(repo.key(parent))}!!.fusionRoots.orEmpty())
        evidence("publisher-thread-supplement-one-card-and-both-originals")
    }

    @Test fun aFullySharedCaptionUsesOneFusedBodyAndSavesBothOriginalEditions() {
        val (originalX,originalInstagram)=pair()
        val x=originalX.copy(body=shared,title="RT by @WhiteHouse: $shared");val instagram=originalInstagram.copy(body=shared)
        records[records.indexOf(originalX)]=x;records[records.indexOf(originalInstagram)]=instagram
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        seed("all",listOf(x,instagram));val vm=model();show(vm,all=true);waitRow(x)
        ui.onAllNodesWithTag("Fused post text").assertCountEquals(1)
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        ui.onNode(hasTestTag("Post attribution") and hasText("Reposted by ",substring=true)).assertExists()
        ui.onNodeWithText(x.title).assertDoesNotExist()
        row(instagram).assertDoesNotExist()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val save=hasContentDescription("Save post") and fixtureCard
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        assertCapturedSources(listOf(x,instagram),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!)
        assertEquals(shared,runBlocking {repo.dao.post(repo.key(x))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
        assertEquals(shared,runBlocking {repo.dao.post(repo.key(instagram))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
        evidence("all-shared-caption-once-with-both-saved-editions")
    }

    @Test fun aSpecificShortCaptionSavesBothMatchedSourceEditionsWithoutDownloadingMedia() {
        val shortCaption="We really got flying cars before GTA 6"
        val (originalX,originalInstagram)=pair()
        val x=originalX.copy(body=shortCaption,title="");val instagram=originalInstagram.copy(body=shortCaption,title="")
        records[records.indexOf(originalX)]=x;records[records.indexOf(originalInstagram)]=instagram
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        val keys=listOf(x,instagram).map(repo::key)
        val originalPayloads=runBlocking {keys.associateWith {requireNotNull(repo.dao.post(it)).payload}}
        cachePicture(x);cachePicture(instagram,jpeg=true)
        assertTrue("The short caption needs independently decoded cross-host image evidence",repo.images.identities.same(x,instagram))
        seed("all",listOf(x,instagram));val vm=model();show(vm,all=true);waitRow(x)
        ui.onAllNodesWithText(shortCaption).assertCountEquals(1)
        row(instagram).assertDoesNotExist()
        ui.onNodeWithText("2 platforms · matching post").assertExists()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val xComments=hasContentDescription("X original actions") and fixtureCard
        val instagramComments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed()
        val save=hasContentDescription("Save post") and fixtureCard
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        val saved=runBlocking {repo.library.copy(repo.key(x))}!!
        assertCapturedSources(listOf(x,instagram),saved.details!!.fusionRoots!!)
        assertTrue("Quick Save preserves text and original editions without selecting attachments",saved.details!!.selectedAssets.isEmpty())
        assertTrue("Quick Save must not queue source media downloads",saved.assets.isEmpty())
        assertEquals(originalPayloads,runBlocking {keys.associateWith {requireNotNull(repo.dao.post(it)).payload}})
        ui.onAllNodesWithText(shortCaption).assertCountEquals(1)
        evidence("short-caption-once-two-source-copies-without-downloads")
    }

    @Test fun sharedOpeningAndConclusionSurroundEachPlatformsMiddleContextInTheFeedAndSavedCopy() {
        val opening="The neighborhood garden crew completed its seasonal inspection and published the work plan for everyone who uses the public grounds. The notes explain which gates are open and where shared tools belong."
        val xMiddle="Volunteers repaired twelve gate panels during the morning workday."
        val instagramMiddle="The greenhouse received eight seed trays for the family workshop."
        val conclusion="Everyone can inspect the workshop materials, read the maintenance schedule, and suggest practical improvements before the next gathering. The project welcomes families who want safer paths and friendly public spaces."
        val (originalX,originalInstagram)=pair()
        val x=originalX.copy(body="$opening\n\n$xMiddle\n\n$conclusion",title="")
        val instagram=originalInstagram.copy(body="$opening\n\n$instagramMiddle\n\n$conclusion",title="")
        records[records.indexOf(originalX)]=x;records[records.indexOf(originalInstagram)]=instagram
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        val keys=listOf(x,instagram).map(repo::key)
        val originalPayloads=runBlocking {keys.associateWith {requireNotNull(repo.dao.post(it)).payload}}
        cachePicture(x);cachePicture(instagram,jpeg=true)
        seed("all",listOf(x,instagram));val vm=model();show(vm,all=true);waitRow(x)
        fun assertPassages() {
            ui.onAllNodesWithTag("Fused post text").assertCountEquals(1)
            ui.onAllNodesWithTag("Fusion shared text").assertCountEquals(2)
            listOf(opening,conclusion,xMiddle,instagramMiddle).forEach {ui.onAllNodesWithText(it).assertCountEquals(1)}
            ui.onNodeWithText(xMiddle).assert(hasAnySibling(hasContentDescription("X",substring=true)))
            ui.onNodeWithText(instagramMiddle).assert(hasAnySibling(hasContentDescription("Instagram",substring=true)))
            val first=ui.onNodeWithText(opening).fetchSemanticsNode().positionInRoot.y
            val last=ui.onNodeWithText(conclusion).fetchSemanticsNode().positionInRoot.y
            listOf(xMiddle,instagramMiddle).forEach {passage->
                val middle=ui.onNodeWithText(passage).fetchSemanticsNode().positionInRoot.y
                assertTrue("Source-only wording stays between the shared opening and conclusion",first<middle&&middle<last)
            }
        }
        assertPassages();row(instagram).assertDoesNotExist()
        evidence("multi-span-shared-opening-native")
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        revealCombinedControl(hasText(xMiddle) and fixtureCard)
        evidence("multi-span-middle-context-with-source-labels-native")
        revealCombinedControl(hasText(conclusion) and fixtureCard)
        evidence("multi-span-shared-conclusion-native")
        val save=hasContentDescription("Save post") and fixtureCard
        revealCombinedControl(save);ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        assertCapturedSources(listOf(x,instagram),runBlocking {repo.library.details(repo.key(x))}!!.fusionRoots!!)
        val xComments=hasContentDescription("X original actions") and fixtureCard
        val instagramComments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed().performClick();ui.onNodeWithTag("Platform comments instagram").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab twitter").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.X.id)?.page!=null}
        val threads=vm.discussions.state.value!!.threads
        assertEquals(x.canonicalUrl,threads.getValue(Platform.X.id).post.canonicalUrl)
        assertEquals(instagram.canonicalUrl,threads.getValue(Platform.INSTAGRAM.id).post.canonicalUrl)
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM),vm.discussions.state.value!!.posts.map {it.platform}.toSet())
        ui.onNodeWithContentDescription("Close discussions").performClick()
        ui.runOnIdle {vm.section("saved")}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry ${repo.key(x)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(x)}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Local copy reader").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Local source edition all").assertIsSelected()
        assertPassages()
        evidence("multi-span-saved-copy-retains-both-source-contexts")
        assertEquals(originalPayloads,runBlocking {keys.associateWith {requireNotNull(repo.dao.post(it)).payload}})
    }

    @Test fun lateImageEvidenceCollapsesDuplicatesWithoutReplacingTheVisibleBodyUntilNewest() {
        val (x,instagram)=pair();val xFollow=follow(Platform.X)
        store(x,xFollow);store(instagram,follow(Platform.INSTAGRAM))
        // X is the older of the two editions. Supply unrelated content below
        // it so LazyColumn can actually place it at the requested offset;
        // otherwise its end-of-list clamp can leave Instagram as the anchor.
        val tail=x.copy(id=x.id+"7",canonicalUrl="https://x.com/$handle/status/${x.id}7",author="Independent notice",
            body=(1..24).joinToString("\n"){"Separate library notice $it: the reading room has a quiet study desk."},publishedAt=now-120_000,media=emptyList())
        records+=tail;store(tail,xFollow)
        cachePicture(x)
        assertFalse(repo.images.identities.same(x,instagram))
        val feed="person:$person";seed(feed,listOf(x,instagram,tail),offset=29)
        val vm=model();show(vm,all=false);waitRow(x)
        try {
            ui.waitUntil(10_000){repo.feedMemory.get(feed).anchorKey==repo.key(x)&&repo.feedMemory.get(feed).anchorOffset==29&&
                ui.onAllNodesWithTag("Post body ${repo.key(x)}").fetchSemanticsNodes().isNotEmpty()&&
                ui.onAllNodesWithTag("Jump to newest").fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
        }catch(failure: Throwable) {
            evidence("failure-late-image-initial-anchor")
            val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"fusion-feed")
            File(dir,"failure-late-image-initial-anchor-memory.json").writeText(repo.gson.toJson(mapOf(
                "expectedKey" to repo.key(x),"expectedOffset" to 29,"memory" to repo.feedMemory.get(feed),
                "fixtureKeys" to records.map(repo::key),"rules" to repo.contentFilters.rules.value)))
            File(dir,"failure-late-image-initial-anchor-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString())
            throw failure
        }
        ui.waitForIdle()
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        ui.onNodeWithContentDescription("Instagram original actions").assertDoesNotExist()
        val before=top(x);val offset=repo.feedMemory.get(feed).anchorOffset
        cachePicture(instagram,jpeg=true)
        val lateInstagram=hasContentDescription("Instagram original actions") and hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        ui.waitUntil(10_000){ui.onAllNodes(lateInstagram).fetchSemanticsNodes().isNotEmpty()}
        assertTrue(repo.images.identities.same(x,instagram))
        assertTrue("Late image evidence must not move the current card",abs(top(x)-before)<=2f)
        assertEquals(offset,repo.feedMemory.get(feed).anchorOffset)
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Jump to newest").assertIsDisplayed()
        evidence("late-fusion-keeps-visible-body-and-fixed-anchor")
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Fused post text").fetchSemanticsNodes().isNotEmpty()}
        assertFusion();row(instagram).assertDoesNotExist()
        row(x).assertIsDisplayed()
        assertEquals(repo.key(x),repo.feedMemory.get(feed).anchorKey)
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val xComments=hasContentDescription("X original actions") and fixtureCard
        val instagramComments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed()
        evidence("late-fusion-adopted-native")
    }

    @Test fun newestAdoptsKnownContextOutsideTheAcceptedSourceWindow() {
        val (x,instagram)=pair();store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM))
        cachePicture(x);cachePicture(instagram,jpeg=true)
        assertTrue(repo.images.identities.same(x,instagram))
        // The production known-group pool can retain an edition outside a
        // particular feed's bounded source window. Keep that source window
        // fixed at one post throughout, including the explicit Newest action.
        val sourceWindow=listOf(x)
        val feed="outside-window:$person";seed(feed,sourceWindow,offset=19)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,sourceWindow,accounts,emptySet(),feed,"Fusion fixture","Bounded source window",grouped=true,onAdd={},counterparts=known.value)
            DiscussionSheet(vm)
        }}}
        waitRow(x)
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        val before=top(x)
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram))}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("Known editions must not replace the visible source text before adoption",abs(top(x)-before)<=2f)
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Fused post text").fetchSemanticsNodes().isNotEmpty()}
        assertFusion()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed incoming banner").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertEquals(listOf(x),sourceWindow)
        row(instagram).assertDoesNotExist()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val xComments=hasContentDescription("X original actions") and fixtureCard
        val instagramComments=hasContentDescription("Instagram original actions") and fixtureCard
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed().performClick();ui.onNodeWithTag("Platform comments instagram").performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM),vm.discussions.state.value!!.posts.map {it.platform}.toSet())
        assertEquals(2,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
        evidence("outside-window-adopts-context-and-keeps-comments")
        ui.onNodeWithContentDescription("Close discussions").performClick()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
    }

    @Test fun followingOffersLateDifferingContextWithoutMovingOrReplacingTheVisibleBody() {
        val (x,instagram)=pair();val xFollow=follow(Platform.X);val instagramFollow=follow(Platform.INSTAGRAM)
        store(x,xFollow)
        // Register both sources before the session starts. Only the second
        // source's post arrives late, so this exercises a live Following card.
        runBlocking {repo.dao.putFollow(FollowRow(instagramFollow.id,instagramFollow.target.platform.id,repo.gson.toJson(instagramFollow)))}
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val feed="following:${Platform.X.id}:group:${Repository.hash("")}"
        seed(feed,listOf(x),offset=17)
        val vm=model();ui.runOnUiThread {ui.activity.setContent {AntiSocialApp(vm)}};waitRow(x)
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        val before=top(x);val offset=repo.feedMemory.get(feed).anchorOffset
        store(instagram,instagramFollow)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("A late source's additional wording must wait without moving the Following card",abs(top(x)-before)<=2f)
        assertEquals(offset,repo.feedMemory.get(feed).anchorOffset)
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithText(x.body).assertExists()
        evidence("following-late-context-keeps-visible-body")
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Fused post text").fetchSemanticsNodes().isNotEmpty()}
        assertFusion();row(instagram).assertDoesNotExist()
        row(x).assertIsDisplayed()
        assertEquals(repo.key(x),repo.feedMemory.get(feed).anchorKey)
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        evidence("following-adopts-all-source-context")
    }

    @Test fun anUpdatedKnownEditionOutsideTheSourceWindowWaitsThenReplacesItsOldContext() {
        val (x,instagram)=pair();cachePicture(x);cachePicture(instagram,jpeg=true)
        val sourceWindow=listOf(x);val feed="updated-window:$person"
        seed(feed,sourceWindow,offset=13)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,sourceWindow,accounts,emptySet(),feed,"Fusion fixture","Bounded source window",grouped=true,onAdd={},counterparts=known.value)
        }}}
        waitRow(x);assertFusion()
        val before=top(x);val offset=repo.feedMemory.get(feed).anchorOffset
        val updatedAfter="The greenhouse now has 14 seed trays ready for families."
        val updated=instagram.copy(body="$beforeInstagram\n\n$shared\n\n$updatedAfter")
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,updated),repo.key(updated) to listOf(x,updated))}
        ui.waitForIdle()
        ui.onNodeWithText(afterInstagram).assertExists();ui.onNodeWithText(updatedAfter).assertDoesNotExist()
        assertTrue(abs(top(x)-before)<=2f);assertEquals(offset,repo.feedMemory.get(feed).anchorOffset)
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.waitUntil(10_000){ui.onAllNodesWithText(updatedAfter).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText(updatedAfter).assertExists();ui.onNodeWithText(afterInstagram).assertDoesNotExist()
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        row(x).assertIsDisplayed()
        assertEquals(repo.key(x),repo.feedMemory.get(feed).anchorKey)
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertEquals(listOf(x),sourceWindow)
        evidence("outside-window-update-replaces-only-adopted-context")
    }

    @Test fun savingALateExactSidecarKeepsBothSourceEditionsWithoutChangingTheFrozenBodyOrDownloadingMedia() {
        val (originalX,originalInstagram)=pair()
        val x=originalX.copy(body=shared);val instagram=originalInstagram.copy(body=shared)
        records[records.indexOf(originalX)]=x;records[records.indexOf(originalInstagram)]=instagram
        cachePicture(x);cachePicture(instagram,jpeg=true)
        val sourceWindow=listOf(x);val feed="late-exact:$person";seed(feed,sourceWindow,offset=11)
        val known=mutableStateOf(mapOf(repo.key(x) to listOf(x)))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {
            ChronologicalFeed(vm,sourceWindow,accounts,emptySet(),feed,"Following fixture","Exact source editions",onAdd={},counterparts=known.value)
        }}}
        waitRow(x);val before=top(x);val offset=repo.feedMemory.get(feed).anchorOffset
        ui.runOnIdle {known.value=mapOf(repo.key(x) to listOf(x,instagram),repo.key(instagram) to listOf(x,instagram))}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Instagram original actions").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        assertTrue("Exact sidecar discovery must leave the reading card in place",abs(top(x)-before)<=2f)
        assertEquals(offset,repo.feedMemory.get(feed).anchorOffset)
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val save=hasContentDescription("Save post") and fixtureCard
        revealCombinedControl(save,"Following feed");ui.onNode(save).performClick()
        ui.waitUntil(5_000){runBlocking {repo.library.details(repo.key(x))}?.fusionRoots?.size==2}
        val saved=runBlocking {repo.library.copy(repo.key(x))}!!
        assertCapturedSources(listOf(x,instagram),saved.details!!.fusionRoots!!)
        assertTrue("Saving text/source editions must not select attachments",saved.details!!.selectedAssets.isEmpty())
        assertTrue("No supplementary download should be queued",saved.assets.isEmpty())
        ui.onNodeWithTag("Fused post text").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        ui.onAllNodesWithText(shared).assertCountEquals(1)
        evidence("late-exact-sidecar-saves-both-roots-without-body-change")
    }

    private fun picture(): Bitmap {
        val width=512;val height=320;val colors=IntArray(width*height)
        for(y in 0 until height)for(x in 0 until width) {
            val u=x.toDouble()/width;val v=y.toDouble()/height
            val texture=((x*13+y*23+(x*y)%31)%19)-9
            val color=if(v<.48+sin(u*9)*.09)Color.rgb((95+v*100).toInt(),(145+v*110).toInt(),(225-v*30).toInt())else Color.rgb((70+v*80).toInt(),(110+v*70).toInt(),(35+v*45).toInt())
            colors[y*width+x]=Color.rgb((Color.red(color)+texture).coerceIn(0,255),(Color.green(color)+texture).coerceIn(0,255),(Color.blue(color)+texture).coerceIn(0,255))
        }
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {bitmap->
            bitmap.setPixels(colors,0,width,0,0,width,height)
            val canvas=Canvas(bitmap);val paint=Paint().apply {isAntiAlias=true}
            paint.color=Color.rgb(178,61,38);canvas.drawRect(80f,155f,210f,275f,paint)
            paint.color=Color.rgb(235,211,146);canvas.drawRect(110f,180f,150f,225f,paint)
            paint.color=Color.rgb(28,71,39)
            for(index in 0 until 4)canvas.drawOval((290+index*43).toFloat(),(95+index*17).toFloat(),(330+index*43).toFloat(),(200+index*17).toFloat(),paint)
            paint.color=Color.rgb(237,189,51);canvas.drawCircle(410f,50f,25f,paint)
        }
    }
}
