/* anti social, GPL-3.0-or-later. Opt-in isolated-device discovery qualification. */
package app.antisocial.android

import android.content.Context
import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalAnnouncementTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val model get()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private val area=LocalArea("fixture-local","Melbourne","Melbourne, Florida, US",LocalPoint(28.08,-80.60),25,"America/New_York",countryCode="US")
    private val owner=LocalBusiness("fixture-library","Town Library","library",address="123 Main St, Melbourne, FL",locality="Melbourne",point=area.point)
    private lateinit var prefs:String
    private lateinit var backend:LocalDiscoveryBackend
    @Before fun isolate() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};prefs=repo.prefs.export().toString();backend=model.localSearch.backend
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
        ui.runOnIdle {repo.prefs.intervalMinutes=0;model.section("discover");model.localSearch.stop();model.localSearch.mode(LocalSearchMode.EVENTS);model.localSearch.area(area);model.localSearch.text("");model.localSearch.saved(false)}
    }
    @After fun restore() {
        if(!::prefs.isInitialized)return
        ui.runOnIdle {model.localSearch.stop();model.localSearch.backend=backend;ui.activity.setContent {MaterialTheme {androidx.compose.material3.Text("Discovery checks complete")}}}
        ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings()}
    }
    private fun evidence(name:String) {
        ui.waitForIdle();val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {val dir=File(ui.activity.getExternalFilesDir("validation"),"quality-release").apply {mkdirs()};File(dir,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {image.recycle()}
    }
    @Test fun firstCardsArriveBeforeFlyersAndUncertainAnnouncementsStayCollapsed() {
        val session=LocalEvent("fixture-walk","Children’s nature walk",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),venue=owner.name,point=area.point,locality=area.name,description="A family activity exploring the garden.")
        val candidate=LocalAnnouncementCandidate("fixture-possible","Evening storytime","Storytime at the library; time to be announced.","https://library.fixture.invalid/storytime",owner.id,listOf("Date needs confirmation"),emptyList(),System.currentTimeMillis())
        val pending=CompletableDeferred<LocalExtraction>();val started=CompletableDeferred<Unit>()
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(owner),events=listOf(session),announcements=listOf(candidate),posters=listOf(LocalPosterSource(Post("p",Platform.FACEBOOK,"https://www.facebook.com/library/posts/123","Library","",media=listOf(Media("https://image.fixture.invalid/flyer.png"))),owner)))
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction()
            override suspend fun poster(task:LocalPosterSource,query:LocalDiscoveryQuery):LocalExtraction {started.complete(Unit);return pending.await()}
        };model.localSearch.text("something outdoors for toddlers");model.section("discover")}
        ui.onNodeWithTag("Run discovery search").performClick()
        ui.waitUntil(10_000){started.isCompleted&&model.localSearch.state.value.events.isNotEmpty()}
        assertFalse(model.localSearch.state.value.loading);assertTrue(model.localSearch.state.value.enriching)
        ui.onNodeWithText(session.title).assertIsDisplayed();ui.onNodeWithText("Age range not stated").assertIsDisplayed()
        assertEquals(1,model.localSearch.state.value.announcements.size)
        ui.onNodeWithText(candidate.title).assertDoesNotExist();evidence("family-search")
        ui.onNodeWithTag("Local discovery results").performScrollToNode(hasTestTag("Possible events toggle"))
        ui.onNodeWithTag("Possible events toggle").performClick()
        ui.onNodeWithText(candidate.title).assertIsDisplayed();evidence("possible-events")
        pending.complete(LocalExtraction());ui.waitUntil(5000){!model.localSearch.state.value.enriching}
        ui.onNodeWithText(candidate.title).performClick();ui.onAllNodesWithText("Date needs confirmation").onLast().assertIsDisplayed();evidence("announcement-details")
    }
    @Test fun completeOccurrenceReplacesItsUncertainCandidateAndSavedCopySurvivesExpiry():Unit=runBlocking(Dispatchers.IO) {
        val name="announcement-fixture-${System.nanoTime()}.db";var clock=System.currentTimeMillis()
        try {LocalDiscoveryStore(ui.activity,now={clock},databaseName=name).use {store->
            val source="https://www.facebook.com/library/posts/456"
            val candidate=LocalAnnouncementCandidate("possible","Library workshop","Join us",source,owner.id,listOf("Date needs confirmation"),emptyList(),clock)
            store.ingest(LocalExtraction(businesses=listOf(owner),announcements=listOf(candidate)),area)
            assertEquals(1,store.announcements(area).size)
            val event=LocalEvent("complete","Library workshop",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),point=area.point,locality=area.name,urls=listOf(source),checkedAt=clock)
            store.ingest(LocalExtraction(events=listOf(event)),area);assertTrue(store.announcements(area).isEmpty())
            store.save(event);clock+=100L*86400000;store.cleanup()
            assertTrue(store.events().isEmpty());assertEquals(event,store.events(true).single())
        }}finally {ui.activity.deleteDatabase(name)}
    }
    @Test fun bundledOcrReadsARealFlyerWithAiOffAndTorUnavailable():Unit=runBlocking {
        val manager=repo.mediaTools;val wasEnabled=repo.ai.preferences.enabled
        val media=ui.activity.getSharedPreferences("context-media",Context.MODE_PRIVATE);val before=media.all.toMap()
        val file=File(ui.activity.cacheDir,"discovery-offline-flyer.png")
        val policy=repo.gateway.currentPolicy()
        try {
            repo.ai.enabled(false);repo.prefs.localFlyerReading=true;repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val bitmap=Bitmap.createBitmap(1600,700,Bitmap.Config.ARGB_8888)
            try {Canvas(bitmap).apply {drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=66f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
                listOf("Library workshop","October 24, 2026 at 10 AM","Join us at Town Library","Free admission. Ages 1-3.").forEachIndexed {i,line->drawText(line,60f,120f+i*130f,paint)}}
                file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }finally {bitmap.recycle()}
            val result=withTimeout(45_000){manager.discoveryOcr(file)}
            assertTrue(result.text.contains("Library workshop"));assertTrue(result.text.contains("October 24"));assertEquals(64,result.sourceSha256.length)
            assertFalse(repo.ai.preferences.enabled);assertEquals(before,media.all)
            val post=Post("poster",Platform.FACEBOOK,"https://www.facebook.com/library/posts/789","Library","",publishedAt=System.currentTimeMillis())
            val extracted=LocalAnnouncements.extract(post,owner,area,System.currentTimeMillis(),result,"https://image.fixture.invalid/flyer.png",publisherLocationVerified=true)
            assertTrue(extracted.events.isNotEmpty());assertTrue(extracted.events.single().evidence.any {it.region!=null})
        }finally {file.delete();repo.gateway.configure(policy);repo.ai.enabled(wasEnabled)}
    }
    @Test fun cachedFlyerKeepsItsObservationDateButForcedRefreshFailsClosedAndCleansScratch():Unit=runBlocking {
        val image="https://image.fixture.invalid/cache-flyer.png"
        val post=Post("cached-flyer",Platform.FACEBOOK,"https://www.facebook.com/library/posts/799","Library","",publishedAt=System.currentTimeMillis())
            .copy(media=listOf(Media(image)))
        val checked=System.currentTimeMillis()-3600000
        val text="Library workshop\nOctober 24, 2026 at 10 AM\nAt Town Library\nFree admission."
        val hash="a".repeat(64)
        val result=OcrResult(text,listOf(OcrLine(text,95f,1,1,500,100)),"en",600,200,95f,false,sourceSha256=hash)
        val key="flyer-ocr:tesseract5-fast-eng-v2:$hash"
        val receipt="flyer-url:v2:"+Repository.hash(image+"|"+post.body+"|"+post.publishedAt)
        repo.localDiscovery.cache(key,com.google.gson.Gson().toJson(LocalAnnouncementReader.Receipt(result,checked)),86400000)
        repo.localDiscovery.cache(receipt,key,86400000)
        val policy=repo.gateway.currentPolicy();val wasEnabled=repo.ai.preferences.enabled
        try {
            repo.ai.enabled(false);repo.prefs.localFlyerReading=true
            repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
            val task=LocalPosterSource(post,owner,publisherLocationVerified=true)
            val cached=LocalAnnouncementReader(repo).read(task,LocalDiscoveryQuery(area,"",LocalSearchMode.EVENTS))
            assertEquals(checked,cached.events.single().evidence.first().checkedAt)
            val failed=runCatching {LocalAnnouncementReader(repo).read(task.copy(refresh=true),LocalDiscoveryQuery(area,"",LocalSearchMode.EVENTS))}
            assertTrue("Manual refresh must not fall back to a direct request",failed.isFailure)
            assertTrue(File(ui.activity.cacheDir,"discovery-flyers").listFiles().orEmpty().isEmpty())
        }finally {repo.gateway.configure(policy);repo.ai.enabled(wasEnabled)}
    }
    @Test fun freeAdmissionAppliesToPossibleEventsAndDoesNotMeanFreeParking():Unit=runBlocking {
        val now=System.currentTimeMillis()
        val candidates=listOf("Paid workshop. Free parking. Admission $20.","Workshop. Free admission.","Workshop. Price to be announced.").mapIndexed {i,text->
            LocalAnnouncementCandidate("free-check-$i","Workshop",text,"https://library.fixture.invalid/workshop-$i",owner.id,listOf("Date needs confirmation"),emptyList(),now)
        }
        withContext(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(owner),announcements=candidates),area)}
        ui.runOnIdle {model.localSearch.text("");model.localSearch.free(false)}
        model.localSearch.publish();assertEquals(3,model.localSearch.state.value.announcements.size)
        ui.runOnIdle {model.localSearch.free(true)}
        model.localSearch.publish();assertEquals(listOf("free-check-1"),model.localSearch.state.value.announcements.map {it.id})
        ui.runOnIdle {model.localSearch.free(false);model.localSearch.text("free workshop")}
        model.localSearch.publish();assertEquals(listOf("free-check-1"),model.localSearch.state.value.announcements.map {it.id})
    }
    @Test fun imageEvidenceAndFlyerPreferenceRoundTripWithoutBreakingOlderSnapshots():Unit=runBlocking(Dispatchers.IO) {
        val name="flyer-export-${System.nanoTime()}.db"
        try {LocalDiscoveryStore(ui.activity,databaseName=name).use {store->
            val region=LocalImageRegion("https://image.fixture.invalid/flyer.png","a".repeat(64),1,2,100,40,200,300)
            val proof=LocalFieldEvidence("start","2026-10-24","https://library.fixture.invalid/event",0,LocalEvidenceKind.POSTER,"October 24",region=region)
            val event=LocalEvent("fixture-saved-flyer","Workshop",LocalEventTime("2026-10-24",allDay=true),evidence=listOf(proof))
            store.save(event)
            val exported=LocalDiscoveryPortability.encode(store)
            assertEquals(event,LocalDiscoveryPortability.decode(exported).events.single())
            exported["events"].asJsonArray.single().asJsonObject["evidence"].asJsonArray.single().asJsonObject.remove("region")
            assertNull(LocalDiscoveryPortability.decode(exported).events.single().evidence.single().region)
            repo.prefs.localFlyerReading=false;repo.prefs.validate(repo.prefs.export())
            assertFalse(repo.prefs.export().getBoolean("localFlyerReading"))
        }}finally {ui.activity.deleteDatabase(name)}
    }
}
