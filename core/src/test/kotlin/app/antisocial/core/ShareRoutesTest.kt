/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ShareRoutesTest {
    private val cleaner=UrlCleaner(JsonParser.parseString("{\"providers\":{}}").asJsonObject)
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,cleaner)
    private val source=Source(Platform.X,"nitter","https://nitter.example")
    private val now=1_792_000_000_000L
    private val primary=Post("123",Platform.X,"https://x.com/alice/status/123","Alice","A complete original caption about replacing a laptop battery safely.",publishedAt=now)
    init {routes.registerPathwayAliases(listOf(source))}
    private fun fetched(post: Post=primary,final: String="https://nitter.example/alice/status/123")=post.copy(provenance=Provenance(source,now,final))
    private fun resolver(memory: ShareRouteMemory=MemoryShareRoutes(),allowed: ()->Boolean={true},mode: ()->Boolean={false},clock: ()->Long={now},reader: suspend (ShareRouteCandidate)->ShareRouteCheck)=ShareRouteResolver(routes,cleaner,reader,memory,allowed,mode,clock)

    @Test fun aliveOriginalIsCheckedOnceAndFreshProofAvoidsFurtherRequests(): Unit=runBlocking {
        val memory=MemoryShareRoutes();var reads=0
        val resolver=resolver(memory){reads++;ShareRouteCheck(ShareRouteStatus.READABLE,it.url)}
        repeat(2){val result=resolver.resolve(fetched()) as ShareRouteResult.Available;assertEquals(primary.canonicalUrl,result.url);assertTrue(result.verified);assertEquals(ShareRouteKind.ORIGINAL,result.kind)}
        assertEquals(1,reads)
    }
    @Test fun missingOriginalChoosesExactLiveFrontendWithoutRewritingItBack(): Unit=runBlocking {
        val checked=mutableListOf<String>()
        val resolver=resolver {candidate->checked+=candidate.url;ShareRouteCheck(if(candidate.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.READABLE,candidate.url)}
        val result=resolver.resolve(fetched()) as ShareRouteResult.Available
        assertEquals("https://nitter.example/alice/status/123",result.url)
        assertEquals(primary.canonicalUrl,result.originalUrl);assertEquals(primary.canonicalUrl,result.editionUrl)
        assertEquals(ShareRouteKind.FRONTEND,result.kind);assertTrue(result.verified)
        assertEquals(primary.canonicalUrl,OriginalLinks(routes,cleaner).url(result.url))
        assertEquals(2,checked.size)
    }
    @Test fun confirmedMissingNeverReturnsOriginalWhenNoReadableCopyExists(): Unit=runBlocking {
        val result=resolver {ShareRouteCheck(ShareRouteStatus.NOT_FOUND)}.resolve(primary)
        assertTrue(result is ShareRouteResult.Unavailable)
    }
    @Test fun networkAndLoginFailuresDoNotMeanDeletedAndOfflineCopyKeepsAuthenticUrl(): Unit=runBlocking {
        for(status in listOf(ShareRouteStatus.UNVERIFIED,ShareRouteStatus.LOGIN_REQUIRED,ShareRouteStatus.EMPTY)) {
            val result=resolver {ShareRouteCheck(status)}.resolve(primary) as ShareRouteResult.Available
            assertEquals(primary.canonicalUrl,result.url);assertFalse(result.verified)
            assertTrue(result.notice!!.contains("could not be verified"));assertTrue(result.notice!!.contains("does not establish"))
        }
    }
    @Test fun deniedOriginalIsNeverContactedAndOpenRequiresAnAlternative(): Unit=runBlocking {
        var reads=0
        val resolver=resolver(allowed={false}) {candidate->reads++;assertNotEquals(primary.canonicalUrl,candidate.url);ShareRouteCheck(ShareRouteStatus.READABLE,candidate.url)}
        val chosen=resolver.resolve(fetched(),action=ShareLinkAction.OPEN) as ShareRouteResult.Available
        assertEquals(ShareRouteKind.FRONTEND,chosen.kind);assertEquals(1,reads)
        val absent=resolver(allowed={false}) {error("Original must not be requested")}
        assertTrue(absent.resolve(primary,action=ShareLinkAction.OPEN) is ShareRouteResult.Unavailable)
        assertFalse((absent.resolve(primary,action=ShareLinkAction.COPY) as ShareRouteResult.Available).verified)
    }
    @Test fun freshExactHtmlProofCanBeUsedButFeedAndApiProvenanceCannot(): Unit=runBlocking {
        var reads=0
        val first=resolver(allowed={false}) {reads++;ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
        val post=fetched();first.remember(Page(listOf(post),provenance=post.provenance))
        assertTrue((first.resolve(post) as ShareRouteResult.Available).verified);assertEquals(0,reads)
        for(final in listOf("https://nitter.example/alice/rss","https://nitter.example/alice/status/123/rss","https://nitter.example/api/status/123")) {
            var checked=0
            val next=resolver(allowed={false}) {checked++;ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
            val feed=fetched(final=final);next.remember(Page(listOf(feed),provenance=feed.provenance))
            assertFalse((next.resolve(feed) as ShareRouteResult.Available).verified);assertEquals(1,checked)
        }
    }
    @Test fun cachedAndStaleProvenanceDoNotCertifyALiveLink(): Unit=runBlocking {
        for(provenance in listOf(Provenance(source,now,"https://nitter.example/alice/status/123",cached=true),Provenance(source,now-16*60_000,"https://nitter.example/alice/status/123"))) {
            var reads=0;val resolver=resolver(allowed={false}){reads++;ShareRouteCheck(ShareRouteStatus.UNVERIFIED)}
            val post=primary.copy(provenance=provenance);resolver.remember(Page(listOf(post),provenance=provenance))
            resolver.resolve(post);assertEquals(1,reads)
        }
    }
    @Test fun newerMissingProofOverridesOlderPositiveAcrossConnectionModes(): Unit=runBlocking {
        val memory=MemoryShareRoutes()
        memory.put(ShareRouteEvidence(primary.canonicalUrl,primary.canonicalUrl,false,ShareRouteStatus.READABLE,now-1000))
        memory.put(ShareRouteEvidence(primary.canonicalUrl,primary.canonicalUrl,true,ShareRouteStatus.NOT_FOUND,now))
        val resolver=resolver(memory){error("Fresh missing proof should not cause a repeat request")}
        assertTrue(resolver.resolve(primary) is ShareRouteResult.Unavailable)
        memory.put(ShareRouteEvidence(primary.canonicalUrl,primary.canonicalUrl,false,ShareRouteStatus.READABLE,now+1))
        val recovered=resolver(memory,clock={now+1}){error("Fresh readable proof should be reused")}.resolve(primary) as ShareRouteResult.Available
        assertTrue(recovered.verified);assertEquals(primary.canonicalUrl,recovered.url)
    }
    @Test fun expiredMissingProofCanBeRecheckedAndRestored(): Unit=runBlocking {
        val memory=MemoryShareRoutes();var reads=0;var time=now
        val resolver=resolver(memory,clock={time}) {reads++;ShareRouteCheck(if(reads==1)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.READABLE,it.url)}
        assertTrue(resolver.resolve(primary) is ShareRouteResult.Unavailable)
        time+=24*60*60_000L+1
        assertTrue((resolver.resolve(primary) as ShareRouteResult.Available).verified);assertEquals(2,reads)
    }
    @Test fun exactArchiveIsEligibleButAnotherPostArchiveIsNeverSubstituted(): Unit=runBlocking {
        val replay="https://web.archive.org/web/20250101000000id_/https://x.com/alice/status/123"
        val post=primary.copy(recoveredArchive=ArchiveRecovery("Internet Archive",1,replay))
        val resolver=resolver {ShareRouteCheck(if(it.kind==ShareRouteKind.ARCHIVE)ShareRouteStatus.READABLE else ShareRouteStatus.NOT_FOUND,it.url)}
        val result=resolver.resolve(post) as ShareRouteResult.Available
        assertEquals(replay,result.url);assertEquals(ShareRouteKind.ARCHIVE,result.kind)
        val wrong=post.copy(recoveredArchive=post.recoveredArchive!!.copy(archiveUrl=replay.replace("123","999")))
        assertTrue(resolver.resolve(wrong) is ShareRouteResult.Unavailable)
    }
    @Test fun onlyACompleteIdenticalRetainedSiblingCanReplaceTheMissingEdition(): Unit=runBlocking {
        val sibling=primary.copy(platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/alice.test/post/abc",publishedAt=now-1000)
        val resolver=resolver {candidate->ShareRouteCheck(if(candidate.post.platform==Platform.BLUESKY)ShareRouteStatus.READABLE else ShareRouteStatus.NOT_FOUND,candidate.url)}
        val result=resolver.resolve(primary,listOf(sibling)) as ShareRouteResult.Available
        assertEquals(ShareRouteKind.CROSS_POST,result.kind);assertEquals(sibling.canonicalUrl,result.url)
        assertEquals(primary.canonicalUrl,result.originalUrl);assertEquals(sibling.canonicalUrl,result.editionUrl)
        for(other in listOf(sibling.copy(body=sibling.body+" Additional platform context."),sibling.copy(publishedAt=null),sibling.copy(parentId="other"),sibling.copy(media=listOf(Media("https://images.example/different.jpg"))))) {
            assertTrue(resolver.resolve(primary,listOf(other)) is ShareRouteResult.Unavailable)
        }
    }
    @Test fun actionNeverReadsMoreThanThreeDiscoveredCandidates(): Unit=runBlocking {
        var reads=0
        val replay="https://web.archive.org/web/20250101000000id_/https://x.com/alice/status/123"
        val post=fetched().copy(recoveredArchive=ArchiveRecovery("Internet Archive",1,replay))
        val extra=primary.copy(platform=Platform.BLUESKY,canonicalUrl="https://bsky.app/profile/alice.test/post/abc")
        val resolver=resolver {reads++;ShareRouteCheck(if(it.kind==ShareRouteKind.ORIGINAL)ShareRouteStatus.NOT_FOUND else ShareRouteStatus.UNVERIFIED)}
        assertTrue(resolver.resolve(post,listOf(extra)) is ShareRouteResult.Unavailable);assertEquals(3,reads)
    }
    @Test fun cancellationIsNotConvertedToAnOfflineCopy(): Unit=runBlocking {
        val began=CompletableDeferred<Unit>();var completed=false
        val resolver=resolver {began.complete(Unit);awaitCancellation()}
        val job=launch {resolver.resolve(primary);completed=true}
        began.await();job.cancelAndJoin();assertFalse(completed)
    }
    @Test fun unsafeAddressesAreRejectedBeforeAnyReaderCall(): Unit=runBlocking {
        for(url in listOf("https://127.0.0.1/post","https://100.64.0.1/post","https://private.local/post","https://user:password@x.com/alice/status/123","https://x.com/i/flow/login","http://unrecognized.example/read","https://x.com/alice/status/123?access_token=secret")) {
            val result=resolver {error("Unsafe input reached the reader")}.resolve(primary.copy(canonicalUrl=url))
            assertTrue(url,result is ShareRouteResult.Unavailable)
        }
    }
}
