/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Public-source-shaped local fixtures: no accounts, upstream requests or fake availability. */
@RunWith(AndroidJUnit4::class)
class CrossPostFeedTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalLoader: suspend (Target,(String)->Unit)->Page
    private lateinit var originalValidator: suspend (ShareRouteCandidate)->ShareRouteCheck
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope
    private val models=mutableListOf<AntiSocialViewModel>()
    private val records=mutableListOf<Post>()
    private val accounts=mutableListOf<Follow>()
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val person="cross-post-$suffix"
    private val handle="antisocialcross${suffix.takeLast(6)}"
    private val now=System.currentTimeMillis()
    private val caption="President Trump is delivering for the great state of Georgia, and the Peach State is feeling the results. 🇺🇸"
    private val calls=CopyOnWriteArrayList<String>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory;originalLoader=repo.directLoader
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].let {it.following();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Preparing cross-post fixtures")}
        }
        runBlocking {withTimeout(10_000){repo.feedRefresh.first {!it.running}}}
        originalValidator=repo.outgoing.validate
        repo.outgoing.validate={ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        repo.prefs.originalFallback=true
        repo.prefs.communityComments=false;repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.theme="dark"
        // An active tab must also be selected. The isolated device can retain
        // a Reddit-only selection from other qualification journeys.
        repo.prefs.selected=(repo.prefs.selected+listOf(Platform.X,Platform.BLUESKY,Platform.INSTAGRAM,Platform.FACEBOOK)).distinct()
        repo.prefs.activePlatform=Platform.X;repo.prefs.readingGroup(Platform.X,"")
        check(repo.prefs.activePlatform==Platform.X)
        Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
        memoryFile=File(ui.activity.cacheDir,"cross-post-memory-$suffix.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
        repo.directLoader={target,_->Page(records.filter {repo.key(it)=="${target.platform.id}:${ContentIdentity.key(target.platform,target.canonicalUrl)}"})}
    }
    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Cross-post fixture complete")}
        }
        runBlocking {repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin()}
        repo.feedMemory=originalMemory;repo.directLoader=originalLoader;repo.outgoing.validate=originalValidator
        runBlocking {
            accounts.forEach {repo.dao.removeFollow(it.id)}
            val keys=records.map(repo::key).distinct()
            keys.forEach {repo.library.cancel(it);repo.dao.removeBookmark(it);repo.dao.deleteDocuments(it,"saved");repo.dao.deleteDocuments(it,"history")};repo.dao.removePosts(keys)
            for(table in listOf("history","seen"))for(key in keys)
                repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun follow(platform: Platform): Follow {
        val target=repo.routes.target(platform,if(platform==Platform.BLUESKY)"$handle.test"else handle)
        return Follow("$person:${platform.id}",target,"Cross-post ${platform.label}",personId=person,personName="Cross-post fixture").also {accounts+=it}
    }
    private fun post(platform: Platform,index: Int=0,body: String=caption,title: String=if(platform==Platform.X)body else "",time: Long=now-60_000): Post {
        val id="97$suffix${1000+index}"
        val url=if(platform==Platform.BLUESKY)"https://bsky.app/profile/$handle.test/post/$id"else "https://x.com/$handle/status/$id"
        return Post(id,platform,url,if(platform==Platform.BLUESKY)"$handle.test"else "@$handle",body,title,publishedAt=time).also {records+=it}
    }
    private fun store(post: Post,follow: Follow)=runBlocking {
        repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
        repo.store(Page(listOf(post)),follow.id,true)
    }
    private fun seed(feed: String,posts: List<Post>,read: Boolean=false,offset: Int=0) {
        val keys=posts.map(repo::key);repo.feedMemory.reconcile(feed,keys)
        repo.feedMemory.anchor(feed,keys.first(),offset)
        if(read)repo.feedMemory.markRead(feed,keys)
    }
    private fun model(): AntiSocialViewModel= AntiSocialViewModel(repo,discussionReader={scope->DiscussionReader(repo,scope) {root,_,order->
        calls+="${root.platform.id}:${order.name}"
        val rootUrl=root.canonicalUrl.trimEnd('/')
        val replyUrl=rootUrl.substringBeforeLast('/')+"/"+rootUrl.substringAfterLast('/')+"9"
        val replyId=ContentIdentity.key(root.platform,replyUrl)
        assertNotEquals("Each fixture reply must have a distinct supported social identity",ContentIdentity.key(root.platform,root.canonicalUrl),replyId)
        val reply=root.copy(id=replyId,canonicalUrl=replyUrl,title="",body="${root.platform.label} fixture reply",publishedAt=now+if(root.platform==Platform.BLUESKY)2_000 else 1_000,parentId=root.id)
        Page(listOf(root,reply))
    }}).also {it.following();models+=it}
    private fun show(vm: AntiSocialViewModel,all: Boolean=false) {
        ui.runOnUiThread {if(all)vm.combined()else vm.subscription(person);ui.activity.setContent {AntiSocialApp(vm)}}
    }
    private fun row(post: Post)=ui.onNodeWithTag("Feed row ${repo.key(post)}")
    private fun waitRow(post: Post) {
        try {
            ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
            row(post).assertIsDisplayed()
        }catch(failure:Throwable) {
            val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"cross-post-feed").apply {mkdirs()}
            runCatching {evidence("failure-${post.id}")}
            runCatching {File(directory,"failure-${post.id}.txt").writeText("active=${repo.prefs.activePlatform}; channel=${repo.channels.selected.value}\n"+ui.onRoot(useUnmergedTree=true).printToString())}
            throw failure
        }
    }
    private fun top(post: Post)=row(post).fetchSemanticsNode().boundsInRoot.top
    private fun rowCount(posts: List<Post>,count: Int) {
        val tags=posts.map {"Feed row ${repo.key(it)}"}.toSet()
        ui.onAllNodes(SemanticsMatcher("known feed cards") {it.config.getOrNull(SemanticsProperties.TestTag) in tags}).assertCountEquals(count)
    }
    private fun revealCombinedControl(matcher: SemanticsMatcher) {
        val list=ui.onNodeWithTag("Combined feed")
        list.performScrollToNode(matcher)
        ui.onNode(matcher).performScrollTo()
        repeat(24) {
            val control=ui.onNode(matcher)
            // Expose the selected icon in its horizontal controls row first.
            // LazyColumn's seek can still leave a tall item's footer clipped.
            if(runCatching {control.assertIsDisplayed()}.isSuccess)return
            val node=control.fetchSemanticsNode()
            val viewport=list.fetchSemanticsNode().boundsInRoot
            val targetY=node.positionInRoot.y+node.size.height/2f
            list.performTouchInput {
                if(targetY>viewport.center.y)swipeUp(startY=height*.75f,endY=height*.4f)
                else swipeDown(startY=height*.4f,endY=height*.75f)
            }
            ui.waitForIdle()
        }
        ui.onNode(matcher).assertIsDisplayed()
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"cross-posts").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun cachedRssCaptionIsRenderedOnceInTheRealNativeFeed() {
        val x=post(Platform.X);store(x,follow(Platform.X));seed("person:$person",listOf(x))
        val vm=model();show(vm);waitRow(x)
        ui.onAllNodesWithText(caption).assertCountEquals(1)
        assertEquals(caption,runBlocking {repo.dao.post(repo.key(x))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.title)
        evidence("rss-caption-once-native")
    }
    @Test fun aColdReaderCanReadTheCompleteCacheWhileAnotherSourceIsStillRefreshing() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val xFollow=follow(Platform.X);val blueFollow=follow(Platform.BLUESKY)
        val previous=post(Platform.X,30,body="The previous complete workshop announcement contains the venue, equipment and practical registration instructions.")
        store(previous,xFollow)
        val tail=(31..32).map {index->post(Platform.X,index,body="Earlier independent workshop $index has its own equipment list, room location and access arrangements for visiting readers.",time=now-index*3_600_000L).also {store(it,xFollow)}}
        runBlocking {repo.dao.putFollow(FollowRow(blueFollow.id,blueFollow.target.platform.id,repo.gson.toJson(blueFollow)))}
        seed("person:$person",listOf(previous)+tail,read=true,offset=17)
        val text="The workshop software release adds offline project exports, fixes the spreadsheet importer, and includes a documented migration guide for existing installations. The release archive and verification instructions are available from the project website."
        val newer=post(Platform.X,33,body=text,time=now+1_000)
        val counterpart=post(Platform.BLUESKY,34,body=text,time=now+2_000)
        val loader=repo.followLoader;val policy=repo.gateway.currentPolicy()
        val slowStarted=CompletableDeferred<Unit>();val release=CompletableDeferred<Page>()
        val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.gateway.configure(NetworkPolicy(tor=true))
        fun fetched(values:List<Post>,platform:Platform):Page {
            val source=Source(platform,"native-cold-fixture","https://cold-start-fixture.invalid")
            val stamp=Provenance(source,System.currentTimeMillis(),source.root)
            return Page(values.map {it.copy(provenance=stamp)},provenance=stamp)
        }
        repo.followLoader={source->if(source.id==xFollow.id)fetched(listOf(newer,previous),Platform.X)else {assertEquals(blueFollow.id,source.id);slowStarted.complete(Unit);release.await()}}
        val refresh=jobs.async {repo.refresh(personId=person)}
        try {
            runBlocking {withTimeout(5000){slowStarted.await()}}
            assertTrue(repo.feedRefresh.value.running)
            val active=repo.feedRefresh.value.generation
            val began=System.nanoTime()
            val cached=runBlocking {withTimeoutOrNull(2000){repo.followedSnapshot(personId=person).first {it.posts.isNotEmpty()}}}
            val cacheReadMs=(System.nanoTime()-began)/1_000_000
            assertNotNull("A cold cache read must not wait for a slow network source",cached)
            assertEquals((listOf(previous)+tail).map(repo::key).toSet(),cached!!.posts.map(repo::key).toSet())
            assertTrue(cached.generation<active)
            assertNull("A partial source batch must not mutate the complete reader window",runBlocking {repo.dao.post(repo.key(newer))})
            val vm=model();show(vm);waitRow(previous)
            val before=top(previous);val offset=repo.feedMemory.get("person:$person").anchorOffset
            assertFalse(refresh.isCompleted)
            evidence("cold-cache-readable-during-gated-refresh")
            release.complete(fetched(listOf(counterpart),Platform.BLUESKY))
            runBlocking {withTimeout(10_000){refresh.await()}}
            val completed=runBlocking {withTimeout(5000){repo.followedSnapshot(personId=person).first {it.generation==active&&it.posts.size==5}}}
            assertEquals(4,CrossPosts.group(completed.posts,accounts,completed.memberships).size)
            ui.waitUntil(10_000){repo.key(newer) in repo.feedMemory.get("person:$person").acceptedKeys&&repo.key(counterpart) in repo.feedMemory.get("person:$person").acceptedKeys}
            assertTrue(abs(top(previous)-before)<=2f)
            assertEquals(offset,repo.feedMemory.get("person:$person").anchorOffset)
            assertFalse(repo.feedRefresh.value.running)
            val timing=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"cross-posts/cold-reader-timing.json")
            timing.parentFile!!.mkdirs();timing.writeText(repo.gson.toJson(mapOf("cacheReadMs" to cacheReadMs,"sourceHeldWhileFirstCardWasDisplayed" to true,"anchorOffsetPreserved" to offset)))
            evidence("complete-refresh-keeps-cold-reader-anchor")
        }finally {
            release.complete(Page(emptyList()));runBlocking {refresh.cancelAndJoin();jobs.coroutineContext[Job]?.cancelAndJoin()}
            repo.followLoader=loader;repo.gateway.configure(policy)
        }
    }
    @Test fun nasaNotSlowingDownIsOneNativeCardWithThreePreservedOriginals() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val originals=InstrumentationRegistry.getInstrumentation().context.assets.open("nasa-not-slowing-posts.json").reader().use {
            repo.gson.fromJson(it,Array<Post>::class.java).toList()
        }
        records+=originals
        val oldPolicy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            for(post in originals) {
                val target=repo.routes.target(post.platform,"NASA")
                val follow=Follow("$person:${post.platform.id}",target,"NASA",personId=person,personName="NASA").also {accounts+=it}
                store(post,follow)
            }
            seed("all",originals,read=true)
            val vm=model();show(vm,all=true)
            // Chronological cache order, rather than a preferred platform,
            // chooses the stable primary. Observe the actual collected row.
            ui.waitUntil(10_000){originals.any {ui.onAllNodesWithTag("Feed row ${repo.key(it)}").fetchSemanticsNodes().isNotEmpty()}}
            rowCount(originals,1)
            val primary=originals.single {ui.onAllNodesWithTag("Feed row ${repo.key(it)}").fetchSemanticsNodes().isNotEmpty()}
            waitRow(primary)
            assertEquals(3,repo.library.normalizedFusionRoots(primary,originals).size)
            val card=hasAnyAncestor(hasTestTag("Feed row ${repo.key(primary)}"))
            for(platform in listOf("X","Instagram","Facebook"))revealCombinedControl(hasContentDescription("Read $platform comments") and card)
            evidence("nasa-not-slowing-one-card-three-originals")
            for(original in originals)assertEquals(original,runBlocking {repo.dao.post(repo.key(original))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)})
        }finally {repo.gateway.configure(oldPolicy)}
    }

    @Test fun cachedRepostHeadingKeepsItsAttributionWithoutRepeatingTheSourceCaption() {
        val rawTitle="RT by @WhiteHouse: $caption"
        val original=post(Platform.X,title=rawTitle)
        val x=original.copy(author="@USTradeRep")
        records[records.indexOf(original)]=x
        store(x,follow(Platform.X));seed("person:$person",listOf(x))
        val vm=model();show(vm);waitRow(x)
        ui.onAllNodesWithText(caption).assertCountEquals(1)
        ui.onNodeWithText(rawTitle).assertDoesNotExist()
        ui.onNode(hasTestTag("Post attribution") and hasText("Reposted by ",substring=true)).assertExists()
        ui.onNodeWithText("USTradeRep").assertExists()
        ui.onNodeWithText("@USTradeRep").assertDoesNotExist()
        assertEquals(rawTitle,runBlocking {repo.dao.post(repo.key(x))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.title)
        evidence("repost-caption-once-with-attribution-native")
        ui.onNodeWithText(caption).performScrollTo().performClick()
        ui.waitUntil(10_000){vm.screen.value.target!=null&&!vm.screen.value.loading}
        ui.onAllNodesWithText(caption).assertCountEquals(1)
        ui.onNodeWithText(rawTitle).assertDoesNotExist()
        ui.onNode(hasTestTag("Post attribution") and hasText("Reposted by ",substring=true)).assertExists()
        evidence("repost-detail-keeps-attribution-without-caption-echo")
    }
    @Test fun initialAllFeedMergesFormattingVariantsIntoOneCardWithEveryCommentPlatform() {
        val x=post(Platform.X);val blue=post(Platform.BLUESKY,body=caption.replace("great state","great\u00a0state").replace("results.","results!"),time=x.publishedAt!!+1_000)
        store(x,follow(Platform.X));store(blue,follow(Platform.BLUESKY));seed("all",listOf(x,blue))
        val vm=model();show(vm,all=true);waitRow(x)
        rowCount(listOf(x,blue),1);ui.onAllNodesWithText(caption).assertCountEquals(1)
        ui.onNodeWithText("2 platforms · matching post").assertExists()
        ui.onNodeWithContentDescription("Read Bluesky comments").performScrollTo().assertIsDisplayed()
        evidence("all-one-card-two-platforms-native")
    }
    @Test fun shortConcurrentEditionsShowOneCaptionWithEveryAttachmentAndDiscussion() {
        val text="NEXT STOP: KANSAS 🇺🇸"
        val x=post(Platform.X,body=text).copy(media=listOf(Media("https://fixture.invalid/one.jpg")))
        val blue=post(Platform.BLUESKY,body=text,time=x.publishedAt!!+240_000).copy(media=listOf(Media("https://fixture.invalid/two.jpg")))
        val echo=post(Platform.X,index=8,body="🇺🇸🇺🇸🇺🇸",title="",time=x.publishedAt!!+360_000).copy(
            canonicalUrl=x.canonicalUrl.replace("/$handle/","/anotherpublisher/").replace(x.id,"98${suffix}8008"),media=x.media)
        records+=echo
        val oldPolicy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            val source=follow(Platform.X);store(x,source);store(echo,source);store(blue,follow(Platform.BLUESKY));seed("all",listOf(x,blue,echo))
            val vm=model();show(vm,all=true);waitRow(x)
            rowCount(listOf(x,blue,echo),1);ui.onAllNodesWithText(text).assertCountEquals(1)
            assertEquals("Different attachments belong to the collection, not the duplicate bin",2,collectedMedia(listOf(x,blue)).size)
            assertEquals(3,repo.library.normalizedFusionRoots(x,listOf(x,blue,echo)).size)
            evidence("short-concurrent-caption-one-native-card")
            revealCombinedControl(hasTestTag("Read discussions ${repo.key(x)}"))
            ui.onNodeWithTag("Read discussions ${repo.key(x)}").performClick()
            ui.waitUntil(10_000){calls.size==3&&vm.discussions.state.value?.threads?.size==3}
            assertEquals(setOf("twitter:CHRONOLOGICAL","bluesky:CHRONOLOGICAL"),calls.toSet())
            evidence("short-caption-every-original-comment-stream")
        }finally {repo.gateway.configure(oldPolicy)}
    }
    @Test fun selfPermalinkAliasesDoNotSplitTheInstagramEditionFromAnExistingCollection() {
        val text="Restoring our Community Workshop. Putting our Makers & Repairers FIRST. Catch each step of the rebuild on WorkshopFixture.test TODAY! 🛠"
        val x=post(Platform.X,19,body=text,title=text.replace("WorkshopFixture.test","https://WorkshopFixture.test"))
        val facebook=Post("fb$suffix",Platform.FACEBOOK,"https://www.facebook.com/$handle/posts/fb$suffix",handle,text,publishedAt=x.publishedAt!!+1_000).also {records+=it}
        val instagram=Post("OwnLink$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/OwnLink$suffix",handle,text,
            title="Restoring our Community Workshop....",publishedAt=x.publishedAt!!+2_000,
            links=listOf("https://www.instagram.com/p/OwnLink$suffix/"),
            provenance=Provenance(Source(Platform.INSTAGRAM,"rss-bridge","https://fixture.invalid"),now,"https://fixture.invalid/")).also {records+=it}
        val originals=listOf(x,facebook,instagram)
        originals.forEach {store(it,follow(it.platform))};seed("all",originals,read=true,offset=12)
        val vm=model();show(vm,all=true);waitRow(x)
        rowCount(originals,1);row(facebook).assertDoesNotExist();row(instagram).assertDoesNotExist()
        ui.onAllNodesWithText(text).assertCountEquals(1)
        assertEquals(3,repo.library.normalizedFusionRoots(x,originals).size)
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        revealCombinedControl(hasContentDescription("Read Instagram comments") and fixtureCard)
        evidence("self-permalink-three-platform-one-card-native")
        revealCombinedControl(hasTestTag("Read discussions ${repo.key(x)}"))
        ui.onNodeWithTag("Read discussions ${repo.key(x)}").performClick()
        ui.waitUntil(10_000){calls.size==3&&vm.discussions.state.value?.threads?.size==3}
        assertEquals(setOf("twitter:CHRONOLOGICAL","facebook:CHRONOLOGICAL","instagram:CHRONOLOGICAL"),calls.toSet())
        evidence("self-permalink-all-three-discussion-roots-native")
        for(original in originals) {
            val retained=runBlocking {repo.dao.post(repo.key(original))}!!
            assertEquals(original,repo.gson.fromJson(retained.payload,Post::class.java))
        }
    }
    @Test fun allFeedMergesUrlExpandedAndAttributedTruncatedCaptionTitlesWithoutLosingCommentStreams() {
        // Preserve the two cached-source shapes behind the reported border miss:
        // X expands a bare domain in its echoed title, while Instagram prefixes
        // the author and truncates that same caption. The fixture is independent
        // of any real subscription or saved copy already present on this device.
        val firstParagraph="Securing the border around our school garden. Standing with neighborhood volunteers. Repairing the greenhouse. And so much more."
        val domain="BorderFixture$suffix.test"
        val body="$firstParagraph\n\nWorkshop photos at $domain"
        val x=post(Platform.X,12,body=body,title=body.replace(domain,"https://$domain"))
        val instagram=Post("border$suffix",Platform.INSTAGRAM,"https://www.instagram.com/p/Border$suffix/",handle,body,
            title="Post by $handle: $firstParagraph \n\n...",publishedAt=x.publishedAt!!+1_000).also {records+=it}
        store(x,follow(Platform.X));store(instagram,follow(Platform.INSTAGRAM));seed("all",listOf(x,instagram))
        val vm=model();show(vm,all=true);waitRow(x)
        rowCount(listOf(x,instagram),1);row(instagram).assertDoesNotExist()
        ui.onAllNodesWithText(body).assertCountEquals(1)
        ui.onNodeWithText(x.title).assertDoesNotExist();ui.onNodeWithText(instagram.title).assertDoesNotExist()
        ui.onNodeWithText("2 platforms · matching post").assertExists()
        val fixtureCard=hasAnyAncestor(hasTestTag("Feed row ${repo.key(x)}"))
        val xComments=hasContentDescription("Read X comments") and fixtureCard
        val instagramComments=hasContentDescription("Read Instagram comments") and fixtureCard
        // Each icon must be reachable; a narrow horizontal viewport need not
        // display both at once. The helper also scrolls the actual feed footer.
        revealCombinedControl(xComments);ui.onNode(xComments).assertIsDisplayed()
        revealCombinedControl(instagramComments);ui.onNode(instagramComments).assertIsDisplayed()
        for(original in listOf(x,instagram)) {
            val cached=runBlocking {repo.dao.post(repo.key(original))}!!
            val preserved=repo.gson.fromJson(cached.payload,Post::class.java)
            assertEquals(original.title,preserved.title);assertEquals(body,preserved.body)
        }
        evidence("border-caption-one-card-native")
        revealCombinedControl(instagramComments)
        ui.onNode(instagramComments).assertIsDisplayed().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.INSTAGRAM.id)?.page!=null}
        assertEquals(listOf("instagram:CHRONOLOGICAL"),calls.toList())
        ui.onNodeWithTag("Discussion tab twitter").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.X.id)?.page!=null}
        assertEquals(listOf("instagram:CHRONOLOGICAL","twitter:CHRONOLOGICAL"),calls.toList())
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        val loaded=vm.discussions.state.value!!.threads.values
        loaded.forEach {thread->
            val replies=Discussions.comments(thread.post,thread.page!!)
            assertEquals("One distinct reply in ${thread.post.platform.label}",1,replies.size)
            assertNotEquals(ContentIdentity.key(thread.post.platform,thread.post.canonicalUrl),ContentIdentity.key(replies.single().platform,replies.single().canonicalUrl))
        }
        assertEquals(2,loaded.sumOf {Discussions.comments(it.post,it.page!!).size})
        ui.onNodeWithContentDescription("Close discussions").performClick()
    }
    @Test fun lateMatchedCopyExtendsCommentsWithoutMovingOrReopeningAReadMessage() {
        val xFollow=follow(Platform.X);val blueFollow=follow(Platform.BLUESKY)
        val x=post(Platform.X);store(x,xFollow)
        runBlocking {repo.dao.putFollow(FollowRow(blueFollow.id,blueFollow.target.platform.id,repo.gson.toJson(blueFollow)))}
        // A real tail makes this nonzero offset physically reachable; a single
        // short final card legitimately clamps at the end of a LazyColumn.
        val tail=(1..2).map {index->post(Platform.X,index,body="Earlier distinct engineering workshop $index: tools, venue and public registration details from the previous day.",time=now-index*3_600_000L).also {store(it,xFollow)}}
        seed("person:$person",listOf(x)+tail,read=true,offset=31)
        val vm=model();show(vm);waitRow(x);val before=top(x)
        val blue=post(Platform.BLUESKY,body=caption.replace("great state","great\u00a0state"),time=x.publishedAt!!+1_000)
        store(blue,blueFollow)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Read Bluesky comments").fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10_000){repo.key(blue) in repo.feedMemory.get("person:$person").readKeys}
        assertTrue(abs(top(x)-before)<=2f);assertEquals(31,repo.feedMemory.get("person:$person").anchorOffset)
        rowCount(listOf(x,blue),1);row(blue).assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertTrue(repo.feedMemory.get("person:$person").freshKeys.isEmpty())
        repo.feedMemory.reconcile("elsewhere",listOf(repo.key(blue)))
        assertTrue(repo.key(blue) in repo.feedMemory.get("elsewhere").readKeys)
        evidence("late-copy-keeps-read-anchor-native")
        ui.onNodeWithContentDescription("Read Bluesky comments").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.threads?.get(Platform.BLUESKY.id)?.page!=null}
        assertEquals(listOf("bluesky:CHRONOLOGICAL"),calls.toList())
        assertEquals("bluesky",vm.discussions.state.value!!.selected)
        ui.onNodeWithTag("Discussion tab twitter").performScrollTo().performClick()
        ui.waitUntil(5_000){calls.size==2}
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        assertEquals(2,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
        ui.onNodeWithTag("Discussion order SLOP").performClick()
        ui.waitUntil(5_000){calls.containsAll(listOf("twitter:SLOP","bluesky:SLOP"))}
        evidence("merged-all-comment-streams-native")
        ui.onNodeWithContentDescription("Close discussions").performClick()
    }
    @Test fun followingDetailsAndLibraryKeepAlternateCommentStreamsAndSavedAliases() {
        val x=post(Platform.X);val blue=post(Platform.BLUESKY,time=x.publishedAt!!+1_000)
        store(x,follow(Platform.X));store(blue,follow(Platform.BLUESKY))
        val feed="following:${Platform.X.id}:group:${Repository.hash("")}"
        seed(feed,listOf(x));runBlocking {repo.bookmark(blue,true)}
        val vm=model();ui.runOnUiThread {ui.activity.setContent {AntiSocialApp(vm)}};waitRow(x)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Read Bluesky comments").fetchSemanticsNodes().isNotEmpty()}
        row(blue).assertDoesNotExist();ui.onNodeWithContentDescription("Remove saved post").assertExists()
        ui.onNodeWithContentDescription("Remove saved post").performClick()
        ui.waitUntil(5_000){runBlocking {repo.dao.bookmarksByKeys(listOf(repo.key(blue)))}.isEmpty()}
        ui.onNodeWithText(caption).performScrollTo().performClick()
        ui.waitUntil(10_000){vm.screen.value.target!=null&&!vm.screen.value.loading}
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Read Bluesky comments").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("Read Bluesky comments").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value?.selected=="bluesky"}
        assertEquals(listOf("bluesky:CHRONOLOGICAL"),calls.toList())
        evidence("detail-keeps-cross-platform-comments-native")
        ui.onNodeWithTag("Discussion tab all").performScrollTo().performClick()
        ui.waitUntil(5_000){vm.discussions.state.value!!.threads.values.all {it.page!=null}}
        val captured=captureLoadedThreads(vm,listOf(x,blue))
        ui.onNodeWithContentDescription("Close discussions").performClick()
        runBlocking {repo.library.save(blue,emptyList(),captured)}
        ui.runOnIdle {vm.section("saved")}
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Library entry ${repo.key(blue)}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library entry ${repo.key(blue)}").performClick()
        ui.onNodeWithTag("Local copy content").performScrollToNode(hasText("Captured public replies"))
        ui.onNodeWithText("Captured public replies").assertIsDisplayed()
        assertEquals(setOf(Platform.X,Platform.BLUESKY),runBlocking {repo.library.copy(repo.key(blue))}!!.details!!.threads.map {it.root.platform}.toSet())
        evidence("library-keeps-captured-cross-platform-comments-native")
    }
    @Test fun aRealNewPostIsAdmittedAboveTheReaderWhileItsMatchedNeighbourFoldsInPlace() {
        val xFollow=follow(Platform.X);val blueFollow=follow(Platform.BLUESKY)
        val x=post(Platform.X);store(x,xFollow);seed("person:$person",listOf(x),read=true,offset=23)
        runBlocking {repo.dao.putFollow(FollowRow(blueFollow.id,blueFollow.target.platform.id,repo.gson.toJson(blueFollow)))}
        val vm=model();show(vm);waitRow(x);val before=top(x)
        val blue=post(Platform.BLUESKY,time=x.publishedAt!!+1_000)
        val next=post(Platform.X,1,body="An entirely different announcement about tomorrow's public engineering workshop. Registration opens at noon and the venue details will follow.",time=now)
        store(blue,blueFollow);store(next,xFollow)
        ui.waitUntil(10_000){repo.key(next) in repo.feedMemory.get("person:$person").acceptedKeys&&repo.key(blue) in repo.feedMemory.get("person:$person").readKeys}
        assertTrue(abs(top(x)-before)<=2f)
        row(blue).assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertTrue(repo.feedMemory.get("person:$person").stagedKeys.isEmpty())
        assertFalse(repo.key(next) in repo.feedMemory.get("person:$person").readKeys)
        assertTrue(repo.key(blue) in repo.feedMemory.get("person:$person").readKeys)
        val list=ui.onNodeWithTag("Combined feed")
        list.performScrollToNode(hasTestTag("Feed row ${repo.key(next)}"));row(next).assertIsDisplayed()
        list.performScrollToNode(hasTestTag("Feed row ${repo.key(x)}"));row(x).assertIsDisplayed();row(blue).assertDoesNotExist()
        ui.onNodeWithContentDescription("Read X comments").assertExists()
        evidence("only-genuine-arrival-admitted-above-anchor-native")
    }
    @Test fun aReadMergedCardStaysReadWhenTheAcceptedIdentityBudgetIsFull() {
        val x=post(Platform.X);val blue=post(Platform.BLUESKY,time=x.publishedAt!!+1_000)
        store(x,follow(Platform.X));store(blue,follow(Platform.BLUESKY))
        val feed="person:$person";val keys=(1 until FeedReading.MAX_ACCEPTED).map {"ghost:$suffix:$it"}+repo.key(x)
        repo.feedMemory.reconcile(feed,keys);repo.feedMemory.anchor(feed,repo.key(x),11);repo.feedMemory.markRead(feed,listOf(repo.key(x)))
        val vm=model();show(vm);waitRow(x)
        ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Read Bluesky comments").fetchSemanticsNodes().isNotEmpty()}
        rowCount(listOf(x,blue),1)
        ui.onNodeWithTag("Feed row status ${repo.key(x)}").assertDoesNotExist()
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertEquals(FeedReading.MAX_ACCEPTED,repo.feedMemory.get(feed).acceptedKeys.size)
        repo.feedMemory.reconcile("blue-context",listOf(repo.key(blue)))
        repo.feedMemory.mergeAliases("blue-context",listOf(listOf(repo.key(x),repo.key(blue))))
        assertTrue(repo.key(blue) in repo.feedMemory.get("blue-context").readKeys)
        evidence("bounded-aliases-stay-logically-read-native")
    }
    @Test fun threeSameAccountOriginalsUseOneChronologicalCardAndCopyItsLargestSuppliedThread() {
        fun counted(post: Post,count: Long?): Post {records.remove(post);return post.copy(replyCount=count).also {records+=it}}
        val first=counted(post(Platform.X,index=31,time=now-90_000),4)
        val second=counted(post(Platform.X,index=32,time=now-60_000),91)
        val third=counted(post(Platform.X,index=33,time=now-30_000),null)
        val source=follow(Platform.X);listOf(first,second,third).forEach {store(it,source)}
        seed("all",listOf(third,second,first))
        val vm=model();show(vm,all=true);waitRow(first)
        rowCount(listOf(first,second,third),1);ui.onAllNodesWithText(caption).assertCountEquals(1)
        ui.onNodeWithText("3 originals · X").assertExists()
        ui.onAllNodesWithContentDescription("Read X comments").assertCountEquals(1)
        revealCombinedControl(hasTestTag("Collected originals ${repo.key(first)}"))
        ui.onNodeWithTag("Collected originals ${repo.key(first)}").performClick()
        ui.onNodeWithTag("Collected originals").assertExists()
        for(original in listOf(first,second,third)) {
            ui.onNodeWithTag("Collected original list").performScrollToNode(hasTestTag("Original URL ${repo.key(original)}"))
            ui.onNodeWithTag("Original URL ${repo.key(original)}").assertTextEquals(original.canonicalUrl)
        }
        evidence("three-repeat-originals-retained-native")
        ui.onNodeWithContentDescription("Close collected originals").performClick()
        revealCombinedControl(hasTestTag("Post options ${repo.key(first)}"))
        ui.onNodeWithTag("Post options ${repo.key(first)}").performClick()
        ui.onNodeWithTag("Copy post link ${repo.key(first)}").performClick()
        var copied: String?=null
        ui.waitUntil(10_000) {
            ui.runOnIdle {copied=ui.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()}
            copied==second.canonicalUrl
        }
        assertEquals(second.canonicalUrl,copied)
        assertTrue(calls.isEmpty())
    }

    @Test fun theCachedWhiteHouseQuoteAndRepostedFullCaptionShareOneCardWithoutLosingEitherOriginal() {
        val oldPolicy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            val full=".@VP: \"We didn't just talk about closing the border — we actually did it. We didn't just talk about getting opioid overdose deaths down — we actually did it. We didn't just talk about bringing trillions of dollars of new investment into the country — we actually did it. I do think you have to raise the flag and celebrate your accomplishments a little bit, but I do fundamentally think you have to make a promise to the American voter — a believable promise — that you can continue to work on the things that they care about today.\""
            val short="“We didn't just talk about closing the border — we actually did it.” - @VP 🎯"
            val provenance=Provenance(Source(Platform.X,"nitter","https://nitter.netbub.com"),now,"https://nitter.netbub.com/WhiteHouse/rss")
            val media=listOf(Media("https://pbs.twimg.com/amplify_video_thumb/2111249215355580416/img/SL7jClSKrhKkTC__.jpg"))
            val first=Post("98${suffix}7001",Platform.X,"https://x.com/RapidResponse47/status/98${suffix}7001","@RapidResponse47",full+" Video",title="RT by @WhiteHouse: $full",publishedAt=now-120_000,media=media,provenance=provenance)
            val second=Post("98${suffix}7002",Platform.X,"https://x.com/WhiteHouse/status/98${suffix}7002","@WhiteHouse",short,publishedAt=now-60_000,
                media=listOf(Media("https://video.twimg.com/amplify_video/2111249215355580416/vid/clip.mp4","video",preview=media.single().url)),
                provenance=Provenance(Source(Platform.X,"fxembed-api","https://api.fxtwitter.com"),now,"https://api.fxtwitter.com/2/profile/WhiteHouse/statuses"))
            records+=listOf(first,second)
            val source=Follow("$person:curator",repo.routes.target(Platform.X,"WhiteHouse"),"White House",personId=person).also {accounts+=it}
            store(first,source);store(second,source);seed("all",listOf(second,first))
            val vm=model();show(vm,all=true);waitRow(first)
            rowCount(listOf(first,second),1);ui.onAllNodesWithText(full.removePrefix(".").replace("@VP","VP")).assertCountEquals(1)
            assertEquals(listOf(second.media.single()),collectedMedia(listOf(first,second)))
            ui.onNode(hasTestTag("Post attribution") and hasText("Reposted by ",substring=true)).assertExists()
            ui.onNodeWithText(short).assertDoesNotExist()
            revealCombinedControl(hasTestTag("Collected originals ${repo.key(first)}"))
            evidence("actual-clip-reuse-caption-once-native")
            ui.onNodeWithTag("Collected originals ${repo.key(first)}").performClick()
            ui.onNodeWithTag("Collected original list").performScrollToNode(hasTestTag("Original caption ${repo.key(second)}"))
            ui.onNodeWithTag("Original caption ${repo.key(second)}").assertTextEquals(short.replace("@VP","VP"))
            for(original in listOf(first,second))assertEquals(original.body,runBlocking {repo.dao.post(repo.key(original))}!!.let {repo.gson.fromJson(it.payload,Post::class.java)}.body)
            evidence("actual-short-quote-original-retained-native")
            ui.onNodeWithContentDescription("Close collected originals").performClick()
            assertTrue(calls.isEmpty())
        }finally {repo.gateway.configure(oldPolicy)}
    }

    @Test fun lateImageEvidenceDeduplicatesAFrozenThreePlatformCardWithoutRefreshingItsBody() {
        val old=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val a=assets.open("whitehouse-image-copy-0.jpg").use(android.graphics.BitmapFactory::decodeStream)
        val b=assets.open("whitehouse-image-copy-1.jpg").use(android.graphics.BitmapFactory::decodeStream)
        val local=mutableListOf<Pair<String,File>>()
        repo.images.identities.clear()
        try {
            val urls=(0..2).map {"https://gallery-$suffix.invalid/$it.jpg"}
            val first=post(Platform.X,index=71).copy(media=listOf(Media(urls[0])))
            val second=post(Platform.BLUESKY,index=72).copy(media=listOf(Media(urls[1])))
            val third=Post("$suffix-73",Platform.FACEBOOK,"https://www.facebook.com/$handle/posts/987${suffix}73",handle,caption,publishedAt=now-60_000,media=listOf(Media(urls[2])))
            records+=third
            val originals=listOf(first,second,third)
            originals.forEach {store(it,follow(it.platform))}
            val primary=orderedCollectedOriginals(originals).first()
            seed("all",orderedCollectedOriginals(originals));val vm=model();show(vm,all=true);waitRow(primary)
            fun photos()=ui.onAllNodes(SemanticsMatcher("photos on the frozen card") {it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("Post image ${repo.key(primary)} ")==true})
            photos().assertCountEquals(3)
            val last=collectedMedia(orderedCollectedOriginals(originals)).last()
            ui.onNodeWithTag("Post image ${repo.key(primary)} ${last.url}").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
            try {ui.waitUntil(5000){ui.onAllNodesWithContentDescription("Close image").fetchSemanticsNodes().isNotEmpty()}}
            catch(t:Throwable) {evidence("late-gallery-viewer-failure");File(ui.activity.filesDir,"gallery-viewer-failure.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString());throw t}
            val selectedPage=(1..3).map {"$it / 3"}.single {ui.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(selectedPage).assertIsDisplayed()
            val before=originals.map {it.body to it.canonicalUrl}
            runBlocking {urls.forEachIndexed {index,url->
                val file=File(ui.activity.cacheDir,"merged-image-$suffix-$index.jpg")
                assets.open("whitehouse-image-copy-${if(index==1)1 else 0}.jpg").use {input->file.outputStream().use {input.copyTo(it)}}
                val key=Repository.hash(url);local+=key to file
                repo.dao.putMedia(MediaRow(key,file.path,file.length(),now))
            }}
            runBlocking {urls.forEach {repo.images.load(it,primary.contentSourceUrl,1600)}}
            assertTrue("Actual retained X and Instagram encodings are the same image",repo.images.identities.same(first,second))
            assertEquals(1,collectedMedia(originals.map(repo.images.identities::captured)).size)
            ui.waitForIdle()
            ui.onNodeWithText(selectedPage).assertIsDisplayed()
            ui.onNodeWithContentDescription("Close image").performClick()
            ui.waitUntil(10_000){photos().fetchSemanticsNodes().size==1}
            rowCount(originals,1);photos().assertCountEquals(1)
            ui.onAllNodesWithContentDescription("Retry image").fetchSemanticsNodes().takeIf {it.size==1}?.let {ui.onNodeWithContentDescription("Retry image").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}}
            ui.waitUntil(10_000){ui.onAllNodesWithContentDescription("Retry image").fetchSemanticsNodes().isEmpty()}
            photos().onFirst().performScrollTo()
            ui.waitUntil(10_000){photos().fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.ContentDescription)?.contains("")==true}
            assertEquals(before,originals.map {it.body to it.canonicalUrl})
            for(original in originals)assertNotNull(runBlocking {repo.dao.post(repo.key(original))})
            evidence("three-platform-image-once-native")
            runBlocking {repo.library.save(primary,emptyList(),fusionRoots=originals);repo.library.cancel(repo.key(primary))}
            repo.images.identities.clear()
            val saved=runBlocking {repo.library.copy(repo.key(primary))}!!
            val offline=repo.library.normalizedFusionRoots(saved.post,saved.details!!.fusionRoots!!)
            assertEquals(3,offline.size)
            assertEquals("Saving raw frozen editions retains image evidence for offline deduplication",1,collectedMedia(offline).size)
            assertEquals(originals.map {it.canonicalUrl}.toSet(),offline.map {it.canonicalUrl}.toSet())
            assertTrue("Source records are never rewritten",originals.all {it.media.single().visualFingerprint==null})
        }finally {
            a.recycle();b.recycle();repo.images.identities.clear();repo.gateway.configure(old)
            runBlocking {local.forEach {(key,file)->repo.dao.removeMedia(listOf(key));file.delete()}}
        }
    }

    @Test fun actualBessentVariantsCollectCaptionsPicturesCommentsAndAnOfflineCopy() {
        val oldPolicy=repo.gateway.currentPolicy();repo.gateway.configure(NetworkPolicy(tor=true))
        val fixtureMedia=mutableListOf<Pair<String,File>>()
        val previousMedia=mutableMapOf<String,MediaRow?>()
        try {
            val originals=InstrumentationRegistry.getInstrumentation().context.assets.open("bessent-collection.json").reader().use {repo.gson.fromJson(it,Array<Post>::class.java).toList()}.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=null)})}
            originals.flatMap {it.media}.forEachIndexed {index,media->
                val key=Repository.hash(media.url);previousMedia[key]=runBlocking {repo.dao.media(key)}
                val file=File(ui.activity.cacheDir,"bessent-$suffix-$index.jpg")
                InstrumentationRegistry.getInstrumentation().context.assets.open("bessent-$index.jpg").use {input->file.outputStream().use {input.copyTo(it)}}
                fixtureMedia+=key to file
                runBlocking {repo.dao.putMedia(MediaRow(key,file.path,file.length(),now,lastViewedAt=now))}
            }
            runBlocking {repo.images.restoreCachedIdentities(originals)}
            records+=originals
            val source=Follow("$person:bessent",repo.routes.target(Platform.X,"WhiteHouse"),"The White House",personId=person).also {accounts+=it}
            originals.forEach {store(it,source)}
            val ordered=orderedCollectedOriginals(originals);val primary=ordered.first()
            seed("all",ordered);val vm=model();show(vm,all=true);waitRow(primary)
            rowCount(originals,1)
            for(phrase in listOf("Core inflation is down.","No one has been tougher on Russian sanctions than President Trump."))ui.onAllNodesWithText(phrase,substring=true).assertCountEquals(1)
            assertEquals("The same interview frame should appear once; the different second scene remains",2,collectedMedia(originals.map(repo.images.identities::captured)).size)
            assertEquals("A frozen collection uses fresh observations for its related-frame guard too",2,collectedMedia(originals,repo.images.identities).size)
            evidence("bessent-collected-coverage-native")
            revealCombinedControl(hasTestTag("Read discussions ${repo.key(primary)}"))
            ui.onNodeWithTag("Read discussions ${repo.key(primary)}").performClick()
            ui.waitUntil(10_000){calls.size==2&&vm.discussions.state.value?.threads?.values?.let {threads->threads.size==2&&threads.all {it.page!=null}}==true}
            ui.onNodeWithTag("Discussion tab all").assertIsSelected()
            assertEquals(2,vm.discussions.state.value!!.threads.values.sumOf {Discussions.comments(it.post,it.page!!).size})
            for(original in ordered) {
                val replyKey=original.platform.id+":"+ContentIdentity.key(original.platform,original.canonicalUrl+"9")
                ui.onNodeWithTag("Discussion comments").performScrollToNode(hasTestTag("Copy reply link $replyKey"))
                ui.onNodeWithTag("Copy reply link $replyKey").assertIsDisplayed()
            }
            evidence("bessent-both-original-discussions-native")
            val capturedThreads=captureLoadedThreads(vm,ordered)
            vm.discussions.close();ui.waitForIdle()
            val captured=ordered.map(repo.images.identities::captured)
            runBlocking {repo.library.save(primary,emptyList(),capturedThreads,fusionRoots=captured);repo.library.cancel(repo.key(primary))}
            repo.images.identities.clear()
            val saved=runBlocking {repo.library.copy(repo.key(primary))}!!
            val restored=repo.library.normalizedFusionRoots(saved.post,saved.details!!.fusionRoots!!)
            assertEquals(2,restored.size);assertNotNull(collectedPostFusion(restored))
            assertEquals(2,saved.details!!.threads.size)
            for(original in originals)assertEquals(original.body,restored.single {repo.key(it)==repo.key(original)}.body)
            // Local fingerprint observations are captured metadata, not altered source captions.
            assertTrue(restored.all {VisualFrames.valid(it.media.first().visualFingerprint)})
            val exported=runBlocking {repo.portability.exportState(false)}
            assertTrue(runBlocking {repo.portability.preview(exported)}.savedPosts>=1)
        }finally {
            repo.gateway.configure(oldPolicy)
            runBlocking {fixtureMedia.forEach {(key,file)->val previous=previousMedia[key];if(previous==null)repo.dao.removeMedia(listOf(key))else repo.dao.putMedia(previous);file.delete()}}
        }
    }

    @Test fun aLateSameSourceRepeatKeepsItsReadFollowingCardAndPositionWithoutANewPostBanner() {
        val source=follow(Platform.X);val first=post(Platform.X,index=41,time=now-90_000)
        val feed="following:${Platform.X.id}:group:${Repository.hash("")}"
        store(first,source);seed(feed,listOf(first),read=true,offset=17)
        val vm=model();ui.runOnUiThread {vm.following();ui.activity.setContent {AntiSocialApp(vm)}}
        waitRow(first);val before=top(first)
        val second=post(Platform.X,index=42,time=now-30_000);store(second,source)
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Collected originals ${repo.key(first)}").fetchSemanticsNodes().isNotEmpty()}
        rowCount(listOf(first,second),1);row(second).assertDoesNotExist()
        assertTrue(abs(top(first)-before)<=2f)
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertTrue(repo.key(second) in repo.feedMemory.get(feed).readKeys)
        ui.onNodeWithTag("Feed row status ${repo.key(first)}").assertDoesNotExist()
        evidence("same-source-repeat-keeps-reading-place-native")
    }

    @Test fun savingAFrozenTwoOriginalCardAlsoKeepsItsLateExactThirdOriginal() {
        val first=post(Platform.X,index=51,time=now-90_000)
        val blue=post(Platform.BLUESKY,index=52,time=now-60_000)
        val third=post(Platform.X,index=53,time=now-30_000)
        val source=follow(Platform.X);store(first,source);store(third,source);store(blue,follow(Platform.BLUESKY))
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {androidx.compose.material3.MaterialTheme {PostCard(first,vm,false,listOf(first,blue,third),bodyVariants=listOf(first,blue)) {}}}}
        ui.onNodeWithContentDescription("Save post").performClick()
        ui.waitUntil(10_000){runBlocking {repo.dao.savedDetail(repo.key(first))}!=null}
        val detail=runBlocking {repo.library.details(repo.key(first))}!!
        assertEquals(setOf(repo.key(first),repo.key(blue),repo.key(third)),detail.fusionRoots.orEmpty().map(repo::key).toSet())
        assertTrue(calls.isEmpty())
    }

    @Test fun matchingAnOldMessageDoesNotRepublishItsUnrelatedEvictedReadHistory() {
        val store=repo.feedMemory
        store.reconcile("old",listOf("old-a","old-b","old-c"));store.markRead("old",listOf("old-a","old-b","old-c"))
        repeat(4){batch->
            val keys=(0 until 1000).map {"recent-$batch-$it"}
            store.reconcile("recent-$batch",keys);store.markRead("recent-$batch",keys)
        }
        store.mergeAliases("old",listOf(listOf("old-a","matching-copy")))
        store.reconcile("probe",listOf("old-b","matching-copy","recent-3-999"))
        val read=store.get("probe").readKeys
        assertFalse("Unrelated evicted history must not become globally read again","old-b" in read)
        assertTrue("matching-copy" in read);assertTrue("recent-3-999" in read)
    }
}
