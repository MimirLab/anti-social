/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Native preferences, Room follow records and Gateway policy; no public endpoint is contacted. */
@RunWith(AndroidJUnit4::class)
class RouteHeartbeatTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=(context.applicationContext as AntiSocialApplication).repo
    private lateinit var preferences: String
    private lateinit var savedHeartbeat: HeartbeatSnapshot
    private lateinit var previousDeviceState: ()->HeartbeatDeviceState
    private val suffix=System.nanoTime().toString()
    private val prefix="heartbeat-native-$suffix:"
    private val controllers=mutableListOf<RouteHeartbeatAndroid>()
    private val files=mutableListOf<File>()
    private val healthy=HeartbeatDeviceState(true,true,true)
    private fun target(platform: Platform=Platform.X,name: String="reader"): Target = Target(platform,TargetKind.PROFILE,name,
        when(platform){Platform.X->"https://x.com/$name";Platform.BLUESKY->"https://bsky.app/profile/$name.test";else->"${platform.origin}/@$name"})
    private fun follow(name: String,target: Target=target(name=name),enabled: Boolean=true,feed: String?=null)=Follow(prefix+name,target,name,enabled=enabled,feedUrl=feed)
    private suspend fun insert(vararg follows: Follow) {follows.forEach {repo.dao.putFollow(FollowRow(it.id,it.target.platform.id,repo.gson.toJson(it)))}}
    private fun controller(scheduled: MutableList<Long?> = CopyOnWriteArrayList()): RouteHeartbeatAndroid {
        val file=File(context.cacheDir,"heartbeat-test-$suffix-${files.size}.json").also {files+=it}
        return RouteHeartbeatAndroid(repo,file,followRows={repo.dao.allFollows().filter {it.id.startsWith(prefix)}},scheduler={scheduled+=it}).also {it.deviceState={healthy};controllers+=it}
    }
    private fun activate(target: Target,tor: Boolean=false) {
        val scope=RouteHeartbeat.scope(target,Capability.FEED,tor)
        repo.pool.heartbeat.observeRead(scope,"native fixture winner","native fixture plan")
        val snapshot=repo.pool.heartbeat.snapshot()
        repo.pool.heartbeat.restore(snapshot.copy(scopes=snapshot.scopes.map {if(it.scope==scope)it.copy(nextDueAt=System.currentTimeMillis())else it}))
    }
    @Before fun preserve()=runBlocking {
        repo.awaitReady()
        preferences=repo.prefs.export().toString();savedHeartbeat=repo.pool.heartbeat.snapshot()
        previousDeviceState=repo.routeHeartbeat.deviceState
        repo.routeHeartbeat.deviceState={HeartbeatDeviceState(false,false,false)}
        // Stop the controller before setting a policy so a live Tor status reply cannot
        // accidentally replace the deliberately unavailable test transport.
        withContext(Dispatchers.Main){repo.tor.enable(false)}
        withTimeout(5000){while(repo.tor.state.value.enabled)delay(25)}
        repo.prefs.tor=false;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.originalFallback=true
        repo.prefs.selected=listOf(Platform.X);repo.prefs.intervalMinutes=60
        repo.gateway.configure(NetworkPolicy());repo.pool.heartbeat.clear()
    }
    @After fun restore()=runBlocking {
        controllers.forEach {it.close()}
        repo.dao.allFollows().filter {it.id.startsWith(prefix)}.forEach {repo.dao.removeFollow(it.id)}
        files.forEach {file->listOf(file,File(file.path+".part")).forEach {it.delete()}}
        repo.pool.heartbeat.restore(savedHeartbeat)
        withContext(Dispatchers.Main) {
            context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(context,repo.prefs)
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
        repo.routeHeartbeat.deviceState=previousDeviceState
    }

    @Test fun enabledSelectedAccountsShareOneActivatedPlanWhileCustomFeedsAndUnvisitedSitesStayQuiet()=runBlocking {
        val first=target(name="first-$suffix");val second=target(name="second-$suffix")
        insert(follow("first",first),follow("second",second),follow("disabled",enabled=false),
            follow("unselected",target(Platform.BLUESKY,"outside-$suffix")),
            follow("custom",feed="https://feeds.invalid/private-$suffix.xml"),
            follow("search",first.copy(kind=TargetKind.SEARCH,canonicalUrl="https://x.com/search?q=private-$suffix")),
            follow("post",first.copy(kind=TargetKind.POST,canonicalUrl=first.canonicalUrl+"/status/12345")))
        val controller=controller()
        assertTrue("Selection alone must not trigger blind platform probes",controller.plans().isEmpty())
        activate(first)
        val plans=controller.plans()
        assertEquals(1,plans.size)
        assertEquals(Capability.FEED,plans.single().capability)
        assertTrue(plans.single().target in listOf(first,second))
        assertEquals(Platform.X,plans.single().target.platform)
        repo.prefs.selected=listOf(Platform.REDDIT)
        assertTrue("Removing a selected site makes its scheduled route ineligible",controller.plans().isEmpty())
    }

    @Test fun unmeteredQuietOpportunityUsesTheRealSelectedFollowPlan()=runBlocking {
        val chosen=target(name="chosen-$suffix");insert(follow("chosen",chosen));activate(chosen)
        val controller=controller();val reads=CopyOnWriteArrayList<Target>()
        controller.execute={target,_->reads+=target}
        // A preceding activity can still be cancelling its foreground read.
        // The production controller correctly yields while that read is active.
        withTimeout(5000){while(repo.pool.hasActiveReads)delay(25)}
        controller.run(foreground=true)
        assertEquals(listOf(chosen),reads.toList())
        assertFalse("An opportunistic route check must not start Tor",repo.tor.state.value.enabled)
    }

    @Test fun offlineMeteredAndLowBatteryStatesDoNotSpendAProbe()=runBlocking {
        val chosen=target(name="defer-$suffix");insert(follow("defer",chosen));activate(chosen)
        val scheduled=CopyOnWriteArrayList<Long?>();val controller=controller(scheduled);var calls=0
        controller.execute={_,_->calls++}
        for(state in listOf(HeartbeatDeviceState(false,false,true),HeartbeatDeviceState(true,false,true),HeartbeatDeviceState(true,true,false))) {
            controller.deviceState={state};val before=System.currentTimeMillis()
            controller.run()
            assertEquals(0,calls)
            assertTrue("An unavailable background opportunity is deferred at least thirty minutes",requireNotNull(scheduled.last())>=before+30*60_000L)
        }
    }

    @Test fun selectedTorFailsClosedWithoutStartingOrWaitingForATransport()=runBlocking {
        val chosen=target(name="tor-$suffix");insert(follow("tor",chosen))
        repo.prefs.tor=true;repo.gateway.configure(NetworkPolicy(tor=true,ready=false));activate(chosen,tor=true)
        val scheduled=CopyOnWriteArrayList<Long?>();val controller=controller(scheduled);var calls=0
        controller.execute={_,_->calls++}
        withTimeout(2000){controller.run()}
        assertEquals(0,calls);assertFalse(repo.tor.state.value.enabled)
        assertTrue(repo.gateway.currentPolicy().tor);assertFalse(repo.gateway.currentPolicy().ready)
        assertTrue(runCatching {repo.gateway.client()}.exceptionOrNull() is IOException)
        assertNotNull(scheduled.last())
        // A preference/policy transition is equally ineligible; no direct fallback is used.
        repo.gateway.configure(NetworkPolicy())
        withTimeout(2000){controller.run(foreground=true)}
        assertEquals(0,calls)
    }

    @Test fun turningBackgroundRefreshOffCancelsHeartbeatWithoutLosingLearnedState()=runBlocking {
        val chosen=target(name="disabled-$suffix");insert(follow("disabled-background",chosen));activate(chosen)
        val before=repo.pool.heartbeat.snapshot();val scheduled=CopyOnWriteArrayList<Long?>();val controller=controller(scheduled);var calls=0
        controller.execute={_,_->calls++};repo.prefs.intervalMinutes=0
        controller.run()
        assertEquals(0,calls);assertEquals(listOf<Long?>(null),scheduled.toList())
        assertEquals(before,repo.pool.heartbeat.snapshot())
    }

    @Test fun persistentNativeCacheKeepsBudgetsAndHashesWhileExcludingAccountAndSearchText() {
        var now=System.currentTimeMillis();val policy=RouteHeartbeat {now}
        val scoped=target(Platform.MASTODON,"private-account-$suffix")
        val scope=RouteHeartbeat.scope(scoped,Capability.FEED,false)
        val source=Source(Platform.MASTODON,"native","https://alternate.invalid")
        policy.observeRead(scope,source.key,"secret query and route material $suffix")
        now=requireNotNull(policy.dueAt(scope))
        val reservation=requireNotNull(policy.reserve(scope,source,"secret query and route material $suffix"))
        assertTrue(policy.complete(reservation,HeartbeatOutcome.HEALTHY,"secret query and route material $suffix"))
        val file=File(context.cacheDir,"heartbeat-persistence-$suffix.json").also {files+=it}
        val cache=RouteHeartbeatCache(file,repo.gson);cache.write(policy)
        val raw=file.readText()
        assertFalse(raw.contains("private-account"));assertFalse(raw.contains("secret query"));assertFalse(raw.contains(scoped.canonicalUrl))
        assertTrue(raw.contains("mastodon.social"))
        val restored=RouteHeartbeat {now};cache.restore(restored)
        assertEquals(policy.snapshot(),restored.snapshot())
        assertEquals(policy.dueAt(scope),restored.dueAt(scope))
        assertEquals(1,restored.snapshot().attempts.size)
        val other=scope.copy(capability=Capability.TIMELINE)
        restored.observeRead(other,"other winner","other plan")
        val otherState=restored.snapshot()
        restored.restore(otherState.copy(scopes=otherState.scopes.map {if(it.scope==other)it.copy(nextDueAt=now)else it}))
        assertNull("A restart cannot erase the shared host/request budget",restored.reserve(other,source,"other plan"))
    }
}
