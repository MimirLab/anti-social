/* anti social, GPL-3.0-or-later. Explicit supplied-query native qualification. */
package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderSearchLiveNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun suppliedSiteWideQueriesDeliverReadableNativeCards() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialReaderSearchLive")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        val input=File(ui.activity.filesDir,"reader-routing-live.json");require(input.length() in 1..32768)
        val requests=JsonParser.parseReader(input.reader()).asJsonArray.filter {it.asJsonObject["kind"].asString=="search"}
        require(requests.size in 1..6)
        val before=repo.prefs.export().toString();val report=JsonArray();var model:AntiSocialViewModel?=null
        val output=File(ui.activity.getExternalFilesDir("validation"),"reader-search-live").apply {mkdirs()}
        try {
            ui.runOnIdle {
                ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java].viewModelScope.cancel()
                repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.mentionLookups=false;repo.prefs.communityComments=false
                Platform.entries.forEach {repo.prefs.lastUrl(it,"")};repo.applyNetworkSettings()
                model=AntiSocialViewModel(repo);ui.activity.setContent {AntiSocialApp(requireNotNull(model))}
            }
            val vm=requireNotNull(model)
            for((index,item) in requests.withIndex()) {
                val spec=item.asJsonObject;val platform=Platform.entries.single {it.id==spec["platform"].asString};val query=spec["value"].asString
                ui.runOnIdle {if(platform !in repo.prefs.selected)repo.prefs.selected=repo.prefs.selected+platform;repo.prefs.activePlatform=platform;vm.section("discover");vm.localSearch.mode(LocalSearchMode.POSTS)}
                ui.onNodeWithTag("Search site ${platform.id}").performScrollTo().performClick()
                ui.onNodeWithTag("Discovery query").performTextClearance();ui.onNodeWithTag("Discovery query").performTextInput(query)
                val start=System.nanoTime();ui.onNodeWithTag("Run discovery search").performClick()
                ui.waitUntil(120_000){vm.screen.value.let {s->s.target?.kind==TargetKind.SEARCH&&(s.page?.posts?.isNotEmpty()==true||!s.loading)}}
                val state=vm.screen.value;val row=JsonObject().apply {add("request",item);addProperty("firstReadableMs",(System.nanoTime()-start)/1_000_000);add("state",repo.gson.toJsonTree(state))};report.add(row)
                File(output,"runs.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
                assertEquals(platform,state.target?.platform);assertNull(state.error)
                assertTrue("A site-wide post search must deliver readable posts",state.page?.posts?.any {!it.preview}==true)
                ui.onNodeWithTag("Search feed").assertIsDisplayed()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(output,"$index-results.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }finally {
            ui.runOnIdle {model?.viewModelScope?.cancel();ui.activity.setContent {Text("Live search qualification complete")}}
            runBlocking {model?.viewModelScope?.coroutineContext?.get(Job)?.join()}
            ui.runOnIdle {ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply();repo.prefs.restore(JSONObject(before));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)}
        }
    }
}
