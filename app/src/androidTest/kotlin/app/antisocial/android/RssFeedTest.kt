/* AntiSocial, GPL-3.0-or-later. Native RSS/Atom integration with local-only feed fixtures. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** All feed bodies are supplied locally; an accidental request cannot leave through Tor. */
@RunWith(AndroidJUnit4::class)
class RssFeedTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val feed="https://blog.example/antisocial-rss-$suffix/feed.xml"
    private val sourceName="Maker blog $suffix"
    private val now=System.currentTimeMillis()
    private val records=mutableListOf<Post>()
    private val follows=mutableListOf<Follow>()
    private val models=mutableListOf<AntiSocialViewModel>()
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalFollowLoader: suspend (Follow)->Page
    private lateinit var originalDirectLoader: suspend (Target,(String)->Unit)->Page
    private var originalNetworkObserver: ((NetworkEvent)->Unit)?=null
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope
    private val networkEvents=CopyOnWriteArrayList<NetworkEvent>()
    private val directReads=CopyOnWriteArrayList<Target>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory
        originalFollowLoader=repo.followLoader;originalDirectLoader=repo.directLoader
        originalNetworkObserver=repo.gateway.onNetworkEvent
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].following()
            ui.activity.setContent {Text("Preparing RSS fixtures")}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.intervalMinutes=0;repo.prefs.wifiOnly=false;repo.prefs.archiveChecks=false
            repo.prefs.archiveRecovery=false;repo.prefs.communityComments=false;repo.prefs.theme="dark"
            // This fixture tests cached RSS/card navigation, not the independent
            // deferred name lookup for the accompanying social account.
            repo.prefs.mentionLookups=false;repo.mentionNames.changed()
            repo.prefs.selected=listOf(Platform.X);repo.prefs.activePlatform=Platform.X
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        }
        memoryFile=File(ui.activity.cacheDir,"rss-feed-memory-$suffix.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
        repo.followLoader={error("This RSS test must explicitly supply a feed response")}
        repo.directLoader={target,_->directReads+=target;error("RSS summaries must not use the direct social reader")}
        repo.gateway.onNetworkEvent={networkEvents+=it}
    }
    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("RSS fixtures complete")}
        }
        runBlocking {repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin()}
        repo.feedMemory=originalMemory;repo.followLoader=originalFollowLoader;repo.directLoader=originalDirectLoader
        repo.gateway.onNetworkEvent=originalNetworkObserver
        runBlocking {repo.db.withTransaction {
            follows.map {it.id}.distinct().forEach {repo.dao.removeFollow(it)}
            val keys=records.map(repo::key).distinct()
            keys.forEach {key->
                repo.dao.removeBookmark(key);repo.dao.deleteDocuments(key,"saved");repo.dao.deleteDocuments(key,"history")
            }
            repo.dao.removeRetainedHistory(keys)
            keys.chunked(400).forEach {repo.dao.removePosts(it)}
            for(table in listOf("history","seen"))for(key in keys)repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
            val pageKeys=records.mapNotNull(repo::postTarget).map(repo::pageKey).distinct()
            pageKeys.forEach {key->repo.dao.media(key)?.let {File(it.path).delete()}}
            if(pageKeys.isNotEmpty())repo.dao.removeMedia(pageKeys)
        }}
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(JsonParser.parseString(preferences),JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun addSource(url: String=feed,title: String=sourceName,folder: String="Workshop"): Follow=runBlocking {
        repo.addRssSource(url,title,folder).also {follows+=it}
    }
    private fun rssPage(): Page {
        val body="""<rss version="2.0"><channel><title>$sourceName</title><description>Public workshop updates</description><item><guid isPermaLink="false">battery-$suffix</guid><link>https://blog.example/antisocial-rss-$suffix/laptop-battery</link><title>Release: laptop battery guide</title><description><![CDATA[<p>Release of a detailed laptop battery replacement guide.</p>]]></description><pubDate>Fri, 16 Oct 2026 12:00:00 GMT</pubDate><author>Maker</author></item><item><guid isPermaLink="false">older-$suffix</guid><link>https://blog.example/antisocial-rss-$suffix/older</link><title>Release: repair notes</title><description>Release of the earlier hinge repair notes.</description><pubDate>Thu, 15 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        return RssSources.parse(Payload(body,feed,"application/rss+xml",200),RssSources.target(feed),now).also {records+=it.posts}
    }
    private fun social(): Pair<Follow,Post> {
        val handle="rssmaker${suffix.takeLast(6)}"
        val target=repo.routes.target(Platform.X,handle)
        val account=Follow("rss-social:$suffix",target,"Workshop social $suffix",personId="rss-social-person:$suffix").also {follows+=it}
        val address="https://x.com/$handle/status/97${suffix}42"
        val provenance=Provenance(Source(Platform.X,"original",Platform.X.origin,role=SourceRole.ORIGINAL),now,address)
        val post=Post("rss-x-$suffix",Platform.X,address,handle,"Release of the pocket installer package.",publishedAt=Extraction.timestamp("2026-10-16T11:00:00Z"),provenance=provenance).also {records+=it}
        runBlocking {repo.dao.putFollow(FollowRow(account.id,account.target.platform.id,repo.gson.toJson(account)))}
        return account to post
    }
    private fun store(page: Page,follow: Follow)=runBlocking {repo.store(page,follow.id,true)}
    private fun snapshot(channel: PersonalChannel)=runBlocking {withTimeout(10_000){repo.followedSnapshot(channel=channel).first {it.posts.isNotEmpty()}}}
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"rss-feeds").apply {mkdirs()}
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun addingRssIsLocalCanonicalAndKeepsFolderWithoutDuplicateSubscriptions() {
        val first=addSource()
        val again=addSource(feed.replace("https://blog.example/","https://BLOG.EXAMPLE:443/"))
        assertEquals(first.id,again.id);assertEquals(RssSources.followId(feed),first.id)
        assertEquals(Platform.RSS,first.target.platform);assertEquals(TargetKind.FEED,first.target.kind)
        assertEquals(feed,first.feedUrl);assertEquals("Workshop",first.group);assertEquals(sourceName,first.name)
        val matching=runBlocking {repo.dao.allFollows().filter {it.id==first.id}}
        assertEquals(1,matching.size)
        assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
        val before=runBlocking {repo.dao.allFollows().size}
        val invalid=runCatching {runBlocking {repo.addRssSource("http://blog.example/insecure","Unsafe","")}}
        assertTrue(invalid.isFailure);assertEquals(before,runBlocking {repo.dao.allFollows().size})
    }
    @Test fun rssAndSocialSubscriptionsRefreshIntoOneChronologicalRuleBasedFeed() {
        val rss=addSource();val page=rssPage();val (x,social)=social()
        val channel=PersonalChannel("rss-mixed:$suffix","Workshop releases",subscriptionIds=setOf(Subscriptions.id(rss),Subscriptions.id(x)),keywords=listOf("release"))
        val fetched=CopyOnWriteArrayList<String>()
        repo.followLoader={follow->fetched+=follow.id;if(follow.id==rss.id)page else if(follow.id==x.id)Page(listOf(social))else error("An unrelated source was refreshed")}
        runBlocking {repo.refresh(channel=channel)}
        assertEquals(setOf(rss.id,x.id),fetched.toSet());assertEquals(2,fetched.size)
        val result=snapshot(channel)
        assertEquals(listOf(repo.key(page.posts[0]),repo.key(social),repo.key(page.posts[1])),result.posts.map(repo::key))
        assertEquals(setOf(rss.id),result.memberships[repo.key(page.posts[0])]);assertEquals(setOf(x.id),result.memberships[repo.key(social)])
        val selection=selectChannelFeed(channel,result.posts,follows,result.memberships,emptyMap())
        assertEquals(result.posts,selection.posts)
        assertTrue(selection.explanations.getValue(repo.key(page.posts[0])).reasons.contains("From $sourceName on RSS / Atom"))
        assertTrue(RefreshLedger.caughtUp(listOf(rss.id,x.id),repo.feedMemory.refresh.value))
        assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
    }
    @Test fun undatedRssEntriesRemainAfterDatedEntriesWithoutInventingAPublicationTime() {
        val source=addSource();val page=rssPage();val dated=page.posts.first()
        val undated=dated.copy(id="undated-$suffix",canonicalUrl="https://blog.example/antisocial-rss-$suffix/undated",title="Undated workshop note",body="Available feed text without a publication date",publishedAt=null).also {records+=it}
        repo.followLoader={page.copy(posts=listOf(undated,dated))}
        runBlocking {repo.refresh(personId=Subscriptions.id(source))}
        val retained=runBlocking {withTimeout(10_000){repo.posts(Platform.RSS,personId=Subscriptions.id(source)).first {it.size==2}}}
        assertEquals(listOf(repo.key(dated),repo.key(undated)),retained.map(repo::key))
        assertNull(retained.last().publishedAt)
        assertTrue(RefreshLedger.caughtUp(listOf(source.id),repo.feedMemory.refresh.value))
        assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
    }
    @Test fun openingAnRssSummaryUsesItsExactCachedTextWithoutArticleOrSocialRequests() {
        val source=addSource();val page=rssPage();store(page,source)
        val post=page.posts.first();val target=requireNotNull(repo.postTarget(post))
        assertEquals(RssSources.itemTarget(post),target)
        val opened=runBlocking {repo.open(target)}
        assertEquals(1,opened.posts.size);assertEquals(post.body,opened.posts.single().body)
        assertEquals(post.title,opened.posts.single().title);assertEquals(post.canonicalUrl,opened.posts.single().canonicalUrl)
        assertTrue(opened.notice.orEmpty().contains("feed",true));assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
        assertNotNull(runBlocking {repo.cachedPage(target)})
        assertTrue(runBlocking {repo.dao.allHistory().any {it.key==repo.key(post)}})
    }
    @Test fun explicitlySavedRssSummaryRemainsReadableAfterTheOrdinaryPostCacheIsGone() {
        val source=addSource();val page=rssPage();val post=page.posts.first();store(page,source)
        runBlocking {repo.bookmark(post,true);repo.dao.removePosts(listOf(repo.key(post)))}
        assertNull(runBlocking {repo.dao.post(repo.key(post))});assertEquals(1,runBlocking {repo.dao.bookmarksByKeys(listOf(repo.key(post))).size})
        val opened=runBlocking {repo.open(requireNotNull(repo.postTarget(post)))}
        assertEquals(post.body,opened.posts.single().body);assertEquals(post.author,opened.posts.single().author)
        assertEquals(post.canonicalUrl,opened.posts.single().canonicalUrl);assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
        assertEquals(1,runBlocking {repo.dao.bookmarksByKeys(listOf(repo.key(post))).size})
    }
    @Test fun missingRssSummaryStaysUnavailableInsteadOfScrapingOrRecoveringAnArticle() {
        val post=Post("missing-$suffix",Platform.RSS,"https://blog.example/antisocial-rss-$suffix/never-fetched","Maker","No local copy").also {records+=it}
        val result=runCatching {runBlocking {repo.open(requireNotNull(repo.postTarget(post)))}}
        assertTrue(result.isFailure);assertTrue(result.exceptionOrNull() is SourceFailure)
        assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
        assertNull(runBlocking {repo.dao.post(repo.key(post))})
    }
    @Test fun aDatedBlogAndSocialFusionCanBeSavedAndPreviewedAsPortableLocalSourceEditions() {
        val source=addSource();val original=rssPage().posts.first();val (account,social)=social()
        val shared="At our public repair workshop, neighbors learned how to replace a laptop battery, organize the screws, disconnect the old pack, and safely fit the replacement before closing the cover. Everyone can inspect the detailed repair notes and follow the next community session."
        val blog=original.copy(title="",body=shared,publishedAt=now-2_000,media=emptyList(),links=emptyList())
        val publicPost=social.copy(title="",body=shared,publishedAt=now-1_000,media=emptyList(),links=emptyList())
        store(Page(listOf(blog)),source);store(Page(listOf(publicPost)),account)
        val channel=PersonalChannel("rss-save:$suffix","Repair workshop",subscriptionIds=setOf(Subscriptions.id(source),Subscriptions.id(account)))
        val feed=snapshot(channel)
        val groups=CrossPosts.group(feed.posts,follows,feed.memberships)
        assertEquals(1,groups.size)
        val editions=groups.single().posts
        assertEquals(setOf(repo.key(blog),repo.key(publicPost)),editions.map(repo::key).toSet())
        assertEquals(setOf(source.id),feed.memberships[repo.key(blog)])
        assertEquals(setOf(account.id),feed.memberships[repo.key(publicPost)])
        val primary=editions.first()
        assertEquals(Platform.RSS,primary.platform)
        assertEquals(blog.publishedAt,editions.mapNotNull {it.publishedAt}.minOrNull())
        val owner=runBlocking {repo.library.save(primary,emptyList(),fusionRoots=editions).also {repo.library.cancel(it)}}
        val captured=runBlocking {repo.library.copy(owner)}!!
        assertEquals(editions,captured.details!!.fusionRoots)
        assertNotNull(CrossPostFusion.compose(captured.details!!.fusionRoots!!))
        val bytes=runBlocking {repo.portability.exportState(false)}
        val preview=runBlocking {repo.portability.preview(bytes)}
        assertTrue(preview.isState);assertFalse(preview.hasProgress)
        val library=repo.gson.fromJson(preview.document.statePayload!!.get("library"),PortableLibrary::class.java)
        val exported=library.saved.single {repo.key(it.post)==owner}
        assertEquals(primary,exported.post)
        assertEquals(editions,exported.details!!.fusionRoots)
        assertNotNull(CrossPostFusion.compose(exported.details!!.fusionRoots!!))
        runBlocking {repo.dao.removePosts(editions.map(repo::key))}
        val durable=runBlocking {repo.library.copy(owner)}!!
        assertEquals(editions,durable.details!!.fusionRoots)
        val opened=runBlocking {repo.open(requireNotNull(repo.postTarget(primary)))}
        assertEquals(shared,opened.posts.single().body)
        assertTrue(networkEvents.isEmpty());assertTrue(directReads.isEmpty())
    }
    @Test fun readingRssDoesNotReclassifyItsBlogOrMediaAsForbiddenOriginalSocialSites() {
        val post=rssPage().posts.first().copy(media=listOf(Media("https://media.example/antisocial-rss-$suffix/photo.jpg")))
        // This is the same origin-learning boundary used by store, feed reads and saved copies.
        repo.registerPageOrigins(Page(listOf(post),provenance=post.provenance))
        repo.gateway.configure(NetworkPolicy(tor=false));repo.gateway.originalFallback=false
        val supplied=mutableListOf<String>()
        // Append an in-memory terminal after the gateway's real privacy interceptor.
        // Validation and filters run normally; no DNS, socket or remote server is involved.
        val fixture=repo.gateway.client().newBuilder().addInterceptor {chain->
            supplied+=chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local fixture").body("feed fixture".toResponseBody()).build()
        }.build()
        listOf(feed,post.media.single().url).forEach {url->
            fixture.newCall(Request.Builder().url(url).build()).execute().use {response->assertEquals(200,response.code);assertEquals("feed fixture",response.body!!.string())}
        }
        val refused=runCatching {fixture.newCall(Request.Builder().url("https://x.com/asfixture/status/97${suffix}42").build()).execute().close()}.exceptionOrNull()
        assertNotNull(refused);assertTrue(refused!!.message.orEmpty().contains("Original-site access is disabled"))
        assertEquals(listOf(feed,post.media.single().url),supplied)
        assertTrue(networkEvents.none {it.stage=="dns"||it.stage=="dns ready"||it.stage=="connect"||it.stage=="tls"||it.stage=="response"})
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
        val paused=runCatching {repo.gateway.client()}.exceptionOrNull()
        assertNotNull(paused);assertTrue(paused!!.message.orEmpty().contains("Tor is connecting"))
    }
    @Test fun nativeChannelShowsBlogAndSocialCardsExplainsTheBlogMatchAndOpensItsFeedCopy() {
        val source=addSource();val page=rssPage();val (account,social)=social()
        store(page,source);store(Page(listOf(social)),account)
        val channel=repo.channels.save(PersonalChannel("rss-native:$suffix","Workshop releases",subscriptionIds=setOf(Subscriptions.id(source),Subscriptions.id(account)),keywords=listOf("release")))
        repo.channels.select(channel.id)
        val vm=AntiSocialViewModel(repo).also {it.combined();models+=it}
        ui.runOnUiThread {ui.activity.setContent {AntiSocialApp(vm)}}
        val post=page.posts.first();val tag="Feed row ${repo.key(post)}"
        ui.waitUntil(10_000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(listOf(repo.key(post),repo.key(social),repo.key(page.posts[1])),repo.feedMemory.get("channel:${channel.id}:${PersonalChannels.fingerprint(channel)}:all").acceptedKeys)
        evidence("blog-and-social-chronological-channel")
        val why="Why post ${repo.key(post)}"
        val options="Post options ${repo.key(post)}"
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag(options))
        ui.onNodeWithTag(options).performClick()
        ui.onNodeWithTag(why).performClick()
        ui.onNodeWithText("From $sourceName on RSS / Atom").assertIsDisplayed()
        ui.onNodeWithText("Contains “release”").assertIsDisplayed()
        evidence("blog-channel-reason")
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText(post.body).performClick()
        ui.waitUntil(10_000){vm.screen.value.target?.canonicalUrl==post.canonicalUrl&&!vm.screen.value.loading}
        assertEquals(Platform.RSS,vm.screen.value.target!!.platform)
        ui.onNodeWithText(post.body).assertIsDisplayed()
        evidence("native-cached-blog-summary")
        assertTrue("Unexpected gateway events: $networkEvents",networkEvents.isEmpty());assertTrue("Unexpected social reads: $directReads",directReads.isEmpty())
    }
}
