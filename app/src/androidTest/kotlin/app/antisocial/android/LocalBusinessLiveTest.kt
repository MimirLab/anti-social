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
    @Test fun suppliedBusinessesUseProductionDiscoveryAndWashing()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalBusinessLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val area=LocalLiveInput.area();val cases=LocalLiveInput.businessCases()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo
        repo.awaitReady();val before=repo.prefs.export().toString();val report=JsonObject();val runs=JsonArray()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val controller=LocalDiscoveryController(repo,scope)
        try {
            repo.prefs.originalFallback=true;repo.prefs.searchProvider=SearchProvider.DUCKDUCKGO
            withContext(Dispatchers.IO){repo.localDiscovery.cleanup(clear=true)}
            withContext(Dispatchers.Main){controller.mode(LocalSearchMode.BUSINESSES);controller.area(area)}
            for(case in cases) {
                val query=case.query
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
                Assert.assertTrue(state.businesses.all {it.point?.let {p->area.point.distanceKm(p)<=area.radiusKm} ?: LocalDiscoveryFilter.locality(area,it.locality)})
                case.excludedAccount?.let {excluded->Assert.assertFalse(state.businesses.any {it.socials.any {url->url.contains(excluded,true)}})}
                case.uniqueName?.let {name->Assert.assertEquals("The same physical business must occupy one card",1,state.businesses.count {LocalDiscoveryMerge.businessName(it.name).contains(LocalDiscoveryMerge.businessName(name))})}
                case.uniqueAddress?.let {address->Assert.assertEquals("Address copies must be one physical business",1,state.businesses.count {it.address.contains(address,true)})}
            }
            report.addProperty("torReady",repo.tor.state.value.ready);report.addProperty("status","verified")
        }finally {
            File(context.filesDir,"local-business-live.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            withContext(Dispatchers.Main){controller.close()};scope.cancel();repo.prefs.restore(JSONObject(before));repo.localSearchConnection(false)
        }
    }
}
