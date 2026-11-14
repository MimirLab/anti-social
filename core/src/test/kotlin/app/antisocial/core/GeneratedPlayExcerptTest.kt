/* anti social, GPL-3.0-or-later. Synthetic generated-heading/body-echo controls. */
package app.antisocial.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class GeneratedPlayExcerptTest {
    private val at=1_800_000_000_000L
    private val prose="The community workshop opens this afternoon with repaired equipment, illustrated instructions, public demonstrations, and volunteers ready to answer questions about the practical repair sessions."
    private val source=Source(Platform.INSTAGRAM,"rss-bridge","https://reader.fixture.invalid/bridge",role=SourceRole.ALTERNATE)
    private val provenance=Provenance(source,at,"${source.root}?action=display&bridge=Instagram&context=Username&u=workshop&format=Atom&media_type=all")
    private fun instagram(flag:String="🇦🇺",ending:String="...")=Post("FlagWorkshop1",Platform.INSTAGRAM,"https://www.instagram.com/p/FlagWorkshop1/","Workshop","$flag $prose",
        title="▶ $flag$ending",publishedAt=at,provenance=provenance,authorAccount=PostAuthorAccount("https://www.instagram.com/workshop/"),media=listOf(Media("https://picture.fixture.invalid/preview.jpg")))
    private fun x(body:String)=Post("920001",Platform.X,"https://x.com/workshop/status/920001","Workshop",body,publishedAt=at,
        media=listOf(Media("https://video.fixture.invalid/recording.mp4",type="video")),authorAccount=PostAuthorAccount("https://x.com/workshop"))
    private val follows=listOf(Platform.X,Platform.INSTAGRAM).map {p->Follow(p.id,Target(p,TargetKind.PROFILE,"workshop",if(p==Platform.X)"https://x.com/workshop"else"https://www.instagram.com/workshop/"),"Workshop",personId="flag-workshop")}
    @Test fun aSourceBoundGeneratedFlagExcerptDoesNotRepeatTheAuthoredFlag() {
        for(flag in listOf("🇦🇺","🇨🇦","🇸🇪","🇬🇧","🇦🇺\uFE0F"))for(ending in listOf("...","…")) {
            val post=instagram(flag,ending);val before=Gson().toJson(post)
            assertEquals("",PostPresentation.text(post).title)
            assertEquals(post.body,PostPresentation.content(post));assertEquals(before,Gson().toJson(post))
            assertEquals(post.body,PostPresentation.content(post.copy(title=post.title.replace("▶","▶\uFE0F"))))
        }
    }
    @Test fun otherShortLiteralExcerptsUseTheSameBodyProofWithoutAWordOrEmojiDictionary() {
        for(prefix in listOf("Workshop","12 repairs","🔥","🇦🇺 🇨🇦","🛠️ Updates")) {
            val post=instagram().copy(title="▶ $prefix...",body="$prefix $prose")
            assertEquals("",PostPresentation.text(post).title);assertEquals(post.body,PostPresentation.content(post))
        }
        for(prefix in listOf("@workshop","#repairs","example.com","https://a.test")) {
            val post=instagram().copy(title="▶ $prefix...",body="$prefix $prose")
            assertEquals(post.title,PostPresentation.text(post).title)
        }
    }
    @Test fun theCompleteCaptionCollectsBeforeAnyImageOrVideoObservations() {
        val second=instagram();val posts=listOf(x(second.body),second);val before=Gson().toJson(posts)
        for(input in listOf(posts,posts.reversed())) {
            val group=CrossPosts.group(input,follows).single();assertEquals(posts.toSet(),group.posts.toSet())
            val fusion=requireNotNull(CrossPostFusion.compose(group.posts))
            assertEquals(1,Regex(Regex.escape(second.body)).findAll(fusion.segments.joinToString(" "){it.text}).count())
            assertEquals(posts.flatMap {it.media}.toSet(),group.posts.flatMap {it.media}.toSet())
            assertTrue(group.posts.flatMap {it.media}.all {it.visualFingerprint==null&&it.attachmentFingerprint==null&&it.videoFootage==null})
        }
        assertEquals(before,Gson().toJson(posts))
    }
    @Test fun titleCleanupDoesNotEstablishAccountControlOrEraseChangedClaims() {
        val second=instagram();val first=x(second.body)
        // Independent-copy admission can already collect distinctive complete
        // captions. Cleanup retains that policy and both account identities;
        // it supplies no ownership binding.
        val clean=listOf(first,second.copy(title=""))
        assertEquals(CrossPosts.group(clean,emptyList()).map {it.posts.map(CrossPosts::originalKey)},CrossPosts.group(listOf(first,second),emptyList()).map {it.posts.map(CrossPosts::originalKey)})
        assertNotEquals(CrossPosts.originalAccount(first),CrossPosts.originalAccount(second))
        for(post in listOf(second.copy(body=second.body.replace("opens","does not open")),instagram("🇨🇦")))
            assertEquals(2,CrossPosts.group(listOf(first,post),follows).size)
        assertEquals(2,CrossPosts.group(listOf(first.copy(body=first.body+" We completed 21 repairs."),second.copy(body=second.body+" We completed 22 repairs.")),follows).size)
    }
    @Test fun ordinaryHeadingsOtherSymbolsAndIncompleteFlagExcerptsRemain() {
        val post=instagram()
        for(title in listOf("🇦🇺...","▶ 🇨🇦...","▶ 🇦...","▶ 🇦🇺🇨🇦...","▶ ⭐...","▶ 12...","▶ 🇦🇺","▶ 🇦🇺\u202e...")) {
            val other=post.copy(title=title);assertEquals(title,PostPresentation.text(other).title);assertEquals(other.body,PostPresentation.text(other).body)
        }
        for(body in listOf("🇦🇺 Hello.","🇦🇺$prose","$prose 🇦🇺","🇦🇺 $prose\u202e"))assertEquals(post.title,PostPresentation.text(post.copy(body=body)).title)
    }
    @Test fun theBridgeRoleOriginAndDeclaredOperationMustAgree() {
        val post=instagram()
        val wrong=listOf<Provenance?>(null,provenance.copy(source=source.copy(frontend="rsshub")),provenance.copy(source=source.copy(platform=Platform.X)),
            provenance.copy(source=source.copy(role=SourceRole.ORIGINAL)),provenance.copy(finalUrl=provenance.finalUrl.replace("https:","http:")),
            provenance.copy(finalUrl=provenance.finalUrl.replace("reader.fixture.invalid","elsewhere.fixture.invalid")),provenance.copy(finalUrl=provenance.finalUrl.replace("https://","https://name:secret@")),
            provenance.copy(finalUrl=provenance.finalUrl.replace("bridge=Instagram","bridge=Other")),provenance.copy(finalUrl=provenance.finalUrl.replace("context=Username","context=Other")),
            provenance.copy(finalUrl=provenance.finalUrl+"&bridge=Other"),provenance.copy(finalUrl=provenance.finalUrl.replace("action=display","action=other")),provenance.copy(finalUrl=provenance.finalUrl+"#fragment"))
        wrong.forEach {assertEquals(post.title,PostPresentation.text(post.copy(provenance=it)).title)}
    }
    @Test fun everyAuthoredFlagRemainsIncludingMultipleFlagsInTheBody() {
        val post=instagram().copy(body="🇦🇺 🇨🇦 $prose")
        assertEquals("",PostPresentation.text(post).title);assertEquals(post.body,PostPresentation.content(post))
        assertEquals(4,PostPresentation.content(post).codePoints().filter {it in 0x1f1e6..0x1f1ff}.count().toInt())
    }
    @Test fun commentsOtherPlatformsAndUnknownOriginalDestinationsKeepTheirHeadings() {
        val post=instagram()
        for(other in listOf(post.copy(parentId="other"),post.copy(preview=true),post.copy(platform=Platform.FACEBOOK),post.copy(canonicalUrl="https://elsewhere.fixture.invalid/p/FlagWorkshop1/"),
            post.copy(canonicalUrl="https://www.instagram.com/p/FlagWorkshop1/c/1234/"),post.copy(canonicalUrl="http://www.instagram.com/p/FlagWorkshop1/")))
            assertEquals(other.title,PostPresentation.text(other).title)
    }
}
