/* anti social, GPL-3.0-or-later. Synthetic signed delivery addresses. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class VideoObjectIdentityTest {
    private val objectName="oWorkshopRepairClip0123456789abcdef"
    private val path="/video/tos/alisg/tos-alisg-ve-001c001-sg/$objectName/"
    private val before="https://v45.tiktokcdn-eu.com/"+"a".repeat(32)+"/6ae10001$path?a=1233&ft=OpaqueDeliveryToken0123456789&mime_type=video_mp4&rc=SharedEncodingValue&l=20270101120000ABCDEF0123456789ABCDEF"
    private val after=before.replace("a".repeat(32),"b".repeat(32)).replace("6ae10001","6ae10002")
        .replace("OpaqueDeliveryToken0123456789","OpaqueDeliveryToken9876543210").replace("20270101120000","20270101120100")

    @Test fun expiringDeliveryPrefixesAndTransportTokensDoNotInventAnotherMediaObject() {
        val key=requireNotNull(VideoAssetIdentity.observationKey(before))
        assertEquals(key,VideoAssetIdentity.observationKey(after));assertNotEquals(before,after)
        assertNull(VideoAssetIdentity.key(before));assertNull(VideoAssetIdentity.key(after))
        assertEquals(after,VideoAssetIdentity.sampleUrl(Media(after,"video")))
    }
    @Test fun theWholeObjectNamespaceAuthorityAndUnrecognizedQuerySemanticsRemainDistinct() {
        val key=requireNotNull(VideoAssetIdentity.observationKey(before))
        for(other in listOf(after.replace(objectName,objectName+"2"),after.replace("alisg/","maliva/"),
            after.replace("ve-001c001-sg","ve-001c002-sg"),after.replace("v45.","v46."),
            after+"&crop=top",after.replace("video_mp4","audio_mp4"),after.replace("SharedEncodingValue","DifferentEncodingValue")))
            assertNotEquals(other,key,VideoAssetIdentity.observationKey(other))
        assertEquals(VideoAssetIdentity.observationKey(before+"&crop=top"),VideoAssetIdentity.observationKey(after+"&crop=top"))
    }
    @Test fun malformedRoutesCredentialsAndLookalikeHostsDoNotQualify() {
        for(other in listOf(before.replace("https:","http:"),before.replace("https://","https://user@"),
            before.replace(".com/",".com:8443/"),before.replace(".com/",".com.evil.example/"),before+"#t=1,5",
            before.replace("/6ae10001/","/not-an-expiry/"),before.replace("a".repeat(32),"short"),
            before.replace("/video/tos/","/image/tos/"),before.replace(objectName,"short"),
            before.replace("/video/tos/","/video%2Ftos/"),before+"&ft=AnotherDeliveryToken0123456789",before+"&l=duplicate",
            before.replace("&ft=","&%66t="),before.replace("&l=","&%6c="),before+"&%66t=AnotherDeliveryToken0123456789"))
            assertNull(other,VideoAssetIdentity.observationKey(other))
    }
}
