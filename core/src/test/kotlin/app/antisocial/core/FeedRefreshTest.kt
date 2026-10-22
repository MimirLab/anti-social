package app.antisocial.core

import org.junit.Test
import org.junit.Assert.*

class FeedRefreshTest {
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val provenance=Provenance(source,1000,"https://reader.example/user")
    private val post=Post("1",Platform.X,"https://x.com/user/status/1","user","Public post",publishedAt=900,provenance=provenance)
    @Test fun freshPublicTimelineQualifiesEvenWithAnHonestCoverageNotice() {
        assertNull(FeedRefresh.pendingReason(Page(listOf(post),next="more",provenance=provenance,notice="Only public posts are available."),Platform.X))
    }
    @Test fun reachableEmptyAndProfileOnlySourcesRemainPending() {
        assertNotNull(FeedRefresh.pendingReason(Page(emptyList(),provenance=provenance),Platform.X))
        assertNotNull(FeedRefresh.pendingReason(Page(emptyList(),profile=Profile(Target(Platform.X,TargetKind.PROFILE,"user","https://x.com/user"),"user"),provenance=provenance),Platform.X))
    }
    @Test fun previewsUndatedRepliesOtherPlatformsAndRecoveredCopiesAreNotFreshFeedChecks() {
        for(value in listOf(post.copy(preview=true),post.copy(publishedAt=null),post.copy(parentId="parent"),post.copy(platform=Platform.REDDIT),post.copy(body=""),post.copy(recoveredArchive=ArchiveRecovery("archive",100,"https://archive.example/1"))))
            assertNotNull(FeedRefresh.pendingReason(Page(listOf(value),provenance=provenance),Platform.X))
    }
    @Test fun cachedAndIndexedResultsCannotClearPending() {
        assertNotNull(FeedRefresh.pendingReason(Page(listOf(post),provenance=provenance.copy(cached=true)),Platform.X))
        assertNotNull(FeedRefresh.pendingReason(Page(listOf(post.copy(provenance=provenance.copy(cached=true)))),Platform.X))
        assertNotNull(FeedRefresh.pendingReason(Page(listOf(post),provenance=provenance.copy(source=source.copy(frontend="librey"))),Platform.X))
        assertNotNull(FeedRefresh.pendingReason(Page(listOf(post.copy(provenance=null))),Platform.X))
    }
    @Test fun invalidPartialRowsCannotReplaceRetainedFeedPosts() {
        val partials=listOf(post.copy(preview=true),post.copy(provenance=provenance.copy(cached=true)),post.copy(platform=Platform.REDDIT))
        assertTrue(FeedRefresh.posts(Page(partials,provenance=provenance),Platform.X).isEmpty())
        assertEquals(listOf(post),FeedRefresh.posts(Page(partials+post,provenance=provenance),Platform.X))
        assertTrue(FeedRefresh.posts(Page(listOf(post),provenance=provenance.copy(cached=true)),Platform.X).isEmpty())
    }
    @Test fun validatedOrdinaryFeedEntriesCanOmitOptionalDatesWithoutInventingTimestamps() {
        val feedSource=Source(Platform.RSS,"rss","https://blog.example",role=SourceRole.ORIGINAL)
        val feedProvenance=Provenance(feedSource,1000,"https://blog.example/feed")
        val undated=Post("one",Platform.RSS,"https://blog.example/one","Author","Feed content",provenance=feedProvenance)
        val page=Page(listOf(undated),provenance=feedProvenance)
        assertEquals(listOf(undated),FeedRefresh.posts(page,Platform.RSS))
        assertNull(FeedRefresh.pendingReason(page,Platform.RSS));assertNull(FeedRefresh.posts(page,Platform.RSS).single().publishedAt)
        assertTrue(FeedRefresh.posts(page.copy(posts=listOf(undated.copy(provenance=feedProvenance.copy(source=feedSource.copy(frontend="original"))))),Platform.RSS).isEmpty())
        assertTrue(FeedRefresh.posts(page.copy(provenance=feedProvenance.copy(cached=true)),Platform.RSS).isEmpty())
    }
    @Test fun aResolvedNativePostMayBeRetainedWithoutCertifyingAnIndexedTimeline() {
        val indexed=provenance.copy(source=source.copy(frontend="librey"))
        val page=Page(listOf(post,post.copy(canonicalUrl="https://x.com/user/status/2",provenance=indexed)),provenance=indexed)
        assertEquals(listOf(post),FeedRefresh.posts(page,Platform.X))
        assertNotNull(FeedRefresh.pendingReason(page,Platform.X))
    }
}
