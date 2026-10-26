package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestaurantMenuTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val model get()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private val area=LocalArea("fixture-menu-area","Sampleton","Sampleton, Florida, United States",LocalPoint(28.612218,-80.807555),25,"America/New_York",countryCode="US")
    private val at=System.currentTimeMillis()
    private val place=LocalBusiness("fixture-menu-cafe","Example Coffee","CafeOrCoffeeShop",address="12 Main St, Sampleton, FL 00001",locality="Sampleton",point=area.point,websites=listOf("https://examplecoffee.fixture.invalid/"),checkedAt=at)
    private fun menu(channel:String="Pickup")=LocalMenus.document("Coffee and food", "https://examplecoffee.fixture.invalid/menu",at,place,"Menu","a".repeat(64),false)!!.copy(channel=channel,text="",items=listOf(
        LocalMenuItem("Mushroom sandwich","Lunch","Roasted mushrooms",listOf(LocalMenuPrice("12.50","USD")),listOf("Vegetarian"),listOf("wheat")),
        LocalMenuItem("Espresso","Drinks",prices=listOf(LocalMenuPrice("3.00","USD")))))
    private lateinit var prefs:String
    private lateinit var backend:LocalDiscoveryBackend
    @Before fun setup() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()};prefs=repo.prefs.export().toString();backend=model.localSearch.backend
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(true)}
        ui.runOnIdle {repo.prefs.intervalMinutes=0;model.section("discover");model.localSearch.mode(LocalSearchMode.BUSINESSES);model.localSearch.area(area);model.localSearch.text("");model.localSearch.saved(false)}
    }
    @After fun cleanup() {
        if(!::prefs.isInitialized)return
        ui.runOnIdle {model.localSearch.stop();model.localSearch.backend=backend;ui.activity.setContent {MaterialTheme {androidx.compose.material3.Text("Menu checks complete")}}}
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(true);repo.localDiscovery.removeSaved(place.id)}
        ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().commit();repo.prefs.restore(JSONObject(prefs));repo.applyNetworkSettings()}
    }
    private fun screenshot(name:String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val root=File(ui.activity.getExternalFilesDir("validation"),"restaurant-menus").apply {mkdirs()}
        File(root,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun nativeMenuSearchKeepsChannelsDistinctAndReadsSavedTextOffline() {
        val value=place.copy(menus=listOf(menu(),menu("Delivery").copy(title="Delivery",items=listOf(LocalMenuItem("Mushroom sandwich","Lunch",prices=listOf(LocalMenuPrice("15.00","USD")))))))
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(value)),area);repo.localDiscovery.save(value)}
        ui.runOnIdle {model.localSearch.saved(true)}
        ui.waitUntil(8000){model.localSearch.state.value.businesses.any {it.id==place.id}}
        ui.onNodeWithTag("Business ${place.id}").performClick();ui.onNodeWithTag("Read restaurant menu").performClick()
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Mushroom sandwich"));ui.onNodeWithText("Mushroom sandwich").assertIsDisplayed();ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("USD 12.50"));ui.onNodeWithText("USD 12.50").assertIsDisplayed();ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Listed allergens: wheat",substring=true));ui.onNodeWithText("Listed allergens: wheat",substring=true).assertIsDisplayed();screenshot("pickup")
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Delivery"));ui.onNodeWithText("Delivery",useUnmergedTree=true).performClick();ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("USD 15.00"));ui.onNodeWithText("USD 15.00").assertIsDisplayed();ui.onAllNodesWithText("USD 12.50").assertCountEquals(0);screenshot("delivery")
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Pickup · Menu"));ui.onNodeWithText("Pickup · Menu").performClick();ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasTestTag("Find menu dish"));ui.onNodeWithTag("Find menu dish").performTextInput("espresso")
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Espresso"))
        ui.onNodeWithText("Espresso").assertIsDisplayed();ui.onAllNodesWithText("Mushroom sandwich").assertCountEquals(0);screenshot("search")
    }
    @Test fun savedMenuSurvivesOrdinaryExpiryAndPortableRoundTripRejectsAnUnsafeSource()=runBlocking {
        var now=at;val name="menu-fixture-${System.nanoTime()}.db";val store=LocalDiscoveryStore(ui.activity,{now},name)
        try {withContext(Dispatchers.IO) {
            val value=place.copy(menus=listOf(menu()))
            store.ingest(LocalExtraction(businesses=listOf(value)),area);store.save(value)
            now+=100L*86400000;store.trim();assertTrue(store.businesses(area).isEmpty())
            val exported=LocalDiscoveryPortability.encode(store);val imported=LocalDiscoveryPortability.decode(exported)
            assertEquals("Mushroom sandwich",imported.businesses.single().menus!!.single().items.first().name)
            exported["businesses"].asJsonArray[0].asJsonObject["menus"].asJsonArray[0].asJsonObject.addProperty("sourceUrl","javascript:alert(1)")
            try {LocalDiscoveryPortability.decode(exported);fail("Executable menu source accepted")}catch(_:IllegalArgumentException){}
        }}finally {store.close();ui.activity.deleteDatabase(name)}
    }
    @Test fun firstCardDoesNotWaitForMenuAndASecondOpenReusesSuccessfulRoutes() {
        var reads=0
        val complete=place.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("menu",menu().sourceUrl,place.websites.single(),at,LocalEvidenceKind.PUBLISHER)))
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(businesses=listOf(complete))
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                if(source.kind==LocalSourceKind.MENU){reads++;delay(1000);return LocalExtraction(businesses=listOf(complete.copy(menus=listOf(menu()))))}
                return LocalExtraction(businesses=listOf(complete))
            }
        };model.localSearch.search()}
        ui.waitUntil(8000){model.localSearch.state.value.businesses.any {it.id==place.id}&&!model.localSearch.state.value.loading}
        ui.onNodeWithTag("Business ${place.id}").performClick()
        ui.waitUntil(15000){model.localSearch.state.value.businesses.any {it.menus.orEmpty().isNotEmpty()}}
        // This verifies menu completion and route reuse. Other business fields
        // can keep enriching behind the already delivered card independently.
        ui.waitUntil(10000){model.localSearch.state.value.menuLoading==null}
        val before=reads
        ui.runOnIdle {model.localSearch.menu(model.localSearch.state.value.businesses.first())}
        ui.waitUntil(5000){model.localSearch.state.value.menuLoading==null};assertEquals(before,reads)
    }
    @Test fun finishingSearchCannotRestoreAConcurrentlyCompletedMenuStatus() {
        val controller=model.localSearch;val original=controller.state
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val collided=java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeMenuBeforeWrite(next:LocalDiscoveryState) {
            if(next.phase==LocalSearchPhase.COMPLETE&&next.menuLoading!=null&&collided.compareAndSet(false,true)) {
                // Force the independent menu completion between a search's
                // state read and write. An atomic update must retry the copy.
                original.update {it.copy(menuLoading=null)}
            }
        }
        val racing=object:MutableStateFlow<LocalDiscoveryState> by original {
            override var value:LocalDiscoveryState
                get()=original.value
                set(next){completeMenuBeforeWrite(next);original.value=next}
            override fun compareAndSet(expect:LocalDiscoveryState,update:LocalDiscoveryState):Boolean {
                completeMenuBeforeWrite(update);return original.compareAndSet(expect,update)
            }
        }
        // A fixture-only flow wrapper injects the collision without a product
        // hook, network operation, extra wait or changed completion assertion.
        val stateField=controller.javaClass.getDeclaredField("state").apply {isAccessible=true}
        try {
            ui.runOnIdle {
                stateField.set(controller,racing)
                controller.backend=object:LocalDiscoveryBackend {
                    override suspend fun areas(text:String)=listOf(area)
                    override suspend fun directory(query:LocalDiscoveryQuery):LocalExtraction {started.complete(Unit);release.await();return LocalExtraction(validEmpty=true)}
                    override suspend fun identities(point:LocalPoint)=LocalExtraction()
                    override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
                    override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?)=LocalExtraction()
                }
                controller.search()
            }
            ui.waitUntil(5000){started.isCompleted}
            original.update {it.copy(menuLoading="fixture-completing-menu")};release.complete(Unit)
            ui.waitUntil(10000){original.value.phase==LocalSearchPhase.COMPLETE}
            assertTrue("The fixture must inject the concurrent completion",collided.get())
            assertNull("Search completion must preserve the menu's cleared status",original.value.menuLoading)
        }finally {release.complete(Unit);ui.runOnIdle {controller.stop();stateField.set(controller,original)}}
    }
    @Test fun ordinaryPdfTextNeedsNoModelOnAnySupportedAndroidVersion()=runBlocking {
        val file=File(ui.activity.cacheDir,"menu-text-fixture.pdf")
        val enabled=repo.ai.preferences.enabled
        try {
            repo.ai.preferences.enabled=false
            val pdf=android.graphics.pdf.PdfDocument()
            try {val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,800,1).create());page.canvas.drawText("Coffee 3.50 and sandwich 12.50",40f,80f,android.graphics.Paint().apply {textSize=20f});pdf.finishPage(page);file.outputStream().use {pdf.writeTo(it)}}finally {pdf.close()}
            val result=withTimeout(15_000){repo.mediaTools.pdfTextPage(file,0)}
            assertTrue(result.available);assertFalse(result.ocr);assertTrue(result.text.contains("sandwich 12.50"));assertFalse(repo.ai.preferences.enabled)
        }finally {file.delete();repo.ai.preferences.enabled=enabled}
    }
    @Test fun aMalformedPdfAndAbandonedDownloadCannotPersistAsAWorkingMenu()=runBlocking {
        val file=File(ui.activity.cacheDir,"menu-invalid-fixture.pdf").apply {writeText("Not a PDF")}
        try {try {withTimeout(15_000){repo.mediaTools.pdfTextPage(file,0)};fail("Malformed document accepted")}catch(_:IllegalStateException){}
            val dir=File(ui.activity.cacheDir,"restaurant-menus").apply {mkdirs()};val orphan=File(dir,"old-fixture.pdf").apply {writeText("old");setLastModified(System.currentTimeMillis()-7200000)}
            LocalMenuReader.cleanup(ui.activity.cacheDir);assertFalse(orphan.exists())
        }finally {file.delete()}
    }
    @Test fun imageOnlyPdfIsNotInventedAsTextOrSilentlySentToAModel()=runBlocking {
        val file=File(ui.activity.cacheDir,"menu-scanned-fixture.pdf");val enabled=repo.ai.preferences.enabled
        try {
            repo.ai.preferences.enabled=false
            val image=android.graphics.Bitmap.createBitmap(600,800,android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(image).apply {drawColor(android.graphics.Color.WHITE);drawText("Photo of a menu",40f,80f,android.graphics.Paint().apply {textSize=20f})}
            val pdf=android.graphics.pdf.PdfDocument()
            try {val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,800,1).create());page.canvas.drawBitmap(image,0f,0f,null);pdf.finishPage(page);file.outputStream().use {pdf.writeTo(it)}}finally {pdf.close();image.recycle()}
            val result=withTimeout(15000){repo.mediaTools.pdfTextPage(file,0)}
            assertTrue(result.text.isBlank());assertFalse(result.ocr);assertFalse(repo.ai.preferences.enabled)
        }finally {file.delete();repo.ai.preferences.enabled=enabled}
    }
    @Test fun anOldMenuCannotTurnAnEmptyContactPageIntoAHealthyNewMenu() {
        val owner=place.copy(menus=listOf(menu()))
        val source=LocalPublisherSource(menu().sourceUrl,LocalSourceKind.MENU,place.id,"Menu",discoveredFrom=place.websites.single(),menuBranch=true)
        val contact=Payload("<title>Example Coffee</title><a href='mailto:hi@examplecoffee.fixture.invalid'>Email</a>",source.url,"text/html",200)
        try {LocalMenuReader(repo).html(contact,source,LocalDiscoveryQuery(area),owner,at+1000);fail("Cached prices counted as newly readable menu content")}catch(e:SourceFailure){assertEquals(HealthStatus.EMPTY,e.status)}
        assertEquals("12.50",owner.menus!!.single().items.first().prices.single().amount)
        val public=contact.copy(text="<title>Menu</title><a>Login</a><input type='password'><form><div><h4>Tea</h4><b class='price'>$3.00</b></div></form>")
        val data=LocalMenuReader(repo).html(public,source,LocalDiscoveryQuery(area),owner,at+1000)
        assertTrue(data.businesses.flatMap {it.menus.orEmpty()}.flatMap {it.items}.any {it.name=="Tea"})
    }
    @Test fun unreadableOriginalDocumentsRemainAvailableBesideTheExtractedMenu() {
        val original="https://cdn.fixture.invalid/current-menu.pdf"
        val value=place.copy(menus=listOf(menu().copy(items=listOf(menu().items.last()))),evidence=listOf(LocalDiscoveryIdentity.evidence("menu",original,place.websites.single(),at,LocalEvidenceKind.PUBLISHER)))
        val opened=java.util.concurrent.atomic.AtomicReference<String>()
        ui.runOnIdle {ui.activity.setContent {MaterialTheme {RestaurantMenuSheet(value,false,{}, {}, {opened.set(it)})}}}
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Other published menus"));ui.onNodeWithText("Other published menus").performClick()
        ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Menu document 1"));ui.onNodeWithText("Menu document 1").performClick()
        assertEquals(original,opened.get());ui.onNodeWithTag("Restaurant menu").performScrollToNode(hasText("Espresso"));ui.onNodeWithText("Espresso").assertIsDisplayed()
    }
    @Test fun aCrowdedColdDiscoveryGraphStillReadsTheVerifiedFullMenuFirst() {
        val full="https://examplecoffee.fixture.invalid/coffee/menu/?lid=1"
        val directories=(1..10).map {n->LocalDiscoveryIdentity.evidence("branch menu","https://www.menupix.com/menudirectory/menu.php?id=${863660+n}","https://www.menupix.com/florida/restaurants/${863660+n}/Example-Coffee",at,LocalEvidenceKind.DIRECTORY)}
        val owner=place.copy(evidence=directories+LocalDiscoveryIdentity.evidence("branch menu",full,place.websites.single(),at,LocalEvidenceKind.DIRECTORY),menus=listOf(menu().copy(title="Menu highlights",partial=true)))
        val reads=java.util.Collections.synchronizedList(mutableListOf<String>())
        runBlocking(Dispatchers.IO){repo.localDiscovery.ingest(LocalExtraction(businesses=listOf(owner)),area)}
        ui.runOnIdle {model.localSearch.backend=object:LocalDiscoveryBackend {
            override suspend fun areas(text:String)=listOf(area)
            override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction()
            override suspend fun identities(point:LocalPoint)=LocalExtraction()
            override suspend fun discover(query:LocalDiscoveryQuery)=emptyList<LocalWebHit>()
            override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                reads+=source.url
                check(source.url==full){"Directory fallback ran ahead of the location's full menu"}
                return LocalExtraction(businesses=listOf(owner.copy(menus=listOf(menu().copy(sourceUrl=full,title="Full menu",partial=false)))))
            }
        };model.localSearch.menu(owner)}
        ui.waitUntil(10000){model.localSearch.state.value.menuLoading==null&&model.localSearch.state.value.businesses.any {it.menus.orEmpty().any {m->m.title=="Full menu"}}}
        assertEquals(full,reads.first())
    }
}
