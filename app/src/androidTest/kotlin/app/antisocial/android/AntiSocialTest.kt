package app.antisocial.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AntiSocialTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    private fun model()=androidx.lifecycle.ViewModelProvider(ui.activity,AntiSocialViewModel.Factory(repo))[AntiSocialViewModel::class.java]
    @Before fun isolateConnectionPolicy() {runBlocking {repo.awaitReady()};ui.runOnUiThread {repo.tor.enable(false)}}
    @After fun restoreConnectionPolicy() {ui.runOnUiThread {repo.applyNetworkSettings()}}
    private fun visualPause() {
        val ms=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("antisocialVisualPauseMs")?.toLongOrNull()?.coerceIn(0,45_000) ?: 0
        if(ms>0)Thread.sleep(ms)
    }
    @Test fun savedVideoRendersWhileNetworkIsFailClosed() {
        runBlocking {repo.awaitReady()}
        val url="https://example.org/antisocial-video-fixture.mp4";val key=Repository.hash(url)
        val file=java.io.File(ui.activity.cacheDir,"antisocial-video-fixture.mp4")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("video-fixture.mp4").use {input->file.outputStream().use {input.copyTo(it)}}
        runBlocking {repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis(),saved=true))}
        val target=repo.routes.target(Platform.X,"fixture/status/123")
        val post=Post("saved-video",Platform.X,target.canonicalUrl,"fixture","Synthetic offline video",media=listOf(Media(url,"video")))
        val vm=model();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.platform(Platform.X,false);vm.following();vm.screen.value=ScreenState(Platform.X,target=target,page=Page(listOf(post)))}
            ui.onNodeWithText("Play video").performClick()
            ui.waitUntil(60_000){ui.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Video ready")).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("Video player").assertIsDisplayed()
        }finally {repo.gateway.configure(NetworkPolicy());ui.runOnIdle {vm.following()};ui.waitForIdle();runBlocking {repo.dao.removeMedia(listOf(key))};file.delete()}
    }
    @Test fun invalidCustomFilterAddressesCannotBreakSettings() {
        val before=repo.prefs.customLists();val url="https://example.org/antisocial-filter-fixture.txt"
        try {
            assertFalse(repo.prefs.addList("https://"));assertFalse(repo.prefs.addList("http://example.org/list.txt"))
            assertFalse(repo.prefs.addList("https://user:password@example.org/list.txt"))
            assertTrue(repo.prefs.addList("$url#section"))
            assertTrue(repo.filterSources.any {it.url==url&&it.name=="example.org"})
        }finally {if(url !in before)repo.prefs.removeList(url)}
    }
    @Test fun bundledProtectionRestoresOnAndroid()=runBlocking {
        withTimeout(90_000){repo.awaitReady()}
        assertTrue(repo.filters.value.ready)
        val expected=com.google.gson.JsonParser.parseReader(ui.activity.assets.open("filter-seed-status.json").reader()).asJsonObject["rules"].asInt
        // Active protection may be newer or customized; verify the bundled
        // snapshot independently instead of requiring it to replace that set.
        val bundled=com.google.gson.JsonParser.parseReader(ui.activity.assets.open("network-rules.compiled.json").reader()).asJsonObject
        assertEquals(expected,bundled["entries"].asInt)
        val bytes=ui.activity.assets.open("network-rules.compiled").use {it.readBytes()}
        ui.activity.assets.openFd("network-rules.compiled").use {assertEquals(bytes.size.toLong(),it.length)}
        ui.activity.assets.openFd("network-rules.compiled").use {asset->
            NativeFilter.restoreFileRange("/proc/self/fd/${asset.parcelFileDescriptor.fd}",asset.startOffset,asset.length).use {filter->
                assertTrue(filter.check("https://www.google-analytics.com/collect?v=1","https://reader.test/","xmlhttprequest").blocked)
            }
        }
        NativeFilter.restore(bytes).use {filter->
            assertTrue(filter.check("https://www.google-analytics.com/collect?v=1","https://reader.test/","xmlhttprequest").blocked)
            assertFalse(filter.check("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=bsky.app","https://bsky.app/","xmlhttprequest").blocked)
        }
    }
    @Test fun cachedNativeGridSurvivesFailedRefreshAndTabSwitch() {
        runBlocking {repo.awaitReady()}
        val target=repo.routes.target(Platform.INSTAGRAM,"cachefixture")
        val posts=(0 until 90).map {Post("cached-grid-$it",Platform.INSTAGRAM,"https://www.instagram.com/p/CacheFixture$it","cachefixture","Cached native tile $it")}
        val page=Page(posts,profile=Profile(target,"Cached native profile"))
        runBlocking {repo.store(page,allowPurged=true);repo.rememberPage(target,page)}
        val vm=model();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.open(target)}
            ui.waitUntil(30_000){vm.screen.value.page?.profile?.name=="Cached native profile"&&vm.screen.value.error!=null}
            ui.onNodeWithText("Cached native profile").assertIsDisplayed()
            ui.runOnIdle {vm.refresh()}
            ui.onNodeWithText("Cached native profile").assertIsDisplayed()
            ui.waitUntil(30_000){!vm.screen.value.loading}
            ui.onNodeWithTag("Instagram profile grid").performScrollToIndex(60)
            ui.onNodeWithText("Cached native tile 59").assertIsDisplayed()
            ui.runOnIdle {vm.platform(Platform.REDDIT,false)}
            ui.waitForIdle()
            ui.runOnIdle {vm.platform(Platform.INSTAGRAM)}
            ui.onNodeWithText("Cached native tile 59").assertIsDisplayed()
            ui.runOnIdle {vm.section("settings")}
            ui.onNodeWithText("Settings").assertIsDisplayed()
            ui.runOnIdle {vm.back()}
            ui.onNodeWithText("Cached native tile 59").assertIsDisplayed()
        }finally {
            repo.gateway.configure(NetworkPolicy());ui.runOnIdle {vm.following()}
            runBlocking {repo.dao.media(repo.pageKey(target))?.let {java.io.File(it.path).delete()};repo.dao.removeMedia(listOf(repo.pageKey(target)));repo.dao.removePosts(posts.map {repo.key(it)})}
        }
    }
    @Test fun decodedImagesAreReusedAndCachedPagesExpireNormally()=runBlocking {
        repo.awaitReady()
        val url="https://example.org/image-memory-fixture.png";val key=Repository.hash(url)
        val file=java.io.File(ui.activity.cacheDir,"image-memory-fixture.png")
        val bitmap=android.graphics.Bitmap.createBitmap(1024,1024,android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()))
        val target=repo.routes.target(Platform.REDDIT,"r/CacheExpiryFixture")
        try {
            val a=repo.images.load(url,url,256);val b=repo.images.load(url,url,256)
            assertSame(a,b);assertTrue(a.width<=256)
            repo.rememberPage(target,Page(emptyList(),profile=Profile(target,"Cache expiry fixture")))
            assertNotNull(repo.cachedPage(target))
            val row=repo.dao.media(repo.pageKey(target))!!
            repo.dao.putMedia(row.copy(lastViewedAt=System.currentTimeMillis()-31*CachePolicy.DAY))
            repo.cleanup();assertNull(repo.cachedPage(target));assertFalse(java.io.File(row.path).exists())
        }finally {repo.images.clear();repo.dao.removeMedia(listOf(key,repo.pageKey(target)));file.delete()}
    }
    @Test fun pageMetadataCannotResurrectExpiredPostBodies()=runBlocking {
        repo.awaitReady()
        val target=repo.routes.target(Platform.REDDIT,"r/PageReferenceFixture")
        val post=Post("page-reference",Platform.REDDIT,"https://www.reddit.com/r/PageReferenceFixture/comments/page_reference/test","fixture","Unique body that must expire")
        val page=Page(listOf(post),profile=Profile(target,"Page reference fixture"))
        val key=repo.key(post)
        try {
            repo.store(page,allowPurged=true);repo.rememberPage(target,page)
            val snapshot=java.io.File(repo.dao.media(repo.pageKey(target))!!.path)
            assertFalse(snapshot.readText().contains(post.body))
            repo.dao.putPost(repo.dao.post(key)!!.copy(createdAt=System.currentTimeMillis()-(repo.prefs.unseenDays+1L)*CachePolicy.DAY,lastViewedAt=null))
            repo.cleanup();assertNull(repo.dao.post(key));assertTrue(repo.dao.seen(key)!!.purged)
            assertTrue(repo.cachedPage(target)!!.posts.isEmpty())
        }finally {repo.dao.media(repo.pageKey(target))?.let {java.io.File(it.path).delete()};repo.dao.removeMedia(listOf(repo.pageKey(target)));repo.dao.removePosts(listOf(key))}
    }
    @Test fun nativeInstagramGridPostAndImageViewerWorkOffline() {
        runBlocking {repo.awaitReady()}
        val originalTheme=repo.prefs.theme
        androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("antisocialVisualTheme")?.takeIf {it in setOf("light","dark")}?.let {
            repo.prefs.theme=it;ui.activityRule.scenario.recreate()
        }
        val target=repo.routes.target(Platform.INSTAGRAM,"fixture")
        val source=repo.routes.sources.first {it.frontend=="kittygram"}
        val page=Extraction.parse(Payload("""<div class="user-info-card"><div class="usernames"><h3>Fixture profile</h3></div><p class="user-bio-text">Synthetic native UI test</p></div><div class="post"><a class="username">fixture</a><div class="post-image"><img src="https://example.org/fixture.png" alt="Synthetic image"></div><time datetime="2026-10-14T12:00:00Z"></time><div class="caption">Synthetic caption</div><a href="/p/Fixture">Comments</a></div>""","${source.root}/fixture","text/html",200),target,source,repo.routes)
        val file=java.io.File(ui.activity.cacheDir,"native-fixture.png")
        val bitmap=android.graphics.Bitmap.createBitmap(80,80,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.GREEN)}
        file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        runBlocking {repo.store(page,allowPurged=true);repo.dao.putMedia(MediaRow(Repository.hash(page.posts.single().media.single().url),file.path,file.length(),System.currentTimeMillis()))}
        val vm=model()
        repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.platform(Platform.INSTAGRAM,false);vm.following();vm.screen.value=ScreenState(Platform.INSTAGRAM,target=target,page=page)}
            ui.waitForIdle()
            ui.onAllNodesWithText("Fixture profile").onFirst().assertIsDisplayed()
            ui.waitUntil(30_000){ui.onAllNodesWithContentDescription("Synthetic caption").fetchSemanticsNodes().isNotEmpty()}
            visualPause()
            ui.onNodeWithContentDescription("Synthetic caption").performClick()
            ui.waitForIdle()
            ui.waitUntil(30_000){ui.onAllNodesWithText("Synthetic caption").fetchSemanticsNodes().isNotEmpty()}
            ui.waitUntil(30_000){ui.onAllNodesWithContentDescription("Synthetic image").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithContentDescription("Synthetic image").performClick()
            ui.waitForIdle()
            ui.onNodeWithContentDescription("Close image").assertIsDisplayed()
            ui.onNodeWithText("1 / 1").assertIsDisplayed()
            visualPause()
            ui.onNodeWithContentDescription("Close image").performClick()
            ui.waitForIdle()
        }catch(e: Throwable) {
            android.util.Log.e("AntiSocialTest","UI failure thread identities: "+Thread.getAllStackTraces().keys.joinToString {"${it.id}:${it.name}"})
            throw e
        }finally {
            repo.prefs.theme=originalTheme
            repo.gateway.configure(NetworkPolicy());ui.runOnIdle {vm.following()}
            runBlocking {repo.dao.removePosts(listOf(repo.key(page.posts.single())));repo.dao.removeMedia(listOf(Repository.hash(page.posts.single().media.single().url)))};file.delete()
        }
    }
    @Test fun nativePinterestBoardOpensItsOwnTarget() {
        runBlocking {repo.awaitReady()}
        val originalTabs=repo.prefs.selected
        repo.prefs.selected=listOf(Platform.X,Platform.REDDIT,Platform.INSTAGRAM,Platform.TIKTOK)
        repo.prefs.activePlatform=Platform.INSTAGRAM
        ui.activityRule.scenario.recreate()
        val target=repo.routes.target(Platform.PINTEREST,"fixture")
        val board=repo.routes.recognize("https://www.pinterest.com/fixture/space/")!!
        val vm=model();repo.gateway.configure(NetworkPolicy(tor=true))
        try {
            ui.runOnIdle {vm.platform(Platform.PINTEREST,false);vm.following();vm.screen.value=ScreenState(Platform.PINTEREST,target=target,page=Page(emptyList(),profile=Profile(target,"Fixture boards"),collections=listOf(app.antisocial.core.Collection(board,"Space board",detail="12 Pins"))))}
            ui.onNodeWithText("Space board").assertIsDisplayed();ui.onNodeWithText("Space board").performClick()
            ui.runOnIdle {assertEquals(board,vm.screen.value.target)}
        }finally {repo.gateway.configure(NetworkPolicy());repo.prefs.selected=originalTabs;ui.runOnIdle {vm.following()}}
    }
    @Test fun nativeTabsSettingsLibraryAndExpiry() {
        runBlocking {repo.awaitReady()}
        ui.runOnIdle {model().platform(Platform.REDDIT,false);model().following();model().platform(Platform.X,false);model().following()}
        ui.onNodeWithTag("Platform tab reddit").performClick()
        // A remembered feed can keep its introductory header collapsed. The
        // selected platform icon and active reader are the navigation contract.
        ui.onNodeWithTag("Platform tab reddit").assertIsSelected()
        ui.runOnIdle {assertEquals(Platform.REDDIT,model().screen.value.platform)}
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("Settings").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("Settings").performClick()
        val now=System.currentTimeMillis()
        val old=Post("https://www.reddit.com/r/Android/comments/fixture/old",Platform.REDDIT,"https://www.reddit.com/r/Android/comments/fixture/old","fixture","This is synthetic instrumentation data.","Offline fixture",now-8*CachePolicy.DAY)
        runBlocking {
            repo.store(Page(listOf(old)),allowPurged=true);val key=repo.key(old);repo.dao.putPost(repo.dao.post(key)!!.copy(createdAt=now-(repo.prefs.unseenDays+1L)*CachePolicy.DAY,lastViewedAt=null));repo.bookmark(old,true);repo.cleanup()
            assertNull(repo.dao.post(key));assertTrue(repo.dao.seen(key)!!.purged)
            repo.store(Page(listOf(old)));assertNull(repo.dao.post(key))
            assertTrue(repo.dao.allBookmarks().any {it.key==key})
        }
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithText("Library",useUnmergedTree=true).performClick()
        ui.onNodeWithText("Offline fixture").assertIsDisplayed()
        ui.onNodeWithTag("Library entry ${repo.key(old)}").performClick()
        ui.onNodeWithTag("Local copy reader").assertExists()
        ui.onNodeWithContentDescription("Remove local copy").performClick()
        ui.onNodeWithTag("Confirm remove local copy").performClick()
        ui.waitUntil(5000){runBlocking {repo.dao.allBookmarks().none {it.key==repo.key(old)}}}
    }
    @Test fun encryptedBackupRejectsWrongPasswordAndTampering() {
        val bytes="private subscriptions".toByteArray();val encrypted=BackupCrypto.encrypt(bytes,"correct passphrase".toCharArray())
        assertArrayEquals(bytes,BackupCrypto.decrypt(encrypted,"correct passphrase".toCharArray()))
        assertThrows(Exception::class.java){BackupCrypto.decrypt(encrypted,"wrong passphrase".toCharArray())}
        encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java){BackupCrypto.decrypt(encrypted,"correct passphrase".toCharArray())}
    }
    @Test fun invalidBackupSettingsCannotPartiallyImportSubscriptions()=runBlocking {
        repo.awaitReady()
        val backup=com.google.gson.JsonParser.parseString(String(repo.exportBackup())).asJsonObject
        backup.getAsJsonObject("settings").addProperty("cacheMb",-1)
        val target=repo.routes.target(Platform.REDDIT,"r/InvalidBackupFixture")
        val follow=Follow("invalid-backup-fixture",target,"Invalid backup fixture")
        backup.getAsJsonArray("follows").add(repo.gson.toJsonTree(FollowRow(follow.id,target.platform.id,repo.gson.toJson(follow))))
        assertThrows(IllegalArgumentException::class.java){runBlocking {repo.importBackup(backup.toString().toByteArray())}}
        assertTrue(repo.dao.allFollows().none {it.id==follow.id})
    }
    @Test fun twoVisibleUsersOfTheSameCacheEntryKeepItProtected()=runBlocking {
        repo.awaitReady();val file=java.io.File(ui.activity.cacheDir,"lease-fixture").apply {writeText("fixture")};val key="lease-fixture"
        repo.dao.putMedia(MediaRow(key,file.path,file.length(),System.currentTimeMillis()-8*CachePolicy.DAY))
        repo.lease(key,true);repo.lease(key,true);repo.lease(key,false)
        repo.cleanup(clearAll=true);assertNotNull(repo.dao.media(key));assertTrue(file.exists())
        repo.lease(key,false);repo.cleanup(clearAll=true);assertNull(repo.dao.media(key));assertFalse(file.exists())
    }
    @Test fun torSelectionFailsClosedAndSettingsSurviveRecreation() {
        runBlocking {repo.awaitReady()};repo.prefs.cacheMb=200
        repo.gateway.configure(NetworkPolicy(tor=true))
        assertThrows(java.io.IOException::class.java){repo.gateway.client()}
        repo.gateway.configure(NetworkPolicy())
        assertEquals(200,Preferences(ui.activity).cacheMb)
        repo.prefs.cacheMb=500
    }
    @Test fun nativeAtomFeedAndXmlEntityRejection() {
        val target=repo.routes.target(Platform.REDDIT,"r/Android")
        val source=Source(Platform.REDDIT,"rss","https://example.org")
        val atom="""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Synthetic feed entry</title><link rel="alternate" href="https://www.reddit.com/r/Android/comments/native/feed"/><author><name>fixture</name></author><updated>2026-10-14T10:00:00Z</updated><content type="html">&lt;p&gt;Synthetic feed body&lt;/p&gt;</content></entry></feed>"""
        val page=Extraction.parse(Payload(atom,"https://example.org/feed","application/atom+xml",200),target,source,repo.routes)
        assertEquals("Synthetic feed body",page.posts.single().body);assertNotNull(page.posts.single().publishedAt)
        val unsafe="""<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///data/local/tmp/private">]><rss><channel><item><link>https://example.org/1</link><description>&x;</description></item></channel></rss>"""
        assertThrows(Exception::class.java){Extraction.parse(Payload(unsafe,"https://example.org/feed","application/xml",200),target,source,repo.routes)}
    }
    @Test fun bundledTorConnectsAndUsesTorExit()=runBlocking {
        repo.awaitReady()
        val manager=ui.activity.getSystemService(android.app.ActivityManager::class.java)
        fun torProcess()=manager.runningAppProcesses?.firstOrNull {it.processName=="app.antisocial.android:tor"}
        val pids=mutableSetOf<Int>()
        try {
            repeat(2) {
                ui.runOnUiThread {repo.tor.enable(true)}
                withTimeout(300_000){while(!repo.tor.state.value.ready)delay(1000)}
                assertEquals(java.net.Proxy.Type.SOCKS,repo.gateway.client().proxy!!.type())
                val result=repo.gateway.text("https://check.torproject.org/api/ip")
                assertTrue(com.google.gson.JsonParser.parseString(result.text).asJsonObject["IsTor"].asBoolean)
                val pid=torProcess()?.pid ?: error("Tor must run in its private process")
                assertNotEquals(android.os.Process.myPid(),pid);assertTrue(pids.add(pid))
                ui.runOnUiThread {repo.tor.enable(false)}
                withTimeout(30_000){while(torProcess()!=null)delay(250)}
                delay(2500)
            }
        }finally {ui.runOnUiThread {repo.tor.enable(false)}}
    }
}
