/* anti social, GPL-3.0-or-later. Opt-in first-operation diagnostics. */
package app.antisocial.android

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Run in a freshly force-stopped engineering fixture. There is deliberately
 * no Activity rule, awaitReady(), Tor-readiness wait or preparatory search. */
class ColdReaderSearchLiveTest {
    @Test fun suppliedSearchIsTheFirstOperationAndRecordsCrossSiteRecovery()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialColdReaderSearchLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=File(context.filesDir,"reader-routing-live.json");require(input.length() in 1..32768)
        val specs=JsonParser.parseReader(input.reader()).asJsonArray;require(specs.size() in 1..6)
        val repo=(context.applicationContext as AntiSocialApplication).repo
        val before=repo.prefs.export().toString();val report=JsonObject();val runs=JsonArray();report.add("runs",runs)
        report.add("initialTor",repo.gson.toJsonTree(repo.tor.state.value))
        report.add("initialFilters",repo.gson.toJsonTree(repo.filters.value))
        val events=CopyOnWriteArrayList<NetworkEvent>();repo.gateway.onNetworkEvent={events+=it}
        val output=File(context.getExternalFilesDir("validation"),"cold-reader-search-live").apply {mkdirs()}
        var vm:AntiSocialViewModel?=null
        try {
            withContext(Dispatchers.Main){repo.prefs.intervalMinutes=0;repo.applyNetworkSettings();vm=AntiSocialViewModel(repo)}
            val model=requireNotNull(vm)
            for(item in specs) {
                val spec=item.asJsonObject;val p=Platform.entries.single {it.id==spec["platform"].asString}
                val target=repo.routes.search(p,spec["value"].asString);val start=System.nanoTime()
                val row=JsonObject().apply {add("request",item);add("startingTor",repo.gson.toJsonTree(repo.tor.state.value))};runs.add(row)
                withContext(Dispatchers.Main){model.open(target,true)}
                try {
                    val state=withTimeout(150_000){model.screen.first {s->s.target==target&&s.searchFeed?.revision?.let {it>0}==true&&!s.loading}}
                    row.add("state",repo.gson.toJsonTree(state))
                }catch(e:TimeoutCancellationException){row.addProperty("error","Cold request timed out")}
                row.addProperty("elapsedMs",(System.nanoTime()-start)/1_000_000)
                row.add("endingTor",repo.gson.toJsonTree(repo.tor.state.value));row.add("health",repo.gson.toJsonTree(repo.pool.results(p)))
                report.add("events",repo.gson.toJsonTree(events));File(output,"runs.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            }
            val first=runs.first().asJsonObject.getAsJsonObject("state")
            assertNotNull("The first search must complete",first)
            assertTrue("The first cold search must deliver readable posts",first.getAsJsonObject("page")?.getAsJsonArray("posts")?.isEmpty==false)
        }finally {
            repo.gateway.onNetworkEvent=null
            withContext(Dispatchers.Main){vm?.viewModelScope?.cancel();context.getSharedPreferences("antisocial",android.content.Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();scheduleRefresh(context,repo.prefs)}
        }
    }
}
