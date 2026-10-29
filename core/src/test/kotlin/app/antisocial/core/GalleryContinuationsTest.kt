package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class GalleryContinuationsTest {
    private val at=1_800_000_000_000L
    private val text="Six detailed workshop repairs show the connectors and their completed assemblies."
    private val media=(0..5).map {Media("https://pictures.example.org/repair-$it.jpg")}
    private val root=Post("20",Platform.X,"https://x.com/publiclab/status/20","Public lab",text,publishedAt=at,media=media.take(4))
    private val peer=Post("Gallery",Platform.INSTAGRAM,"https://www.instagram.com/p/Gallery/","Public lab",text,publishedAt=at+1000,media=media)
    private val child=Post("21",Platform.X,"https://x.com/publiclab/status/21","Public lab","",publishedAt=at+2000,media=media.drop(4),parentId=root.id)
    private val follows=listOf(Follow("x",Target(Platform.X,TargetKind.PROFILE,"publiclab","https://x.com/publiclab"),"Lab",personId="lab"),
        Follow("ig",Target(Platform.INSTAGRAM,TargetKind.PROFILE,"publiclab","https://www.instagram.com/publiclab/"),"Lab",personId="lab"))
    private val members=mapOf(root.identityKey to setOf("x"),peer.identityKey to setOf("ig"))
    private fun plans(posts:List<Post> = listOf(root,peer),accounts:List<Follow> = follows)=CrossPosts.galleryContinuationRequests(listOf(PostGroup(posts)),accounts,members)
    @Test fun onlyAnObservedIncompleteLinkedFourImageEditionAsksForItsOwnContinuation() {
        val request=plans().single()
        assertEquals(root,request.root);assertEquals(peer,request.peer);assertEquals(setOf("x"),request.followIds)
        assertEquals(follows.map {it.copy(name="",personName=null)}.toSet(),request.bindings.toSet())
        assertEquals("A display-name refresh cannot restart an identical acquisition",request,plans(accounts=follows.map {it.copy(name="Updated label",personName="Updated person label")}).single())
        for(changed in listOf(root.copy(media=media.take(3)),root.copy(media=media),root.copy(preview=true),
            root.copy(body="This gallery describes a different announcement entirely."),root.copy(media=media.take(3)+Media("https://pictures.example.org/elsewhere.jpg"))))
            assertTrue(plans(listOf(changed,peer)).isEmpty())
        assertTrue(plans(accounts=follows.map {it.copy(personId=it.id)}).isEmpty())
        assertTrue(plans(accounts=follows.map {it.copy(enabled=false)}).isEmpty())
        assertTrue(CrossPosts.galleryContinuationRequests(listOf(PostGroup(listOf(root,peer))),follows).isEmpty())
    }
    @Test fun suppliedParentNominatesButObservedPicturesDecideAdmissionAndStopFurtherAcquisition() {
        val request=plans().single()
        val stranger=child.copy(id="30",canonicalUrl="https://x.com/stranger/status/30")
        val other=child.copy(id="31",canonicalUrl="https://x.com/publiclab/status/31",parentId="missing")
        assertEquals(listOf(child),GalleryContinuations.candidates(request,Page(listOf(root,stranger,other,child))))
        assertTrue(GalleryContinuations.proven(request,listOf(child)))
        assertFalse(GalleryContinuations.proven(request,listOf(child.copy(media=listOf(Media("https://pictures.example.org/different.jpg"))))))
        assertTrue(GalleryContinuations.candidates(request,Page(listOf(root.copy(body="A revised announcement."),child))).isEmpty())
        val all=listOf(root,peer,child)
        val grouped=CrossPosts.group(all,follows,members+(child.identityKey to setOf("x")))
        assertEquals(all.toSet(),grouped.single().posts.toSet())
        assertTrue(CrossPosts.galleryContinuationRequests(grouped,follows,members+(child.identityKey to setOf("x"))).isEmpty())
    }
    @Test fun everyGenerationOfASelfReplyChainMustFitTheSameCompleteGallery() {
        val first=root.copy(media=media.take(2))
        val middle=child.copy(media=media.subList(2,4),parentId=first.id)
        val end=child.copy(id="22",canonicalUrl="https://x.com/publiclab/status/22",parentId=middle.id)
        val originals=listOf(peer,first,middle,end)
        for(input in listOf(originals,originals.reversed()))assertTrue(CrossPosts.galleryCollection(input))
        for(changed in listOf(middle.copy(media=listOf(Media("https://pictures.example.org/unrelated.jpg"))),
            middle.copy(body="This repair was not completed."),middle.copy(parentId=end.id),middle.copy(parentId="missing"),
            middle.copy(canonicalUrl="https://x.com/stranger/status/21")))assertFalse(CrossPosts.galleryCollection(listOf(peer,first,changed,end)))
    }
    @Test fun inputRevisionAllowsOnlyQualifiedRenditionsWithoutEstablishingPictureEvidence() {
        val first=root.copy(media=listOf(Media("https://pbs.twimg.com/media/Workshop_A.jpg?name=small&format=webp")))
        assertTrue(GalleryContinuations.sameInput(first,first.copy(media=listOf(Media("https://pbs.twimg.com/media/Workshop_A.jpg?name=orig")))))
        for(url in listOf("https://pbs.twimg.com/media/Workshop_B.jpg?name=orig","http://pbs.twimg.com/media/Workshop_A.jpg?name=orig",
            "https://pbs.twimg.com:444/media/Workshop_A.jpg?name=orig","https://user@pbs.twimg.com/media/Workshop_A.jpg?name=orig",
            "https://pbs.twimg.com/media/Workshop_A.jpg?name=orig&crop=face","https://pbs.twimg.com/media/Workshop_A.jpg?name=orig&name=small",
            "https://pbs.twimg.com/media/Workshop_A.jpg?name=orig#different"))assertFalse(url,GalleryContinuations.sameInput(first,first.copy(media=listOf(Media(url)))))
        assertFalse(GalleryContinuations.sameInput(first,first.copy(body="A different revision.")))
        assertFalse(GalleryContinuations.sameInput(first,first.copy(media=listOf(first.media.single().copy(type="video")))))
    }
}
