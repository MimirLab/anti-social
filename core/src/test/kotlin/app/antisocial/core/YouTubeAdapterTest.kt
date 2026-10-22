/* AntiSocial, GPL-3.0-or-later. Public-reader fixtures, independent of live availability. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class YouTubeAdapterTest {
    private val id="BaW_jenozKc"
    private val second="abcdefghijk"
    private val channel="UC_x5XG1OV2P6uZZ5FSM9Ttw"
    private val canonical="https://www.youtube.com/watch?v=$id"
    private fun setup(filter: RequestFilter=object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(false)}): Pair<DarwinRoutes,NetworkGateway> {
        val cleaner=UrlCleaner(JsonParser.parseString("""{"providers":{}}""").asJsonObject)
        val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
        return routes to NetworkGateway(cleaner,filter,true)
    }
    private fun source(server: MockWebServer,piped: Boolean=false)=Source(Platform.YOUTUBE,if(piped)"piped"else "invidious",server.url("/").toString().trimEnd('/'))
    private fun video(videoId: String=id,time: Long=1700000000L,title: String="Battery repair",authorUrl: String="/channel/$channel")="""{"type":"video","videoId":"$videoId","title":"$title","author":"Public workshop","authorUrl":"$authorUrl","description":"<p>A complete repair guide.</p>","published":$time,"lengthSeconds":182,"likeCount":12,"videoThumbnails":[{"url":"https://img.example/$videoId.jpg","width":640}],"formatStreams":[{"url":"https://media.example/$videoId.mp4","type":"video/mp4; codecs=avc1","qualityLabel":"720p"}]}"""
    @Test fun exactInvidiousVideoBecomesANativePostThroughOneAnonymousGatewayRequest(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody(video()))
            val observed=mutableListOf<Triple<String,String,String>>()
            val (routes,gateway)=setup(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String): FilterDecision {observed+=Triple(url,sourceUrl,type);return FilterDecision(false)}})
            val source=source(server);val page=YouTubeAdapter(source,gateway,routes).fetch(YouTubeUrls.recognize(canonical)!!)
            val post=page.posts.single()
            assertEquals(canonical,post.canonicalUrl);assertEquals("A complete repair guide.",post.body);assertEquals("Battery repair",post.title)
            assertEquals(1700000000000L,post.publishedAt);assertEquals(182000L,post.media.single().durationMs)
            assertEquals(canonical,post.media.single().url);assertEquals("video",post.media.single().type)
            assertEquals("https://www.youtube.com/channel/$channel",post.links!!.single());assertEquals(source,post.provenance!!.source)
            val request=server.takeRequest();assertEquals("/api/v1/videos/$id",request.path);assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))
            assertTrue(observed.any {it.second==canonical});assertEquals(1,server.requestCount)
        }
    }
    @Test fun wrongVideosWebLoginPagesAndReaderErrorsNeverBecomeWorkingContent(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (routes,gateway)=setup();val adapter=YouTubeAdapter(source(server),gateway,routes);val target=YouTubeUrls.recognize(canonical)!!
            for(body in listOf(video(second),"<html><form>Sign in to continue</form></html>","""{"error":"Video unavailable"}""")) {
                server.enqueue(MockResponse().setBody(body))
                assertThrows(SourceFailure::class.java){runBlocking {adapter.fetch(target)}}
            }
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun searchIsChronologicalAndItsContinuationCannotCrossQuerySourceOrDirectory(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("[${video(time=10)},${video(second,time=20)}]"));server.enqueue(MockResponse().setBody("[]"))
            val (routes,gateway)=setup();val source=source(server);val adapter=YouTubeAdapter(source,gateway,routes);val target=routes.search(Platform.YOUTUBE,"battery repair")
            val first=adapter.fetch(target);assertEquals(listOf(second,id),first.posts.map {it.canonicalUrl.substringAfter("v=")});assertNotNull(first.next)
            val request=server.takeRequest();assertTrue(request.path!!.contains("sort_by=upload_date"));assertTrue(request.path!!.contains("q=battery%20repair"));assertTrue(request.path!!.contains("page=1"))
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.fetch(routes.search(Platform.YOUTUBE,"different query"),first.next)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {YouTubeAdapter(source.copy(root=source.root+"/other"),gateway,routes).fetch(target,first.next)}}
            assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.directory(target,first.next)}}
            val next=adapter.fetch(target,first.next);assertTrue(next.posts.isEmpty());assertNull(next.next)
            assertTrue(server.takeRequest().path!!.contains("page=2"));assertEquals(2,server.requestCount)
        }
    }
    @Test fun suppliedChannelContinuationsRetainTheChannelAndExactOpaqueToken(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();server.enqueue(MockResponse().setBody("""{"videos":[${video()}],"continuation":"opaque+/next=","id":"$channel"}"""));server.enqueue(MockResponse().setBody("""{"videos":[],"id":"$channel"}"""))
            val (routes,gateway)=setup();val adapter=YouTubeAdapter(source(server),gateway,routes);val target=routes.target(Platform.YOUTUBE,channel)
            val first=adapter.fetch(target);assertNotNull(first.next);assertEquals(1,first.posts.size)
            assertEquals("/api/v1/channels/$channel/videos",server.takeRequest().path)
            adapter.fetch(target,first.next)
            val request=server.takeRequest();assertTrue(request.path!!.startsWith("/api/v1/channels/$channel/videos?"));assertEquals("opaque+/next=",request.requestUrl!!.queryParameter("continuation"))
        }
    }
    @Test fun pipedSearchHasIndependentNativeChannelCardsAndPublicRelativeOrAbsoluteLinks(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (routes,gateway)=setup();val adapter=YouTubeAdapter(source(server,true),gateway,routes);val target=routes.search(Platform.YOUTUBE,"workshop")
            for(path in listOf("/channel/$channel","https://www.youtube.com/channel/$channel")) {
                server.enqueue(MockResponse().setBody("""{"items":[{"type":"channel","url":"$path","name":"Public workshop","subscribers":123,"thumbnail":"https://img.example/avatar.jpg"}],"nextpage":"directory-next"}"""))
                val directory=adapter.directory(target);assertTrue(directory.posts.isEmpty());assertEquals("https://www.youtube.com/channel/$channel",directory.accounts.single().target.canonicalUrl)
                assertEquals("Public workshop",directory.accounts.single().name);assertNotNull(directory.next)
                assertEquals("channels",server.takeRequest().requestUrl!!.queryParameter("filter"))
            }
            server.enqueue(MockResponse().setBody("""{"title":"Public clip","uploader":"Workshop","uploaderUrl":"https://www.youtube.com/channel/$channel","description":"A public clip","thumbnailUrl":"https://img.example/clip.jpg","duration":75,"videoStreams":[]}"""))
            val post=adapter.fetch(YouTubeUrls.recognize(canonical)!!).posts.single()
            assertEquals("https://www.youtube.com/channel/$channel",post.links!!.single());assertEquals(75000L,post.media.single().durationMs)
        }
    }
    @Test fun commentsStayBoundToTheVideoAndRequestTheChosenPublicSort(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (routes,gateway)=setup();val adapter=YouTubeAdapter(source(server),gateway,routes)
            val body="""{"comments":[{"commentId":"Ugy-comment-001","author":"A public reader","content":"<b>Useful comment</b>","published":1700000000,"likeCount":5,"replyCount":2}],"continuation":"comments-next"}"""
            for(sort in listOf("new","top")) {
                server.enqueue(MockResponse().setBody(body))
                val target=YouTubeUrls.recognize(canonical)!!.copy(canonicalUrl="$canonical&sort=$sort")
                val page=adapter.comments(target);val comment=page.posts.single()
                assertEquals("$canonical&lc=Ugy-comment-001",comment.canonicalUrl);assertEquals(ContentIdentity.key(Platform.YOUTUBE,canonical),comment.parentId)
                assertEquals("Useful comment",comment.body);assertEquals(5L,comment.score);assertEquals(1700000000000L,comment.publishedAt)
                assertEquals(sort,server.takeRequest().requestUrl!!.queryParameter("sort_by"));assertNotNull(page.next)
                assertThrows(IllegalArgumentException::class.java){runBlocking {adapter.comments(target.copy(canonicalUrl=YouTubeUrls.canonical(second)),page.next)}}
            }
        }
    }
    @Test fun blockedReaderRequestsAndUnavailableTorFailBeforeOpeningASocket(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val (routes,gateway)=setup();gateway.configure(NetworkPolicy(tor=true,ready=false))
            assertThrows(Exception::class.java){runBlocking {YouTubeAdapter(source(server),gateway,routes).fetch(YouTubeUrls.recognize(canonical)!!)}}
            assertEquals(0,server.requestCount)
            val (blockedRoutes,blockedGateway)=setup(object: RequestFilter {override fun check(url: String,sourceUrl: String,type: String)=FilterDecision(true,"Fixture rule")})
            assertThrows(Exception::class.java){runBlocking {YouTubeAdapter(source(server),blockedGateway,blockedRoutes).fetch(YouTubeUrls.recognize(canonical)!!)}}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun playbackChoosesOnlyContentStreamsAndCanAvoidARejectedTransport(): Unit=runBlocking {
        MockWebServer().use {server->
            server.start();val data=video().dropLast(1)+""", "adPlacements":[{"url":"https://ads.example/pre-roll.mp4"}]}"""
            server.enqueue(MockResponse().setBody(data));server.enqueue(MockResponse().setBody(data))
            val (routes,gateway)=setup();val adapter=YouTubeAdapter(source(server),gateway,routes)
            val playback=adapter.playback(id)
            assertEquals(id,playback.videoId);assertEquals("https://media.example/$id.mp4",playback.media.url);assertEquals("video/mp4",playback.media.mimeType)
            assertFalse(playback.recovered);assertEquals(source(server),playback.source)
            assertThrows(SourceFailure::class.java){runBlocking {adapter.playback(id,setOf(playback.media.url))}}
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun adaptiveSelectionPairsVideoWithAudioAndDoesNotTurnAdObjectsIntoMedia() {
        fun json(value: String)=JsonParser.parseString(value).asJsonObject
        val base="https://reader.example/api/v1/videos/$id"
        val data=json("""{"adaptiveFormats":[{"url":"https://media.example/video.mp4","type":"video/mp4; codecs=avc1","height":1080},{"url":"https://media.example/audio.m4a","type":"audio/mp4; codecs=mp4a","bitrate":128000}],"adPlacements":[{"url":"https://ads.example/pre-roll.mp4","type":"video/mp4","height":1080}]}""")
        val media=YouTubeStreams.select(data,base,false)!!
        assertEquals("https://media.example/video.mp4",media.url);assertEquals("https://media.example/audio.m4a",media.audioUrl);assertEquals("audio/mp4",media.audioMimeType)
        assertNull(YouTubeStreams.select(data,base,false,muxedOnly=true))
        assertNull(YouTubeStreams.select(data,base,false,setOf("https://media.example/audio.m4a")))
        assertNull(YouTubeStreams.select(json("""{"adPlacements":[{"url":"https://ads.example/pre-roll.mp4","type":"video/mp4","height":1080}]}"""),base,false))
        assertNull(YouTubeStreams.select(json("""{"formatStreams":[{"url":"http://media.example/clip.mp4","type":"video/mp4","qualityLabel":"720p"},{"url":"https://user:secret@media.example/clip.mp4","type":"video/mp4","qualityLabel":"720p"}]}"""),base,false))
    }
    @Test fun pipedVideoOnlyStreamsNeedTheirAudioOrARealMuxedAlternative() {
        val data=JsonParser.parseString("""{"videoStreams":[{"url":"https://media.example/silent.mp4","mimeType":"video/mp4","quality":"1080p","videoOnly":true},{"url":"https://media.example/complete.mp4","mimeType":"video/mp4","quality":"720p","videoOnly":false}],"audioStreams":[{"url":"https://media.example/audio.m4a","mimeType":"audio/mp4","bitrate":128000}]}""").asJsonObject
        assertEquals("https://media.example/complete.mp4",YouTubeStreams.select(data,"https://reader.example/streams/$id",true)!!.url)
        val adaptive=YouTubeStreams.select(data,"https://reader.example/streams/$id",true,setOf("https://media.example/complete.mp4"))!!
        assertEquals("https://media.example/silent.mp4",adaptive.url);assertEquals("https://media.example/audio.m4a",adaptive.audioUrl)
    }
}
