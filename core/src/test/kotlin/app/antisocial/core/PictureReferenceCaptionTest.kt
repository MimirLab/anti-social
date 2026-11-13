/* anti social, GPL-3.0-or-later. Synthetic publishers and picture observations. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Test
import org.jsoup.parser.Parser
import kotlin.test.*

class PictureReferenceCaptionTest {
    private val at=1_790_000_000_000L
    private val prefix="From repairing damaged tools, to sharing practical skills, to welcoming new volunteers, the workshop continues to bring neighbors together and preserve useful equipment across our community,"
    private val names="Workshop Director Mira Vale and Rowan Beck's"
    private val handles="@WorkshopLead and @RepairNotes"
    private val suffix="efforts are delivering results."
    private val article="https://workshop.fixture.invalid/reports/2026/11/repair-notes"
    private val picture=requireNotNull(VisualFrames.encode(.8,48.0,0x12345678,0x76543210,List(48){it*17%256},ByteArray(4096){((it*37+it/64)%256).toByte()}))
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM)
    private fun source(platform:Platform)=Source(platform,if(platform==Platform.X)"nitter"else if(platform==Platform.INSTAGRAM)"rss-bridge"else"original",if(platform==Platform.X)"https://nitter.fixture.invalid"else"https://source.fixture.invalid")
    private val posts=platforms.mapIndexed {i,p->Post("991$i",p,when(p) {
        Platform.X->"https://x.com/sharedworkshop/status/9910"
        Platform.FACEBOOK->"https://www.facebook.com/sharedworkshop/posts/9911"
        else->"https://www.instagram.com/p/WorkshopReference9912/"
    },"sharedworkshop",when(p) {
        Platform.X->"$prefix $handles $suffix"
        Platform.FACEBOOK->"$prefix $names $suffix\n\n$article"
        else->"$prefix ${names.replace("'","&#039;")} $suffix"
    },publishedAt=at+i*60_000L,media=listOf(Media("https://picture.fixture.invalid/${p.id}.jpg",visualFingerprint=picture)),
        provenance=Provenance(source(p),at+180_000,"https://source.fixture.invalid/feed"),links=when(p) {
            Platform.X->listOf("https://nitter.fixture.invalid/WorkshopLead","https://nitter.fixture.invalid/RepairNotes")
            Platform.FACEBOOK->listOf(article)
            else->listOf("https://www.instagram.com/p/WorkshopReference9912/","https://www.instagram.com/explore/tags/039")
        })}
    private val follows=platforms.map {p->Follow(p.id,Target(p,TargetKind.PROFILE,"sharedworkshop",when(p) {
        Platform.X->"https://x.com/sharedworkshop"
        Platform.FACEBOOK->"https://www.facebook.com/sharedworkshop"
        else->"https://www.instagram.com/sharedworkshop/"
    }),"Shared workshop",personId="shared-workshop-publisher")}
    private fun members(values:List<Post>)=values.associate {CrossPosts.originalKey(it)to setOf(it.platform.id)}
    private fun groups(values:List<Post>,linked:Boolean=true)=CrossPosts.group(values,if(linked)follows else emptyList(),if(linked)members(values)else emptyMap())
    private fun projection(values:List<Post>,fusion:PostFusion) {
        val weave=assertNotNull(fusion.weave)
        fun normalized(text:String)=TextWhitespace.collapse(text).trim()
        for(post in values) {
            val projected=weave.joinToString(" "){run->run.variants.single {CrossPosts.originalKey(post)in it.originalKeys}.text}
            var literal=PostPresentation.content(post);repeat(2){literal=Parser.unescapeEntities(literal,false)}
            assertEquals(normalized(literal),normalized(projected))
        }
        assertEquals(1,Regex(Regex.escape(prefix)).findAll(fusion.segments.single().text).count())
        val reference=weave.single {run->run.variants.any {handles==it.text}}
        assertEquals(setOf(names,handles),reference.variants.map {it.text}.toSet())
        assertEquals(setOf(Platform.FACEBOOK,Platform.INSTAGRAM),reference.variants.single {it.text==names}.platforms)
    }
    @Test fun linkedPictureEditionsShareProseAndKeepTheirReferenceNamesCoupled() {
        val before=posts.map {Gson().toJson(it)}
        for(values in listOf(posts,posts.reversed(),posts.drop(1)+posts.first())) {
            val rows=groups(values);assertEquals(1,rows.size);assertEquals(posts.toSet(),rows.single().posts.toSet())
        }
        assertEquals(before,posts.map {Gson().toJson(it)})
    }
    @Test fun theCollectedReaderKeepsOneCommonPassageAndEveryLiteralSourcePath() {
        for(values in listOf(posts,posts.reversed()))projection(values,assertNotNull(CrossPostFusion.composeCollectedEditions(values)))
    }
    @Test fun ordinaryAndMediaConsumersUseTheSameCoupledReferenceComposition() {
        projection(posts,assertNotNull(CrossPostFusion.compose(posts)))
        projection(posts,assertNotNull(CrossPostFusion.composeMedia(posts)))
    }
    @Test fun portableContentProofKeepsSourcesWithoutInferringAccountControl() {
        assertTrue(CrossPosts.sameContentCopies(posts))
        assertEquals(2,groups(posts.take(2),linked=false).size)
    }
    @Test fun onlySourceQualifiedMentionNavigationCanBeExcludedFromResourceConflicts() {
        val post=posts.first();val url="https://nitter.fixture.invalid/WorkshopLead"
        assertTrue(CrossPosts.nitterMentionLink(post,url))
        for(other in listOf("https://different.fixture.invalid/WorkshopLead",url+"?action=follow",url+"#reference","https://nitter.fixture.invalid/Unmentioned"))
            assertFalse(CrossPosts.nitterMentionLink(post,other))
        assertFalse(CrossPosts.nitterMentionLink(post.copy(provenance=null),url))
        assertFalse(CrossPosts.nitterMentionLink(post.copy(body=post.body+" "+url),url))
    }
    @Test fun missingCoarseChangedOrUnqualifiedPicturesDoNotEstablishThisRelation() {
        val coarse=picture.substringBeforeLast('|').replaceFirst("f2|","f1|")
        val changed=requireNotNull(VisualFrames.encode(.8,48.0,0x12345678,0x76543210,List(48){it*17%256},ByteArray(4096){0}))
        val alternatives=listOf(
            posts.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=null)})},
            posts.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=coarse)})},
            posts.map {p->p.copy(media=p.media.map {it.copy(visualFingerprint=null,attachmentFingerprint=picture)})},
            posts.mapIndexed {i,p->if(i==0)p.copy(media=p.media.map {it.copy(visualFingerprint=changed)})else p},
            posts.map {p->p.copy(media=p.media.map {it.copy(type="video",preview=it.url)})},
            posts.map {p->p.copy(media=p.media+p.media.single().copy(url=p.media.single().url+"?other"))},
            posts.map {p->p.copy(avatar=p.media.single().url)}
        )
        alternatives.forEachIndexed {i,values->assertFalse(CrossPosts.pictureReferenceCollection(values),"Unqualified picture case $i")}
        assertTrue(CrossPosts.pictureReferenceCollection(posts))
    }
    @Test fun aChangedClaimDateResourceOrUnqualifiedProxyLinkStaysOutsideTheRoute() {
        fun changed(transform:(Post)->Post)=posts.mapIndexed {i,p->if(i==0)transform(p)else p}
        val alternatives=listOf(
            changed {it.copy(publishedAt=null)},changed {it.copy(publishedAt=at+900_000)},
            changed {it.copy(preview=true)},changed {it.copy(parentId="other-original")},
            changed {it.copy(recoveredArchive=ArchiveRecovery("fixture",at,"https://archive.fixture.invalid/capture"))},
            changed {it.copy(body=it.body.replace("results.","no results."))},
            changed {it.copy(body=it.body.replace("results.","results 17."))},
            changed {it.copy(body=it.body+" 🇨🇦")},changed {it.copy(body=it.body.replace("results.","results?"))},
            changed {it.copy(body=it.body+"\nhttps://different.fixture.invalid/report",links=it.links.orEmpty()+"https://different.fixture.invalid/report")},
            changed {it.copy(provenance=null)},
            changed {it.copy(links=it.links.orEmpty()+"https://nitter.fixture.invalid/Unmentioned")},
            changed {it.copy(body=it.body+"\nhttps://nitter.fixture.invalid/WorkshopLead")}
        )
        alternatives.forEachIndexed {i,values->assertFalse(CrossPosts.pictureReferenceCollection(values),"Changed evidence case $i")}
    }
    @Test fun unsupportedTextAndAmbiguousPlatformVersionsCannotBecomeReferenceAlternatives() {
        for(text in listOf("we ignored safety rules","Former Director Mira Vale","Director Mira Vale rejected Rowan Beck","@WorkshopLead approved @RepairNotes")) {
            val values=posts.mapIndexed {i,p->if(i==0)p.copy(body=p.body.replace(handles,text))else p}
            assertFalse(CrossPosts.pictureReferenceCollection(values),text)
        }
        assertFalse(CrossPosts.pictureReferenceCollection(posts.map {it.copy(body=it.body.substringAfter("community, "))}))
        val another=posts[1].copy(id="other-facebook",canonicalUrl="https://www.facebook.com/sharedworkshop/posts/9913",body=posts[1].body.replace(names,"Director Aster Dawn and Mira Vale"))
        assertFalse(CrossPosts.pictureReferenceCollection(posts+another))
    }
}
