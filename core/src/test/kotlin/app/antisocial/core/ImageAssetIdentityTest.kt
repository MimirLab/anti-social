/* anti social, GPL-3.0-or-later. Source-specific object names only. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ImageAssetIdentityTest {
    private val path="/v/t39.30808-6/123456789_234567890_345678901_n.jpg"
    private val first="https://scontent-a.xx.fbcdn.net$path"
    private val second="https://scontent-b.cdninstagram.com$path"
    @Test fun regionalHostsAndOnlyRecognizedResizingOrExpiryParametersShareAnAsset() {
        assertNotNull(ImageAssetIdentity.key(first))
        assertTrue(ImageAssetIdentity.same(first+"?stp=dst-jpg_s640x640_tt6&oh=one&oe=ABC",second+"?stp=dst-jpg_e35_p1080x1080_tt6&_nc_gid=other&ig_cache_key=value&ccb=7-5&efg=metadata"))
    }
    @Test fun changedObjectsContainersAndFileTypesStaySeparate() {
        for(url in listOf(second.replace("234567890","234567891"),second.replace("t39.30808-6","t51.82787-15"),second.replace(".jpg",".png")))assertFalse(ImageAssetIdentity.same(first,url))
    }
    @Test fun lookalikeAndUnrelatedHostsCannotClaimMetaObjects() {
        for(host in listOf("fbcdn.net.attacker.example","evilcdninstagram.com","images.example.org"))assertNull(ImageAssetIdentity.key("https://$host$path"))
    }
    @Test fun croppingAndUnqualifiedTransformParametersDoNotProveTheSamePicture() {
        for(query in listOf("stp=dst-jpg_c0.0.640.640a","crop=left","stp=unknown","stp=dst-jpg_s640x640_tt6&stp=dst-jpg_s320x320_tt6","filter=alternate"))assertNull(ImageAssetIdentity.key(first+"?"+query))
    }
    @Test fun unsafeOrAmbiguousUrlFormsAreNotQualified() {
        for(url in listOf(first.replace("https:","http:"),first.replace("https://","https://user:password@"),first.replace(".net/",".net:8443/"),first+"#region",first+"\n"))assertNull(ImageAssetIdentity.key(url))
    }
    @Test fun arbitraryFilenamesAndHugeValuesAreRejected() {
        assertNull(ImageAssetIdentity.key(first.replace("123456789_234567890_345678901_n","logo")))
        assertNull(ImageAssetIdentity.key(first+"?oh="+"a".repeat(8192)))
        assertFalse(ImageAssetIdentity.same("https://example.org/picture.jpg","https://example.org/picture.jpg"))
    }
    @Test fun contraryPixelEvidenceOutranksEvenTheSameQualifiedObjectName() {
        val pixels=ByteArray(4096){100};val changed=pixels.clone().apply {this[150]=0}
        val a=VisualFrames.encode(1.25,40.0,0,0,List(48){100},pixels)!!
        val b=VisualFrames.encode(1.25,40.0,0,0,List(48){100},changed)!!
        assertTrue(ImageAssetIdentity.same(first,second));assertFalse(ImageAssetIdentity.same(Media(first,visualFingerprint=a),Media(second,visualFingerprint=b)))
    }
    @Test fun theAssetMemoCannotBeConfusedWithOrdinaryUrlNormalization()=CrossPostFusion.withPrepared {
        CrossPostFusion.normalizedUrl("image-asset|$first"){null}
        assertNotNull(ImageAssetIdentity.key(first))
        assertEquals("ordinary-url",CrossPostFusion.normalizedUrl(first){"ordinary-url"})
        assertEquals(ImageAssetIdentity.key(first),ImageAssetIdentity.key(second))
    }
}
