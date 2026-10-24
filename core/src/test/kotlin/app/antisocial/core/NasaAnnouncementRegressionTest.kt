package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/** Public captions captured from the preview; ephemeral CDN URLs are replaced by distinct fixtures. */
class NasaAnnouncementRegressionTest {
    private fun posts()=Gson().fromJson(javaClass.getResourceAsStream("/nasa-not-slowing-posts.json")!!.reader(),Array<Post>::class.java).toList()
    private fun follows()=listOf(
        Target(Platform.X,TargetKind.PROFILE,"NASA","https://x.com/NASA"),
        Target(Platform.INSTAGRAM,TargetKind.PROFILE,"nasa","https://www.instagram.com/nasa"),
        Target(Platform.FACEBOOK,TargetKind.PROFILE,"NASA","https://www.facebook.com/NASA")
    ).map {Follow("nasa:${it.platform.id}",it,"NASA",personId="nasa-fixture",personName="NASA")}
    @Test fun theArtemisAnnouncementCollectsAllThreePlatformEditions() {
        val posts=posts();val follows=follows()
        val memberships=posts.associate {p->CrossPosts.originalKey(p) to setOf(follows.single {it.target.platform==p.platform}.id)}
        val groups=CrossPosts.group(posts,follows,memberships)
        assertEquals("Platform hashtag, video-label and short-link variations must not repeat this announcement",1,groups.size)
        assertEquals(posts.map {it.canonicalUrl}.toSet(),groups.single().posts.map {it.canonicalUrl}.toSet())
        val fusion=CrossPostFusion.compose(groups.single().posts)
        assertNotNull("The shared caption must remain a usable collected post",fusion)
        assertEquals(1,fusion!!.segments.count {it.text.contains("We are not slowing down")})
        assertEquals(4,groups.single().posts.flatMap(PostLinks::urls).filter {it.startsWith("https://go.nasa.gov/")}.distinct().size)
    }
    @Test fun changedFactsAndConcreteDestinationsStillRemainDistinct() {
        val posts=posts();val x=posts.single {it.platform==Platform.X};val facebook=posts.single {it.platform==Platform.FACEBOOK};val follows=follows()
        fun groups(other:Post):List<PostGroup> {
            val members=listOf(x,other).associate {p->CrossPosts.originalKey(p) to setOf(follows.single {it.target.platform==p.platform}.id)}
            return CrossPosts.group(listOf(x,other),follows,members)
        }
        for(other in listOf(
            facebook.copy(body=facebook.body.replace("Artemis IV","Artemis V")),
            facebook.copy(body=facebook.body.replace("not slowing","slowing")),
            facebook.copy(body=facebook.body.replace("https://go.nasa.gov/4zV3TuG","https://different.example/report"),links=facebook.links.orEmpty()+"https://different.example/report"),
            facebook.copy(publishedAt=x.publishedAt!!+2*60*60_000L)
        ))assertEquals(other.body,2,groups(other).size)
    }
}
