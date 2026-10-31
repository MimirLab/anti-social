/* anti social, GPL-3.0-or-later. Synthetic source refresh and non-alias controls. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VideoPostRefreshTest {
    private val at=1_800_000_000_000L
    private val source=Source(Platform.TIKTOK,"tikwm","https://reader.example")
    private fun url(shard:String="v45",region:String="eu",objectName:String="oWorkshopFilm0123456789abcdef")=
        "https://$shard.tiktokcdn-$region.com/"+"a".repeat(32)+"/6ae10001/video/tos/alisg/tos-alisg-ve-001c001-sg/$objectName/?mime_type=video_mp4"
    private fun old()=Post("710003",Platform.TIKTOK,"https://www.tiktok.com/@publicstudio/video/710003","Public studio","",title="The workshop demonstration is ready.",publishedAt=at,
        media=listOf(Media(url(),"video")),provenance=Provenance(source,at+30_000,"https://reader.example/api/"))
    private fun fresh()=old().copy(title="",body=old().title,publishedAt=at+11_000,media=listOf(Media(url("v16m","us","oNewCurrentWorkshopFilm0123456789abcdef"),"video",durationMs=120_000)),
        provenance=Provenance(source,at+60_000,"https://reader.example/api/"))
    @Test fun aWholeCurrentPostCanRenewDeliveryWithoutAliasingTheFormerFile() {
        val original=old();val current=fresh();val before=Gson().toJson(listOf(original,current))
        assertTrue(VideoPostRefresh.eligible(original));assertTrue(VideoPostRefresh.accepts(original,current))
        assertSame(current,VideoPostRefresh.candidate(original,Page(listOf(current))))
        assertNull(VideoAssetIdentity.key(current.media.single().url))
        val oldKey=VideoAssetIdentity.observationKey(original.media.single().url)
        assertFalse("Missing keys never establish an alias",oldKey!=null&&oldKey==VideoAssetIdentity.observationKey(current.media.single().url))
        assertEquals(before,Gson().toJson(listOf(original,current)))
    }
    @Test fun onlyAQualifiedOriginalAndProgressiveSingleVideoCanAskForRefresh() {
        val post=old();val media=post.media.single()
        for(other in listOf(post.copy(platform=Platform.X),post.copy(preview=true),post.copy(parentId="710000"),post.copy(publishedAt=null),post.copy(publishedAt=0),post.copy(body="",title=""),
            post.copy(media=emptyList()),post.copy(media=listOf(media,media)),post.copy(media=listOf(media.copy(type="image"))),post.copy(media=listOf(media.copy(audioUrl="https://media.example/audio.m4a"))),
            post.copy(media=listOf(media.copy(mimeType=VideoFormats.DASH))),post.copy(media=listOf(media.copy(samplingUrl=media.url))),post.copy(media=listOf(media.copy(durationMs=1))),
            post.copy(provenance=post.provenance!!.copy(source=source.copy(role=SourceRole.CACHE))),post.copy(canonicalUrl=post.canonicalUrl.replace("video/","photo/")),
            post.copy(canonicalUrl=post.canonicalUrl.replace("https:","http:")),post.copy(canonicalUrl=post.canonicalUrl.replace(".com/",".com.evil.example/")),
            post.copy(canonicalUrl=post.canonicalUrl+"/delete"),post.copy(canonicalUrl=post.canonicalUrl+"?confirm=private-token"),post.copy(body="x".repeat(16_385))))
            assertFalse(other.toString(),VideoPostRefresh.eligible(other))
    }
    @Test fun aNewObjectNeedsTheSameExactOriginalAccountAndCompletePresentedCaption() {
        val current=fresh()
        for(other in listOf(current.copy(id="710004",canonicalUrl=current.canonicalUrl.replace("710003","710004")),
            current.copy(canonicalUrl=current.canonicalUrl.replace("publicstudio","anotherstudio")),current.copy(body=current.body+" Another session begins."),
            current.copy(body=current.body.replace("is ready","is not ready")),current.copy(links=listOf("https://studio.example/new-destination"))))
            assertFalse(other.toString(),VideoPostRefresh.accepts(old(),other))
        assertNull(VideoPostRefresh.candidate(old(),Page(listOf(current,current))))
    }
    @Test fun timeAndDurationChecksRemainIndependentOfPostIdentity() {
        val current=fresh()
        for(other in listOf(current.copy(publishedAt=at+60_001),current.copy(publishedAt=Long.MAX_VALUE),
            current.copy(media=current.media.map {it.copy(durationMs=null)}),current.copy(media=current.media.map {it.copy(durationMs=VideoFootage.MAX_DURATION_MS+1)})))
            assertFalse(other.toString(),VideoPostRefresh.accepts(old(),other))
        assertTrue(VideoPostRefresh.accepts(old(),current.copy(publishedAt=at+60_000)))
        assertFalse(VideoPostRefresh.accepts(old().copy(media=old().media.map {it.copy(durationMs=80_000)}),current))
        assertTrue(VideoPostRefresh.accepts(old().copy(media=old().media.map {it.copy(durationMs=120_000)}),current))
    }
    @Test fun cachedArchiveOrForeignSourceMetadataCannotBecomeACurrentCopy() {
        val current=fresh();val provenance=current.provenance!!
        for(other in listOf(current.copy(provenance=null),current.copy(provenance=provenance.copy(cached=true)),current.copy(provenance=provenance.copy(fetchedAt=1)),
            current.copy(provenance=provenance.copy(source=source.copy(role=SourceRole.CACHE))),current.copy(provenance=provenance.copy(source=source.copy(role=SourceRole.ORIGINAL))),
            current.copy(provenance=provenance.copy(source=source.copy(platform=Platform.X))),current.copy(provenance=provenance.copy(finalUrl="https://other.example/api/")),
            current.copy(recoveredArchive=ArchiveRecovery("archive",at,"https://archive.example/capture"))))
            assertFalse(other.toString(),VideoPostRefresh.accepts(old(),other))
    }
    @Test fun noOldObservationsOrUnchangedFailedDeliveryAreSmuggledIntoTheNewRecord() {
        val original=old();val current=fresh();val media=current.media.single()
        for(other in listOf(current.copy(media=listOf(media.copy(url=original.media.single().url))),current.copy(media=listOf(media.copy(aiMetadata=AiMetadata(inspectedAt=at)))),
            current.copy(media=listOf(media.copy(visualFingerprint="untrusted"))),current.copy(media=listOf(media.copy(attachmentFingerprint="untrusted"))),
            current.copy(media=listOf(media.copy(videoFootage=VideoFootageEvidence(media.url,120_000,at,emptyList()))))))
            assertFalse(other.toString(),VideoPostRefresh.accepts(original,other))
        assertFalse(VideoPostRefresh.accepts(original.copy(conversationId="710000"),current))
    }
    @Test fun queuedInputIgnoresDerivedObservationsButKeepsEverySourceAndAcquisitionChange() {
        val post=old();val observed=post.copy(provenance=post.provenance!!.copy(cached=true),media=post.media.map {it.copy(aiMetadata=AiMetadata(inspectedAt=at),visualFingerprint="local",attachmentFingerprint="detail")})
        assertTrue(VideoPostRefresh.sameInput(post,observed))
        for(other in listOf(post.copy(body="changed"),post.copy(publishedAt=at+1),post.copy(provenance=post.provenance!!.copy(fetchedAt=at+1)),
            post.copy(media=post.media.map {it.copy(durationMs=120_000)}),post.copy(media=fresh().media)))
            assertFalse(VideoPostRefresh.sameInput(post,other))
    }
    @Test fun deceptiveMediaAuthoritiesAndUnrecognizedObjectShapesRemainIneligible() {
        val post=old();val raw=post.media.single().url
        for(other in listOf(raw.replace("https:","http:"),raw.replace("https://","https://user@"),raw.replace(".com/",".com:8443/"),raw.replace(".com/",".com.evil.example/"),raw+"#fragment",
            raw.replace("/video/tos/","/image/tos/"),raw.replace("6ae10001","expiry"),raw.replace("a".repeat(32),"short"),raw.replace("v45.","arbitrary.")))
            assertFalse(other,VideoPostRefresh.eligible(post.copy(media=listOf(post.media.single().copy(url=other)))))
    }
}
