/* anti social, GPL-3.0-or-later. Synthetic workshop carriers. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class CarriedRecordingTest {
    private val at=1_790_000_000_000L
    private val clip="https://video.twimg.com/amplify_video/734001/vid/avc1/640x360/workshop.mp4?tag=29"
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM,Platform.TIKTOK)
    private val urls=listOf("https://x.com/asworkshop/status/734001","https://www.facebook.com/asworkshop/posts/734002","https://www.instagram.com/p/Workshop734003/","https://www.tiktok.com/@asworkshop/video/734004")
    private val captions=listOf("W.O.R.K.","Every repair starts somewhere.","Workshop repair demonstration.","Watch the completed machine.")
    private val posts=platforms.indices.map {i->Post("73400${i+1}",platforms[i],urls[i],"Workshop",captions[i],publishedAt=at+i*60_000,media=listOf(Media(clip,"video",durationMs=18_000)))}
    private val follows=platforms.map {Follow(it.id,Target(it,TargetKind.PROFILE,"asworkshop",urls[platforms.indexOf(it)].substringBefore("/status/").substringBefore("/posts/").let {u->if(it==Platform.INSTAGRAM)"https://www.instagram.com/asworkshop/"else if(it==Platform.TIKTOK)"https://www.tiktok.com/@asworkshop"else u}),"Workshop",personId="workshop")}
    private fun groups(rows:List<Post>,accounts:List<Follow> = follows)=CrossPosts.group(rows,accounts,rows.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)})

    @Test fun identicalQualifiedClipCollectsDifferentCaptionsAcrossLinkedPlatformCarriersWithoutSampling() {
        val before=posts.map(Gson()::toJson)
        for(order in listOf(posts,posts.reversed(),posts.drop(1)+posts.first()))assertEquals(posts.toSet(),groups(order).single().posts.toSet())
        assertTrue(CrossPosts.carriedRecordingCollection(posts));assertEquals(before,posts.map(Gson()::toJson))
        assertTrue(posts.all {it.media.single().videoFootage==null})
    }
    @Test fun matchingCoversGenericUrlsAndOtherRenditionsCannotSupplyThisProof() {
        for(media in listOf(Media(clip,"image"),Media("https://media.example/clip.mp4","video"),Media(clip.replace("640x360","1280x720"),"video"),
            Media(clip.replace("734001","734099"),"video"),Media(clip+"&transform=crop","video"),Media(clip,"video",durationMs=80_000))) {
            val changed=listOf(posts.first(),posts.last().copy(media=listOf(media)))
            assertFalse(CrossPosts.carriedRecordingCollection(changed));assertEquals(2,groups(changed).size)
        }
    }
    @Test fun absentOrConflictingPublisherLinkageCannotNominateTheExactRecording() {
        assertEquals(4,groups(posts,emptyList()).size)
        val separate=follows.map {it.copy(personId=it.id)}
        assertEquals(4,groups(posts,separate).size)
    }
    @Test fun previewsRepliesArchivesExtraAttachmentsAndDistantReusesStayOutsideThisRoute() {
        for(other in listOf(posts.last().copy(preview=true),posts.last().copy(parentId="parent"),posts.last().copy(publishedAt=at+7*60*60_000),
            posts.last().copy(media=posts.last().media+Media("https://images.example/other.jpg")),posts.last().copy(canonicalUrl="https://other.example/post/734004"))) {
            assertFalse(CrossPosts.carriedRecordingCollection(listOf(posts.first(),other)))
            assertEquals(2,groups(listOf(posts.first(),other)).size)
        }
    }
    @Test fun sourceCredentialsPortsFragmentsAndUnqualifiedProviderPathsAreRejected() {
        for(url in listOf(clip.replace("https://","http://"),clip.replace("video.twimg.com","video.twimg.com.evil.example"),
            clip.replace("video.twimg.com","user:pass@video.twimg.com"),clip.replace("video.twimg.com","video.twimg.com:444"),clip+"#other",
            clip.replace("/vid/","/pl/"),clip.replace(".mp4",".m3u8")))assertNull(CarriedRecording.key(posts.first().copy(media=listOf(Media(url,"video")))),url)
    }
    @Test fun repeatedReuseCannotBridgeBeyondTheWholeFamilyWindow() {
        val rows=listOf(posts[0],posts[1].copy(publishedAt=at+5*60*60_000),posts[2].copy(publishedAt=at+10*60*60_000))
        assertFalse(CrossPosts.carriedRecordingCollection(rows));assertEquals(2,groups(rows).size)
        assertEquals(groups(rows).map {it.posts.toSet()}.toSet(),groups(rows.reversed()).map {it.posts.toSet()}.toSet())
    }
}
