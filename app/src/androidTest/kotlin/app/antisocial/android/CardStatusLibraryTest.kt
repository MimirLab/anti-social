/* anti social, GPL-3.0-or-later. Isolated native disclosure and local-library journeys. */
package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CardStatusLibraryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var model:AntiSocialViewModel
    private lateinit var preferences:String
    private val area=LocalArea("status-fixture-area","Sampleton","Sampleton, Florida",LocalPoint(28.612188,-80.807545),10,"America/New_York")
    private val place=LocalBusiness("status-fixture-place","Signal Café","restaurant","Coffee, lunch and community workshops","10 Main Street, Sampleton FL","Sampleton",area.point,websites=listOf("https://status-fixture.invalid/menu"),phones=listOf("+1 202 555 0105"),emails=listOf("hello@status-fixture.invalid"),checkedAt=System.currentTimeMillis())
    @Before fun isolate() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString()
        ui.runOnIdle {
            ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
            ui.activity.setContent {Text("Preparing isolated card checks")}
            repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.mentionLookups=false;repo.prefs.localFlyerReading=false
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")}
            model=AntiSocialViewModel(repo)
        }
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
    }
    @After fun restore() {
        if(!::preferences.isInitialized)return
        ui.runOnIdle {model.localSearch.stop();model.viewModelScope.cancel();ui.activity.setContent {Text("Card checks complete")}}
        runBlocking(Dispatchers.IO) {
            val db=repo.localDiscovery.writableDatabase
            for(t in listOf("records","copies","search"))db.delete(t,"id LIKE 'status-fixture-%'",null)
            db.delete("sources","area=?",arrayOf(area.cacheKey));repo.localDiscovery.trim()
        }
        ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()}
    }
    private fun status(id:String,value:String)=ui.onNodeWithTag("Card activity $id").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,value))
    private fun screen(name:String) {
        ui.waitForIdle()
        val frames=CountDownLatch(1)
        ui.runOnIdle {ui.activity.window.decorView.postOnAnimation {ui.activity.window.decorView.postOnAnimation {frames.countDown()}}}
        assertTrue(frames.await(5,TimeUnit.SECONDS))
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(ui.activity.getExternalFilesDir("validation"),"card-status-library").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun onlyTheBusinessWithOutstandingDetailsIsBusyAndCancellationClearsIt() {
        val entered=CompletableDeferred<Unit>();val started=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val blocked=place.copy(id="status-fixture-blocked",name="Second Workshop",address="20 Other Street, Sampleton FL",point=LocalPoint(28.613588,-80.805445),websites=listOf("https://blocked-status-fixture.invalid/"))
        val queued=place.copy(id="status-fixture-queued",name="Third Workshop",address="30 Other Street, Sampleton FL",point=LocalPoint(28.615588,-80.803445),websites=listOf("https://queued-status-fixture.invalid/"))
        val other=place.copy(id="status-fixture-other",name="Community Bakery",address="90 Other Street, Sampleton FL",point=LocalPoint(28.618588,-80.799445),websites=emptyList())
        ui.runOnIdle {
            model.localSearch.backend=object:LocalDiscoveryBackend {
                override suspend fun areas(text:String)=emptyList<LocalArea>()
                override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(place,blocked,queued,other))
                override suspend fun identities(point:LocalPoint)=LocalExtraction()
                override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
                override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                    source.businessId?.takeIf {it in setOf(place.id,blocked.id,queued.id)}?.let {id->
                        started.add(id);if(started.size==2)entered.complete(Unit);awaitCancellation()
                    }
                    return LocalExtraction(validEmpty=true)
                }
            }
            model.localSearch.mode(LocalSearchMode.BUSINESSES);model.localSearch.area(area);model.section("discover")
            ui.activity.setContent {AntiSocialApp(model)}
        }
        ui.onNodeWithTag("Run discovery search").performClick()
        runBlocking {withTimeout(10_000){entered.await()}}
        ui.waitUntil(10_000){model.localSearch.state.value.businesses.size==4}
        status(place.id,"Updating")
        val waiting=listOf(place,blocked,queued).single {it.id !in started}
        ui.onNodeWithTag("Local discovery results").performScrollToNode(hasTestTag("Business ${waiting.id}"))
        status(waiting.id,"Updating");assertFalse("A queued route is visible before it consumes a network slot",waiting.id in started)
        ui.onNodeWithTag("Local discovery results").performScrollToNode(hasTestTag("Business ${other.id}"))
        status(other.id,"Searching");screen("business-scoped-updates")
        ui.runOnIdle {model.localSearch.stop()}
        ui.waitUntil(10_000){repo.cardWork.active.value.none {businessWorkKey(place.id) in it.keys}}
        ui.onNodeWithTag("Local discovery results").performScrollToNode(hasTestTag("Business ${place.id}"))
        status(place.id,"Ready");screen("business-ready-after-stop")
        ui.onAllNodesWithText("Saved",useUnmergedTree=true).assertCountEquals(0)
    }
    @Test fun libraryShowsBothSavedKindsSearchesLocalTextAndOpensCapturedDetailsWithoutDiscovery() {
        val calls=AtomicInteger()
        val event=LocalEvent("status-fixture-event","Puppet workshop",LocalEventTime(area.today().plusDays(1).atTime(14,0).atZone(java.time.ZoneId.of("America/New_York")).toInstant().toString()),description="Learn to make shadow puppets",venue=place.name,address=place.address,locality=area.name,point=area.point,urls=listOf("https://status-fixture.invalid/puppets"),price="Free",checkedAt=System.currentTimeMillis())
        runBlocking(Dispatchers.IO) {
            repo.localDiscovery.save(place);repo.localDiscovery.save(event)
            repo.localLinks.rememberReadable(event.urls.single(),"Library",event)
            repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(place.copy(phones=listOf("+1 202 555 0107"),checkedAt=System.currentTimeMillis()+1000))),area)
        }
        ui.runOnIdle {
            model.localSearch.backend=object:LocalDiscoveryBackend {
                override suspend fun areas(text:String):List<LocalArea>{calls.incrementAndGet();error("Offline")}
                override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction{calls.incrementAndGet();error("Offline")}
                override suspend fun identities(point:LocalPoint):LocalExtraction{calls.incrementAndGet();error("Offline")}
                override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit>{calls.incrementAndGet();error("Offline")}
                override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction{calls.incrementAndGet();error("Offline")}
            }
            model.localSearch.area(area);model.section("saved");ui.activity.setContent {AntiSocialApp(model)}
        }
        ui.onNodeWithTag("Library scope businesses").performClick()
        ui.waitUntil {ui.onAllNodesWithTag("Business ${place.id}").fetchSemanticsNodes().isNotEmpty()}
        screen("library-businesses")
        ui.onNodeWithTag("Business ${place.id}").performClick()
        ui.waitUntil(5_000){ui.onAllNodesWithTag("Business details").fetchSemanticsNodes().isNotEmpty()}
        screen("library-business-details")
        ui.onNodeWithText("+1 (202) 555-0105").performScrollTo().assertIsDisplayed();ui.onAllNodesWithText("+1 (202) 555-0107").assertCountEquals(0)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil {ui.onAllNodesWithText("Website").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("Library scope events").performClick()
        ui.waitUntil {ui.onAllNodesWithTag("Event ${event.id}").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("Library search").performTextInput("puppet")
        ui.onNodeWithTag("Event ${event.id}").assertIsDisplayed();ui.onNodeWithText("14:00",substring=true).assertIsDisplayed();screen("library-events")
        ui.onNodeWithTag("Event ${event.id}").performClick();ui.onNodeWithTag("Announcement ${event.urls.single()}").assertIsDisplayed();ui.onAllNodesWithText("Learn to make shadow puppets").assertCountEquals(2)
        assertEquals(0,calls.get());assertFalse(model.localSearch.state.value.savedOnly)
    }
    @Test fun aMergedPostIncludesWorkOwnedByAnyOfItsSourceEditions() {
        val x=Post("status-fixture-x",Platform.X,"https://x.com/workshop/status/12345","Workshop","A useful public workshop announcement with the complete schedule and clear repair instructions for replacing a laptop battery.")
        val fb=x.copy(id="status-fixture-fb",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/98765")
        val work=repo.cardWork.begin(listOf(postWorkKey(fb)),"Archive history")
        try {
            ui.runOnIdle {ui.activity.setContent {MaterialTheme(colorScheme=darkColorScheme()){Surface(Modifier.fillMaxSize()){PostCard(x,model,false,listOf(x,fb)){}}}}}
            status(x.identityKey,"Updating")
            ui.onNodeWithTag("Card activity ${x.identityKey}").performClick();ui.onNodeWithText("Adding details").assertIsDisplayed()
            ui.onNodeWithText("Done").performClick();screen("post-updating")
            work.close();ui.waitUntil {repo.cardWork.active.value.none {postWorkKey(fb) in it.keys}}
            status(x.identityKey,"Ready");screen("post-ready")
            ui.onNodeWithTag("Card activity ${x.identityKey}").performClick();ui.onNodeWithText("Ready to read").assertIsDisplayed()
        }finally {work.close()}
    }
}
