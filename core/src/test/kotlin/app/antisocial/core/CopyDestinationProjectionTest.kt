/* anti social, GPL-3.0-or-later. Self links and competing source destinations. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CopyDestinationProjectionTest {
    private val text="The community workshop has published its complete maintenance guide for the equipment library. Volunteers documented the replacement parts, compatible connectors, installation steps and repair tools. Readers can inspect the illustrated instructions and reserve a public workbench."
    private val a=Post("81000001",Platform.X,"https://x.com/workshop/status/81000001","workshop",text,publishedAt=1_800_000_000_000L)
    private val b=Post("second",Platform.BLUESKY,"https://bsky.app/profile/workshop.example/post/second","workshop.example",text,publishedAt=1_800_000_002_000L)
    private fun groups(a:Post,b:Post)=CrossPosts.group(listOf(a,b),emptyList())
    @Test fun eachSelfAliasAndAnExplicitOtherOriginalRemainAttributionRatherThanCompetingArticles() {
        for(link in listOf(a.canonicalUrl,"https://twitter.com/workshop/status/81000001?utm_source=share",b.canonicalUrl)) {
            val originals=listOf(a.copy(links=listOf(link)),b.copy(links=listOf(b.canonicalUrl)))
            assertEquals(1,groups(originals[0],originals[1]).size)
            assertEquals(originals.toSet(),groups(originals[0],originals[1]).single().posts.toSet())
        }
    }
    @Test fun genuineSharedAndCompetingArticlesRetainTheirExistingMeaning() {
        val first="https://example.org/guide-A";val second="https://example.org/guide-B"
        assertEquals(1,groups(a.copy(links=listOf(first)),b.copy(links=listOf(first))).size)
        assertEquals(1,groups(a,b.copy(links=listOf(first))).size)
        assertEquals(2,groups(a.copy(links=listOf(first)),b.copy(links=listOf(second))).size)
    }
    @Test fun aKnownAcquiredSelfLinkCannotAuthorizeAnUnrelatedLookalikeHost() {
        val source=a.copy(provenance=Provenance(Source(Platform.X,"nitter","https://reader.example"),1_800_000_000_000L,"https://reader.example/workshop/rss"))
        val other=b.copy(links=listOf("https://example.org/illustrated-guide"))
        assertEquals(1,groups(source.copy(links=listOf("https://reader.example/workshop/status/81000001#m")),other).size)
        assertEquals(2,groups(source.copy(links=listOf("https://lookalike.example/workshop/status/81000001")),other).size)
    }
    @Test fun commentsAndCaseSensitiveDifferentPostsNeverBecomeSelfLinks() {
        val fb=a.copy(platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/workshop/posts/81000001")
        val other=b.copy(links=listOf("https://example.org/guide"))
        val comment=fb.canonicalUrl+"?comment_id=92000002"
        assertFalse(CrossPosts.originalLink(comment,fb))
        // A comment pointer is retained context, not a competing article or
        // account-ownership proof. The legacy collector keeps this full copy.
        val originals=listOf(fb.copy(links=listOf(comment)),other)
        assertEquals(originals.toSet(),groups(originals[0],originals[1]).single().posts.toSet())
        assertEquals(2,groups(a.copy(links=listOf(b.canonicalUrl.replace("second","SECOND"))),other).size)
    }
}
