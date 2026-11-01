/* anti social, GPL-3.0-or-later. Owned publisher responses through the production reader. */
package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PublisherDocumentTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private val model get()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private val area=LocalArea("fixture-document","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC",countryCode="ZZ")
    private val url="https://publisher.fixture.invalid/details"
    private val space="""{"api_compatibility":["15"],"space":"Community workshop","url":"https://publisher.fixture.invalid/","location":{"lat":10,"lon":20,"address":"1 Example Street, Example City","country_code":"ZZ","hint":"Use the side entrance."},"contact":{},"feeds":{"calendar":{"type":"ical","url":"https://publisher.fixture.invalid/calendar.ics"}}}"""
    private lateinit var preferences:String
    private lateinit var previous:LocalDiscoveryBackend
    private val reads=CopyOnWriteArrayList<Map<String,String>>()
    @Before fun isolate() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
        preferences=repo.prefs.export().toString();previous=model.localSearch.backend
        ui.runOnIdle {
            model.localSearch.stop();repo.prefs.intervalMinutes=0;repo.prefs.tor=false
            repo.localSearchConnection(false);repo.applyNetworkSettings();model.section("discover")
            model.localSearch.mode(LocalSearchMode.BUSINESSES);model.localSearch.area(area);model.localSearch.text("workshop");model.localSearch.saved(false)
        }
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
    }
    @After fun restore() {
        if(!::preferences.isInitialized)return
        ui.runOnIdle {model.localSearch.stop();model.localSearch.backend=previous}
        runBlocking(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
        ui.runOnIdle {repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings()}
    }
    /** Replace only our own cached client's final exchange. No public socket,
     * test trust root or weaker production URL/transport rule is introduced.
     * Gateway admission, bounded decoding and the actual backend still run. */
    private fun response(body:String,type:String,action:(List<String>)->Unit) {
        val gateway=repo.gateway;val original=gateway.client();val requests=CopyOnWriteArrayList<String>()
        @Suppress("UNCHECKED_CAST")
        val clients=NetworkGateway::class.java.getDeclaredField("clients").apply {isAccessible=true}.get(gateway) as MutableMap<Any,OkHttpClient>
        val key=synchronized(gateway){clients.entries.single {it.value===original}.key}
        val fixture=original.newBuilder().addInterceptor {chain->
            val request=chain.request()
            if(request.url.host!="publisher.fixture.invalid")throw IOException("Only owned publisher exchanges are available in this fixture")
            check(request.header("Cookie")==null&&request.header("Authorization")==null)
            requests+=request.url.toString()
            val main=request.url.toString()==url
            val text=if(main)body else "<p>No additional published details</p>"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .header("Content-Type",if(main)type else "text/html").body(text.toResponseBody()).build()
        }.build()
        synchronized(gateway){check(clients[key]===original);clients[key]=fixture}
        try {action(requests)}finally {synchronized(gateway){if(clients[key]===fixture)clients[key]=original}}
    }
    private fun evidence(name:String,requests:List<String>) {
        ui.waitForIdle();requireNativeFeedVisible(ui.activity)
        val dir=File(ui.activity.getExternalFilesDir("validation"),"publisher-document").apply {mkdirs()}
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        File(dir,"$name.json").writeText(com.google.gson.Gson().toJson(mapOf("requests" to requests,"reads" to reads,"businesses" to model.localSearch.state.value.businesses,"events" to model.localSearch.state.value.events,"scope" to "Owned response exchange through Gateway decoding, actual backend and native search; not live-source or physical-device performance.")))
    }
    private fun deck(marked:Boolean,calendar:Boolean,html:Boolean=false,plainCalendar:Boolean=false) {
        val prefix=if(marked)"\uFEFF"else ""
        val body=if(calendar)"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:fixture-document\r\nSUMMARY:Radio workshop\r\nDTSTART;VALUE=DATE:${area.today().plusDays(1).toString().replace("-","")}\r\nLOCATION:1 Example Street\\, Example City\r\nGEO:10;20\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"else if(html)
            """<script type="application/ld+json">{"@type":"LocalBusiness","name":"Community workshop","address":{"streetAddress":"1 Example Street","addressLocality":"Example City"},"geo":{"latitude":10,"longitude":20}}</script>"""else space
        val name="${if(marked)"marked"else "plain"}-${if(plainCalendar)"sniffed-calendar"else if(calendar)"calendar"else if(html)"html"else "space"}"
        response(prefix+body,if(html||plainCalendar)"text/plain"else if(calendar)"text/calendar"else "application/json") {requests->
            val actual=PublicLocalDiscoveryBackend(repo)
            ui.runOnIdle {
                model.localSearch.backend=object:LocalDiscoveryBackend {
                    override suspend fun areas(text:String)=listOf(area)
                    override suspend fun directory(query:LocalDiscoveryQuery)=LocalExtraction(validEmpty=true)
                    override suspend fun identities(point:LocalPoint)=LocalExtraction(validEmpty=true)
                    override suspend fun places(query:LocalDiscoveryQuery)=LocalExtraction(validEmpty=true)
                    override suspend fun discover(query:LocalDiscoveryQuery)=listOf(LocalWebHit(url,"Community workshop"))
                    override suspend fun discover(query:LocalDiscoveryQuery,onHits:suspend (List<LocalWebHit>)->Unit){onHits(discover(query))}
                    override suspend fun account(url:String)=false
                    override suspend fun enrich(query:LocalDiscoveryQuery,businesses:List<LocalBusiness>,onData:suspend (LocalExtraction)->Unit) {}
                    override suspend fun refine(query:LocalDiscoveryQuery,business:LocalBusiness)=emptyList<LocalWebHit>()
                    override suspend fun read(source:LocalPublisherSource,query:LocalDiscoveryQuery,business:LocalBusiness?):LocalExtraction {
                        try {
                            val data=actual.read(source,query,business)
                            reads+=mapOf("url" to source.url,"businesses" to data.businesses.size.toString(),"events" to data.events.size.toString())
                            return data
                        }catch(e:Exception){reads+=mapOf("url" to source.url,"failure" to e.javaClass.simpleName,"health" to ((e as? SourceFailure)?.status?.name ?: ""));throw e}
                    }
                }
                model.localSearch.mode(if(calendar)LocalSearchMode.EVENTS else LocalSearchMode.BUSINESSES)
            }
            requireNativeFeedVisible(ui.activity);ui.onNodeWithTag("Run discovery search").performClick()
            try {
                ui.waitUntil(6000){if(calendar)model.localSearch.state.value.events.any {it.title=="Radio workshop"}else model.localSearch.state.value.businesses.any {it.name=="Community workshop"}}
                ui.onNodeWithText(if(calendar)"Radio workshop"else "Community workshop").assertIsDisplayed()
                assertTrue(requests.contains(url));evidence(name,requests)
            }catch(e:Throwable){evidence("$name-failure",requests);throw e}
            finally {ui.runOnIdle {model.localSearch.stop()}}
        }
    }
    @Test fun anOrdinarySpaceDocumentProducesAReadableBusinessCard()=deck(false,false)
    @Test fun aMarkedSpaceDocumentStillProducesAReadableBusinessCard()=deck(true,false)
    @Test fun anOrdinaryCalendarProducesAReadableEventCard()=deck(false,true)
    @Test fun aMarkedCalendarStillProducesAReadableEventCard()=deck(true,true)
    @Test fun ordinaryHtmlRemainsReadableDespiteAGenericMediaType()=deck(false,false,html=true)
    @Test fun aMarkedHtmlPageRemainsReadableDespiteAGenericMediaType()=deck(true,false,html=true)
    @Test fun aMarkedPlainTextCalendarStillProducesAReadableEventCard()=deck(true,true,plainCalendar=true)
}
