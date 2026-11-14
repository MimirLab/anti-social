/* anti social, GPL-3.0-or-later. Synthetic workshop announcements. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class CuratedImageRepostsTest {
    private val at=1_790_000_000_000L
    private val image="https://pbs.twimg.com/media/WorkshopPosterA.jpg?name=large"
    private val source=Source(Platform.X,"nitter","https://nitter.workshop.invalid")
    private val root=Post("883101",Platform.X,"https://x.com/asworkshop/status/883101","Workshop office",
        "The neighborhood repair workshop has published its equipment handbook. Visitors can read the checkout procedure, inspect current maintenance records, ask the coordinators about accessible tools, and compare the room schedule before reserving a workbench.",
        publishedAt=at,media=listOf(Media(image)),provenance=Provenance(source,at+10_000,"https://nitter.workshop.invalid/asworkshop?cursor=next-page"))
    private val child=Post("883102",Platform.X,"https://x.com/asvolunteer/status/883102","Workshop volunteer",
        "Repair should be practical. I use four tools at this workshop when helping neighbors mend their household equipment. The volunteer session supplies a useful place to compare repair methods and share experience.",
        publishedAt=at+70*60_000,media=listOf(Media(image)),provenance=Provenance(source,at+71*60_000,"https://nitter.workshop.invalid/asworkshop#m"),
        publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/asworkshop","Workshop office")))
    private val follows=listOf(
        Follow("workshop-x",Target(Platform.X,TargetKind.PROFILE,"asworkshop","https://x.com/asworkshop"),"Workshop office",personId="workshop"),
        Follow("workshop-facebook",Target(Platform.FACEBOOK,TargetKind.PROFILE,"asworkshop","https://www.facebook.com/asworkshop"),"Workshop office",personId="workshop"),
        Follow("workshop-instagram",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"asworkshop","https://www.instagram.com/asworkshop/"),"Workshop office",personId="workshop"))
    private val facebook=root.copy(id="883103",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/asworkshop/posts/883103",publishedAt=at+60_000,
        media=listOf(Media("https://photos.fixture.invalid/workshop-facebook.jpg")),provenance=null)
    private val instagram=root.copy(id="883104",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/WorkshopPosterA/",publishedAt=at+62_000,
        media=listOf(Media("https://photos.fixture.invalid/workshop-instagram.jpg")),provenance=null)
    private fun groups(posts:List<Post>,accounts:List<Follow> = follows):List<PostGroup> {
        val memberships=posts.associate {post->CrossPosts.originalKey(post) to setOf(when(post.platform) {
            Platform.FACEBOOK->"workshop-facebook";Platform.INSTAGRAM->"workshop-instagram";else->"workshop-x"
        })}
        return CrossPosts.group(posts,accounts,memberships)
    }

    @Test fun anExplicitCuratorCanCollectTheSamePictureWithoutEquatingItsAuthors() {
        assertTrue(CrossPosts.curatedQuotationSource(child,root))
        val posts=listOf(root,child);val before=posts.map(Gson()::toJson)
        for(order in listOf(posts,posts.reversed()))assertEquals(posts.toSet(),groups(order).single().posts.toSet())
        assertEquals(before,posts.map(Gson()::toJson))
        assertNotEquals(CrossPosts.originalAccount(root),CrossPosts.originalAccount(child))
        assertFalse(CrossPosts.sameContentCopies(posts))
    }

    @Test fun existingCrossPlatformCaptionEditionsStayWithTheirDirectPictureWitness() {
        val posts=listOf(root,facebook,instagram,child)
        for(order in listOf(posts,posts.reversed(),listOf(child,instagram,root,facebook)))assertEquals(posts.toSet(),groups(order).single().posts.toSet())
    }

    @Test fun portableSourceBlocksReadEachCompleteCaptionOnceAndKeepEveryOriginalPath() {
        val posts=listOf(root,facebook,instagram,child)
        assertTrue(CrossPosts.curatedImageCollection(posts))
        val fusion=assertNotNull(CrossPostFusion.composeCollectedEditions(posts))
        val text=fusion.segments.joinToString("\n"){it.text}
        for(caption in listOf(root.body,child.body))assertEquals(1,Regex(Regex.escape(caption)).findAll(text).count())
        for(post in posts) {
            val selected=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}
            assertEquals(PostPresentation.content(post),selected.joinToString("\n"){it.text})
        }
        assertFalse(CrossPosts.sameContentCopies(posts))
    }

    @Test fun curationCannotChainThroughAnIntermediateAuthorsDifferentSharingRole() {
        val further=child.copy(id="883105",canonicalUrl="https://x.com/asrepairclub/status/883105",author="Repair club",
            body="Our repair club uses this workshop for its regular skills exchange. Members bring their own projects and compare practical repair techniques with the visiting volunteers.",
            publishedAt=at+75*60_000,provenance=Provenance(source,at+76*60_000,"https://nitter.workshop.invalid/asvolunteer#m"),
            publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/asvolunteer","Workshop volunteer")))
        val posts=listOf(root,child,further)
        assertFalse(CrossPosts.curatedImageCollection(posts))
        assertEquals(2,groups(posts).size)
        assertEquals(posts.toSet(),groups(posts).flatMap {it.posts}.toSet())
    }

    @Test fun twoDifferentOwnAnnouncementsLeaveAReusedPosterAssignmentUnresolved() {
        val other=root.copy(id="883106",canonicalUrl="https://x.com/asworkshop/status/883106",publishedAt=at+5*60_000,
            body="The community choir has published the rehearsal calendar for its winter concert. Singers should review the score, check the rehearsal times, and contact the conductor about the performance arrangements.")
        val posts=listOf(root,other,child)
        assertFalse(CrossPosts.curatedImageCollection(posts))
        assertEquals(3,groups(posts).size)
        assertEquals(posts.toSet(),groups(posts.reversed()).flatMap {it.posts}.toSet())
    }

    @Test fun contraryObservationsAndDifferentResourcesDoNotBecomeOnePictureProof() {
        fun frame(value:Int)=VisualFrames.encode(1.0,64.0,0,0,List(48){value},ByteArray(4096){value.toByte()})!!
        val observed=root.copy(media=listOf(Media(image,visualFingerprint=frame(0))))
        val changed=child.copy(media=listOf(Media(image,visualFingerprint=frame(255))))
        assertFalse(CrossPosts.curatedImageCollection(listOf(observed,changed)))
        assertEquals(2,groups(listOf(observed,changed)).size)
        val variants=listOf(
            child.copy(links=listOf("https://booking.fixture.invalid/different-session")),
            child.copy(media=listOf(Media(image,sourceLayout=SourceImageLayout(1000,1000,1,2)))),
            child.copy(avatar=image),
            child.copy(canonicalUrl="https://unrelated.fixture.invalid/883102"),
            child.copy(media=listOf(Media(image.replace("name=large","name=small")))))
        for(post in variants)assertFalse(CrossPosts.curatedImageCollection(listOf(root,post)))
        val thumbnail="https://pbs.twimg.com/amplify_video_thumb/883100/img/WorkshopPosterA.jpg"
        assertFalse(CrossPosts.curatedImageCollection(listOf(root.copy(media=listOf(Media(thumbnail))),child.copy(media=listOf(Media(thumbnail))))))
    }

    @Test fun identicalShortRootEditionsKeepTheirIndependentSourcePaths() {
        val first=root.copy(body="Workshop tools ready.")
        val other=first.copy(id="883107",canonicalUrl="https://x.com/asworkshop/status/883107",publishedAt=at+60_000)
        val posts=listOf(first,other,child)
        assertTrue(CrossPosts.curatedImageCollection(posts))
        val fusion=assertNotNull(CrossPostFusion.composeCollectedEditions(posts))
        for(post in posts)assertEquals(PostPresentation.content(post),fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString("\n"){it.text})
        assertEquals(posts.toSet(),groups(posts).single().posts.toSet())
    }

    @Test fun independentlyFollowingTheOriginalAuthorDoesNotEraseThePublishedCuratorRole() {
        val author=Follow("volunteer-x",Target(Platform.X,TargetKind.PROFILE,"asvolunteer","https://x.com/asvolunteer"),"Workshop volunteer",personId="volunteer")
        val posts=listOf(root,child)
        assertEquals(posts.toSet(),groups(posts,follows+author).single().posts.toSet())
        assertEquals(2,groups(listOf(root,child.copy(publicRelations=null)),follows+author).size)
    }

    @Test fun aDisplayLabelOrUnrelatedAcquisitionCannotClaimCuratorship() {
        val cases=listOf(
            child.copy(publicRelations=null),
            child.copy(publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/asother","Workshop office"))),
            child.copy(provenance=child.provenance!!.copy(finalUrl="https://nitter.workshop.invalid/asother#m")),
            child.copy(provenance=child.provenance!!.copy(source=source.copy(root="https://different.fixture.invalid"))),
            child.copy(provenance=child.provenance!!.copy(source=source.copy(frontend="unqualified"))),
            child.copy(provenance=null),child.copy(preview=true),child.copy(parentId="other-post"),
            child.copy(recoveredArchive=ArchiveRecovery("fixture",at,"https://archive.fixture.invalid/capture")))
        for(post in cases)assertEquals(2,groups(listOf(root,post)).size)
    }

    @Test fun differentPicturesGalleriesAndDistantReusesStaySeparate() {
        val cases=listOf(
            child.copy(media=listOf(Media(image.replace("PosterA","PosterB")))),
            child.copy(media=listOf(Media(image),Media("https://photos.fixture.invalid/second.jpg"))),
            child.copy(media=listOf(Media(image,"video"))),
            child.copy(publishedAt=at+2*60*60_000+1),child.copy(publishedAt=null),
            child.copy(body="This is false. The picture is misleading."))
        for(post in cases)assertEquals(2,groups(listOf(root,post)).size)
        for(url in listOf("https://pbs.twimg.com/profile_images/WorkshopPosterA.jpg","https://photos.fixture.invalid/logo.jpg")) {
            assertEquals(2,groups(listOf(root.copy(media=listOf(Media(url))),child.copy(media=listOf(Media(url))))).size)
        }
        // The already-qualified declared-video route remains useful; a
        // recording thumbnail must not be reclassified as an ordinary photo.
        val thumbnail="https://pbs.twimg.com/amplify_video_thumb/883100/img/WorkshopPosterA.jpg"
        assertEquals(1,groups(listOf(root.copy(media=listOf(Media(thumbnail))),child.copy(media=listOf(Media(thumbnail))))).size)
    }
}
