package app.antisocial.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Real Android intents use the production VM/repository pipeline with local reader fixtures. */
@RunWith(AndroidJUnit4::class)
class DirectLinkFlowTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private lateinit var preferences: Map<String,Any?>
    private lateinit var aliases: Map<Platform,Int>
    private lateinit var previousLoader: suspend (Target,(String)->Unit)->Page
    private lateinit var previousLookup: suspend (Target)->ArchivedVersion?
    private lateinit var initialIntent: Intent
    private var budget: ByteArray?=null
    private val targets=mutableListOf<Target>()
    private val otherModels=mutableListOf<AntiSocialViewModel>()
    private val prefix=System.nanoTime().toString().takeLast(12)
    private var sequence=0
    private val loads=AtomicInteger();private val archives=AtomicInteger()
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]

    @Before fun isolate() {
        runBlocking {repo.awaitReady()}
        ui.runOnIdle {initialIntent=ui.activity.intent}
        preferences=ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).all.mapValues {(_,v)->if(v is Set<*>)v.toSet()else v}
        aliases=Platform.entries.filterNot {it==Platform.RSS}.associateWith {ui.activity.packageManager.getComponentEnabledSetting(LinkOpening.component(ui.activity,it))}
        previousLoader=repo.directLoader;previousLookup=repo.recoveryStore.lookup
        budget=File(repo.context.cacheDir,"archive-recovery-budget.json").takeIf {it.isFile}?.readBytes()
        ui.runOnIdle {
            model().platform(Platform.REDDIT,false);model().following();repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.archiveRecovery=true
            repo.prefs.originalFallback=false;repo.prefs.searchProvider=SearchProvider.DARWIN;repo.prefs.tor=false
            repo.prefs.onboarding=true;repo.prefs.selected=listOf(Platform.REDDIT)
            Platform.entries.forEach {repo.prefs.lastUrl(it,"")};repo.applyNetworkSettings()
        }
        // Let the existing native composition finish its platform-change effect
        // before pinning this fixture's deliberately unselected X alias.
        ui.waitForIdle();ui.runOnIdle {repo.prefs.selected=listOf(Platform.REDDIT)}
        repo.directLoader={_,_->loads.incrementAndGet();throw SourceFailure(HealthStatus.UNAVAILABLE,"Live fixture unavailable")}
        repo.recoveryStore.lookup={archives.incrementAndGet();version(it)}
    }
    @After fun restore() {
        ui.runOnIdle {
            // ActivityScenario matches lifecycle events to its launch intent.
            // Production onNewIntent intentionally retains each real incoming intent.
            ui.activity.intent=initialIntent
            model().following();otherModels.forEach {it.discussions.close();it.viewModelScope.cancel()}
            ui.activity.setContent {Text("Direct link flow fixture complete")}
            repo.directLoader=previousLoader;repo.recoveryStore.lookup=previousLookup
        }
        ui.waitForIdle()
        runBlocking {
            for(target in targets) {
                val post=Post(target.identity,target.platform,target.canonicalUrl,"","");val key=repo.key(post)
                repo.dao.removePosts(listOf(key))
                for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(key))
                for(cache in listOf(repo.pageKey(target),repo.recoveryStore.cacheKey(target,false),repo.recoveryStore.cacheKey(target,true))) {
                    repo.dao.media(cache)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(cache))
                    File(repo.context.cacheDir,"content/$cache").delete()
                }
            }
        }
        val file=File(repo.context.cacheDir,"archive-recovery-budget.json")
        budget?.let {Repository.atomic(file,it)} ?: file.delete()
        ui.runOnIdle {
            val editor=ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear()
            preferences.forEach {(key,value)->when(value) {
                is String->editor.putString(key,value);is Boolean->editor.putBoolean(key,value);is Int->editor.putInt(key,value)
                is Long->editor.putLong(key,value);is Float->editor.putFloat(key,value)
                is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            }}
            check(editor.commit());repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
            aliases.forEach {(platform,state)->ui.activity.packageManager.setComponentEnabledSetting(LinkOpening.component(ui.activity,platform),state,PackageManager.DONT_KILL_APP)}
        }
    }
    private fun target(platform: Platform=Platform.REDDIT): Target {
        val id="92$prefix${++sequence}"
        val url=if(platform==Platform.REDDIT)"https://www.reddit.com/r/Android/comments/lf$id/fixture" else "https://x.com/antisocial_fixture/status/$id"
        return requireNotNull(repo.routes.recognize(url)).also {targets+=it}
    }
    private fun version(target: Target)=ArchivedVersion("Internet Archive",Instant.parse("2026-02-02T02:40:00Z").toEpochMilli(),
        "https://web.archive.org/web/20260202024000id_/${target.canonicalUrl}",PostSnapshot(body="Recovered through the actual incoming-link pipeline",author="Public fixture"))
    private fun live(target: Target,body: String)=Page(listOf(Post(target.identity,target.platform,target.canonicalUrl,"Public fixture",body,
        provenance=Provenance(Source(target.platform,"fixture","https://reader-fixture.invalid"),System.currentTimeMillis(),target.canonicalUrl))))
    private fun send(target: Target,shared: Boolean=false) {
        val intent=if(shared)Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"A shared public post: ${target.canonicalUrl}")
        else Intent(Intent.ACTION_VIEW,Uri.parse(target.canonicalUrl))
        ui.runOnIdle {ui.activity.startActivity(intent.setClass(ui.activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))}
        ui.waitUntil(10_000){model().screen.value.let {it.target==target&&!it.loading}}
    }
    private fun evidence(name: String) {
        ui.waitForIdle();val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"direct-link-flow").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun viewIntentRecoversTheExactUnavailablePostUnderItsOriginalIdentity() {
        val target=target();send(target)
        val state=model().screen.value;assertNull(state.error)
        val post=requireNotNull(state.page).posts.single()
        assertEquals(target.canonicalUrl,post.canonicalUrl);assertNotNull(post.recoveredArchive);assertNull(post.archivedVersion)
        ui.onNodeWithTag("Recovered archive label").assertIsDisplayed()
        ui.onNodeWithText("Recovered through the actual incoming-link pipeline").assertIsDisplayed()
        ui.onNodeWithTag("Archive version toggle").assertDoesNotExist()
        assertEquals(1,loads.get());assertEquals(1,archives.get());evidence("view-archive-recovery")
    }

    @Test fun successfulDirectReadsNeverUseArchivesAndNewIntentsReplaceThePreviousPost() {
        repo.directLoader={target,_->loads.incrementAndGet();live(target,"Live fixture ${target.identity}")}
        val first=target();send(first);val vm=model()
        assertNull(vm.screen.value.page!!.posts.single().recoveredArchive)
        ui.onNodeWithTag("Recovered archive label").assertDoesNotExist()
        val second=target();send(second)
        assertSame(vm,model());assertEquals(second,vm.screen.value.target)
        assertEquals(listOf(second.canonicalUrl),vm.screen.value.page!!.posts.map {it.canonicalUrl})
        ui.onNodeWithText("Live fixture ${second.identity}").assertIsDisplayed()
        ui.onNodeWithText("Live fixture ${first.identity}").assertDoesNotExist()
        assertEquals(2,loads.get());assertEquals(0,archives.get());evidence("second-view-live-post")
    }

    @Test fun explicitShareCanReadAnUnselectedPlatformThroughTheSameRecoveryFlow() {
        assertFalse(Platform.X in repo.prefs.selected)
        val target=target(Platform.X);send(target,shared=true)
        assertEquals(target,model().screen.value.target);assertNull(model().screen.value.error)
        assertNotNull(model().screen.value.page!!.posts.single().recoveredArchive)
        ui.onNodeWithTag("Recovered archive label").assertIsDisplayed()
        assertEquals(1,loads.get());assertEquals(1,archives.get());evidence("shared-unselected-platform")
    }

    @Test fun externalRoutingNeverFetchesSocialContentAndUnavailableArchiveCanRetryLive() {
        lateinit var observer: AntiSocialViewModel
        ui.runOnIdle {observer=AntiSocialViewModel(repo).also {it.following();otherModels+=it}}
        for(url in listOf("https://example.org/direct-link-fixture","https://x.com/antisocial_fixture/status/12345"))runBlocking {
            val external=async(Dispatchers.Default){withTimeout(3_000){observer.externalLinks.first()}}
            ui.runOnIdle {observer.openLink(url)}
            assertEquals(url,external.await())
        }
        assertEquals(0,loads.get());assertEquals(0,archives.get())
        repo.recoveryStore.lookup={archives.incrementAndGet();throw SourceFailure(HealthStatus.UNAVAILABLE,"Archive fixture unavailable")}
        val target=target();send(target)
        assertNotNull(model().screen.value.error);assertTrue(model().screen.value.page?.posts.isNullOrEmpty())
        ui.onNodeWithText("Content is unavailable").assertIsDisplayed()
        ui.onNodeWithTag("Recovered archive label").assertDoesNotExist();evidence("archive-unavailable")
        repo.directLoader={requested,_->loads.incrementAndGet();live(requested,"Live post restored after Retry")}
        ui.onNodeWithText("Retry").performClick()
        ui.waitUntil(10_000){!model().screen.value.loading&&model().screen.value.error==null}
        ui.onNodeWithText("Live post restored after Retry").assertIsDisplayed()
        assertNull(model().screen.value.page!!.posts.single().recoveredArchive)
        assertEquals(2,loads.get());assertEquals(1,archives.get());evidence("retry-restores-live-content")
    }
}
