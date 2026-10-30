package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class AnnouncementCollectionsTest {
    private val at=1_800_000_000_000L
    private val platforms=listOf(Platform.X,Platform.FACEBOOK,Platform.INSTAGRAM,Platform.TIKTOK)
    private fun profile(p:Platform)=when(p) {
        Platform.X->"https://x.com/publiclab"
        Platform.FACEBOOK->"https://www.facebook.com/publiclab"
        Platform.INSTAGRAM->"https://www.instagram.com/publiclab/"
        else->"https://www.tiktok.com/@publiclab"
    }
    private val accounts=platforms.map {Follow(it.id,Target(it,TargetKind.PROFILE,"publiclab",profile(it)),"Public lab",personId="public-lab")}
    private fun post(p:Platform,text:String,id:String="123456789",minutes:Int=0):Post {
        val url=when(p) {
            Platform.X->"${profile(p)}/status/$id"
            Platform.FACEBOOK->"${profile(p)}/posts/$id"
            Platform.INSTAGRAM->"https://www.instagram.com/p/$id/"
            else->"${profile(p)}/video/$id"
        }
        return Post(id,p,url,"Public lab",text,publishedAt=at+minutes*60_000L,
            media=listOf(Media("https://media.example.org/${p.id}-$id.mp4","video",preview="https://media.example.org/${p.id}-$id.jpg")))
    }
    private fun group(posts:List<Post>,artwork:(Post,Post)->Boolean={_,_->false}):List<PostGroup> {
        val membership=posts.associate {CrossPosts.originalKey(it) to setOf(it.platform.id)}
        return CrossPosts.group(posts,accounts,membership,artwork)
    }
    private fun owners(posts:List<Post>)=posts.associate {CrossPosts.originalKey(it) to "public-lab"}
    private fun collect(groups:List<PostGroup>,artwork:(Post,Post)->Boolean={_,_->false})=
        AnnouncementCollections.collect(groups,owners(groups.flatMap {it.posts}),artwork)
    private fun anchor()=platforms.take(3).map {post(it,"Our workshop is open. 🎷",minutes=1)}

    private val decoratedStatement="The laboratory team demonstrates the winter telescope workshop. Visitors can build their own instruments during the open afternoon."
    private fun decoratedAnchor(statement:String=decoratedStatement):List<Post> {
        val video=post(Platform.X,"$statement 👻",minutes=1)
        return listOf(video,post(Platform.FACEBOOK,"$statement 👻",minutes=2).copy(media=emptyList()))
    }
    @Test fun aDecoratedStillPreviewJoinsAnEstablishedVideoAnnouncementWithMissingMediaOnOneEdition() {
        val base=decoratedAnchor()
        val still=post(Platform.INSTAGRAM,decoratedStatement,minutes=3).copy(media=listOf(Media(base.first().media.single().preview!!)))
        for(input in listOf(base+still,(base+still).reversed())) {
            val result=group(input).single()
            assertEquals(input.toSet(),result.posts.toSet())
            assertEquals(1,result.posts.flatMap {it.media}.count {it.type=="video"})
            val fusion=requireNotNull(CrossPostFusion.composeCollectedEditions(result.posts))
            assertEquals(1,Regex("The laboratory team").findAll(fusion.segments.joinToString(" "){it.text}).count())
        }
    }
    @Test fun decorativeArtworkAdmissionKeepsAmbiguityClaimAndOwnershipBoundaries() {
        val statement="The laboratory telescope workshop opens for local visitors this afternoon."
        val base=PostGroup(decoratedAnchor(statement))
        val still=post(Platform.INSTAGRAM,statement,minutes=3).copy(media=listOf(Media("https://media.example.org/still.jpg")))
        assertEquals(2,collect(listOf(base,PostGroup(listOf(still)))).size)
        assertEquals(1,collect(listOf(base,PostGroup(listOf(still))),{_,_->true}).size)
        for(changed in listOf(still.copy(body=statement.replace("opens","does not open")),
            still.copy(body="$statement ❌"),still.copy(body="$statement 2"),
            still.copy(body=statement.replace("afternoon.","afternoon?")),still.copy(links=listOf("https://example.org/different")),
            still.copy(publishedAt=at+32*60_000),still.copy(preview=true),still.copy(parentId="other")))
            assertEquals(changed.body,2,collect(listOf(base,PostGroup(listOf(changed))),{_,_->true}).size)
        val other=PostGroup(base.posts.map {it.copy(id="other-${it.id}",canonicalUrl=it.canonicalUrl+"0",publishedAt=it.publishedAt!!+60_000)})
        assertEquals(3,collect(listOf(base,other,PostGroup(listOf(still))),{_,_->true}).size)
        val twin=still.copy(id="987654321",canonicalUrl=still.canonicalUrl.replace("123456789","987654321"))
        assertEquals(3,collect(listOf(base,PostGroup(listOf(still)),PostGroup(listOf(twin))),{_,_->true}).size)
        assertEquals(2,AnnouncementCollections.collect(listOf(base,PostGroup(listOf(still))),owners(base.posts),{_,_->true}).size)
    }
    @Test fun longDecorativeAgreementDoesNotEraseChangedFlagsOrAuthorizeATransitiveAnchor() {
        val base=PostGroup(decoratedAnchor())
        val still=post(Platform.INSTAGRAM,decoratedStatement,minutes=3).copy(media=emptyList())
        assertEquals(1,collect(listOf(base,PostGroup(listOf(still)))).size)
        for(text in listOf("$decoratedStatement 🇨🇦",decoratedStatement.replace("winter","summer"),"$decoratedStatement..."))
            assertEquals(2,collect(listOf(base,PostGroup(listOf(still.copy(body=text)))),{_,_->true}).size)
        val noAnchor=base.posts.take(1)+still
        assertEquals(2,collect(noAnchor.map {PostGroup(listOf(it))},{_,_->true}).size)
    }

    @Test fun delayedShortEditionJoinsEstablishedCollectionInEitherInputOrder() {
        val originals=anchor()+post(Platform.TIKTOK,"Our workshop is open",minutes=23)
        for(input in listOf(originals,originals.reversed())) {
            val result=group(input).single()
            assertEquals(originals.toSet(),result.posts.toSet())
            assertEquals(1,CrossPostFusion.composeCollectedEditions(result.posts)!!.segments.size)
        }
    }
    @Test fun shortEditionNeedsAnEstablishedOwnerAndUnambiguousWholeClaim() {
        val anchor=anchor();val later=post(Platform.TIKTOK,"Our workshop is open",minutes=23)
        assertEquals(2,group(listOf(anchor.first(),later)).size)
        for(text in listOf("Our workshop is closed","Our workshop is not open","Our workshop is open?","Our workshop is open ❌","Our workshop is open 2","Our workshop is open..."))
            assertEquals(text,2,group(anchor+later.copy(body=text)).size)
        assertEquals(2,group(anchor+later.copy(publishedAt=at+32*60_000)).size)
        val groups=listOf(PostGroup(anchor),PostGroup(listOf(later)))
        assertEquals(2,AnnouncementCollections.collect(groups,owners(anchor),{_,_->true}).size)
        assertEquals(2,group(anchor+later.copy(links=listOf("https://example.org/another-claim"))).size)
        assertEquals(2,group(anchor+later.copy(preview=true)).size)
        assertEquals(2,group(anchor+later.copy(parentId="another-post")).size)
    }
    @Test fun competingLeavesAndRepeatedAnnouncementsCannotChooseByInputOrder() {
        val anchor=PostGroup(anchor());val leaf=post(Platform.TIKTOK,"Our workshop is open",minutes=23)
        val duplicate=leaf.copy(id="987654321",canonicalUrl=leaf.canonicalUrl.replace("123456789","987654321"))
        assertEquals(3,collect(listOf(anchor,PostGroup(listOf(leaf)),PostGroup(listOf(duplicate)))).size)
        val second=PostGroup(anchor.posts.map {it.copy(id="other-${it.id}",canonicalUrl=it.canonicalUrl+"0",publishedAt=at+3*60_000)})
        assertEquals(3,collect(listOf(anchor,second,PostGroup(listOf(leaf)))) .size)
        // A newly attached leaf cannot extend a frozen anchor's time window.
        val late=post(Platform.TIKTOK,"Our workshop is open",id="999999999",minutes=55)
        val result=collect(listOf(anchor,PostGroup(listOf(leaf)),PostGroup(listOf(late))))
        assertEquals(2,result.size);assertTrue(result.any {it.posts==listOf(late)})
    }
    @Test fun distinctiveReferenceAppendixJoinsWithoutWaitingForPixelsAndRemainsAttributed() {
        val statement="The public laboratory is open for visitors. 🎯"
        val base=platforms.drop(1).take(2).map {post(it,statement).let {p->p.copy(media=p.media.map {it.copy(type="image")})}}
        val x=post(Platform.X,"$statement @MuseumTeam https://t.co/AbCdEf123",minutes=2).let {p->p.copy(media=p.media.map {it.copy(type="image")})}
        assertEquals(1,group(base+x).size)
        val originals=group(base+x,{_,_->true}).single().posts
        assertEquals((base+x).toSet(),originals.toSet())
        val fusion=CrossPostFusion.composeCollectedEditions(originals)!!
        assertEquals(1,Regex("The public laboratory").findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("@MuseumTeam"))
        assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text.contains("https://t.co/AbCdEf123")});assertFalse(fusion.segments.single().text.contains("https://t.co/"))
        assertEquals(2,group(base+x.copy(body=x.body.replace("open","closed")),{_,_->true}).size)
        assertEquals(2,collect(listOf(PostGroup(base),PostGroup(listOf(x.copy(body="$statement https://example.org/different")))),{_,_->true}).size)
        val brief=base.map {it.copy(body="Our workshop is open.")}
        val reference=x.copy(body="Our workshop is open. @MuseumTeam")
        assertEquals(2,collect(listOf(PostGroup(brief),PostGroup(listOf(reference)))).size)
        assertEquals(1,collect(listOf(PostGroup(brief),PostGroup(listOf(reference))),{_,_->true}).size)
    }
    @Test fun acquiredMentionNavigationDoesNotBecomeACompetingArticleDestination() {
        val statement="Not at the public laboratory. 🎯"
        val base=listOf(Platform.FACEBOOK,Platform.INSTAGRAM).map {post(it,statement).let {p->p.copy(media=p.media.map {it.copy(type="image")})}}
        val root="https://reader.example.org"
        val mention="$root/MuseumTeam"
        val source=Source(Platform.X,"nitter",root)
        val x=post(Platform.X,"$statement @MuseumTeam",minutes=1).let {p->p.copy(media=p.media.map {it.copy(type="image")})}.copy(links=listOf(mention),
            provenance=Provenance(source,at,root+"/publiclab/status/123456789"))
        assertEquals(2,group(base+x).size) // Short caption still needs artwork.
        val originals=group(base+x,{_,_->true}).single().posts
        assertEquals((base+x).toSet(),originals.toSet())
        val fusion=requireNotNull(CrossPostFusion.composeCollectedEditions(originals))
        assertEquals(1,Regex("Not at the public laboratory").findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("@MuseumTeam"))
        assertTrue(originals.single {it.platform==Platform.X}.links!!.contains(mention))
        for(changed in listOf(
            x.copy(links=listOf("https://unrelated.example.org/MuseumTeam")),
            x.copy(links=listOf("$mention?article=1")),
            x.copy(links=listOf("$root/AnotherTeam")),
            x.copy(body="$statement [@MuseumTeam]($mention)"),
            x.copy(provenance=x.provenance!!.copy(source=source.copy(frontend="other"))),
            x.copy(body=x.body.replace("Not at","Open at")),
            x.copy(body="Not at the public laboratory? 🎯 @MuseumTeam"),
            x.copy(body="Not at the public laboratory 2. 🎯 @MuseumTeam"),
            x.copy(body="Not at @MuseumTeam's public laboratory. 🎯")
        ))assertEquals(changed.body+changed.links,2,group(base+changed,{_,_->true}).size)
    }
    @Test fun exactBroadcastAndDistinctiveHeadingCanJoinAFullerEstablishedAnnouncement() {
        val heading="Quiet music from our public laboratory"
        val body="$heading 🎶 The team has prepared a new recording with several musicians and a detailed explanation of the instruments visitors can build during the next public workshop."
        val broadcast="https://x.com/i/broadcasts/1AbCdEfGhIjKl"
        val x=post(Platform.X,"$body https://t.co/AbCdEf123",minutes=1).copy(media=emptyList(),links=listOf(broadcast))
        val fb=post(Platform.FACEBOOK,body,minutes=2)
        val leaf=post(Platform.X,"$heading 🎶 https://t.co/XyZ123456","987654321").copy(media=emptyList(),links=listOf(broadcast))
        val base=PostGroup(listOf(x,fb))
        val result=collect(listOf(base,PostGroup(listOf(leaf)))).single()
        assertEquals(setOf(x,fb,leaf),result.posts.toSet())
        assertTrue(CrossPosts.sameSourceRepeats(listOf(x,leaf)))
        for(changed in listOf(leaf.copy(links=listOf(broadcast+"Different")),leaf.copy(publishedAt=at-11*60_000),leaf.copy(body="Another completely different live show https://t.co/XyZ123456"),leaf.copy(canonicalUrl=leaf.canonicalUrl.replace("publiclab","anotherlab"))))
            {assertEquals(2,collect(listOf(base,PostGroup(listOf(changed)))).size);assertFalse(CrossPosts.sameSourceRepeats(listOf(x,changed)))}
        val fusion=CrossPostFusion.composeCollectedEditions(result.posts)!!
        assertEquals(1,Regex(heading).findAll(fusion.segments.single().text).count())
        assertEquals(1,Regex("The team has prepared").findAll(fusion.segments.single().text).count())
        // Each original, including two X editions, reconstructs its own text.
        for(post in result.posts) {
            val key=CrossPosts.originalKey(post)
            val projection=fusion.weave!!.joinToString(" "){it.variants.single {key in it.originalKeys}.text}.replace(Regex("\\s+")," ").trim()
            assertEquals(PostPresentation.content(post).replace(Regex("\\s+")," ").trim(),projection)
        }
        assertEquals(2,collect(listOf(PostGroup(listOf(x)),PostGroup(listOf(leaf)))).size)
    }
    @Test fun overfullUnionNeverLosesAnyOriginal() {
        val many=(0 until CrossPosts.MAX_EDITIONS-1).map {i->post(platforms[i%2],"Our workshop is open. 🎷",id=(100000000L+i).toString(),minutes=1)}
        val leaves=listOf(post(Platform.TIKTOK,"Our workshop is open",minutes=23),post(Platform.INSTAGRAM,"Our workshop is open",id="other",minutes=23))
        val groups=listOf(PostGroup(many))+leaves.map {PostGroup(listOf(it))}
        assertEquals((many+leaves).toSet(),collect(groups).flatMap {it.posts}.toSet())
    }
    @Test fun literalCollationDoesNotInventPathsOrDropCaseSensitiveReferences() {
        val text="The laboratory is open for visitors today."
        val originals=listOf(post(Platform.X,"$text https://example.org/Case?token=AbC"),post(Platform.FACEBOOK,text))
        val fusion=CrossPostFusion.composeCollectedEditions(originals)!!
        assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text.contains("https://example.org/Case?token=AbC")})
        assertNull(CrossPostFusion.composeCollectedEditions(originals.map {it.copy(body=it.body.replace("visitors","different guests"))}.take(1)))
        assertNull(AnnouncementEditions.compose(listOf(originals[0],originals[1].copy(body="The laboratory is closed for visitors today."))))
        assertNull(AnnouncementEditions.caption(originals[0].copy(body="word ".repeat(300))))
        assertNull(AnnouncementEditions.caption(originals[0].copy(body="\u202e$text")))
        assertEquals("The laboratory https://example.org/source is open for visitors today.",AnnouncementEditions.caption(originals[0].copy(body="The laboratory https://example.org/source is open for visitors today."))!!.message)
    }
    @Test fun smallLiteralWordingEditsFromLinkedPublishersDoNotWaitForAnImage() {
        val text="Our public laboratory has completed its latest workshop for visitors. These are the results of several months of careful preparation by the observing team. Everyone can inspect the published instructions and try the experiment with ordinary household materials."
        val a=post(Platform.X,text).copy(media=emptyList())
        val b=post(Platform.FACEBOOK,text.replace("the results","the direct results"),minutes=1).copy(media=emptyList())
        val result=group(listOf(a,b)).single()
        val fusion=CrossPostFusion.compose(result.posts)!!
        assertEquals(1,Regex("Our public laboratory").findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("direct results"))
        for(changed in listOf(b.copy(body=b.body.replace("completed","cancelled")),b.copy(body=b.body+" 12 visitors attended."),b.copy(body=b.body.replace("observing team","Hubble team")),b.copy(publishedAt=at+11*60_000),b.copy(links=listOf("https://example.org/different"))))
            assertEquals(2,group(listOf(a,changed)).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),accounts.map {it.copy(personId=null)},mapOf(b.identityKey to setOf(Platform.FACEBOOK.id))).size)
    }
    @Test fun exactFeedAndTimestampCanReconcileOpaqueMediaBearingAliasesWithoutPixels() {
        val text="A clear view across the workshop. Next stop: the public laboratory."
        val a=post(Platform.FACEBOOK,text).copy(canonicalUrl="https://www.facebook.com/10000123456789/posts/123456789")
        val b=a.copy(id="pfbidFixtureAlias123456789",canonicalUrl="https://www.facebook.com/publiclab/posts/pfbidFixtureAlias123456789",media=listOf(Media("https://media.example.org/another-rendition.jpg")))
        val x=post(Platform.X,text)
        for(input in listOf(listOf(a,b,x),listOf(x,b,a)))assertEquals(setOf(a,b,x),group(input).single().posts.toSet())
        assertEquals(2,group(listOf(a,b.copy(publishedAt=at+1))).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),accounts).size)
        assertEquals(2,group(listOf(a,b.copy(body=text.replace("clear","different")))).size)
    }
    @Test fun aRetainedVideoTitleDoesNotCreateAnotherCardForTheSameFullAnnouncementBody() {
        val text="The public laboratory will host its next open workshop tomorrow. Visitors can inspect the equipment and discuss the published experiment with our observing team."
        val fb=post(Platform.FACEBOOK,text);val ig=post(Platform.INSTAGRAM,text)
        val x=post(Platform.X,text,minutes=1).copy(title="A new look inside the laboratory")
        for(input in listOf(listOf(fb,ig,x),listOf(x,ig,fb))) {
            val roots=group(input).single().posts
            val fusion=CrossPostFusion.composeCollectedEditions(roots)!!
            assertEquals(1,Regex("Visitors can inspect").findAll(fusion.segments.single().text).count())
            assertTrue(fusion.segments.single().text.contains(x.title))
            for(post in roots) {
                val key=CrossPosts.originalKey(post)
                val projection=fusion.weave!!.joinToString(" "){it.variants.single {key in it.originalKeys}.text}.trim()
                assertEquals(PostPresentation.content(post).replace(Regex("\\s+")," ").trim(),projection)
            }
        }
        for(changed in listOf(x.copy(title="This claim is false"),x.copy(body=text.replace("tomorrow","yesterday")),x.copy(publishedAt=at+11*60_000)))
            assertEquals(2,group(listOf(fb,ig,changed)).size)
        assertEquals(2,group(listOf(fb,x)).size)
    }
    @Test fun corroboratedNameEditionsKeepTheirLiteralNamesAndReferencePaths() {
        val shared="Crew-4 contributed to science research including studying growing plants, bone regeneration, and resistant bacteria. Everyone can inspect the detailed published observations from the experiment and compare the methods used during the public demonstration."
        val frame=VisualFrames.encode(1.0,40.0,1234,5678,List(48){(it*11)%256},ByteArray(4096){(it%255).toByte()})!!
        val a=post(Platform.X,"While aboard the @Aster_Station, $shared https://t.co/AbC123456").copy(media=listOf(Media("https://media.example.org/x.jpg",visualFingerprint=frame)))
        val b=post(Platform.FACEBOOK,"While aboard the International Aster Station, $shared https://t.co/DeF987654",minutes=1).copy(media=listOf(Media("https://media.example.org/fb.jpg",attachmentFingerprint=frame)))
        val roots=group(listOf(a,b)).single().posts
        val fusion=CrossPostFusion.compose(roots)!!
        assertEquals(1,Regex("contributed to science research").findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("@Aster_Station"));assertTrue(fusion.segments.single().text.contains("International Aster Station"))
        assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text.contains("https://t.co/AbC123456")});assertTrue(fusion.weave!!.flatMap {it.variants}.any {it.text.contains("https://t.co/DeF987654")})
        for(post in roots) {
            val key=CrossPosts.originalKey(post)
            val projection=fusion.weave!!.joinToString(" "){it.variants.single {key in it.originalKeys}.text}.trim()
            assertEquals(PostPresentation.content(post).replace(Regex("\\s+")," ").trim(),projection)
        }
        for(changed in listOf(b.copy(body=b.body.replace("International Aster Station","Another Laboratory")),b.copy(body=b.body.replace("Crew-4","Crew-5")),b.copy(body="This claim is false. "+b.body),b.copy(publishedAt=at+11*60_000),b.copy(media=emptyList())))
            assertEquals(2,group(listOf(a,changed)).size)
        assertEquals(2,group(listOf(a.copy(links=listOf("https://example.org/one")),b.copy(links=listOf("https://example.org/two")))).size)
        assertFalse(CaptionAliases.correspondingNames("@UnrelatedPerson","International Space Station"))
        assertTrue(CaptionAliases.correspondingNames("@AsterHubble","Aster's Hubble Space Telescope"))
    }
    @Test fun aWholeSharedResourceAnnouncementKeepsIntroductionsAndRecordingsWithoutRepeatingTheMessage() {
        val statement="Public equipment repair requests are welcome; report damaged instruments and review the workshop maintenance instructions."
        val reference="https://laboratory.example.org/report"
        val a=post(Platform.FACEBOOK,"Keeping the workshop working.\n\n$statement $reference")
        val b=post(Platform.X,"Helping our public equipment last.\n\n$statement $reference",minutes=7)
        val roots=group(listOf(a,b)).single().posts
        assertEquals(setOf(a,b),roots.toSet())
        val fusion=CrossPostFusion.composeCollectedEditions(roots)!!
        assertEquals(1,Regex(Regex.escape(statement)).findAll(fusion.segments.single().text).count())
        assertTrue(fusion.segments.single().text.contains("Keeping the workshop working."))
        assertTrue(fusion.segments.single().text.contains("Helping our public equipment last."))
        assertFalse(fusion.segments.single().text.contains(reference))
        for(post in roots) {
            val key=CrossPosts.originalKey(post)
            val projection=fusion.weave!!.joinToString(" "){it.variants.single {key in it.originalKeys}.text}.trim()
            assertEquals(PostPresentation.content(post).replace(Regex("\\s+")," ").trim(),projection)
        }
        for(changed in listOf(b.copy(body=b.body.replace("damaged","missing")),b.copy(body=b.body.replace("/report","/different")),b.copy(body="Correction notice.\n\n$statement $reference"),b.copy(body="Helping 20 neighbors.\n\n$statement $reference"),b.copy(publishedAt=at+11*60_000),b.copy(media=emptyList()),b.copy(body=b.body.replace("\n\n"," "))))
            assertEquals(2,group(listOf(a,changed)).size)
        assertEquals(2,CrossPosts.group(listOf(a,b),accounts.map {it.copy(personId=null)},mapOf(a.identityKey to setOf(Platform.FACEBOOK.id))).size)
        assertNull(ResourceAnnouncements.key(a.copy(body="Workshop notes.\n\n$statement Today.")))
        val monday=a.copy(body="The repair workshop starts Monday.\n\n$statement $reference")
        val tuesday=b.copy(body="The repair workshop starts Tuesday.\n\n$statement $reference")
        assertEquals("A shared resource footer cannot erase a changed appointment",2,group(listOf(monday,tuesday)).size)
    }
    @Test fun portableSameSecondCaptionCopiesDoNotNeedToInventAccountOwnership() {
        val text="The public laboratory has opened its doors for visitors. Everyone can inspect the demonstration and speak with our observing team."
        val a=post(Platform.FACEBOOK,text).copy(canonicalUrl="https://www.facebook.com/10000123456789/posts/123456789")
        val b=a.copy(id="pfbidFixture123456789",canonicalUrl="https://www.facebook.com/publiclab/posts/pfbidFixture123456789",media=emptyList())
        assertTrue(CrossPosts.sameContentCopies(listOf(a,b)))
        assertFalse(CrossPosts.sameSourceRepeats(listOf(a,b)))
        assertFalse(CrossPosts.sameContentCopies(listOf(a,b.copy(publishedAt=at+1))))
        val changed=b.copy(body=text.replace("opened","closed"))
        assertFalse(CrossPosts.sameContentCopies(listOf(a,changed)))
        val linkedA=a.copy(links=listOf("https://example.org/one"))
        val linkedB=b.copy(links=listOf("https://example.org/two"))
        assertFalse(CrossPosts.sameContentCopies(listOf(linkedA,linkedB)))
    }
}
