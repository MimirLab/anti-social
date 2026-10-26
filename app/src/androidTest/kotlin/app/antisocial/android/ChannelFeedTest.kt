/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real native feed + Room fixtures. All content/refresh responses are local. */
@RunWith(AndroidJUnit4::class)
class ChannelFeedTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var originalMemory: FeedMemoryStore
    private lateinit var originalFollowLoader: suspend (Follow)->Page
    private lateinit var originalDirectLoader: suspend (Target,(String)->Unit)->Page
    private lateinit var memoryFile: File
    private lateinit var memoryScope: CoroutineScope
    private val models=mutableListOf<AntiSocialViewModel>()
    private val records=mutableListOf<Post>()
    private val accounts=mutableListOf<Follow>()
    private val suffix=System.nanoTime().toString().takeLast(10)
    private val person="channel-feed-$suffix"
    private val handle="aschannel${suffix.takeLast(6)}"
    private val now=System.currentTimeMillis()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();originalMemory=repo.feedMemory
        originalFollowLoader=repo.followLoader;originalDirectLoader=repo.directLoader
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].let {it.following();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Preparing personal channel feed fixtures")}
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.intervalMinutes=0;repo.prefs.wifiOnly=false;repo.prefs.archiveChecks=false
            repo.prefs.communityComments=false;repo.prefs.theme="dark"
            repo.prefs.selected=listOf(Platform.X,Platform.BLUESKY);repo.prefs.activePlatform=Platform.X
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            // Any unexpected media/probe attempt also remains unable to leave the device.
            repo.gateway.configure(NetworkPolicy(tor=true))
        }
        runBlocking {withTimeout(10_000){repo.feedRefresh.first {!it.running}}}
        memoryFile=File(ui.activity.cacheDir,"personal-channel-feed-$suffix.json")
        memoryScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        repo.feedMemory=FeedMemoryStore(ui.activity,memoryScope,memoryFile)
        runBlocking {repo.feedMemory.awaitReady()}
        repo.followLoader={Page(emptyList())}
        repo.directLoader={target,_->Page(records.filter {repo.key(it)=="${target.platform.id}:${ContentIdentity.key(target.platform,target.canonicalUrl)}"})}
    }

    @After fun restore() {
        ui.runOnIdle {
            models.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Personal channel feed fixtures complete")}
        }
        runBlocking {repo.feedMemory.flush();memoryScope.coroutineContext[Job]?.cancelAndJoin()}
        repo.feedMemory=originalMemory;repo.followLoader=originalFollowLoader;repo.directLoader=originalDirectLoader
        runBlocking {repo.db.withTransaction {
            accounts.forEach {repo.dao.removeFollow(it.id)}
            val keys=records.map(repo::key).distinct()
            keys.chunked(400).forEach {repo.dao.removePosts(it)}
            for(table in listOf("history","seen"))for(key in keys)
                repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
        }}
        listOf(memoryFile,File(memoryFile.path+".bak"),File(memoryFile.path+".new")).forEach {it.delete()}
        ui.runOnIdle {
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        assertEquals(JsonParser.parseString(preferences),JsonParser.parseString(repo.prefs.export().toString()))
    }

    private fun follow(platform: Platform=Platform.X,bundle: String=person,name: String="Channel developer",enabled: Boolean=true): Follow {
        val identity=if(platform==Platform.BLUESKY)"$handle.test"else handle
        val target=repo.routes.target(platform,identity)
        return Follow("$bundle:${platform.id}",target,name,enabled=enabled,personId=bundle,personName=name).also {accounts+=it}
    }
    private fun post(index: Int,body: String="Release $index is available #Software",platform: Platform=Platform.X,media: List<Media> = emptyList()): Post {
        val id="94$suffix${1000+index}"
        val url=if(platform==Platform.BLUESKY)"https://bsky.app/profile/$handle.test/post/$id"else "https://x.com/$handle/status/$id"
        return Post(id,platform,url,handle,body,publishedAt=now-index*60_000L,media=media).also {records+=it}
    }
    private fun store(post: Post,follow: Follow)=runBlocking {
        repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
        repo.store(Page(listOf(post)),follow.id,true)
    }
    private fun rule(name: String="Software releases")=PersonalChannel("$person-rule",name,subscriptionIds=setOf(person),keywords=listOf("release"),hashtags=listOf("software"))
    private fun save(channel: PersonalChannel): PersonalChannel=repo.channels.save(channel).also {repo.channels.select(it.id)}
    private fun feedId(channel: PersonalChannel,scope: String="all")="channel:${channel.id}:${PersonalChannels.fingerprint(channel)}:$scope"
    private fun model()=AntiSocialViewModel(repo).also {it.combined();models+=it}
    private fun show(vm: AntiSocialViewModel) {
        ui.runOnUiThread {ui.activity.setContent {AntiSocialApp(vm)}}
    }
    private fun waitFeed() {
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Combined feed").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun row(post: Post)=ui.onNodeWithTag("Feed row ${repo.key(post)}")
    private fun waitRow(post: Post) {
        ui.waitUntil(10_000){ui.onAllNodesWithTag("Feed row ${repo.key(post)}").fetchSemanticsNodes().isNotEmpty()}
        row(post).assertIsDisplayed()
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(200)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"personal-channel-feed").apply {mkdirs()}
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun revealControl(tag: String) {
        val list=ui.onNodeWithTag("Combined feed")
        list.performScrollToNode(hasTestTag(tag))
        repeat(6) {
            val control=ui.onNodeWithTag(tag)
            control.performScrollTo()
            if(runCatching {control.assertIsDisplayed()}.isSuccess)return
            list.performTouchInput {swipeUp(startY=height*.75f,endY=height*.4f)}
            ui.waitForIdle()
        }
        ui.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun reveal(tag: String) {
        if(tag.startsWith("Why post ")) {
            val options="Post options "+tag.removePrefix("Why post ")
            revealControl(options);ui.onNodeWithTag(options).performClick()
        }else revealControl(tag)
    }

    @Test fun chosenChannelShowsOnlyMatchingSubscriptionsInChronologicalOrderAndExplainsWhy() {
        val selected=follow();val outside=follow(bundle="$person-other",name="Other creator")
        val newest=post(0,"Release of the desktop package #Software")
        val older=post(2,"Release of a different build for the handheld edition #Software")
        val ordinary=post(1,"A garden update #Software")
        val excludedSource=post(3,"Release from another creator #Software")
        store(older,selected);store(ordinary,selected);store(newest,selected);store(excludedSource,outside)
        val channel=save(rule());val vm=model();show(vm);waitRow(newest)
        ui.onNodeWithTag("Choose feed").performClick()
        ui.onNodeWithTag("Channel tab ${channel.id}").assertIsDisplayed()
        ui.onNodeWithTag("Channel all subscriptions").assertExists()
        ui.onNodeWithTag("Channel tab ${channel.id}").performClick()
        assertEquals(listOf(repo.key(newest),repo.key(older)),repo.feedMemory.get(feedId(channel)).acceptedKeys)
        row(ordinary).assertDoesNotExist();row(excludedSource).assertDoesNotExist()
        evidence("chosen-channel-chronological")
        reveal("Why post ${repo.key(newest)}")
        ui.onNodeWithTag("Why post ${repo.key(newest)}").performClick()
        ui.onNodeWithTag("Channel match explanation").assertIsDisplayed()
        ui.onNodeWithText("In Software releases").assertIsDisplayed()
        ui.onNodeWithText("From Channel developer on X").assertIsDisplayed()
        ui.onNodeWithText("Contains “release”").assertIsDisplayed()
        ui.onNodeWithText("Tagged #software").assertIsDisplayed()
        evidence("readable-match-explanation")
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(older)}"))
        row(older).assertIsDisplayed()
    }

    @Test fun excludedKnownFusionCannotReturnThroughThePostCardGlobalCounterpartSidecar() {
        val x=follow();val blue=follow(Platform.BLUESKY)
        val forbidden=post(1,"Release shared across the two platforms #Software")
        val badEdition=post(1,"Release shared across the two platforms #Software #Ad",Platform.BLUESKY)
        val safe=post(0,"Release of an unrelated clean utility #Software")
        val channel=save(rule().copy(excludeHashtags=listOf("ad")))
        val membership=mapOf(repo.key(forbidden) to setOf(x.id),repo.key(badEdition) to setOf(blue.id),repo.key(safe) to setOf(x.id))
        val complete=mapOf(repo.key(forbidden) to listOf(forbidden,badEdition),repo.key(badEdition) to listOf(forbidden,badEdition),repo.key(safe) to listOf(safe))
        val selection=selectChannelFeed(channel,listOf(safe,forbidden),listOf(x,blue),membership,complete)
        assertEquals(listOf(safe),selection.posts)
        assertEquals(setOf(repo.key(safe)),selection.counterparts.keys)
        assertFalse(selection.counterparts.values.flatten().any {it.canonicalUrl==badEdition.canonicalUrl})
        val vm=model()
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            // A broad reader context must be shadowed by the channel's admitted context.
            CompositionLocalProvider(LocalPostCounterparts provides mapOf(repo.key(safe) to listOf(safe,badEdition))) {
                PersonalChannelFeed(vm,channel,listOf(safe,forbidden),listOf(x,blue),emptySet(),membership,complete,true,onAdd={},onEdit={})
            }
        }}}
        waitRow(safe);row(forbidden).assertDoesNotExist();row(badEdition).assertDoesNotExist()
        ui.onNodeWithText(badEdition.body).assertDoesNotExist()
        ui.onNodeWithContentDescription("Bluesky original actions").assertDoesNotExist()
        evidence("excluded-edition-stays-out-of-native-card")
    }

    @Test fun onlyMatchingArrivalsAreAdmittedAboveTheReaderWithoutMovingTheCurrentCard() {
        val account=follow();val original=post(3,"Release of the stable package #Software")
        store(original,account)
        val channel=save(rule());show(model());waitRow(original)
        val before=row(original).fetchSemanticsNode().boundsInRoot.top
        val irrelevant=post(1,"A weather report #Local")
        store(irrelevant,account)
        runBlocking {withTimeout(5_000){repo.followedSnapshot(personId=person).first {snapshot->snapshot.posts.any {repo.key(it)==repo.key(irrelevant)}}}}
        ui.waitForIdle()
        row(irrelevant).assertDoesNotExist();ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        val arriving=post(0,"Release of the next package #Software")
        store(arriving,account)
        ui.waitUntil(10_000){repo.key(arriving) in repo.feedMemory.get(feedId(channel)).acceptedKeys}
        assertTrue(repo.feedMemory.get(feedId(channel)).stagedKeys.isEmpty())
        ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        row(irrelevant).assertDoesNotExist()
        assertEquals(before,row(original).fetchSemanticsNode().boundsInRoot.top,2f)
        evidence("matching-arrivals-admitted-with-stable-anchor")
        assertEquals(listOf(repo.key(arriving),repo.key(original)),repo.feedMemory.get(feedId(channel)).acceptedKeys)
        row(irrelevant).assertDoesNotExist()
        ui.onNodeWithTag("Jump to newest").performClick()
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed row ${repo.key(arriving)}"))
        row(arriving).assertIsDisplayed()
    }

    @Test fun editingRulesStartsTheMatchingFeedContextWithoutRestoringOldMembership() {
        val account=follow();val release=post(1,"Release of the small package #Software")
        val local=post(0,"Town meeting tomorrow #Local")
        store(release,account);store(local,account)
        val original=save(rule());show(model());waitRow(release)
        assertEquals(listOf(repo.key(release)),repo.feedMemory.get(feedId(original)).acceptedKeys)
        val edited=repo.channels.save(original.copy(name="Local",keywords=listOf("town"),hashtags=listOf("local")))
        assertNotEquals(feedId(original),feedId(edited))
        waitRow(local)
        row(release).assertDoesNotExist();ui.onNodeWithTag("Feed incoming banner").assertDoesNotExist()
        assertEquals(listOf(repo.key(local)),repo.feedMemory.get(feedId(edited)).acceptedKeys)
        assertEquals(listOf(repo.key(release)),repo.feedMemory.get(feedId(original)).acceptedKeys)
        reveal("Why post ${repo.key(local)}")
        ui.onNodeWithTag("Why post ${repo.key(local)}").performClick()
        ui.onNodeWithText("In Local").assertIsDisplayed();ui.onNodeWithText("Contains “town”").assertIsDisplayed()
        evidence("edited-rules-new-reading-context")
    }

    @Test fun refreshTouchesOnlyEligibleAccountsAndAnUnavailableSelectedSourceStaysPending() {
        val x=follow();val blue=follow(Platform.BLUESKY)
        val excluded=follow(bundle="$person-excluded",name="Excluded creator")
        val outside=follow(bundle="$person-outside",name="Outside creator")
        val disabled=follow(bundle="$person-disabled",name="Paused creator",enabled=false)
        val xPost=post(1,"Release of the stable tools #Software")
        val bluePost=post(2,"Release of the public dashboard #Software",Platform.BLUESKY)
        store(xPost,x);store(bluePost,blue)
        runBlocking {listOf(excluded,outside,disabled).forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}}
        val channel=save(rule().copy(subscriptionIds=setOf(person,excluded.personId!!,disabled.personId!!),excludeSubscriptionIds=setOf(excluded.personId!!)))
        val calls=CopyOnWriteArrayList<String>()
        var failBlue=true
        repo.followLoader={account->
            calls+=account.id
            if(account.id==blue.id&&failBlue)throw SourceFailure(HealthStatus.UNAVAILABLE,"Local fixture source is offline")
            val source=Source(account.target.platform,"local-channel-fixture","https://channel-fixture.invalid")
            val provenance=Provenance(source,System.currentTimeMillis(),source.root)
            val content=when(account.id){x.id->listOf(xPost);blue.id->listOf(bluePost);else->emptyList()}
            Page(content.map {it.copy(provenance=provenance)},provenance=provenance)
        }
        val vm=model()
        ui.runOnIdle {vm.refresh()}
        ui.waitUntil(10_000){!vm.screen.value.loading&&calls.size>=2}
        assertEquals(setOf(x.id,blue.id),calls.toSet());assertEquals(2,calls.size)
        assertEquals(FollowRefreshStatus.SUCCESS,repo.feedMemory.refresh.value[x.id]?.status)
        assertEquals(FollowRefreshStatus.PENDING,repo.feedMemory.refresh.value[blue.id]?.status)
        val keys=listOf(xPost,bluePost).map(repo::key)
        repo.feedMemory.reconcile(feedId(channel),keys);repo.feedMemory.markRead(feedId(channel),keys)
        show(vm);waitFeed()
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed pending subscriptions"))
        ui.onNodeWithTag("Feed pending subscriptions").assertIsDisplayed();ui.onNodeWithTag("Feed caught up").assertDoesNotExist()
        evidence("selected-unavailable-source-remains-pending")
        failBlue=false;calls.clear()
        ui.runOnIdle {vm.refresh()}
        ui.waitUntil(10_000){!vm.screen.value.loading&&calls.size>=2}
        assertEquals(setOf(x.id,blue.id),calls.toSet())
        ui.onNodeWithTag("Combined feed").performScrollToNode(hasTestTag("Feed caught up"))
        ui.onNodeWithTag("Feed caught up").assertIsDisplayed();ui.onNodeWithTag("Feed pending subscriptions").assertDoesNotExist()
        evidence("eligible-sources-successfully-caught-up")
    }

    @Test fun unknownVideoDurationNeverEntersALongVideoChannel() {
        val account=follow()
        val unknown=post(0,"Long recording #Software",media=listOf(Media("https://channel-fixture.invalid/unknown.mp4","video")))
        val short=post(1,"Short recording #Software",media=listOf(Media("https://channel-fixture.invalid/short.mp4","video",durationMs=599_999)))
        val long=post(2,"Long recording with supplied duration #Software",media=listOf(Media("https://channel-fixture.invalid/long.mp4","video",durationMs=600_000)))
        store(unknown,account);store(short,account);store(long,account)
        val channel=save(rule("Long videos").copy(keywords=emptyList(),hashtags=emptyList(),contentTypes=setOf(ChannelContentType.VIDEO),minVideoSeconds=600))
        show(model());waitRow(long)
        row(unknown).assertDoesNotExist();row(short).assertDoesNotExist()
        assertEquals(listOf(repo.key(long)),repo.feedMemory.get(feedId(channel)).acceptedKeys)
        reveal("Why post ${repo.key(long)}")
        ui.onNodeWithTag("Why post ${repo.key(long)}").performClick()
        ui.onNodeWithText("Includes video").assertIsDisplayed()
        ui.onNodeWithText("Video is 10 minutes long; at least 10 minutes required").assertIsDisplayed()
        evidence("known-duration-long-video-explanation")
    }

    @Test fun channelPlatformTabIgnoresAnOlderDetailStackAndBackRestoresTheScopedChannel() {
        val account=follow(Platform.BLUESKY)
        val current=post(0,"Release of the current clean package #Software",Platform.BLUESKY)
        val stale=post(12,"An unrelated older detail page",Platform.BLUESKY)
        store(current,account)
        val reads=CopyOnWriteArrayList<String>()
        repo.directLoader={target,_->
            reads+=target.canonicalUrl
            Page(records.filter {repo.key(it)=="${target.platform.id}:${ContentIdentity.key(target.platform,target.canonicalUrl)}"})
        }
        val vm=model()
        val oldTarget=Target(stale.platform,TargetKind.POST,stale.id,stale.canonicalUrl)
        ui.runOnIdle {vm.platform(Platform.BLUESKY);vm.open(oldTarget)}
        ui.waitUntil(10_000){vm.screen.value.target==oldTarget&&!vm.screen.value.loading}
        assertTrue("The older detail stack was really populated",reads.contains(stale.canonicalUrl))
        val channel=save(rule())
        ui.runOnIdle {vm.combined()}
        show(vm);waitRow(current)
        val readsBefore=reads.size
        ui.onNodeWithTag("Platform tab ${Platform.BLUESKY.id}").performClick()
        ui.waitUntil(5_000){vm.screen.value.platform==Platform.BLUESKY&&!vm.screen.value.combined}
        ui.waitForIdle()
        assertNull("A channel platform tab must remain a feed",vm.screen.value.target)
        assertEquals("Switching a channel platform must not fetch a stale detail",readsBefore,reads.size)
        waitRow(current)
        assertEquals(listOf(repo.key(current)),repo.feedMemory.get(feedId(channel,Platform.BLUESKY.id)).acceptedKeys)
        ui.onNodeWithText(stale.body).assertDoesNotExist()
        evidence("channel-platform-tab-ignores-detail-stack")
        ui.onNodeWithText(current.body).performClick()
        ui.waitUntil(10_000){vm.screen.value.target?.canonicalUrl==current.canonicalUrl&&!vm.screen.value.loading}
        ui.runOnIdle {ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.waitUntil(5_000){vm.screen.value.target==null}
        assertFalse(vm.screen.value.combined)
        assertEquals(Platform.BLUESKY,vm.screen.value.platform)
        assertEquals(channel.id,repo.channels.selected.value)
        waitRow(current);ui.onNodeWithText(stale.body).assertDoesNotExist()
        evidence("back-restores-platform-scoped-channel")
        ui.runOnIdle {vm.settings(SettingsPage.HOME)}
        ui.waitUntil(5_000){vm.screen.value.section=="settings"}
        ui.runOnIdle {ui.activity.onBackPressedDispatcher.onBackPressed()}
        ui.waitUntil(5_000){vm.screen.value.section=="feed"}
        assertNull(vm.screen.value.target);assertFalse(vm.screen.value.combined)
        assertEquals(Platform.BLUESKY,vm.screen.value.platform)
        assertEquals(channel.id,repo.channels.selected.value)
        waitRow(current)
        evidence("settings-back-restores-platform-scoped-channel")
    }

    @Test fun aQuietSelectedCreatorIsNotLostBehindAThousandOutsidePosts() {
        val quiet=follow(name="Quiet developer")
        val busy=follow(bundle="$person-busy",name="Busy outside creator")
        val wanted=post(1_500,"Release from the quiet developer #Software")
        store(wanted,quiet)
        val flood=(1..1_000).map {index->post(index,"Busy outside update $index")}
        // One transaction and prepared statements model the bounded feed pressure
        // without performing a thousand repository refresh/store cycles.
        runBlocking {repo.db.withTransaction {
            repo.dao.putFollow(FollowRow(busy.id,busy.target.platform.id,repo.gson.toJson(busy)))
            val database=repo.db.openHelper.writableDatabase
            database.compileStatement("INSERT OR REPLACE INTO posts (`key`,platform,payload,publishedAt,createdAt,lastViewedAt,bytes,followId) VALUES (?,?,?,?,?,NULL,?,?)").use {postInsert->
                database.compileStatement("INSERT OR IGNORE INTO post_follows (postKey,followId) VALUES (?,?)").use {membershipInsert->
                    flood.forEach {post->
                        val payload=repo.gson.toJson(post)
                        postInsert.clearBindings();postInsert.bindString(1,repo.key(post));postInsert.bindString(2,post.platform.id)
                        postInsert.bindString(3,payload);postInsert.bindLong(4,post.publishedAt!!);postInsert.bindLong(5,now)
                        postInsert.bindLong(6,payload.toByteArray(Charsets.UTF_8).size.toLong());postInsert.bindString(7,busy.id);postInsert.executeInsert()
                        membershipInsert.clearBindings();membershipInsert.bindString(1,repo.key(post));membershipInsert.bindString(2,busy.id);membershipInsert.executeInsert()
                    }
                }
            }
        }}
        val channel=save(rule())
        val broad=runBlocking {withTimeout(10_000){repo.followedSnapshot().first {it.posts.size==1_000}}}
        assertFalse("The global window establishes the old starvation case",broad.posts.any {repo.key(it)==repo.key(wanted)})
        val scoped=runBlocking {withTimeout(10_000){repo.followedSnapshot(channel=channel).first {it.posts.isNotEmpty()}}}
        assertEquals(listOf(repo.key(wanted)),scoped.posts.map(repo::key))
        assertEquals(setOf(quiet.id),scoped.memberships[repo.key(wanted)])
        show(model());waitRow(wanted)
        assertEquals(listOf(repo.key(wanted)),repo.feedMemory.get(feedId(channel)).acceptedKeys)
        flood.take(3).forEach {row(it).assertDoesNotExist()}
        evidence("quiet-selected-source-survives-global-window")
    }

}
