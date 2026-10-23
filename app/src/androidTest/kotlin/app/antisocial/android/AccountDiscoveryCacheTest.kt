package app.antisocial.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.antisocial.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AccountDiscoveryCacheTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val repo get()=(context as AntiSocialApplication).repo
    @Test fun ordinaryAssociationsExpireAndClearingRejectsALateWriter() {
        val dir=File(context.cacheDir,"account-cache-fixture-${System.nanoTime()}");var now=1000L
        val cache=AccountDiscoveryCache(dir){now}
        try {
            val target=repo.routes.target(Platform.X,"cache_fixture")
            val value=AccountDiscovery("Fixture",listOf(AccountCandidate(target,"Entered",AccountLinkKind.ENTERED)),complete=true)
            val epoch=cache.epoch();cache.put("one",value,epoch)
            assertEquals(value,cache.get("one"));now+=16*60_000;assertNull(cache.get("one"))
            val web=AccountWebLinks(value.candidates,emptyList())
            cache.rememberWebsite("site",web,epoch);assertEquals(web,cache.website("site"))
            cache.put("two",value,epoch);cache.cleanup(true);cache.put("late",value,epoch)
            cache.rememberWebsite("late-site",web,epoch);assertNull(cache.website("late-site"))
            assertNull(cache.get("late"));assertEquals(0L,cache.bytes())
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        }finally {dir.deleteRecursively()}
    }
    @Test fun cacheRemainsBoundedAndMalformedLocalFilesDoNotBreakDiscovery() {
        val dir=File(context.cacheDir,"account-cache-fixture-${System.nanoTime()}");val cache=AccountDiscoveryCache(dir)
        try {
            val target=repo.routes.target(Platform.X,"cache_fixture")
            val value=AccountDiscovery("Fixture",listOf(AccountCandidate(target,"Entered")))
            repeat(150){cache.put("key$it",value,cache.epoch())}
            assertTrue(dir.listFiles().orEmpty().size<=128);assertTrue(cache.bytes()<=1024*1024)
            File(dir,Repository.hash("bad")+".json").writeText("{\"expires\":9999999999999}")
            assertNull(cache.get("bad"))
        }finally {dir.deleteRecursively()}
    }
    @Test fun selectedTorUnavailableStillReturnsVisibleProfileLinksWithoutDirectFallback()=runBlocking {
        repo.awaitReady();val old=repo.gateway.currentPolicy();val original=repo.prefs.originalFallback;val oldTor=repo.prefs.tor
        val seed=repo.routes.target(Platform.X,"tor_fixture");val other=repo.routes.target(Platform.INSTAGRAM,"tor_photos")
        repo.prefs.tor=true;repo.tor.enable(false);kotlinx.coroutines.delay(100);repo.gateway.configure(NetworkPolicy(tor=true));repo.prefs.originalFallback=false
        try {
            val result=repo.discoverAccounts(seed,Profile(seed,"Tor fixture",links=listOf(other.canonicalUrl)))
            assertEquals(setOf(seed,other),result.candidates.map {it.target}.toSet())
            assertTrue("Selected Tor policy changed",repo.gateway.currentPolicy().tor);assertTrue("Discovery should finish",result.complete)
        }finally {repo.prefs.originalFallback=original;repo.prefs.tor=oldTor;repo.gateway.configure(old);repo.accountDiscoveryCache.cleanup(true)}
    }
}
