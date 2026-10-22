package app.antisocial.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.ContextModelCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Metadata fixtures only; no inference, credentials, accounts or network. */
@RunWith(AndroidJUnit4::class)
class ContextCapabilityStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("context-model-capabilities", Context.MODE_PRIVATE)
    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun vllmUtilityHintSurvivesReopeningAndOldMetadataIsRechecked():Unit=runBlocking {
        val store=ContextCapabilityStore(context)
        val report=ContextModelCapabilities.local("selected").copy(vllmChatTemplate=true)
        store.resolve("selected","direct"){report}
        assertTrue(ContextCapabilityStore(context).peek("selected","direct")!!.vllmChatTemplate)
        val key=prefs.all.keys.single()
        val value=prefs.getString(key,null)
        prefs.edit().clear().putString(key.replaceFirst("v2:","v1:"),value).commit()
        assertNull(ContextCapabilityStore(context).peek("selected","direct"))
        var reads=0
        store.resolve("selected","direct"){reads++;report}
        assertEquals(1,reads);assertTrue(prefs.all.keys.all {it.startsWith("v2:")})
    }

    @Test fun cacheExpiresAndIsScopedByModelRouteAndPrivacyMode(): Unit = runBlocking {
        var now = 100_000L
        val store = ContextCapabilityStore(context, { now }, 1000)
        val loads = AtomicInteger()
        suspend fun load() = ContextModelCapabilities.local("selected", now).also { loads.incrementAndGet() }
        store.resolve("route-model-a", "direct") { load() }
        store.resolve("route-model-a", "direct") { load() }; assertEquals(1, loads.get())
        store.resolve("route-model-a", "tor") { load() }
        store.resolve("route-model-b", "direct") { load() }; assertEquals(3, loads.get())
        now += 1000
        assertNull(store.peek("route-model-a", "direct"))
        store.resolve("route-model-a", "direct") { load() }; assertEquals(4, loads.get())
        store.resolve("route-model-a", "direct", force = true) { load() }; assertEquals(5, loads.get())
    }
    @Test fun concurrentRequestsShareMetadataAndInvalidationCannotBeUndoneByInFlightLoad(): Unit = runBlocking {
        val store = ContextCapabilityStore(context)
        val loads = AtomicInteger()
        (1..8).map { async { store.resolve("route", "direct") { delay(20); loads.incrementAndGet(); ContextModelCapabilities.local("selected") } } }.awaitAll()
        assertEquals(1, loads.get())
        store.invalidate()
        val began = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val inFlight = async { store.resolve("route", "direct") { began.complete(Unit); proceed.await(); ContextModelCapabilities.local("selected") } }
        began.await(); store.invalidate("route"); proceed.complete(Unit); inFlight.await()
        assertNull(store.peek("route", "direct"))
    }
    @Test fun cacheIsBoundedAndDoesNotPersistRawRouteIdentity(): Unit = runBlocking {
        val store = ContextCapabilityStore(context)
        val identity = "https://private-host.invalid/v1|selected|private-route-marker"
        store.resolve(identity, "direct") { ContextModelCapabilities.local("selected") }
        assertFalse(prefs.all.toString().contains(identity)); assertFalse(prefs.all.toString().contains("private-route-marker"))
        repeat(40) { index -> store.resolve("route-$index", "direct") { ContextModelCapabilities.local("selected") } }
        assertTrue(prefs.all.size <= 32)
    }
    @Test fun invalidPersistedReportsAreRejected(): Unit = runBlocking {
        val store = ContextCapabilityStore(context)
        store.resolve("route", "direct") { ContextModelCapabilities.local("selected") }
        val key = prefs.all.keys.single()
        prefs.edit().putString(key, """{"cachedAt":${System.currentTimeMillis()},"report":{"contextTokens":-1}}""").commit()
        assertNull(store.peek("route", "direct"))
        store.resolve("route", "direct") { ContextModelCapabilities.local("selected") }
        assertEquals(2048, store.peek("route", "direct")?.contextTokens)
    }
    @Test fun missingMetadataRetriesSoonerThanAnEstablishedWindowWithoutBackgroundProbes(): Unit = runBlocking {
        var now=100_000L
        val store=ContextCapabilityStore(context,{now})
        var loads=0
        store.resolve("unknown","direct"){loads++;ContextModelCapabilities.unknown("selected",now)}
        store.resolve("known","direct"){loads++;ContextModelCapabilities.local("selected",now)}
        now+=5L*60*1000-1
        assertNotNull(store.peek("unknown","direct"));assertEquals(2,loads)
        now++
        assertNull(store.peek("unknown","direct"));assertNotNull(store.peek("known","direct"));assertEquals(2,loads)
        val recovered=store.resolve("unknown","direct"){loads++;ContextModelCapabilities.local("selected",now,8192)}
        assertEquals(8192,recovered.contextTokens);assertEquals(3,loads)
    }
}
