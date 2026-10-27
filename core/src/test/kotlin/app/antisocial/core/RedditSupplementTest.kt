/* anti social, GPL-3.0-or-later. Synthetic publisher-authored thread context. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class RedditSupplementTest {
    private val time=1793000000000L
    private val title="Public workshop opens a new community equipment room"
    private val body="The volunteer team has finished preparing the new public equipment room. Visitors can inspect the tools, meet the instructors, read the complete schedule, and join a practical demonstration during the afternoon. The full access instructions explain how to reach the entrance and where to leave bicycles."
    private val p=Provenance(Source(Platform.REDDIT,"original","https://www.reddit.com",role=SourceRole.ORIGINAL),time,"https://www.reddit.com/user/fixture/.rss")
    private val parent=Post("post:abc123",Platform.REDDIT,"https://www.reddit.com/user/fixture/comments/abc123/workshop","u/fixture","",title,publishedAt=time,provenance=p)
    private val comment=Post("comment:abc123:def456",Platform.REDDIT,"https://www.reddit.com/r/u_fixture/comments/abc123/workshop/def456","/u/fixture",body,"/u/fixture on $title",publishedAt=time+20_000,provenance=p,links=listOf("https://workshop.example/opening"))
    @Test fun thePublishersOwnThreadSupplementUsesOneTitleOneBodyAndKeepsBothOriginals() {
        for(posts in listOf(listOf(parent,comment),listOf(comment,parent))) {
            val groups=CrossPosts.group(posts,emptyList())
            assertEquals(1,groups.size);assertEquals(posts.toSet(),groups.single().posts.toSet())
            assertTrue(CrossPosts.sameContentCopies(groups.single().posts))
            val fusion=CrossPostFusion.compose(groups.single().posts)!!
            assertEquals(listOf(title,body),fusion.segments.map {it.text})
            assertEquals(setOf(CrossPosts.originalKey(comment)),fusion.segments.last().originalKeys)
        }
    }
    @Test fun ordinaryRepliesOtherAuthorsChangedHeadlinesAndSubstantiveParentCardsStaySeparate() {
        for(c in listOf(comment.copy(author="/u/another"),comment.copy(parentId=parent.id),comment.copy(title="/u/fixture on Another workshop"),
            comment.copy(canonicalUrl=comment.canonicalUrl.replace("abc123","xyz789")),comment.copy(publishedAt=time+600_001),
            comment.copy(provenance=p.copy(finalUrl="https://www.reddit.com/r/u_fixture/.rss"))))
            assertEquals(c.toString(),2,CrossPosts.group(listOf(parent,c),emptyList()).size)
        for(root in listOf(parent.copy(body="Additional entrance instructions."),parent.copy(media=listOf(Media("https://image.example/workshop.jpg"))),parent.copy(links=listOf("https://workshop.example/other"))))
            assertEquals(2,CrossPosts.group(listOf(root,comment),emptyList()).size)
    }
    @Test fun exactAuthoredCopiesCanBringTheirDifferentDiscussionRootsIntoTheCollection() {
        val other=comment.copy(id="comment:xyz789:ghi123",canonicalUrl=comment.canonicalUrl.replace("abc123","xyz789").replace("def456","ghi123"),publishedAt=time+40_000)
        val group=CrossPosts.group(listOf(parent,comment,other),emptyList()).single()
        assertEquals(3,group.posts.size);assertEquals(listOf(title,body),CrossPostFusion.compose(group.posts)!!.segments.map {it.text})
    }
    @Test fun aPublishersLaterEmojiReplyKeepsItsContentWithoutRepeatingTheThreadHeadline() {
        val wave=comment.copy(id="comment:abc123:jkl456",canonicalUrl=comment.canonicalUrl.replace("def456","jkl456"),body="👋",links=emptyList(),publishedAt=time+3_600_000)
        val group=CrossPosts.group(listOf(wave,parent,comment),emptyList()).single()
        assertEquals(3,group.posts.size);assertEquals(listOf(title,body,"👋"),CrossPostFusion.compose(group.posts)!!.segments.map {it.text})
        assertEquals(2,CrossPosts.group(listOf(parent,comment,wave.copy(author="/u/another")),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(parent,comment,wave.copy(publishedAt=time+CrossPosts.MAX_REPEAT_WINDOW+600_001)),emptyList()).size)
    }
}
