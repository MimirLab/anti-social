package app.antisocial.android

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class FeedAnalysisLifetimeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun aRealActivityStopReleasesOnlySourceReuseAndResumeKeepsItsBase() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        val mib=1024L*1024
        val resources=FeedAnalysisResources(ui.activity,true){SourcePreparationMemory(false,false,256*mib,16*mib,512*mib,128*mib)}
        val matcher=FeedEditionMatcher(emptyList(),emptyMap(),preparationBudget=resources::allowance)
        fun image(revision:Int)=MediaIdentitySnapshot(listOf(Post("71000001",Platform.X,"https://x.com/workshop/status/71000001",
            "workshop","A source retained by the reader.",publishedAt=1_800_000_000_000L,
            media=listOf(Media("https://images.example.org/photo.jpg",attachmentFingerprint="resource-$revision"))))){_,_->false}
        ui.runOnUiThread {ui.activity.setContent {FeedAnalysisLifetime(resources,matcher);Text("Source analysis lifecycle control")}}
        ui.waitForIdle()
        val first=image(0)
        runBlocking {matcher.project(first,{it},{_,_->null})}
        assertTrue(sourceAnalysisAllocation(matcher).second>0)
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0L,resources.allowance());assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
        val hidden=image(1)
        runBlocking {assertEquals(hidden.posts,matcher.project(hidden,{it},{_,_->null}).single().posts)}
        assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);ui.waitForIdle()
        runBlocking {assertEquals(hidden.posts,matcher.project(hidden,{it},{_,_->null}).single().posts)}
        assertEquals(0L to 0,sourceAnalysisAllocation(matcher))
        runBlocking {matcher.project(image(2),{it},{_,_->null})}
        assertEquals(SourcePreparationBudget.EXPANDED,sourceAnalysisAllocation(matcher).first)
        requireNativeFeedVisible(ui.activity)
    }
}
