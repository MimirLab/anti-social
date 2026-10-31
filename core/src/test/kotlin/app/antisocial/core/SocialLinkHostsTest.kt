package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class SocialLinkHostsTest {
    @Test fun compiledDeclarationsKeepFirstMatchPriorityAcrossExactAndWildcardRules() {
        val broadFirst=SocialLinkHosts.HostIndex(linkedMapOf(Platform.X to listOf("*.studio.example"),Platform.FACEBOOK to listOf("news.studio.example","*.news.studio.example")))
        assertEquals(Platform.X,broadFirst.platform("news.studio.example"))
        assertEquals(Platform.X,broadFirst.platform("local.news.studio.example"))
        val exactFirst=SocialLinkHosts.HostIndex(linkedMapOf(Platform.FACEBOOK to listOf("news.studio.example"),Platform.X to listOf("*.studio.example")))
        assertEquals(Platform.FACEBOOK,exactFirst.platform("news.studio.example"))
        assertEquals(Platform.X,exactFirst.platform("other.studio.example"))
    }
    @Test fun compiledDeclarationsKeepDuplicatePriorityAndWholeSubdomainBoundaries() {
        val index=SocialLinkHosts.HostIndex(linkedMapOf(Platform.INSTAGRAM to listOf("studio.example","*.studio.example"),Platform.X to listOf("studio.example")))
        assertEquals(Platform.INSTAGRAM,index.platform("studio.example"))
        assertEquals(Platform.INSTAGRAM,index.platform("public.studio.example"))
        assertNull(index.platform("evilstudio.example"));assertNull(index.platform("studio.example.evil"))
        assertNull(SocialLinkHosts.HostIndex(mapOf(Platform.X to listOf("*.studio.example"))).platform("studio.example"))
    }
    @Test fun everyPlatformHasKnownHostEntryPoints() {
        assertEquals(Platform.entries.filterNot {it==Platform.RSS}.toSet(),SocialLinkHosts.hosts.keys)
        SocialLinkHosts.hosts.forEach {(platform,hosts)->hosts.forEach {host->
            assertEquals(platform,SocialLinkHosts.platform("https://${host.replace("*.","publicblog.")}/public/path"))
        }}
    }
    @Test fun legacyAndMobileAliasesBecomeRecognizableOriginalLinks() {
        assertEquals("https://x.com/NASA/status/123?q=test#thread",SocialLinkHosts.canonical("http://mobile.twitter.com/NASA/status/123?q=test#thread"))
        assertEquals("https://www.reddit.com/r/Android/comments/abc/title",SocialLinkHosts.canonical("https://old.reddit.com/r/Android/comments/abc/title"))
        assertEquals("https://www.facebook.com/NASA/posts/123",SocialLinkHosts.canonical("https://mbasic.facebook.com/NASA/posts/123"))
        assertEquals("https://www.threads.com/@nasa/post/abc",SocialLinkHosts.canonical("https://www.threads.net/@nasa/post/abc"))
        assertEquals("https://www.instagram.com/p/abc",SocialLinkHosts.canonical("https://instagr.am/p/abc"))
    }
    @Test fun tumblrBlogsKeepTheirIdentityAndUseAStrictDomainBoundary() {
        assertEquals(Platform.TUMBLR,SocialLinkHosts.platform("https://staff.tumblr.com/post/123/public-post"))
        assertEquals("https://staff.tumblr.com/post/123/public-post",SocialLinkHosts.canonical("https://staff.tumblr.com/post/123/public-post"))
        assertNull(SocialLinkHosts.platform("https://tumblr.com.evil.example/post/123"))
        assertNull(SocialLinkHosts.platform("https://eviltumblr.com/post/123"))
    }
    @Test fun credentialsWrongPortsAndNonWebSchemesCannotClaimASite() {
        listOf("https://x.com.evil.example/nasa","https://x.com@evil.example/nasa","https://user:secret@x.com/nasa","https://x.com:444/nasa","http://x.com:443/nasa","file://x.com/nasa","javascript:alert(1)").forEach {url->
            assertNull(url,SocialLinkHosts.platform(url));assertNull(url,SocialLinkHosts.canonical(url))
        }
        assertEquals(Platform.X,SocialLinkHosts.platform("https://X.COM:443/nasa"))
    }
    @Test fun shortenersAreClassifiedWithoutGuessingTheirDestination() {
        listOf("https://t.co/abc","https://redd.it/abc","https://reddit.app.link/abc","https://pin.it/abc","https://fb.watch/abc","https://lnkd.in/abc","https://www.tiktok.com/t/abc").forEach {url->assertTrue(url,SocialLinkHosts.needsExpansion(url))}
        assertEquals("https://t.co/abc",SocialLinkHosts.canonical("https://t.co/abc"))
        assertFalse(SocialLinkHosts.needsExpansion("https://x.com/nasa/status/123"))
        assertFalse(SocialLinkHosts.needsExpansion("https://unknown.example/t/abc"))
    }
    @Test fun shareRoutesKeepTheirOriginalHostEscapedPathQueryAndFragment() {
        for(url in listOf("https://mbasic.facebook.com/share/p/AbC?source=fixture#Panel",
            "https://m.instagram.com/share/AbC/more?source=fixture#Panel",
            "https://old.reddit.com/r/studio/s/AbC?source=fixture#Panel",
            "https://m.tiktok.com/t/AbC?source=fixture#Panel",
            "https://mbasic.facebook.com/%73hare/p/AbC?source=fixture#Panel")) {
            assertTrue(url,SocialLinkHosts.needsExpansion(url));assertEquals(url,SocialLinkHosts.canonical(url))
        }
        assertFalse(SocialLinkHosts.needsExpansion("https://mbasic.facebook.com/Share/p/AbC"))
        assertFalse(SocialLinkHosts.needsExpansion("https://mbasic.facebook.com/share/article/AbC"))
        assertEquals("https://www.facebook.com/Share/p/AbC",SocialLinkHosts.canonical("https://mbasic.facebook.com/Share/p/AbC"))
    }
    @Test fun knownFederatedServersNeverTurnIntoInterchangeableMirrors() {
        assertEquals(Platform.MASTODON,SocialLinkHosts.platform("https://mstdn.social/@stux/123"))
        assertEquals("https://mstdn.social/@stux/123",SocialLinkHosts.canonical("https://mstdn.social/@stux/123"))
        assertEquals("https://lemmy.ml/post/123",SocialLinkHosts.canonical("https://lemmy.ml/post/123"))
        assertNull(SocialLinkHosts.platform("https://an-unregistered-server.example/@user/123"))
    }
}
