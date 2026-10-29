/* anti social, GPL-3.0-or-later. Synthetic public-preview extraction controls. */
package app.antisocial.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class NitterLinkCardsTest {
    private val loader=javaClass
    private val cleaner=UrlCleaner(JsonParser.parseReader(loader.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(loader.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val source=routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"}
    private val target=routes.target(Platform.X,"studio")
    private fun card(destination:String="https://workshop.example/guide",asset:String="https://images.example/guide.jpg",title:String="Repair manual")="""<div class="card large"><a class="card-container" href="$destination"><div class="card-image"><img src="$asset" alt="Connector photograph"></div><div class="card-content"><h2 class="card-title">$title</h2><p class="card-description">Part 40 remains available.</p></div></a></div>"""
    private fun post(extra:String,body:String="Inspect the source guide.")="""<div class="timeline-item"><a class="tweet-link" href="/studio/status/81000001"></a><div class="tweet-body"><a class="username" href="/studio">@studio</a><div class="tweet-content">$body</div>$extra</div></div>"""
    private fun page(html:String,selected:Source=source,selectedTarget:Target=target)=Extraction.parse(Payload(html,routes.convert(selectedTarget,selected),"text/html",200),selectedTarget,selected,routes)
    @Test fun declaredPreviewRetainsItsImageAndDestinationWithoutRewritingTheCaption() {
        val result=page(post(card())).posts.single()
        assertEquals("Inspect the source guide.",result.body);assertEquals("Repair manual",result.title)
        assertEquals(listOf("https://workshop.example/guide"),result.links)
        val image=result.media.single();assertEquals("https://images.example/guide.jpg",image.url)
        assertEquals("Linked preview: Repair manual\nSource excerpt: Part 40 remains available.\nImage description: Connector photograph",image.description)
        assertEquals("post:81000001",result.id);assertEquals(source,result.provenance!!.source)
    }
    @Test fun ownPreviewExcludesQuotedNestedAndCaptionInsertedCards() {
        val other=card("https://other.example/quote","https://images.example/quote.jpg")
        val extra=card()+"<div class=quote>$other</div><div class=quoted-tweet>$other</div>"+
            "<div class=timeline-item>$other</div>"
        val result=page(post(extra)).posts.single()
        assertEquals(listOf("https://images.example/guide.jpg"),result.media.map {it.url})
        assertEquals(listOf("https://workshop.example/guide"),result.links)
    }
    @Test fun markupInsideTheCaptionKeepsAuthoredLinksWithoutBecomingAPreview() {
        val authored=card("https://other.example/guide","https://images.example/authored.jpg")
        val result=page(post("",body="Authored text $authored")).posts.single()
        assertTrue(result.media.isEmpty());assertEquals(listOf("https://other.example/guide"),result.links)
    }
    @Test fun aMainWrapperCannotPromoteItsFirstQuotedOrAmbiguousTimelineToTheOwner() {
        val quoted="<div class=main-tweet><div class=quote>"+post(card())+"</div></div>"
        assertTrue(page(quoted).posts.single().media.isEmpty())
        val ambiguous="<div class=main-tweet>"+post(card())+post(card(asset="https://images.example/other.jpg"))+"</div>"
        assertTrue(page(ambiguous).posts.single().media.isEmpty())
        val quotation=post(card(asset="https://images.example/quote.jpg")).replace("/studio/status/81000001","/other/status/81000002")
        val own="<div class=main-tweet><div class=quote>"+quotation+"</div>"+post(card())+"</div>"
        val selected=page(own).posts.single()
        assertEquals("post:81000002",selected.id)
        assertTrue("An own preview cannot be attached to the generic parser's different selected identity",selected.media.isEmpty())
    }
    @Test fun competingOwnPermalinksDoNotSupplyAnUnboundPreview() {
        val html=post(card()).replace("<div class=\"tweet-body\">","<div class=\"tweet-body\"><span class=tweet-date><a href='/studio/status/81000002'>Other original</a></span>")
        assertTrue(page(html).posts.single().media.isEmpty())
    }
    @Test fun mainTweetWrapperUsesItsOwnTimelineAndKeepsTheOriginalGallery() {
        val gallery="<div class=attachments><img src='https://images.example/own.jpg' alt='Own photograph'></div>"
        val result=page("<div class=main-tweet>"+post(card()+gallery)+"</div>").posts.single()
        assertEquals(setOf("https://images.example/guide.jpg","https://images.example/own.jpg"),result.media.map {it.url}.toSet())
        assertEquals("Own photograph",result.media.single {it.url.endsWith("own.jpg")}.description)
    }
    @Test fun ambiguousOrCredentialedAndInsecureContainersSupplyNoPreview() {
        for(extra in listOf(card(destination="http://workshop.example/guide"),card(destination="https://secret@workshop.example/guide"),
            card().replace("</a>","</a><a class=card-container href='https://other.example/guide'>Ambiguous</a>"))) {
            val result=page(post(extra)).posts.single();assertTrue(result.media.isEmpty());assertTrue(result.links.isNullOrEmpty())
        }
        for(asset in listOf("http://images.example/guide.jpg","https://secret@images.example/guide.jpg","javascript:alert(1)"))
            assertTrue(page(post(card(asset=asset))).posts.single().media.isEmpty())
    }
    @Test fun duplicatePreviewsAndVideoCardsKeepTheirExistingMediaSemantics() {
        val video="<div class='attachments card'><video src='https://videos.example/guide.mp4' poster='https://images.example/video.jpg'></video></div>"
        val result=page(post(card()+card()+video)).posts.single()
        assertEquals(1,result.media.count {it.type=="image"});assertEquals(1,result.media.count {it.type=="video"})
        assertEquals(listOf("https://workshop.example/guide"),result.links)
    }
    @Test fun previewBoundsAndFrontendScopeDoNotExpandGenericExtraction() {
        val extra=(0..10).joinToString(""){card("https://workshop.example/$it","https://images.example/$it.jpg","T".repeat(600))}
        val result=page(post(extra)).posts.single();assertEquals(4,result.media.size);assertEquals(4,result.links!!.size)
        assertTrue(result.media.all {it.description.substringBefore('\n').length<=528})
        val original=Source(Platform.X,"fixture",Platform.X.origin,role=SourceRole.ORIGINAL)
        assertTrue(page(post(card()),original).posts.single().media.isEmpty())
    }
}
