package app.antisocial.android

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import app.antisocial.core.Target
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** These fixtures test native behavior, independently of upstream availability. */
@RunWith(AndroidJUnit4::class)
class ExpandedPlatformsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    private lateinit var preferences: String
    private val expanded=listOf(Platform.FACEBOOK,Platform.TRUTH_SOCIAL,Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY,Platform.GETTR,Platform.GAB,Platform.TELEGRAM,Platform.LINKEDIN,Platform.SNAPCHAT)
    private val fixtures=mutableListOf<Post>()

    @Before fun preserve() {
        runBlocking {repo.awaitReady()};preferences=repo.prefs.export().toString()
        repo.prefs.intervalMinutes=0
        ui.runOnIdle {model().following()}
    }
    @After fun restore() {
        ui.runOnIdle {
            model().discussions.close();model().following()
            ui.activity.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().apply()
            repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();scheduleRefresh(ui.activity,repo.prefs)
        }
        runBlocking {
            for(post in fixtures)for(table in listOf("history","seen"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE `key`=?",arrayOf(repo.key(post)))
        }
        assertEquals(com.google.gson.JsonParser.parseString(preferences),com.google.gson.JsonParser.parseString(repo.prefs.export().toString()))
    }
    private fun evidence(name: String) {
        ui.waitForIdle();android.os.SystemClock.sleep(250)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"expanded-platforms").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun addingOneNewPlatformPreservesTheOthersAndSurvivesRecreation() {
        var before=repo.prefs.selected
        ui.runOnIdle {model().section("settings");model().settings(SettingsPage.PLATFORMS)}
        for(platform in listOf(Platform.FACEBOOK,Platform.SNAPCHAT)) {
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${platform.id}"))
            ui.onNodeWithTag("Platform toggle ${platform.id}").assertExists()
        }
        if(expanded.all {it in before}) {
            ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle facebook"))
            ui.onNodeWithTag("Platform toggle facebook").performClick()
            before=repo.prefs.selected
        }
        val chosen=expanded.first {it !in before}
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${chosen.id}"))
        ui.onNodeWithTag("Platform toggle ${chosen.id}").performClick()
        assertEquals(before+chosen,repo.prefs.selected)
        assertEquals(before.toSet()+chosen,repo.prefs.selected.toSet())
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("Settings title").assertTextEquals("Platform tabs")
        assertEquals(before+chosen,repo.prefs.selected)
        ui.onNodeWithTag("Settings list").performScrollToNode(hasTestTag("Platform toggle ${chosen.id}"))
        evidence("choose-platforms")
    }

    @Test fun newPlatformPostsUseNativeCardsAndAccessibleTabs() {
        val vm=model()
        for(platform in expanded) {
            val url="${platform.origin}/antisocial_native_route_fixture/88001"
            val post=Post("expanded-native-${platform.id}",platform,url,"Native ${platform.label} author","Native ${platform.label} post body",publishedAt=1_760_000_000_000L)
            fixtures+=post
            ui.runOnIdle {
                vm.platform(platform,false);vm.following()
                vm.screen.value=ScreenState(platform,target=Target(platform,TargetKind.POST,"88001",url),page=Page(listOf(post)))
            }
            ui.onNodeWithTag("Platform tab ${platform.id}").assertIsSelected().assert(hasContentDescription(platform.label))
            ui.onNodeWithText(post.body).assertIsDisplayed()
            ui.onNodeWithContentDescription("Save post").assertExists()
            ui.onNodeWithTag("Read discussions ${repo.key(post)}").assertExists()
            ui.onNodeWithTag("Search feed").assertDoesNotExist()
            if(platform in setOf(Platform.FACEBOOK,Platform.MASTODON,Platform.TELEGRAM))evidence("native-${platform.id}")
        }
    }

    @Test fun subscriptionPickerExplainsServerAddressesAndPublicSurfaces() {
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {SubscriptionDialog(model(),Platform.MASTODON,onDismiss={})}}}
        ui.onNodeWithText("@person@server or public link").assertExists()
        ui.onNodeWithText("Use a full @person@server address or public link.").assertIsDisplayed()
        ui.onNodeWithTag("Subscription platform facebook").performScrollTo().performClick()
        ui.onNodeWithText("Page name or public post link").assertExists()
        ui.onNodeWithText("Public Pages and posts; personal profiles may be limited.").assertIsDisplayed()
        evidence("subscription-public-scope")
        ui.onNodeWithTag("Subscription platform lemmy").performScrollTo().performClick()
        ui.onNodeWithText("Community or post link").assertExists()
        ui.onNodeWithText("Use a full community or post link, including its server.").assertIsDisplayed()
    }

    @Test fun routeHealthDistinguishesStaleSuccessFromLoginWallsAndEmptyResponses() {
        val now=1_800_000_000_000L
        val sources=(0..2).map {Source(Platform.FACEBOOK,"fixture-$it","https://route$it.example.org")}
        val results=listOf(
            ProbeResult(sources[0],Capability.TIMELINE,HealthStatus.HEALTHY,215,now-3_720_000,true),
            ProbeResult(sources[1],Capability.COMMENTS,HealthStatus.LOGIN_REQUIRED,0,now,false,"No accountless comments were supplied"),
            ProbeResult(sources[2],Capability.POST,HealthStatus.EMPTY,0,now,false)
        )
        ui.runOnUiThread {ui.activity.setContent {MaterialTheme {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {results.forEach {SourceHealthCard(it.source,it,now)}}}}}
        ui.onNodeWithText("Previously supplied public data").assertIsDisplayed()
        ui.onNodeWithText("Checked 1 h ago · Tor · 215 ms").assertIsDisplayed()
        ui.onNodeWithText("Source requires a login").assertIsDisplayed()
        ui.onNodeWithText("No usable public data returned").assertIsDisplayed()
        ui.onNodeWithText("Public data verified").assertDoesNotExist()
        evidence("route-health")
    }

    @Test fun sourceInspectionRetainsTheSelectedFederatedServerWithoutProbing() {
        val vm=model();val target=repo.routes.target(Platform.MASTODON,"https://native-route.example.org/@alice")
        ui.runOnIdle {
            repo.prefs.originalFallback=true;vm.platform(Platform.MASTODON,false);vm.following()
            vm.screen.value=ScreenState(Platform.MASTODON,target=target,error="Fixture has no network request")
            vm.section("pools")
        }
        assertEquals(target,vm.screen.value.sourceTarget)
        ui.onNodeWithTag("Source pathways").assertIsDisplayed()
        ui.onAllNodesWithText("native-route.example.org").onFirst().assertIsDisplayed()
        ui.onAllNodesWithText("Not yet content-tested").onFirst().assertExists()
        evidence("selected-server-pathways")
    }

    /** External outcomes are evidence, not a claim that every upstream stays online. */
    @Test fun liveNewPlatformReadsRecordNativeOutcomes() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveExpanded")=="true")
        val vm=model();val records=mutableListOf<Map<String,Any?>>()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"expanded-platforms").apply {mkdirs()}
        val wanted=InstrumentationRegistry.getArguments().getString("antisocialExpandedPlatforms")?.split(',')?.toSet()
        val examples=listOf(
            Platform.FACEBOOK to "NASA",Platform.MASTODON to "stux@mstdn.social",Platform.LEMMY to "c/technology",
            Platform.TELEGRAM to "telegram",Platform.LINKEDIN to "company/google",Platform.TRUTH_SOCIAL to "realDonaldTrump",
            Platform.GETTR to "joerogan",Platform.GAB to "AmericanFamAssc",Platform.PIXELFED to "pixelfed",Platform.MISSKEY to "hmzdk3721",Platform.SNAPCHAT to "nasa"
        ).filter {wanted?.contains(it.first.id) ?: (it.first in setOf(Platform.FACEBOOK,Platform.MASTODON,Platform.LEMMY,Platform.TELEGRAM,Platform.LINKEDIN))}
        for((platform,input) in examples) {
            val started=System.currentTimeMillis()
            ui.runOnIdle {vm.platform(platform,false);vm.following();repo.applyNetworkSettings();vm.open(repo.routes.target(platform,input),true)}
            var timeout=false
            try {ui.waitUntil(90_000){!vm.screen.value.loading}}catch(_: androidx.compose.ui.test.ComposeTimeoutException){timeout=true}
            val state=vm.screen.value
            records+=mapOf("platform" to platform.id,"elapsedMs" to System.currentTimeMillis()-started,"timeout" to timeout,"error" to state.error,"posts" to state.page?.posts?.size,"source" to state.page?.provenance?.source?.root,"notice" to state.page?.notice)
            File(dir,"live-outcomes.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(records))
            evidence("live-${platform.id}")
            ui.runOnIdle {vm.following()}
        }
    }

    @Test fun liveIncomingFacebookPostAndBothCommentOrdersRecordNativeOutcomes() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialLiveFacebookPost")=="true")
        val vm=model();val records=mutableListOf<Map<String,Any?>>()
        val dir=File(requireNotNull(ui.activity.getExternalFilesDir("validation")),"expanded-platforms").apply {mkdirs()}
        val url="https://www.facebook.com/NASA/posts/pfbid09MsdoFygvqpxzL3H1Tw7eEGsYM48dTqRP4wjET6vJJUJLoYe4E1CNfWQhtSt6rVEl"
        val target=repo.routes.recognize(url)!!;val started=System.currentTimeMillis()
        ui.runOnIdle {
            if(Platform.FACEBOOK !in repo.prefs.selected)repo.prefs.selected=repo.prefs.selected+Platform.FACEBOOK
            ui.activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)).setClass(ui.activity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        var timeout=false
        try {ui.waitUntil(90_000){vm.screen.value.target?.canonicalUrl==target.canonicalUrl&&!vm.screen.value.loading}}catch(_: androidx.compose.ui.test.ComposeTimeoutException){timeout=true}
        val state=vm.screen.value
        records+=mapOf("operation" to "incoming post","elapsedMs" to System.currentTimeMillis()-started,"timeout" to timeout,"error" to state.error,"posts" to state.page?.posts?.size,"source" to state.page?.provenance?.source?.root)
        evidence("live-facebook-incoming-post")
        val root=state.page?.posts?.firstOrNull {ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}
        if(root!=null)for(order in ReadingOrder.entries) {
            val start=System.currentTimeMillis()
            ui.runOnIdle {vm.discussions.open(listOf(root),root.platform.id);vm.discussions.order(order)}
            ui.waitUntil(60_000){vm.discussions.state.value?.threads?.get(root.platform.id)?.loading==false}
            val thread=vm.discussions.state.value?.threads?.get(root.platform.id)
            records+=mapOf("operation" to order.label,"elapsedMs" to System.currentTimeMillis()-start,"error" to thread?.error,"comments" to thread?.page?.let {Discussions.comments(root,it).size},"hasNext" to (thread?.page?.next!=null))
            evidence("live-facebook-comments-${order.name.lowercase()}")
            if(thread?.page?.next!=null) {
                ui.runOnIdle {vm.discussions.more(root.platform.id)}
                ui.waitUntil(60_000){vm.discussions.state.value?.threads?.get(root.platform.id)?.loading==false}
                val next=vm.discussions.state.value?.threads?.get(root.platform.id)
                records+=mapOf("operation" to "${order.label} next block","error" to next?.error,"comments" to next?.page?.let {Discussions.comments(root,it).size})
            }
            ui.runOnIdle {vm.discussions.close()}
        }
        File(dir,"live-facebook-post.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(records))
    }
}
