/* anti social, GPL-3.0-or-later. Synthetic public recording declarations. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DeclaredVideoTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject))
    private val source=routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"}
    private val base=source.root.trimEnd('/')
    private val target=routes.target(Platform.X,"Workshop")
    private val stream="https://video.twimg.com/amplify_video/710001/vid/avc1/1920x1080/demo.mp4?tag=29"
    private val proxy="$base/video/signed/"+DarwinRoutes.encode(stream)
    private val poster="$base/pic/amplify_video_thumb%2F710001%2Fimg%2Fcover.jpg%3Fname%3Dsmall"
    private val profile="""<div class=profile-card><a class=profile-card-fullname href=/Workshop>Community Workshop</a><a class=profile-card-username href=/Workshop>@Workshop</a></div>"""
    private fun card(id:String,author:String,body:String,repost:Boolean=false,extra:String="")="""<div class=timeline-item><a class=tweet-link href=/$author/status/$id></a><a class=username>@$author</a><span class=tweet-date><a title='2027-01-15T12:00:00Z'></a></span>${if(repost)"<div class=retweet-header>Community Workshop retweeted</div>"else ""}<div class=tweet-content>$body</div><div class=attachments><div class=gallery-video><div class=attachment><img src='$poster'><div class=video-overlay><form action=/enablehls method=post><button>Enable hls playback</button></form></div><a class=video-download href='$proxy' download>Download video</a></div></div>$extra</div></div>"""
    private fun parse(html:String,selected:Source=source)=Extraction.parse(Payload(html,"$base/Workshop","text/html",200),target,selected,routes).posts
    private fun pair()=parse(profile+card("810001","Workshop","The workshop team demonstrates the complete repair process.")+card("810002","RepairCrew","Here is how we restored the machine, with instructions for every step.",true))

    @Test fun disabledHlsStillRetainsThePublishedProgressiveDownloadAndPoster() {
        val media=pair().first().media.single()
        assertEquals("video",media.type);assertEquals(proxy,media.url);assertEquals(poster,media.preview)
        assertEquals("video/mp4",media.mimeType)
    }
    @Test fun oneDeclaredRecordingCollectsTheAuthoredAndExplicitlyRepostedCaptionsWithoutPixels() {
        val posts=pair();assertEquals("twitter:workshop",CrossPosts.originalAccount(posts[0]));assertNotNull(posts[1].publicRelations);assertTrue(DeclaredVideo.same(posts[0],posts[1]));val group=CrossPosts.group(posts,emptyList()).single()
        assertEquals(posts.toSet(),group.posts.toSet())
        assertEquals(setOf("twitter:workshop","twitter:repaircrew"),group.posts.map(CrossPosts::originalAccount).toSet())
        assertTrue(CrossPosts.sameSourceRepeats(group.posts))
        assertEquals(group,CrossPosts.group(posts.reversed(),emptyList()).single())
    }
    @Test fun retainedPreviewOnlySnapshotsCanUseTheSameDeclaredRecordingWithoutInventingPlayback() {
        val posts=pair().map {it.copy(media=listOf(Media(poster)))}
        assertEquals(posts.toSet(),CrossPosts.group(posts,emptyList()).single().posts.toSet())
        assertTrue(CrossPosts.sameSourceRepeats(posts));assertTrue(posts.all {it.media.single().type=="image"})
    }
    @Test fun genericCoverEqualityOtherAuthorsAndUnqualifiedSourcesCannotCollectDifferentCaptions() {
        val posts=pair()
        val bad=listOf(
            posts[1].copy(publicRelations=null),
            posts[1].copy(publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/Other"))),
            posts[1].copy(publishedAt=posts[0].publishedAt!!+7*60*60_000L),
            posts[1].copy(parentId="parent"),posts[1].copy(preview=true),
            posts[1].copy(media=listOf(Media(proxy.replace("710001","710002"),"video",poster))),
            posts[1].copy(provenance=posts[1].provenance!!.copy(source=source.copy(frontend="other"))),
            posts[1].copy(provenance=posts[1].provenance!!.copy(finalUrl="https://elsewhere.example/Workshop")),
            posts[1].copy(media=posts[1].media+Media("https://media.example/another.mp4","video")))
        for(other in bad) {
            assertEquals(other.toString(),2,CrossPosts.group(listOf(posts[0],other),emptyList()).size)
            assertFalse(CrossPosts.sameSourceRepeats(listOf(posts[0],other)))
        }
        val stills=posts.map {it.copy(media=listOf(Media("https://media.example/same-cover.jpg")))}
        assertEquals(2,CrossPosts.group(stills,emptyList()).size)
    }
    @Test fun aDownloadInsideAQuoteOrArbitraryExternalLinkCannotBecomeTheOuterVideo() {
        val ordinary=card("810001","Workshop","A public workshop announcement.")
        assertTrue(parse(ordinary,source.copy(frontend="other")).single().media.none {it.type=="video"})
        assertTrue(parse(ordinary.replace(proxy,"https://other.example/movie.mp4")).single().media.none {it.type=="video"})
        val quote="""<div class=timeline-item><a class=tweet-link href=/Workshop/status/810003></a><div class=tweet-content>Our own caption.</div><div class=quote>$ordinary</div></div>"""
        assertTrue(parse(quote).single().media.none {it.type=="video"})
    }
    @Test fun sourceScopedPercentAndBase64ProxiesAgreeWithoutChangingTransportOrTrustingOtherHosts() {
        val post=pair().first();val media=post.media.single()
        val encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(stream.toByteArray())
        assertEquals("twitter:amplify_video:710001",DeclaredVideo.key(post,media))
        assertEquals(DeclaredVideo.key(post,media),DeclaredVideo.key(post,media.copy(url="$base/video/enc/signed/$encoded")))
        for(url in listOf(proxy.replace(base,"https://other.example"),proxy+"?extra=1",proxy.replace("signed/","signed/extra/"),
            "$base/video/signed/"+DarwinRoutes.encode(stream.replace("video.twimg.com","video.twimg.com.evil.example")),
            "$base/video/signed/"+DarwinRoutes.encode(stream.replace("https://","https://user:pass@")),
            "$base/video/signed/"+DarwinRoutes.encode(DarwinRoutes.encode(stream))))assertNull(url,DeclaredVideo.key(post,media.copy(url=url)))
        assertEquals(media,post.media.single())
    }
    @Test fun recurringUseCannotChainAcrossTheSixHourCollectionWindow() {
        val first=pair().first();val second=first.copy(id="810003",canonicalUrl=first.canonicalUrl.replace("810001","810003"),body="Another complete description.",publishedAt=first.publishedAt!!+5*60*60_000L)
        val third=second.copy(id="810004",canonicalUrl=second.canonicalUrl.replace("810003","810004"),body="A third complete description.",publishedAt=first.publishedAt!!+10*60*60_000L)
        val groups=CrossPosts.group(listOf(first,second,third),emptyList())
        assertEquals(2,groups.size);assertTrue(groups.all {it.posts.size<=2})
        assertEquals(groups.map {it.posts.toSet()}.toSet(),CrossPosts.group(listOf(third,first,second),emptyList()).map {it.posts.toSet()}.toSet())
        assertFalse(CrossPosts.sameSourceRepeats(listOf(first,second,third)))
    }
    @Test fun anotherCaptionOnTheSameRecordingExpandsRatherThanStealsAnExistingCrossPlatformCollection() {
        val posts=pair();val first=posts.first()
        val facebook=first.copy(id="810003",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/Workshop/posts/810003",
            publishedAt=first.publishedAt!!+60_000,provenance=null,publicRelations=null)
        val follows=listOf(Platform.X,Platform.FACEBOOK).map {platform->
            Follow(platform.id,routes.target(platform,"Workshop"),"Workshop",personId="workshop")
        }
        assertEquals(2,CrossPosts.group(listOf(first,facebook),follows).single().posts.size)
        val merged=CrossPosts.group(posts+facebook,follows).single()
        assertEquals((posts+facebook).toSet(),merged.posts.toSet())
        assertTrue(CrossPosts.declaredRecordingCollection(merged.posts))
        assertFalse(CrossPosts.declaredRecordingCollection(posts+facebook.copy(body="An unrelated meeting has been cancelled.")))
        assertEquals(2,CrossPosts.group(posts+facebook.copy(body="An unrelated meeting has been cancelled."),follows).size)
    }
}
