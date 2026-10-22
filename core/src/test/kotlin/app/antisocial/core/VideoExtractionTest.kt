package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** Public response fixtures: parsing only, no scripts or external network. */
class VideoExtractionTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private fun parse(platform: Platform,body: String,frontend: String="original",path: String?=null): Post {
        val target=routes.recognize(path ?: when(platform) {
            Platform.REDDIT->"https://www.reddit.com/r/test/comments/abc/a_post/"
            Platform.TELEGRAM->"https://t.me/example/123"
            else->"https://x.com/fixture/status/123"
        })!!
        val root=if(frontend=="original")platform.origin else "https://frontend.test"
        val source=Source(platform,frontend,root,role=if(frontend=="original")SourceRole.ORIGINAL else SourceRole.ALTERNATE)
        return Extraction.parse(Payload(body,root+java.net.URI(target.canonicalUrl).rawPath,"text/html",200),target,source,routes).posts.single()
    }
    private fun card(video: String)="""<div class="timeline-item"><a class="tweet-link" href="/fixture/status/123"></a><div class="tweet-content">Public video</div><div class="attachments">$video</div></div>"""
    private fun reddit(video: String,container: String="secure_media")="""{"permalink":"/r/test/comments/abc/a_post/","title":"Public video","author":"fixture","$container":{"reddit_video":$video}}"""

    @Test fun nitterLazyAdaptiveVideoSurvivesWithoutJavascript() {
        // Nitter renderVideoAttachment emits data-url, not src, for HLS.
        val post=parse(Platform.X,card("""<video poster="/preview.jpg" data-url="/video/playlist.m3u8?token=a&amp;part=b" data-autoload="false"></video>"""),"nitter")
        assertEquals("https://frontend.test/video/playlist.m3u8?token=a&part=b",post.media.single().url)
        assertEquals("https://frontend.test/preview.jpg",post.media.single().preview)
        assertEquals("video",post.media.single().type)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun nitterSignedOpaqueProxyKeepsItsRouteAndGetsTheRightDecoder() {
        val original="https://video.twimg.com/amplify_video/123/pl/playlist.m3u8?tag=14"
        val encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(original.toByteArray())
        val proxy="/video/enc/signature/$encoded"
        val post=parse(Platform.X,card("""<video data-url="$proxy" poster="/preview.jpg"></video>"""),"nitter")
        assertEquals("https://frontend.test$proxy",post.media.single().url)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun nitterPercentEncodedProxyKeepsItsSignedUrl() {
        val original="https://video.twimg.com/123/playlist.m3u8?tag=14"
        val proxy="/video/signature/"+java.net.URLEncoder.encode(original,"UTF-8")
        val post=parse(Platform.X,card("""<video data-url="$proxy"></video>"""),"nitter")
        assertEquals("https://frontend.test$proxy",post.media.single().url)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun alternativesStayOneAttachmentWithPosterAndMimeHint() {
        val post=parse(Platform.X,card("""<video poster="/preview.jpg"><source src="/adaptive" type="application/vnd.apple.mpegurl"><source src="/movie" type="video/mp4; codecs=avc1"><source src="/other.webm" type="video/webm"></video>"""),"nitter")
        assertEquals(1,post.media.size)
        assertEquals("https://frontend.test/movie",post.media.single().url)
        assertEquals("video/mp4",post.media.single().mimeType)
        assertEquals("https://frontend.test/preview.jpg",post.media.single().preview)
    }
    @Test fun unsupportedAndUnsafeAlternativesDoNotDisplacePlayableVideo() {
        val post=parse(Platform.X,card("""<video src="blob:https://frontend.test/browser-state"><source src="javascript:alert(1)" type="video/mp4"><source src="https://user:pass@media.test/private.mp4" type="video/mp4"><source src="/flash" type="application/x-shockwave-flash"><source data-src="/manifest" type="application/dash+xml"></video>"""),"nitter")
        assertEquals("https://frontend.test/manifest",post.media.single().url)
        assertEquals("application/dash+xml",post.media.single().mimeType)
    }
    @Test fun redditPrefersExposedAdaptiveAudioVideoOverSilentProgressiveFallback() {
        val post=parse(Platform.REDDIT,reddit("""{"hls_url":"https://v.redd.it/abc/HLSPlaylist.m3u8?a=1&amp;b=2","dash_url":"https://v.redd.it/abc/DASHPlaylist.mpd","fallback_url":"https://v.redd.it/abc/DASH_720.mp4"}"""))
        assertEquals("https://v.redd.it/abc/HLSPlaylist.m3u8?a=1&b=2",post.media.single().url)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun redditDashMayBeExtensionlessAndAnInvalidHlsDoesNotHideIt() {
        val post=parse(Platform.REDDIT,reddit("""{"hls_url":"javascript:ignored","dash_url":"https://media.example/manifest?token=signed","fallback_url":"https://media.example/video.mp4"}""","media"))
        assertEquals("https://media.example/manifest?token=signed",post.media.single().url)
        assertEquals("application/dash+xml",post.media.single().mimeType)
    }
    @Test fun redditRetainsProgressiveFallbackWhenNoAdaptiveStreamWasSupplied() {
        val post=parse(Platform.REDDIT,reddit("""{"fallback_url":"https://v.redd.it/abc/DASH_720.mp4"}"""))
        assertEquals("https://v.redd.it/abc/DASH_720.mp4",post.media.single().url)
        assertEquals("video/mp4",post.media.single().mimeType)
    }
    @Test fun redditPublicVideoPreviewCanSupplyTheActualStream() {
        val post=parse(Platform.REDDIT,"""{"permalink":"/r/test/comments/abc/a_post/","title":"Public video","preview":{"reddit_video_preview":{"hls_url":"https://media.example/preview.m3u8"}}}""")
        assertEquals("https://media.example/preview.m3u8",post.media.single().url)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun malformedXVariantsDoNotAbortThePostOrBeatAUsableStream() {
        val post=parse(Platform.X,"""{"id_str":"123","full_text":"Public video","user":{"screen_name":"fixture"},"video":{"poster":"https://media.example/preview.jpg","variants":[{"type":"video/mp4","bitrate":999999,"src":"javascript:ignored"},{"type":"video/mp4","bitrate":"broken","src":"https://media.example/low.mp4"},{"type":"video/mp4","bitrate":1200,"src":"https://media.example/good.mp4"}]}}""")
        assertEquals("https://media.example/good.mp4",post.media.single().url)
        assertEquals("video/mp4",post.media.single().mimeType)
    }
    @Test fun xExtendedEntitiesPreserveSeparateVideoAndGalleryAttachments() {
        val post=parse(Platform.X,"""{"id_str":"123","full_text":"Public gallery","user":{"screen_name":"fixture"},"extended_entities":{"media":[{"type":"photo","media_url_https":"https://media.example/photo.jpg","ext_alt_text":"A diagram"},{"type":"video","media_url_https":"https://media.example/poster.jpg","video_info":{"variants":[{"content_type":"application/x-mpegURL","url":"https://media.example/manifest"}]}}]}}""")
        assertEquals(listOf("image","video"),post.media.map {it.type})
        assertEquals("A diagram",post.media[0].description)
        assertEquals("https://media.example/poster.jpg",post.media[1].preview)
        assertEquals("application/x-mpegURL",post.media[1].mimeType)
    }
    @Test fun rssNestedVideoAlternativesRemainOnePlayableItem() {
        val post=parse(Platform.X,"""<rss><channel><item><link>https://x.com/fixture/status/123</link><description><![CDATA[<p>Public video</p><video poster="https://media.example/poster.jpg"><source src="https://media.example/playlist" type="application/x-mpegURL"><source src="https://media.example/movie" type="video/mp4"></video>]]></description></item></channel></rss>""")
        assertEquals("https://media.example/movie",post.media.single().url)
        assertEquals("video/mp4",post.media.single().mimeType)
        assertEquals("https://media.example/poster.jpg",post.media.single().preview)
    }
    @Test fun existingMediaSnapshotsRemainReadableWithoutAMimeHint() {
        val media=Gson().fromJson("""{"url":"https://media.example/movie.mp4","type":"video","preview":"https://media.example/poster.jpg"}""",Media::class.java)
        assertEquals("video",media.type);assertNull(media.mimeType)
        assertEquals("https://media.example/poster.jpg",media.preview)
    }
    @Test fun openGraphUsesSecureVideoAndAnExtensionlessMimeHint() {
        val post=parse(Platform.X,"""<meta property="og:url" content="https://x.com/fixture/status/123"><meta property="og:description" content="Public video"><meta property="og:video" content="https://media.example/older"><meta property="og:video:secure_url" content="https://media.example/stream?token=signed"><meta property="og:video:type" content="application/vnd.apple.mpegurl"><meta property="og:image" content="https://media.example/poster.jpg">""")
        assertEquals("https://media.example/stream?token=signed",post.media.single().url)
        assertEquals("application/x-mpegURL",post.media.single().mimeType)
    }
    @Test fun openGraphWebEmbedsArePreviewImagesRatherThanFakeVideos() {
        val post=parse(Platform.X,"""<meta property="og:url" content="https://x.com/fixture/status/123"><meta property="og:description" content="Public preview"><meta property="og:video" content="https://media.example/embed"><meta property="og:video:type" content="text/html"><meta property="og:image" content="https://media.example/poster.jpg">""")
        assertEquals("image",post.media.single().type)
        assertEquals("https://media.example/poster.jpg",post.media.single().url)
    }
}
