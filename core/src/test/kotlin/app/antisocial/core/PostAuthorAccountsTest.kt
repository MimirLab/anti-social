/* anti social, GPL-3.0-or-later. Source bindings must survive without name guesses. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class PostAuthorAccountsTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject))
    private fun post(handle:String?="actual_reader")=Post("1",Platform.INSTAGRAM,"https://www.instagram.com/p/ABC/c/17/","Shared display name","A substantive reply",authorAccount=handle?.let {PostAuthorAccounts.instagram(it,"123")})

    @Test fun exactSourceAccountsSurviveCopiesAndSerializationWithoutDerivedState() {
        val original=post();val account=requireNotNull(original.authorAccount)
        assertEquals("actual_reader",AdjacentDiscovery.author(original,routes)?.identity)
        assertEquals("instagram:actual_reader",CrossPosts.originalAccount(original))
        assertEquals(account,original.copy(body="Updated caption").authorAccount)
        val json=Gson().toJson(original)
        assertFalse(json.contains("resolved"));assertFalse(json.contains("cachedIdentity"))
        val restored=Gson().fromJson(json,Post::class.java)
        assertEquals(original,restored);assertEquals(AdjacentDiscovery.author(original,routes),AdjacentDiscovery.author(restored,routes))
        assertEquals("123",restored.authorAccount?.persistentId)
    }
    @Test fun unknownAuthorsAreNotGuessedFromDisplayNamesOrCurrentArchiveAuthors() {
        val current=post()
        assertNull(AdjacentDiscovery.author(post(null),routes))
        assertNull(CrossPosts.originalAccount(post(null)))
        val old=PostSnapshot(author="An older display name",body="Older capture").asPost(current)
        assertNull(old.authorAccount);assertNull(AdjacentDiscovery.author(old,routes))
        val captured=PostSnapshot.from(current)
        assertEquals(current.authorAccount,captured.asPost(current.copy(authorAccount=null)).authorAccount)
        assertEquals(current.authorAccount,Gson().fromJson(Gson().toJson(captured),PostSnapshot::class.java).authorAccount)
    }
    @Test fun invalidDestinationsNeverSupplyAnAuthorOrCommonControlClaim() {
        val urls=listOf("http://www.instagram.com/reader/","https://www.instagram.com:444/reader/","https://user:secret@www.instagram.com/reader/","https://www.instagram.com/reader/?x=1","https://www.instagram.com/reader/#x","https://www.instagram.com/%72eader/","https://www.instagram.com//reader/","https://instagram.example/reader/","https://www.instagram.com/p/ABC/","https://www.instagram.com/accounts/","https://www.instagram.com/reader/followers/","https://www.instagram.com/./")
        urls.forEach {url->val p=post().copy(authorAccount=PostAuthorAccount(url));assertFalse(url,PostAuthorAccounts.valid(p));assertNull(url,AdjacentDiscovery.author(p,routes));assertNull(url,CrossPosts.originalAccount(p))}
        assertFalse(PostAuthorAccounts.valid(post().copy(platform=Platform.X)))
        listOf("", ".", "..", "accounts", "P", "a/b", "@reader", "a".repeat(31)).forEach {assertNull(it,PostAuthorAccounts.instagram(it))}
    }
    @Test fun missingOrInvalidPersistentIdsNeverBecomeAnonymousZeroIdentities() {
        listOf("0","-1","001","1e3","1.1","true","9".repeat(41)).forEach {id->
            assertNull(PostAuthorAccounts.instagram("reader",id)?.persistentId)
            assertFalse(PostAuthorAccounts.valid(post().copy(authorAccount=PostAuthorAccount("https://www.instagram.com/reader/",id))))
        }
        assertEquals("9999999999999999999999999999999999999999",PostAuthorAccounts.instagram("reader","9".repeat(40))?.persistentId)
    }
    @Test fun suppliedAuthorBeatsHandleLookingLabelsAndLeavesBodyMentionsIndependent() {
        val target=requireNotNull(PostAuthorAccounts.target(post()))
        val label=MentionNames.label("@somebody_else",Platform.INSTAGRAM,post().canonicalUrl,routes,declaredAuthor=target).single()
        assertEquals("actual_reader",label.key.username)
        val body=MentionNames.find("Thanks @somebody_else",Platform.INSTAGRAM,post().canonicalUrl,routes).single()
        assertEquals("somebody_else",body.key.username)
        val authors=listOf("one","two").map {handle->MentionNames.label("Shared display name",Platform.INSTAGRAM,post().canonicalUrl,routes,declaredAuthor=PostAuthorAccounts.target(post(handle))).single()}
        assertEquals(listOf("one","two"),authors.map {it.key.username});assertEquals(listOf("Shared display name","Shared display name"),authors.map {it.fallbackLabel})
        assertTrue(MentionNames.label("Shared display name",Platform.X,"https://x.com/i/status/1",routes,declaredAuthor=target).isEmpty())
    }
}
