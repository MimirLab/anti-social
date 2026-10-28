/* anti social, GPL-3.0-or-later. Synthetic bounded container fixtures. */
package app.antisocial.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class VideoContainerPrefixTest {
    private fun box(type:String,body:ByteArray)=ByteBuffer.allocate(8+body.size).putInt(8+body.size).put(type.toByteArray()).put(body).array()
    private fun mp4(brand:String="isom",compatible:String="mp42")=box("ftyp",brand.toByteArray()+ByteArray(4)+compatible.toByteArray())
    private fun ebml(type:String="webm"):ByteArray {
        val body=byteArrayOf(0x42,0x86.toByte(),0x81.toByte(),1,0x42,0x82.toByte(),(0x80+type.length).toByte())+type.toByteArray()
        return byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),(0x80+body.size).toByte())+body
    }
    @Test fun progressiveContainerHeadersWorkWithoutAFileSuffixOrHttpType() {
        assertEquals("video/mp4",VideoFormats.sniff(mp4()))
        assertEquals("video/mp4",VideoFormats.sniff(mp4("avc1")))
        assertEquals("video/mp4",VideoFormats.sniff(mp4("xxxx","mp41")))
        assertEquals("video/mp4",VideoFormats.sniff(box("free",ByteArray(8))+mp4()))
        assertEquals("video/webm",VideoFormats.sniff(ebml()))
    }
    @Test fun imageAudioAndUnrelatedEbmlHeadersDoNotBecomeVideoHints() {
        for(brand in listOf("avif","avis","heic","heix","mif1","M4A "))assertNull(brand,VideoFormats.sniff(mp4(brand)))
        assertNull(VideoFormats.sniff(ebml("matroska")))
        assertNull(VideoFormats.sniff("<html>ftypisom webm Please sign in</html>".toByteArray()))
        assertNull(VideoFormats.sniff("{\"type\":\"video/mp4\",\"title\":\"webm\"}".toByteArray()))
        assertNull(VideoFormats.sniff(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte(),0xff.toByte())))
    }
    @Test fun truncatedHugeOrConflictingHeadersStayUnknownWithoutFollowingTheirSizes() {
        for(size in 0 until mp4().size)assertNull("Truncated MP4 $size",VideoFormats.sniff(mp4().copyOf(size)))
        for(size in 0 until ebml().size)assertNull("Truncated WebM $size",VideoFormats.sniff(ebml().copyOf(size)))
        assertNull(VideoFormats.sniff(ByteBuffer.allocate(16).putInt(Int.MAX_VALUE).put("ftyp".toByteArray()).put("isom".toByteArray()).putInt(0).array()))
        assertNull(VideoFormats.sniff(ByteBuffer.allocate(16).putInt(1).put("ftyp".toByteArray()).putLong(Long.MAX_VALUE).array()))
        assertNull(VideoFormats.sniff(box("free",ByteArray(4096))+mp4()))
        val original=ebml();val duplicate=original+byteArrayOf(0x42,0x82.toByte(),0x84.toByte())+"webm".toByteArray()
        duplicate[4]=(0x80+duplicate.size-5).toByte()
        assertNull(VideoFormats.sniff(duplicate))
    }
    @Test fun extensionlessProgressivePlaybackUsesOneExistingBoundedGatewayProbe()=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(okio.Buffer().write(mp4()+ByteArray(200_000))))
            val contexts=mutableListOf<Pair<String,String>>()
            val gateway=NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),object:RequestFilter {
                override fun check(url:String,sourceUrl:String,type:String):FilterDecision {contexts+=sourceUrl to type;return FilterDecision(false)}
            },true)
            val media=Media(server.url("/opaque").toString(),"video")
            assertEquals("video/mp4",VideoFormats.resolve(media,"https://publisher.example/post/1",gateway))
            assertEquals("bytes=0-4095",server.takeRequest().getHeader("Range"))
            assertEquals(listOf("https://publisher.example/post/1" to "media"),contexts)
            assertEquals(1,server.requestCount)
        }
    }
}
