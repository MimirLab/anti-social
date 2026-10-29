/* anti social, GPL-3.0-or-later. Literal URL identity and validation parity. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContentIdentityPreparationTest {
    private val urls=listOf(
        "https://x.com/studio/status/101?ref=A#one","https://x.com/studio/status/101?ref=a#two",
        "https://www.facebook.com/studio/posts/123?comment_id=456","https://www.facebook.com/studio/posts/123?comment_id=457",
        "https://www.facebook.com/permalink.php?story_fbid=123&id=99&reply_comment_id=456",
        "https://www.instagram.com/p/Gallery_A/c/123/","https://www.instagram.com/p/Gallery_A/?comment_id=124",
        "https://www.tiktok.com/@studio/video/123?comment_id=456","https://www.tiktok.com/@studio/photo/123",
        "https://www.youtube.com/watch?v=AbC12345678&lc=UgzExample","https://youtu.be/AbC12345678?t=30",
        "https://bsky.app/profile/studio.example/post/AbC","https://bsky.app/profile/studio.example/post/abc",
        "https://one.example/@studio/123","https://two.example/@studio/123",
        "https://one.example/comment/123","https://two.example/comment/123",
        "https://example.org/Part?key=A#one","https://example.org/part?key=a#two",
        "  https://x.com/studio/status/101  ","HTTPS://X.COM/studio/status/101",
        "https://user:secret@x.com/studio/status/101","https://x.com:444/studio/status/101",
        "http://x.com/studio/status/101","https://x.com.example.org/studio/status/101",
        "https://example.org/%E2%98%83?q=%26#fragment","https://example.org/%ZZ","https://bad host/123",
        "not a URI","https://t.co/Short","https://t.me/s/publicstudio/123"
    )
    private fun read(url:String)=Triple(SocialLinkHosts.platform(url),SocialLinkHosts.canonical(url),Platform.entries.map {ContentIdentity.key(it,url)})
    @Test fun scopedReuseKeepsEveryPlatformAndLiteralUrlResultIdenticalToAnUncachedRead() {
        val expected=urls.associateWith(::read)
        CrossPostFusion.withPrepared {
            repeat(3){for(url in urls.reversed())assertEquals(url,expected[url],read(url))}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.uriHits>urls.size);assertTrue(stats.identityHits>urls.size*Platform.entries.size)
            assertTrue(stats.uriEntries<=2048);assertTrue(stats.identityEntries<=2048);assertTrue(stats.bytes<=8L*1024*1024)
        }
        assertNull(CrossPostFusion.preparationStats())
    }
    @Test fun identityReuseNeverBecomesDestinationPermissionOrErasesCommentAndOriginScope() {
        CrossPostFusion.withPrepared {
            val x="https://x.com/studio/status/101#one"
            assertEquals("post:101",ContentIdentity.key(Platform.X,x));assertEquals(x,ContentIdentity.key(Platform.RSS,x))
            assertNotEquals(ContentIdentity.key(Platform.RSS,x),ContentIdentity.key(Platform.RSS,x.replace("one","two")))
            assertNotEquals(ContentIdentity.key(Platform.FACEBOOK,urls[2]),ContentIdentity.key(Platform.FACEBOOK,urls[3]))
            assertNotEquals(ContentIdentity.key(Platform.MASTODON,urls[13]),ContentIdentity.key(Platform.MASTODON,urls[14]))
            assertNotEquals(ContentIdentity.key(Platform.LEMMY,urls[15]),ContentIdentity.key(Platform.LEMMY,urls[16]))
            for(url in listOf("https://user:secret@x.com/studio/status/101","https://x.com:444/studio/status/101","https://x.com.example.org/studio/status/101")) {
                assertNotNull(CrossPostFusion.parsedUri(url));repeat(2){assertNull(SocialLinkHosts.platform(url));assertNull(SocialLinkHosts.canonical(url))}
            }
            repeat(3){assertNull(CrossPostFusion.parsedUri("https://bad host/123"))}
        }
    }
    @Test fun boundsAndExceptionCleanupKeepParsedUrlsOutOfReaderHistory() {
        var previous:java.net.URI?=null
        assertThrows(IllegalStateException::class.java){CrossPostFusion.withPrepared {
            previous=CrossPostFusion.parsedUri(urls.first());assertSame(previous,CrossPostFusion.parsedUri(urls.first()));error("Interrupted grouping")
        }}
        assertNull(CrossPostFusion.preparationStats());assertNotSame(previous,CrossPostFusion.parsedUri(urls.first()))
        CrossPostFusion.withPrepared {
            repeat(4000){i->val url="https://example.org/$i";CrossPostFusion.parsedUri(url);ContentIdentity.key(Platform.RSS,url)}
            val stats=CrossPostFusion.preparationStats()!!
            assertTrue(stats.uriEntries<=2048);assertTrue(stats.identityEntries<=2048);assertTrue(stats.bytes<=8L*1024*1024)
        }
        CrossPostFusion.withPrepared {
            repeat(300){i->val url="https://example.org/$i/"+"a".repeat(8193);CrossPostFusion.parsedUri(url);ContentIdentity.key(Platform.RSS,url)}
            assertEquals(0,CrossPostFusion.preparationStats()!!.uriEntries);assertEquals(0,CrossPostFusion.preparationStats()!!.identityEntries)
        }
    }
    @Test fun independentReadersDoNotShareParsedObjects() {
        val pool=Executors.newFixedThreadPool(2);val barrier=CyclicBarrier(2)
        try {
            val tasks=(0..1).map {pool.submit<java.net.URI> {CrossPostFusion.withPrepared {
                val value=CrossPostFusion.parsedUri(urls.first())!!;barrier.await(10,TimeUnit.SECONDS)
                assertSame(value,CrossPostFusion.parsedUri(urls.first()));value
            }}}
            val a=tasks[0].get(15,TimeUnit.SECONDS);val b=tasks[1].get(15,TimeUnit.SECONDS)
            assertEquals(a,b);assertNotSame(a,b);assertNull(CrossPostFusion.preparationStats())
        }finally {pool.shutdownNow()}
    }
}
