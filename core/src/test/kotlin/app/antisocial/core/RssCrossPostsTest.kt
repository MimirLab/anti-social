/* AntiSocial, GPL-3.0-or-later. Feed identity and dates never establish account ownership. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class RssCrossPostsTest {
    @Test fun exactRssSelfLinksRemainAttributionWhileOtherArticleDestinationsStayDistinct() {
        val text="Our workshop has published the complete battery replacement instructions for everyone to read. The guide describes the tools, preparation, removal, installation, and safety checks for this repair. Visit RepairFixture.test for details."
        val x=Post("123",Platform.X,"https://x.com/repairworkshop/status/123","Repair workshop",text,publishedAt=1_792_152_000_000L,links=listOf("https://RepairFixture.test"))
        val rss=Post("blog-one",Platform.RSS,"https://blog.example/repair-notes","Repair workshop",text,publishedAt=x.publishedAt!!+1_000,links=listOf("https://blog.example/repair-notes"))
        assertEquals(1,CrossPosts.group(listOf(rss,x),emptyList()).size)
        assertEquals(2,CrossPosts.group(listOf(rss.copy(links=listOf("https://blog.example/other-notes")),x),emptyList()).size)
    }

    @Test fun datedCopiesAndUndatedCopiesWithAnExactOriginalReferenceCanMergeWithoutInventingDates() {
        val caption="At our public repair workshop, neighbors learned how to replace a laptop battery, organize the screws, disconnect the old pack, and safely fit the replacement before closing the cover. Everyone can inspect the detailed repair notes and follow the next community session."
        val time=1_792_152_000_000L
        val x=Post("123",Platform.X,"https://x.com/repairworkshop/status/123","Repair workshop",caption,publishedAt=time)
        val rss=Post("blog-one",Platform.RSS,"https://blog.example/repair-notes","Repair workshop",caption,publishedAt=time-1_000,links=listOf(x.canonicalUrl))
        val blog=Follow(RssSources.followId("https://blog.example/feed"),RssSources.target("https://blog.example/feed"),"Repair workshop",feedUrl="https://blog.example/feed",personId="explicit-workshop")
        val social=Follow("workshop-x",Target(Platform.X,TargetKind.PROFILE,"repairworkshop","https://x.com/repairworkshop"),"Repair workshop",personId="explicit-workshop")
        val accounts=listOf(blog,social)
        val memberships=mapOf(PersonalChannels.postKey(rss) to setOf(blog.id),PersonalChannels.postKey(x) to setOf(social.id))
        val dated=CrossPosts.group(listOf(rss,x),accounts,memberships)
        assertEquals(1,dated.size);assertEquals(setOf(rss,x),dated.single().posts.toSet())
        val undatedBlog=rss.copy(publishedAt=null)
        val referenced=CrossPosts.group(listOf(undatedBlog,x),accounts,memberships)
        assertEquals(1,referenced.size);assertEquals(setOf(undatedBlog,x),referenced.single().posts.toSet())
        assertEquals(x,referenced.single().primary)
        assertEquals(1,CrossPosts.group(listOf(rss,x.copy(publishedAt=null)),accounts,memberships).size)
        assertEquals(2,CrossPosts.group(listOf(undatedBlog.copy(links=emptyList()),x),accounts,memberships).size)
        assertEquals(2,CrossPosts.group(listOf(rss.copy(links=emptyList()),x.copy(publishedAt=null)),accounts,memberships).size)
        assertEquals(listOf(undatedBlog),CrossPosts.group(listOf(undatedBlog),accounts,memberships).single().posts)
    }
}
