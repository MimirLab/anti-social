/* anti social, GPL-3.0-or-later. A render alias is not another fetch permission. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NitterRenderAliasTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject))
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val target=routes.target(Platform.X,"Workshop")
    private val prose="The community workshop has published detailed repair instructions and a complete equipment inventory for the next public session."
    private fun rss(channel:String="Workshop",creator:String="@RepairCrew",guid:String="910001")="""<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><link>https://render.example/$channel</link><item><title>RT @RepairCrew (by @Workshop): $prose</title><dc:creator>$creator</dc:creator><guid isPermaLink="false">$guid</guid><link>https://render.example/RepairCrew/status/910001</link><description>$prose</description></item></channel></rss>"""
    private fun page(body:String,selected:Source=source)=Extraction.parse(Payload(body,source.root+"/Workshop/rss","application/rss+xml",200),target,selected,routes)

    @Test fun aScopedDeclaredRenderHostAndAgreeingRecordRetainTheOriginalPostIdentity() {
        val post=page(rss()).posts.single()
        assertEquals("https://x.com/RepairCrew/status/910001",post.canonicalUrl)
        assertEquals("post:910001",post.id);assertEquals("twitter:repaircrew",CrossPosts.originalAccount(post))
        assertNull(routes.recognize("https://render.example/Other/status/910002"))
        assertEquals(source,post.provenance!!.source)
    }

    @Test fun aDisagreeingChannelCreatorOrGuidCannotInventAnOriginalIdentity() {
        for(body in listOf(rss(channel="Other"),rss(creator="@AnotherCrew"),rss(guid="910002"))) {
            assertFalse(page(body).posts.single().canonicalUrl.startsWith("https://x.com/"))
        }
        assertFalse(page(rss(),source.copy(frontend="other")).posts.single().canonicalUrl.startsWith("https://x.com/"))
    }

    @Test fun theAlternateRetweetHeadingPreservesItsCuratorWithoutRepeatingTheCaption() {
        val post=page(rss()).posts.single()
        assertEquals(prose,PostPresentation.content(post))
        assertEquals("Reposted by @Workshop",PostPresentation.text(post).attribution)
        assertEquals(prose,post.body);assertTrue(post.title.startsWith("RT @RepairCrew"))
    }

    @Test fun aHeadingNamingADifferentAuthorRemainsVisible() {
        val post=page(rss(creator="@AnotherCrew")).posts.single()
        assertTrue(PostPresentation.text(post).title.startsWith("RT @RepairCrew"))
        assertNull(PostPresentation.text(post).attribution)
    }
}
