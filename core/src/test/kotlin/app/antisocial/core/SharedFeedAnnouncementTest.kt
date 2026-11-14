/* anti social, GPL-3.0-or-later. Synthetic public-feed observations. */
package app.antisocial.core

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class SharedFeedAnnouncementTest {
    private val at=1_790_000_000_000L
    private val body="Under the community repair program, equipment is returning. Volunteers are returning. Practical skills are returning. The workshop is BACK. 🛠"
    private val bridge="https://bridge.fixture.invalid"
    private fun instagram(id:String,name:String,person:String)=Follow(id,Target(Platform.INSTAGRAM,TargetKind.PROFILE,name,"https://www.instagram.com/$name/"),name,personId=person)
    private fun facebook(id:String,name:String,person:String)=Follow(id,Target(Platform.FACEBOOK,TargetKind.PROFILE,name,"https://www.facebook.com/$name"),name,personId=person)
    private val local=instagram("local-instagram","communityworkshop","workshop")
    private val partner=instagram("partner-instagram","repairguild","guild")
    private val other=facebook("local-facebook","communityworkshop","workshop")
    private val follows=listOf(local,partner,other)
    private val ig=Post("post:SharedRepair91",Platform.INSTAGRAM,"https://www.instagram.com/p/SharedRepair91/","repairguild",body,"▶ $body",at,
        media=listOf(Media("https://images.fixture.invalid/repair-preview.jpg")),
        provenance=Provenance(Source(Platform.INSTAGRAM,"rss-bridge",bridge),at+90_000,"$bridge/?action=display&format=Atom&bridge=Instagram&context=Username&u=repairguild&media_type=all"))
    private val fb=Post("post:991122",Platform.FACEBOOK,"https://www.facebook.com/communityworkshop/posts/991122","Community workshop",body,publishedAt=at+30_000,
        media=listOf(Media("https://videos.fixture.invalid/repair-recording.mp4","video")))
    private fun members(posts:List<Post> = listOf(ig,fb))=posts.associate {CrossPosts.originalKey(it) to if(it.platform==Platform.INSTAGRAM)setOf(local.id,partner.id)else setOf(other.id)}
    private fun grouped(posts:List<Post> = listOf(ig,fb),accounts:List<Follow> = follows,membership:Map<String,Set<String>> = members(posts))=CrossPosts.group(posts,accounts,membership)

    @Test fun aCompleteAnnouncementSuppliedByDistinctLinkedFeedsReadsOnceWithoutChoosingAnOwner() {
        val posts=listOf(ig,fb);val originals=posts.map {Gson().toJson(it)};val links=follows.map {Gson().toJson(it)}
        for(input in listOf(posts,posts.reversed()))for(accounts in listOf(follows,follows.reversed())) {
            val groups=grouped(input,accounts);assertEquals(1,groups.size)
            val group=groups.single();assertEquals(posts.toSet(),group.posts.toSet())
            val fusion=assertNotNull(CrossPostFusion.compose(group.posts));assertEquals(body,fusion.segments.single().text)
            assertEquals(2,group.posts.flatMap {it.media}.size)
        }
        assertNull(CrossPosts.originalAccount(ig));assertNull(ig.authorAccount)
        assertEquals(originals,posts.map {Gson().toJson(it)});assertEquals(links,follows.map {Gson().toJson(it)})
    }
    @Test fun aDeclaredActualAuthorRemainsDistinctFromTheOtherDistributingPublisher() {
        val actual=ig.copy(authorAccount=PostAuthorAccounts.instagram("repairguild","77"))
        val groups=grouped(listOf(actual,fb));assertEquals(1,groups.size)
        val group=groups.single()
        assertEquals("instagram:repairguild",CrossPosts.originalAccount(group.posts.single {it.platform==Platform.INSTAGRAM}))
        assertEquals(actual.authorAccount,group.posts.single {it.platform==Platform.INSTAGRAM}.authorAccount)
        val unrelated=actual.copy(authorAccount=PostAuthorAccounts.instagram("independentauthor","88"))
        assertEquals(2,grouped(listOf(unrelated,fb)).size)
    }
    @Test fun addingTheJointFeedEditionCannotStrandAnExistingMentionMarkedSibling() {
        val xFollow=Follow("local-x",Target(Platform.X,TargetKind.PROFILE,"communityworkshop","https://x.com/communityworkshop"),"Workshop",personId="workshop")
        val text=body.replace("workshop is","@workshop is")
        val x=Post("post:991120",Platform.X,"https://x.com/communityworkshop/status/991120","@communityworkshop","$text Video","RT by @repairguild: $text",at-120_000,
            media=listOf(Media("https://images.fixture.invalid/recording-poster.jpg")),links=listOf("https://nitter.fixture.invalid/workshop"),
            provenance=Provenance(Source(Platform.X,"nitter","https://nitter.fixture.invalid"),at+90_000,"https://nitter.fixture.invalid/repairguild/rss"))
        val accounts=follows+xFollow;val posts=listOf(ig,fb,x);val membership=members()+mapOf(CrossPosts.originalKey(x)to setOf(xFollow.id))
        assertEquals(1,grouped(listOf(x,fb),accounts,membership).size)
        for(input in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val groups=grouped(input,accounts,membership);assertEquals(1,groups.size)
            val roots=groups.single().posts;assertEquals(posts.toSet(),roots.toSet())
            val fusion=assertNotNull(CrossPostFusion.compose(roots));val weave=assertNotNull(fusion.weave)
            assertEquals(1,Regex(Regex.escape("Under the community repair program")).findAll(fusion.segments.single().text).count())
            assertEquals(setOf("workshop","@workshop"),weave.single {it.variants.any {v->v.text=="@workshop"}}.variants.map {it.text}.toSet())
            for(post in roots) {
                val literal=weave.joinToString(" "){run->run.variants.single {CrossPosts.originalKey(post)in it.originalKeys}.text}
                assertEquals(TextWhitespace.collapse(PostPresentation.content(post)),literal)
            }
            assertEquals(3,roots.flatMap {it.media}.size)
        }
    }
    @Test fun identicalEditorialWordingStaysInTheCommonCaptionWhileAddedFramingStaysSeparate() {
        val caption="Misleading. $body"
        val group=grouped(listOf(ig.copy(body=caption,title=""),fb.copy(body=caption))).single()
        assertEquals(caption,assertNotNull(CrossPostFusion.compose(group.posts)).segments.single().text)
        assertEquals(2,grouped(listOf(ig.copy(body=caption,title=""),fb)).size)
    }
    @Test fun ambiguousAliasesMissingMembershipAndUnqualifiedFeedsDoNotSupplyThisEvidence() {
        val key=CrossPosts.originalKey(ig)
        assertEquals(2,grouped(membership=mapOf(key to setOf(partner.id))).size)
        assertEquals(2,grouped(membership=emptyMap()).size)
        val alias=local.copy(target=partner.target)
        assertEquals(2,grouped(accounts=listOf(alias,partner,other)).size)
        assertEquals(2,grouped(accounts=listOf(local.copy(enabled=false),partner,other)).size)
        assertEquals(2,grouped(accounts=listOf(local.copy(target=local.target.copy(kind=TargetKind.TAG)),partner,other)).size)
        assertEquals(2,grouped(accounts=listOf(local.copy(target=local.target.copy(canonicalUrl="https://spoof.fixture.invalid/communityworkshop")),partner,other)).size)
        val source=requireNotNull(ig.provenance)
        for(provenance in listOf(null,source.copy(source=source.source.copy(platform=Platform.X)),
            source.copy(source=source.source.copy(frontend="unqualified")),source.copy(source=source.source.copy(role=SourceRole.CACHE)),
            source.copy(finalUrl="$bridge/?bridge=Instagram&context=Username&u=unrelated"),
            source.copy(finalUrl="$bridge/?bridge=Instagram&context=Username&u=repairguild&u=communityworkshop"),
            source.copy(finalUrl="$bridge/?bridge=Instagram&context=Media&u=repairguild"),
            source.copy(finalUrl="$bridge/other/?bridge=Instagram&context=Username&u=repairguild"),
            source.copy(finalUrl="https://different.fixture.invalid/?bridge=Instagram&context=Username&u=repairguild")))
            assertEquals(2,grouped(listOf(ig.copy(provenance=provenance),fb)).size)
    }
    @Test fun aDifferentClaimDateDestinationOrIncompleteEditionStaysSeparate() {
        val variations=listOf(
            ig.copy(body=body.replace("is BACK","is not BACK"),title=""),
            ig.copy(body=body+" 19",title=""),ig.copy(body=body.replace("🛠","🇨🇦"),title=""),
            ig.copy(body=body.replace("returning.","returning?"),title=""),
            ig.copy(publishedAt=at+900_000),ig.copy(publishedAt=null),ig.copy(preview=true),ig.copy(parentId="different-root"),
            ig.copy(recoveredArchive=ArchiveRecovery("fixture",at,"https://archive.fixture.invalid/snapshot")),
            ig.copy(media=emptyList()),ig.copy(body="Workshop opens today",title=""),
            ig.copy(body=body.replace("program","campaign"),title="")
        )
        for((index,value)in variations.withIndex())assertEquals(2,grouped(listOf(value,fb)).size,"Changed edition $index")
        assertEquals(2,grouped(listOf(ig.copy(links=listOf("https://first.fixture.invalid/report")),fb.copy(links=listOf("https://other.fixture.invalid/report")))).size)
    }
    @Test fun oneJointlySuppliedPostCannotChooseBetweenCompetingPlatformOriginals() {
        val another=facebook("guild-facebook","repairguild","guild")
        val second=fb.copy(id="post:991123",canonicalUrl="https://www.facebook.com/repairguild/posts/991123",publishedAt=at+60_000,
            media=listOf(Media("https://videos.fixture.invalid/guild-recording.mp4","video")))
        val posts=listOf(ig,fb,second);val membership=members()+mapOf(CrossPosts.originalKey(second)to setOf(another.id))
        val groups=grouped(posts,follows+another,membership)
        assertEquals(3,groups.size);assertEquals(posts.toSet(),groups.flatMap {it.posts}.toSet())
    }
    @Test fun mentionMarkPresentationCannotHideChangedNamesClaimsOrPunctuation() {
        val marked=fb.copy(platform=Platform.X,canonicalUrl="https://x.com/communityworkshop/status/991122",body=body.replace("workshop is","@workshop is"))
        assertNotNull(AnnouncementEditions.composeMentionMarks(listOf(ig,marked)))
        for(text in listOf(marked.body.replace("@workshop","@differentworkshop"),marked.body.replace("@workshop","@2019"),
            marked.body.replace("@workshop","writer@workshop"),marked.body.replace("BACK.","BACK?"),
            marked.body.replace("BACK.","not BACK."),marked.body+" 19",marked.body.replace("Volunteers","Directors")))
            assertNull(AnnouncementEditions.composeMentionMarks(listOf(ig,marked.copy(body=text))),text)
        assertNull(AnnouncementEditions.composeMentionMarks(listOf(ig.copy(platform=Platform.X),marked)))
        assertNull(AnnouncementEditions.composeMentionMarks(listOf(ig.copy(body="Workshop returns today",title=""),marked.copy(body="@Workshop returns today"))))
    }
    @Test fun sourcePreparationRechecksChangedMembershipAndDoesNotCreatePersistentIdentity()=runBlocking {
        val posts=listOf(ig,fb);val projection=CrossPosts.sourceProjection(follows,members())
        assertEquals(1,projection.group(posts).size);assertEquals(1,projection.group(posts).size)
        val snapshot=assertNotNull(projection.snapshotSourceAnalysis(SourcePreparationBudget.EXPANDED))
        val changed=CrossPosts.sourceProjection(follows,mapOf(CrossPosts.originalKey(ig)to setOf(partner.id)))
        changed.restoreSourceAnalysis(snapshot,posts)
        assertEquals(2,changed.group(posts).size);assertNull(ig.authorAccount)
    }
}
