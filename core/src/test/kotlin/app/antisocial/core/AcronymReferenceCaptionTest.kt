/* anti social, GPL-3.0-or-later. Unrelated synthetic announcement editions. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import kotlin.test.*

class AcronymReferenceCaptionTest {
    private val at=1_790_000_000_000L
    private val passage="opened the community repair workshop for registered neighborhood groups after the quarterly equipment inspection. MCA coordinators have published the room roster and safety checklist. Visitors can inspect each tool, read the volunteer handbook, review the maintenance record, and discuss accessible workstations with the staff before choosing a project."
    private val posts=listOf(
        Post("882001",Platform.FACEBOOK,"https://www.facebook.com/asworkshopcase/posts/882001","Workshop office",
            "The Metro Civic Authority $passage",publishedAt=at),
        Post("882002",Platform.X,"https://x.com/asworkshopcase/status/882002","Workshop office",
            "The @MCAnotices $passage",publishedAt=at+24*60_000L,
            provenance=Provenance(Source(Platform.X,"nitter","https://nitter.fixture.invalid"),at+25*60_000L,"https://nitter.fixture.invalid/asworkshopcase/rss"),
            links=listOf("https://nitter.fixture.invalid/MCAnotices"))
    )
    private val follows=listOf(Platform.FACEBOOK,Platform.X,Platform.INSTAGRAM).map {platform->Follow(platform.id,Target(platform,TargetKind.PROFILE,"asworkshopcase",
        when(platform) {Platform.X->"https://x.com/asworkshopcase";Platform.FACEBOOK->"https://www.facebook.com/asworkshopcase";else->"https://www.instagram.com/asworkshopcase/"}),"Workshop office",personId="synthetic-workshop-office")}
    private fun groups(values:List<Post>)=CrossPosts.group(values,follows,values.associate {CrossPosts.originalKey(it)to setOf(it.platform.id)})

    @Test fun aLinkedInstitutionAndHandleReadAsOneCompleteAnnouncementWithoutMedia() {
        val before=posts.map(Gson()::toJson)
        for(values in listOf(posts,posts.reversed())) {
            val group=groups(values).single()
            assertEquals(posts.toSet(),group.posts.toSet())
            assertTrue(group.posts.all {it.media.isEmpty()})
        }
        assertEquals(before,posts.map(Gson()::toJson))
    }

    @Test fun portableCompositionReadsTheCommonPassageOnceAndKeepsBothReferenceSpellings() {
        val fusion=assertNotNull(CrossPostFusion.composeCollectedEditions(posts))
        assertEquals(1,Regex(Regex.escape(passage)).findAll(fusion.segments.joinToString(" "){it.text}).count())
        val weave=assertNotNull(fusion.weave)
        val reference=weave.single {run->run.variants.any {it.text=="@MCAnotices"}}
        assertEquals(setOf("Metro Civic Authority","@MCAnotices"),reference.variants.map {it.text}.toSet())
        for(post in posts) {
            val path=weave.joinToString(" "){run->run.variants.single {CrossPosts.originalKey(post)in it.originalKeys}.text}
            assertEquals(TextWhitespace.collapse(PostPresentation.content(post)).trim(),TextWhitespace.collapse(path).trim())
        }
    }

    @Test fun sharedInitialsAloneAndAnUnrelatedHandleDoNotEstablishAReferenceEdition() {
        val candidates=listOf(
            posts.map {it.copy(body=it.body.replace("MCA coordinators","Workshop coordinators"))},
            listOf(posts[0],posts[1].copy(body=posts[1].body.replace("@MCAnotices","@MTAnotices"),links=emptyList())),
            posts.map {it.copy(body=it.body.replace("Metro Civic Authority","Metro Authority").replace("@MCAnotices","@MAnotices").replace("MCA coordinators","MA coordinators"),links=emptyList())}
        )
        for(values in candidates) {
            assertNull(CrossPosts.acronymReferenceFusion(values))
            assertEquals(2,groups(values).size)
        }
    }

    @Test fun changedClaimsResourcesAndPublicationBoundariesRemainSeparate() {
        val other=posts[1]
        val candidates=listOf(
            other.copy(body=other.body.replace("have published","have not published")),
            other.copy(body=other.body.replace("each tool","12 tools")),
            other.copy(body=other.body.replace("a project.","a project?")),
            other.copy(links=other.links.orEmpty()+"https://different.fixture.invalid/changed-announcement"),
            other.copy(links=listOf("https://nitter.fixture.invalid/MCAnotices?action=follow")),
            other.copy(publishedAt=at+30*60_000L+1),other.copy(publishedAt=null),
            other.copy(preview=true),other.copy(parentId="other-root"),
            other.copy(recoveredArchive=ArchiveRecovery("fixture",at,"https://archive.fixture.invalid/capture")),
            other.copy(canonicalUrl="https://unrelated.fixture.invalid/not-a-platform-post")
        )
        for(post in candidates) {
            assertNull(CrossPosts.acronymReferenceFusion(listOf(posts[0],post)))
            assertEquals(2,groups(listOf(posts[0],post)).size)
        }
        assertEquals(2,CrossPosts.group(posts,emptyList()).size)
        assertEquals(2,groups(posts.map {it.copy(media=listOf(Media("https://photo.fixture.invalid/${it.platform.id}.jpg")))}).size)
    }

    @Test fun oneHandleCannotBridgeTwoDifferentExpandedInstitutions() {
        val other=posts[0].copy(id="882003",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/WorkshopAcronym883/",
            body=posts[0].body.replace("Metro Civic Authority","Moss Conservation Academy"),publishedAt=at+5*60_000L)
        val values=posts+other
        assertNull(CrossPosts.acronymReferenceFusion(values))
        val grouped=groups(values)
        assertTrue(grouped.all {it.posts.size<=2})
        assertEquals(values.toSet(),grouped.flatMap {it.posts}.toSet())
    }

    @Test fun identicalSpellingsDoNotMakeALegitimateMixedFamilyImpossibleToSave() {
        val identical=posts[0].copy(id="882004",platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/WorkshopAcronym884/",publishedAt=at+48*60_000L)
        val values=posts+identical
        assertEquals(values.toSet(),groups(values).single().posts.toSet())
        val fusion=assertNotNull(CrossPostFusion.composeCollectedEditions(values))
        assertEquals(1,Regex(Regex.escape(passage)).findAll(fusion.segments.joinToString(" "){it.text}).count())
        for(post in values) {
            val path=assertNotNull(fusion.weave).joinToString(" "){run->run.variants.single {CrossPosts.originalKey(post)in it.originalKeys}.text}
            assertEquals(TextWhitespace.collapse(PostPresentation.content(post)).trim(),TextWhitespace.collapse(path).trim())
        }
    }
}
