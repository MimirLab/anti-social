/* anti social, GPL-3.0-or-later. Synthetic source-bound RSS sharing records. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NitterRssRepostsTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject))
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val target=routes.target(Platform.X,"Workshop")
    private val caption="The repair crew published the complete equipment list for the next community workshop."
    private fun rss(title:String="RT by @Workshop: $caption",body:String=caption,creator:String="@RepairCrew",guid:String="2100000000000000001",channel:String="Workshop",render:String="https://render.example",media:Boolean=false)=
        """<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><link>$render/$channel</link><item><title>$title</title><dc:creator>$creator</dc:creator><guid isPermaLink="false">$guid</guid><link>$render/RepairCrew/status/2100000000000000001#m</link><pubDate>Fri, 09 Jan 2026 12:00:00 GMT</pubDate><description><![CDATA[<p>$body</p>${if(media)"<img src=\"https://images.example/notice.jpg\"/>"else ""}]]></description></item></channel></rss>"""
    private fun page(body:String=rss(),url:String=source.root+"/Workshop/rss",selected:Source=source,owner:Target=target)=
        Extraction.parse(Payload(body,url,"application/rss+xml",200),owner,selected,routes)

    @Test fun aBoundRssRepostFeedsTheExistingDiscoveryBankWithoutChangingSourceText() {
        val page=page();val post=page.posts.single()
        assertEquals(listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,target.canonicalUrl)),post.publicRelations)
        assertEquals("@RepairCrew",post.author);assertEquals(caption,post.body);assertEquals("RT by @Workshop: $caption",post.title)
        assertEquals(caption,PostPresentation.content(post));assertEquals("Reposted by @Workshop",PostPresentation.text(post).attribution)
        assertTrue(page.limitedTimeline);assertNull(page.historyExhausted);assertTrue(PublicRelations.valid(post))
        val observations=AdjacentDiscovery.observed(Profile(target,"Community Workshop"),page,routes,123L)
        val shared=observations.single {it.evidence.kind==AdjacentKind.FORWARDED}
        assertEquals("https://x.com/RepairCrew",shared.profile.target.canonicalUrl)
        assertEquals(target,shared.evidence.owner);assertEquals(listOf(post.canonicalUrl),shared.evidence.records)
        assertFalse(AdjacentDiscovery.observed(Profile(routes.target(Platform.X,"RepairCrew"),"Repair crew"),page,routes,123L).any {it.evidence.kind==AdjacentKind.FORWARDED})
    }

    @Test fun declaredLegacyRenderingAndAlternateHeadingsKeepTheirBoundSharingRole() {
        for(body in listOf(rss(render="http://reader.example"),rss(title="RT @RepairCrew (by @Workshop): $caption"),
            rss(guid="https://render.example/RepairCrew/status/2100000000000000001#m"))) {
            val post=page(body).posts.single();assertNotNull(post.publicRelations)
            assertEquals("Workshop",NitterReposts.handle(post));assertTrue(PublicRelations.valid(post))
        }
        assertNotNull(page(url=source.root+"/Workshop/rss?cursor=next-page").posts.single().publicRelations)
    }

    @Test fun anotherOwnerRecordOrFeedScopeCannotTurnAHeadingIntoASharingClaim() {
        for(body in listOf(rss(title="RT by @Different: $caption"),rss(creator="@Different"),rss(guid="2100000000000000002"),rss(channel="Different"),
            rss(guid="https://elsewhere.example/RepairCrew/status/2100000000000000001"),
            rss(guid="https://render.example/RepairCrew/status/2100000000000000001#other"),
            rss(guid="https://user@render.example/RepairCrew/status/2100000000000000001"),
            rss(guid="https://render.example/RepairCrew/status/2100000000000000001?extra=1"),
            rss().replace("</guid>","</guid><guid>2100000000000000002</guid>"),
            rss().replace("</dc:creator>","</dc:creator><dc:creator>@Different</dc:creator>"),
            rss(title="An independent headline",body=caption)))
            assertTrue(page(body).posts.all {it.publicRelations==null})
        for(url in listOf(source.root+"/Other/rss",source.root+"/Workshop/rss?cursor=a&cursor=b",source.root+"/Workshop/rss?other=1",source.root+"/Workshop/rss#other"))
            assertTrue(page(url=url).posts.all {it.publicRelations==null})
        assertTrue(page(selected=source.copy(frontend="other")).posts.all {it.publicRelations==null})
        assertTrue(page(owner=routes.target(Platform.X,"Different")).posts.all {it.publicRelations==null})
        val authored="RT by @Workshop: A literal line in the author's own caption."
        assertNull(page(rss(title=authored,body=authored)).posts.single().publicRelations)
    }

    @Test fun aMediaOnlyRepostHasAttributionWhileItsBlankAuthoredCaptionStaysBlank() {
        val post=page(rss(title="RT by @Workshop: Image",body="",media=true)).posts.single()
        assertNotNull(post.publicRelations);assertEquals("",post.body);assertEquals(1,post.media.size)
        assertEquals("Reposted by @Workshop",PostPresentation.text(post).attribution)
        assertEquals("",PostPresentation.text(post).title)
        assertNull(page(rss(title="RT by @Different: Image",body="",media=true)).posts.single().publicRelations)
        assertNull(page(rss(title="RT by @Workshop: Image",body="")).posts.single().publicRelations)
    }

    @Test fun theRssObservationSurvivesASourceSnapshotWithoutCreatingAnotherAccountIdentity() {
        val post=page().posts.single();assertNotNull(post.publicRelations)
        val captured=Gson().fromJson(Gson().toJson(PostSnapshot.from(post)),PostSnapshot::class.java)
        val restored=captured.asPost(post.copy(provenance=post.provenance!!.copy(source=source.copy(frontend="other"))))
        assertEquals(post.publicRelations,restored.publicRelations);assertEquals("Reposted by @Workshop",PostPresentation.text(restored).attribution)
        assertEquals("twitter:repaircrew",CrossPosts.originalAccount(restored))
    }

    @Test fun aHostTruncatedBeforeItsTldNeedsTheExactSuppliedDestinationAndCaption() {
        val url="https://workshop-registration.community.example"
        val full="$caption $url"
        fun input(href:String=url,label:String="workshop-registration.community.exam…",text:String=caption)=
            rss(title="RT by @Workshop: $full",body="${text} <a href=\"$href\">$label</a> Video",media=true)
        val post=page(input()).posts.single()
        assertNotNull(post.publicRelations)
        assertEquals(full,PostPresentation.content(post));assertEquals("Reposted by @Workshop",PostPresentation.text(post).attribution)
        assertTrue(post.body.endsWith("workshop-registration.community.exam… Video"))
        for(raw in listOf(input(href="https://different.example"),input(label="different-community.exam…"),input(text="A different announcement."))) {
            val different=page(raw).posts.single()
            assertNull(different.publicRelations);assertNull(PostPresentation.text(different).attribution)
            assertEquals("RT by @Workshop: $full",PostPresentation.text(different).title)
        }
    }
}
