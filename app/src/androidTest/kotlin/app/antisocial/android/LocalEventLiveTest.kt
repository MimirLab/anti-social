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

@RunWith(AndroidJUnit4::class)
class LocalEventLiveTest {
    @Test fun publicFestivalPageBecomesADatedLocalEventThroughTheProductionWasher():Unit=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLocalEventLive")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=(context.applicationContext as AntiSocialApplication).repo;repo.awaitReady()
        val before=repo.prefs.export().toString();val report=JsonObject()
        val area=LocalArea("public-example-melbourne","Melbourne","Melbourne, Victoria, Australia",LocalPoint(-37.814,144.96332),5,"Australia/Melbourne")
        try {
            repo.prefs.tor=false;repo.prefs.originalFallback=true;repo.applyNetworkSettings()
            val url="https://whatson.melbourne.vic.gov.au/things-to-do/melbourne-international-jazz-festival"
            val result=withTimeout(45_000){PublicLocalDiscoveryBackend(repo).read(LocalPublisherSource(url),LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS),null)}
            val event=result.events.first {it.title.contains("Melbourne International Jazz Festival")}
            Assert.assertEquals("2026-10-16",event.start.value);Assert.assertEquals("2026-10-25",event.end!!.value)
            Assert.assertEquals("Melbourne",event.locality);Assert.assertNull(event.point)
            val during=java.time.Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
            Assert.assertTrue(LocalDiscoveryFilter.event(LocalDiscoveryQuery(area,mode=LocalSearchMode.EVENTS),event,during))
            Assert.assertTrue(eventTime(event,area).contains("25 Oct"))
            report.add("event",Gson().toJsonTree(event));report.addProperty("label",eventTime(event,area));report.addProperty("status","verified")
        }finally {
            File(context.filesDir,"local-event-live.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings()
        }
    }
}
