/* anti social, GPL-3.0-or-later. Synthetic rasters; private captures opt in. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

class VideoFootageTest {
    private fun pixels(seed:Int)=ByteArray(4096){index->
        val x=index%64;val y=index/64;var n=seed*7919+(x/4)*31+(y/4)*1009
        n=(n xor(n ushr 13))*1274126177
        ((n ushr 16 and 191)+32).toByte()
    }
    private fun crop(p:ByteArray,x:Int,y:Int,w:Int,h:Int)=ByteArray(4096){i->p[(y+(i/64)*h/64)*64+x+(i%64)*w/64]}
    private fun frame(p:ByteArray,aspect:Double=16.0/9):String {
        val values=p.map {it.toInt()and 255};val mean=values.average();val contrast=sqrt(values.sumOf {(it-mean)*(it-mean)}/4096).coerceIn(20.0,128.0)
        return requireNotNull(VisualFrames.encode(aspect,contrast,0L,0L,List(48){128},p))
    }
    private fun evidence(frames:List<String>,duration:Long=120_000)=VideoFootageEvidence("https://video.twimg.com/amplify_video/710001/vid/320x180/fixture.mp4",duration,1,frames.mapIndexed {i,p->SampledVideoScene(i,p)})
    private val source get()=(0..3).map {pixels(it+1)}
    private fun cropped(p:List<ByteArray>)=p.mapIndexed {i,a->frame(crop(a,10+i*4,0,20,64),9.0/16)}

    @Test fun fixedSamplingPositionsAvoidTheSharedIntroAndOutro() {
        assertEquals(listOf(15_000L,45_000L,75_000L,105_000L),(0..3).map {VideoFootage.requestedAt(120_000,it)})
        assertThrows(IllegalArgumentException::class.java){VideoFootage.requestedAt(0,0)}
        assertThrows(IllegalArgumentException::class.java){VideoFootage.requestedAt(120_000,4)}
    }
    @Test fun aSavedFootageFamilyPreservesDifferentCaptionsAndRequiresEachUnmeasuredEditionToRelate() {
        val footage=evidence(source.map(::frame))
        val at=1_800_000_000_000L
        val x=Post("1",Platform.X,"https://x.com/publicstudio/status/710001","Public studio","A complete workshop demonstration, from start to finish.",publishedAt=at,
            media=listOf(Media(footage.renditionUrl,"video",videoFootage=footage)))
        val fb=Post("2",Platform.FACEBOOK,"https://www.facebook.com/publicstudio/posts/710002","Public studio","Try a new technique at our open studio this weekend.",publishedAt=at+60_000,
            media=listOf(Media("https://media.example.org/studio.mp4","video",videoFootage=footage.copy(renditionUrl="https://media.example.org/studio.mp4",scenes=cropped(source).mapIndexed {i,f->SampledVideoScene(i,f)}))))
        val ig=Post("3",Platform.INSTAGRAM,"https://www.instagram.com/p/FixtureClip/","Public studio",fb.body,publishedAt=at+120_000,media=listOf(Media("https://media.example.org/cover.jpg")))
        val originals=listOf(x,fb,ig);val before=Gson().toJson(originals)
        assertTrue(VideoFootage.collection(originals));assertEquals(before,Gson().toJson(originals))
        val dotted=x.copy(id="4",platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/publicstudio.test/post/fixture4",body=x.body.replace(" ","."),media=listOf(Media("https://media.example.org/unmeasured.mp4","video")))
        assertTrue(VideoFootage.collection(originals+dotted))
        assertFalse(VideoFootage.collection(listOf(x,fb.copy(media=fb.media.map {it.copy(videoFootage=null)}),ig)))
        assertFalse(VideoFootage.collection(listOf(x,fb,ig.copy(body="An unrelated public announcement with its own distinct information."))))
        assertFalse(VideoFootage.collection(listOf(x,fb.copy(publishedAt=at+7*60*60_000L))))
        assertFalse(VideoFootage.collection(listOf(x,fb.copy(platform=Platform.X))))
    }
    @Test fun aRenditionMayUseTheQualifiedProviderShardButCannotBorrowAnotherClipOrAnImage() {
        val evidence=evidence(source.map(::frame))
        val root=Media("https://video-s.twimg.com/amplify_video/710001/vid/1920x1080/other.mp4?tag=29","video",videoFootage=evidence)
        assertTrue(VideoFootage.valid(root))
        assertFalse(VideoFootage.valid(root.copy(url=root.url.replace("710001","710002"))))
        assertFalse(VideoFootage.valid(root.copy(url=root.url.replace("video-s.twimg.com","video-s.twimg.com.example.org"))))
        assertFalse(VideoFootage.valid(root.copy(type="image")))
        assertEquals(VideoAssetIdentity.key(evidence.renditionUrl),VideoAssetIdentity.key(root.url))
        assertNull(VideoAssetIdentity.key(root.url.replace("/vid/","/unrelated/")))
        val gson=Gson();assertEquals(root,gson.fromJson(gson.toJson(root),Media::class.java))
    }
    @Test fun advertisedSmallRenditionsCannotChangeThePlaybackOrBorrowAnotherClip() {
        val playback="https://video-s.twimg.com/amplify_video/710001/vid/1920x1080/main.mp4"
        val small="https://video.twimg.com/amplify_video/710001/vid/320x180/small.mp4"
        val foreign=small.replace("710001","710002")
        assertEquals(small,VideoAssetIdentity.smallRendition(playback,listOf(playback to 1000L,small to 100L,foreign to 1L)))
        val media=Media(playback,"video",samplingUrl=small)
        assertTrue(VideoFootage.valid(media));assertEquals(playback,media.url);assertEquals(small,VideoAssetIdentity.sampleUrl(media))
        assertFalse(VideoFootage.valid(media.copy(samplingUrl=foreign)))
        assertFalse(VideoFootage.valid(media.copy(type="image")))
        assertNull(VideoAssetIdentity.smallRendition("https://example.org/player.mp4",listOf(small to 100L)))
    }
    @Test fun panningPortraitCropsShareFootageWithoutBecomingIdenticalPictures() {
        val p=source;val a=evidence(p.map(::frame));val b=evidence(cropped(p))
        assertTrue(VideoFootage.valid(a));assertTrue(VideoFootage.valid(b))
        val match=requireNotNull(VideoFootage.shared(a,b))
        assertEquals(4,match.scenes);assertEquals(match,VideoFootage.shared(b,a))
        assertTrue(a.scenes.zip(b.scenes).all {(x,y)->!VisualFrames.sameImage(x.fingerprint,y.fingerprint)})
    }
    @Test fun aZoomWithTheSameAspectIsSymmetric() {
        val p=source;val a=evidence(p.map(::frame));val b=evidence(p.map {frame(crop(it,16,16,32,32))})
        assertEquals(4,requireNotNull(VideoFootage.shared(a,b)).scenes)
        assertEquals(VideoFootage.shared(a,b),VideoFootage.shared(b,a))
    }
    @Test fun oneMissingPositionDoesNotBecomeAnInventedScene() {
        val p=source;val a=evidence(p.map(::frame));val b=evidence(cropped(p)).let {it.copy(scenes=it.scenes.dropLast(1))}
        assertEquals(3,requireNotNull(VideoFootage.shared(a,b)).scenes)
        assertNull(VideoFootage.shared(a,b.copy(scenes=b.scenes.take(2))))
    }
    @Test fun aCommonCoverOrStaticBackgroundCannotStandInForThreeScenes() {
        val repeated=List(4){frame(pixels(1))}
        assertNull(VideoFootage.shared(evidence(repeated),evidence(repeated)))
        val p=source;val a=evidence(p.map(::frame));val b=evidence(listOf(frame(p[0]),frame(pixels(21)),frame(pixels(22)),frame(p[3])))
        assertNull(VideoFootage.shared(a,b))
    }
    @Test fun reversedOrUnrelatedScenesDoNotMatchByAnUnorderedBagOfFrames() {
        val a=evidence(source.map(::frame))
        assertNull(VideoFootage.shared(a,evidence(a.scenes.reversed().map {it.fingerprint})))
        assertNull(VideoFootage.shared(a,evidence((20..23).map {frame(pixels(it))})))
    }
    @Test fun durationAndRepresentationBoundsStayAttachedToTheEvidence() {
        val a=evidence(source.map(::frame))
        assertNotNull(VideoFootage.shared(a,a.copy(durationMs=120_600)))
        assertNull(VideoFootage.shared(a,a.copy(durationMs=140_000)))
        for(bad in listOf(a.copy(version=2),a.copy(durationMs=0),a.copy(sampledAt=0),a.copy(renditionUrl="http://video.example/clip"),a.copy(scenes=a.scenes+a.scenes.first()),a.copy(scenes=a.scenes.reversed()),a.copy(scenes=listOf(SampledVideoScene(4,a.scenes.first().fingerprint))),a.copy(scenes=listOf(SampledVideoScene(0,"f2|broken")))))assertFalse(VideoFootage.valid(bad))
    }
    @Test fun cancellationStopsTheSearchAndNeitherResultNorInputLeaksIntoTheNextPass() {
        val p=source;val a=evidence(p.map(::frame));val b=evidence(cropped(p));val before=Gson().toJson(listOf(a,b));var checks=0
        assertThrows(CancellationException::class.java){VideoFootage.shared(a,b){if(++checks==3)throw CancellationException("Stopped")}}
        assertTrue(checks>=3);assertEquals(4,requireNotNull(VideoFootage.shared(a,b)).scenes)
        assertEquals(before,Gson().toJson(listOf(a,b)))
    }
    @Test fun suppliedPrivateRenditionsMatchAcrossCropsButNotReversedScenes() {
        val path=System.getProperty("antisocial.videoFootageInput")
        assumeTrue(!path.isNullOrBlank())
        val file=File(requireNotNull(path)).canonicalFile
        val build=File(requireNotNull(System.getProperty("antisocial.native.path"))).parentFile.parentFile.canonicalFile
        require(file.path.startsWith(build.path+File.separator)&&file.length() in 1..1024*1024)
        val values=JsonParser.parseString(file.readText()).asJsonArray.also {require(it.size()==2)}.map {Gson().fromJson(it,VideoFootageEvidence::class.java)}
        val expected=System.getProperty("antisocial.videoFootageExpectedScenes","4").toInt().also {require(it in 3..4)}
        assertEquals(expected,requireNotNull(VideoFootage.shared(values[0],values[1])).scenes)
        assertTrue(values[0].scenes.zip(values[1].scenes).all {(a,b)->!VisualFrames.sameImage(a.fingerprint,b.fingerprint)})
        val reversed=values[1].copy(scenes=values[1].scenes.mapIndexed {i,scene->scene.copy(fingerprint=values[1].scenes.reversed()[i].fingerprint)})
        assertNull(VideoFootage.shared(values[0],reversed))
    }
}
