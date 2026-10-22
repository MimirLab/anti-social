/* AntiSocial, GPL-3.0-or-later. Controlled native scope and preservation checks. */
package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubeScopeTest {
    private val repo get()=ApplicationProvider.getApplicationContext<AntiSocialApplication>().repo
    private val suffix=System.nanoTime().toString().takeLast(8)
    private val video=Post("scope-$suffix",Platform.YOUTUBE,YouTubeUrls.canonical("Scp$suffix"),"Workshop","A preserved public workshop video.",publishedAt=System.currentTimeMillis())
    private val channel get()=repo.routes.target(Platform.YOUTUBE,"@ScopeWorkshop$suffix")
    private val legacy get()=Follow("legacy-video-$suffix",channel,"Old workshop channel")
    private lateinit var preferences: String
    private lateinit var policy: NetworkPolicy
    @Before fun preserve()=runBlocking {
        assertEquals("Run storage fixtures only on the isolated test device","true",InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice"))
        repo.awaitReady();preferences=repo.prefs.export().toString();policy=repo.gateway.currentPolicy()
        repo.prefs.intervalMinutes=0;repo.prefs.archiveChecks=false;repo.prefs.historyFullText=false
        repo.gateway.configure(NetworkPolicy(tor=true,ready=false))
    }
    @After fun restore() {
        val raw=repo.context.getSharedPreferences("antisocial",Context.MODE_PRIVATE)
        raw.edit().clear().commit();repo.prefs.restore(JSONObject(preferences));repo.applyNetworkSettings();repo.gateway.configure(policy)
        scheduleRefresh(repo.context,repo.prefs)
    }
    @Test fun legacyChannelRowsAndSavedVideosSurviveButNeverJoinOrRefreshTheFeed(): Unit=runBlocking {
        val social=Follow("social-$suffix",repo.routes.target(Platform.X,"scopetest$suffix"),"Social workshop")
        val post=Post("social-post-$suffix",Platform.X,"https://x.com/scopetest$suffix/status/987$suffix","Social workshop","The public workshop posted a complete update with useful information.",publishedAt=System.currentTimeMillis())
        val oldLoader=repo.followLoader;val requested=mutableListOf<String>()
        val rawLegacy=FollowRow(legacy.id,Platform.YOUTUBE.id,repo.gson.toJson(legacy))
        val bookmark=BookmarkRow(repo.key(video),Platform.YOUTUBE.id,repo.gson.toJson(video),System.currentTimeMillis())
        try {
            repo.dao.putFollow(rawLegacy);repo.dao.putFollow(FollowRow(social.id,Platform.X.id,repo.gson.toJson(social)))
            repo.store(Page(listOf(video)),legacy.id);repo.dao.putBookmark(bookmark)
            repo.followLoader={follow->requested+=follow.id;Page(listOf(post))}
            repo.refresh()
            assertTrue(social.id in requested);assertFalse(legacy.id in requested)
            assertFalse(repo.follows.first().any {it.target.platform==Platform.YOUTUBE})
            assertTrue(repo.legacyVideoFollows.first().any {it.id==legacy.id})
            assertFalse(withTimeout(5_000){repo.posts(null).first()}.any {it.platform==Platform.YOUTUBE})
            assertEquals(rawLegacy,repo.dao.allFollows().single {it.id==legacy.id})
            assertEquals(bookmark,repo.dao.bookmarksByKeys(listOf(repo.key(video))).single())
            assertThrows(IllegalArgumentException::class.java){runBlocking {repo.portability.exportPack(setOf(legacy.id))}}
        }finally {
            repo.followLoader=oldLoader
            repo.dao.removeFollow(legacy.id);repo.dao.removeFollow(social.id)
            repo.dao.removeBookmark(repo.key(video));repo.dao.removePosts(listOf(repo.key(video),repo.key(post)))
        }
    }
    @Test fun oldReaderFilesSkipChannelSubscriptionsAndStillImportTheirSavedVideos(): Unit=runBlocking {
        val payload=JsonObject().apply {
            val library=PortableLibrary(saved=listOf(PortableSaved(video,System.currentTimeMillis())))
            add("library",repo.gson.toJsonTree(library))
        }
        val bytes=PortableReader.encode(PortableDocument(PortableKind.STATE,System.currentTimeMillis(),listOf(legacy),statePayload=payload)).toByteArray()
        val preview=repo.portability.preview(bytes)
        assertTrue(preview.sources.isEmpty());assertEquals(1,preview.savedPosts)
        assertTrue(preview.warnings.any {it.contains("channel subscriptions are skipped")})
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.portability.apply(preview,setOf(legacy.id))}}
        assertFalse(repo.dao.allFollows().any {it.id==legacy.id})
        try {
            val result=repo.portability.apply(preview,emptySet(),PortableImportOptions(channels=false,filters=false,settings=false,progress=false))
            assertEquals(0,result.addedSources);assertEquals(1,result.savedPosts)
            assertEquals(video,repo.gson.fromJson(repo.dao.bookmarksByKeys(listOf(repo.key(video))).single().payload,Post::class.java))
            assertFalse(repo.dao.allFollows().any {it.platform==Platform.YOUTUBE.id&&it.id==legacy.id})
        }finally {repo.dao.removeBookmark(repo.key(video))}
    }
    @Test fun subscriptionAndSearchBackendsRejectChannelEntryBeforeAnyContentRead(): Unit=runBlocking {
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.follow(Platform.YOUTUBE,"@NASA")}}
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.saveSubscription("NASA",listOf(channel))}}
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.discoverAccounts(channel)}}
        assertThrows(IllegalArgumentException::class.java){repo.searchFeed(repo.routes.search(Platform.YOUTUBE,"workshop"))}
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.open(channel)}}
    }
}
