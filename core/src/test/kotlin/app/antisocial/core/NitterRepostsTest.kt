/* anti social, GPL-3.0-or-later. Entirely synthetic profile documents. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NitterRepostsTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject))
    private val source=routes.sources.first {it.frontend=="nitter"}
    private val target=routes.target(Platform.X,"Workshop")
    private val base=routes.convert(target,source).substringBefore('#')
    private val caption="The repair crew published the complete equipment list for the community workshop."
    private val profile="""<div class="profile-card"><div class="profile-card-info"><a class="profile-card-fullname" href="/Workshop">Community Workshop</a><a class="profile-card-username" href="/Workshop">@Workshop</a></div></div>"""
    private val header="""<div class="retweet-header"><span><span class="icon-retweet"></span> Community Workshop retweeted</span></div>"""
    private fun card(label:String=header,extra:String="",author:String="RepairCrew",id:String="910001")="""<div class="timeline-item"><a class="tweet-link" href="/$author/status/$id#m"></a><div class="tweet-body"><div>$label<div class="tweet-header"><a class="username" href="/$author">@$author</a><span class="tweet-date"><a href="/$author/status/$id#m" title="2026-01-09T12:00:00Z">Jan 9</a></span></div></div><div class="tweet-content">$caption <a href="https://workshop.example/equipment">Equipment</a></div><div class="attachments"><img src="https://images.example/equipment.jpg" alt="Equipment inventory"/></div>$extra</div></div>"""
    private fun page(html:String=profile+card(),url:String=base,selected:Source=source,selectedTarget:Target=target)=
        Extraction.parse(Payload(html,url,"text/html",200),selectedTarget,selected,routes)

    @Test fun aBoundRepostRetainsOriginalAuthorshipCaptionDateMediaAndLinks() {
        val post=page().posts.single();val plain=page(profile+card(label="")).posts.single()
        val relation=PublicRelation(PublicRelationKind.REPOSTED_BY,"https://x.com/Workshop","Community Workshop")
        assertEquals(listOf(relation),post.publicRelations)
        assertEquals(plain,post.copy(publicRelations=null,provenance=plain.provenance))
        assertEquals("RepairCrew",post.author);assertEquals("https://x.com/RepairCrew/status/910001",post.canonicalUrl)
        assertEquals(Extraction.timestamp("2026-01-09T12:00:00Z"),post.publishedAt)
        assertEquals("Reposted by @Workshop",PostPresentation.text(post).attribution)
        assertEquals(PostPresentation.content(plain),PostPresentation.content(post))
        assertTrue(post.title.isBlank());assertEquals(1,post.media.size)
        assertEquals("twitter:repaircrew",CrossPosts.originalAccount(post))
        assertTrue(PublicRelations.valid(post))
    }

    @Test fun sourceOwnedCursorAndOrdinaryProfileFragmentKeepTheSameAttribution() {
        for(suffix in listOf("#m","?cursor=next-page","?cursor=next-page#m"))assertNotNull(page(url=base+suffix).posts.single().publicRelations)
        assertNull(page(profile+card(label="")).posts.single().publicRelations)
    }

    @Test fun anotherAccountOrUnqualifiedPageCannotBindAPlainDisplayName() {
        for(url in listOf(base.replace("/Workshop","/Other"),base+"/status/910001",base+"?other=1",base+"?cursor=a&cursor=b",base+"#other",base.replace("https:","http:"),"https://elsewhere.example/Workshop"))
            assertTrue(url,page(url=url).posts.all {it.publicRelations==null})
        assertNull(page(selected=source.copy(frontend="other")).posts.single().publicRelations)
        assertNull(page(selected=source.copy(role=SourceRole.ORIGINAL)).posts.single().publicRelations)
        assertNull(page(selectedTarget=routes.target(Platform.X,"RepairCrew")).posts.single().publicRelations)
        for(bad in listOf(profile.replace("@Workshop","@Other"),profile.replace("href=\"/Workshop\"","href=\"/Other\""),profile.replace("Community Workshop","Other Workshop"),profile+profile,""))
            assertNull(page(bad+card()).posts.single().publicRelations)
    }

    @Test fun quotedAuthoredAndConflictingHeadersDoNotAttributeTheOuterPost() {
        for(extra in listOf("<div class=\"quote\">$header</div>","<div class=\"quoted-tweet\">$header</div>","<div class=\"tweet-content\">$header</div>"))
            assertNull(page(profile+card(label="",extra=extra)).posts.single().publicRelations)
        assertNull(page(profile+card(label=header+header)).posts.single().publicRelations)
        assertNull(page(profile+card(label=header.replace("Community Workshop","Other Workshop"))).posts.single().publicRelations)
        assertNotNull(page(profile+card(extra="<div class=\"quote\">$header</div>")).posts.single().publicRelations)
    }

    @Test fun savedSnapshotAndMeaningfulHeadingsKeepAttributionOutsideTheCaption() {
        val post=page().posts.single().copy(title="Equipment announcement")
        val captured=Gson().fromJson(Gson().toJson(PostSnapshot.from(post)),PostSnapshot::class.java)
        val restored=captured.asPost(post.copy(provenance=post.provenance!!.copy(source=source.copy(frontend="other"))))
        assertEquals(post.publicRelations,restored.publicRelations)
        assertEquals(PostPresentation.text(post),PostPresentation.text(restored))
        assertEquals("Equipment announcement",PostPresentation.text(post).title)
        assertEquals(post.body,PostPresentation.text(post).body)
        assertEquals(post.body,PostPresentation.text(post.copy(title=post.body)).body)
        assertEquals("",PostPresentation.text(post.copy(title=post.body)).title)
        assertEquals("Reposted by @Workshop",PostPresentation.text(post.copy(title=post.body)).attribution)
        assertNull(PostPresentation.text(post.copy(platform=Platform.FACEBOOK)).attribution)
        assertNull(PostPresentation.text(post.copy(publicRelations=listOf(PublicRelation(PublicRelationKind.REPOSTED_BY,"https://other.example/Workshop")))).attribution)
    }

    @Test fun passiveDiscoveryFollowsSharingTowardTheOriginalAuthorWithoutClaimingCommonControl() {
        val page=page();val original=page.posts.single()
        val observed=AdjacentDiscovery.observed(Profile(target,"Community Workshop"),page,routes,123L)
        val edge=observed.single {it.evidence.kind==AdjacentKind.FORWARDED}
        assertEquals("https://x.com/RepairCrew",edge.profile.target.canonicalUrl)
        assertEquals(target,edge.evidence.owner);assertEquals(listOf(original.canonicalUrl),edge.evidence.records)
        assertEquals(123L,edge.evidence.checkedAt)
        assertFalse(observed.any {AdjacentDiscovery.key(it.profile.target)==AdjacentDiscovery.key(target)})
        assertTrue(AdjacentDiscovery.observed(Profile(routes.target(Platform.X,"RepairCrew"),"Repair crew"),page,routes,123L).none {it.evidence.kind==AdjacentKind.FORWARDED})
    }
}
