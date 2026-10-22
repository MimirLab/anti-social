package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OriginalLinksTest {
    @Test fun retainedVideoFrontendLiveLinksUseCaptionIdentityInsteadOfArticleReading() {
        assertEquals("https://www.youtube.com/watch?v=M_lGBxyrnio",links.url("https://invidious.tiekoetter.com/live/M_lGBxyrnio?si=sample"))
        assertEquals("M_lGBxyrnio",YouTubeUrls.videoId(links.url("https://invidious.tiekoetter.com/watch?v=M_lGBxyrnio")))
        assertEquals("https://invidious.unknown.example/live/M_lGBxyrnio",links.url("https://invidious.unknown.example/live/M_lGBxyrnio"))
    }
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private val cleaner=UrlCleaner(asset("clean-links.json"))
    private val routes=DarwinRoutes(asset("darwin-routes.json"),cleaner)
    private val links=OriginalLinks(routes,cleaner)
    private fun source(frontend: String)=routes.sources.first {it.frontend==frontend}
    private fun outward(frontend: String,path: String)=links.url(source(frontend).root+path)

    @Test fun realCachedNitterProfileSearchAndBroadcastLinksHaveAuthenticDestinations() {
        assertEquals("https://x.com/WhiteHouse",links.url("https://nitter.tiekoetter.com/WhiteHouse"))
        assertEquals("https://x.com/realDonaldTrump",links.url("https://nitter.net/realDonaldTrump"))
        assertEquals("https://x.com/WhiteHouse/status/123",links.url("https://nitter.net/WhiteHouse/status/123#m"))
        assertEquals("https://x.com/search?f=tweets&q=%23Georgia",links.url("https://nitter.tiekoetter.com/search?f=tweets&q=%23Georgia"))
        assertEquals("https://x.com/i/broadcasts/1qJDzWgYWVVKV",links.url("https://nitter.netbub.com/i/broadcasts/1qJDzWgYWVVKV"))
        assertEquals("https://x.com/WhiteHouse",links.url("https://nitter.net/WhiteHouse/rss"))
    }

    @Test fun everyBundledFrontendReversesAnActualSocialPathWithoutChangingTheRoute() {
        val paths=mapOf(
            "nitter" to "/NASA/status/123", "skyview" to "/profile/nasa.test/post/abc", "skylib" to "/profile/nasa.test/post/abc",
            "redlib" to "/r/space/comments/abc/title/def", "eddrit" to "/r/space/comments/abc/title/def", "troddit" to "/r/space/comments/abc/title/def",
            "priviblur" to "/staff/123/title", "proxiTok" to "/@nasa/video/123", "offtiktok" to "/@nasa/video/123",
            "kittygram" to "/p/ABC", "proxigram" to "/p/ABC", "binternet" to "/pin/123", "pinless" to "/pin/123", "painterest" to "/pin/123",
            "unfunny" to "/picture/ABC", "ratAintTieba" to "/p/123", "shoelace" to "/t/ABC", "invidious" to "/watch?v=BaW_jenozKc")
        assertEquals(routes.sources.filterNot {it.frontend=="piped"}.map {it.frontend}.toSet(),paths.keys)
        for(source in routes.sources.filterNot {it.frontend=="piped"}) {
            val original=source.platform.origin+paths.getValue(source.frontend)
            val target=Target(source.platform,TargetKind.POST,"fixture",original)
            val route=routes.convert(target,source)
            assertEquals(source.key,original,links.url(route))
            assertEquals(source.key,route,routes.convert(target,source))
        }
    }
    @Test fun pipedApiEndpointsRemainTransportAddressesRatherThanInventedPublicPages() {
        for(source in routes.sources.filter {it.frontend=="piped"})for(path in listOf("/streams/BaW_jenozKc","/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw","/watch?v=BaW_jenozKc")) {
            val input=source.root+path
            assertEquals(input,links.url(input));assertNull(routes.recognize(input))
        }
    }

    @Test fun transformationsPreserveIdentityFieldsCaseEncodedPathsAndCommentAnchors() {
        assertEquals("https://www.reddit.com/r/Test/comments/AbC/title/DeF?context=3&comment_id=DeF#t1_DeF",outward("redlib","/r/Test/comments/AbC/title/DeF?context=3&comment_id=DeF#t1_DeF"))
        assertEquals("https://x.com/NASA/status/123?A=Case%2fSensitive#photos",outward("nitter","/NASA/status/123?A=Case%2fSensitive#photos"))
        assertEquals("https://x.com/NASA/status/123#M",outward("nitter","/NASA/status/123#M"))
        assertEquals("https://www.instagram.com/explore/tags/Space%2FTime?q=Case",outward("kittygram","/popular/Space%2FTime?q=Case"))
        assertEquals("https://www.pinterest.com/search/pins/?q=Space%2BTime&scope=pins",outward("binternet","/search.php?q=Space%2BTime&scope=pins"))
        assertEquals("https://tieba.baidu.com/f?kw=Case%2FName&pn=2",outward("ratAintTieba","/f?kw=Case%2FName&pn=2"))
    }

    @Test fun localCleaningRunsOnTheAuthenticDestinationWithoutRemovingPostIdentity() {
        assertEquals("https://x.com/NASA/status/123?custom=Case",outward("nitter","/NASA/status/123?utm_source=mirror&custom=Case"))
        assertEquals("https://www.facebook.com/story.php?story_fbid=Case123&id=456&comment_id=ABC#reply",links.url("https://m.facebook.com/story.php?story_fbid=Case123&id=456&comment_id=ABC&utm_source=test#reply"))
        val signed="https://media.example/image.jpg?token=secret&expires=123&utm_source=keep&Case=Value"
        assertEquals(signed,links.url(signed))
    }

    @Test fun archivesUnwrapBeforeKnownFrontendAndPreserveRawDestinationIdentity() {
        assertEquals("https://x.com/NASA/status/123?Case=A%2fb",links.url("https://web.archive.org/web/20261016090000id_/https://nitter.net/NASA/status/123?Case=A%2fb"))
        assertEquals("https://www.reddit.com/r/test/comments/abc/title?context=3",links.url("https://arquivo.pt/noFrame/replay/20261016090000/https://www.reddit.com/r/test/comments/abc/title?context=3"))
        assertEquals("https://www.reddit.com/r/test/comments/abc/title?context=3#t1_Reply",links.url("https://web.archive.org/web/20261016090000id_/https://safereddit.com/r/test/comments/abc/title?context=3#t1_Reply"))
        assertEquals("https://x.com/NASA/status/123",links.url("https://web.archive.org/web/20261016090000id_/https://nitter.net/NASA/status/123#m"))
        val search="https://web.archive.org/web/*/https://x.com/NASA/status/123"
        assertEquals(search,links.url(search))
        val invalid="https://web.archive.org/web/20261399090000/https://x.com/NASA/status/123"
        assertEquals(invalid,links.url(invalid))
    }

    @Test fun skyviewDecodesOnlyAnAuthenticatedOriginalPlatformAddress() {
        val target=Target(Platform.BLUESKY,TargetKind.POST,"fixture","https://bsky.app/profile/nasa.test/post/AbC?Case=A%2FB#reply")
        assertEquals(target.canonicalUrl.substringBefore('#'),links.url(routes.convert(target,source("skyview"))))
        assertEquals(target.canonicalUrl,links.url(source("skyview").root+"?url="+DarwinRoutes.encode(target.canonicalUrl)))
        for(value in listOf("https://name:pass@bsky.app/profile/nasa.test/post/AbC","javascript:alert(1)","http:broken","https://unrelated.example/post/AbC")) {
            val input=source("skyview").root+"?url="+DarwinRoutes.encode(value)
            assertEquals(input,links.url(input))
        }
        val ambiguous=source("skyview").root+"?url="+DarwinRoutes.encode(target.canonicalUrl)+"&url="+DarwinRoutes.encode("https://bsky.app/profile/other.test/post/Def")
        assertEquals(ambiguous,links.url(ambiguous))
    }

    @Test fun liveConfiguredAliasesAreVisibleAndSharedApiHostsAreNotForgedAsSocialPages() {
        val custom=Source(Platform.X,"nitter","https://reader.example:8443/social")
        routes.registerPathwayAliases(listOf(custom,Source(Platform.X,"rsshub","https://feeds.example"),Source(Platform.REDDIT,"rsshub","https://feeds.example")))
        assertEquals("https://x.com/NASA/status/123?Case=A%2FB#reply",links.url("https://reader.example:8443/social/NASA/status/123?Case=A%2FB#reply"))
        for(input in listOf("https://reader.example:8443/socially/NASA/status/123","https://reader.example/social/NASA/status/123","https://feeds.example/twitter/user/NASA"))assertEquals(input,links.url(input))
        assertEquals("https://x.com/NASA/status/123",links.url("http://nitter.net/NASA/status/123"))
        assertEquals("https://x.com/NASA/status/123",links.url(source("nitter").root.replace("https://","http://")+"/NASA/status/123"))
        for(input in listOf("https://nitter.net:80/NASA/status/123","http://nitter.net:443/NASA/status/123","https://nitter.net:8443/NASA/status/123"))assertEquals(input,links.url(input))
    }

    @Test fun originalFederatedBlogAndCdnIdentitiesRemainOriginal() {
        val originals=listOf("https://mstdn.social/@user/123?Case=A%2Fb#reply","https://own.instance.example/@user/123?Case=A%2Fb#reply","https://staff.tumblr.com/post/123/title?Case=A%2Fb#reply","https://pbs.twimg.com/media/Case.jpg?format=jpg&name=orig","https://video.twimg.com/ext_tw_video/Case/pu/vid/Case.mp4?tag=12","https://media.example:8443/Case.jpg?signature=Case%2Fb")
        for(input in originals)assertEquals(input,links.url(input,Platform.MASTODON))
        assertEquals("https://bsky.app/profile/nasa.test/post/AbC#reply",links.url("https://web-cdn.bsky.app/profile/nasa.test/post/AbC#reply"))
    }

    @Test fun outwardProxyArtworkCanBeCopiedWithoutTouchingFetchedMedia() {
        assertEquals("https://pbs.twimg.com/media/A+B.jpg?name=orig",outward("nitter","/pic/media%2FA+B.jpg%3Fname%3Dorig"))
        assertEquals("https://i.redd.it/Case.jpg?width=100#image",outward("redlib","/img/Case.jpg?width=100#image"))
        assertEquals("https://64.media.tumblr.com/Case.jpg?name=orig",outward("priviblur","/tblr/media/64/Case.jpg?name=orig"))
        for(frontend in listOf("binternet","pinless","painterest")) {
            val original="https://i.pinimg.com/originals/Case.jpg?Case=A%2FB"
            val target=Target(Platform.PINTEREST,TargetKind.MEDIA,"Case",original)
            assertEquals(original,links.url(routes.convert(target,source(frontend))))
        }
        val raw=Post("123",Platform.X,"https://nitter.net/NASA/status/123","@NASA","Read https://nitter.net/NASA/status/123",media=listOf(Media(source("nitter").root+"/pic/media%2FCase.jpg")),provenance=Provenance(source("nitter"),123,source("nitter").root+"/NASA/status/123"))
        val before=Gson().toJson(raw)
        links.url(raw.canonicalUrl);links.text(raw.body);links.url(raw.media.first().url)
        assertEquals(before,Gson().toJson(raw))
    }

    @Test fun readableProseUsesAuthenticUrlsWithoutEatingPunctuationOrRewritingEmail() {
        val input="Read (https://nitter.net/NASA/status/123#m), then nitter.tiekoetter.com/WhiteHouse. Search https://nitter.net/search?f=tweets&q=%23Georgia. Contact user@nitter.net/help or use nitter.net."
        val expected="Read (https://x.com/NASA/status/123), then https://x.com/WhiteHouse. Search https://x.com/search?f=tweets&q=%23Georgia. Contact user@nitter.net/help or use https://x.com."
        assertEquals(expected,links.text(input))
        assertEquals("Read https://example.org/(Case) next",links.text("Read https://example.org/(Case) next"))
        assertEquals("Read https://x.com/NASA/status/123 next",links.text("Read //nitter.net/NASA/status/123 next"))
        assertEquals("@user@nitter.net/hello",links.text("@user@nitter.net/hello"))
    }

    @Test fun repeatedPresentationIsIdempotentAndUnknownOrInvalidAddressesStayUnchanged() {
        val inputs=listOf("https://nitter.net/NASA/status/123#m","nitter.net/NASA/status/123","https://nitter.example/NASA/status/123","https://api.fxtwitter.com/2/profile/NASA/statuses","https://feeds.example/twitter/user/NASA","https://name:secret@nitter.net/NASA/status/123","javascript:alert(1)",source("nitter").root+"/about",source("nitter").root+"/video/opaque-token", "https://nitter.net.evil.example/NASA/status/123")
        for(input in inputs)assertEquals(links.url(input),links.url(input).let {links.url(it)})
        for(input in inputs.drop(2))assertEquals(input,links.url(input))
    }
}
