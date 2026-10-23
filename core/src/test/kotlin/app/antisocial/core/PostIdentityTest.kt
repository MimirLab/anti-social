package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PostIdentityTest {
    @Test fun derivedIdentityNeverChangesStoredOrExportedFieldsAndCopiesGetFreshIdentity() {
        val gson=Gson();val post=Post("1",Platform.FACEBOOK,"https://www.facebook.com/workshop/posts/123?comment_id=17","Workshop","Body")
        val before=gson.toJson(post)
        repeat(20){assertEquals("facebook:comment:123:17",post.identityKey)}
        assertEquals(before,gson.toJson(post));assertFalse(before.contains("cachedIdentity"));assertFalse(before.contains("identityKey"))
        val restored=gson.fromJson(before,Post::class.java)
        assertEquals(post,restored);assertEquals(post.identityKey,restored.identityKey)
        val changed=post.copy(canonicalUrl="https://www.facebook.com/workshop/posts/124")
        assertEquals("facebook:post:124",changed.identityKey);assertEquals("facebook:comment:123:17",post.identityKey)
        assertEquals("twitter:post:555",post.copy(platform=Platform.X,canonicalUrl="https://x.com/workshop/status/555").identityKey)
    }
}
