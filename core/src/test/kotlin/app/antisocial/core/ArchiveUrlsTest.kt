package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ArchiveUrlsTest {
    private val post=Post("42",Platform.X,"https://x.com/person/status/42","Person","Current statement")
    private fun wayback(original: String="https://twitter.com/person/status/42",segment: String="20200101120000id_")="https://web.archive.org/web/$segment/$original"

    @Test fun knownTimestampReplayWrappersRetainTheOriginalUrlAndQuery() {
        val original="https://twitter.com/person/status/42?lang=en&mode=public"
        assertEquals(original,ArchiveUrls.original(wayback(original)))
        assertEquals(original,ArchiveUrls.original("https://web.archive.org:443/web/20200229120000im_/$original"))
        assertEquals(original,ArchiveUrls.original("https://arquivo.pt/noFrame/replay/20200101120000id_/$original"))
        assertEquals(original,ArchiveUrls.original("https://arquivo.pt/wayback/20200101120000/$original"))
        assertEquals("http://twitter.com/person/status/42",ArchiveUrls.original(wayback("http://twitter.com/person/status/42",segment="20200101120000")))
    }
    @Test fun archiveControlEndpointsUnknownHostsAndNondefaultArchivePortsAreRejected() {
        for(url in listOf(
            "https://web.archive.org/save/https://twitter.com/person/status/42",
            "https://web.archive.org/cdx/search/cdx?url=https://twitter.com/person/status/42",
            "https://arquivo.pt/textsearch?versionHistory=https://twitter.com/person/status/42",
            "https://evil.example/web/20200101120000id_/https://twitter.com/person/status/42",
            "https://web.archive.org.evil.example/web/20200101120000id_/https://twitter.com/person/status/42",
            "https://web.archive.org:8443/web/20200101120000id_/https://twitter.com/person/status/42",
            "http://web.archive.org/web/20200101120000id_/https://twitter.com/person/status/42"
        ))assertNull(url,ArchiveUrls.original(url))
    }
    @Test fun credentialsMalformedTimestampsAndEncodedOrUnsafeSchemesAreRejected() {
        for(url in listOf(
            "https://account@web.archive.org/web/20200101120000id_/https://twitter.com/person/status/42",
            "https://@web.archive.org/web/20200101120000id_/https://twitter.com/person/status/42",
            wayback("https://account:secret@twitter.com/person/status/42"),
            wayback("https://@twitter.com/person/status/42"),
            wayback("javascript:alert(1)"),wayback("data:text/plain,post"),
            wayback("https%3A%2F%2Ftwitter.com%2Fperson%2Fstatus%2F42"),
            wayback("https:///twitter.com/person/status/42"),
            wayback(segment="20200230120000id_"),wayback(segment="20201301120000id_"),
            wayback(segment="20200101250000id_"),wayback(segment="20200101id_"),
            wayback(segment="20200101120000save"),wayback(segment="latest"),
            wayback("https://twitter.com\\@evil.example/person/status/42"),
            wayback("https://twitter.com/person/status/42\n")
        ))assertNull(url,ArchiveUrls.original(url))
    }
    @Test fun explicitOwnedXAliasesCanMatchAHistoricalHttpCapture() {
        for(host in listOf("x.com","www.x.com","twitter.com","www.twitter.com","mobile.twitter.com","m.twitter.com","mobile.x.com","m.x.com")) {
            assertTrue(host,ArchiveUrls.samePost(post,"http://$host/old_author/status/42?tracking=ignored"))
        }
        assertFalse(ArchiveUrls.samePost(post,"https://twitter.com/person/status/43"))
        assertFalse(ArchiveUrls.samePost(post,"https://evil.example/person/status/42"))
        assertFalse(ArchiveUrls.samePost(post,"https://twitter.com.evil.example/person/status/42"))
        assertFalse(ArchiveUrls.samePost(post,"https://evil.twitter.com/person/status/42"))
        assertFalse(ArchiveUrls.samePost(post.copy(canonicalUrl="https://evil.example/person/status/42"),"https://evil.example/person/status/42"))
    }
    @Test fun postIdentityNeverPermitsCredentialsOrNonHttpSchemes() {
        for(candidate in listOf("https://account@x.com/person/status/42","https://x.com@evil.example/person/status/42","ftp://x.com/person/status/42","file:///person/status/42","https:///x.com/person/status/42","https://x.com\\@evil.example/person/status/42")) {
            assertFalse(candidate,ArchiveUrls.samePost(post,candidate))
        }
        assertFalse(ArchiveUrls.samePost(post.copy(canonicalUrl="https://account@x.com/person/status/42"),post.canonicalUrl))
    }
    @Test fun nondefaultOriginalPortsMustMatchAndFederationHostsAreNotInterchangeable() {
        val federated=post.copy(platform=Platform.MASTODON,canonicalUrl="https://social.example:8443/@person/42")
        assertTrue(ArchiveUrls.samePost(federated,"https://social.example:8443/users/person/statuses/42"))
        assertFalse(ArchiveUrls.samePost(federated,"https://social.example/@person/42"))
        assertFalse(ArchiveUrls.samePost(federated,"https://another.example:8443/@person/42"))
        assertFalse(ArchiveUrls.samePost(post,"https://x.com:8443/person/status/42"))
        assertFalse(ArchiveUrls.samePost(post,"http://x.com:443/person/status/42"))
        assertTrue(ArchiveUrls.samePost(post,"https://x.com:443/person/status/42"))
    }
    @Test fun artworkRequiresAReplayWrapperOnTheSelectedArchiveOrigin() {
        val capture=wayback()
        assertTrue(ArchiveUrls.safeAsset(wayback("https://pbs.twimg.com/media/Photo123.jpg"),capture))
        assertTrue(ArchiveUrls.safeAsset(wayback("https://pbs.twimg.com/media/Photo123.jpg",segment="20190101120000im_"),capture))
        assertFalse(ArchiveUrls.safeAsset("https://web.archive.org/save/https://pbs.twimg.com/media/Photo123.jpg",capture))
        assertFalse(ArchiveUrls.safeAsset("https://web.archive.org/cdx/search/cdx?url=image",capture))
        assertFalse(ArchiveUrls.safeAsset("https://pbs.twimg.com/media/Photo123.jpg",capture))
        assertFalse(ArchiveUrls.safeAsset("https://arquivo.pt/noFrame/replay/20200101120000id_/https://pbs.twimg.com/media/Photo123.jpg",capture))
        assertFalse(ArchiveUrls.safeAsset(wayback("https://pbs.twimg.com/media/Photo123.jpg"),"https://web.archive.org/save/https://twitter.com/person/status/42"))
        assertTrue(ArchiveUrls.safeAsset("https://arquivo.pt/noFrame/replay/20200101120000id_/https://images.example/photo.jpg","https://arquivo.pt/wayback/20200101120000/https://social.example/@person/42"))
    }
    @Test fun unrecognizedProfileAndCommentUrlsCannotMasqueradeAsThePost() {
        assertFalse(ArchiveUrls.samePost(post,"https://x.com/person"))
        val profile=post.copy(canonicalUrl="https://x.com/person")
        assertFalse(ArchiveUrls.samePost(profile,profile.canonicalUrl))
        val comment=post.copy(platform=Platform.REDDIT,canonicalUrl="https://www.reddit.com/r/example/comments/abc/title/def/")
        assertFalse(ArchiveUrls.samePost(comment,comment.canonicalUrl))
    }
}
