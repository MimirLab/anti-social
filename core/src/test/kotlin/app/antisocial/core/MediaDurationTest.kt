/* AntiSocial, GPL-3.0-or-later. Public data fixtures only; no media downloads. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MediaDurationTest {
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private fun obj(raw: String)=JsonParser.parseString(raw).asJsonObject
    private fun target(platform: Platform)=routes.recognize(when(platform) {
        Platform.REDDIT->"https://www.reddit.com/r/test/comments/abc/a_post/"
        Platform.INSTAGRAM->"https://www.instagram.com/p/first/"
        Platform.THREADS->"https://www.threads.net/@fixture/post/first"
        else->"https://x.com/fixture/status/123"
    })!!
    private fun parse(platform: Platform,raw: String): Page {
        val target=target(platform)
        val source=Source(platform,"original",platform.origin,role=SourceRole.ORIGINAL)
        return Extraction.parse(Payload(raw,target.canonicalUrl,"text/html",200),target,source,routes)
    }
    private fun reddit(secure: String,extra: String="")="""{"permalink":"/r/test/comments/abc/a_post/","title":"Video","secure_media":{"reddit_video":$secure}$extra}"""
    private fun video(url: String,duration: String="",extra: String="")="""{"type":"video","media_url_https":"https://media.example/poster.jpg","video_info":{"variants":[{"url":"https://media.example/$url.mp4","content_type":"video/mp4"}]$duration}$extra}"""

    @Test fun secondsAndMillisecondsHaveExplicitUnitsAndBoundedConversion() {
        assertEquals(88_654L,MediaDuration.seconds(JsonParser.parseString("88.654")))
        assertEquals(88_654L,MediaDuration.milliseconds(JsonParser.parseString("88654")))
        assertEquals(1L,MediaDuration.seconds("0.001"))
        assertEquals(9999L,MediaDuration.seconds("9.9999"))
    }

    @Test fun invalidAndUnboundedSuppliedValuesRemainUnknown() {
        listOf("null","true","false","{}","[]","0","-1","\"NaN\"","\"Infinity\"","\"-Infinity\"","\"bad\"","\"PT10M\"","1e1000000000","1e-1000000000","0.00001","31622401").forEach {raw->
            assertNull(raw,MediaDuration.seconds(JsonParser.parseString(raw)))
        }
        assertNull(MediaDuration.milliseconds(JsonParser.parseString("9223372036854775807")))
        assertNull(MediaDuration.seconds("9".repeat(65)))
    }

    @Test fun redditRuntimeBelongsToTheSelectedPlayableContainer() {
        val raw=reddit("""{"hls_url":"javascript:ignored","duration":900}""", """, "media":{"reddit_video":{"fallback_url":"https://media.example/selected.mp4","duration":65.25}},"duration":9999""")
        val media=parse(Platform.REDDIT,raw).posts.single().media.single()
        assertEquals("https://media.example/selected.mp4",media.url)
        assertEquals(65_250L,media.durationMs)
    }

    @Test fun redditDoesNotBorrowThePreviewOrParentRuntimeForSelectedMedia() {
        val raw=reddit("""{"fallback_url":"https://media.example/selected.mp4"}""", """, "preview":{"reddit_video_preview":{"hls_url":"https://media.example/other.m3u8","duration":900}},"duration":9999""")
        assertNull(parse(Platform.REDDIT,raw).posts.single().media.single().durationMs)
    }

    @Test fun redditInvalidRuntimeDoesNotRemovePlayableMedia() {
        val post=parse(Platform.REDDIT,reddit("""{"fallback_url":"https://media.example/selected.mp4","duration":"NaN"}""")).posts.single()
        assertEquals("video",post.media.single().type)
        assertNull(post.media.single().durationMs)
    }

    @Test fun xRuntimeStaysWithItsOwnAttachmentRatherThanSiblingOrVariant() {
        val attachments=listOf(video("first",",\"duration_millis\":123456"),video("second",extra=",\"duration_millis\":999999"),"""{"type":"photo","media_url_https":"https://media.example/image.jpg","duration_millis":999999}""").joinToString(",")
        val raw="""{"id_str":"123","full_text":"Public videos","user":{"screen_name":"fixture"},"duration_millis":999999,"extended_entities":{"media":[$attachments]}}"""
        val media=parse(Platform.X,raw).posts.single().media
        assertEquals(listOf(123456L,null,null),media.map {it.durationMs})
    }

    @Test fun instagramRelatedPostsKeepTheirOwnRuntimeAndImagesStayUnknown() {
        val raw="""[{"shortcode":"first","caption":"First","video_url":"https://media.example/first.mp4","video_duration":600.125,"related":{"shortcode":"second","caption":"Second","video_url":"https://media.example/second.mp4"}},{"shortcode":"image","caption":"Image","display_url":"https://media.example/image.jpg","video_duration":9999}]"""
        val posts=parse(Platform.INSTAGRAM,raw).posts.associateBy {it.canonicalUrl.substringAfterLast('/')}
        assertEquals(600125L,posts.getValue("first").media.single().durationMs)
        assertNull(posts.getValue("second").media.single().durationMs)
        assertNull(posts.getValue("image").media.single().durationMs)
    }

    @Test fun threadsVideoKeepsSuppliedRuntimeWithoutUsingItsCaption() {
        val raw="""{"code":"first","text_post_app_info":{},"user":{"username":"fixture"},"caption":{"text":"A 2-hour stream"},"video_versions":[{"url":"https://media.example/first.mp4"}],"video_duration":15.5}"""
        assertEquals(15500L,parse(Platform.THREADS,raw).posts.single().media.single().durationMs)
    }

    @Test fun mastodonRuntimeIsAttachmentScopedAndDoesNotBorrowFromQuotedPosts() {
        val raw="""{"id":"12","url":"https://social.example/@fixture/12","content":"<p>Selected</p>","account":{"username":"fixture"},"media_attachments":[{"type":"video","url":"https://media.example/first.mp4","meta":{"duration":88.65,"original":{"duration":88.654}}},{"type":"video","url":"https://media.example/second.mp4"},{"type":"image","url":"https://media.example/image.jpg","meta":{"duration":9999}}],"quote":{"media_attachments":[{"type":"video","url":"https://media.example/unrelated.mp4","meta":{"duration":9999}}]}}"""
        val provenance=Provenance(Source(Platform.MASTODON,"mastodon-api","https://social.example"),1,"https://social.example/@fixture/12")
        val post=PublicSocialParsing.mastodonStatus(obj(raw),Platform.MASTODON,provenance,"https://social.example")!!
        assertEquals(listOf(88654L,null,null),post.media.map {it.durationMs})
        val reply=PublicSocialParsing.mastodonStatus(obj(raw.replace("\"12\"","\"13\"").replace("/12","/13").replace("88.654","2.5")),Platform.MASTODON,provenance,"https://social.example")!!
        assertEquals(2500L,reply.media.first().durationMs)
        assertEquals(88654L,post.media.first().durationMs)
    }

    @Test fun mastodonFallsBackToItsOwnValidMetaRuntime() {
        val raw="""{"id":"12","url":"https://social.example/@fixture/12","content":"Video","account":{},"media_attachments":[{"type":"video","url":"https://media.example/first.mp4","meta":{"duration":12.25,"original":{"duration":-1}}}]}"""
        val p=Provenance(Source(Platform.MASTODON,"mastodon-api","https://social.example"),1,"https://social.example")
        assertEquals(12250L,PublicSocialParsing.mastodonStatus(obj(raw),Platform.MASTODON,p,"https://social.example")!!.media.single().durationMs)
    }

    @Test fun mediaRssDurationAppliesOnlyToItsOwnVideoContentElement() {
        val raw="""<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item><link>https://x.com/fixture/status/123</link><title>First</title><media:content url="https://media.example/first.mp4" type="video/mp4" duration="601.125"/><media:content url="https://media.example/image.jpg" type="image/jpeg" duration="9999"/></item><item><link>https://x.com/fixture/status/124</link><title>Second</title><enclosure url="https://media.example/second.mp4" type="video/mp4" duration="9999"/></item></channel></rss>"""
        val posts=parse(Platform.X,raw).posts
        assertEquals(listOf(601125L,null),posts.first().media.map {it.durationMs})
        assertNull(posts.last().media.single().durationMs)
    }

    @Test fun duplicateStreamKeepsItsPosterAndTheMatchingMediaRssRuntime() {
        val raw="""<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item><link>https://x.com/fixture/status/123</link><description><![CDATA[<p>Video</p><video src="https://media.example/first.mp4" poster="https://media.example/poster.jpg"></video>]]></description><media:content url="https://media.example/first.mp4" type="video/mp4" duration="601.125"/></item></channel></rss>"""
        val media=parse(Platform.X,raw).posts.single().media.single()
        assertEquals(601125L,media.durationMs)
        assertEquals("https://media.example/poster.jpg",media.preview)
    }

    @Test fun htmlLabelsAndUnsupportedSchemaDurationsRemainUnknown() {
        val raw="""<div class="timeline-item"><a class="tweet-link" href="https://x.com/fixture/status/123"></a><div class="tweet-content">Long video 01:00:00</div><video src="https://media.example/first.mp4" data-duration="3600"></video></div>"""
        assertNull(parse(Platform.X,raw).posts.single().media.single().durationMs)
        val schema="""{"@type":"VideoObject","url":"https://x.com/fixture/status/123","description":"A video","contentUrl":"https://media.example/first.mp4","duration":"PT1H"}"""
        assertNull(parse(Platform.X,schema).posts.single().media.single().durationMs)
    }

    @Test fun savedSnapshotRoundTripPreservesRuntimeAndLegacyMediaRemainReadable() {
        val post=Post("id",Platform.X,"https://x.com/fixture/status/123","Fixture","Video",media=listOf(Media("https://media.example/first.mp4","video",durationMs=601125)))
        val gson=Gson()
        val snapshot=gson.fromJson(gson.toJson(PostSnapshot.from(post)),PostSnapshot::class.java)
        assertEquals(601125L,snapshot.asPost(post).media.single().durationMs)
        assertEquals(601125L,post.media.single().copy(preview="https://media.example/poster.jpg").durationMs)
        assertNull(gson.fromJson("""{"url":"https://media.example/old.mp4","type":"video"}""",Media::class.java).durationMs)
    }
}
