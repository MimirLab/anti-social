package app.antisocial.core

import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncomingLinksTest {
    private val cleaner=UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private fun routes()=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val selected=Platform.entries.toSet()
    private fun open(result: IncomingLinkResult)= (result as IncomingLinkResult.Open).target

    @Test fun directMobileAndLegacyHostsNormalizeWithoutNetwork(): Unit=runBlocking {
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;error("Direct links need no expansion")}
        val cases=mapOf(
            "http://mobile.twitter.com/NASA/status/123?utm_source=share" to "https://x.com/NASA/status/123",
            "https://old.reddit.com/r/Android/comments/abc/title/" to "https://www.reddit.com/r/Android/comments/abc/title",
            "https://m.instagram.com/p/abc/" to "https://www.instagram.com/p/abc",
            "https://pinterest.co.uk/pin/123/" to "https://www.pinterest.com/pin/123",
            "https://threads.net/@nasa/post/abc" to "https://www.threads.com/@nasa/post/abc",
            "https://m.facebook.com/NASA/posts/123" to "https://www.facebook.com/NASA/posts/123",
            "https://m.gettr.com/post/abc123" to "https://gettr.com/post/abc123",
            "https://telegram.me/durov/123" to "https://t.me/durov/123",
            "https://m.linkedin.com/posts/example_activity-1234567890123-aa" to "https://www.linkedin.com/posts/example_activity-1234567890123-aa"
        )
        for((url,expected) in cases)assertEquals(url,expected,open(incoming.resolve(url,selected)).canonicalUrl)
        assertEquals(0,calls)
    }

    @Test fun everyKnownShortenerIsExpandedBeforeItsApparentProfileCanOpen(): Unit=runBlocking {
        val calls=mutableListOf<String>()
        val cases=mapOf(
            "https://t.co/abc" to "https://x.com/NASA/status/123",
            "https://redd.it/abc" to "https://www.reddit.com/r/Android/comments/abc/title",
            "https://pin.it/abc" to "https://www.pinterest.com/pin/123",
            "https://fb.watch/abc/" to "https://www.facebook.com/NASA/posts/123",
            "https://vm.tiktok.com/abc/" to "https://www.tiktok.com/@nasa/video/123",
            "https://www.tiktok.com/t/abc/" to "https://www.tiktok.com/@nasa/video/123",
            "https://www.facebook.com/share/p/token/" to "https://www.facebook.com/NASA/posts/123",
            "https://www.instagram.com/share/token" to "https://www.instagram.com/p/abc",
            "https://www.reddit.com/r/Android/s/token" to "https://www.reddit.com/r/Android/comments/abc/title"
        )
        val incoming=IncomingLinks(routes(),cleaner){url->calls+=url;cases.getValue(url)}
        for((url,destination) in cases) {
            val result=open(incoming.resolve(url,selected))
            assertEquals(destination.trimEnd('/'),result.canonicalUrl)
            assertEquals(TargetKind.POST,result.kind)
        }
        assertEquals(cases.keys.toList(),calls)
    }

    @Test fun ordinaryUnselectedLinksStayExternalBeforeAnyExpansionButExplicitShareCanOpen(): Unit=runBlocking {
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;"https://x.com/NASA/status/123"}
        assertEquals(IncomingLinkResult.External("https://t.co/abc"),incoming.resolve("https://t.co/abc",setOf(Platform.REDDIT)))
        assertTrue(incoming.resolve("https://x.com/NASA/status/123",setOf(Platform.REDDIT)) is IncomingLinkResult.External)
        assertEquals(0,calls)
        assertEquals(Platform.X,open(incoming.resolve("https://t.co/abc",setOf(Platform.REDDIT),explicit=true)).platform)
        assertEquals(1,calls)
    }

    @Test fun disabledOriginalResolutionDoesNotTouchShortLinksButKeepsDirectAlternateRoutingPossible(): Unit=runBlocking {
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;error("Original resolution disabled")}
        assertTrue(incoming.resolve("https://pin.it/abc",selected,allowOriginalResolution=false) is IncomingLinkResult.Unavailable)
        assertTrue(incoming.resolve("https://x.com/NASA/status/123",selected,allowOriginalResolution=false) is IncomingLinkResult.Open)
        assertEquals(0,calls)
    }

    @Test fun platformShareTokensRemainGatedAndCannotBecomeFakeProfilesOrPosts(): Unit=runBlocking {
        val tokens=listOf(
            "https://www.facebook.com/share/p/token/",
            "https://www.facebook.com/share/r/token/",
            "https://www.facebook.com/share/v/token/",
            "https://www.instagram.com/share/token",
            "https://www.instagram.com/share/reel/token/",
            "https://www.reddit.com/r/Android/s/token"
        )
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;it}
        for(url in tokens) {
            assertTrue(url,SocialLinkHosts.needsExpansion(url))
            assertTrue(incoming.resolve(url,emptySet()) is IncomingLinkResult.External)
            assertTrue(incoming.resolve(url,selected,allowOriginalResolution=false) is IncomingLinkResult.Unavailable)
            assertEquals(0,calls)
        }
        for(url in tokens)try {incoming.resolve(url,selected);fail("Share token was mistaken for content: $url")}
        catch(e: SourceFailure){assertEquals(HealthStatus.UNAVAILABLE,e.status)}
        assertEquals(tokens.size,calls)
    }

    @Test fun shortenerCrossPlatformDestinationRespectsSelectionAndExplicitIntent(): Unit=runBlocking {
        var calls=0;val destination="https://www.reddit.com/r/Android/comments/abc/title"
        val incoming=IncomingLinks(routes(),cleaner){calls++;destination}
        assertEquals(IncomingLinkResult.External(destination),incoming.resolve("https://t.co/abc",setOf(Platform.X)))
        assertEquals(Platform.REDDIT,open(incoming.resolve("https://t.co/abc",setOf(Platform.X),explicit=true)).platform)
        assertEquals(2,calls)
    }

    @Test fun ordinaryUnknownUrlsAndUnsupportedSocialNavigationAreFinalBrowserDestinations(): Unit=runBlocking {
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;"https://example.org/article?utm_source=tracking"}
        assertEquals(IncomingLinkResult.External("https://example.org/article"),incoming.resolve("https://example.org/article?utm_source=tracking",selected))
        assertEquals(IncomingLinkResult.External("https://www.instagram.com/stories/nasa/123"),incoming.resolve("https://www.instagram.com/stories/nasa/123",selected))
        assertEquals(0,calls)
        assertEquals(IncomingLinkResult.External("https://example.org/article"),incoming.resolve("https://t.co/abc",selected))
        assertEquals(1,calls)
    }

    @Test fun unsafeOriginalInputsAreRejectedBeforeCleaningOrResolving(): Unit=runBlocking {
        var calls=0;val incoming=IncomingLinks(routes(),cleaner){calls++;error("Unsafe input")}
        val invalid=listOf(
            "intent://x.com/NASA/status/123", "javascript:alert(1)", "file:///etc/passwd",
            "https://user:password@t.co/abc", "https://user%40mail@x.com/NASA/status/123",
            "https://localhost/@alice", "https://device.local/@alice", "https://device.internal/@alice",
            "http://127.0.0.1/path", "https://10.1.2.3/path", "https://100.64.0.1/path",
            "https://169.254.169.254/path", "https://192.168.1.1/path", "https://224.0.0.1/path",
            "https://[::1]/path", "https://[fd00::1]/path", "https://[fe80::1]/path",
            "https://2130706433/path", "https://127.1/path", "https://0177.0.0.1/path", "https://x.com\\@evil.example/path"
        )
        for(url in invalid)assertTrue(url,incoming.resolve(url,selected,explicit=true,hint=Platform.MASTODON) is IncomingLinkResult.Unavailable)
        assertEquals(0,calls)
    }

    @Test fun unsafeRedirectsAndSignInPagesNeverEscapeAsBrowserOrNativeContent(): Unit=runBlocking {
        for(destination in listOf("http://127.0.0.1/private","https://user:password@x.com/NASA/status/123","javascript:alert(1)","https://www.facebook.com/login.php?next=post")) {
            val incoming=IncomingLinks(routes(),cleaner){destination}
            try {incoming.resolve("https://t.co/abc",selected);fail(destination)}catch(e: SourceFailure) {
                assertEquals(if(destination.contains("login.php"))HealthStatus.LOGIN_REQUIRED else HealthStatus.UNAVAILABLE,e.status)
            }
        }
        val direct=IncomingLinks(routes(),cleaner){error("Sign-in is not expanded")}
        for(url in listOf("https://x.com/i/flow/login","https://www.instagram.com/accounts/login/","https://www.linkedin.com/authwall"))assertTrue(direct.resolve(url,selected) is IncomingLinkResult.Unavailable)
    }

    @Test fun cancellationKnownLinkFailureAndUnresolvedShortenerDoNotTriggerMoreLookups(): Unit=runBlocking {
        var calls=0;val cancellation=CancellationException("Caller stopped")
        val cancelled=IncomingLinks(routes(),cleaner){calls++;throw cancellation}
        try {cancelled.resolve("https://t.co/abc",selected);fail("Expected cancellation")}catch(e: CancellationException){assertSame(cancellation,e)}
        assertEquals(1,calls)
        val failed=IncomingLinks(routes(),cleaner){calls++;throw IOException("Resolver refused")}
        try {failed.resolve("https://t.co/abc",selected);fail("Expected source failure")}catch(e: SourceFailure){assertEquals(HealthStatus.UNAVAILABLE,e.status)}
        val stillShort=IncomingLinks(routes(),cleaner){calls++;"https://redd.it/another"}
        try {stillShort.resolve("https://t.co/abc",selected);fail("Expected unresolved short link")}catch(e: SourceFailure){assertEquals(HealthStatus.UNAVAILABLE,e.status)}
        assertEquals(3,calls)
    }

    @Test fun facebookLoginContinuationRecoversOnlyTheLocallyDisclosedExactPostIdentity(): Unit=runBlocking {
        var calls=0
        val destination="https://m.facebook.com/somepage/posts/123456?utm_source=share"
        val incoming=IncomingLinks(routes(),cleaner){calls++;"https://www.facebook.com/login.php?next=${DarwinRoutes.encode(destination)}"}
        val target=open(incoming.resolve("https://fb.watch/short",setOf(Platform.FACEBOOK)))
        assertEquals("https://www.facebook.com/somepage/posts/123456",target.canonicalUrl)
        assertEquals(TargetKind.POST,target.kind);assertEquals(1,calls)
    }

    @Test fun loginContinuationsRejectProfilesShareTokensOtherPlatformsAndUnsafeOrNestedValues(): Unit=runBlocking {
        val invalid=listOf(
            "https://www.facebook.com/somepage", "https://www.facebook.com/share/p/token/",
            "https://www.facebook.com/login.php?next=another", "https://x.com/NASA/status/123",
            "https://user:password@www.facebook.com/somepage/posts/123456", "http://127.0.0.1/private",
            DarwinRoutes.encode("https://www.facebook.com/somepage/posts/123456")
        )
        for(value in invalid) {
            var calls=0
            val incoming=IncomingLinks(routes(),cleaner){calls++;"https://www.facebook.com/login.php?next=${DarwinRoutes.encode(value)}"}
            try {incoming.resolve("https://fb.watch/short",selected);fail(value)}catch(e: SourceFailure){assertEquals(value,HealthStatus.LOGIN_REQUIRED,e.status)}
            assertEquals(1,calls)
        }
    }

    @Test fun aSingleSupportedContinuationParameterStillRespectsSelectionAndNeverChains(): Unit=runBlocking {
        val destination="https://www.facebook.com/somepage/posts/123456"
        for(field in listOf("next","continue","return_to")) {
            var calls=0
            val incoming=IncomingLinks(routes(),cleaner){calls++;"https://www.facebook.com/login.php?$field=${DarwinRoutes.encode(destination)}"}
            assertTrue(incoming.resolve("https://fb.watch/short",setOf(Platform.REDDIT)) is IncomingLinkResult.External)
            assertEquals(0,calls)
            assertEquals(Platform.FACEBOOK,open(incoming.resolve("https://fb.watch/short",setOf(Platform.REDDIT),explicit=true)).platform)
            assertEquals(1,calls)
        }
        val ambiguous=IncomingLinks(routes(),cleaner){"https://www.facebook.com/login.php?next=${DarwinRoutes.encode(destination)}&return_to=${DarwinRoutes.encode(destination)}"}
        try {ambiguous.resolve("https://fb.watch/short",selected);fail("Ambiguous continuation")}
        catch(e: SourceFailure){assertEquals(HealthStatus.LOGIN_REQUIRED,e.status)}
    }

    @Test fun customFederationNeedsAnExplicitHintOrPreviouslyRegisteredServer(): Unit=runBlocking {
        var calls=0;val registry=routes();val incoming=IncomingLinks(registry,cleaner){calls++;error("Federation links do not need expansion")}
        val url="https://social.example.org/@alice/123"
        assertEquals(IncomingLinkResult.External(url),incoming.resolve(url,selected,explicit=true))
        assertEquals(Platform.MASTODON,open(incoming.resolve(url,selected,explicit=true,hint=Platform.MASTODON)).platform)
        assertEquals(Platform.MASTODON,open(incoming.resolve(url,selected)).platform)
        assertEquals(0,calls)
    }
}
