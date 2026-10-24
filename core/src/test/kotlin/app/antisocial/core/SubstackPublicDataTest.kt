package app.antisocial.core

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubstackPublicDataTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val user=SubstackPublicData.account("https://substack.com/@fixture")!!
    private val pub=SubstackPublicData.account("https://workshop.substack.com")!!
    private fun publication(id:Int=7,name:String="workshop")="""{"id":$id,"subdomain":"$name","name":"Workshop Notes","author_id":42,"author_handle":"fixture","custom_domain":"notes.example.org","custom_domain_optional":false}"""
    private fun profile(extra:String="")="""{"id":42,"handle":"fixture","name":"Community Workshop","bio":"Practical notes","userLinks":[{"url":"https://x.com/workshop_news"}],"primaryPublication":${publication()},"subscriptions":[{"user_id":42,"visibility":"public","publication":${publication(8,"peer")}}, {"user_id":42,"visibility":"private","publication":${publication(9,"private")}}]$extra}"""
    private fun payload(text:String,status:Int=200)=Payload(text,"https://proxy.pullsy.online/","application/json",status)
    @Test fun usersPublicationsAndArticlesHaveDifferentIdentities() {
        assertEquals("substack",PublicAccounts.parse(user.url,routes)!!.provider)
        assertEquals("user:42",SubstackPublicData.account("https://substack.com/profile/42-fixture")!!.id)
        for(url in listOf("https://substack.com","https://substack.com/@fixture/note/c-123","https://workshop.substack.com/p/article","https://support.substack.com","https://evil.example/substack.com/@fixture","http://substack.com/@fixture"))assertNull(url,SubstackPublicData.account(url))
        assertEquals(pub.url,PublicAccounts.from(RssSources.target(pub.url+"/feed")).url)
        assertNull(PublicAccounts.target(pub,routes))
    }
    @Test fun ownedPublicationFeedsAndExternalIdentitiesDoNotIncludeThePublicReadingList() {
        val value=SubstackPublicData.profile(user,payload(profile()))
        assertEquals("user:42",value.account.id)
        assertEquals("publication:7",value.publications.single().id)
        assertEquals("https://notes.example.org/feed",value.publications.single().readerUrl)
        assertEquals("publication:8",value.readingList.single().id)
        val links=SubstackPublicData.webLinks(value,routes,123)
        assertEquals(setOf(Platform.RSS,Platform.X),links.accounts.map {it.target.platform}.toSet())
        assertEquals("Workshop Notes",links.nameHints.single().name)
        assertTrue(links.publicAccounts.orEmpty().none {it.id=="publication:8"})
        val bridge=SubstackPublicData.bridge(value,123)
        assertEquals(AdjacentKind.FOLLOWS,bridge.neighbors.single().kind)
        assertEquals("Publicly listed subscription",bridge.neighbors.single().detail)
        assertTrue(bridge.neighbors.none {it.account.url.contains("private")})
        assertEquals(Platform.RSS,PublicAccounts.target(value.publications.single(),routes)!!.platform)
    }
    @Test fun swappedProfilesUnboundTwitterAndUnexpectedPrimaryOwnersAreRejected() {
        assertTrue(runCatching {SubstackPublicData.profile(user,payload(profile().replace("\"handle\":\"fixture\"","\"handle\":\"someoneelse\"")))}.isFailure)
        assertTrue(runCatching {SubstackPublicData.profile(user.copy(id="user:99"),payload(profile()))}.isFailure)
        val value=SubstackPublicData.profile(user,payload(profile(",\"twitterAccount\":{\"user_id\":99,\"screen_name\":\"unrelated\"}").replace("\"author_id\":42","\"author_id\":99")))
        assertTrue(value.publications.isEmpty());assertFalse(value.links.any {it.endsWith("unrelated")})
    }
    @Test fun boundedStrictJsonSkipsTrackingAndDoesNotAcceptInjectedTrailingScripts() {
        val value=SubstackPublicData.profile(user,payload(profile(",\"anonymousSession\":\"discard-me\",\"opaque\":[1,2,3]")))
        assertFalse(value.toString().contains("discard-me"))
        for(body in listOf(profile()+"<script>bad()</script>","{\"id\":42,\"handle\":\"fixture\",\"opaque\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}"," ".repeat(SubstackPublicData.MAX_DOCUMENT+1)))
            assertTrue(runCatching {SubstackPublicData.profile(user,payload(body))}.isFailure)
        assertTrue(runCatching {SubstackPublicData.profile(user,payload(profile(),403))}.isFailure)
    }
    @Test fun literalPublicationDataCanBeReadWithoutEvaluatingThePage() {
        val html="""<script id="preloads-json" type="application/json">{"pub":${publication()}}</script><script>window._preloads=JSON.parse(document.getElementById('preloads-json').textContent);bad();</script>"""
        val value=SubstackPublicData.publication(pub,payload(html))
        assertEquals("publication:7",value.account.id);assertEquals(listOf(user.url),value.links)
        assertTrue(runCatching {SubstackPublicData.publication(pub,payload(html,404))}.isFailure)
        assertTrue(runCatching {SubstackPublicData.publication(pub,payload(html.replace("\"workshop\"","\"someoneelse\"")))}.isFailure)
    }
    @Test fun recommendationEdgesMustBelongToTheRequestedPublication() {
        val owner=SubstackPublicData.publication(pub,payload("""{"publication":${publication()}}""")).account
        fun row(ownerId:Int,peerId:Int)="""{"recommending_publication_id":$ownerId,"recommended_publication_id":$peerId,"recommendedPublication":${publication(peerId,"peer")}}"""
        val peers=SubstackPublicData.recommendations(owner,payload("[${row(7,8)},${row(99,9)}]"))
        assertEquals("publication:8",peers.single().account.id);assertEquals(AdjacentKind.FEATURED,peers.single().kind)
    }
    @Test fun failedAlternateAdvancesToOriginalAndDisabledOriginalsNeverLeak()=runBlocking {
        val calls=mutableListOf<Pair<String,Boolean>>()
        val reader=SubstackResolver(read={url,original->calls+=url to original;if(!original)error("Proxy offline")else payload(profile())})
        assertEquals("user:42",reader.identity(user).account.id)
        assertEquals(listOf(false,true),calls.map {it.second})
        calls.clear()
        val privateReader=SubstackResolver(read={url,original->calls+=url to original;error("Proxy offline")},originals=false)
        assertTrue(runCatching {privateReader.identity(user)}.isFailure)
        assertEquals(listOf(false),calls.map {it.second})
    }
    @Test fun publicProxyIdentityCanReturnToAReaderFeedWithoutFetchingAnOriginal()=runBlocking {
        val calls=mutableListOf<String>()
        val result=SubstackResolver(read={url,original->assertFalse(original);calls+=url;payload(profile())},originals=false).bridge(user,false)
        assertEquals(1,calls.size);assertTrue(calls.single().startsWith("https://proxy.pullsy.online/"))
        assertEquals("publication:7",result.publications.single().id);assertTrue(result.neighbors.isEmpty())
    }
    @Test fun aPublicationSurvivesFailedOptionalAuthorAndRecommendationReads()=runBlocking {
        var calls=0
        val resolver=SubstackResolver(read={_,_->calls++;if(calls==1)payload("""{"publication":${publication()}}""")else error("Offline")})
        val result=resolver.bridge(pub,true)
        assertEquals("publication:7",result.account.id);assertEquals("https://notes.example.org/feed",result.publications.single().readerUrl)
        assertTrue(calls<=5)
    }
    @Test fun anAuthorsReadingListRemainsAttachedToTheirProfileRatherThanTheirPublication()=runBlocking {
        val result=SubstackResolver(read={url,_->when {
            "substacktools" in url->payload("""{"publication":${publication()}}""")
            "public_profile" in java.net.URLDecoder.decode(url,"UTF-8")->payload(profile())
            else->payload("[]")
        }}).bridge(pub,true)
        assertTrue(result.links.contains(user.url));assertTrue(result.links.contains("https://x.com/workshop_news"))
        assertTrue(result.neighbors.none {it.kind==AdjacentKind.FOLLOWS})
    }
    @Test fun ordinarySubscribeLocalFollowsSubstackIdentityRatherThanFlatteningItsHtml()=runBlocking {
        var identityReads=0
        val known=Profile(routes.target(Platform.X,"workshop_news"),"Workshop",links=listOf(user.url))
        val engine=AccountDiscoveryEngine(routes,profile={null},web={_,_->error("Unrelated route unavailable")},
            publicIdentity={identityReads++;SubstackPublicData.webLinks(SubstackPublicData.profile(it,payload(profile())),routes,123)})
        val result=engine.discover(known.target,known)
        assertEquals(1,identityReads)
        assertTrue(result.candidates.any {it.target.canonicalUrl=="https://notes.example.org/feed"})
        assertTrue(result.candidates.none {it.target.canonicalUrl.contains("peer.substack")})
    }
    @Test fun cancellationStopsAnInFlightProxyWithoutLaunchingAnOriginal()=runBlocking {
        var calls=0;var stopped=false
        val resolver=SubstackResolver(read={_,_->calls++;try {delay(10_000);payload(profile())}finally {stopped=true}})
        val job=launch {resolver.identity(user)};delay(30);job.cancelAndJoin()
        assertEquals(1,calls);assertTrue(stopped)
    }
    @Test fun aRouteDeadlineIsAnOrdinaryFailureAndCanAdvanceToAnAllowedOriginal()=runBlocking {
        val calls=mutableListOf<Boolean>()
        val resolver=SubstackResolver(read={_,original->calls+=original;if(!original)delay(200);payload(profile())},timeoutMs=20)
        assertEquals("user:42",resolver.identity(user).account.id);assertEquals(listOf(false,true),calls)
        val unavailable=SubstackResolver(read={_,_->delay(200);payload(profile())},originals=false,timeoutMs=20)
        val failure=runCatching {unavailable.identity(user)}.exceptionOrNull()
        assertTrue(failure is SourceFailure);assertFalse(failure is CancellationException)
        currentCoroutineContext().ensureActive()
    }
    @Test fun aCancelledParentDeadlineNeverTrainsAnOutageOrLaunchesFallback()=runBlocking {
        val learning=AdaptiveRouting();var calls=0
        val resolver=SubstackResolver(read={_,_->calls++;delay(200);payload(profile())},learning=learning,timeoutMs=1000)
        assertTrue(runCatching {withTimeout(20){resolver.identity(user)}}.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1,calls);assertTrue(learning.snapshot().isEmpty())
    }
}
