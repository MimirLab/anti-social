package app.antisocial.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Refresh must enrich a discovery, rather than discard already acquired evidence. */
@RunWith(AndroidJUnit4::class)
class DiscoveryRefreshNativeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as AntiSocialApplication).repo
    @Before fun prepare() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("antisocialIsolatedFixtureDevice")=="true")
        runBlocking {repo.awaitReady()}
    }
    private fun card(seed:Profile,handle:String,at:Long=System.currentTimeMillis()):AdjacentCard {
        val profile=Profile(repo.routes.target(Platform.X,handle),"Workshop $handle")
        return AdjacentCard(AdjacentDiscovery.key(profile.target),profile,listOf(AdjacentEvidence(AdjacentKind.FEATURED,seed.target,seed.target.canonicalUrl,at)),100.0)
    }
    @Test fun checkingAgainRetainsCachedCardsWhileForcingFreshCollection()=runBlocking {
        val seed=Profile(repo.routes.target(Platform.X,"refresh_fixture"),"Workshop")
        val policy=repo.gateway.currentPolicy()
        val key="v2|"+AdjacentDiscovery.key(seed.target)+"|"+policy+"|"+repo.prefs.originalFallback
        val file=File(repo.context.cacheDir,"adjacent-discovery/${Repository.hash(key)}.json")
        try {
            val cards=listOf(card(seed,"first_fixture"),card(seed,"second_fixture"))
            repo.adjacentStore.put(key,AdjacentSnapshot(cards,complete=true),repo.adjacentStore.epoch())
            val normal=repo.adjacentSession(seed)
            assertEquals(cards.map {it.key},normal.session.current().cards.map {it.key})
            val refreshed=repo.adjacentSession(seed,force=true)
            assertEquals("Checking again must retain useful rows before new data arrives",cards.map {it.key},refreshed.session.current().cards.map {it.key})
            assertFalse(refreshed.fresh)
            assertTrue(refreshed.session.current().hasMore)
        }finally {file.delete()}
    }
    @Test fun partialRefreshCannotReplaceAnAlreadyRicherNeighborhood() {
        val directory=File(ui.activity.cacheDir,"refresh-cache-${System.nanoTime()}")
        val cache=AdjacentStore(directory);val seed=Profile(repo.routes.target(Platform.X,"refresh_fixture"),"Workshop")
        try {
            val first=card(seed,"first_fixture");val second=card(seed,"second_fixture");val third=card(seed,"third_fixture")
            val epoch=cache.epoch()
            cache.put("fixture",AdjacentSnapshot(listOf(first,second),complete=true),epoch)
            cache.put("fixture",AdjacentSnapshot(listOf(second,third),hasMore=true,complete=true),epoch)
            assertEquals(listOf(first.key,second.key,third.key),cache.get("fixture")!!.snapshot.cards.map {it.key})
        }finally {directory.deleteRecursively()}
    }
    @Test fun checkingAgainKeepsEarlierBridgeMethodsWhenTheNextSampleIsSmaller() {
        val seed=Profile(repo.routes.target(Platform.X,"refresh_fixture"),"Workshop")
        val channel=PublicAccount("youtube","https://www.youtube.com/channel/UC${"a".repeat(22)}",id="UC${"a".repeat(22)}")
        val peers=listOf('b','c').map {PublicAccount("youtube","https://www.youtube.com/channel/UC${it.toString().repeat(22)}",id="UC${it.toString().repeat(22)}")}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);var checks=0
        val controller=AdjacentController(repo,scope,
            create={p,_,_->AdjacentRuntime(AdjacentSession(p,repo.routes,read={error("No fixture network")}),"same-policy",0,repo.gateway.currentPolicy(),false)},
            identities={p->AccountDiscovery(p.name,listOf(AccountCandidate(p.target,"Opened",AccountLinkKind.ENTERED)),publicAccounts=if(p.target==seed.target)listOf(channel)else emptyList())},
            next={_,update->AdjacentSnapshot(complete=true).also(update)},roots={emptyList()},
            bridge={account,related,force->if(related){checks++;BridgePage(account,neighbors=(if(force)peers.takeLast(1)else peers).map {BridgeNeighbor(it,AdjacentKind.FEATURED,"Creator selected",account.url)},checkedAt=System.currentTimeMillis())}else BridgePage(account,checkedAt=System.currentTimeMillis())})
        try {
            ui.runOnIdle {controller.start(seed)}
            ui.waitUntil(5000){checks==1&&!controller.state.value.loading}
            assertEquals(2,controller.state.value.bridgeMethods.single().page.neighbors.size)
            ui.runOnIdle {controller.refresh()}
            ui.waitUntil(5000){checks==2&&!controller.state.value.loading}
            assertEquals(2,controller.state.value.bridgeMethods.single().page.neighbors.size)
        }finally {ui.runOnIdle {controller.stop();scope.cancel()}}
    }
}
