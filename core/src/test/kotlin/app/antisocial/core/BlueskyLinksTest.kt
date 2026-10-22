/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BlueskyLinksTest {
    private val routes=DarwinRoutes(asset("darwin-routes.json"),UrlCleaner(asset("clean-links.json")))
    private val source=Source(Platform.BLUESKY,"bluesky-api","https://public.api.bsky.app",role=SourceRole.ORIGINAL)
    private fun asset(name: String)=JsonParser.parseReader(javaClass.getResourceAsStream("/$name")!!.reader()).asJsonObject
    private fun facet(uri: String,type: String="app.bsky.richtext.facet#link")=JsonObject().apply {
        add("features",JsonArray().apply {add(JsonObject().apply {addProperty("\$type",type);addProperty("uri",uri)})})
    }
    private fun external(uri: String)=JsonObject().apply {add("external",JsonObject().apply {addProperty("uri",uri)})}
    private fun parse(body: String,record: JsonObject,embed: JsonObject?=null): Post {
        record.addProperty("text",body);record.addProperty("createdAt","2026-10-16T19:57:02Z")
        val post=JsonObject().apply {
            addProperty("uri","at://did:plc:fixture/app.bsky.feed.post/public-link-fixture")
            add("author",JsonObject().apply {addProperty("handle","fixture.test")})
            add("record",record);embed?.let {add("embed",it)}
        }
        val payload=Payload(post.toString(),source.root+"/xrpc/app.bsky.feed.getPostThread","application/json",200)
        return Extraction.parse(payload,routes.target(Platform.BLUESKY,"fixture.test"),source,routes).posts.single()
    }

    @Test fun fullFacetAndExternalDestinationsSurviveAnAbbreviatedUnicodeCaption() {
        val destination="https://example.org/Updates/Approved?mode=New"
        val body="🛠 Café project update. example.org/Updates/..."
        val record=JsonObject().apply {
            add("facets",JsonArray().apply {add(facet(destination));add(facet(destination))})
            add("embed",external("https://example.org/background"))
        }
        val post=parse(body,record,external(destination))
        assertEquals(body,post.body)
        assertEquals(listOf(destination,"https://example.org/background"),post.links)
        assertEquals("https://bsky.app/profile/fixture.test/post/public-link-fixture",post.canonicalUrl)
    }

    @Test fun onlyAbsoluteHttpsLinkFacetsAndOwnedExternalEmbedsContributeDestinations() {
        val record=JsonObject().apply {
            add("facets",JsonArray().apply {
                listOf("http://example.org/insecure","javascript:alert(1)","/relative","https://user:password@example.org/private","https:///missing-host").forEach {add(facet(it))}
                add(facet("https://example.org/not-a-link","app.bsky.richtext.facet#mention"))
                add("malformed facet")
                add(JsonObject().apply {addProperty("features","malformed")})
                add(facet("https://example.org/public"))
            })
            add("embed",JsonObject().apply {add("media",external("https://example.org/media-article"))})
        }
        val post=parse("A public caption with no visible links.",record)
        assertEquals(listOf("https://example.org/public","https://example.org/media-article"),post.links)
    }

    @Test fun largeFacetListsStayBoundedAndDoNotRewriteTheSourceText() {
        val body="The original caption remains unchanged."
        val record=JsonObject().apply {add("facets",JsonArray().apply {repeat(100){add(facet("https://example.org/article/$it"))}})}
        val post=parse(body,record)
        assertEquals(body,post.body)
        assertEquals(12,post.links!!.size)
        assertEquals((0..11).map {"https://example.org/article/$it"},post.links)
    }
}
