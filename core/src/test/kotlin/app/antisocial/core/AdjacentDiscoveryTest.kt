package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AdjacentDiscoveryTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val seed=Profile(routes.target(Platform.X,"graph_fixture"),"Public workshop")
    private fun person(handle:String)= """{"screen_name":"$handle","name":"Workshop $handle","id":"$handle","following":30}"""
    private fun list(vararg people:String,cursor:String="")="""{"code":200,"results":[${people.joinToString(","){person(it)}}],"cursor":"$cursor"}"""
    private fun document(text:String,url:String)=AdjacentDocument(Payload(text,url,"application/json",200))
    @Test fun anEmptyLastPageDoesNotSuppressTheWorkingFirstPageOnTheNextSearch()=runBlocking {
        val learning=AdaptiveRouting();var firstPages=0
        suspend fun collect():AdjacentSnapshot {
            val session=AdjacentSession(seed,routes,learning=learning,read={endpoint->
                val body=if(endpoint.url.contains("/graph_fixture/following")&&!endpoint.url.contains("cursor=")) {
                    firstPages++;list("useful_friend",cursor="last-page")
                }else list()
                document(body,endpoint.url)
            })
            var result=session.loadNext();repeat(5){if(result.hasMore)result=session.loadNext()};return result
        }
        assertTrue(collect().cards.any {it.profile.target.identity=="useful_friend"})
        assertTrue("A valid empty continuation must not hide data on the next search",collect().cards.any {it.profile.target.identity=="useful_friend"})
        assertEquals(2,firstPages)
    }
    @Test fun publicReferencesAreConnectionsNotOwnershipAndIncludeCommunitiesAndForwardSources() {
        val other=routes.target(Platform.X,"other_fixture");val community=routes.target(Platform.REDDIT,"r/Android")
        val post=Post("1",Platform.X,"https://x.com/graph_fixture/status/123","Workshop","Ask @other_fixture",links=listOf(community.canonicalUrl),publicRelations=listOf(PublicRelation(PublicRelationKind.FORWARD,other.canonicalUrl,"Other")))
        val values=AdjacentDiscovery.observed(seed,Page(listOf(post),profile=seed.copy(links=listOf("https://www.instagram.com/same_handle"))),routes,123)
        assertTrue(values.filter {it.evidence.kind==AdjacentKind.MENTIONED}.none {it.profile.displayNameSupplied});assertTrue(values.any {it.profile.target==community});assertTrue(values.any {it.evidence.kind==AdjacentKind.FORWARDED})
        assertTrue(values.none {it.profile.target.platform==Platform.INSTAGRAM});assertTrue(values.all {it.evidence.checkedAt==123L})
    }
    @Test fun sharedFollowersCountDistinctObserversAndRepeatedPagesCannotInflateThem()=runBlocking {
        val calls=AtomicInteger();val session=AdjacentSession(seed,routes,read={request->calls.incrementAndGet()
            val text=when {request.url.contains("/graph_fixture/followers")->list("viewer_one","viewer_two")
                request.url.contains("/graph_fixture/following")->list()
                else->list("same_creator","same_creator")}
            document(text,request.url)
        })
        var value=session.loadNext()
        repeat(3){if(value.hasMore)value=session.loadNext()}
        val card=value.cards.single {it.profile.target.identity=="same_creator"}
        val evidence=card.evidence.single {it.kind==AdjacentKind.SHARED_FOLLOWERS}
        assertEquals(2,evidence.count);assertEquals(2,evidence.sampleSize);assertEquals(2,evidence.witnesses.size)
        assertTrue(AdjacentDiscovery.reason(evidence).contains("2 sampled followers"));assertTrue(calls.get()<=6)
    }
    @Test fun aSingleObservedFollowerDoesNotBecomeAnAudienceOverlapClaim()=runBlocking {
        val session=AdjacentSession(seed,routes,read={r->document(when {r.url.contains("/graph_fixture/followers")->list("only_viewer");r.url.contains("/graph_fixture/following")->list();else->list("one_creator")},r.url)})
        val value=session.loadNext();assertTrue(value.cards.isEmpty())
    }
    @Test fun fastFollowUpsStreamBeforeAnIndependentSlowPublisherFinishes()=runBlocking {
        val target=routes.target(Platform.MASTODON,"https://masto.example/@workshop")
        val profile=Profile(target,"Workshop",links=listOf("https://workshop.example/"));var slowFinished=false;var early=false
        val session=AdjacentSession(profile,routes,read={r->
            val body=when {
                r.source.frontend=="discovery-publisher"->{delay(200);slowFinished=true;"<html><title>Website</title></html>"}
                r.url.contains("lookup")->"""{"id":"42","url":"${target.canonicalUrl}","username":"workshop","display_name":"Workshop"}"""
                r.url.contains("endorsements")->"""[{"id":"43","url":"https://masto.example/@friend","username":"friend","display_name":"Useful friend"}]"""
                else->"[]"
            };document(body,r.url)
        })
        val value=session.loadNext {if(it.cards.isNotEmpty()&&!slowFinished)early=true}
        assertTrue("Fast lookup follow-ups were held behind the slow lane",early);assertTrue(value.cards.any {it.profile.name=="Useful friend"})
    }
    @Test fun oneFailedSourcePreservesResultsAndRateLimitCooldown()=runBlocking {
        val bank=AdaptiveRouting();val session=AdjacentSession(seed,routes,learning=bank,read={r->
            if(r.url.contains("followers"))throw SourceFailure(HealthStatus.RATE_LIMITED,"Rate limited")
            document(if(r.url.contains("graph_fixture"))list("useful_friend")else list(),r.url)
        })
        val value=session.loadNext();assertTrue(value.cards.any {it.profile.target.identity=="useful_friend"})
        assertTrue(value.coverage.any {it.status==HealthStatus.RATE_LIMITED});assertTrue(bank.snapshot().any {it.cooldownUntil>0})
    }
    @Test fun cancellationStopsRequestsAndDoesNotPublishAFinalCompletedResult()=runBlocking {
        val publications=mutableListOf<AdjacentSnapshot>();val session=AdjacentSession(seed,routes,read={awaitCancellation()})
        val job=launch {session.loadNext {publications+=it}};yield();delay(20);job.cancelAndJoin()
        assertTrue(publications.none {it.complete});val count=publications.size;delay(20);assertEquals(count,publications.size)
    }
    @Test fun routeAndCursorWorkStayBoundedAndPublishedRowsKeepTheirPositions()=runBlocking {
        val active=AtomicInteger();var peak=0;var calls=0
        val session=AdjacentSession(seed,routes,read={r->val n=active.incrementAndGet();peak=maxOf(n,peak);calls++
            try {delay(2);document(list("friend_one","friend_two",cursor="repeated-cursor"),r.url)}finally {active.decrementAndGet()}
        })
        var value=session.loadNext();val first=value.cards.map {it.key};repeat(10){if(value.hasMore)value=session.loadNext()}
        assertTrue(peak<=2);assertTrue(calls<=AdjacentDiscovery.MAX_REQUESTS);assertEquals(first,value.cards.take(first.size).map {it.key});assertFalse(value.hasMore)
    }
    @Test fun opmlKeepsCategoriesAndRefusesExternalEntitiesAndActionLinks() {
        val payload=Payload("""<opml version="2.0"><body><outline text="Criticism"><outline text="Independent writer" xmlUrl="https://writer.example/feed.xml"/></outline></body></opml>""","https://publisher.example/blogroll.opml","text/x-opml",200)
        assertEquals(Triple("https://writer.example/feed.xml","Independent writer","Criticism"),PublicRelations.opml(payload).single())
        assertTrue(runCatching {PublicRelations.opml(payload.copy(text="<!DOCTYPE x SYSTEM 'https://evil.example/x'>"+payload.text))}.isFailure)
        val links=PublicRelations.publisher(Payload("""<link rel="alternate" type="application/rss+xml" href="/unsubscribe?token=sample"><link rel="blogroll" type="text/x-opml" href="/reading.opml"><script>fetch('/executed')</script>""","https://publisher.example/","text/html",200))
        assertTrue(links.feeds.isEmpty());assertEquals(listOf("https://publisher.example/reading.opml"),links.blogrolls)
    }
    @Test fun headerDeclaredFeedsAndNamedRecommendationsSurviveWashing() {
        val payload=Payload("<html><meta name=generator content='Ghost 6'><a rel=colleague href='https://friend.example/'>Repair writer</a></html>","https://publisher.example/","text/html",200)
        val found=PublicRelations.publisher(payload,mapOf("Link" to listOf("</feed.xml>; rel=alternate; type=\"application/rss+xml\"")))
        assertEquals("https://publisher.example/feed.xml",found.feeds.single().first);assertEquals("Repair writer",found.recommendations.single().second);assertTrue(found.ghost)
    }
    @Test fun scopedStructuredCitationsBecomeSignalReferencesButFooterOwnersDoNot() {
        val html="""<script type="application/ld+json">{"@graph":[{"@type":"NewsArticle","url":"https://publisher.example/report","citation":{"@type":"ScholarlyArticle","url":"https://doi.org/10.1234/repair","name":"Battery findings"}},{"@type":"Organization","citation":"https://evil.example/footer"},{"@type":"NewsArticle","url":"https://other.example/unrelated","citation":"https://evil.example/unrelated"}]}</script>"""
        val found=PublicRelations.citations(org.jsoup.Jsoup.parse(html),"https://publisher.example/report")
        assertEquals(listOf("https://doi.org/10.1234/repair"),found.map {it.url});assertEquals("Battery findings",found.single().label)
    }
    @Test fun malformedDeepJsonAndEmptyLoginResponsesNeverBecomeHealthyGraphs() {
        assertTrue(runCatching {PublicRelations.json("[".repeat(34)+"0"+"]".repeat(34))}.isFailure)
        val request=AdjacentSources.plan(seed,routes).first();val ep=request.endpoints.first()
        assertTrue(runCatching {AdjacentSources.parse(request,ep,document("<html>Sign in</html>",ep.url),routes,123)}.isFailure)
        assertTrue(runCatching {AdjacentSources.parse(request,ep,document("{}",ep.url),routes,123)}.isFailure)
    }
    @Test fun capturedRelationshipMetadataSurvivesTheOfflineSnapshot() {
        val post=Post("one",Platform.TELEGRAM,"https://t.me/public_channel/1","Public channel","A useful forwarded post",publicRelations=listOf(PublicRelation(PublicRelationKind.FORWARD,"https://t.me/source_channel","Source")))
        assertEquals(post.publicRelations,PostSnapshot.from(post).asPost(post.copy(publicRelations=null)).publicRelations)
    }
    @Test fun hostilePublisherParsingIsContainedInsideItsIdentityLane()=runBlocking {
        val failures=mutableListOf<Throwable>();val handler=CoroutineExceptionHandler {_,error->failures+=error}
        val known=seed.copy(links=listOf("https://workshop.example/"))
        val engine=AccountDiscoveryEngine(routes,profile={known},web={url,_->Payload(if(url.contains("workshop.example"))"<div>".repeat(600)else "{}",url,"text/html",200)})
        val value=withContext(handler){engine.discover(seed.target,known)}
        assertTrue(value.candidates.any {it.target==seed.target});assertTrue("Website parser failure escaped into the reader",failures.isEmpty())
    }
    @Test fun blueskyUsesTheActualHandleRatherThanTheRoutingPathPrefix() {
        val profile=Profile(routes.target(Platform.BLUESKY,"workshop.test"),"Workshop")
        val q=AdjacentSources.plan(profile,routes).first();val ep=q.endpoints.first()
        assertTrue(ep.url.contains("actor=workshop.test"));assertFalse(ep.url.contains("profile%2F"))
        val body="""{"subject":{"did":"did:plc:seed","handle":"workshop.test"},"follows":[{"did":"did:plc:other","handle":"other.test","displayName":"Other workshop"}]}"""
        val batch=AdjacentSources.parse(q,ep,document(body,ep.url),routes,1000)
        assertEquals("Other workshop",batch.observations.single().profile.name)
        assertEquals("did:plc:other",batch.observations.single().profile.persistentId)
    }
    @Test fun caselessPlatformHandlesDoNotRecommendTheRootBackToItself()=runBlocking {
        assertEquals(AdjacentDiscovery.key(seed.target),AdjacentDiscovery.key(routes.target(Platform.X,"GRAPH_FIXTURE")))
        val session=AdjacentSession(seed,routes,read={r->document(list("GRAPH_FIXTURE"),r.url)})
        assertTrue(session.loadNext().cards.isEmpty())
        assertNotEquals(AdjacentDiscovery.key(RssSources.target("https://site.example/Feed")),AdjacentDiscovery.key(RssSources.target("https://site.example/feed")))
    }
    @Test fun independentPublisherRequestsAreNotCollapsedIntoOneQueueEntry() {
        val p=Profile(RssSources.target("https://writer.example/feed"),"Writer")
        val q=AdjacentRequest(p,operation=AdjacentOperation.RECOMMENDATIONS,endpoints=listOf(AdjacentEndpoint(Source(Platform.RSS,"discovery-publisher","https://writer.example"),"https://writer.example/.well-known/recommendations.json")))
        val body="""[{"url":"https://one.example/"},{"url":"https://two.example/"}]"""
        val batch=AdjacentSources.parse(q,q.endpoints.single(),document(body,q.endpoints.single().url),routes,123)
        assertEquals(2,batch.next.map {it.key}.distinct().size)
    }
    @Test fun federationPaginationCannotChangeTheAccountOrHost() {
        val p=Profile(routes.target(Platform.MASTODON,"https://masto.example/@workshop"),"Workshop")
        val lookup=AdjacentSources.plan(p,routes).first();val ep=lookup.endpoints.single()
        val batch=AdjacentSources.parse(lookup,ep,document("""{"id":"42","url":"https://masto.example/@workshop"}""",ep.url),routes,123)
        val q=batch.next.first {it.operation==AdjacentOperation.FOLLOWING};val endpoint=q.endpoints.single()
        fun parse(next:String)=AdjacentSources.parse(q,endpoint,document("[]",endpoint.url).copy(headers=mapOf("Link" to listOf("<$next>; rel=\"next\""))),routes,124)
        assertEquals("abc",parse("https://masto.example/api/v1/accounts/42/following?max_id=abc").next.single().cursor)
        assertTrue(parse("https://evil.example/api/v1/accounts/42/following?max_id=abc").next.isEmpty())
        assertTrue(parse("https://masto.example/api/v1/accounts/99/following?max_id=abc").next.isEmpty())
    }
    @Test fun feedRecommendationsAreScopedAndNeverFetchEnclosures() {
        val xml="""<?xml version="1.0"?><rss xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:blogChannel="http://backend.userland.com/blogChannelModule"><channel><podcast:podroll><podcast:remoteItem feedUrl="https://friend.example/feed" title="Practical repairs"/></podcast:podroll><blogChannel:blogRoll>https://writer.example/reading.opml</blogChannel:blogRoll><item><podcast:podroll><podcast:remoteItem feedUrl="https://ignored.example/feed"/></podcast:podroll><enclosure url="https://ignored.example/audio.mp3"/></item></channel></rss>"""
        val result=PublicRelations.readingFeed(Payload(xml,"https://writer.example/feed","application/rss+xml",200))!!
        assertEquals(listOf("https://friend.example/feed" to "Practical repairs"),result.feeds)
        assertEquals(listOf("https://writer.example/reading.opml"),result.blogrolls)
        val q=AdjacentSources.plan(Profile(RssSources.target("https://writer.example/feed"),"Writer"),routes).first()
        val opml="""<?xml version="1.0"?><opml><body><outline xmlUrl="https://friend.example/feed" text="Friend"/></body></opml>"""
        assertEquals(1,AdjacentSources.parse(q,q.endpoints.first(),document(opml,q.endpoints.first().url),routes,123).observations.size)
    }
    @Test fun ambiguousNoUrlArticlesDoNotDonateUnrelatedCitations() {
        val html="""<script type="application/ld+json">[{"@type":"NewsArticle","citation":"https://one.example/report"},{"@type":"NewsArticle","citation":"https://two.example/report"}]</script>"""
        assertTrue(PublicRelations.citations(org.jsoup.Jsoup.parse(html),"https://publisher.example/current").isEmpty())
    }

    @Test fun cachedPublisherDeclarationsPreserveTheirAgeAndDoNotTrainNetworkLatency()=runBlocking {
        val p=Profile(RssSources.target("https://writer.example/"),"Writer");val bank=AdaptiveRouting()
        val warm=AccountLinkage.website(Payload("<meta name=generator content='Ghost 6'>","https://writer.example/","text/html",200),routes,123,
            mapOf("Link" to listOf("</feed.xml>; rel=alternate; type=\"application/rss+xml\"")))
        assertTrue(warm.publisher!!.ghost);assertEquals("https://writer.example/feed.xml",warm.publisher.feeds.single().first)
        val session=AdjacentSession(p,routes,learning=bank,read={r->
            if(r.url.contains("recommendations.json"))document("""[{"url":"https://friend.example/"}]""",r.url)
            else AdjacentDocument(Payload("",warm.sourceUrl!!,"text/html",200),cachedPublisher=warm.publisher,checkedAt=warm.checkedAt)
        })
        val result=session.loadNext()
        assertTrue(result.coverage.any {it.checkedAt==123L&&it.detail.startsWith("Cached publisher")})
        // Only the real recommendations request is eligible for route learning.
        assertEquals(1,bank.snapshot().size)
    }

    @Test fun anExactProtocolIdentityDoesNotRecommendTheSeedUnderItsNewHandle()=runBlocking {
        val p=Profile(routes.target(Platform.BLUESKY,"did:plc:seed"),"Workshop",persistentId="did:plc:seed")
        val other=Profile(routes.target(Platform.BLUESKY,"new-name.test"),"Workshop",persistentId="did:plc:seed")
        val session=AdjacentSession(p,routes,read={throw IllegalStateException("No request")},initial=listOf(AdjacentObservation(other,AdjacentEvidence(AdjacentKind.FEATURED,p.target,p.target.canonicalUrl,123))))
        assertTrue(session.current().cards.isEmpty())
        assertEquals(AdjacentDiscovery.key(routes.target(Platform.MASTODON,"https://masto.example/@Workshop")),AdjacentDiscovery.key(routes.target(Platform.MASTODON,"https://masto.example/@workshop")))
    }

    @Test fun websiteIdentityGroupsDoNotConfuseSeparatePeopleDescribedOnOnePage() {
        val html="""<script type="application/ld+json">{"@graph":[{"@type":"Person","name":"Alex Repair","sameAs":["https://x.com/alex_fixture","https://bsky.app/profile/alex.bsky.social"]},{"@type":"Person","name":"Alex Repair","sameAs":["https://www.instagram.com/another_alex"]}]}</script>"""
        val found=AccountLinkage.website(Payload(html,"https://workshop.example/","text/html",200),routes,123)
        val x=found.nameHints.single {it.target.platform==Platform.X};val b=found.nameHints.single {it.target.platform==Platform.BLUESKY};val i=found.nameHints.single {it.target.platform==Platform.INSTAGRAM}
        assertNotNull(x.identityGroup);assertEquals(x.identityGroup,b.identityGroup);assertNotEquals(x.identityGroup,i.identityGroup)
    }

}
