package app.antisocial.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestaurantMenuLiveTest {
    private fun repo():Repository {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialRestaurantMenuLive")=="true")
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        return (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AntiSocialApplication).repo
    }
    private val area get()=LocalLiveInput.area()
    private fun evidence(repo:Repository,name:String,value:Any){File(repo.context.filesDir,"restaurant-menu-$name.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(value))}
    @Test fun publishedMenuSuppliesLocationScopedDishesWithoutLoadingItsOrderingWidget()=runBlocking {
        val repo=repo();repo.awaitReady()
        val b=LocalLiveInput.business("antisocialMenuBusiness")
        val expectedItem=LocalLiveInput.text("antisocialMenuItem",200)
        val menuPath=LocalLiveInput.text("antisocialMenuPath",200)
        val started=System.currentTimeMillis()
        val backend=PublicLocalDiscoveryBackend(repo);val query=LocalDiscoveryQuery(area)
        val first=withTimeout(40_000){backend.read(LocalPublisherSource(b.websites.single(),businessId=b.id),query,b)}
        val owner=first.businesses.first {it.id==b.id}
        val route=first.sources.first {it.kind==LocalSourceKind.MENU&&it.url.contains(menuPath)}
        val data=withTimeout(30_000){backend.read(route,query,owner)}
        val menus=data.businesses.flatMap {it.menus.orEmpty()}
        evidence(repo,"burger",mapOf("elapsedMs" to System.currentTimeMillis()-started,"data" to data))
        assertTrue("Published dishes must reach native menu records",menus.flatMap {it.items}.any {it.name.contains(expectedItem,true)&&it.prices.isNotEmpty()})
        assertTrue(menus.all {LocalMenus.valid(it)})
        assertTrue("The locator's public menu should provide more than its three popular items",menus.any {it.items.size>=20})
    }
    @Test fun coffeeMenuPdfIsReadWithoutAIAndItsDisposableBinaryIsRemoved()=runBlocking {
        val repo=repo();repo.awaitReady()
        val b=LocalLiveInput.business("antisocialMenuPdfBusiness")
        val backend=PublicLocalDiscoveryBackend(repo);val query=LocalDiscoveryQuery(area)
        val first=withTimeout(40_000){backend.read(LocalPublisherSource(b.websites.single(),businessId=b.id),query,b)}
        val owner=first.businesses.first {it.id==b.id}
        // Select the supplied embedded-text PDF separately from scanned sheets.
        val suffix=LocalLiveInput.text("antisocialMenuPdfSuffix",200)
        val pdf=LocalMenuRouting.known(owner).first {it.url.substringBefore('?').endsWith(suffix)}
        evidence(repo,"coffee-route",mapOf("route" to pdf,"business" to owner))
        val enabled=repo.ai.preferences.enabled
        try {
            repo.ai.preferences.enabled=false
            val data=withTimeout(45_000){backend.read(pdf,query,owner)}
            evidence(repo,"coffee-pdf",data)
            assertTrue(data.businesses.flatMap {it.menus.orEmpty()}.any {it.text.contains("Coffee",true)&&it.sourceHash.isNotBlank()})
            assertFalse(repo.ai.preferences.enabled)
            assertTrue(File(repo.context.cacheDir,"restaurant-menus").listFiles().orEmpty().isEmpty())
        }finally {repo.ai.preferences.enabled=enabled}
    }
}
