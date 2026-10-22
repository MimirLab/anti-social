package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import com.google.gson.*
import org.junit.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Opt-in public example, never an inferred user/home location. Every request
 * uses the production backend and NetworkGateway with Tor for regional data. */
@RunWith(AndroidJUnit4::class)
class LocalDiscoveryLiveTest {
    @Test fun melbourneIdentityGraphOverProtectedProductionTransport()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalDiscoveryLive")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady();val before=repo.prefs.export().toString();val report=JsonObject()
        val store=LocalDiscoveryStore(context,databaseName="local-live-audit.db")
        try {
            repo.prefs.originalFallback=true
            val backend=PublicLocalDiscoveryBackend(repo)
            val areas=withTimeout(120_000){backend.areas("Melbourne")}
            val area=areas.first {it.countryCode=="AU"}.copy(radiusKm=5)
            report.add("area",Gson().toJsonTree(area));report.addProperty("torReady",repo.tor.state.value.ready)
            Assert.assertTrue(repo.tor.state.value.ready)
            val identities=withTimeout(90_000){backend.identities(area.point)}
            report.addProperty("overturePlaces",identities.businesses.size);report.addProperty("withSocials",identities.businesses.count {it.socials.isNotEmpty()})
            Assert.assertTrue("Expected public Overture business identities",identities.businesses.isNotEmpty())
            Assert.assertTrue("Expected identities linking businesses to social accounts",identities.businesses.any {it.socials.isNotEmpty()})
            val start=android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO){store.ingest(identities,area)}
            report.addProperty("ingestMs",android.os.SystemClock.elapsedRealtime()-start)
            val native=withContext(Dispatchers.IO){store.businesses(area)}
            report.addProperty("nativeCards",native.size);Assert.assertTrue(native.isNotEmpty())
            report.add("sample",Gson().toJsonTree(native.first {it.socials.isNotEmpty()}))
            try {
                repo.localDiscovery.writableDatabase.delete("cache","id LIKE 'directory-rest:%'",null)
                val directory=withTimeout(60_000){backend.directory(LocalDiscoveryQuery(area,"organic food"))}
                report.addProperty("osmPlaces",directory.businesses.size)
            }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;report.addProperty("osmUnavailable","Timed out")}
            catch(e:Exception){report.addProperty("osmUnavailable",e.message)}
            report.addProperty("status","verified")
        }finally {
            File(context.filesDir,"local-discovery-live.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            store.close();context.deleteDatabase("local-live-audit.db")
            repo.prefs.restore(JSONObject(before));repo.localSearchConnection(false)
        }
    }
}
