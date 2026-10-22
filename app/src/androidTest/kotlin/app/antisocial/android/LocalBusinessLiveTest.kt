package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalBusinessLiveTest {
    @Test fun melbourneFloridaBusinessesUseProductionDiscoveryAndWashing()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalBusinessLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady();val before=repo.prefs.export().toString();val report=JsonObject();val runs=JsonArray()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val controller=LocalDiscoveryController(repo,scope)
        val area=LocalArea("audit-melbourne-fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
        try {
            repo.prefs.originalFallback=true;repo.prefs.searchProvider=SearchProvider.DUCKDUCKGO
            withContext(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
            withContext(Dispatchers.Main){controller.mode(LocalSearchMode.BUSINESSES);controller.area(area)}
            for(query in listOf("sky zone","tacos","burger inn","indian river coffee")) {
                val start=android.os.SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main){controller.text(query);controller.search()}
                var firstMs:Long?=null
                val watcher=launch {controller.state.first {it.businesses.any {b->LocalDiscoveryFilter.matches(query,LocalBusinessRouting.text(b))}};firstMs=android.os.SystemClock.elapsedRealtime()-start}
                val state=withTimeout(180_000){controller.state.first {!it.loading&&!it.enriching}}
                watcher.cancelAndJoin()
                val row=JsonObject().apply {addProperty("query",query);addProperty("elapsedMs",android.os.SystemClock.elapsedRealtime()-start);addProperty("firstCardMs",firstMs);addProperty("cards",state.businesses.size);add("coverage",Gson().toJsonTree(state.coverage));add("businesses",Gson().toJsonTree(state.businesses.take(20)))}
                runs.add(row);report.add("runs",runs)
                File(context.filesDir,"local-business-live.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
                Assert.assertTrue("No native business cards for $query",state.businesses.isNotEmpty())
                Assert.assertTrue(state.businesses.all {it.point?.let {p->area.point.distanceKm(p)<=25} ?: LocalDiscoveryFilter.locality(area,it.locality)})
                Assert.assertFalse(state.businesses.any {it.socials.any {url->url.contains("joefrenchrealtor",true)}})
                if(query=="burger inn")Assert.assertEquals("The same physical Burger Inn must occupy one card",1,state.businesses.count {LocalDiscoveryMerge.businessName(it.name).contains("burger inn")})
                if(query=="indian river coffee")Assert.assertEquals("Sarno Road copies must be one physical coffee shop",1,state.businesses.count {it.address.contains("Sarno",true)})
            }
            report.addProperty("torReady",repo.tor.state.value.ready);report.addProperty("status","verified")
        }finally {
            File(context.filesDir,"local-business-live.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            withContext(Dispatchers.Main){controller.close()};scope.cancel();repo.prefs.restore(JSONObject(before));repo.localSearchConnection(false)
        }
    }
}
