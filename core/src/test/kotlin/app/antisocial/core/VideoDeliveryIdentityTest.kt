/* anti social, GPL-3.0-or-later. Qualified public delivery paths retain playback. */
package app.antisocial.core

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class VideoDeliveryIdentityTest {
    private val high="https://video.twimg.com/ext_tw_video/920001/pu/vid/avc1/720x1280/first.mp4?tag=12"
    private val low="https://video.twimg.com/ext_tw_video/920001/pu/vid/avc1/320x568/second.mp4?tag=12"
    @Test fun aSuppliedPublicDeliveryRenditionKeepsItsClipIdentityAndOriginalPlayback() {
        val expected="twitter:ext_tw_video:920001"
        assertEquals(expected,VideoAssetIdentity.key(high));assertEquals(expected,VideoAssetIdentity.key(low))
        assertEquals(expected,VideoAssetIdentity.key(high.replace("/pu/vid/","/vid/")))
        assertEquals(expected,VideoAssetIdentity.key(high.replace("video.twimg.com","video-s.twimg.com")))
        assertEquals(low,VideoAssetIdentity.smallRendition(high,listOf(high to 4000L,low to 1000L,low.replace("920001","920002")to 1L)))
        val media=Media(high,"video",samplingUrl=low);assertEquals(high,media.url);assertEquals(low,VideoAssetIdentity.sampleUrl(media))
        val source=Source(Platform.X,"nitter","https://reader.fixture.invalid",role=SourceRole.ALTERNATE)
        val original=Post("920010",Platform.X,"https://x.com/workshop/status/920010","Workshop","The complete community workshop recording is available.",publishedAt=1_800_000_000_000L,provenance=Provenance(source,1_800_000_000_000L,source.root+"/workshop/rss"))
        val proxy=source.root+"/video/enc/sig/"+Base64.getUrlEncoder().withoutPadding().encodeToString(high.toByteArray())
        assertEquals(expected,DeclaredVideo.key(original,Media(proxy,"video")))
        assertEquals(proxy,Media(proxy,"video").url)
    }
    @Test fun differentObjectsAndUnqualifiedDeliveryShapesCannotBecomeRenditions() {
        assertNotEquals(VideoAssetIdentity.key(high),VideoAssetIdentity.key(high.replace("920001","920002")))
        for(url in listOf(high.replace("https:","http:"),high.replace("https://","https://name:secret@"),high.replace("video.twimg.com","elsewhere.fixture.invalid"),
            high.replace("ext_tw_video","amplify_video"),high.replace("/pu/vid/","/pu/pl/"),high.replace("/pu/vid/","/pu/other/"),high.replace("920001","not-an-id"),
            high.replace("video.twimg.com","video.twimg.com:8443"),high.replace(".mp4?",".ts?"),high.replace(".mp4?",".m3u8?"),high+"#other"))assertNull(url,VideoAssetIdentity.key(url))
        assertNull(VideoAssetIdentity.smallRendition(high,listOf(low.replace("920001","920002")to 1L)))
    }
}
