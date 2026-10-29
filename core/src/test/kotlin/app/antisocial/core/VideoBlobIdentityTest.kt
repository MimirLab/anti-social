/* anti social, GPL-3.0-or-later. Synthetic content IDs, no reader captures. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class VideoBlobIdentityTest {
    private val did="did:plc:abcdefghijklmnopqrstuvwx"
    private fun cid(codec:Int=0x55,hash:Int=0x12):String {
        val bytes=byteArrayOf(1,codec.toByte(),hash.toByte(),32)+MessageDigest.getInstance("SHA-256").digest("synthetic-video-fixture".toByteArray())
        val alphabet="abcdefghijklmnopqrstuvwxyz234567";var buffer=0;var bits=0
        return buildString {
            append('b')
            for(byte in bytes) {
                buffer=(buffer shl 8)or(byte.toInt()and 255);bits+=8
                while(bits>=5){bits-=5;append(alphabet[(buffer ushr bits)and 31])}
                buffer=buffer and((1 shl bits)-1)
            }
            if(bits>0)append(alphabet[(buffer shl(5-bits))and 31])
        }
    }
    private fun url(did:String=this.did,cid:String=cid())="https://pds-cache.fxbsky.app/$did/$cid"
    @Test fun aCanonicalSourceBlobHasAStableAccountScopedClipKey() {
        assertEquals("bluesky-blob:$did:${cid()}",VideoAssetIdentity.key(url()))
        assertNotEquals(VideoAssetIdentity.key(url()),VideoAssetIdentity.key(url(did=did.replace('a','z'))))
        assertTrue(VideoAssetIdentity.validSampling(Media(url(),"video",samplingUrl=url())))
        assertNull(VideoFormats.mime(Media(url(),"video"))) // Still requires the existing container probe.
    }
    @Test fun foreignHostsCredentialsPathsQueriesAndNonCanonicalContentIdsDoNotQualify() {
        val blob=cid();val alphabet="abcdefghijklmnopqrstuvwxyz234567";val badPad=blob.dropLast(1)+alphabet[alphabet.indexOf(blob.last())or 1]
        for(value in listOf(url().replace("pds-cache.fxbsky.app","pds-cache.fxbsky.app.example.org"),url().replace("https://","http://"),
            url().replace("https://","https://name:secret@"),url().replace(".app/",".app:8443/"),url()+"?transform=crop",url()+"#copy",url()+"/",
            url().replace("did:plc:","did%3Aplc%3A"),url(did="did:plc:short"),url(cid=blob.uppercase()),url(cid=blob.dropLast(1)),url(cid=badPad),
            url(cid=cid(codec=0x71)),url(cid=cid(hash=0x13))))assertNull(value,VideoAssetIdentity.key(value))
    }
}
