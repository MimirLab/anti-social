/* anti social, GPL-3.0-or-later. Content copies do not prove common ownership. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class FacebookAliasCopiesTest {
    private val at=1_800_000_017_000L
    private val caption="See our team make good things for everyone."
    private val api=Source(Platform.FACEBOOK,"original","https://www.facebook.com",role=SourceRole.ORIGINAL)
    private val rss=Source(Platform.FACEBOOK,"rss-bridge","https://feed.example.org",role=SourceRole.ALTERNATE)
    private val full=Post("post:710001",Platform.FACEBOOK,"https://www.facebook.com/700001/posts/710001","Public studio",caption,
        publishedAt=at,media=listOf(Media("https://media.example.org/studio.mp4","video")),provenance=Provenance(api,at+1_000,"https://www.facebook.com/api/graphql/"))
    private val alias=full.copy(id="post:710002",canonicalUrl="https://www.facebook.com/reel/710002",media=emptyList(),provenance=Provenance(rss,at+2_000,"https://feed.example.org/feed"))

    @Test fun aCompleteCompactCaptionAtTheSameInstantCanCollectATextOnlyExtractionAlias() {
        val posts=listOf(full,alias);val before=posts.map {Gson().toJson(it)}
        assertTrue(CrossPosts.sameContentCopies(posts))
        for(order in listOf(posts,posts.reversed())) {
            val group=CrossPosts.group(order,emptyList()).single()
            assertEquals(posts.toSet(),group.posts.toSet())
            val caption=CrossPostFusion.compose(group.posts)!!.segments.single()
            assertEquals(this.caption,caption.text)
            assertEquals(posts.mapTo(hashSetOf(),CrossPosts::originalKey),caption.originalKeys)
        }
        assertEquals(before,posts.map {Gson().toJson(it)})
    }
    @Test fun differingDestinationsClaimsAndUnknownAuthorsCannotBorrowTheAliasRoute() {
        for(changed in listOf(alias.copy(links=listOf("https://other.example.org/notice")),alias.copy(body=caption.replace("good","different")),
            alias.copy(body=caption+" Not approved."),alias.copy(body=caption+" 12."),alias.copy(author="Another studio"),alias.copy(author=""))) {
            assertFalse(CrossPosts.sameContentCopies(listOf(full,changed)))
            assertEquals(2,CrossPosts.group(listOf(full,changed),emptyList()).size)
        }
    }
    @Test fun anotherPublicationInstantAndTwoUnobservedMediaSetsStayOutsideTheCompactRoute() {
        val otherImage=alias.copy(media=listOf(Media("https://media.example.org/another.jpg")))
        for(changed in listOf(alias.copy(publishedAt=at+1_000),alias.copy(publishedAt=null),alias.copy(preview=true),alias.copy(parentId="parent"),
            alias.copy(provenance=alias.provenance!!.copy(source=rss.copy(role=SourceRole.CACHE))),alias.copy(provenance=null),otherImage)) {
            assertFalse(CrossPosts.sameContentCopies(listOf(full,changed)))
            assertEquals(2,CrossPosts.group(listOf(full,changed),emptyList()).size)
        }
    }
    @Test fun ordinaryShortGreetingsAndCrossPlatformNamesNeedTheirExistingEvidence() {
        val short=full.copy(body="Have a wonderful day together.")
        assertFalse(CrossPosts.sameContentCopies(listOf(short,alias.copy(body=short.body))))
        assertFalse(CrossPosts.sameContentCopies(listOf(full,alias.copy(platform=Platform.X,canonicalUrl="https://x.com/publicstudio/status/710002"))))
        assertFalse(CrossPosts.sameContentCopies(listOf(full.copy(media=emptyList()),alias)))
    }
    @Test fun aProvenAliasCannotBecomeACompetingCandidateAndDisplaceAnotherPlatform() {
        val instagram=full.copy(id="post:FixtureAlias",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/FixtureAlias/",publishedAt=at+1_000,
            media=listOf(Media("https://media.example.org/studio.jpg")),authorAccount=PostAuthorAccounts.instagram("publicstudio"))
        val follows=listOf(Follow("fb",Target(Platform.FACEBOOK,TargetKind.PROFILE,"publicstudio","https://www.facebook.com/publicstudio"),"Public studio",personId="studio"),
            Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"publicstudio","https://www.instagram.com/publicstudio/"),"Public studio",personId="studio"))
        val posts=listOf(full,alias,instagram);val membership=posts.associate {CrossPosts.originalKey(it)to setOf(if(it.platform==Platform.FACEBOOK)"fb"else "ig")}
        for(order in listOf(posts,posts.reversed(),listOf(alias,instagram,full))) {
            val groups=CrossPosts.group(order,follows,membership)
            assertEquals(1,groups.size);assertEquals(posts.toSet(),groups.single().posts.toSet())
        }
    }
}
