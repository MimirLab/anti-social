/* anti social, GPL-3.0-or-later. Reader checkpoint and backpressure qualification. */
package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchProgressTest {
    private val target=Target(Platform.INSTAGRAM,TargetKind.SEARCH,"fixture","https://www.instagram.com/search/?q=fixture")
    private val source=Source(Platform.INSTAGRAM,"librey","https://index.example")
    private fun destination(n:Int)=Target(target.platform,TargetKind.POST,"Fixture$n","https://www.instagram.com/p/Fixture$n/")
    private fun page(ids:List<Int>,next:String?)=Page(emptyList(),next=next,provenance=Provenance(source,1,source.root),collections=ids.map {Collection(destination(it),"Index hint")})
    private fun post(t:Target)=Page(listOf(Post(t.identity,t.platform,t.canonicalUrl,"Fixture","Useful result ${t.identity}",publishedAt=1)))
    private suspend fun partial(page:Page,validate:()->Unit={})=requireNotNull(currentCoroutineContext()[SearchDiscoveryUpdates]).publish(page,validate)

    @Test fun deltasKeepAllDestinationsBeyondTheFinalPagesSixtyItemProjection():Unit=runBlocking {
        var calls=0;val reads=mutableListOf<String>()
        val feed=SearchFeed(target,discover={previous->
            calls++
            if(previous==null)page(listOf(0),"first")else {
                for(group in (1..90).chunked(30))partial(page(group,"checkpoint"))
                page((1..60).toList(),null)
            }
        },read={reads+=it.canonicalUrl;post(it)},maxPages=2)
        withTimeout(3_000){while(feed.state.hasMore)feed.loadNext {}}
        assertEquals(91,feed.state.page.posts.size);assertEquals(91,reads.distinct().size);assertEquals(91,reads.size)
        assertEquals(2,calls);assertEquals(0,feed.state.unavailable)
    }
    @Test fun aPartialAndItsIdenticalTerminalCursorDoNotEndTheNextPage():Unit=runBlocking {
        val previous=mutableListOf<String?>()
        val feed=SearchFeed(target,discover={p->
            previous+=p?.next
            when(previous.size) {
                1->page(listOf(0),"first")
                2->{partial(page(listOf(1),"second"));page(listOf(1),"second")}
                3->{assertEquals("second",p?.next);page(listOf(2),null)}
                else->error("Extra discovery")
            }
        },read={post(it)})
        withTimeout(2_000){feed.loadNext {};feed.loadNext {}}
        assertEquals(listOf(null,"first","second"),previous);assertEquals(3,feed.state.page.posts.size);assertFalse(feed.state.hasMore)
    }
    @Test fun malformedPartialSourcesAndExpiredPublicationChecksCannotMutateTheReader():Unit=runBlocking {
        for(mode in 0..2) {
            val feed=SearchFeed(target,discover={previous->
                if(previous==null)page(listOf(0),"first")else {
                    val incoming=page(listOf(1),"second")
                    when(mode) {
                        0->partial(incoming.copy(provenance=null))
                        1->partial(incoming.copy(provenance=Provenance(source.copy(root="https://wrong.example"),1,"https://wrong.example")))
                        else->partial(incoming){throw CancellationException("The connection changed")}
                    }
                    error("Rejected publication must stop this operation")
                }
            },read={post(it)})
            withTimeout(2_000){feed.loadNext {}}
            assertEquals(listOf(destination(0).canonicalUrl),feed.state.page.posts.map {it.canonicalUrl})
            assertFalse(feed.state.loading);assertNotNull(feed.state.error);assertTrue(feed.state.hasMore)
        }
    }
    @Test fun internalPolicyCancellationReturnsAnHonestStateWithoutStrandingTheLoad():Unit=runBlocking {
        val feed=SearchFeed(target,discover={throw CancellationException("Search connection changed")},read={post(it)})
        withTimeout(1_000){feed.loadNext {}}
        assertFalse(feed.state.loading);assertEquals("Search connection changed",feed.state.error);assertTrue(feed.state.hasMore)
    }
}
