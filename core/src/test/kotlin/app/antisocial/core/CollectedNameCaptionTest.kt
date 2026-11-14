/* anti social, GPL-3.0-or-later. Synthetic source captions. */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CollectedNameCaptionTest {
    private val tail="speaks about the shared workshop schedule and the practical demonstrations planned for visitors throughout the afternoon. \"Everyone is welcome. Please bring your questions and learn about the equipment available for our community.\""
    private val full="Director Mira Vale"
    private val account="Director, @WorkshopLead,"
    private fun originals()=listOf(
        Post("881",Platform.X,"https://x.com/caption_fixture/status/881","","Workshop $account $tail https://t.co/FixtureName01",publishedAt=1_790_000_000_000L,media=listOf(Media("https://video.fixture.invalid/workshop.mp4",type="video"))),
        Post("882",Platform.FACEBOOK,"https://www.facebook.com/caption_fixture/posts/882","","Workshop $full $tail",publishedAt=1_790_000_010_000L,media=listOf(Media("https://video.fixture.invalid/other.mp4",type="video"))),
        Post("883",Platform.INSTAGRAM,"https://www.instagram.com/p/CaptionFixture883/","▶ Workshop $full speaks about the shared workshop schedule...","Workshop $full $tail",publishedAt=1_790_000_015_000L,media=listOf(Media("https://image.fixture.invalid/preview.png")))
    )
    private fun normalized(text:String)=TextWhitespace.collapse(org.jsoup.parser.Parser.unescapeEntities(org.jsoup.parser.Parser.unescapeEntities(text,false),false)).trim()
    private fun projection(posts:List<Post>,fusion:PostFusion) {
        for(post in posts) {
            val key=CrossPosts.originalKey(post)
            val projected=requireNotNull(fusion.weave).joinToString(" "){run->run.variants.single {key in it.originalKeys}.text}
            assertEquals(normalized(PostPresentation.content(post)),normalized(projected))
        }
    }
    @Test fun earlyNameAndHandleDifferencesReadTheLongSharedCaptionOnceWithoutPictureProof() {
        val originals=originals();val before=Gson().toJson(originals)
        for(posts in listOf(originals,originals.reversed(),originals.drop(1)+originals.first())) {
            val fusion=requireNotNull(CrossPostFusion.composeCollectedNames(posts))
            projection(posts,fusion)
            val names=fusion.weave!!.single {run->run.variants.any {it.text==account}}
            assertEquals(setOf(account,full),names.variants.map {it.text}.toSet())
            assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),names.variants.single {it.text==full}.platforms)
            assertEquals(1,Regex(Regex.escape(tail)).findAll(fusion.segments.single().text).count())
            assertTrue(fusion.weave.flatMap {it.variants}.any {it.text.contains("https://t.co/FixtureName01")})
        }
        assertEquals(before,Gson().toJson(originals))
    }
    @Test fun aLeadingCoupledNameListRetainsEveryCompleteOriginalPath() {
        val posts=originals().take(2).map {it.copy(body=it.body.removePrefix("Workshop ").replace(account,"@WorkshopLead and @RepairNotes").replace(full,"Mira Vale and Rowan Beck"))}
        val fusion=requireNotNull(CrossPostFusion.composeCollectedNames(posts));projection(posts,fusion)
        val changes=fusion.weave!!.filterNot {it.shared}.flatMap {it.variants}.map {it.text}
        assertTrue(changes.contains("@WorkshopLead and @RepairNotes"));assertTrue(changes.contains("Mira Vale and Rowan Beck"))
        assertEquals(1,Regex(Regex.escape(tail)).findAll(fusion.segments.single().text).count())
    }
    @Test fun namesDoNotBecomeAnEscapeForChangedFactsActionsOrIncompleteCaptions() {
        val posts=originals()
        for(change in listOf("Former Director Mira Vale","Director 19 Mira Vale","Director does not agree","Director Criticizes Mira Vale?")) {
            val altered=listOf(posts[0],posts[1].copy(body=posts[1].body.replace(full,change)))
            assertNull(change,CrossPostFusion.composeCollectedNames(altered))
        }
        for(altered in listOf(posts[1].copy(preview=true),posts[1].copy(body="An unrelated workshop announcement with another purpose."),posts[1].copy(body=posts[1].body+"\u202e"),posts[1].copy(body="Long ".repeat(1000))))
            assertNull(CrossPostFusion.composeCollectedNames(listOf(posts[0],altered)))
        assertNull(CrossPostFusion.composeCollectedNames(listOf(posts[0])))
    }
    @Test fun suppliedAlreadyCollectedSourceCaptionsReadTheirCommonPassagesOnce() {
        val path=System.getenv("ANTISOCIAL_PRIVATE_COLLECTED_CAPTION").orEmpty()
        assumeTrue("An explicit private admitted-caption packet is required",path.isNotBlank())
        val file=File(path);require(file.length() in 1..4*1024*1024)
        val gson=Gson();val packet=JsonParser.parseReader(file.reader()).asJsonObject
        val posts=packet.getAsJsonArray("posts").map {gson.fromJson(it,Post::class.java)}
        val before=gson.toJson(posts)
        for(input in listOf(posts,posts.reversed())) {
            val fusion=requireNotNull(CrossPostFusion.composeCollectedNames(input));projection(input,fusion)
            val text=normalized(fusion.segments.joinToString(" "){it.text})
            packet.getAsJsonArray("oncePassages").forEach {passage->assertEquals(1,Regex(Regex.escape(normalized(passage.asString))).findAll(text).count())}
        }
        assertEquals(before,gson.toJson(posts))
    }
}
