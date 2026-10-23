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
        ui.runOnIdle {ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()}
    }
    @After fun cleanup(){if(::scope.isInitialized)scope.cancel()}
    private fun card(name:String="Useful workshop",platform:Platform=Platform.X,handle:String="other_fixture"):AdjacentCard {
        val p=Profile(repo.routes.target(platform,handle),name,"Public repair notes and practical projects")
        return AdjacentCard(AdjacentDiscovery.key(p.target),p,listOf(AdjacentEvidence(AdjacentKind.FEATURED,seed.target,seed.target.canonicalUrl,System.currentTimeMillis())),100.0)
    }
    private fun evidence(name:String) {
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val file=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"adjacent-discovery/$name.png").apply {parentFile!!.mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
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
            val value=AdjacentSnapshot(listOf(card()),complete=true);val epoch=cache.epoch()
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
