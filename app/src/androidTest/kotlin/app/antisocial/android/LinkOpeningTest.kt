package app.antisocial.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.Platform
import app.antisocial.core.SocialLinkHosts
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkOpeningTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val preferences get()=Preferences(context)
    private lateinit var saved: String
    private lateinit var states: Map<Platform,Int>
    @Before fun preserve() {
        runBlocking {(context.applicationContext as AntiSocialApplication).repo.awaitReady()}
        saved=preferences.export().toString()
        states=Platform.entries.filterNot {it==Platform.RSS}.associateWith {context.packageManager.getComponentEnabledSetting(LinkOpening.component(context,it))}
    }
    @After fun restore() {
        context.getSharedPreferences("antisocial",Context.MODE_PRIVATE).edit().clear().commit()
        preferences.restore(JSONObject(saved))
        states.forEach {(platform,state)->context.packageManager.setComponentEnabledSetting(LinkOpening.component(context,platform),state,PackageManager.DONT_KILL_APP)}
        assertEquals(JsonParser.parseString(saved),JsonParser.parseString(preferences.export().toString()))
    }
    private fun handlers(url: String)=context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName),PackageManager.MATCH_DEFAULT_ONLY
    ).map {it.activityInfo.name}.distinct()

    @Test fun selectedHostsResolveThroughTheirOwnAliasAndUnrelatedSitesDoNot() {
        preferences.selected=Platform.entries.toList()
        SocialLinkHosts.hosts.forEach {(platform,hosts)->hosts.forEach {host->
            val concrete=host.replace("*.","publicblog.")
            val path=if(platform==Platform.YOUTUBE)if(host.endsWith("youtu.be"))"/BaW_jenozKc"else "/watch?v=BaW_jenozKc"else "/public/path"
            assertEquals("https://$concrete",listOf(LinkOpening.component(context,platform).className),handlers("https://$concrete$path"))
            assertEquals("http://$concrete",listOf(LinkOpening.component(context,platform).className),handlers("http://$concrete$path"))
        }}
        assertTrue(handlers("https://unrelated.example/article").isEmpty())
        assertTrue(handlers("https://tumblr.com.evil.example/article").isEmpty())
    }
    @Test fun removingATabStopsItsAutomaticHostHandlerWithoutDisablingSharing() {
        preferences.selected=listOf(Platform.X,Platform.REDDIT)
        assertFalse(handlers("https://mobile.twitter.com/nasa/status/123").isEmpty())
        preferences.selected=listOf(Platform.REDDIT)
        assertTrue(handlers("https://x.com/nasa/status/123").isEmpty())
        assertTrue(handlers("https://t.co/abc").isEmpty())
        assertFalse(handlers("https://old.reddit.com/r/Android/comments/abc/title").isEmpty())
        val share=Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(share,PackageManager.MATCH_DEFAULT_ONLY).any {it.activityInfo.name==MainActivity::class.java.name})
    }
    @Test fun restoredSelectionsSynchronizeHostComponents() {
        preferences.selected=listOf(Platform.X)
        val backup=preferences.export()
        preferences.selected=listOf(Platform.REDDIT)
        preferences.restore(backup)
        assertFalse(handlers("https://x.com/nasa/status/123").isEmpty())
        assertTrue(handlers("https://reddit.com/r/Android").isEmpty())
    }
    @Test fun setupOpensThisAppsNativeDefaultsPageWithoutRequestingBrowserRole() {
        val intent=LinkOpening.settingsIntent(context)
        assertEquals("package:${context.packageName}",intent.dataString)
        assertEquals(if(Build.VERSION.SDK_INT>=31)Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS else Settings.ACTION_APPLICATION_DETAILS_SETTINGS,intent.action)
        assertNotNull(intent.resolveActivity(context.packageManager))
    }
}
