package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.Instant
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalDiscoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val area=LocalArea("fixture-melbourne","Melbourne","Melbourne, Victoria, Australia",LocalPoint(-37.8136,144.9631),5,"Australia/Melbourne")
    private val place=LocalBusiness("fixture-library","Community Library","library","Books, workshops and a public calendar.","10 Main St, Melbourne","Melbourne",area.point,websites=listOf("https://library.fixture.invalid/"),socials=listOf("https://www.facebook.com/library_fixture","https://www.instagram.com/library_fixture/"),phones=listOf("+61 123"),sourceIds=listOf("fixture-library"),checkedAt=System.currentTimeMillis())
    private val firstDeckArea=LocalArea("fixture-first-deck","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC",countryCode="ZZ")
    private lateinit var preferences:String
    private lateinit var backend:LocalDiscoveryBackend
    private val model get()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    @Before fun isolate() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString();backend=model.localSearch.backend
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
        ui.runOnIdle {repo.prefs.intervalMinutes=0;model.section("discover");model.localSearch.stop();model.localSearch.mode(LocalSearchMode.BUSINESSES);model.localSearch.area(area);model.localSearch.text("");model.localSearch.saved(false)}
    }
    @After fun restore() {
        if(!::preferences.isInitialized)return
        ui.runOnIdle {model.localSearch.stop();model.localSearch.backend=backend;ui.activity.setContent {MaterialTheme {androidx.compose.material3.Text("Local discovery checks complete")}}}
        runBlocking(Dispatchers.IO) {
            val db=repo.localDiscovery.writableDatabase
            db.delete("records","id LIKE 'fixture-%'",null);db.delete("copies","id LIKE 'fixture-%'",null);db.delete("sources","area=?",arrayOf(area.cacheKey));db.delete("cache","id LIKE 'directory:%' OR id LIKE 'search:%' OR id LIKE 'overture:%'",null);repo.localDiscovery.trim()
        }
        ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()}
    }
    private fun show(){ui.runOnIdle {model.section("discover")}}
    private fun evidence(name:String) {
        ui.waitForIdle();android.os.SystemClock.sleep(300);val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(ui.activity.getExternalFilesDir("validation"),"local-discovery").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun nativeSearchConnectsBusinessesToSocialAccountsAndDisplaysDatedEvents() {
        val session=LocalEvent("fixture-event","Puppet storytime",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),description="A family puppet workshop with practical activities.",venue=place.name,address=place.address,locality=area.name,point=area.point,urls=listOf("https://library.fixture.invalid/events/puppets"),price="Free",checkedAt=System.currentTimeMillis())
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun account(url:String)=true
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(place))
            override suspend fun identities(point:LocalPoint)=LocalExtraction(businesses=listOf(place))
            override suspend fun discover(query:LocalDiscoveryQuery)=listOf(LocalWebHit("https://library.fixture.invalid/calendar","Public calendar"))
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction(events=listOf(session))
        }}
        show();ui.onNodeWithTag("Run discovery search").performClick()
        ui.waitUntil(10_000){model.localSearch.state.value.businesses.any {it.id==place.id}&&!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        evidence("business-before-assert");ui.onNodeWithText(place.name).assertIsDisplayed();ui.onNodeWithContentDescription("Open Facebook account").assertIsDisplayed();evidence("businesses")
        ui.onNodeWithContentDescription("Save ${place.name}").performClick();ui.waitUntil {place.id in model.localSearch.state.value.saved}
        ui.onNodeWithTag("Business ${place.id}").performClick();ui.onNodeWithText("+61 123").assertIsDisplayed();ui.onNodeWithText("Website").assertIsDisplayed();evidence("business-details")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5000){ui.onAllNodesWithText("Website").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("Search mode EVENTS").performClick();ui.waitUntil {model.localSearch.state.value.events.any {it.id==session.id}}
        ui.onNodeWithText("Puppet storytime").assertIsDisplayed();ui.onNodeWithText("Community Library · Melbourne · Free").assertIsDisplayed();evidence("events")
        ui.onNodeWithContentDescription("Save Puppet storytime").performClick();ui.waitUntil {session.id in model.localSearch.state.value.saved}
        ui.onNodeWithContentDescription("Library",useUnmergedTree=true).performClick();ui.onNodeWithTag("Library scope events").performClick();ui.waitUntil {ui.onAllNodesWithTag("Event ${session.id}").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("Puppet storytime").assertIsDisplayed()
    }
    @Test fun businessCardsArriveBeforeSocialDetailsAndReadAccountsOnlyOnce() {
        val first=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()
        val accountCalls=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()
        val social=place.copy(name="Community Library",phones=emptyList(),websites=emptyList(),socials=listOf("https://www.facebook.com/library_fixture","https://www.instagram.com/library_fixture/","https://x.com/library_fixture"))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(social))
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun account(url:String):Boolean {accountCalls.computeIfAbsent(url){java.util.concurrent.atomic.AtomicInteger()}.incrementAndGet();return true}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                calls.computeIfAbsent(source.url){java.util.concurrent.atomic.AtomicInteger()}.incrementAndGet()
                if(source.kind!=LocalSourceKind.SOCIAL)return LocalExtraction(validEmpty=true)
                first.complete(Unit);release.await()
                val profile=Profile(repo.routes.recognize(source.url)!!,social.name,"Melbourne\nHours: Monday - Friday 9am - 5pm\nEmail: hello@library.fixture.invalid",links=listOf("https://www.instagram.com/library_fixture/"),displayNameSupplied=true)
                return LocalBusinessSocial.profile(business!!,profile,repo.routes,System.currentTimeMillis())
            }
        }}
        runBlocking(Dispatchers.IO){repo.localDiscovery.cache(model.localSearch.accountKey(social.socials.first()),"usable",6*3600000)}
        show();ui.onNodeWithTag("Run discovery search").performClick()
        ui.waitUntil(10_000){first.isCompleted&&model.localSearch.state.value.businesses.isNotEmpty()}
        assertFalse(model.localSearch.state.value.loading);assertTrue(model.localSearch.state.value.enriching)
        assertTrue(model.localSearch.state.value.businesses.single().hours.isBlank())
        ui.onNodeWithContentDescription("Open Facebook account").assertIsDisplayed()
        ui.onNodeWithText(social.name).assertIsDisplayed();evidence("business-before-social-details")
        release.complete(Unit)
        ui.waitUntil(15_000){!model.localSearch.state.value.enriching&&model.localSearch.state.value.businesses.single().hours.isNotBlank()}
        assertEquals(1,calls[social.socials.first()]?.get());assertEquals(1,calls[social.socials.last()]?.get());assertTrue(accountCalls.isEmpty())
        ui.onNodeWithContentDescription("Open Facebook account").assertIsDisplayed()
        ui.onNodeWithContentDescription("Open Instagram account").assertIsDisplayed()
        ui.onNodeWithTag("Business ${social.id}").performClick()
        ui.onNodeWithText("hello@library.fixture.invalid").assertIsDisplayed()
        evidence("business-social-details")
    }
    @Test fun anUnboundSocialSearchResultCanStillLeadToUsefulLocalWebsiteFacts() {
        val unknown="https://x.com/local_library_updates";val website="https://library.fixture.invalid/contact"
        val calls=java.util.Collections.synchronizedList(mutableListOf<String>())
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(place.copy(socials=emptyList(),websites=emptyList(),phones=emptyList())))) {
            override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend (List<LocalWebHit>)->Unit){onHits(listOf(LocalWebHit(unknown,"Community Library")))}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                calls+=source.url
                return when(source.url) {
                    unknown->LocalExtraction(sources=listOf(LocalPublisherSource(website,label="Community Library",discoveredFrom=unknown)))
                    website->LocalExtraction(businesses=listOf(place.copy(socials=emptyList(),websites=listOf(website),phones=listOf("3212598005"),checkedAt=System.currentTimeMillis())))
                    else->LocalExtraction(validEmpty=true)
                }
            }
        };model.localSearch.search(force=true)}
        show();ui.waitUntil(15_000){!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        assertTrue("Social source was not read: $calls",calls.contains(unknown));assertTrue("Offered website was not read: $calls",calls.contains(website))
        assertEquals(listOf("3212598005"),model.localSearch.state.value.businesses.single().phones)
    }
    @Test fun savedSnapshotSurvivesExpiryAndOrdinaryDirectoryCorrections()=runBlocking {
        var clock=System.currentTimeMillis();val dbName="local-fixture-${System.nanoTime()}.db"
        val store=LocalDiscoveryStore(ui.activity,{clock},dbName)
        try {
            withContext(Dispatchers.IO) {store.ingest(LocalExtraction(businesses=listOf(place)),area);store.save(place)
                store.ingest(LocalExtraction(businesses=listOf(place.copy(phones=listOf("+61 999"),checkedAt=clock+1000))),area)
                assertEquals("+61 123",store.businesses(null,true).single().phones.single())
                clock+=100L*86400000;store.trim();assertTrue(store.businesses(area).isEmpty());assertEquals(place.id,store.businesses(null,true).single().id)
                store.removeSaved(place.id);assertTrue(store.businesses(null,true).isEmpty())}
        }finally {store.close();ui.activity.deleteDatabase(dbName)}
    }
    @Test fun changedSearchCancelsOldDeliveryAndUnavailableSourcesPreserveCachedResults() {
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(place)),area)}
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=emptyList<LocalArea>()
            override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {delay(5000);error("must cancel")}
            override suspend fun identities(point:LocalPoint):LocalExtraction=error("offline")
            override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit> = error("offline")
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction=error("offline")
        };model.localSearch.search(force=true)}
        ui.waitUntil {model.localSearch.state.value.businesses.isNotEmpty()}
        ui.runOnIdle {model.localSearch.text("unrelated")}
        ui.waitUntil {model.localSearch.state.value.businesses.isEmpty()&&!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        assertEquals("unrelated",model.localSearch.state.value.text)
    }
    @Test fun fastIdentityResultsDoNotWaitForSlowerDirectoryAndIndexRoutes() {
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=emptyList<LocalArea>()
            override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {delay(5000);return LocalExtraction()}
            override suspend fun identities(point:LocalPoint)=LocalExtraction(businesses=listOf(place))
            override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit> {delay(5000);return emptyList()}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction()
        };model.localSearch.search(force=true)}
        ui.waitUntil(2000){model.localSearch.state.value.businesses.any {it.id==place.id}&&!model.localSearch.state.value.loading&&model.localSearch.state.value.enriching}
        ui.runOnIdle {model.localSearch.stop()}
    }
    @Test fun openingAnotherPlatformsProfileReturnsToDedicatedSearch() {
        ui.runOnIdle {
            model.section("discover")
            val platform=if(model.screen.value.platform==Platform.FACEBOOK)Platform.X else Platform.FACEBOOK
            val destination=repo.routes.recognize(if(platform==Platform.X)"https://x.com/library_fixture"else "https://www.facebook.com/library_fixture")!!
            model.open(destination)
            assertEquals(destination,model.screen.value.target)
            assertTrue(model.back());assertEquals("discover",model.screen.value.section)
        }
    }
    @Test fun nativeReaderExportIncludesProtectedDiscoveryCopiesAndRejectsMalformedRecords():Unit=runBlocking {
        withContext(Dispatchers.IO){repo.localDiscovery.save(place)}
        val bytes=repo.portability.exportState(includeProgress=false)
        val preview=repo.portability.preview(bytes)
        val record=preview.document.statePayload!!.getAsJsonObject("localDiscovery")
        assertTrue(LocalDiscoveryPortability.decode(record).businesses.any {it.id==place.id})
        assertTrue(preview.savedPosts>0)
        val corrupt=record.deepCopy();corrupt.getAsJsonArray("businesses").first().asJsonObject.addProperty("name",42)
        assertThrows(IllegalArgumentException::class.java){LocalDiscoveryPortability.decode(corrupt)}
    }
    @Test fun eventDatesShowTheirWholeRangeAndSavedYearsStayVisible() {
        val event=LocalEvent("fixture-range","Festival",LocalEventTime("2026-10-16",allDay=true),end=LocalEventTime("2026-10-25",allDay=true))
        val label=eventTime(event,area);assertTrue(label.contains("16 Oct"));assertTrue(label.contains("25 Oct"))
        val year=java.time.LocalDate.now().year-1
        assertTrue(eventTime(event.copy(start=LocalEventTime("$year-10-16",allDay=true),end=null),area).contains(year.toString()))
    }
    @Test fun productionCalendarParserRunsNativelyOnAndroid() {
        val date=area.today().plusDays(1).toString().replace("-", "")
        val body="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:android-calendar\r\nSUMMARY:Workshop\r\nDTSTART;VALUE=DATE:$date\r\nRRULE:FREQ=DAILY;COUNT=2\r\nLOCATION:Melbourne\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val events=LocalCalendars.parse(body,"https://calendar.fixture.invalid/public.ics",System.currentTimeMillis(),area).events
        assertEquals(2,events.size);assertTrue(events.all {it.start.allDay});assertNotEquals(events[0].id,events[1].id)
    }
    @Test fun rememberedAreaPreferencesSurviveModelRecreationAndAreValidatedForExport() {
        assertEquals(area.id,repo.prefs.localArea)
        assertTrue(LocalAreaCodec.decode(repo.prefs.localAreas).any {it==area})
        repo.prefs.validate(JSONObject().put("localAreas",repo.prefs.localAreas).put("localArea",area.id))
        assertThrows(IllegalArgumentException::class.java){repo.prefs.validate(JSONObject().put("localAreas","[{\"id\":null}]"))}
    }
    private fun fixture(data:LocalExtraction,accessible:(String)->Boolean={true})=object:LocalDiscoveryBackend {
        override suspend fun areas(text:String)=listOf(area)
        override suspend fun directory(query:LocalDiscoveryQuery)=data
        override suspend fun identities(point:LocalPoint)=LocalExtraction()
        override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
        override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
            if(source.kind!=LocalSourceKind.SOCIAL)return LocalExtraction()
            if(!accessible(source.url))throw SourceFailure(HealthStatus.UNAVAILABLE,"Fixture account is unavailable")
            return LocalExtraction(businesses=listOfNotNull(business),validEmpty=true)
        }
        override suspend fun account(url:String)=accessible(url)
    }
    @Test fun unitSwitchPreservesRadiusAndPersistsAcrossControllerRecreation() {
        ui.runOnIdle {model.localSearch.area(area.copy(radiusKm=25));model.localSearch.unit(LocalDistanceUnit.KILOMETRES)}
        show();ui.onNodeWithTag("Discovery area").performClick()
        ui.onNodeWithTag("Distance unit mi").performClick()
        ui.onNodeWithText("Search radius · 15.5 mi").assertExists();evidence("miles")
        assertEquals(25,model.localSearch.state.value.area!!.radiusKm)
        assertEquals(LocalDistanceUnit.MILES,repo.prefs.localDistanceUnit)
        repo.prefs.validate(JSONObject().put("localDistanceUnit","mi"))
        assertThrows(IllegalArgumentException::class.java){repo.prefs.validate(JSONObject().put("localDistanceUnit","furlongs"))}
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }
    @Test fun unavailableAccountsStayOutOfCardsAndContactActionsUseDialAndEmail() {
        val value=place.copy(id="fixture-contact",phones=listOf("+1 (321) 254-2211"),emails=listOf("hello@library.fixture.invalid"),socials=listOf("https://www.facebook.com/fixtureLiveBusiness","https://www.facebook.com/fixtureDeadBusiness"))
        ui.runOnIdle {model.localSearch.backend=fixture(LocalExtraction(businesses=listOf(value))){!it.contains("Dead")};model.localSearch.search(force=true)}
        show();ui.waitUntil(10000){!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching&&model.localSearch.state.value.businesses.any {it.id==value.id}}
        assertEquals(listOf(value.socials.first()),model.localSearch.state.value.businesses.first {it.id==value.id}.socials)
        ui.onAllNodesWithContentDescription("Open Facebook account").assertCountEquals(1)
        ui.onNodeWithTag("Business ${value.id}").performClick();evidence("contact-after-open");ui.onNodeWithText("Phone").assertIsDisplayed();ui.onNodeWithText("Email").assertIsDisplayed();evidence("contact-details")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val intent=java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val monitor=object:android.app.Instrumentation.ActivityMonitor(){override fun onStartActivity(value:android.content.Intent):android.app.Instrumentation.ActivityResult? {
            if(value.action in setOf(android.content.Intent.ACTION_DIAL,android.content.Intent.ACTION_SENDTO)){intent.set(value);return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)};return null
        }}
        instrumentation.addMonitor(monitor)
        try {
            ui.onNodeWithText("+1 (321) 254-2211").performClick();ui.waitUntil {intent.get()!=null};assertEquals(android.content.Intent.ACTION_DIAL,intent.get().action);assertEquals("tel:+13212542211",intent.get().dataString)
            intent.set(null);ui.onNodeWithText("hello@library.fixture.invalid").performClick();ui.waitUntil {intent.get()!=null};assertEquals(android.content.Intent.ACTION_SENDTO,intent.get().action);assertEquals("mailto:hello@library.fixture.invalid",intent.get().dataString)
        }finally {instrumentation.removeMonitor(monitor)}
    }
    @Test fun legacyDuplicatesAreReconciledWithoutRewritingASavedCopy():Unit=runBlocking {
        val name="business-merge-${System.nanoTime()}.db";val store=LocalDiscoveryStore(ui.activity,databaseName=name)
        val a=place.copy(id="fixture-old-a",name="Burger Inn",address="1819 N Harbor City Blvd, Melbourne",point=null,locality="Melbourne")
        val b=a.copy(id="fixture-old-b",name="The Burger Inn",address="1819 North Harbor City Boulevard, Melbourne, FL",socials=listOf("https://www.instagram.com/burger/"))
        try {withContext(Dispatchers.IO) {
            store.save(a)
            for(p in listOf(a,b))store.writableDatabase.insertOrThrow("records",null,android.content.ContentValues().apply {put("id",p.id);put("kind","business");put("payload",com.google.gson.Gson().toJson(p));put("checked",System.currentTimeMillis());put("expires",System.currentTimeMillis()+86400000)})
            store.reconcile(area);assertEquals(1,store.businesses(area).size);assertEquals((a.socials+b.socials).toSet(),store.businesses(area).single().socials.toSet())
            assertEquals(a,store.businesses(null,true).single())
        }}finally {store.close();ui.activity.deleteDatabase(name)}
    }
    @Test fun escapedCalendarDuplicatesReconcileLocallyWithoutRenewingOrRewritingSavedCopies():Unit=runBlocking {
        val name="event-merge-${System.nanoTime()}.db";val store=LocalDiscoveryStore(ui.activity,databaseName=name)
        val now=System.currentTimeMillis();val link="https://events.fixture.invalid/event/trick-or-treat/"
        val a=LocalEvent("fixture-event-old","Downtown Melbourne Trick or Treat",LocalEventTime("2026-10-24T11:00-03:00"),venue="Downtown Melbourne, E New Haven Avenue, Melbourne",locality="Melbourne",urls=listOf(link),sourceIds=listOf("calendar-one"),checkedAt=now-100,
            evidence=listOf(LocalDiscoveryIdentity.evidence("start","2026-10-24T11:00-03:00",link,now-100,LocalEvidenceKind.CALENDAR)))
        val b=a.copy(id="fixture-event-web",start=LocalEventTime("2026-10-24T11:00-04:00"),venue="Downtown Melbourne",sourceIds=listOf("web-one"),checkedAt=now,price="Free")
        try {withContext(Dispatchers.IO) {
            store.save(a)
            for(e in listOf(a,b))store.writableDatabase.insertOrThrow("records",null,android.content.ContentValues().apply {put("id",e.id);put("kind","event");put("payload",com.google.gson.Gson().toJson(e));put("checked",e.checkedAt);put("expires",now+60000)})
            store.reconcileEvents()
            val result=store.events().single();assertEquals(a.id,result.id);assertEquals(b.start,result.start);assertEquals("Free",result.price);assertEquals(2,result.sourceIds.size)
            assertEquals(a,store.events(true).single())
            store.readableDatabase.rawQuery("SELECT expires FROM records WHERE id=?",arrayOf(a.id)).use {it.moveToFirst();assertEquals(now+60000,it.getLong(0))}
            store.ingest(LocalExtraction(events=listOf(b.copy(checkedAt=now+1000,price="$5"))),area)
            assertEquals("$5",store.events().single().price);assertEquals(a,store.events(true).single())
        }}finally {store.close();ui.activity.deleteDatabase(name)}
    }
    @Test fun sourceQueueRetainsNewCandidatesAndLaterOwnerBindingsWithoutLosingBackoff():Unit=runBlocking {
        val name="source-queue-${System.nanoTime()}.db";val store=LocalDiscoveryStore(ui.activity,databaseName=name)
        try {withContext(Dispatchers.IO) {
            store.ingest(LocalExtraction(businesses=listOf(place)),area)
            repeat(300){i->store.offerSource(area,LocalPublisherSource("https://source$i.fixture.invalid/",nextCheckAt=123456,failures=2))}
            val url="https://source299.fixture.invalid/"
            store.offerSource(area,LocalPublisherSource(url,businessId=place.id,label="Official site"))
            assertEquals(300,store.sources(area).size)
            val linked=store.sources(area).single {it.url==url}
            assertEquals(place.id,linked.businessId);assertEquals(123456L,linked.nextCheckAt);assertEquals(2,linked.failures)
        }}finally {store.close();ui.activity.deleteDatabase(name)}
    }
    @Test fun socialConnectionsBecomeAvailableWhileSlowSupplementalPagesAreStillPending() {
        val gate=CompletableDeferred<Unit>();val started=java.util.concurrent.atomic.AtomicInteger()
        val value=place.copy(socials=listOf("https://www.facebook.com/library_fixture"))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(value))) {
            override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit){started.incrementAndGet();gate.await()}
            override suspend fun refine(query:LocalDiscoveryQuery,business:LocalBusiness):List<LocalWebHit>{started.incrementAndGet();gate.await();return emptyList()}
        };model.localSearch.text("Community Library");model.localSearch.search(force=true)}
        try {
            ui.waitUntil(8000){started.get()==2&&model.localSearch.state.value.businesses.any {it.id==value.id&&it.socials.isNotEmpty()}}
            assertTrue(model.localSearch.state.value.enriching)
            show();ui.onNodeWithContentDescription("Open Facebook account").assertIsDisplayed();evidence("social-while-enriching")
        }finally {gate.complete(Unit);ui.runOnIdle {model.localSearch.stop()}}
    }
    @Test fun openEventDetailsFollowTheFreshestRecordWithoutReopeningTheSheet() {
        val at=System.currentTimeMillis()
        val event=LocalEvent("fixture-live-event","Neighborhood workshop",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),venue="Community Library",locality="Melbourne",price="$12",sourceIds=listOf("fixture-occurrence"),checkedAt=at)
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(events=listOf(event)),area)}
        ui.runOnIdle {model.localSearch.mode(LocalSearchMode.EVENTS)};show()
        ui.waitUntil {model.localSearch.state.value.events.any {it.id==event.id}}
        ui.onNodeWithText(event.title).performClick();ui.onNodeWithText("$12").assertIsDisplayed()
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(events=listOf(event.copy(price="$8",status="rescheduled",modifiedAt=at+1000,checkedAt=at+1000))),area)}
        ui.waitUntil {ui.onAllNodesWithText("$8").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("$8").assertIsDisplayed();ui.onNodeWithText("rescheduled").assertIsDisplayed();evidence("updated-event-details")
    }
    @Test fun aFastPublisherCardDoesNotWaitForUnrelatedSlowDiscoveryLanes() {
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=emptyList<LocalArea>()
            override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {delay(5000);return LocalExtraction()}
            override suspend fun identities(point:LocalPoint):LocalExtraction {delay(5000);return LocalExtraction()}
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend (List<LocalWebHit>)->Unit){onHits(listOf(LocalWebHit("https://fast.fixture.invalid/","Fast library")));delay(20_000)}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction(businesses=listOf(place))
            override suspend fun account(url:String)=true
        };model.localSearch.search(force=true)}
        try {ui.waitUntil(5000){model.localSearch.state.value.businesses.any {it.id==place.id}&&!model.localSearch.state.value.loading&&model.localSearch.state.value.enriching}}
        catch(e:Exception){throw AssertionError("Publisher delivery: "+model.localSearch.state.value.let {"loading=${it.loading}; names=${it.businesses.map {b->b.name}}; coverage=${it.coverage}"},e)}
        ui.runOnIdle {model.localSearch.stop()}
    }
    @Test fun readyCardQuietlyReceivesVisitDetailsAndItsSavedCopyRetainsThem() {
        val deliver=CompletableDeferred<Unit>()
        val menu="https://library.fixture.invalid/menu"
        val enriched=place.copy(phones=listOf("3212598005"),checkedAt=place.checkedAt+1000,evidence=listOf(LocalDiscoveryIdentity.evidence("menu",menu,place.websites.first(),place.checkedAt+1000,LocalEvidenceKind.PUBLISHER)))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(place))) {
            override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit){deliver.await();onData(LocalExtraction(businesses=listOf(enriched)))}
        };model.localSearch.search(force=true)}
        show();ui.waitUntil(10_000){model.localSearch.state.value.businesses.any {it.id==place.id}&&!model.localSearch.state.value.loading&&model.localSearch.state.value.enriching}
        ui.onNodeWithText("Finding public information…").assertDoesNotExist()
        ui.onNodeWithTag("Business ${place.id}").performClick();ui.onNodeWithText("Menu").assertDoesNotExist()
        ui.runOnIdle {deliver.complete(Unit)}
        ui.waitUntil(10_000){model.localSearch.state.value.businesses.any {LocalBusinessDetails.facts(it).any {f->f.value==menu}}&&!model.localSearch.state.value.enriching}
        ui.onNodeWithText("Menu").performScrollTo().assertIsDisplayed();evidence("enriched-details")
        ui.onNodeWithText("Save local copy").performScrollTo().performClick()
        ui.waitUntil {place.id in model.localSearch.state.value.saved}
        runBlocking(Dispatchers.IO){assertTrue(repo.localDiscovery.businesses(null,true).first {it.id==place.id}.evidence.any {it.field=="menu"&&it.value==menu})}
    }
    @Test fun laterCloserBusinessesAppendWithoutMovingTheCardAlreadyBeingRead() {
        val deliver=CompletableDeferred<Unit>()
        val first=place.copy(point=LocalPoint(-37.83,144.96))
        val later=place.copy(id="fixture-near",name="Neighbourhood Library",address="20 Main St, Melbourne",sourceIds=listOf("fixture-near"))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(first))) {
            override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit){deliver.await();onData(LocalExtraction(businesses=listOf(later)))}
        };model.localSearch.search(force=true)}
        ui.waitUntil(10_000){model.localSearch.state.value.businesses.isNotEmpty()&&!model.localSearch.state.value.loading&&model.localSearch.state.value.enriching}
        ui.runOnIdle {deliver.complete(Unit)}
        try {ui.waitUntil(10_000){model.localSearch.state.value.businesses.size==2&&!model.localSearch.state.value.enriching}}
        catch(e:Exception){throw AssertionError("Later delivery: foreground=${repo.gateway.hasForegroundRequests}; "+model.localSearch.state.value.let {"loading=${it.loading}; enriching=${it.enriching}; ids=${it.businesses.map {b->b.id}}; coverage=${it.coverage}"},e)}
        assertEquals(listOf(first.id,later.id),model.localSearch.state.value.businesses.map {it.id})
    }
    @Test fun changingTheQueryRejectsEvenALateUncooperativeEnrichmentCallback() {
        val started=CompletableDeferred<Unit>()
        val late=place.copy(id="fixture-late",name="Late unrelated result",address="99 Side St, Melbourne",point=LocalPoint(-37.84,144.97),sourceIds=listOf("fixture-late"))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(place))) {
            override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit){started.complete(Unit);withContext(NonCancellable){delay(700);onData(LocalExtraction(businesses=listOf(late)))}}
        };model.localSearch.search(force=true)}
        try {ui.waitUntil(10_000){started.isCompleted}}
        catch(e:Exception){throw AssertionError("Background startup: foreground=${repo.gateway.hasForegroundRequests}, state=${model.localSearch.state.value}",e)}
        ui.runOnIdle {model.localSearch.text("different query")}
        ui.waitUntil(3000){model.localSearch.state.value.businesses.isEmpty()&&!model.localSearch.state.value.enriching}
        runBlocking {delay(900)}
        runBlocking(Dispatchers.IO){assertNull(repo.localDiscovery.business(late.id))}
    }
    @Test fun aWiderCalendarWindowRefetchesAHealthyRangeLimitedSource() {
        val calendar=LocalPublisherSource("https://calendar.fixture.invalid/api/2/events",LocalSourceKind.LOCALIST,label="Library events")
        val count=java.util.concurrent.atomic.AtomicInteger()
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(sources=listOf(calendar))) {
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                count.incrementAndGet();return LocalExtraction(validEmpty=true)
            }
        };model.localSearch.mode(LocalSearchMode.EVENTS);model.localSearch.window(LocalEventWindow.WEEK);model.localSearch.search()}
        ui.waitUntil(10_000){count.get()>0&&!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        val before=count.get()
        ui.runOnIdle {model.localSearch.window(LocalEventWindow.MONTH);model.localSearch.search()}
        ui.waitUntil(10_000){count.get()>before&&!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        assertTrue(model.localSearch.state.value.coverage.none {it.contains("unavailable")||it.contains("No readable")})
    }
    @Test fun regionalEventsArriveBeforeSlowDiscoveryAndForeignRoutesAreNeverRead() {
        val local=LocalArea("fixture-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val session=LocalEvent("fixture-regional-event","Nearby repair workshop",LocalEventTime(local.today().plusDays(1).toString(),allDay=true),venue="Community workshop",locality="Satellite Beach",point=LocalPoint(28.175,-80.602),sourceIds=listOf("fixture-regional-event"))
        val seen=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        runBlocking(Dispatchers.IO){repo.localDiscovery.offerSource(local,LocalPublisherSource("https://api.hel.fi/linkedevents/v1/event/",LocalSourceKind.LINKED_EVENTS))}
        ui.runOnIdle {model.localSearch.area(local);model.localSearch.mode(LocalSearchMode.EVENTS);model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=emptyList<LocalArea>()
            override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {delay(5000);return LocalExtraction()}
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit> {delay(5000);return emptyList()}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                seen.add(source.url)
                return LocalExtraction(events=if(source.url.contains("fun4spacecoastkids"))listOf(session,session.copy(id="fixture-distant",point=LocalPoint(60.17,24.94),sourceIds=listOf("fixture-distant")))else emptyList(),validEmpty=true)
            }
        };model.localSearch.search(force=true)}
        show();ui.waitUntil(3000){model.localSearch.state.value.events.any {it.id==session.id}&&!model.localSearch.state.value.loading&&model.localSearch.state.value.enriching}
        ui.onNodeWithText(session.title).assertIsDisplayed();evidence("regional-events")
        assertFalse(seen.any {it.contains("api.hel.fi")})
        runBlocking(Dispatchers.IO){assertTrue(repo.localDiscovery.events().none {it.id=="fixture-distant"})}
        ui.runOnIdle {model.localSearch.stop()}
    }
    @Test fun neighboringTownAddressesUseAtMostTwoVenueLookupsPerSearch() {
        val local=LocalArea("fixture-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val count=java.util.concurrent.atomic.AtomicInteger()
        val rows=(0..3).map {i->LocalEvent("fixture-venue-$i","Workshop $i",LocalEventTime(local.today().plusDays(1).toString(),allDay=true),venue="Workshop",address="${750+i} Jamaica Blvd, Satellite Beach, FL 32937",sourceIds=listOf("fixture-venue-$i"))}
        ui.runOnIdle {model.localSearch.area(local);model.localSearch.mode(LocalSearchMode.EVENTS);model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=emptyList<LocalArea>()
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction()
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction(events=if(source.url.contains("fun4spacecoastkids"))rows else emptyList(),validEmpty=true)
            override suspend fun venue(query:LocalDiscoveryQuery,event:LocalEvent):LocalEvent {count.incrementAndGet();return event.copy(point=LocalPoint(28.175,-80.602),locality="Satellite Beach")}
        };model.localSearch.search(force=true)}
        ui.waitUntil(10000){!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
        assertEquals(2,count.get());assertEquals(2,model.localSearch.state.value.events.size)
    }
    @Test fun geographicAdmissionKeepsDistantEventsOutButRetainsAKnownEventsMoveAndSavedCopy():Unit=runBlocking {
        val local=LocalArea("fixture-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        val name="opportunity-geo-${System.nanoTime()}.db";val store=LocalDiscoveryStore(ui.activity,databaseName=name)
        val session=LocalEvent("fixture-known","Workshop",LocalEventTime(local.today().plusDays(1).toString(),allDay=true),point=local.point,sourceIds=listOf("fixture-known"),checkedAt=1000)
        try {withContext(Dispatchers.IO) {
            val far=session.copy(id="fixture-far",point=LocalPoint(60.17,24.94),sourceIds=listOf("fixture-far"))
            val foreign=session.copy(id="fixture-foreign",point=null,locality="Melbourne",address="Melbourne, Victoria, Australia",sourceIds=listOf("fixture-foreign"))
            assertEquals(1,store.ingest(LocalExtraction(events=listOf(session,far,foreign)),local).second)
            store.save(session)
            store.ingest(LocalExtraction(events=listOf(session.copy(point=far.point,checkedAt=2000,modifiedAt=2000))),local)
            assertFalse(LocalDiscoveryFilter.geographic(local,store.events().single()))
            assertEquals(session,store.events(true).single())
        }}finally {store.close();ui.activity.deleteDatabase(name)}
    }
    @Test fun cuisineIntentFindsUnnamedRestaurantsAndExactNamesStillWorkFromTheSameCache() {
        val indian=LocalBusiness("fixture-indian","Saffron Palace","restaurant",address="10 Main Street",locality=area.name,point=area.point,keywords=listOf("cuisine","indian"))
        val italian=indian.copy(id="fixture-italian",name="Garden Table",keywords=listOf("cuisine","italian"))
        val far=indian.copy(id="fixture-distant-indian",name="Distant Curry",point=LocalPoint(0.0,0.0))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(indian,italian,far))
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction(validEmpty=true)
        };model.localSearch.text("indian food");model.localSearch.search(force=true)}
        show();ui.waitUntil(10_000){model.localSearch.state.value.businesses.map {it.id}==listOf(indian.id)}
        ui.onNodeWithTag("Business ${indian.id}").assertIsDisplayed();evidence("indian-food-concept")
        ui.runOnIdle {model.localSearch.text("indain resturants")}
        ui.waitUntil(5000){model.localSearch.state.value.businesses.map {it.id}==listOf(indian.id)}
        ui.runOnIdle {model.localSearch.text("\"Saffron Palace\"")}
        ui.waitUntil(5000){model.localSearch.state.value.businesses.map {it.id}==listOf(indian.id)}
        ui.onNodeWithTag("Business ${indian.id}").assertIsDisplayed()
    }

    @Test fun detailEdgeDragsKeepTheBusinessAndEventReadingViewportFixed() {
        val long=place.copy(description=(1..90).joinToString("\n"){"Useful visiting detail number $it for this location."})
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(long)),area)}
        show();ui.waitUntil {model.localSearch.state.value.businesses.any {it.id==long.id}}
        ui.onNodeWithTag("Business ${long.id}").performClick()
        fun edge(tag:String,dy:Float) {
            ui.waitForIdle();val node=ui.onNodeWithTag(tag);val top=node.fetchSemanticsNode().boundsInRoot.top
            try {
                node.performTouchInput {down(center);moveBy(Offset(0f,dy),100)}
                ui.waitForIdle()
                assertEquals("An edge drag must not also move the reading sheet",top,node.fetchSemanticsNode().boundsInRoot.top,2f)
            }finally {node.performTouchInput {up()}}
        }
        edge("Business details",180f)
        ui.onNodeWithTag("Business details").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100_000f)}
        edge("Business details",-180f);evidence("business-fixed-edge-scroll")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitUntil {ui.onAllNodesWithTag("Business details").fetchSemanticsNodes().isEmpty()}
        val event=LocalEvent("fixture-fixed-event","A long public workshop",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),description=long.description,locality=area.name,point=area.point)
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(events=listOf(event)),area)}
        ui.runOnIdle {model.localSearch.mode(LocalSearchMode.EVENTS)}
        ui.waitUntil {model.localSearch.state.value.events.any {it.id==event.id}}
        ui.onNodeWithTag("Event ${event.id}").performClick();edge("Event details",180f)
        ui.onNodeWithTag("Event details").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,100_000f)}
        edge("Event details",-180f);evidence("event-fixed-edge-scroll")
        ui.onNodeWithContentDescription("Close details").performClick()
        ui.waitUntil {ui.onAllNodesWithTag("Event details").fetchSemanticsNodes().isEmpty()}
    }

    @Test fun detailWorkStartsBeforeSlowDiscoveryAndBothViewsReportCompletion() {
        val slow=CompletableDeferred<Unit>();val account=CompletableDeferred<Unit>();val finishAccount=CompletableDeferred<Unit>()
        val value=place.copy(websites=emptyList(),socials=listOf(place.socials.first()))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {slow.await();return LocalExtraction()}
            override suspend fun identities(point:LocalPoint)=LocalExtraction(businesses=listOf(value))
            override suspend fun discover(query:LocalDiscoveryQuery):List<LocalWebHit>{slow.await();return emptyList()}
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                if(source.kind!=LocalSourceKind.SOCIAL)return LocalExtraction(validEmpty=true)
                account.complete(Unit);finishAccount.await()
                return LocalExtraction(businesses=listOf(value.copy(hours="Monday 9am - 5pm")))
            }
        };model.localSearch.search(force=true)}
        try {
            ui.waitUntil(5000){account.isCompleted&&model.localSearch.state.value.businesses.any {it.id==value.id}}
            show();ui.onNodeWithTag("Business ${value.id}").performClick()
            ui.onNode(hasTestTag("Card activity ${value.id}") and hasAnyAncestor(isDialog())).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Updating"))
            finishAccount.complete(Unit)
            ui.waitUntil(5000){ui.onAllNodesWithText("Monday 9am - 5pm").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(model.localSearch.state.value.enriching);assertFalse(slow.isCompleted)
            ui.waitUntil(5000){repo.cardWork.active.value.none {businessWorkKey(value.id) in it.keys}}
            ui.onNode(hasTestTag("Card activity ${value.id}") and hasAnyAncestor(isDialog())).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Searching"))
            evidence("business-details-pending-search")
            slow.complete(Unit)
            ui.waitUntil(10_000){!model.localSearch.state.value.loading&&!model.localSearch.state.value.enriching}
            ui.onNode(hasTestTag("Card activity ${value.id}") and hasAnyAncestor(isDialog())).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Ready"))
            ui.onNodeWithContentDescription("Close details").performClick()
            ui.waitUntil {ui.onAllNodesWithTag("Business details").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithText("Search complete").assertIsDisplayed();evidence("local-search-complete")
        }finally {slow.complete(Unit);finishAccount.complete(Unit);ui.runOnIdle {model.localSearch.stop()}}
    }

    @Test fun aLightContactLookupStreamsWhileTheLargeBrandDatasetIsStillRunning() {
        val large=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val contact=place.websites.first()+"contact"
        ui.runOnIdle {model.localSearch.text("Community Library");model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(businesses=listOf(place))) {
            override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit){started.complete(Unit);large.await()}
            override suspend fun refine(query:LocalDiscoveryQuery,business:LocalBusiness)=listOf(LocalWebHit(contact,"Library contact"))
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=if(source.url==contact)LocalExtraction(businesses=listOf(place.copy(hours="Tuesday 8am - 4pm",checkedAt=place.checkedAt+1000)))else LocalExtraction(validEmpty=true)
        };model.localSearch.search(force=true)}
        try {
            ui.waitUntil(8000){started.isCompleted&&model.localSearch.state.value.businesses.any {it.hours=="Tuesday 8am - 4pm"}}
            assertFalse(large.isCompleted);assertTrue(model.localSearch.state.value.enriching)
        }finally {large.complete(Unit);ui.runOnIdle {model.localSearch.stop()}}
    }

    @Test fun chainLocationsShareOneCardAndKeepTheirOwnContactDetails() {
        ui.runOnIdle {model.localSearch.backend=fixture(LocalExtraction(validEmpty=true))}
        val first=place.copy(id="fixture-chain-one",name="Northstar Coffee - Downtown",address="10 Main St, Melbourne",websites=listOf("https://northstarcoffee.fixture.invalid/locations/downtown"),socials=emptyList(),phones=listOf("+13215550101"),sourceIds=listOf("fixture-chain-one"))
        val second=first.copy(id="fixture-chain-two",name="Northstar Coffee - Riverside",address="20 River Rd, Melbourne",phones=listOf("+13215550102"),sourceIds=listOf("fixture-chain-two"),point=LocalPoint(area.point.latitude+.002,area.point.longitude))
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(first,second)),area)}
        show();ui.waitUntil {model.localSearch.state.value.businesses.size==2}
        ui.onNodeWithText("2 nearby locations").assertIsDisplayed()
        assertEquals(1,model.localSearch.state.value.groups.size)
        val group=model.localSearch.state.value.groups.single();ui.onNodeWithTag("Business ${group.primary.id}").performClick()
        ui.onNodeWithTag("Choose business location").performClick();ui.onNodeWithTag("Business location ${second.id}").performClick()
        ui.onNodeWithText(second.address).assertIsDisplayed()
        ui.onNodeWithText(LocalBusinessContact.displayPhone(second.phones.single(),area.countryCode)).assertIsDisplayed()
        ui.onNodeWithText(first.address).assertDoesNotExist();evidence("chain-selected-branch")
        ui.onNodeWithContentDescription("Close details").performClick()
        ui.waitUntil {ui.onAllNodesWithTag("Business details").fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithContentDescription("Save ${group.title}").performClick()
        ui.waitUntil {setOf(first.id,second.id).all {it in model.localSearch.state.value.saved}}
        runBlocking(Dispatchers.IO){assertEquals(setOf(first.phones,second.phones),repo.localDiscovery.businesses(null,true).filter {it.id in setOf(first.id,second.id)}.map {it.phones}.toSet())}
    }

    @Test fun announcementActionsAndEvidenceDeduplicateAndDeadLinksStayOutOfTheButtons() {
        val previous=repo.localLinks.probe;val calls=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()
        val live="https://event.fixture.invalid/workshop";val other="https://calendar.fixture.invalid/workshop";val dead="https://old.fixture.invalid/gone"
        val event=LocalEvent("fixture-link-event","Radio repair workshop",LocalEventTime(area.today().plusDays(1).toString(),allDay=true),locality=area.name,point=area.point,
            urls=listOf(dead,live+"?utm_source=social",live,other),registrationUrl=live,evidence=listOf(
                LocalFieldEvidence("description","Bring a broken radio",live+"?utm_source=social",100,LocalEvidenceKind.PUBLIC_POST,"Bring a broken\nradio"),
                LocalFieldEvidence("description","Bring a broken radio",live,200,LocalEvidenceKind.PUBLIC_POST,"Bring a broken radio"),
                LocalFieldEvidence("description","Bring a broken radio",other,200,LocalEvidenceKind.PUBLIC_POST,"Bring a broken radio"),
                LocalFieldEvidence("title","Radio repair workshop",dead,100,LocalEvidenceKind.PUBLISHER)))
        repo.localLinks.probe={url,_->calls.computeIfAbsent(url){java.util.concurrent.atomic.AtomicInteger()}.incrementAndGet();LocalLinkCheck(url,if(url==dead)LocalLinkState.UNAVAILABLE else LocalLinkState.AVAILABLE,checkedAt=System.currentTimeMillis())}
        try {
            runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(events=listOf(event)),area)}
            ui.runOnIdle {model.localSearch.mode(LocalSearchMode.EVENTS)};show()
            ui.waitUntil {model.localSearch.state.value.events.any {it.id==event.id}};ui.onNodeWithTag("Event ${event.id}").performClick()
            ui.waitUntil(8000){listOf(live,other,dead).all {repo.localLinks.check(it,event)?.state in setOf(LocalLinkState.AVAILABLE,LocalLinkState.UNAVAILABLE)}}
            assertEquals("Every unique link must reach the checker",setOf(live,other,dead),calls.keys)
            assertEquals(LocalLinkState.AVAILABLE,repo.localLinks.check(live,event)?.state)
            assertEquals(LocalLinkState.AVAILABLE,repo.localLinks.check(other,event)?.state)
            assertEquals(LocalLinkState.UNAVAILABLE,repo.localLinks.check(dead,event)?.state)
            evidence("event-announcements-before-assert")
            ui.onNodeWithTag("Announcement $live").assertIsDisplayed();ui.onNodeWithTag("Announcement $dead").assertDoesNotExist()
            ui.onNodeWithTag("Announcement $other").assertDoesNotExist();ui.onNodeWithText("1 more announcement").performClick()
            ui.onNodeWithTag("Announcement $other").assertIsDisplayed();ui.onNodeWithText("Read announcement").assertDoesNotExist()
            ui.onNodeWithText("Where this information came from").performScrollTo().performClick()
            ui.onAllNodesWithTag("Discovery source $live").assertCountEquals(1)
            ui.onAllNodesWithText("Bring a broken radio").assertCountEquals(1)
            assertEquals(3,calls.size);assertTrue(calls.values.all {it.get()==1})
            runBlocking(Dispatchers.IO){assertTrue(repo.localDiscovery.events().single {it.id==event.id}.urls.contains(dead))}
            evidence("event-clean-announcements")
            ui.onNodeWithContentDescription("Close details").performClick();ui.waitUntil {ui.onAllNodesWithTag("Event details").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithTag("Event ${event.id}").performClick();ui.waitForIdle();assertTrue(calls.values.all {it.get()==1})
        }finally {repo.localLinks.probe=previous}
    }

    @Test fun firstBusinessAndEventCardsCanPassTwoStalledPublisherReads() {
        for(mode in listOf(LocalSearchMode.BUSINESSES,LocalSearchMode.EVENTS)) {
            val waiting=java.util.concurrent.atomic.AtomicInteger()
            val bothStarted=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val owner=place.copy(id="fixture-rescue-business",name="Community workshop",address="1 Example Street",locality=firstDeckArea.name,point=firstDeckArea.point,phones=emptyList(),socials=emptyList(),websites=emptyList(),sourceIds=listOf("fixture-rescue-business"))
            val session=LocalEvent("fixture-rescue-event","Radio workshop",LocalEventTime(firstDeckArea.today().plusDays(1).toString(),allDay=true),venue=owner.name,locality=firstDeckArea.name,point=firstDeckArea.point,sourceIds=listOf("fixture-rescue-event"))
            try {
                runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
                ui.runOnIdle {
                    model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(validEmpty=true)) {
                        override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend(List<LocalWebHit>)->Unit)=coroutineScope {
                            val early=launch {onHits(listOf(LocalWebHit("https://slow-one.fixture.invalid/","Slow publisher one"),LocalWebHit("https://slow-two.fixture.invalid/","Slow publisher two")))}
                            withTimeout(5000){bothStarted.await()}
                            onHits(listOf(LocalWebHit("https://quick.fixture.invalid/","Community workshop")))
                            early.join()
                        }
                        override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                            if(source.url.startsWith("https://slow-")){if(waiting.incrementAndGet()==2)bothStarted.complete(Unit);release.await();return LocalExtraction(validEmpty=true)}
                            if(source.url=="https://quick.fixture.invalid/")return if(mode==LocalSearchMode.BUSINESSES)LocalExtraction(businesses=listOf(owner))else LocalExtraction(events=listOf(session))
                            return LocalExtraction(validEmpty=true)
                        }
                    }
                    model.localSearch.mode(mode);model.localSearch.area(firstDeckArea);model.localSearch.text("workshop");model.localSearch.search(force=true)
                }
                ui.waitUntil(6000){if(mode==LocalSearchMode.BUSINESSES)model.localSearch.state.value.businesses.any {it.id==owner.id}else model.localSearch.state.value.events.any {it.id==session.id}}
                assertTrue(bothStarted.isCompleted);assertFalse("Fast cards must arrive while both slow publishers are still held",release.isCompleted)
                if(mode==LocalSearchMode.BUSINESSES)ui.onNodeWithTag("Business ${owner.id}").assertIsDisplayed()else ui.onNodeWithTag("Event ${session.id}").assertIsDisplayed()
                evidence("first-${mode.name.lowercase()}-card-rescue")
            }finally {ui.runOnIdle {model.localSearch.stop()};release.complete(Unit);runBlocking(Dispatchers.IO){repo.localDiscovery.writableDatabase.delete("sources","area=?",arrayOf(firstDeckArea.cacheKey))}}
        }
    }

    @Test fun firstCardsCanUseFallbackPublishersBeyondTheFirstBatch() {
        for(mode in listOf(LocalSearchMode.BUSINESSES,LocalSearchMode.EVENTS))for(streamed in listOf(false,true)) {
            val calls=java.util.concurrent.atomic.AtomicInteger()
            val owner=place.copy(id="fixture-fallback-business",name="Community workshop",address="1 Example Street",locality=firstDeckArea.name,point=firstDeckArea.point,phones=emptyList(),socials=emptyList(),websites=emptyList(),sourceIds=listOf("fixture-fallback-business"))
            val session=LocalEvent("fixture-fallback-event","Radio workshop",LocalEventTime(firstDeckArea.today().plusDays(1).toString(),allDay=true),venue=owner.name,locality=firstDeckArea.name,point=firstDeckArea.point,sourceIds=listOf("fixture-fallback-event"))
            runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
            ui.runOnIdle {
                model.localSearch.backend=object:LocalDiscoveryBackend by fixture(LocalExtraction(validEmpty=true)) {
                    override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend(List<LocalWebHit>)->Unit) {
                        val batches=if(streamed)listOf((1..6).toList(),listOf(7,8),listOf(9))else listOf((1..9).toList())
                        for(batch in batches)onHits(batch.map {LocalWebHit("https://publisher-$it.fixture.invalid/","Publisher $it")})
                    }
                    override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                        if(!source.url.contains("publisher-"))return LocalExtraction(validEmpty=true)
                        calls.incrementAndGet()
                        return if(source.url=="https://publisher-9.fixture.invalid/") {
                            if(mode==LocalSearchMode.BUSINESSES)LocalExtraction(businesses=listOf(owner))else LocalExtraction(events=listOf(session))
                        }else LocalExtraction(validEmpty=true)
                    }
                }
                model.localSearch.mode(mode);model.localSearch.area(firstDeckArea);model.localSearch.text("workshop");model.localSearch.search(force=true)
            }
            try {
                ui.waitUntil(8000){if(mode==LocalSearchMode.BUSINESSES)model.localSearch.state.value.businesses.any {it.id==owner.id}else model.localSearch.state.value.events.any {it.id==session.id}}
                if(streamed)assertEquals(9,calls.get())else assertTrue(calls.get() in 7..9)
            }finally {ui.runOnIdle {model.localSearch.stop()};runBlocking(Dispatchers.IO){repo.localDiscovery.writableDatabase.delete("sources","area=?",arrayOf(firstDeckArea.cacheKey))}}
        }
    }

    @Test fun opportunityMigrationRequeuesSuccessfulPagesButPreservesFailureRestsAndSavedSnapshots():Unit=runBlocking {
        val name="opportunity-upgrade-${System.nanoTime()}.db"
        val healthy=LocalPublisherSource("https://library.fixture.invalid/calendar",nextCheckAt=System.currentTimeMillis()+86400000)
        val failed=healthy.copy(url="https://library.fixture.invalid/offline",failures=3)
        try {withContext(Dispatchers.IO) {
            LocalDiscoveryStore(ui.activity,databaseName=name).use {store->store.save(place);store.source(area,healthy);store.source(area,failed);store.cache("local-account:v2:old-success","usable",86400000);store.cache("local-account:v2:old-failure","unavailable",86400000);store.writableDatabase.version=8}
            LocalDiscoveryStore(ui.activity,databaseName=name).use {store->
                assertEquals(9,store.readableDatabase.version)
                assertEquals("usable",store.cache("local-account:v2:old-success"));assertEquals("unavailable",store.cache("local-account:v2:old-failure"))
                assertEquals(0L,store.sources(area).single {it.url==healthy.url}.nextCheckAt)
                assertEquals(failed,store.sources(area).single {it.url==failed.url})
                assertEquals(place,store.businesses(null,true).single())
            }
        }}finally {ui.activity.deleteDatabase(name)}
    }
}
