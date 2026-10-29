/* anti social, GPL-3.0-or-later. Literal captions are never match evidence here. */
package app.antisocial.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SharedVideoCollectionsTest {
    private val at=1_800_000_000_000L
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM,Platform.BLUESKY,Platform.TIKTOK)
    private fun profile(p:Platform)=when(p){Platform.X->"https://x.com/publicstudio";Platform.FACEBOOK->"https://www.facebook.com/publicstudio";Platform.INSTAGRAM->"https://www.instagram.com/publicstudio/";Platform.TIKTOK->"https://www.tiktok.com/@publicstudio";else->"https://bsky.app/profile/publicstudio.test"}
    private val accounts=platforms.map {p->Follow(p.id,Target(p,TargetKind.PROFILE,if(p==Platform.BLUESKY)"publicstudio.test"else "publicstudio",profile(p)),"Public studio",personId="studio")}
    private fun post(p:Platform,caption:String,id:String="71000001",minute:Long=0):Post {
        val url=when(p){Platform.X->profile(p)+"/status/$id";Platform.FACEBOOK->profile(p)+"/posts/$id";Platform.INSTAGRAM->"https://www.instagram.com/p/Fixture$id/";Platform.TIKTOK->profile(p)+"/video/$id";else->profile(p)+"/post/$id"}
        return Post(id,p,url,if(p==Platform.BLUESKY)"publicstudio.test"else "publicstudio",caption,publishedAt=at+minute*60_000,
            media=listOf(Media("https://media.example.org/${p.id}-$id.mp4","video",durationMs=120_000)))
    }
    private fun owners(groups:List<PostGroup>)=groups.flatMap {it.posts}.associate {CrossPosts.originalKey(it) to "studio"}
    private fun proof(a:Post,b:Post)=if(a.media.any {it.type=="video"}&&b.media.any {it.type=="video"})SharedVideoFootage(4,.99)else null
    @Test fun twoCaptionCollectionsJoinWithoutRewritingOrDiscardingAnyOriginal()=runBlocking {
        val caption="A complete demonstration from the public workshop."
        val other="Watch the new process in action, from the first step to the final result."
        val posts=listOf(post(Platform.X,caption),post(Platform.BLUESKY,caption,minute=1),post(Platform.FACEBOOK,other,minute=2),post(Platform.INSTAGRAM,other,minute=3))
        val members=posts.associate {CrossPosts.originalKey(it)to setOf(it.platform.id)}
        assertEquals(2,CrossPosts.group(posts,accounts,members).size)
        val merged=CrossPosts.groupWithVideoFootage(posts,accounts,members,sharedFootage=::proof).single()
        assertEquals(posts.toSet(),merged.posts.toSet())
        assertEquals(posts.map(Post::canonicalUrl).toSet(),merged.posts.map(Post::canonicalUrl).toSet())
        assertEquals(merged,CrossPosts.groupWithVideoFootage(posts.reversed(),accounts,members,sharedFootage=::proof).single())
    }
    @Test fun aPosterOrTwoScenesCannotGrantFootageAdmission() {
        val groups=listOf(PostGroup(listOf(post(Platform.X,"Workshop instructions."))),PostGroup(listOf(post(Platform.FACEBOOK,"An independent caption."))))
        assertEquals(2,SharedVideoCollections.collect(groups,owners(groups),{_,_->null}).size)
        assertEquals(2,SharedVideoCollections.collect(groups,owners(groups),{_,_->SharedVideoFootage(2,.999)}).size)
        assertEquals(2,SharedVideoCollections.collect(groups,owners(groups),{_,_->SharedVideoFootage(4,.5)}).size)
    }
    @Test fun anAbbreviatedFifthCaptionJoinsOnlyWithItsOwnFootageEvidence()=runBlocking {
        val first="Building. A. Better. Neighborhood."
        val other="See the completed public workshop demonstration."
        val posts=listOf(post(Platform.X,first),post(Platform.BLUESKY,first.replace(" ",""),minute=1),
            post(Platform.FACEBOOK,other,minute=2),post(Platform.INSTAGRAM,other,minute=3),post(Platform.TIKTOK,"B.A.B.N.",minute=4))
        val members=posts.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)}
        assertEquals(3,CrossPosts.group(posts,accounts,members).size)
        val noFifthProof=CrossPosts.groupWithVideoFootage(posts,accounts,members,sharedFootage={a,b->
            if(a.platform==Platform.TIKTOK||b.platform==Platform.TIKTOK)null else proof(a,b)
        })
        assertEquals(2,noFifthProof.size)
        val merged=CrossPosts.groupWithVideoFootage(posts,accounts,members,sharedFootage=::proof).single()
        assertEquals(posts.toSet(),merged.posts.toSet())
        assertEquals(posts,CrossPosts.groupWithVideoFootage(posts.reversed(),accounts,members,sharedFootage=::proof).single().posts)
    }
    @Test fun aDottedCaptionKeepsItsLiteralPathBeforeFootageJoinsTheOtherCaption()=runBlocking {
        val text="Building. A. Better. Neighborhood."
        val other="See the completed public workshop demonstration."
        val posts=listOf(post(Platform.X,text+" https://t.co/Fixture01"),post(Platform.BLUESKY,text.replace(" ",""),minute=1),post(Platform.FACEBOOK,other,minute=2),post(Platform.INSTAGRAM,other,minute=3))
        val members=posts.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)}
        assertEquals(2,CrossPosts.group(posts,accounts,members).size)
        val merged=CrossPosts.groupWithVideoFootage(posts,accounts,members,sharedFootage={a,b->
            if(setOf(a.platform,b.platform)==setOf(Platform.X,Platform.FACEBOOK))SharedVideoFootage(4,.99)else null
        }).single()
        assertEquals(posts.toSet(),merged.posts.toSet())
        val caption=requireNotNull(CrossPostFusion.compose(posts.take(2).reversed())).segments.single()
        assertEquals(posts[1].body,caption.text);assertEquals(setOf(Platform.X,Platform.BLUESKY),caption.platforms)
        assertTrue(CrossPosts.formattingCaptionCopies(posts.take(2)))
        assertFalse(CrossPosts.formattingCaptionCopies(listOf(posts.first(),posts[1].copy(body="Building? A better neighborhood."))))
        assertFalse(CrossPosts.formattingCaptionCopies(listOf(posts.first(),posts[1].copy(body="Building. A. Different. Neighborhood."))))
    }
    @Test fun ownerDatesRepliesArchivesAndMultipleClipsRemainSeparateAdmissionBoundaries() {
        val first=post(Platform.X,"First account's caption.")
        val second=post(Platform.FACEBOOK,"Another complete caption.",minute=2)
        val groups=listOf(PostGroup(listOf(first)),PostGroup(listOf(second)))
        assertEquals(1,SharedVideoCollections.collect(groups,owners(groups),::proof).size)
        assertEquals(2,SharedVideoCollections.collect(groups,owners(groups).mapValues {if(it.key==CrossPosts.originalKey(second))"other"else it.value},::proof).size)
        assertEquals(2,SharedVideoCollections.collect(groups,emptyMap(),::proof).size)
        for(changed in listOf(second.copy(publishedAt=null),second.copy(publishedAt=at+7*60*60_000L),second.copy(preview=true),second.copy(parentId="parent"),second.copy(recoveredArchive=ArchiveRecovery("archive",at,"https://archive.example/copy")),second.copy(media=second.media+Media("https://media.example.org/another.mp4","video")))) {
            val changedGroups=listOf(groups.first(),PostGroup(listOf(changed)))
            assertEquals(2,SharedVideoCollections.collect(changedGroups,owners(changedGroups),::proof).size)
        }
    }
    @Test fun aSharedMiddleCannotBridgeTwoUnmatchedRecordings() {
        val a=post(Platform.X,"First film.",id="71000001")
        val b=post(Platform.FACEBOOK,"Middle film.",id="71000002",minute=1)
        val c=post(Platform.BLUESKY,"Third film.",id="71000003",minute=2)
        val groups=listOf(a,b,c).map {PostGroup(listOf(it))}
        val compare:(Post,Post)->SharedVideoFootage?={x,y->if(x.id==b.id||y.id==b.id)SharedVideoFootage(3,.99)else null}
        val first=SharedVideoCollections.collect(groups,owners(groups),compare)
        val reversed=SharedVideoCollections.collect(groups.reversed(),owners(groups),compare)
        assertEquals(2,first.size);assertTrue(first.all {it.posts.size<=2})
        assertEquals(first.map {it.posts.toSet()}.toSet(),reversed.map {it.posts.toSet()}.toSet())
        assertEquals(setOf(a,b,c),first.flatMap {it.posts}.toSet())
    }
    @Test fun interruptedAdmissionCannotReturnAPartialFamily() {
        val groups=listOf(PostGroup(listOf(post(Platform.X,"First film."))),PostGroup(listOf(post(Platform.FACEBOOK,"Other caption."))))
        var checks=0
        assertThrows(CancellationException::class.java){SharedVideoCollections.collect(groups,owners(groups),::proof){if(++checks==3)throw CancellationException("Stopped")}}
        assertEquals(1,SharedVideoCollections.collect(groups,owners(groups),::proof).size)
    }
    @Test fun onlyNearbyOwnedDistinctCaptionGroupsOfferABoundedSamplingPlan() {
        val a=post(Platform.X,"First complete caption.")
        val b=post(Platform.FACEBOOK,"Another complete caption.",minute=1)
        val groups=listOf(PostGroup(listOf(a)),PostGroup(listOf(b)))
        val plan=CrossPosts.videoFootageRequests(groups,accounts).single()
        assertEquals(setOf(a,b),setOf(plan.first,plan.second));assertEquals(setOf(a,b).mapTo(hashSetOf(),CrossPosts::originalKey),plan.originalKeys)
        assertTrue(CrossPosts.videoFootageRequests(listOf(PostGroup(listOf(a,b))),accounts).isEmpty())
        assertTrue(CrossPosts.videoFootageRequests(groups,emptyList()).isEmpty())
        assertTrue(CrossPosts.videoFootageRequests(groups.map {it.copy(posts=it.posts.map {p->p.copy(media=p.media.map {m->m.copy(durationMs=null)})})},accounts).isEmpty())
        assertTrue(CrossPosts.videoFootageRequests(listOf(groups.first(),PostGroup(listOf(b.copy(publishedAt=at+7*60*60_000L)))),accounts).isEmpty())
        assertTrue(CrossPosts.videoFootageRequests(listOf(groups.first(),PostGroup(listOf(b.copy(media=listOf(Media("https://media.example.org/playlist.m3u8","video")))))),accounts).isEmpty())
        val many=(0..30).map {PostGroup(listOf(post(Platform.X,"Different source wording $it.",id="7100${it+100}",minute=it.toLong())))}
        assertEquals(12,CrossPosts.videoFootageRequests(many,accounts).size)
    }
    @Test fun compatibleRuntimesCanNominateExtensionlessVideosWithoutScanningUnknownOrAdaptiveMedia() {
        val a=post(Platform.X,"First clip.").copy(media=listOf(Media("https://media.example.org/first","video",durationMs=120_000)))
        val b=post(Platform.TIKTOK,"Another caption.").copy(media=listOf(Media("https://media.example.org/second","video",durationMs=120_100)))
        fun plans(first:Post=a,second:Post=b)=CrossPosts.videoFootageRequests(listOf(PostGroup(listOf(first)),PostGroup(listOf(second))),accounts)
        assertEquals(1,plans().size)
        assertTrue(plans(second=b.copy(media=b.media.map {it.copy(durationMs=null)})).isEmpty())
        assertTrue(plans(second=b.copy(media=b.media.map {it.copy(mimeType=VideoFormats.HLS)})).isEmpty())
        assertTrue(plans(second=b.copy(media=b.media.map {it.copy(mimeType="video/x-matroska")})).isEmpty())
        assertTrue(plans(second=b.copy(media=b.media.map {it.copy(durationMs=5000)})).isEmpty())
    }
    @Test fun aRetainedQualifiedXClipCanAskForMetadataBeforeComparingDifferentCaptions() {
        val first=post(Platform.X,"First clip.").copy(media=listOf(Media("https://video.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4","video")))
        val second=post(Platform.FACEBOOK,"Another complete caption.",minute=1).copy(media=listOf(Media("https://media.example.org/second.mp4","video")))
        assertTrue(VideoAssetIdentity.canResolveMetadata(first))
        val request=CrossPosts.videoFootageRequests(listOf(PostGroup(listOf(first)),PostGroup(listOf(second))),accounts).single()
        assertTrue(request.resolveMetadata)
        assertEquals(setOf(first,second),setOf(request.first,request.second))
        assertFalse(VideoAssetIdentity.canResolveMetadata(first.copy(parentId="parent")))
        assertFalse(VideoAssetIdentity.canResolveMetadata(first.copy(media=first.media.map {it.copy(url="https://media.example.org/unknown")})))
        assertFalse(VideoAssetIdentity.canResolveMetadata(first.copy(media=first.media.map {it.copy(url=it.url.replace("video.twimg.com","video.twimg.com.example.org"))})))
    }
    @Test fun anUnhintedCaptionPeerCannotHideTheEditionThatCanResolveItsOwnMetadata() {
        val x=post(Platform.X,"A shared first caption.").copy(media=listOf(Media("https://video.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4","video")))
        val bsky=post(Platform.BLUESKY,x.body,minute=1).copy(media=listOf(Media("https://media.example.org/bsky-opaque","video")))
        val fb=post(Platform.FACEBOOK,"A different caption.",minute=2).copy(media=listOf(Media("https://media.example.org/fb-opaque","video")))
        val groups=listOf(PostGroup(listOf(x,bsky)),PostGroup(listOf(fb)))
        val request=CrossPosts.videoFootageRequests(groups,accounts).single()
        assertTrue(request.resolveMetadata)
        assertEquals(setOf(x,fb),setOf(request.first,request.second))
        assertEquals(setOf(x,bsky,fb).mapTo(hashSetOf(),CrossPosts::originalKey),request.originalKeys)
        assertEquals(request.acquisitionKey,CrossPosts.videoFootageRequests(groups.map {it.copy(posts=it.posts.reversed())}.reversed(),accounts).single().acquisitionKey)
    }
    private fun measured(post:Post):Post {
        val media=post.media.single()
        val scenes=(0..3).map {position->SampledVideoScene(position,requireNotNull(VisualFrames.encode(1.5,40.0,0L,0L,List(48){128},ByteArray(4096){(it%193+31).toByte()})))}
        return post.copy(media=listOf(media.copy(videoFootage=VideoFootageEvidence(media.url,120_000,at,scenes))))
    }
    @Test fun aMeasuredPeerKeepsTheRemainingOpaqueEditionEligibleWithoutAnotherMetadataLookup() {
        val x=measured(post(Platform.X,"First film.").copy(media=listOf(Media("https://video.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4","video"))))
        val fb=post(Platform.FACEBOOK,"Different wording.",minute=1).copy(media=listOf(Media("https://media.example.org/opaque","video")))
        val groups=listOf(PostGroup(listOf(x)),PostGroup(listOf(fb)))
        val pending=CrossPosts.videoFootageRequests(groups,accounts).single()
        assertFalse(pending.resolveMetadata)
        assertTrue(CrossPosts.videoFootageRequests(listOf(groups.first(),PostGroup(listOf(measured(fb)))),accounts).isEmpty())
    }
    @Test fun acquisitionRestartIdentityChangesOnlyWhenTheActualAcquisitionInputsChange() {
        val first=post(Platform.X,"First film.")
        val second=post(Platform.FACEBOOK,"Different wording.",minute=1)
        val pending=VideoFootageRequest(first,second,setOf("first","second"),resolveMetadata=true)
        assertEquals(pending.acquisitionKey,pending.copy(first=measured(first).copy(body="A later source caption."),resolveMetadata=false).acquisitionKey)
        assertEquals(pending.acquisitionKey,pending.copy(first=second,second=first).acquisitionKey)
        assertNotEquals(pending.acquisitionKey,pending.copy(first=first.copy(media=first.media.map {it.copy(durationMs=120_500)})).acquisitionKey)
        assertNotEquals(pending.acquisitionKey,pending.copy(first=first.copy(media=first.media.map {it.copy(samplingUrl="https://media.example.org/other.mp4")})).acquisitionKey)
    }
    @Test fun aVisibleOlderCardCannotBeCrowdedOutByTheGlobalNewestWindow() {
        val a=post(Platform.TIKTOK,"An older film.")
        val b=post(Platform.X,"Its other caption.",minute=1)
        val newer=(0..130).map {PostGroup(listOf(post(Platform.X,"Another film $it.",id="72${it+1000}",minute=1_000+it.toLong())))}
        val groups=newer+listOf(PostGroup(listOf(a)),PostGroup(listOf(b)))
        val key=CrossPosts.originalKey(a)
        assertTrue(CrossPosts.videoFootageRequests(groups,accounts).none {key in it.originalKeys})
        val focused=CrossPosts.focusedVideoFootageRequests(groups,accounts,emptyMap(),setOf(key)).single()
        assertEquals(setOf(a,b),setOf(focused.first,focused.second))
    }
    @Test fun focusedPlanningRetainsOwnerDateUnknownMediaAndFiniteComparisonGuards() {
        val a=post(Platform.TIKTOK,"First film.")
        val b=post(Platform.X,"Another caption.",minute=1)
        val key=CrossPosts.originalKey(a)
        fun plans(second:Post=b,follows:List<Follow> = accounts)=CrossPosts.focusedVideoFootageRequests(listOf(PostGroup(listOf(a)),PostGroup(listOf(second))),follows,emptyMap(),setOf(key))
        assertEquals(1,plans().size)
        assertTrue(plans(b.copy(publishedAt=at+7*60*60_000L)).isEmpty())
        assertTrue(plans(follows=accounts.map {if(it.target.platform==Platform.X)it.copy(personId="someone-else")else it}).isEmpty())
        assertTrue(plans(b.copy(parentId="parent")).isEmpty())
        val unknown=listOf(a,b).map {it.copy(media=it.media.map {m->m.copy(durationMs=null)})}
        assertTrue(CrossPosts.focusedVideoFootageRequests(unknown.map {PostGroup(listOf(it))},accounts,emptyMap(),setOf(key)).isEmpty())
        val many=(0..40).map {PostGroup(listOf(post(Platform.X,"Another film $it.",id="73${it+1000}",minute=it+1L)))}
        val bounded=CrossPosts.focusedVideoFootageRequests(many+PostGroup(listOf(a)),accounts,emptyMap(),setOf(key))
        assertEquals(12,bounded.size);assertTrue(bounded.all {key in it.originalKeys})
        assertTrue(CrossPosts.focusedVideoFootageRequests(many,accounts,emptyMap(),setOf("missing")).isEmpty())
    }
    @Test fun currentProofsCloseThePlatformGapWithoutScanningMoreOfTheSamePlatforms() {
        val a=measured(post(Platform.TIKTOK,"First film."))
        val b=measured(post(Platform.X,"Another caption.",minute=1))
        val c=post(Platform.X,"An unrelated nearby film.",id="740001",minute=2)
        val groups=listOf(a,b,c).map {PostGroup(listOf(it))}
        val focused=CrossPosts.focusedVideoFootageRequests(groups,accounts,emptyMap(),setOf(CrossPosts.originalKey(a)),
            sharedFootage={x,y->if(setOf(x.id,y.id)==setOf(a.id,b.id))SharedVideoFootage(4,.99)else null})
        assertTrue(focused.isEmpty())
        var checks=0
        assertThrows(CancellationException::class.java) {
            CrossPosts.focusedVideoFootageRequests(groups,accounts,emptyMap(),setOf(CrossPosts.originalKey(a)),checkpoint={if(++checks==3)throw CancellationException("Stopped")})
        }
    }
}
