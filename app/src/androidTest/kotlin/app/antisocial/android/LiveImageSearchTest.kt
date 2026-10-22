package app.antisocial.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Opt-in external observation, not an assertion that third-party availability
 * can be guaranteed. Fixture is a public NASA image supplied by the test runner. */
@RunWith(AndroidJUnit4::class)
class LiveImageSearchTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun observePublicSourcesThroughTheAppsTorTransport() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveImageSearch")=="true")
        val repo=(ui.activity.application as AntiSocialApplication).repo
        runBlocking {repo.awaitReady()}
        Assume.assumeTrue(repo.prefs.originalFallback)
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"image-search-live").apply {mkdirs()}
        val supplied=File(dir,"public-nasa.jpg")
        Assert.assertTrue("Supply the public NASA fixture explicitly",supplied.isFile)
        val input=BitmapFactory.decodeFile(supplied.path).let {bitmap->try {prepareImageSearchBitmap(bitmap)}finally {bitmap.recycle()}}
        val media=Media("https://images-assets.nasa.gov/image/PIA18033/PIA18033~small.jpg",description="Public NASA Blue Marble image")
        val cacheKey=Repository.hash(media.url)
        runBlocking {
            val file=File(repo.context.cacheDir,"content/$cacheKey").apply {parentFile?.mkdirs();writeBytes(input.bytes)}
            repo.dao.putMedia(MediaRow(cacheKey,file.path,file.length(),System.currentTimeMillis()))
        }
        lateinit var vm: AntiSocialViewModel
        var created=false
        val started=System.currentTimeMillis()
        var response=0
        repo.gateway.onTextResponse={payload->synchronized(dir) {if(++response<=12)File(dir,"response-$response.html").writeText(payload.text)}}
        try {
            ui.runOnUiThread {
                vm=AntiSocialViewModel(repo,imageSearchFactory={scope->ImageSearchController(repo,scope,prepare={_,_->input})})
                created=true
                vm.following();ui.activity.setContent {AntiSocialApp(vm)}
                vm.searchImage(media,"https://images.nasa.gov/")
            }
            ui.waitUntil(300_000){val s=vm.imageSearch.state.value;s.error!=null||!s.loading&&s.feed.engines.isNotEmpty()}
            val outcome=vm.imageSearch.state.value
            val record=mapOf("elapsedMs" to System.currentTimeMillis()-started,"image" to media.url,"imageBytes" to input.bytes.size,
                "searchPolicyTor" to repo.gateway.currentPolicy(NetworkRoute.TOR).tor,"torReady" to repo.tor.state.value.ready,
                "error" to outcome.error,"engines" to outcome.feed.engines,"posts" to outcome.feed.page.posts.map {it.canonicalUrl},
                "accounts" to outcome.feed.page.accounts.map {it.target.canonicalUrl},"unresolved" to outcome.feed.unresolved,"hasMore" to outcome.feed.hasMore)
            File(dir,"outcome.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(record))
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir,"native-live-outcome.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
            Assert.assertFalse(outcome.loading)
        }finally {
            repo.gateway.onTextResponse=null
            if(created)ui.runOnUiThread {vm.imageSearch.close();vm.viewModelScope.cancel()}
            runBlocking {repo.dao.media(cacheKey)?.let {File(it.path).delete()};repo.dao.removeMedia(listOf(cacheKey))}
        }
    }
}
