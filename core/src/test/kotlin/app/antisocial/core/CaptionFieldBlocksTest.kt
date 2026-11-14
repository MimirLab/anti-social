/* anti social, GPL-3.0-or-later. Synthetic, verbatim source-field paths. */
package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class CaptionFieldBlocksTest {
    private val body="BUILDING. A. BETTER. NEIGHBORHOOD."
    private val first=Post("100",Platform.X,"https://x.com/publicstudio/status/100","Public studio",body,"Workshop film",publishedAt=1_800_000_000_000)
    private val second=first.copy(id="200",platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/publicstudio.test/post/fixture",body=body.replace(" ",""),title="",publishedAt=first.publishedAt!!+1_000)
    private val other=first.copy(id="300",platform=Platform.FACEBOOK,canonicalUrl="https://www.facebook.com/publicstudio/posts/300",body="See how the public workshop came together.",title="",publishedAt=first.publishedAt!!+2_000)
    private val closing="We're grateful for every volunteer."
    private val introduction="The repair team prepared the shared tools, welcomed new visitors and explained each safe working area. Every visitor could inspect the materials and ask for help. 🛠️"
    private fun closingEditions():List<Post> = listOf(
        first.copy(title="Workshop recap",body="$introduction\n\n$closing"),
        second.copy(title="",body="$introduction $closing"),
        other.copy(body="$introduction $closing"),
        other.copy(id="400",platform=Platform.TIKTOK,canonicalUrl="https://www.tiktok.com/@publicstudio/video/400",title="We’re grateful for every volunteer 🛠️",body="",publishedAt=first.publishedAt!!+3_000))
    private fun sourcePath(fusion:PostFusion,post:Post):String {
        val key=CrossPosts.originalKey(post)
        return fusion.segments.filter {key in it.originalKeys}.joinToString(" "){segment->
            segment.weave?.joinToString(" "){run->run.variants.single {key in it.originalKeys}.text} ?: segment.text
        }
    }
    @Test fun maximumAllowedApostropheWordsDoNotRequireRecursiveMatching() {
        for(count in listOf(2_047,8_191)) {
            val text="a'".repeat(count)+"a"
            assertTrue(CaptionFieldBlocks.sourcePathMatches(text,text,typography=true))
            assertFalse(CaptionFieldBlocks.sourcePathMatches(text,text+"b",typography=true))
        }
        val exact="a'".repeat(8_191)+"ab"
        assertTrue(CaptionFieldBlocks.sourcePathMatches(exact,exact,typography=true))
        val oversized="a'".repeat(8_192)+"a"
        assertFalse(CaptionFieldBlocks.sourcePathMatches(oversized,oversized,typography=true))
    }
    @Test fun supplementaryLettersAndOtherNumbersKeepTheirLiteralWordBoundaries() {
        val curved="𝒜’Ⅻ⑫";val straight="𝒜'Ⅻ⑫"
        assertTrue(CaptionFieldBlocks.sourcePathMatches(curved,straight,typography=true))
        assertFalse(CaptionFieldBlocks.sourcePathMatches("𝒜 Ⅻ⑫","𝒜Ⅻ⑫",typography=true))
        assertFalse(CaptionFieldBlocks.sourcePathMatches("𝒜’Ⅻ⑫","𝒜’Ⅻ⑬",typography=true))
    }
    @Test fun aCompleteShortClosingSharesItsClauseWhileHeadingsIntroductionsAndEndingsStaySourced() {
        val posts=closingEditions();val raw=posts.map {it.copy()}
        val ordinary=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true))
        assertTrue(ordinary.segments.all {it.weave==null})
        val fused=requireNotNull(CaptionFieldBlocks.compose(posts,partialReferences=true,shortClosings=true))
        val woven=fused.segments.single {it.weave!=null}
        assertEquals(1,Regex("grateful for every").findAll(woven.text).count())
        assertEquals(1,Regex("The repair team prepared").findAll(woven.text).count())
        assertEquals(setOf(CrossPosts.originalKey(posts[0])),fused.segments.single {it.text=="Workshop recap"}.originalKeys)
        assertEquals(setOf(Platform.X,Platform.BLUESKY,Platform.FACEBOOK),woven.weave!!.first().variants.single {it.text.isNotBlank()}.platforms)
        assertEquals(setOf(Platform.TIKTOK),woven.weave.last().variants.single {"🛠️" in it.text}.platforms)
        for(post in posts)assertTrue(CaptionFieldBlocks.sourcePathMatches(PostPresentation.content(post),sourcePath(fused,post),typography=true))
        assertEquals(raw,posts);assertEquals(fused,CaptionFieldBlocks.compose(posts.reversed(),partialReferences=true,shortClosings=true))
        assertTrue("Presentation must not turn different raw accounts into a match",CrossPosts.group(posts,emptyList()).size>1)
    }
    @Test fun distinctCompleteIntroductionsRemainCoupledToTheirOwnClosingAndSource() {
        val posts=closingEditions().toMutableList()
        posts[2]=posts[2].copy(body="The workshop is closed today. $closing")
        val fused=requireNotNull(CaptionFieldBlocks.compose(posts,shortClosings=true))
        val woven=fused.segments.single {it.weave!=null}
        assertTrue(woven.weave!!.first().variants.any {it.text=="The workshop is closed today."&&it.originalKeys==setOf(CrossPosts.originalKey(posts[2]))})
        for(post in posts)assertTrue(CaptionFieldBlocks.sourcePathMatches(PostPresentation.content(post),sourcePath(fused,post),typography=true))
    }
    @Test fun aMidSentenceOrRepeatedClosingCannotBeLiftedIntoASharedAssertion() {
        for(body in listOf("We did not say ${closing.replaceFirstChar {it.lowercase()}}",
            "The team quoted: $closing", "$closing\n\n$introduction\n\n$closing")) {
            val posts=closingEditions().map {if(it.platform==Platform.TIKTOK)it else it.copy(body=body)}
            val fused=requireNotNull(CaptionFieldBlocks.compose(posts,shortClosings=true))
            assertTrue(fused.segments.all {it.weave==null})
            for(post in posts)assertTrue(CaptionFieldBlocks.sourcePathMatches(PostPresentation.content(post),sourcePath(fused,post)))
        }
    }
    @Test fun changedClosingClaimsAndUnrelatedNearMatchesKeepTheirWholeOriginalBlocks() {
        for(text in listOf("We're not grateful for every volunteer.","We're grateful for every donor.","We're grateful for 12 volunteers.","We're grateful for every volunteer? More details follow.")) {
            val posts=closingEditions().map {if(it.platform==Platform.TIKTOK)it.copy(title=text)else it}
            val fused=requireNotNull(CaptionFieldBlocks.compose(posts,shortClosings=true))
            assertTrue(fused.segments.all {it.weave==null})
            for(post in posts)assertTrue(CaptionFieldBlocks.sourcePathMatches(PostPresentation.content(post),sourcePath(fused,post)))
        }
    }
    @Test fun proseApostropheTypographyCannotRewriteUrlsIdentifiersNumbersOrCode() {
        assertFalse(CaptionFieldBlocks.sourcePathMatches("We're grateful.","We’re grateful."))
        assertTrue(CaptionFieldBlocks.sourcePathMatches("We're grateful.","We’re grateful.",typography=true))
        for(value in listOf("https://example.org/O’Neil","Person@O’Neil.org","API_ID=O’Neil","`O’Neil`","12’34"))
            assertFalse(value,CaptionFieldBlocks.sourcePathMatches("Read $value exactly.","Read ${value.replace('’','\'')} exactly.",typography=true))
        assertFalse(CaptionFieldBlocks.sourcePathMatches("New York workshop.","NewYork workshop.",typography=true))
        assertFalse(CaptionFieldBlocks.sourcePathMatches("The team is grateful.","The team is not grateful.",typography=true))
    }
    @Test fun questionAndSymbolEndingsRemainWholeAttributedAlternatives() {
        val posts=closingEditions().map {if(it.platform==Platform.TIKTOK)it.copy(title="We're grateful for every volunteer?")else it}
        val fused=requireNotNull(CaptionFieldBlocks.compose(posts,shortClosings=true))
        val tail=fused.segments.single {it.weave!=null}.weave!!.last()
        assertTrue(tail.variants.any {it.text=="volunteer?"&&it.platforms==setOf(Platform.TIKTOK)})
        assertTrue(tail.variants.any {it.text=="volunteer."})
        for(post in posts)assertTrue(CaptionFieldBlocks.sourcePathMatches(PostPresentation.content(post),sourcePath(fused,post),typography=true))
    }
    @Test fun aResolvedLinkInventoryCannotSplitIdenticalLongBodiesInsideAWiderCollection() {
        val caption="Last month, the repair team opened Example.org, a workshop built around visitors. " +
            "It respects their time. Gives clear instructions. And helps everyone repair their belongings. " +
            "See the complete workshop tour and meet the people who made it possible."
        val x=first.copy(body=caption,title="Workshop tour",links=listOf("https://example.org/"))
        val fb=other.copy(body=caption,title="",links=emptyList())
        val instagram=second.copy(platform=Platform.INSTAGRAM,canonicalUrl="https://www.instagram.com/p/workshop/",body=caption.replace(". Gives",".\n\nGives"),title="",links=emptyList())
        val commentary=(1..4).map {i->first.copy(id="extra$i",canonicalUrl="https://x.com/publicstudio/status/${400+i}",
            title="Tour response $i",body="An independently worded response from workshop partner $i.",publishedAt=first.publishedAt!!+i*10_000)}
        val originals=listOf(x,fb,instagram)+commentary
        val fusion=requireNotNull(CaptionFieldBlocks.compose(originals))
        assertEquals(1,fusion.segments.count {it.text.startsWith("Last month,")})
        val shared=fusion.segments.single {it.text.startsWith("Last month,")}
        assertEquals(setOf(x,fb,instagram).mapTo(linkedSetOf(),CrossPosts::originalKey),shared.originalKeys)
        for(post in originals) {
            val projected=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString("\n\n"){it.text}
            assertEquals(PostPresentation.content(post).filterNot(Char::isWhitespace),projected.filterNot(Char::isWhitespace))
        }
        assertEquals(fusion,CaptionFieldBlocks.compose(originals.reversed()))
        assertEquals(listOf("https://example.org/"),x.links)
    }
    @Test fun anIdenticalLongBodyDoesNotNeedASuppliedHeadingToShareItsPassage() {
        val caption="The workshop team welcomes every visitor with clear directions for safely repairing household equipment. " +
            "Experienced volunteers explain the tools, inspect the repair and help beginners learn practical skills."
        val x=first.copy(title="",body=caption,links=listOf("https://example.org/workshop"))
        val b=second.copy(title="",body=caption,links=emptyList())
        val posts=listOf(x,b,other)
        val fusion=requireNotNull(CaptionFieldBlocks.compose(posts))
        assertEquals(1,fusion.segments.count {it.text==caption})
        assertEquals(setOf(CrossPosts.originalKey(x),CrossPosts.originalKey(b)),fusion.segments.single {it.text==caption}.originalKeys)
        assertEquals(PostPresentation.content(other),fusion.segments.single {CrossPosts.originalKey(other)in it.originalKeys}.text)
    }
    @Test fun aSourceTitleStaysAttributedWhileItsSharedBodyIsReadOnce() {
        val posts=listOf(first,second,other);val fusion=CaptionFieldBlocks.compose(posts)!!
        assertEquals(listOf("Workshop film",body,other.body),fusion.segments.map {it.text})
        assertEquals(setOf(CrossPosts.originalKey(first)),fusion.segments.first().originalKeys)
        assertEquals(setOf(Platform.X,Platform.BLUESKY),fusion.segments[1].platforms)
        for(post in posts) {
            val actual=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString("\n\n"){it.text}.filterNot(Char::isWhitespace)
            assertEquals(PostPresentation.content(post).filterNot(Char::isWhitespace),actual)
        }
        assertEquals(fusion,CaptionFieldBlocks.compose(posts.reversed()))
        assertEquals(2,CrossPosts.group(listOf(first,second),emptyList()).size)
    }
    @Test fun distinctTitlesRemainCoupledToTheirOriginalBodyAndTitleOnlyEditionsSurvive() {
        val changed=second.copy(title="Another supplied title")
        val titleOnly=other.copy(body="",title="A shorter original caption.")
        val fusion=CaptionFieldBlocks.compose(listOf(first,changed,titleOnly))!!
        assertEquals(listOf(first.title,changed.title,body,titleOnly.title),fusion.segments.map {it.text})
        assertEquals(setOf(CrossPosts.originalKey(changed)),fusion.segments[1].originalKeys)
    }
    @Test fun changedBodiesUrlsAndPreviewsCannotDisappearThroughFieldCompression() {
        assertNull(CaptionFieldBlocks.compose(listOf(first,second.copy(body=body.replace("BETTER","DIFFERENT")))))
        assertNull(CaptionFieldBlocks.compose(listOf(first,second.copy(preview=true))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body=body+" https://t.co/First"),second.copy(body=body+" https://t.co/Second"))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body="BUILDING. A. BETTER. COMMUNITY."),second.copy(body="BUILDING.A.BETTER.COMMUNITY."))))
    }
    private val accounts=listOf(
        Follow("x",Target(Platform.X,TargetKind.PROFILE,"publicstudio","https://x.com/publicstudio"),"Public studio",personId="studio"),
        Follow("bs",Target(Platform.BLUESKY,TargetKind.PROFILE,"publicstudio.test","https://bsky.app/profile/publicstudio.test"),"Public studio",personId="studio"))
    @Test fun aLiteralBodyUsesExistingLinkedCaptionRulesWhileASuppliedTitleSurvives() {
        val posts=listOf(first,second).map {it.copy(media=listOf(Media("https://media.example.org/${it.platform.id}.mp4","video",durationMs=120_000)))}
        val members=posts.associate {CrossPosts.originalKey(it)to setOf(if(it.platform==Platform.X)"x"else "bs")}
        assertEquals(2,CrossPosts.group(posts,emptyList()).size)
        for(order in listOf(posts,posts.reversed())) {
            val group=CrossPosts.group(order,accounts,members).single()
            assertEquals(posts.toSet(),group.posts.toSet())
            assertNotNull(CaptionFieldBlocks.compose(group.posts))
            assertTrue(CrossPosts.fieldCaptionComposition(posts[0],posts[1]))
        }
    }
    @Test fun aTitleCannotHideClaimsUrlsOrCompetingHeadingsAndGenericBodiesStaySeparate() {
        val a=first.copy(media=listOf(Media("https://media.example.org/first.mp4","video")))
        val b=second.copy(media=listOf(Media("https://media.example.org/second.mp4","video")))
        for(changed in listOf(a.copy(title="Not approved"),a.copy(title="This claim is false"),a.copy(title="Is this accurate?"),a.copy(title="Workshop 12"),a.copy(title="Details https://other.example.org/item"),
            a.copy(body=a.body.replace("BETTER","DIFFERENT")),a.copy(publishedAt=second.publishedAt!!-601_000),a.copy(media=emptyList()),a.copy(preview=true),a.copy(parentId="parent"))) {
            assertFalse(CrossPosts.fieldCaptionComposition(changed,b))
            val members=listOf(changed,b).associate {CrossPosts.originalKey(it)to setOf(if(it.platform==Platform.X)"x"else "bs")}
            assertEquals(2,CrossPosts.group(listOf(changed,b),accounts,members).size)
        }
        assertFalse(CrossPosts.fieldCaptionComposition(a,b.copy(title="A different heading")))
        assertFalse(CrossPosts.fieldCaptionComposition(a.copy(body="Hello there."),b.copy(body="Hello there.")))
    }

    @Test fun proseCaseAndARepeatedTitleAreFormattingWhileEverySourcePathStaysAvailable() {
        val mixed=second.copy(body="Building.A.Better.Neighborhood.",title="WORKSHOP FILM")
        val originals=listOf(first,mixed,other);val raw=originals.map {it.copy()}
        val fusion=requireNotNull(CaptionFieldBlocks.compose(originals))
        assertEquals(1,fusion.segments.count {it.text.equals(first.title,true)})
        assertEquals(1,fusion.segments.count {it.text.filterNot(Char::isWhitespace).equals(body.filterNot(Char::isWhitespace),true)})
        for(post in originals) {
            val rendered=fusion.segments.filter {CrossPosts.originalKey(post)in it.originalKeys}.joinToString("\n\n"){it.text}
            assertEquals(PostPresentation.content(post).filterNot(Char::isWhitespace).lowercase(),rendered.filterNot(Char::isWhitespace).lowercase())
        }
        assertEquals(raw,originals);assertEquals(fusion,CaptionFieldBlocks.compose(originals.reversed()))
    }
    @Test fun caseFormattingDoesNotLowerSourceUrlPathsOrJoinDifferentWordBoundaries() {
        val text="The workshop report explains the complete repair safely. https://example.org/Reports/CaseSensitive"
        val a=first.copy(body=text);val b=second.copy(body=text.lowercase().replace("/reports/casesensitive","/Reports/CaseSensitive"))
        val fusion=requireNotNull(CaptionFieldBlocks.compose(listOf(a,b)))
        assertTrue(fusion.segments.any {it.text.contains("/Reports/CaseSensitive")})
        assertNull(CaptionFieldBlocks.compose(listOf(a,b.copy(body=text.lowercase()))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body="New York repair workshop."),second.copy(body="NewYork repair workshop."))))
        assertNull(CaptionFieldBlocks.compose(listOf(first.copy(body=body),second.copy(body=body.replace("BETTER","NOT BETTER")))))
    }
    @Test fun addressesIdentifiersNumericUnitsAndCodeRemainVerbatimWithinFormatting() {
        val snippets=listOf("Someone@Example.org","urn:Example:CaseSensitive","API_KEY=CaseSensitive","Case_ID","src/CaseSensitive.kt","1M","@StudioName","#PublicStudio","`CaseSensitive`","```CaseSensitive```")
        for(snippet in snippets) {
            val text="The published workshop report retains $snippet exactly."
            val a=first.copy(body=text);val b=second.copy(body=text.uppercase().replace(snippet.uppercase(),snippet))
            assertNotNull("Surrounding prose can format around $snippet",CaptionFieldBlocks.compose(listOf(a,b)))
            assertNull("The literal identifier cannot change: $snippet",CaptionFieldBlocks.compose(listOf(a,b.copy(body=text.lowercase()))))
        }
    }

}
