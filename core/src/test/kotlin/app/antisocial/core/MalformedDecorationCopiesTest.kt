/* anti social, GPL-3.0-or-later. Synthetic malformed-decoration regressions. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MalformedDecorationCopiesTest {
    private val prose="The repair fund paid each volunteer $48 for documented replacement parts during the spring workshop. This amount represents a 7% increase compared with the previous session. More useful equipment stays in service."
    private val at=1_800_000_000_000L
    private val picture=Media("https://images.example/workshop.jpg")
    private fun posts():List<Post> = listOf(
        Post("51001",Platform.X,"https://x.com/workshop/status/51001","Workshop","$prose 📈🇺��� https://t.co/Fixture1",publishedAt=at,media=listOf(picture)),
        Post("Workshop51",Platform.INSTAGRAM,"https://www.instagram.com/p/Workshop51/","Workshop","$prose 📈🇺🇸",publishedAt=at+1000,media=listOf(picture)),
        Post("51002",Platform.FACEBOOK,"https://www.facebook.com/workshop/posts/51002","Workshop","$prose 📈🇺🇸",publishedAt=at+2000,media=listOf(picture)))
    private val follows=listOf(Platform.X,Platform.INSTAGRAM,Platform.FACEBOOK).map {p->
        val url=when(p){Platform.X->"https://x.com/workshop";Platform.INSTAGRAM->"https://www.instagram.com/workshop/";else->"https://www.facebook.com/workshop"}
        Follow("fixture:${p.id}",Target(p,TargetKind.PROFILE,"workshop",url),"Workshop",personId="fixture-workshop")
    }
    private fun group(ps:List<Post>)=CrossPosts.group(ps,follows,ps.associate {p->p.identityKey to setOf(follows.single {it.target.platform==p.platform}.id)})
    private fun projection(post:Post)=requireNotNull(DecoratedPostCopies.prepare(post))
    private fun <T> permutations(xs:List<T>):List<List<T>> = if(xs.isEmpty())listOf(emptyList())else xs.indices.flatMap {i->permutations(xs.filterIndexed {j,_->i!=j}).map {listOf(xs[i])+it}}

    @Test fun matchingPicturesPermitOneCaptionWithoutRewritingTheDamagedOriginal() {
        val ps=posts();val before=Gson().toJson(ps)
        for(input in permutations(ps))assertEquals(ps.toSet(),group(input).single().posts.toSet())
        assertEquals(before,Gson().toJson(ps))
    }
    @Test fun displayChoosesAnActualIntactEditionAndRetainsAllSourceKeys() {
        val ps=posts();val intact=ps.drop(1).map(PostPresentation::content)
        for(input in permutations(ps)) {
            val fusion=requireNotNull(CrossPostFusion.compose(input));val segment=fusion.segments.single()
            assertTrue(segment.text in intact);assertFalse(segment.text.contains('\ufffd'))
            assertEquals(ps.map {it.identityKey}.toSet(),segment.originalKeys)
        }
        assertTrue(ps.first().body.contains('\ufffd'))
    }
    @Test fun wordsAloneOrASharedArticleCannotExplainUnknownDecoration() {
        val (bad,good)=posts()
        for(pair in listOf(listOf(bad.copy(media=emptyList()),good.copy(media=emptyList())),
            listOf(bad,good.copy(media=listOf(Media("https://images.example/other.jpg")))))) {
            assertEquals(2,group(pair).size);assertNull(CrossPostFusion.compose(pair))
            val linked=pair.map {it.copy(links=listOf("https://workshop.example/report"))}
            assertEquals(2,group(linked).size);assertNull(CrossPostFusion.compose(linked))
        }
    }
    @Test fun unreadableWordsQuantitiesSignsAndUnattachedMarkersStayInTheProjection() {
        val (bad,good)=posts()
        for(text in listOf("$prose �",prose.replace("volunteer","volun�teer")+" 📈🇺🇸",prose.replace("48","4�8")+" 📈🇺🇸",
            "$prose ✅�","$prose 📈� more instructions",prose.removeSuffix(".")+" 📈�","$prose letter�📈")) {
            val first=projection(bad.copy(body=text));assertTrue(text,first.text.contains('\ufffd'))
            assertNotEquals(text,true,DecoratedPostCopies.compare(first,projection(good))?.exact)
        }
    }
    @Test fun factsDestinationsQuestionsAndTheExistingTimeWindowRemainRequired() {
        val (bad,good)=posts()
        for(other in listOf(good.copy(body=good.body.replace("$48","$49")),good.copy(body="Not "+good.body),
            good.copy(body=good.body.replace("service.","service?")),good.copy(links=listOf("https://workshop.example/other")),
            good.copy(publishedAt=at+2*60*60_000L)))assertEquals(other.body,2,group(listOf(bad,other)).size)
    }
    @Test fun entityEncodedDamageHasTheSameBoundedPermissionAsLiteralDamage() {
        val (bad,good)=posts();val encoded=bad.copy(body=bad.body.replace("�","&#xfffd;"))
        assertEquals(1,group(listOf(encoded,good)).size)
        assertFalse(requireNotNull(CrossPostFusion.compose(listOf(encoded,good))).segments.single().text.contains('\ufffd'))
        assertTrue(encoded.body.contains("&#xfffd;"))
    }
    @Test fun contraryPixelsBeatAnEqualAddressAndEqualVideoCoversDoNotProveARecording() {
        val (bad,good)=posts()
        val pixels=ByteArray(4096){100};val changed=pixels.copyOf().apply {this[512]=0}
        fun frame(bytes:ByteArray)=requireNotNull(VisualFrames.encode(1.25,40.0,0,0,List(48){100},bytes))
        val first=bad.copy(media=listOf(picture.copy(visualFingerprint=frame(pixels))))
        val second=good.copy(media=listOf(picture.copy(visualFingerprint=frame(changed))))
        assertNull(DecoratedPostCopies.compare(projection(first),projection(second)))
        assertNull(CrossPostFusion.compose(listOf(first,second)))
        val videos=listOf(bad,good).mapIndexed {i,p->p.copy(media=listOf(Media("https://video.example/recording-$i.mp4","video",picture.url,durationMs=9000)))}
        assertEquals(2,group(videos).size);assertNull(CrossPostFusion.compose(videos))
    }
    @Test fun aMatchingAvatarAndARepeatedArticleAreNotCaptionEvidence() {
        val ps=posts().take(2).map {it.copy(avatar=picture.url,links=listOf("https://workshop.example/report"))}
        assertEquals(2,group(ps).size);assertNull(CrossPostFusion.compose(ps))
    }
    @Test fun emptyMalformedLocalOrCredentialBearingAddressesCannotSupplyPictureEvidence() {
        for(url in listOf("","not an image","file:///image.jpg","https://reader:secret@images.example/photo.jpg")) {
            val ps=posts().take(2).map {it.copy(media=listOf(Media(url)))}
            assertNull(url,DecoratedPostCopies.compare(projection(ps[0]),projection(ps[1])))
            assertNull(url,CrossPostFusion.compose(ps));assertEquals(url,2,group(ps).size)
        }
    }
}
