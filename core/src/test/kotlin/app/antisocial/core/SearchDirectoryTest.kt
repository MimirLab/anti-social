package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchDirectoryTest {
    private val target=Target(Platform.REDDIT,TargetKind.SEARCH,"gardening","https://www.reddit.com/search?q=gardening")
    private fun community(name: String)=Target(Platform.REDDIT,TargetKind.COMMUNITY,"r/$name","https://www.reddit.com/r/$name")
    private fun person(name: String)=Target(Platform.REDDIT,TargetKind.PROFILE,"user/$name","https://www.reddit.com/user/$name")
    private fun post(id: Int)=Post("post:$id",Platform.REDDIT,"https://www.reddit.com/r/gardening/comments/$id/real/","reader","Actual post $id",publishedAt=id.toLong())

    @Test fun aBlockedDirectoryCannotHoldPostCompletionOrItsNextCursor(): Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var pages=0;var directories=0
        val session=SearchFeed(target,discover={previous->pages++
            when(pages) {
                1->Page(listOf(post(1)),next="real-post-cursor-1")
                2->{assertEquals("real-post-cursor-1",previous?.next);Page(listOf(post(2)),next="real-post-cursor-2")}
                else->{assertEquals("real-post-cursor-2",previous?.next);Page(listOf(post(3)))}
            }
        },read={error("Native posts need no hydration")},directory={
            directories++;entered.complete(Unit);release.await()
            Page(listOf(post(999)),next="not-a-post-cursor",accounts=listOf(Profile(community("gardening"),"Gardeners","Actual directory description")))
        })
        val directoryJob=launch {session.loadDirectory {}}
        entered.await()
        withTimeout(2000){session.loadNext {}}
        assertFalse(session.state.loading);assertTrue(session.state.hasMore);assertTrue(directoryJob.isActive)
        assertEquals(listOf("post:2","post:1"),session.state.page.posts.map {it.id})
        withTimeout(2000){session.loadNext {}}
        assertEquals(3,pages);assertFalse(session.state.hasMore);assertTrue(directoryJob.isActive)
        release.complete(Unit);directoryJob.join()
        assertEquals(listOf("post:3","post:2","post:1"),session.state.page.posts.map {it.id})
        assertEquals(TargetKind.COMMUNITY,session.state.page.accounts.single().target.kind)
        assertFalse(session.state.loading);assertFalse(session.state.hasMore);assertNull(session.state.error)
        session.loadDirectory {};assertEquals(1,directories)
    }

    @Test fun directoryFailureIsQuietAndDoesNotRetryEveryPostBlock(): Unit=runBlocking {
        var attempts=0
        val session=SearchFeed(target,discover={Page(listOf(post(1)))},read={error("No hydration")},directory={attempts++;error("Optional directory unavailable")})
        session.loadNext {};val before=session.state
        session.loadDirectory {};session.loadDirectory {}
        assertEquals(before,session.state);assertEquals(1,attempts);assertNull(session.state.error)
    }

    @Test fun cancellingPendingDirectoryCanContinueAfterPostPaginationHasAlreadyEnded(): Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();var attempts=0;var posts=0
        val session=SearchFeed(target,discover={posts++;Page(listOf(post(1)))},read={error("No hydration")},directory={
            attempts++;if(attempts==1){entered.complete(Unit);awaitCancellation()}
            Page(emptyList(),accounts=listOf(Profile(community("gardening"),"Gardeners")))
        })
        session.loadNext {};assertFalse(session.state.hasMore)
        val job=launch {session.loadDirectory {}};entered.await();job.cancelAndJoin();session.pause()
        assertTrue(session.state.paused)
        session.loadNext {};assertFalse(session.state.paused);assertFalse(session.state.loading)
        session.loadDirectory {};session.loadDirectory {}
        assertEquals(2,attempts);assertEquals(1,posts)
        assertEquals("Gardeners",session.state.page.accounts.single().name)
        assertEquals("post:1",session.state.page.posts.single().id);assertFalse(session.state.hasMore)
    }

    @Test fun communityCandidatesNeedTheirActualMatchingMetadataAndNeverBecomePosts(): Unit=runBlocking {
        val first=community("gardening");val other=community("composting")
        val session=SearchFeed(target,discover={Page(emptyList(),collections=listOf(Collection(first,"Indexed title",detail="Untrusted snippet"),Collection(other,"Other index title")))},read={requested->
            if(requested==first)Page(emptyList(),profile=Profile(first,"Actual community","Its actual description"))
            else Page(emptyList(),profile=Profile(community("unrelated"),"Wrong community"))
        })
        session.loadNext {}
        assertTrue(session.state.page.posts.isEmpty());assertTrue(session.state.page.collections.isEmpty())
        assertEquals("Actual community",session.state.page.accounts.single().name)
        assertEquals("Its actual description",session.state.page.accounts.single().description)
        assertEquals(1,session.state.unavailable)
    }

    @Test fun directoryAndNativeCommunitiesShareOneBoundedShelfWithoutChangingPostQuota(): Unit=runBlocking {
        val session=SearchFeed(target,discover={Page(listOf(post(1)),accounts=listOf(Profile(community("Gardening"),"Native gardeners"),Profile(person("reader"),"Reader")))},read={error("No reads")},maxAccounts=2,directory={
            Page(emptyList(),accounts=listOf(Profile(community("gardening"),"Same community indexed elsewhere"),Profile(community("composting"),"Composting"),Profile(person("reader"),"Duplicate person")))
        })
        session.loadDirectory {};assertEquals(2,session.state.page.accounts.size)
        session.loadNext {}
        assertEquals(2,session.state.page.accounts.size)
        assertEquals(setOf("Native gardeners","Reader"),session.state.page.accounts.map {it.name}.toSet())
        assertEquals(1,session.state.page.posts.size);assertFalse(session.state.limited)
    }

    @Test fun directoryBudgetAndScopeCannotStopOrBroadenTheMainPostSearch(): Unit=runBlocking {
        var calls=0
        val session=SearchFeed(target,discover={Page(listOf(post(1)))},read={error("No reads")},maxCharacters=1800,directory={Page(emptyList(),accounts=listOf(
            Profile(community("gardening"),"Huge metadata","x".repeat(4000)),Profile(person("reader"),"Reader"),
            Profile(Target(Platform.X,TargetKind.PROFILE,"other","https://x.com/other"),"Other platform")))})
        session.loadDirectory {};session.loadNext {}
        assertEquals("Reader",session.state.page.accounts.single().name);assertFalse(session.state.limited)
        val scoped=SearchScopes.apply(target,SearchScope(SearchScopeKind.COMMUNITY,"gardening"))
        val contained=SearchFeed(scoped,discover={Page(listOf(post(1)))},read={error("No reads")},directory={calls++;Page(emptyList(),accounts=listOf(Profile(community("unrelated"),"Unrelated")))})
        contained.loadDirectory {};assertEquals(0,calls);assertTrue(contained.state.page.accounts.isEmpty())
    }
}
