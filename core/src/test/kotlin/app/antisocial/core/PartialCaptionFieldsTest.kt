/* anti social, GPL-3.0-or-later. Literal references inside admitted collections. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PartialCaptionFieldsTest {
    private val intro="Last month, the repair team opened"
    private val middle="It respects their time. Gives clear instructions. And helps everyone repair their belongings."
    private fun caption(reference:String)="$intro $reference, a workshop built around visitors. $middle See the complete workshop tour and meet the people who made it possible."
    private fun post(id:String,platform:Platform,body:String,title:String="")=Post(id,platform,when(platform) {
        Platform.FACEBOOK->"https://www.facebook.com/publicstudio/posts/$id"
        Platform.INSTAGRAM->"https://www.instagram.com/p/$id/"
        else->"https://x.com/publicstudio/status/$id"
    },"Public studio",body,title,publishedAt=1_800_000_000_000+id.toLong())
    private fun originals(extra:Int=4):List<Post> {
        val x=post("100",Platform.X,caption("https://t.co/Fixture91")+" https://t.co/More72","Workshop film")
        val fb=post("101",Platform.FACEBOOK,caption("Example.org"))
        val instagram=post("102",Platform.INSTAGRAM,caption("Example.org").replace(". Gives",".\n\nGives"))
        val second=post("103",Platform.FACEBOOK,fb.body)
        return listOf(x,fb,instagram,second)+(1..extra).map {i->post((200+i).toString(),Platform.X,
            "An independently worded response from workshop partner $i.","Response $i")}
    }
    private fun path(fusion:PostFusion,post:Post):String {
        val key=CrossPosts.originalKey(post)
        return fusion.segments.filter {key in it.originalKeys}.joinToString("\n\n"){segment->
            segment.weave?.joinToString(" "){run->run.variants.single {key in it.originalKeys}.text} ?: segment.text
        }
    }
    private fun preserved(fusion:PostFusion,posts:List<Post>) {
        for(post in posts)assertEquals(PostPresentation.content(post).filterNot(Char::isWhitespace),path(fusion,post).filterNot(Char::isWhitespace))
    }
    @Test fun referencesWeaveInsideTheLargerCollectionWithEverySourcePathIntact() {
        val posts=originals();val before=Gson().toJson(posts)
        val literal=requireNotNull(CaptionFieldBlocks.compose(posts))
        assertTrue(literal.segments.all {it.weave==null})
        assertEquals(2,literal.segments.sumOf {Regex(Regex.escape(intro)).findAll(it.text).count()})
        val fusion=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true))
        assertEquals(1,fusion.segments.count {it.weave!=null})
        val woven=fusion.segments.single {it.weave!=null}
        assertEquals(posts.take(4).mapTo(linkedSetOf(),CrossPosts::originalKey),woven.originalKeys)
        for(common in listOf(intro,middle))assertEquals(1,fusion.segments.sumOf {Regex(Regex.escape(common)).findAll(it.text).count()})
        assertTrue(woven.text.contains("https://t.co/More72"))
        preserved(fusion,posts);assertEquals(before,Gson().toJson(posts))
        assertEquals(fusion,CaptionFieldBlocks.compose(posts.reversed(),partialReferences=true))
    }
    @Test fun changedClaimsStayAsCompleteAttributedCaptions() {
        val posts=originals().mapIndexed {i,post->if(i==0)post.copy(body=post.body.replace("It respects their time.","It does not respect their time."))else post}
        val fusion=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true))
        assertTrue(fusion.segments.all {it.weave==null});preserved(fusion,posts)
        assertTrue(fusion.segments.any {it.text.contains("does not respect")})
    }
    @Test fun aFullCollectionKeepsUnexaminedBodiesWhenItsCompressionAllowanceIsSpent() {
        val posts=originals(60);assertEquals(64,posts.size)
        val before=Gson().toJson(posts)
        val fusion=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true))
        preserved(fusion,posts)
        assertEquals(posts.mapTo(linkedSetOf(),CrossPosts::originalKey),fusion.segments.flatMap {it.originalKeys}.toSet())
        assertEquals(before,Gson().toJson(posts))
    }
    @Test fun authoredEntitySpellingsCannotDisappearThroughTransportDecoding() {
        val posts=originals().mapIndexed {i,post->
            val body=post.body.replace("clear instructions","clear &amp; careful instructions")
            if(i==0)post.copy(body=body.replace("&amp;","&amp;amp;"),provenance=Provenance(
                source=Source(Platform.X,"sotwe-api","https://frontend.example.org"),finalUrl="https://frontend.example.org/profile",fetchedAt=1))
            else post.copy(body=body)
        }
        val fusion=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true))
        assertTrue(fusion.segments.all {it.weave==null});preserved(fusion,posts)
        assertTrue(fusion.segments.any {it.text.contains("&amp;")})
    }
}
