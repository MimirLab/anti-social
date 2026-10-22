package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class ArchivePolicyTest {
    private val current=Post("42",Platform.X,"https://x.com/person/status/42","Person","A public statement.",publishedAt=1000)

    @Test fun decorationCountersAndRetrievalDatesDoNotBecomeEdits() {
        val old=current.copy(author="Person's previous name",avatar="https://images.example/old.jpg",publishedAt=500,score=9,replyCount=5,
            provenance=Provenance(Source(Platform.X,"archive","https://archive.example"),123,"https://archive.example/capture"))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(current,old))
        assertEquals(ArchiveContent.signature(current),ArchiveContent.signature(old))
    }
    @Test fun htmlEntitiesUnicodeSpacingAndTrackingLinksArePresentationOnly() {
        val live=current.copy(body="Café & tea. https://example.org/article?story=7&utm_source=social&fbclid=abc")
        val old=current.copy(body="<p>Cafe\u0301 &amp;\u00a0tea.</p><p>https://example.org/article?story=7</p>")
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,old))
        assertEquals(ArchiveContent.signature(live),ArchiveContent.signature(old))
        assertEquals(ArchiveComparison.DIFFERENT,ArchiveContent.compare(live,old.copy(body="Café & tea. https://example.org/article?story=8")))
    }
    @Test fun substantiveTextAndTitlesCanRevealAnEdit() {
        assertEquals(ArchiveComparison.DIFFERENT,ArchiveContent.compare(current,current.copy(body="A retracted public statement.")))
        assertEquals(ArchiveComparison.DIFFERENT,ArchiveContent.compare(current.copy(title="New headline"),current.copy(title="Old headline")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(current,current.copy(title=current.body)))
    }
    @Test fun missingTruncatedOrPreviewFieldsCannotProveAnEdit() {
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(body="A public…")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current.copy(body="A public..."),current))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(body="")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(body="Another statement",preview=true)))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(title="Extractor supplied headline")))
    }
    @Test fun unrelatedOrEmptyCapturesCannotProduceAClock() {
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(canonicalUrl="https://x.com/person/status/43",body="Different statement")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current.copy(body=""),current.copy(body="")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(current,current.copy(platform=Platform.BLUESKY,body="Different statement")))
        val invalid=current.copy(body="",media=listOf(Media("javascript:alert(1)")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(invalid,invalid))
    }
    @Test fun stableImageIdentitySurvivesResizingTrackingAndWaybackWrapping() {
        val live=current.copy(body="",media=listOf(Media("https://pbs.twimg.com/media/Photo123.jpg?name=large&format=jpg")))
        val old=current.copy(body="",media=listOf(Media("https://web.archive.org/web/20200101120000im_/https://pbs.twimg.com/media/Photo123?format=webp&name=small")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,old))
        assertEquals(ArchiveContent.signature(live),ArchiveContent.signature(old))
        val arquivo=old.copy(media=listOf(Media("https://arquivo.pt/noFrame/replay/20210323183740id_/https://pbs.twimg.com/media/Photo123?format=png&name=small")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,arquivo))
        assertEquals(ArchiveContent.signature(live),ArchiveContent.signature(arquivo))
        assertEquals(ArchiveComparison.DIFFERENT,ArchiveContent.compare(live,old.copy(media=listOf(Media("https://pbs.twimg.com/media/PreviousPhoto.jpg")))))
    }
    @Test fun videoThumbnailsAndDifferentStreamEncodingsAreOneAsset() {
        val live=current.copy(body="",media=listOf(Media("https://video.twimg.com/ext_tw_video/12345/pu/vid/1280x720/movie.mp4?tag=12","video")))
        val old=live.copy(media=listOf(Media("https://pbs.twimg.com/ext_tw_video_thumb/12345/pu/img/thumb.jpg")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,old))
    }
    @Test fun expiredSignedUrlsDoNotBecomeAnEdit() {
        val live=current.copy(media=listOf(Media("https://cdn.example/image.jpg?Signature=new&Expires=200&width=640&content=abc")))
        val old=current.copy(media=listOf(Media("https://cdn.example/image.jpg?Signature=old&Expires=100&width=320&content=abc")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,old))
        assertEquals(ArchiveContent.signature(live),ArchiveContent.signature(old))
    }
    @Test fun missingArchiveAssetsOrUnknownCdnRewritesAreInconclusive() {
        val live=current.copy(media=listOf(Media("https://i.redd.it/abcdef123456.jpg")))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(live,current))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(live,live.copy(media=live.media+Media("https://i.redd.it/another12345.jpg"))))
        assertEquals(ArchiveComparison.INCONCLUSIVE,ArchiveContent.compare(live,live.copy(media=listOf(Media("https://proxy.example/archived-image.jpg")))))
        assertEquals(ArchiveComparison.DIFFERENT,ArchiveContent.compare(live,live.copy(body="Previous text",media=listOf(Media("https://proxy.example/archived-image.jpg")))))
    }
    @Test fun mediaOrderIsNotProofOfAnEditAndAssetHostAliasesCanMatch() {
        val live=current.copy(media=listOf(Media("https://i.redd.it/abcdef123456.jpg"),Media("https://i.redd.it/second123456.jpg")))
        val old=current.copy(media=listOf(Media("https://preview.redd.it/second123456.jpg?w=320"),Media("https://preview.redd.it/abcdef123456.png?width=640")))
        assertEquals(ArchiveComparison.SAME,ArchiveContent.compare(live,old))
        assertEquals(ArchiveContent.signature(live),ArchiveContent.signature(old))
    }

    private var now=100L*86_400_000
    private fun policy()=ArchiveCheckPolicy {now}
    private fun finish(policy: ArchiveCheckPolicy,key: String="post-1",signature: String="v1",tor: Boolean=false,outcome: ArchiveCheckOutcome=ArchiveCheckOutcome.SAME)=policy.complete(key,signature,tor,outcome)

    @Test fun onlyOnePassiveCheckCanRunAndScrollCancellationStillUsesTheBudget() {
        val p=policy();assertTrue(p.claim("post-1","v1",false))
        assertFalse(p.claim("post-2","v1",true))
        finish(p,outcome=ArchiveCheckOutcome.CANCELLED)
        assertFalse(p.claim("post-2","v1",true))
        now+=30_000;assertTrue(p.claim("post-2","v1",true))
    }
    @Test fun switchingTorOrChangingThePostCannotEvadeItsDailyBudget() {
        val p=policy();assertTrue(p.claim("post-1","v1",false));finish(p)
        now+=30_000
        assertFalse(p.claim("post-1","edited-v2",true))
        now+=86_400_001
        assertTrue(p.claim("post-1","edited-v2",true))
    }
    @Test fun noCaptureResultsAreSuppressedForThreeDays() {
        val p=policy();assertTrue(p.claim("post-1","v1",false));finish(p,outcome=ArchiveCheckOutcome.NOT_FOUND)
        now+=2*86_400_000
        assertFalse(p.claim("post-1","v1",false))
        now+=86_400_001
        assertTrue(p.claim("post-1","v1",false))
    }
    @Test fun rollingHourlyBudgetCountsAllProvidersAndFailures() {
        val p=policy()
        repeat(6) {index->
            val key="post-$index";assertTrue(p.claim(key,"v1",index%2==0))
            finish(p,key=key,tor=index%2==0,outcome=ArchiveCheckOutcome.FAILED);now+=30_000
        }
        assertFalse(p.claim("post-7","v1",false))
        now+=3_420_001
        assertTrue(p.claim("post-7","v1",false))
    }
    @Test fun providerRateLimitsPauseAllPassiveArchiveTrafficForAnHour() {
        val p=policy();assertTrue(p.claim("post-1","v1",false));finish(p,outcome=ArchiveCheckOutcome.RATE_LIMITED)
        now+=3_599_999;assertFalse(p.claim("post-2","v1",true))
        now+=2;assertTrue(p.claim("post-2","v1",true))
    }
    @Test fun abandonedRequestsExpireButStaleCompletionsCannotReleaseAnotherLease() {
        val p=policy();assertTrue(p.claim("post-1","v1",false))
        now+=60_001;assertTrue(p.claim("post-2","v2",true))
        finish(p)
        now+=30_000;assertFalse(p.claim("post-3","v1",false))
        finish(p,key="post-2",signature="v2",tor=true)
        assertTrue(p.claim("post-3","v1",false))
    }
    @Test fun courtesySpacingAndHourlyLimitsSurviveProcessRestart() {
        var p=policy()
        repeat(6) {index->
            val key="post-$index";assertTrue(p.claim(key,"v1",false));finish(p,key=key)
            val saved=p.budget()
            p=policy().apply {restoreBudget(saved)}
            assertFalse(p.claim("next-$index","v1",true))
            now+=30_000
        }
        assertFalse(p.claim("seventh","v1",false))
        assertEquals(6,p.budget().starts.size)
        now+=3_420_001
        assertTrue(p.claim("seventh","v1",false))
        assertTrue(p.budget().starts.size<=6)
    }
    @Test fun aRateLimitPauseSurvivesRestartAndOldHourlyEventsExpire() {
        val before=policy();assertTrue(before.claim("post-1","v1",false));finish(before,outcome=ArchiveCheckOutcome.RATE_LIMITED)
        val after=policy().apply {restoreBudget(before.budget())}
        now+=3_599_999;assertFalse(after.claim("post-2","v1",false))
        now+=2;assertTrue(after.claim("post-2","v1",false))
        val old=ArchiveBudget(listOf(now-7_200_000),now-7_200_000,now-3_600_000)
        val clean=policy().apply {restoreBudget(old)}
        assertTrue(clean.budget().starts.isEmpty())
        assertTrue(clean.claim("post-3","v1",false))
    }
    @Test fun corruptedBudgetDatesAndCountsCannotCreateUnboundedPauses() {
        for(bad in listOf(
            ArchiveBudget(listOf(now+1),now+1,0),
            ArchiveBudget(listOf(-1),0,0),
            ArchiveBudget(List(7){now-it*30_000},now,0),
            ArchiveBudget(listOf(now,now),now,0),
            ArchiveBudget(listOf(now),now-1,0),
            ArchiveBudget(emptyList(),now,-1)
        )) {
            val p=policy();p.restoreBudget(bad)
            assertTrue(p.claim("post-1","v1",false))
        }
        val capped=policy().apply {restoreBudget(ArchiveBudget(emptyList(),0,Long.MAX_VALUE))}
        assertEquals(now+3_600_000,capped.budget().blockedUntil)
        now+=3_600_001
        assertTrue(capped.claim("post-2","v1",false))
    }
    @Test fun restoringCourtesyMetadataNeverClearsAnExistingActiveRequestOrPause() {
        val p=policy();assertTrue(p.claim("post-1","v1",false))
        p.restoreBudget(ArchiveBudget())
        assertFalse(p.claim("post-2","v1",false))
        finish(p,outcome=ArchiveCheckOutcome.RATE_LIMITED)
        p.restoreBudget(ArchiveBudget())
        now+=30_000
        assertFalse(p.claim("post-2","v1",false))
    }
}
