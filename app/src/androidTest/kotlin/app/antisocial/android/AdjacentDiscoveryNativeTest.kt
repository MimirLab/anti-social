package app.antisocial.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdjacentDiscoveryNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val seed get()=Profile(repo.routes.target(Platform.X,"graph_fixture"),"Community workshop")
    private lateinit var scope:CoroutineScope
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
            ui.activity.setContent {androidx.compose.material3.Text("Preparing discovery fixtures")}
        }
    }
    @After fun cleanup(){if(::scope.isInitialized)scope.cancel()}
    private fun card(name:String="Useful workshop",platform:Platform=Platform.X,handle:String="other_fixture"):AdjacentCard {
        val p=Profile(repo.routes.target(platform,handle),name,"Public repair notes and practical projects")
        return AdjacentCard(AdjacentDiscovery.key(p.target),p,listOf(AdjacentEvidence(AdjacentKind.FEATURED,seed.target,seed.target.canonicalUrl,System.currentTimeMillis())),100.0)
    }
    private fun evidence(name:String) {
        // Semantics can be ready before the modal surface's first compositor
        // frame. Let the entry animation settle before recording native pixels.
        ui.mainClock.advanceTimeBy(500);ui.waitForIdle();Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val file=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"adjacent-discovery/$name.png").apply {parentFile!!.mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun subscriptionFeedSharingSeedsDiscoveryBeforeAnyProfileOrNetworkRead()=runBlocking {
        val suffix=System.nanoTime().toString().takeLast(9)
        val owner=Profile(repo.routes.target(Platform.X,"cache_$suffix"),"Community workshop")
        val author="crew_$suffix";val id="99${suffix}71"
        val source=Source(Platform.X,"nitter","https://reader.example")
        val caption="The repair crew published a complete equipment list for the next public workshop."
        val rss="""<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><link>${source.root}/${owner.target.identity}</link><item><title>RT by @${owner.target.identity}: $caption</title><dc:creator>@$author</dc:creator><guid>$id</guid><link>${source.root}/$author/status/$id</link><description>$caption</description></item></channel></rss>"""
        val page=Extraction.parse(Payload(rss,"${source.root}/${owner.target.identity}/rss","application/rss+xml",200),owner.target,source,repo.routes)
        val follow=Follow("${owner.target.platform.id}:${owner.target.canonicalUrl}",owner.target,owner.name)
        val previous=repo.gateway.onNetworkEvent;val requests=java.util.concurrent.atomic.AtomicInteger()
        try {
            repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
            repo.store(page,follow.id,true)
            assertNull(repo.cachedPage(owner.target))
            repo.gateway.onNetworkEvent={if(it.stage=="start")requests.incrementAndGet()}
            val started=android.os.SystemClock.elapsedRealtime()
            val runtime=repo.adjacentSession(owner)
            val snapshot=runtime.session.current();val cards=snapshot.cards
            val readyMs=android.os.SystemClock.elapsedRealtime()-started
            assertTrue("Cached feed relationships never reached the initial discovery deck",cards.any {it.profile.target.identity==author&&it.evidence.any {e->e.kind==AdjacentKind.FORWARDED}})
            assertEquals(0,requests.get())
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,AdjacentUiState(owner,snapshot=snapshot),{},{},{},{},{},{})}}}
            ui.onNodeWithText(author).assertIsDisplayed()
            val visibleMs=android.os.SystemClock.elapsedRealtime()-started
            evidence("cached-feed-sharing-initial-deck")
            val out=File(ui.activity.getExternalFilesDir("validation"),"adjacent-discovery/cached-feed-seed-timing.json")
            out.writeText(repo.gson.toJson(mapOf("sessionReadyMs" to readyMs,"firstCardMs" to visibleMs,"requestStarts" to requests.get())))
        }finally {
            repo.gateway.onNetworkEvent=previous
            repo.dao.removePosts(page.posts.map(repo::key));repo.dao.removeFollow(follow.id)
        }
    }
    @Test fun aFacebookSeedStreamsCachedConnectionsFromItsLinkedXAccountWithoutNetworkReads()=runBlocking {
        val suffix=System.nanoTime().toString().takeLast(9)
        val owner=Profile(repo.routes.target(Platform.FACEBOOK,"987$suffix"),"Community workshop")
        val linked=repo.routes.target(Platform.X,"hop_$suffix");val author="peer_$suffix";val id="99${suffix}73"
        val bundle="cached-bundle-$suffix"
        val follows=listOf(owner.target,linked).map {Follow("${it.platform.id}:${it.canonicalUrl}",it,it.identity,personId=bundle)}
        val now=System.currentTimeMillis()
        val post=Post(id,Platform.X,"https://x.com/$author/status/$id",author,"A public workshop announcement",publishedAt=now,
            provenance=Provenance(Source(Platform.X,"nitter","https://reader.example"),now,"https://reader.example/${linked.identity}/rss"),
            publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,linked.canonicalUrl)))
        val controller=AdjacentController(repo,scope,
            identities={p->AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Entered",AccountLinkKind.ENTERED)))},
            next={runtime,update->runtime.session.current().copy(hasMore=false,complete=true).also(update)},
            bridge={account,_,_->BridgePage(account)})
        val previous=repo.gateway.onNetworkEvent;val requests=java.util.concurrent.atomic.AtomicInteger()
        try {
            follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}
            repo.store(Page(listOf(post)),follows.single {it.target==linked}.id,true)
            repo.gateway.onNetworkEvent={if(it.stage=="start")requests.incrementAndGet()}
            ui.runOnIdle {controller.start(owner)}
            ui.waitUntil(5_000){controller.state.value.snapshot.cards.any {it.profile.target.identity==author}}
            val card=controller.state.value.snapshot.cards.single {it.profile.target.identity==author}
            assertEquals(linked,card.evidence.single().owner);assertEquals(AdjacentKind.FORWARDED,card.evidence.single().kind)
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,controller.state.collectAsState().value,{},{},{},{},{},{})}}}
            ui.onNodeWithText(author).assertIsDisplayed();assertEquals(0,requests.get())
            evidence("cross-platform-cached-feed-seed")
        }finally {
            ui.runOnIdle {controller.stop()};repo.gateway.onNetworkEvent=previous
            repo.dao.removePosts(listOf(repo.key(post)));follows.forEach {repo.dao.removeFollow(it.id)}
        }
    }
    @Test fun cachedDiscoveryIsBoundedToTheExactEnabledAccountAndUnexpiredSmallRecords()=runBlocking {
        val suffix=System.nanoTime().toString().takeLast(9);val owner=Profile(repo.routes.target(Platform.X,"cache_$suffix"),"Workshop")
        val follow=Follow("${owner.target.platform.id}:${owner.target.canonicalUrl}",owner.target,owner.name)
        val other=Follow("foreign-$suffix",repo.routes.target(Platform.X,"other_$suffix"),"Unrelated")
        val now=System.currentTimeMillis();val keys=mutableListOf<String>();val days=repo.prefs.unseenDays;val viewedDays=repo.prefs.viewedDays
        val damagedBookmark="damaged-cache-$suffix";var savedKey:String?=null
        suspend fun put(index:Int,source:Follow=follow,age:Long=0,body:String="A public workshop announcement",payload:String?=null,viewedAgo:Long?=null):Post {
            val id="99${suffix}${100+index}";val author="peer${index}_${suffix.takeLast(8)}"
            val post=Post(id,Platform.X,"https://x.com/$author/status/$id",author,body,publishedAt=now-(if(index>=90)index else 10_000+index),
                provenance=Provenance(Source(Platform.X,"nitter","https://reader.example"),now,"https://reader.example/${owner.target.identity}/rss"),
                publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,owner.target.canonicalUrl)))
            val key=repo.key(post);keys+=key
            // A deliberately wrong size hint must not defeat the SQL byte cap.
            repo.dao.putPost(PostRow(key,post.platform.id,payload ?: repo.gson.toJson(post),post.publishedAt,now-age,lastViewedAt=viewedAgo?.let {now-it},bytes=1,followId=source.id))
            return post
        }
        try {
            repo.prefs.unseenDays=1;repo.prefs.viewedDays=1
            for(f in listOf(follow,other))repo.dao.putFollow(FollowRow(f.id,f.target.platform.id,repo.gson.toJson(f)))
            val ordinary=(0..80).map {put(it)}
            val foreign=put(90,other);val expired=put(91,age=2*CachePolicy.DAY);val oversized=put(92,body="x".repeat(40*1024));put(93,payload="{}")
            val expiredViewed=put(94,viewedAgo=2*CachePolicy.DAY)
            // The real reader must keep serving good records while the cache
            // contains the same malformed candidate seen by local discovery.
            val reader=withTimeout(5_000){repo.posts(Platform.X).first {it.any {post->post.canonicalUrl==ordinary.first().canonicalUrl}}}
            assertTrue(reader.any {it.canonicalUrl==ordinary.first().canonicalUrl})
            savedKey=repo.key(ordinary.first())
            repo.dao.putBookmark(BookmarkRow(savedKey,Platform.X.id,repo.gson.toJson(ordinary.first()),now))
            repo.dao.putBookmark(BookmarkRow(damagedBookmark,Platform.X.id,"{broken",now))
            val saved=withTimeout(5_000){repo.bookmarks.first {it.any {post->post.canonicalUrl==ordinary.first().canonicalUrl}}}
            assertTrue(saved.any {it.canonicalUrl==ordinary.first().canonicalUrl})
            assertEquals("{broken",repo.dao.allBookmarks().single {it.key==damagedBookmark}.payload)
            val values=repo.cachedAdjacentObservations(owner)
            val captured=values.flatMap {it.evidence.records}.toSet()
            // One malformed candidate consumes a bounded slot but supplies no
            // invented connection. The observer does not expand its allowance.
            assertEquals(79,captured.size);assertTrue(captured.all {url->ordinary.any {it.canonicalUrl==url}})
            assertTrue(listOf(foreign,expired,expiredViewed,oversized).none {it.canonicalUrl in captured})
            val plan=repo.db.query(androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN "+CACHED_DISCOVERY_POSTS_SQL,
                arrayOf<Any?>(follow.id,Platform.X.id,now-CachePolicy.DAY,now-CachePolicy.DAY,32*1024,80))).use {cursor->
                buildList {while(cursor.moveToNext())add(cursor.getString(3))}
            }
            assertTrue(plan.toString(),plan.any {it.contains("post_follows")&&it.contains("followId")})
            assertTrue(plan.toString(),plan.any {it.contains("key=?")})
            assertFalse(plan.toString(),plan.any {it.contains("index_posts_platform")||it.startsWith("SCAN p ")})
            File(ui.activity.getExternalFilesDir("validation"),"adjacent-discovery/cache-query-plan.txt").apply {parentFile!!.mkdirs();writeText(plan.joinToString("\n"))}
            repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow.copy(enabled=false))))
            assertTrue(repo.cachedAdjacentObservations(owner).isEmpty())
        }finally {
            repo.prefs.unseenDays=days;repo.prefs.viewedDays=viewedDays;repo.dao.removePosts(keys)
            repo.dao.removeBookmark(damagedBookmark);savedKey?.let {repo.dao.removeBookmark(it)}
            repo.dao.removeFollow(follow.id);repo.dao.removeFollow(other.id)
        }
    }
    @Test fun aManualPurgeCannotSeedOrReviveAnOldDiscoverySessionAndKeepsSavedState()=runBlocking {
        val suffix=System.nanoTime().toString().takeLast(9);val owner=Profile(repo.routes.target(Platform.X,"cache_$suffix"),"Workshop")
        val follow=Follow("${owner.target.platform.id}:${owner.target.canonicalUrl}",owner.target,owner.name)
        val id="99${suffix}72";val now=System.currentTimeMillis()
        val post=Post(id,Platform.X,"https://x.com/repair_peer/status/$id","repair_peer","A public workshop announcement",publishedAt=now,
            provenance=Provenance(Source(Platform.X,"nitter","https://reader.example"),now,"https://reader.example/${owner.target.identity}/rss"),
            publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,owner.target.canonicalUrl)))
        val key=repo.key(post);val payload=repo.gson.toJson(post);var held=false;var clearing:Job?=null
        val previous=repo.gateway.onNetworkEvent;val requests=java.util.concurrent.atomic.AtomicInteger()
        try {
            repo.dao.putFollow(FollowRow(follow.id,follow.target.platform.id,repo.gson.toJson(follow)))
            repo.store(Page(listOf(post)),follow.id,true);repo.dao.putBookmark(BookmarkRow(key,post.platform.id,payload,now))
            val runtime=repo.adjacentSession(owner);assertTrue(runtime.session.current().cards.isNotEmpty())
            repo.mediaMutex.lock();held=true
            val epoch=repo.adjacentStore.epoch();clearing=launch(Dispatchers.IO){repo.cleanup(true)}
            withTimeout(10_000){while(repo.adjacentStore.epoch()==epoch)delay(10)}
            repo.gateway.onNetworkEvent={if(it.stage=="start")requests.incrementAndGet()}
            assertTrue(repo.cachedAdjacentObservations(owner).isEmpty())
            try {withTimeout(2_000){repo.adjacentSession(owner)};fail("Discovery admitted ordinary data during a purge")}
            catch(e:SourceFailure){assertEquals(HealthStatus.UNKNOWN,e.status)}
            try {repo.adjacentNext(runtime){};fail("An old discovery session restarted after its cache was cleared")}
            catch(e:SourceFailure){assertEquals(HealthStatus.UNKNOWN,e.status)}
            repo.mediaMutex.unlock();held=false;withTimeout(30_000){clearing.join()}
            assertEquals(payload,repo.dao.allBookmarks().single {it.key==key}.payload)
            assertTrue(repo.dao.allFollows().any {it.id==follow.id})
            assertTrue(repo.adjacentSession(owner).session.current().cards.isEmpty());assertEquals(0,requests.get())
        }finally {
            if(held)repo.mediaMutex.unlock();repo.gateway.onNetworkEvent=previous
            withContext(NonCancellable) {
                clearing?.cancelAndJoin();repo.dao.removeBookmark(key);repo.dao.removePosts(listOf(key));repo.dao.removeFollow(follow.id)
            }
        }
    }
    @Test fun nativeCardsFilterByPlatformAndTextAndKeepActionsAndReasonsAvailable() {
        val first=card();val second=card("Library maker group",Platform.BLUESKY,"library.test")
        val value=AdjacentUiState(seed,snapshot=AdjacentSnapshot(listOf(first,second),complete=true))
        var opened:Target?=null;var subscribed:Profile?=null
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,value,{opened=it},{subscribed=it},{},{},{},{})}}}
        ui.onNodeWithText("Useful workshop").assertIsDisplayed();ui.onNodeWithText("Useful workshop").performClick();assertEquals(first.profile.target,opened)
        ui.onNodeWithContentDescription("Subscribe locally to Useful workshop").performClick();assertEquals(first.profile,subscribed)
        ui.onAllNodesWithText("Featured by this account").onFirst().performClick();ui.onNodeWithText("Source account").assertIsDisplayed()
        evidence("connection-evidence")
        ui.onNodeWithTag("Related platform bluesky").performClick();ui.onNodeWithText("Useful workshop").assertDoesNotExist();ui.onNodeWithText("Library maker group").assertIsDisplayed()
        ui.onNodeWithTag("Related all platforms").performClick();ui.onNodeWithContentDescription("Filter connections").performClick()
        ui.onNodeWithTag("Filter related accounts").performTextInput("library")
        ui.onNodeWithText("Useful workshop").assertDoesNotExist();ui.onNodeWithText("Library maker group").assertIsDisplayed()
        evidence("filtered-connections")
    }
    @Test fun onlyEvidenceBackedAliasesCollectRowsAndTheFirstPublishedKeySurvives() {
        val x=card();val instagram=card("Useful workshop",Platform.INSTAGRAM,"other_fixture");val bsky=card("Useful workshop",Platform.BLUESKY,"other.test")
        val value=AdjacentUiState(seed,snapshot=AdjacentSnapshot(listOf(x,instagram,bsky)))
        val guessed=AccountDiscovery("Workshop",listOf(AccountCandidate(instagram.profile.target,"Same handle",AccountLinkKind.USERNAME)))
        assertEquals(3,adjacentGroups(value.copy(identities=mapOf(x.key to guessed))).size)
        val bio=guessed.copy(candidates=listOf(AccountCandidate(instagram.profile.target,"Bio link",AccountLinkKind.PROFILE)))
        assertEquals(3,adjacentGroups(value.copy(identities=mapOf(x.key to bio))).size)
        val website="https://workshop.example/"
        val declared=AccountDiscovery("Workshop",listOf(AccountCandidate(x.profile.target,"Opened",AccountLinkKind.ENTERED),AccountCandidate(instagram.profile.target,"Named website",AccountLinkKind.WEBSITE,website)),
            nameHints=listOf(AccountNameHint(x.profile.target,"Workshop",AccountLinkKind.WEBSITE,website,123,"1".repeat(32)),AccountNameHint(instagram.profile.target,"Workshop",AccountLinkKind.WEBSITE,website,123,"2".repeat(32))))
        assertEquals(3,adjacentGroups(value.copy(identities=mapOf(x.key to declared))).size)
        val together=declared.copy(nameHints=declared.nameHints.map {it.copy(identityGroup="1".repeat(32))})
        assertEquals(2,adjacentGroups(value.copy(identities=mapOf(x.key to together))).size)
        assertEquals(3,adjacentGroups(value.copy(identities=mapOf(x.key to together.copy(nameHints=together.nameHints.map {it.copy(identityGroup=null)})))).size)
        val linked=guessed.copy(candidates=listOf(AccountCandidate(instagram.profile.target,"Listed together",AccountLinkKind.DIRECTORY,"https://www.wikidata.org/wiki/Q1")))
        val groups=adjacentGroups(value.copy(identities=mapOf(x.key to linked)))
        assertEquals(2,groups.size);assertEquals(x.key,groups.first().key);assertEquals(setOf(x.profile.target,instagram.profile.target),groups.first().accounts.toSet())
        assertEquals(bsky.key,groups.last().key)
    }
    @Test fun initialCardsDoNotWaitForIdentityDiscoveryAndDismissalStopsLateUpdates() {
        val graphGate=CompletableDeferred<Unit>();val identitiesGate=CompletableDeferred<Unit>();val first=card();var identityStarted=false
        val session=AdjacentSession(seed,repo.routes,read={throw IllegalStateException("No real requests in this fixture")},initial=listOf(AdjacentObservation(first.profile,first.evidence.single())))
        val controller=AdjacentController(repo,scope,create={_,_,_->AdjacentRuntime(session,"fixture",0,repo.gateway.currentPolicy(),false)},
            identities={p->identityStarted=true;identitiesGate.await();AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Entered",AccountLinkKind.ENTERED),AccountCandidate(repo.routes.target(Platform.BLUESKY,"workshop.bsky.social"),"Exact directory",AccountLinkKind.DIRECTORY,"https://www.wikidata.org/wiki/Q1")))},
            next={_,update->val result=AdjacentSnapshot(listOf(first),complete=true);update(result.copy(loading=true));graphGate.await();update(result);result})
        ui.runOnIdle {controller.start(seed)}
        ui.waitUntil(5000){controller.state.value.snapshot.cards.isNotEmpty()&&identityStarted}
        assertTrue(controller.state.value.findingBridges);assertFalse(identitiesGate.isCompleted)
        ui.runOnIdle {identitiesGate.complete(Unit)}
        ui.waitUntil(5000){controller.state.value.bridges.isNotEmpty()&&!controller.state.value.findingBridges}
        assertTrue("A completed identity lane hid the still-running graph lane",controller.state.value.snapshot.loading)
        ui.runOnIdle {controller.stop();graphGate.complete(Unit)}
        ui.waitForIdle();assertNull(controller.state.value.seed);assertTrue(controller.state.value.snapshot.cards.isEmpty())
    }
    @Test fun visibleIdentityWorkIsSignalledAndCancelledWithoutChangingTheGraph() {
        val aliasGate=CompletableDeferred<Unit>();val first=card();val session=AdjacentSession(seed,repo.routes,read={throw IllegalStateException("Fixture")})
        val controller=AdjacentController(repo,scope,create={_,_,_->AdjacentRuntime(session,"fixture",0,repo.gateway.currentPolicy(),false)},
            identities={p->if(p.target!=seed.target)aliasGate.await();AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Entered",AccountLinkKind.ENTERED)))},
            next={_,update->AdjacentSnapshot(listOf(first),complete=true).also(update)})
        ui.runOnIdle {controller.start(seed)}
        ui.waitUntil(5000){!controller.state.value.loading&&controller.state.value.snapshot.cards.isNotEmpty()}
        ui.runOnIdle {controller.visible(listOf(first.key))}
        ui.waitUntil(5000){first.key in controller.state.value.processing}
        assertEquals(listOf(first),controller.state.value.snapshot.cards)
        ui.runOnIdle {controller.stop();aliasGate.complete(Unit)};ui.waitForIdle();assertTrue(controller.state.value.identities.isEmpty());assertTrue(controller.state.value.processing.isEmpty())
    }
    @Test fun neighborhoodCacheExpiresBoundsFilesAndRejectsMalformedEnumsAndLateWrites() {
        val directory=File(ui.activity.cacheDir,"neighbor-test-${System.nanoTime()}");var now=1000L;val cache=AdjacentStore(directory){now}
        try {
            val original=card();val value=AdjacentSnapshot(listOf(original.copy(evidence=original.evidence.map {it.copy(checkedAt=now)})),complete=true);val epoch=cache.epoch()
            repeat(60){cache.put("entry-$it",value,epoch)};assertTrue(directory.listFiles().orEmpty().size<=48);assertTrue(cache.bytes()<=2*1024*1024)
            assertTrue(cache.get("entry-59")!!.fresh);now+=16*60_000;assertFalse(cache.get("entry-59")!!.fresh)
            val file=File(directory,Repository.hash("entry-59")+".json");file.writeText(file.readText().replace("FEATURED","UNKNOWN_KIND"));assertNull(cache.get("entry-59"))
            cache.cleanup(true);cache.put("late",value,epoch);assertNull(cache.get("late"))
            cache.put("expired",value,cache.epoch());now+=7*60*60_000;assertNull(cache.get("expired"))
        }finally {directory.deleteRecursively()}
    }
    @Test fun cachedDiscussionParticipantsAreDistinctExpireAndCannotReturnAfterCacheClear()=runBlocking {
        val file=File(ui.activity.cacheDir,"participants-test-${System.nanoTime()}.json");var now=1000L;val bank=PublicParticipantBank(file,repo.routes){now}
        fun root(handle:String,id:String)=Post(id,Platform.X,"https://x.com/$handle/status/$id",handle,"Post")
        fun replies(offset:Int)=Page(listOf("reader_one","reader_two").mapIndexed {i,name->Post("${offset+i}",Platform.X,"https://x.com/$name/status/${offset+i}","A readable display name","Reply",parentId="root")})
        try {
            val other=root("other_fixture","2")
            bank.observe(root("graph_fixture","1"),replies(11));bank.observe(other,replies(21));bank.observe(other,replies(21))
            assertEquals(2,bank.related(seed).size)
            val session=AdjacentSession(seed,repo.routes,read={throw IllegalStateException("No request")},initial=bank.related(seed))
            assertEquals(2,session.current().cards.single().evidence.single().count)
            val restored=PublicParticipantBank(file,repo.routes){now};assertEquals(2,restored.related(seed).size)
            val epoch=bank.epoch();bank.cleanup(true);bank.observe(other,replies(31),epoch);assertEquals(0,bank.related(seed).size);assertFalse(file.exists())
            bank.observe(root("graph_fixture","1"),replies(11));bank.observe(other,replies(21));now+=8*CachePolicy.DAY;bank.cleanup(false);assertTrue(bank.related(seed).isEmpty());assertFalse(file.exists())
        }finally {file.delete()}
    }
    @Test fun sourceBoundInstagramParticipantsCrossPollinateWithoutDisplayNameOrHandleGuesses() {
        val file=File(ui.activity.cacheDir,"instagram-participants-${System.nanoTime()}.json")
        val bank=PublicParticipantBank(file,repo.routes){1000L}
        fun root(handle:String,code:String)=Post(code,Platform.INSTAGRAM,"https://www.instagram.com/p/$code/","Same publication name","Post",authorAccount=PostAuthorAccounts.instagram(handle))
        fun reply(handle:String,id:String)=Post(id,Platform.INSTAGRAM,"https://www.instagram.com/p/ABC/c/$id/","Same participant name","A reply",parentId="post:ABC",authorAccount=PostAuthorAccounts.instagram(handle))
        val first=root("source_one","ABC");val other=root("source_two","DEF")
        val profile=Profile(requireNotNull(PostAuthorAccounts.target(first)),first.author)
        try {
            bank.observe(first,Page(listOf(reply("shared_reader","11"),reply("unrelated_reader","12"))))
            bank.observe(other,Page(listOf(reply("shared_reader","21"),reply("different_reader","22"))))
            val value=bank.related(profile).single()
            assertEquals("source_two",value.profile.target.identity);assertEquals("shared_reader",value.evidence.via?.identity)
            val restored=PublicParticipantBank(file,repo.routes){1000L}
            assertEquals(bank.related(profile),restored.related(profile))
            bank.cleanup(true)
            bank.observe(first.copy(authorAccount=null),Page(listOf(reply("shared_reader","31"))))
            bank.observe(other,Page(listOf(reply("shared_reader","41").copy(authorAccount=null))))
            assertTrue(bank.related(profile).isEmpty())
        }finally {file.delete()}
    }

    @Test fun selectedTorUnavailableKeepsCachedConnectionsAndNeverTurnsIntoDirectTraffic()=runBlocking {
        val old=repo.gateway.currentPolicy();val oldTor=repo.prefs.tor;val fallback=repo.prefs.originalFallback
        repo.tor.enable(false);delay(100);repo.prefs.tor=true;repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            val post=Post("1",Platform.X,"https://x.com/graph_fixture/status/1","Workshop","Read @other_fixture")
            val runtime=repo.adjacentSession(seed,Page(listOf(post),profile=seed),true)
            val result=repo.adjacentNext(runtime){}
            assertTrue(result.cards.any {it.profile.target.identity=="other_fixture"});assertTrue(repo.gateway.currentPolicy().tor)
            assertTrue(result.coverage.none {it.status==HealthStatus.HEALTHY});assertTrue(result.coverage.any {it.detail.contains("Tor")})
        }finally {repo.prefs.tor=oldTor;repo.prefs.originalFallback=fallback;repo.gateway.configure(old);repo.adjacentStore.cleanup(true)}
    }
    @Test fun freshCacheStillExpandsEveryLinkedProviderAndYoutubePeersReturnAsReadableSocialAccounts() {
        val session=AdjacentSession(seed,repo.routes,read={throw IllegalStateException("Fixture")})
        val yt=PublicAccount("youtube","https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",id="UCaaaaaaaaaaaaaaaaaaaaaa",kind=AccountLinkKind.DIRECTORY)
        val peer=PublicAccount("youtube","https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb","Practical workshop","UCbbbbbbbbbbbbbbbbbbbbbb")
        val local=listOf(repo.routes.target(Platform.BLUESKY,"workshop.test"),repo.routes.target(Platform.REDDIT,"u/fixture"),repo.routes.target(Platform.MASTODON,"https://masto.example/@fixture"))
        val seen=linkedSetOf<Platform>();var reverseReads=0
        val controller=AdjacentController(repo,scope,create={_,_,_->AdjacentRuntime(session,"fixture",0,repo.gateway.currentPolicy(),true)},
            identities={p->seen+=p.target.platform;AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Entered",AccountLinkKind.ENTERED)),publicAccounts=if(p.target==seed.target)listOf(yt)else emptyList())},
            next={_,update->AdjacentSnapshot(listOf(card()),complete=true).also(update)},roots={local},
            bridge={account,related,_->if(related)BridgePage(account,neighbors=listOf(BridgeNeighbor(peer,AdjacentKind.FEATURED,"Creator selected","https://www.youtube.com/@fixture")))else {reverseReads++;BridgePage(account,links=listOf("https://x.com/peer_fixture","https://bsky.app/profile/peer.test"))}})
        ui.runOnIdle {controller.start(seed)}
        ui.waitUntil(8000){controller.state.value.bridgeMethods.firstOrNull()?.resolved?.values?.flatten()?.size==2&&!controller.state.value.loading}
        assertTrue(seen.containsAll(local.map {it.platform}+Platform.X+Platform.YOUTUBE));assertTrue(reverseReads>0)
        val method=controller.state.value.bridgeMethods.single();assertEquals("Practical workshop",method.page.neighbors.single().account.name)
        assertEquals(setOf(Platform.X,Platform.BLUESKY),method.resolved.values.flatten().map {it.target.platform}.toSet())
        assertTrue(method.processing.isEmpty());assertFalse(ReaderScope.isFeedSource(Platform.YOUTUBE))
        val order=controller.state.value.methodOrder;assertEquals(order.distinct(),order)
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,controller.state.collectAsState().value,{},{},{},controller::refresh,controller::visible,{})}}}
        ui.onNodeWithTag("Related accounts").performScrollToNode(hasTestTag("Discovery method YouTube Creator recommendations"))
        ui.onNodeWithText("Practical workshop").assertIsDisplayed();ui.onNodeWithText("Public accounts found").assertIsDisplayed()
        evidence("method-deck-youtube-return")
        ui.runOnIdle {controller.stop()}
    }
    @Test fun streamedMethodOrderStaysStableWhenAnEarlierAccountGetsEvidenceFromASlowerMethod() {
        val first=card();val slower=first.copy(evidence=listOf(first.evidence.single().copy(kind=AdjacentKind.SHARED_PARTICIPANTS,owner=repo.routes.target(Platform.BLUESKY,"workshop.test"),count=2)))
        val order=listOf(discoveryMethod(first.evidence.single()),discoveryMethod(slower.evidence.single()))
        val state=AdjacentUiState(seed,AdjacentSnapshot(listOf(first.copy(evidence=slower.evidence+first.evidence))),methodOrder=order)
        assertEquals(order,adjacentDeck(state).map {it.key})
        assertEquals(listOf("Creator recommendations","Shared commenters"),adjacentDeck(state).map {it.label})
    }

    @Test fun liveNasaBundleUsesTheDeckAcrossNativeProvidersAndYoutubeWithoutManualExpansion() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveAdjacent")=="true")
        val original=repo.prefs.originalFallback;val policy=repo.gateway.currentPolicy()
        repo.prefs.originalFallback=true;repo.gateway.configure(NetworkPolicy())
        val nasa=Profile(repo.routes.target(Platform.X,"NASA"),"NASA",displayNameSupplied=false)
        val local=listOf("https://x.com/NASA","https://x.com/WomenNASA","https://x.com/NASASTEM","https://x.com/NASA_Astronauts","https://x.com/NASA_Technology","https://x.com/NASAInterns","https://x.com/nasahqphoto","https://www.instagram.com/nasa","https://www.facebook.com/NASA","https://social.beachcom.org/@nasa","https://www.threads.com/@nasa","https://www.reddit.com/user/nasa").mapNotNull(repo.routes::recognize)
        val controller=AdjacentController(repo,scope,roots={local})
        try {
            ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,controller.state.collectAsState().value,{},{},controller::more,controller::refresh,controller::visible,{})}};controller.start(nasa)}
            ui.waitUntil(90_000){val s=controller.state.value;s.bridgeMethods.any {it.page.account.provider=="youtube"&&it.resolved.values.any {it.isNotEmpty()}}&&s.snapshot.coverage.any {it.source.contains("reddit")}}
            val state=controller.state.value
            assertTrue(state.snapshot.cards.isNotEmpty());assertTrue(state.bridges.any {it.target.platform==Platform.REDDIT})
            assertTrue(state.snapshot.coverage.any {it.source.contains("federated")})
            val key=state.bridgeMethods.first {it.page.account.provider=="youtube"}.key
            ui.onNodeWithTag("Related accounts").performScrollToNode(hasTestTag("Discovery method YouTube Creator recommendations"))
            evidence("live-nasa-method-deck")
            val context=ui.activity
            val out=File(requireNotNull(context.getExternalFilesDir("validation")),"adjacent-discovery/live-nasa-deck.json")
            out.writeText(com.google.gson.Gson().toJson(mapOf("nativeCards" to state.snapshot.cards.size,"coverage" to state.snapshot.coverage,"methodOrder" to state.methodOrder,"bridgeProviders" to state.bridgeMethods.map {it.page.account.provider},"youtubeReadableNeighbors" to state.bridgeMethods.first {it.key==key}.resolved.values.count {it.isNotEmpty()})))
        }finally {ui.runOnIdle {controller.stop()};repo.prefs.originalFallback=original;repo.gateway.configure(policy)}
    }

    @Test fun failedBridgeReadsAreUnavailableRatherThanClaimingThereAreNoSocialAccounts() {
        val session=AdjacentSession(seed,repo.routes,read={error("Fixture")})
        val yt=PublicAccount("youtube","https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",id="UCaaaaaaaaaaaaaaaaaaaaaa",kind=AccountLinkKind.DIRECTORY)
        val peer=PublicAccount("youtube","https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb","Unavailable workshop","UCbbbbbbbbbbbbbbbbbbbbbb")
        val controller=AdjacentController(repo,scope,create={_,_,_->AdjacentRuntime(session,"fixture",0,repo.gateway.currentPolicy(),false)},
            identities={p->AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Entered",AccountLinkKind.ENTERED)),publicAccounts=if(p.target==seed.target)listOf(yt)else emptyList())},
            next={_,update->AdjacentSnapshot(complete=true).also(update)},roots={emptyList()},
            bridge={a,related,_->if(related)BridgePage(a,neighbors=listOf(BridgeNeighbor(peer,AdjacentKind.FEATURED,"Creator selected",a.url)))else throw java.io.IOException("Fixture offline")})
        ui.runOnIdle {controller.start(seed)}
        ui.waitUntil(5000){controller.state.value.bridgeMethods.any {it.failed.isNotEmpty()}&&!controller.state.value.loading}
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {AdjacentSheet(repo,controller.state.collectAsState().value,{},{},{},{},{},{})}}}
        ui.onNodeWithText("Lookup unavailable · try again later").assertIsDisplayed()
        ui.onNodeWithText("No public social links found").assertDoesNotExist()
        ui.runOnIdle {controller.stop()}
    }

}
