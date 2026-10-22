package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class VideoFormatsTest {
    private fun gateway(filter: RequestFilter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)})=
        NetworkGateway(UrlCleaner(JsonObject().apply {add("providers",JsonObject())}),filter,true)
    @Test fun declaredContainerResolvesExtensionlessAndMisleadingEndpoints() {
        assertEquals(VideoFormats.HLS,VideoFormats.mime(Media("https://proxy.example/video/opaque","video",mimeType="Application/Vnd.Apple.MpegURL; codecs=avc1")))
        assertEquals(VideoFormats.DASH,VideoFormats.mime(Media("https://cdn.example/clip.mp4","video",mimeType="application/dash+xml")))
    }
    @Test fun suffixIgnoresQueryTokensAndSupportsEscapedProxyPaths() {
        assertEquals(VideoFormats.HLS,VideoFormats.mime(Media("https://proxy.example/video/https%3A%2F%2Fcdn.example%2Fmaster.m3u8?token=abc.mp4","video")))
        assertEquals("video/mp4",VideoFormats.mime(Media("https://cdn.example/clip.MP4?type=.mpd","video")))
        assertNull(VideoFormats.mime(Media("https://cdn.example/opaque?file=master.m3u8","video")))
    }
    @Test fun boundedPrefixFindsAdaptiveFormatsWithoutTreatingHtmlAsMedia() {
        assertEquals(VideoFormats.HLS,VideoFormats.sniff("\uFEFF \n#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertEquals(VideoFormats.DASH,VideoFormats.sniff("<?xml version=\"1.0\"?>\n<!-- public stream --><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
        assertEquals(VideoFormats.DASH,VideoFormats.sniff("<dash:MPD xmlns:dash=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
        assertNull(VideoFormats.sniff("<html><body>Please sign in<MPD></MPD></body></html>".toByteArray()))
        assertNull(VideoFormats.sniff("<!-- incomplete comment".toByteArray()))
        assertNull(VideoFormats.sniff(ByteArray(100_000){0}))
    }
    @Test fun oldSnapshotsCanInferFormatsWithoutANewField() {
        val media=com.google.gson.Gson().fromJson("""{"url":"https://cdn.example/video.mpd","type":"video"}""",Media::class.java)
        assertNull(media.mimeType);assertEquals(VideoFormats.DASH,VideoFormats.mime(media))
        assertTrue(VideoFormats.adaptive("application/vnd.apple.mpegurl"));assertFalse(VideoFormats.adaptive("video/mp4"))
    }
    @Test fun unknownEndpointUsesABoundedFilteredProbeWithItsOriginalContext()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("#EXTM3U\n"+"x".repeat(200_000)));server.start()
            val contexts=mutableListOf<Pair<String,String>>()
            val gateway=gateway(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {contexts+=sourceUrl to type;return FilterDecision(false)}})
            assertEquals(VideoFormats.HLS,VideoFormats.resolve(Media(server.url("/opaque").toString(),"video"),"https://social.example/post/123",gateway))
            assertEquals("bytes=0-4095",server.takeRequest().getHeader("Range"))
            assertEquals(listOf("https://social.example/post/123" to "media"),contexts)
            assertEquals(1L,gateway.protectionStats().checked)
        }
    }
    @Test fun blockedAndUnavailableTorProbesNeverReachTheSource()=runBlocking {
        MockWebServer().use {server->
            server.start();val media=Media(server.url("/opaque").toString(),"video")
            val blocked=gateway(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(true,"fixture")})
            try {VideoFormats.resolve(media,"https://social.example/",blocked);fail("Blocked probe must fail")}catch(_: java.io.IOException){}
            val tor=gateway().apply {configure(NetworkPolicy(tor=true))}
            try {VideoFormats.resolve(media,"https://social.example/",tor);fail("Tor must fail closed")}catch(_: java.io.IOException){}
            assertEquals(0,server.requestCount);assertEquals(1L,blocked.protectionStats().blocked)
            assertEquals(VideoFormats.DASH,VideoFormats.resolve(media.copy(mimeType=VideoFormats.DASH),"https://social.example/",tor))
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun aRangeRefusalCanUseABoundedOrdinaryGetWithoutDroppingTheGateway()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setBody("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"+"x".repeat(100_000)))
            server.start();val gateway=gateway()
            assertEquals(VideoFormats.DASH,VideoFormats.resolve(Media(server.url("/opaque").toString(),"video"),"https://social.example/",gateway))
            assertEquals("bytes=0-4095",server.takeRequest().getHeader("Range"));assertNull(server.takeRequest().getHeader("Range"))
            assertEquals(2L,gateway.protectionStats().checked)
        }
    }
}
