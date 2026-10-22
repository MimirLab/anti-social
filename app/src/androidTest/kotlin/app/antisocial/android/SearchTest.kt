package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=androidx.lifecycle.ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    @Before fun isolateConnectionPolicy() {runBlocking {repo.awaitReady()};ui.runOnUiThread {repo.tor.enable(false)}}
    @After fun stopDiagnostics() {repo.gateway.onNetworkEvent=null;repo.gateway.onTextResponse=null;ui.runOnUiThread {repo.applyNetworkSettings()}}
    private fun enter(value: String,profile: Boolean=false,keyboard: Boolean=false,beforeSubmit: ()->Unit={}) {
        ui.onNodeWithContentDescription("Search or open a link").performClick()
        if(profile)ui.onNodeWithText("Profile or link").performClick()
        ui.onNodeWithTag("Content query").performTextInput(value)
        beforeSubmit()
        if(keyboard)ui.onNodeWithTag("Content query").performImeAction()else ui.onNodeWithTag("Submit content query").performClick()
    }
    @Test fun livePreparedSearchUsesTheReadyConnection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialPreparedSearchBenchmark")=="true")
        runBlocking {repo.awaitReady()};val vm=model()
        val ready=java.util.concurrent.atomic.AtomicBoolean()
        val measuring=java.util.concurrent.atomic.AtomicBoolean()
        val connects=java.util.concurrent.atomic.AtomicInteger()
        repo.gateway.onNetworkEvent={event->
            if(event.host=="html.duckduckgo.com") {
                if(event.stage=="body ready"&&!measuring.get())ready.set(true)
                if(event.stage=="connect"&&measuring.get())connects.incrementAndGet()
            }
        }
        ui.runOnIdle {vm.platform(Platform.X,false);vm.following();repo.applyNetworkSettings();repo.prepareSearchConnection()}
        ui.waitUntil(60_000){ready.get()}
        var submitted=0L
        val freshAfter=System.currentTimeMillis()
        enter("NASA",beforeSubmit={measuring.set(true);submitted=android.os.SystemClock.elapsedRealtime()})
        ui.waitUntil(120_000){vm.screen.value.target?.identity=="NASA"&&!vm.screen.value.loading}
        freshIfCached(vm,TargetKind.SEARCH,freshAfter)
        val page=requireNotNull(vm.screen.value.page);assertNull(vm.screen.value.error)
        assertTrue(page.provenance!!.fetchedAt>=freshAfter&&!page.provenance!!.cached)
        assertEquals("duckduckgo",page.provenance!!.source.frontend);assertTrue(page.posts.isNotEmpty()||page.accounts.isNotEmpty())
        val elapsed=android.os.SystemClock.elapsedRealtime()-submitted
        android.util.Log.i("AntiSocialSearchAudit","Prepared native search feed submitMs=$elapsed newConnections=${connects.get()} posts=${page.posts.size} accounts=${page.accounts.size}")
        evidence("prepared-search-results")
    }
    @Test fun magnifyingGlassSearchesWordsAndInspectsSearchFailures() {
        runBlocking {repo.awaitReady()};val vm=model();val policy=repo.gateway.currentPolicy()
        repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.platform(Platform.X,false);vm.following()}
            enter("antisocial offline search fixture",keyboard=true)
            ui.waitUntil(10_000){vm.screen.value.target?.kind==TargetKind.SEARCH&&!vm.screen.value.loading}
            assertEquals("antisocial offline search fixture",vm.screen.value.target!!.identity)
            ui.onNodeWithText("Search is unavailable").assertIsDisplayed()
            ui.onNodeWithText("Inspect sources").performClick()
            ui.runOnIdle {assertEquals("pools",vm.screen.value.section);assertEquals(Capability.SEARCH,vm.screen.value.sourceCapability)}
            assertTrue(repo.pool.sources(Platform.X,Capability.SEARCH,true).any {it.frontend in PublicSearchAdapter.frontends})
        }finally {repo.gateway.configure(policy);ui.runOnIdle {vm.following()}}
    }
    @Test fun exactUsernameAndProfileModeRemainAvailable() {
        runBlocking {repo.awaitReady()};val vm=model();val policy=repo.gateway.currentPolicy()
        repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.platform(Platform.X,false);vm.following()}
            enter("@elonmusk")
            ui.waitUntil(10_000){vm.screen.value.target?.identity=="elonmusk"&&!vm.screen.value.loading}
            assertEquals(TargetKind.PROFILE,vm.screen.value.target!!.kind)
            enter("elon",profile=true)
            ui.waitUntil(10_000){vm.screen.value.target?.identity=="elon"&&!vm.screen.value.loading}
            assertEquals(TargetKind.PROFILE,vm.screen.value.target!!.kind)
        }finally {repo.gateway.configure(policy);ui.runOnIdle {vm.following()}}
    }
    /** Explicit external check, skipped during normal deterministic instrumentation. */
    @Test fun liveSearchOpensRealProfileAndPost() {
        val args=InstrumentationRegistry.getArguments()
        val query=args.getString("antisocialLiveSearchQuery").orEmpty()
        val expected=args.getString("antisocialLiveSearchProfile").orEmpty()
        assumeTrue("Explicit live-search arguments required",query.isNotBlank()&&expected.isNotBlank())
        runBlocking {repo.awaitReady()};val vm=model()
        ui.runOnUiThread {repo.applyNetworkSettings()}
        repo.gateway.onNetworkEvent={android.util.Log.i("AntiSocialSearchNetwork","${it.host}: ${it.stage} ${it.elapsedMs}ms")}
        val evidenceDir=requireNotNull(ui.activity.getExternalFilesDir("validation")).apply {mkdirs()}
        val responses=java.util.concurrent.atomic.AtomicInteger()
        repo.gateway.onTextResponse={payload->if(java.net.URI(payload.finalUrl).host.orEmpty().endsWith("duckduckgo.com")){val index=responses.getAndIncrement();if(index<3)java.io.File(evidenceDir,"selected-index-response-$index.html").writeText(payload.text)}}
        ui.runOnIdle {vm.platform(Platform.X,false);vm.following()}
        val searchStarted=System.currentTimeMillis()
        enter(query)
        ui.waitUntil(120_000){vm.screen.value.target!=null&&!vm.screen.value.loading}
        android.util.Log.i("AntiSocialSearchAudit","Submitted search reached ${vm.screen.value.target} error=${vm.screen.value.error}")
        assertEquals(TargetKind.SEARCH,vm.screen.value.target!!.kind)
        freshIfCached(vm,TargetKind.SEARCH,searchStarted)
        repo.pool.results(Platform.X).filter {it.capability==Capability.SEARCH}.forEach {android.util.Log.i("AntiSocialSearchAudit","Source ${it.source.root}: ${it.status} ${it.detail}")}
        val state=vm.screen.value;assertNull(state.error);val page=state.page!!
        val indexProvenance=requireNotNull(page.provenance)
        assertTrue("Live proof requires newly fetched index data",indexProvenance.fetchedAt>=searchStarted&&!indexProvenance.cached)
        val found=page.accounts.firstOrNull {it.target.canonicalUrl.equals(expected,true)}
        assertNotNull("Expected a real profile discovery result",found)
        evidence("search-results")
        android.util.Log.i("AntiSocialSearchAudit","Search feed source=${page.provenance?.source?.root} elapsedMs=${System.currentTimeMillis()-searchStarted} defaultTor=${repo.gateway.currentPolicy().tor} posts=${page.posts.size} accounts=${page.accounts.size} target=${found!!.target.canonicalUrl}")
        ui.onNodeWithTag("Search people").performScrollToNode(hasText(found.name))
        ui.onNodeWithText(found.name).assertIsDisplayed().performClick()
        ui.waitUntil(120_000){vm.screen.value.target?.kind==TargetKind.PROFILE&&!vm.screen.value.loading}
        val profile=vm.screen.value;assertNull(profile.error);assertNotNull(profile.page?.profile)
        assertTrue("A reachable profile shell is insufficient",profile.page!!.posts.isNotEmpty())
        evidence("opened-profile")
        android.util.Log.i("AntiSocialSearchAudit","Profile source=${profile.page.provenance?.source?.root} name=${profile.page.profile?.name} posts=${profile.page.posts.size} dated=${profile.page.posts.count {it.publishedAt!=null}}")
        val postStarted=System.currentTimeMillis()
        val indexedPost=page.posts.firstOrNull()
        val postTarget=if(indexedPost!=null) {
            ui.onNodeWithContentDescription("Back").performClick()
            ui.waitUntil(10_000){vm.screen.value.target?.kind==TargetKind.SEARCH&&!vm.screen.value.loading}
            ui.onNodeWithTag("Search feed").performScrollToNode(hasText(indexedPost.body))
            ui.onAllNodesWithText(indexedPost.body).onFirst().performClick()
            repo.routes.recognize(indexedPost.canonicalUrl)!!
        }else {
            val post=profile.page.posts.first {it.body.isNotBlank()}
            ui.onAllNodesWithText(post.body).onFirst().performScrollTo().performClick()
            repo.routes.recognize(post.canonicalUrl)!!
        }
        ui.waitUntil(120_000){vm.screen.value.target?.kind==TargetKind.POST&&!vm.screen.value.loading}
        freshIfCached(vm,TargetKind.POST,postStarted)
        val detail=vm.screen.value;assertNull(detail.error)
        val wanted=ContentIdentity.key(Platform.X,postTarget.canonicalUrl)
        assertTrue("The selected result must load its own post",detail.page!!.posts.any {ContentIdentity.key(it.platform,it.canonicalUrl)==wanted&&(it.body.isNotBlank()||it.media.isNotEmpty())})
        val postProvenance=requireNotNull(detail.page.provenance)
        assertTrue("Live proof requires newly fetched post data",postProvenance.fetchedAt>=postStarted&&!postProvenance.cached)
        evidence("opened-post")
        android.util.Log.i("AntiSocialSearchAudit","Post source=${detail.page.provenance?.source?.root} posts=${detail.page.posts.size} matched=$wanted protection=${repo.gateway.protectionStats()}")
        val warmStarted=System.currentTimeMillis()
        enter("NASA")
        ui.waitUntil(120_000){vm.screen.value.target?.kind==TargetKind.SEARCH&&vm.screen.value.target?.identity=="NASA"&&!vm.screen.value.loading}
        freshIfCached(vm,TargetKind.SEARCH,warmStarted)
        val warm=vm.screen.value;assertNull(warm.error)
        assertTrue(warm.page!!.provenance!!.fetchedAt>=warmStarted&&!warm.page.provenance!!.cached)
        assertTrue(warm.page.accounts.isNotEmpty()||warm.page.posts.isNotEmpty())
        android.util.Log.i("AntiSocialSearchAudit","Warm native search feed elapsedMs=${System.currentTimeMillis()-warmStarted} source=${warm.page.provenance!!.source.root} posts=${warm.page.posts.size} accounts=${warm.page.accounts.size}")
        evidence("warm-search-results")
    }
    private fun freshIfCached(vm: AntiSocialViewModel,kind: TargetKind,started: Long) {
        val state=vm.screen.value
        if(state.error==null&&(state.page?.provenance?.fetchedAt ?: 0)<started) {
            ui.runOnIdle {vm.refresh()}
            ui.waitUntil(120_000){vm.screen.value.target?.kind==kind&&!vm.screen.value.loading}
        }
    }
    private fun evidence(name: String) {
        ui.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=requireNotNull(ui.activity.getExternalFilesDir("validation")).apply {mkdirs()}
        java.io.File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
