/* AntiSocial, GPL-3.0-or-later. Public roundup regression, not a network fixture. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PostLinksTest {
    private fun roundup()=Gson().fromJson(javaClass.getResource("/context-evaluation/whitehouse-summit-roundup.json")!!.readText(),Post::class.java)
    @Test fun cachedRoundupRecoversEveryCompleteDestinationWithoutFetchingShortenedLabels() {
        val post=roundup()
        val links=PostLinks.urls(post)
        assertTrue(links.contains("https://x.com/WHOSTP47/status/2110521283779674185"))
        assertEquals(20,links.filter {it.contains("/status/")}.map {it.substringAfter("/status/").substringBefore('#')}.distinct().size)
        assertTrue(links.none {it.contains('…')})
        assertEquals(12,post.links!!.size)
    }
    @Test fun namedRoundupInventoryFoldsUnderItsIntroductionWithoutLosingSourceCharacters() {
        val intro="In case you missed it, here’s the full lineup from the Science: A New Golden Age Summit!"
        val post=roundup()
        val split=PostBodyDetails.split(post.body)
        assertEquals(intro,split.body)
        assertTrue(split.details.contains("John Martinis:"))
        assertTrue(split.details.contains("@Ben_Reinhardt:"))
        assertEquals(post.body,split.originalText())
    }
    @Test fun linkedExplanationsAreNotMistakenForAListOfNames() {
        val body="The workshop published a complete guide to battery repair. Alice: https://example.org/a Bob: https://example.org/b The important correction is that the fuse must be disconnected before testing: https://example.org/c"
        assertEquals(body,PostBodyDetails.split(body).body)
    }
    @Test fun shortenedRssAnchorLabelsDoNotMakeASecondFullCaption() {
        val post=roundup()
        assertEquals("",PostPresentation.text(post).title)
        assertEquals("Reposted by @WhiteHouse",PostPresentation.text(post).attribution)
    }
    @Test fun aLinkedPostDoesNotIncludeRepliesOrAnUnrelatedRootAsArticleData() {
        val root=roundup();val reply=root.copy(id="reply",canonicalUrl="https://x.com/other/status/12345",body="People are arguing again.")
        val target=Target(Platform.X,TargetKind.POST,"2112289266806006015",root.canonicalUrl)
        assertEquals(root,PostLinks.linkedPost(Page(listOf(reply,root)),target))
        assertNull(PostLinks.linkedPost(Page(listOf(reply)),target))
        assertNull(PostLinks.linkedPost(Page(listOf(root.copy(preview=true))),target))
    }
}
