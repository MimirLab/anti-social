package app.antisocial.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DirectPostReaderTest {
    private val target=Target(Platform.X,TargetKind.POST,"NASA/status/123","https://x.com/NASA/status/123")
    private val source=Source(Platform.X,"nitter","https://reader.example")
    private val provenance=Provenance(source,1000,"https://reader.example/NASA/status/123")
    private val root=Post("post:123",Platform.X,target.canonicalUrl,"NASA","Actual current post",provenance=provenance)
    private val preview=root.copy(body="Public preview",preview=true)
    private val reply=Post("post:124",Platform.X,"https://x.com/reader/status/124","Reader","A public reply",parentId=root.id,provenance=provenance)
    private val other=root.copy(id="post:999",canonicalUrl="https://x.com/other/status/999",body="A different post")
    private val recovered=root.copy(body="Older archived text",recoveredArchive=ArchiveRecovery("Wayback",1700000000000,"https://web.archive.org/web/20230101000000id_/https://x.com/NASA/status/123"))
    private val reader=DirectPostReader()

    @Test fun completeLiveContentReturnsImmediatelyWithItsOwnCommentsAndProvenance(): Unit=runBlocking {
        val page=Page(listOf(root,reply,other),provenance=provenance,notice="Live source note")
        val result=reader.read(target,live={page},cached={error("Cache should not be consulted")},recover={error("Archive should not be consulted")},onPartial={error("Complete content is final")},progress={error("No recovery progress expected")})
        assertEquals(listOf(root,reply),result.posts)
        assertSame(provenance,result.provenance);assertEquals("Live source note",result.notice)
    }
    @Test fun completeSavedCopyPrecedesArchiveRequestsAndRetainsSourceMetadata(): Unit=runBlocking {
        val result=reader.read(target,live={throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"A login is required")},cached={Page(listOf(root,reply,other),provenance=provenance)},recover={error("Saved content is sufficient")})
        assertEquals(listOf(root.id,reply.id),result.posts.map {it.id})
        assertTrue(result.provenance!!.cached);assertEquals(source,result.provenance!!.source)
        assertTrue(result.posts.all {it.provenance!!.cached});assertTrue(result.notice!!.contains("saved copy"))
    }
    @Test fun aMatchingPreviewCanPublishWhileExactArchivedContentIsRecovered(): Unit=runBlocking {
        val events=mutableListOf<String>();val partial=mutableListOf<Page>()
        val result=reader.read(target,live={events+="live";Page(listOf(preview,other))},cached={events+="cache";null},recover={events+="archive";Page(listOf(recovered,other),notice="Recovered public archive")},onPartial={events+="preview";partial+=it},progress={events+=it})
        assertEquals(listOf("live","cache","preview","Checking public archives…","archive"),events)
        assertEquals(listOf(preview),partial.single().posts)
        assertEquals(listOf(recovered),result.posts);assertEquals("Recovered public archive",result.notice)
    }
    @Test fun unrelatedLiveCachedOrArchivePostsNeverBecomeTheRequestedPost(): Unit=runBlocking {
        val failure=try {
            reader.read(target,live={Page(listOf(other))},cached={Page(listOf(other.copy(platform=Platform.REDDIT)))},recover={Page(listOf(other.copy(recoveredArchive=recovered.recoveredArchive)))})
            error("Expected exact-post failure")
        }catch(e: SourceFailure){e}
        assertEquals(HealthStatus.EMPTY,failure.status)
        assertTrue(failure.message!!.contains("requested post"));assertTrue(failure.message!!.contains("this exact post"))
    }
    @Test fun anArchiveResultRequiresRecoveryProvenanceAndReadableCompleteContent(): Unit=runBlocking {
        for(candidate in listOf(root,recovered.copy(preview=true),recovered.copy(body="",title="",media=emptyList()))) {
            val result=reader.read(target,live={Page(listOf(preview))},cached={null},recover={Page(listOf(candidate))})
            assertEquals(listOf(preview),result.posts)
            assertTrue(result.notice!!.contains("did not supply a readable capture"))
        }
    }
    @Test fun confirmedNoCaptureAndArchiveOutageRemainDifferentFailures(): Unit=runBlocking {
        suspend fun failure(recover: suspend ()->Page?): SourceFailure=try {
            reader.read(target,live={throw SourceFailure(HealthStatus.UNAVAILABLE,"Current source failed")},cached={null},recover=recover)
            error("Expected failure")
        }catch(e: SourceFailure){e}
        val missing=failure {null}
        assertTrue(missing.message!!.contains("Current source failed"));assertTrue(missing.message!!.contains("no readable capture"))
        val limited=failure {throw SourceFailure(HealthStatus.RATE_LIMITED,"Archive rate limit")}
        assertEquals(HealthStatus.RATE_LIMITED,limited.status)
        assertTrue(limited.message!!.contains("Public archives are unavailable"));assertTrue(limited.message!!.contains("Archive rate limit"))
        assertFalse(limited.message!!.contains("no readable capture"))
    }
    @Test fun remainingPreviewIsLabeledWithBothCurrentAndArchiveLimitations(): Unit=runBlocking {
        val result=reader.read(target,live={Page(listOf(preview),notice="Public preview supplied")},cached={null},recover={throw SourceFailure(HealthStatus.CHALLENGE,"Archive requires a challenge")})
        assertEquals(listOf(preview),result.posts)
        assertTrue(result.notice!!.contains("Public preview supplied"));assertTrue(result.notice!!.contains("Full content is unavailable"))
        assertTrue(result.notice!!.contains("Archive requires a challenge"));assertTrue(result.posts.single().preview)
    }
    @Test fun cancellingAnyStageStopsTheReadWithoutStartingLaterStages(): Unit=runBlocking {
        for(stage in listOf("live","cache","archive","preview")) {
            val entered=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
            suspend fun pause(name: String) {calls+=name;if(stage==name){entered.complete(Unit);awaitCancellation()}}
            val task=async {
                reader.read(target,live={pause("live");Page(if(stage=="preview")listOf(preview)else emptyList())},cached={pause("cache");null},recover={pause("archive");null},onPartial={pause("preview")})
            }
            withTimeout(3000){entered.await()};task.cancelAndJoin()
            val expected=when(stage){"live"->listOf("live");"cache"->listOf("live","cache");"preview"->listOf("live","cache","preview");else->listOf("live","cache","archive")}
            assertEquals(stage,expected,calls)
        }
    }
    @Test fun profilesSearchMediaAndUnstablePostLinksKeepTheirExistingLiveBehavior(): Unit=runBlocking {
        val candidates=listOf(target.copy(kind=TargetKind.PROFILE),target.copy(kind=TargetKind.SEARCH),target.copy(kind=TargetKind.MEDIA),target.copy(canonicalUrl="https://t.co/unknown"),Target(Platform.REDDIT,TargetKind.THREAD,"comment","https://www.reddit.com/r/android/comments/abc/title/def"))
        val page=Page(listOf(other))
        for(candidate in candidates) {
            assertSame(page,reader.read(candidate,live={page},cached={error("No cache fallback for this target")},recover={error("No archive for this target")}))
        }
    }
    @Test fun directThreadsCanRecoverTheSameStablePostIdentity(): Unit=runBlocking {
        val result=reader.read(target.copy(kind=TargetKind.THREAD),live={Page(emptyList())},cached={null},recover={Page(listOf(recovered))})
        assertEquals(listOf(recovered),result.posts)
    }
}
