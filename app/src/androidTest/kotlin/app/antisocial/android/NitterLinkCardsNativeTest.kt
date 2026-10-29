/* anti social, GPL-3.0-or-later. Native declared-preview retention. */
package app.antisocial.android

import androidx.test.platform.app.InstrumentationRegistry
import app.antisocial.core.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NitterLinkCardsNativeTest {
    @Test fun aQuoteOnlyMainWrapperCannotSupplyAnOwnPreview() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject,
            UrlCleaner(JsonParser.parseReader(context.assets.open("clean-links.json").reader()).asJsonObject))
        val source=routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"};val target=routes.target(Platform.X,"studio")
        val html="""<div class=main-tweet><div class=quote><div class=timeline-item><a class=tweet-link href='/other/status/81000002'></a><div class=tweet-content>A quoted guide.</div><div class=card><a class=card-container href='https://other.example/guide'><div class=card-image><img src='https://images.example/quote.jpg'></div><h2 class=card-title>Quoted source</h2></a></div></div></div></div>"""
        val page=Extraction.parse(Payload(html,routes.convert(target,source),"text/html",200),target,source,routes)
        assertTrue(page.posts.single().media.isEmpty())
        val own="""<div class=timeline-item><a class=tweet-link href='/studio/status/81000001'></a><div class=tweet-content>Own caption.</div><div class=card><a class=card-container href='https://workshop.example/guide'><div class=card-image><img src='https://images.example/guide.jpg'></div></a></div></div>"""
        val firstQuote=html.removeSuffix("</div>")+own+"</div>"
        val selected=Extraction.parse(Payload(firstQuote,routes.convert(target,source),"text/html",200),target,source,routes).posts.single()
        assertEquals("post:81000002",selected.id);assertTrue(selected.media.isEmpty())
    }
    @Test fun nativePreviewKeepsItsDestinationImageAndLabelledExcerpt() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val routes=DarwinRoutes(JsonParser.parseReader(context.assets.open("darwin-routes.json").reader()).asJsonObject,
            UrlCleaner(JsonParser.parseReader(context.assets.open("clean-links.json").reader()).asJsonObject))
        val source=routes.sources.first {it.platform==Platform.X&&it.frontend=="nitter"}
        val target=routes.target(Platform.X,"studio")
        val html="""<div class=timeline-item><a class=tweet-link href='/studio/status/81000001'></a><a class=username>@studio</a><div class=tweet-content>Inspect the workshop guide.</div><div class=card><a class=card-container href='https://workshop.example/guide'><div class=card-image><img src='https://images.example/guide.jpg'></div><h2 class=card-title>Workshop guide</h2><p class=card-description>Part 40 remains available.</p></a></div><div class=quote><div class=card><a class=card-container href='https://other.example/quote'><div class=card-image><img src='https://images.example/quote.jpg'></div></a></div></div></div>"""
        val post=Extraction.parse(Payload(html,routes.convert(target,source),"text/html",200),target,source,routes).posts.single()
        assertEquals("Inspect the workshop guide.",post.body)
        assertEquals(listOf("https://workshop.example/guide"),post.links)
        assertEquals("https://images.example/guide.jpg",post.media.single().url)
        assertEquals("Linked preview: Workshop guide\nSource excerpt: Part 40 remains available.",post.media.single().description)
    }
    @Test fun suppliedPrivatePreviewSurvivesTheRealCapturedRepresentation() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("antisocialPrivateNitterCard")=="true"&&args.getString("antisocialIsolatedFixtureDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.filesDir,"nitter-card-private");val spec=File(folder,"input.json")
        require(spec.isFile&&spec.length() in 1..32768)
        val input=JsonParser.parseReader(spec.reader()).asJsonObject
        val name=input["file"].asString.also {require(it.matches(Regex("[A-Za-z0-9_.-]{1,100}")))}
        val file=File(folder,name);require(file.isFile&&file.length() in 1..2*1024*1024)
        val repo=(context.applicationContext as AntiSocialApplication).repo
        val target=requireNotNull(repo.routes.recognize(input["target"].asString))
        val source=repo.routes.sources.first {it.platform==target.platform&&it.frontend=="nitter"&&it.root==input["sourceRoot"].asString}
        val payload=Payload(file.readText(),input["finalUrl"].asString,"text/html",200)
        val page=Extraction.parse(payload,target,source,repo.routes)
        val post=page.posts.single {CrossPosts.originalKey(it)==input["originalKey"].asString}
        val output=File(context.getExternalFilesDir("validation"),"nitter-card-private").apply {mkdirs()}
        val timings=(0 until 12).map {
            val wall=android.os.SystemClock.elapsedRealtimeNanos();val cpu=android.os.Debug.threadCpuTimeNanos()
            val next=Extraction.parse(payload,target,source,repo.routes)
            val elapsed=mapOf("wallMs" to (android.os.SystemClock.elapsedRealtimeNanos()-wall)/1_000_000.0,
                "cpuMs" to (android.os.Debug.threadCpuTimeNanos()-cpu)/1_000_000.0)
            assertEquals(page.posts.map(CrossPosts::originalKey),next.posts.map(CrossPosts::originalKey))
            elapsed
        }
        File(output,"metrics.json").writeText(repo.gson.toJson(mapOf("characters" to payload.text.length,"posts" to page.posts.size,
            "rootImages" to post.media.count {it.type=="image"},"timings" to timings)))
        val beforeName=input["before"]?.asString?.also {require(it.matches(Regex("[A-Za-z0-9_.-]{1,100}")))}
        if(beforeName!=null) {
            val beforeFile=File(folder,beforeName);require(beforeFile.isFile&&beforeFile.length() in 1..2*1024*1024)
            val before=repo.gson.fromJson(beforeFile.readText(),Page::class.java)
            assertEquals(before.posts.map(CrossPosts::originalKey),page.posts.map(CrossPosts::originalKey))
            before.posts.zip(page.posts).forEach {(old,current)->
                assertEquals(old.provenance?.copy(fetchedAt=requireNotNull(current.provenance).fetchedAt),current.provenance)
                assertEquals(old.copy(media=current.media,links=current.links,provenance=current.provenance),current)
                assertTrue(current.media.containsAll(old.media))
                assertTrue(current.links.orEmpty().containsAll(old.links.orEmpty()))
            }
        }
        assertEquals(input["caption"].asString,post.body);assertEquals(input["title"].asString,post.title)
        assertEquals("The source's declared preview image must survive extraction",1,post.media.size)
        assertEquals(input["image"].asString,post.media.single().url)
        assertTrue(post.media.single().description.startsWith("Linked preview: "))
        assertTrue(post.media.single().description.contains(input["excerpt"].asString))
        assertTrue(input["destination"].asString in post.links.orEmpty())
        File(output,"post.json").writeText(repo.gson.toJson(post));File(output,"page.json").writeText(repo.gson.toJson(page))
    }
}
