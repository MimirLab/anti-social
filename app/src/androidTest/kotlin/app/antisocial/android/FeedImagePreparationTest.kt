/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import org.junit.Test
import org.junit.Assert.*

class FeedImagePreparationTest {
    private val at=1_800_000_000_000L
    private fun post(id:String,platform:Platform=Platform.X,count:Int=1,author:String="studio",offset:Long=0,parent:String?=null)=
        Post(id,platform,"https://${if(platform==Platform.X)"x.com"else "www.facebook.com"}/studio/${if(platform==Platform.X)"status"else "posts"}/$id",author,"A public workshop.",publishedAt=at+offset,parentId=parent,
            media=(0 until count).map {Media("https://media.example.org/$id-$it.png")})
    private fun follow(platform:Platform,person:String="studio")=Follow(platform.id,Target(platform,TargetKind.PROFILE,"studio","https://example.org/${platform.id}"),"Studio",personId=person)
    @Test fun readingAnchorItsParentAndLargerLinkedGalleriesGetTheLocalHeadStart() {
        val child=post("71000002",count=2,parent="71000001")
        val root=post("71000001",count=4)
        val peer=post("72000001",Platform.FACEBOOK,count=6)
        val other=post("72000002",Platform.FACEBOOK,count=1,offset=60_000)
        val posts=listOf(other,peer,root,child)
        val accounts=listOf(follow(Platform.X),follow(Platform.FACEBOOK))
        val members=posts.associate {it.identityKey to setOf(it.platform.id)}
        assertEquals((child.media+root.media+peer.media+other.media).map {it.url},feedImagePreparation(posts,posts,accounts,members,child.identityKey))
    }
    @Test fun sharedNamesAndUnrelatedPublishersNeverExpandTheSchedule() {
        val anchor=post("71000001");val namesake=post("72000001",Platform.FACEBOOK,count=5)
        val unrelated=post("71000002",author="anotherstudio")
        val old=post("71000003",offset=-CrossPosts.MAX_REPEAT_WINDOW-1)
        val undated=post("71000004").copy(publishedAt=null)
        val accounts=listOf(follow(Platform.X),follow(Platform.FACEBOOK,"unrelated"))
        val posts=listOf(anchor,namesake,unrelated,old,undated)
        val members=posts.associate {it.identityKey to if(it==unrelated)emptySet()else setOf(it.platform.id)}
        assertEquals(anchor.media.map {it.url},feedImagePreparation(posts,posts,accounts,members,anchor.identityKey))
    }
    @Test fun scopeFallbackBoundsAndVideoPreviewOnlyAreDeterministic() {
        val anchor=post("71000001").copy(media=listOf(Media("https://media.example.org/recording.mp4","video",preview="https://media.example.org/cover.png")))
        val many=(2..50).map {post("${71000000+it}",count=6)}
        val first=feedImagePreparation(many+anchor,listOf(anchor),emptyList(),emptyMap(),"missing")
        assertEquals(24,first.size);assertEquals(anchor.media.single().preview,first.first())
        assertFalse(first.contains(anchor.media.single().url))
        assertEquals(first,feedImagePreparation((many+anchor).reversed(),listOf(anchor),emptyList(),emptyMap(),"missing"))
        assertTrue(feedImagePreparation(many,emptyList(),emptyList(),emptyMap(),null).isEmpty())
    }
    @Test fun localPreparationDoesNotAdmitTheScheduledCandidatesAsMatches() {
        val anchor=post("71000001");val peer=post("72000001",Platform.FACEBOOK).copy(body="A separate announcement about new opening hours.")
        val follows=listOf(follow(Platform.X),follow(Platform.FACEBOOK))
        val members=mapOf(anchor.identityKey to setOf(Platform.X.id),peer.identityKey to setOf(Platform.FACEBOOK.id))
        assertEquals(2,feedImagePreparation(listOf(anchor,peer),listOf(anchor),follows,members,anchor.identityKey).size)
        assertEquals(2,CrossPosts.group(listOf(anchor,peer),follows,members).size)
    }
}
