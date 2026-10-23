package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountLinkageTest {
    private val routes=DarwinRoutes(JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val seed=routes.target(Platform.X,"repair_fixture")
    private val instagram=routes.target(Platform.INSTAGRAM,"workshop_photos")
    private fun payload(text:String,url:String)=Payload(text,url,"text/html",200)
    private fun claim(value:String,rank:String="normal",ended:Boolean=false)="""{"rank":"$rank","mainsnak":{"snaktype":"value","datavalue":{"value":${Gson().toJson(value)}}}}""".let {if(ended)it.dropLast(1)+",\"qualifiers\":{\"P582\":[{}]}}"else it}
    private fun entity(id:String="Q1",handle:String="repair_fixture",rank:String="normal",extra:String="")=""""$id":{"claims":{"P2002":[${claim(handle,rank)}],"P2003":[${claim("workshop_photos")}]$extra}}"""
    private fun document(vararg records:String)="{\"entities\":{${records.joinToString(",")}}}"

    @Test fun websiteAssociationsPreserveTypedIdentityAndIgnoreArticleMentionsAndShareButtons() {
        val html="""<header><a href="https://www.instagram.com/workshop_photos/">Photos</a></header>
            <article><a href="https://x.com/someone_else">Quoted person</a></article>
            <footer><div class="social-share"><a href="https://x.com/share_author">Share</a></div></footer>
            <a rel="me" href="https://bsky.app/profile/workshop.test">Bluesky</a>
            <script type="application/ld+json">{"@type":"Organization","url":"https://workshop.test","sameAs":["https://www.reddit.com/user/repair_notes"]}</script>
            <script type="application/ld+json">{"@type":"NewsArticle","sameAs":["https://x.com/article_subject"]}</script>
            <script>location='https://x.com/executed_script'</script>"""
        val result=AccountLinkage.website(payload(html,"https://workshop.test/"),routes,123)
        assertEquals(setOf(Platform.INSTAGRAM,Platform.BLUESKY,Platform.REDDIT),result.accounts.map {it.target.platform}.toSet())
        assertTrue(result.accounts.all {it.checkedAt==123L&&it.kind==AccountLinkKind.WEBSITE&&it.sourceUrl=="https://workshop.test/"})
        assertTrue(AccountLinkage.website(payload("x".repeat(AccountLinkage.MAX_DOCUMENT+1),"https://workshop.test/"),routes,123).accounts.isEmpty())
    }
    @Test fun anArticleCannotDonateItsPublishersFooterOrAnotherAuthorsAccounts() {
        val html="""<footer><a href="https://x.com/publisher_account">Publisher</a></footer>
            <script type="application/ld+json">{"@graph":[{"@type":"Person","url":"https://publisher.test/authors/other","sameAs":["https://www.instagram.com/other_author"]},{"@type":"Organization","url":"https://publisher.test/","sameAs":["https://x.com/publisher_account"]}]}</script>"""
        assertTrue(AccountLinkage.website(payload(html,"https://publisher.test/news/story"),routes,123).accounts.isEmpty())
    }
    @Test fun properNounHintsBelongOnlyToNamedIdentityStatements() {
        val html="""<header><a href="https://x.com/incidental">Contact</a></header>
            <script type="application/ld+json">{"@type":"Organization","name":"Community Workshop","url":"https://workshop.test/","sameAs":["${instagram.canonicalUrl}"]}</script>
            <script type="application/ld+json">{"@type":"NewsArticle","name":"Another subject","sameAs":["https://x.com/incidental"]}</script>"""
        val result=AccountLinkage.website(payload(html,"https://workshop.test/"),routes,123)
        assertEquals(2,result.accounts.size)
        assertEquals(listOf(AccountNameHint(instagram,"Community Workshop",AccountLinkKind.WEBSITE,"https://workshop.test/",123)),result.nameHints.map {it.copy(identityGroup=null)})
        assertTrue(result.nameHints.single().identityGroup!!.matches(Regex("[a-f0-9]{32}")))
    }
    @Test fun exactDirectoryEntitySuppliesANameFallbackWithoutChangingTheTarget() {
        val data=JsonParser.parseString(document(entity())).asJsonObject
        data["entities"].asJsonObject["Q1"].asJsonObject.add("labels",JsonParser.parseString("""{"en":{"value":"Community Workshop"}}"""))
        val result=AccountLinkage.wikidata(data.toString(),seed,routes,123)
        assertEquals(2,result.nameHints.size)
        assertEquals(setOf(seed,instagram),result.nameHints.map {it.target}.toSet())
        assertTrue(result.nameHints.all {it.name=="Community Workshop"&&it.sourceUrl=="https://www.wikidata.org/wiki/Q1"})
    }
    @Test fun aLinkedBioCannotRenameAnotherPersonButConfirmedBundlesShareObservedNames()=runBlocking {
        val known=Profile(seed,"Community Workshop",links=listOf(instagram.canonicalUrl),displayNameSupplied=true)
        val engine=AccountDiscoveryEngine(routes,{null},{_,_->error("Unavailable")},laneTimeout=100,totalTimeout=500)
        assertTrue(engine.discover(seed,known).nameHints.isEmpty())
        val local=listOf(Follow("one",seed,"My edited label",personId="shared"),Follow("two",instagram,"Another custom label",personId="shared"))
        val result=engine.discover(seed,known.copy(links=emptyList()),local)
        assertEquals(setOf(seed,instagram),result.nameHints.map {it.target}.toSet())
        assertTrue(result.nameHints.all {it.name=="Community Workshop"&&it.kind==AccountLinkKind.LOCAL})
    }
    @Test fun warmedTypedWebsiteLinksAreReusedWithoutFetchingThePageAgain()=runBlocking {
        var pageReads=0
        val cached=AccountWebLinks(listOf(AccountCandidate(instagram,"Linked from workshop.test",AccountLinkKind.WEBSITE,"https://workshop.test/",55)),emptyList())
        val engine=AccountDiscoveryEngine(routes,{null},{url,_->if(url=="https://workshop.test/")pageReads++;error("Unavailable")},
            laneTimeout=100,totalTimeout=500,cachedWebsite={if(it=="https://workshop.test/")cached else null})
        val result=engine.discover(seed,Profile(seed,"Workshop",links=listOf("https://workshop.test/")))
        assertEquals(0,pageReads);assertTrue(result.candidates.any {it.target==instagram&&it.checkedAt==55L})
    }
    @Test fun hubsSupplyExplicitDifferentlyNamedAccountsWithoutRunningAUsernameSweep() {
        val result=AccountLinkage.website(payload("""<main><a href="https://www.instagram.com/workshop_photos">Photos</a><a href="https://x.com/repair_fixture">Updates</a><a href="https://beacons.ai/workshop">More</a></main>""","https://linktr.ee/workshop"),routes,123)
        assertEquals(2,result.accounts.size);assertEquals(listOf("https://beacons.ai/workshop"),result.websites)
        assertEquals(0,AccountLinkage.usernameTargets(seed,listOf(Platform.YOUTUBE,Platform.RSS,Platform.MASTODON),routes).size)
        assertTrue(AccountLinkage.usernameTargets(seed,PlatformOrder.popular,routes).size<=4)
    }
    @Test fun wikidataUsesExactAccountStatementsAndPreferredEntityWhileRejectingAmbiguousAndEndedAssociations() {
        val body=document(entity(rank="preferred",extra=",\"P11892\":[${claim("workshop_threads")}],\"P7085\":[${claim("old_clip_account",ended=true)}]"),entity("Q2"))
        val result=AccountLinkage.wikidata(body,seed,routes,500)
        assertEquals(setOf(Platform.X,Platform.INSTAGRAM,Platform.THREADS),result.accounts.map {it.target.platform}.toSet())
        assertTrue(result.accounts.all {it.sourceUrl=="https://www.wikidata.org/wiki/Q1"&&it.kind==AccountLinkKind.DIRECTORY})
        assertTrue(AccountLinkage.wikidata(document(entity(),entity("Q2")),seed,routes,500).accounts.isEmpty())
        assertTrue(AccountLinkage.wikidata(document(entity(handle="another_person")),seed,routes,500).accounts.isEmpty())
        assertTrue(AccountLinkage.wikidata(document(entity(rank="deprecated")),seed,routes,500).accounts.isEmpty())
        assertTrue(AccountLinkage.wikidata(document(entity(extra=",\"P2002\":[${claim("repair_fixture",ended=true)}]")),seed,routes,500).accounts.isEmpty())
        val search="""{"query":{"search":[{"title":"Q1"},{"title":"not-an-entity"}]}}"""
        assertTrue(AccountLinkage.wikidataUrl(seed)!!.contains("action=query"))
        assertTrue(AccountLinkage.wikidataEntitiesUrl(search)!!.contains("ids=Q1"))
        assertNull(AccountLinkage.wikidataEntitiesUrl("{}"))
    }
    @Test fun keybaseRequiresAnActiveExactSeedAssociationAndNeverClaimsLocalCryptographicVerification() {
        fun json(seedName:String="repair_fixture",state:Int=1)="""{"status":{"code":0},"them":[{"basics":{"username":"workshop_key"},"proofs_summary":{"all":[{"proof_type":"twitter","nametag":"$seedName","state":$state},{"proof_type":"reddit","nametag":"repair_notes","state":1}]}}]}"""
        val found=AccountLinkage.keybase(json(),seed,routes,100)
        assertEquals(setOf(Platform.X,Platform.REDDIT),found.map {it.target.platform}.toSet())
        assertTrue(found.all {it.kind==AccountLinkKind.DIRECTORY&&it.evidence=="Associated by Keybase"})
        assertTrue(AccountLinkage.keybase(json("other"),seed,routes,100).isEmpty())
        assertTrue(AccountLinkage.keybase(json(state=2),seed,routes,100).isEmpty())
    }
    @Test fun visibleProfileAndSecondWebsiteStreamAccountsEvenWhenOtherLanesFail()=runBlocking {
        var reads=0;val seen=mutableListOf<AccountDiscovery>();val visited=mutableListOf<String>()
        val engine=AccountDiscoveryEngine(routes,{reads++;error("Visible profile should be reused")},{url,_->
            synchronized(visited){visited+=url}
            when(url) {
                "https://broken.test/"->error("Unavailable")
                "https://workshop.test/"->payload("<footer><a href=\"${instagram.canonicalUrl}\">Photos</a></footer>",url)
                else->error("Directory unavailable")
            }
        },laneTimeout=200,totalTimeout=1000)
        val known=Profile(seed,"Community Workshop",links=listOf("https://broken.test/","https://workshop.test/"))
        val result=engine.discover(seed,known,onUpdate={seen+=it})
        assertEquals(0,reads);assertEquals(2,result.candidates.size);assertEquals("Community Workshop",result.name)
        assertEquals(AccountLinkKind.ENTERED,seen.first().candidates.single().kind)
        assertFalse(seen.first().complete);assertTrue(seen.last().complete)
        assertTrue(visited.contains("https://workshop.test/"))
    }
    @Test fun failedSeedReadDoesNotPreventDirectoryRecoveryAndUsernamesStayWeakUnlessTheyLinkBack()=runBlocking {
        val engine=AccountDiscoveryEngine(routes,{t->if(t.platform==Platform.X)error("Profile down")else Profile(t,"Workshop",displayNameSupplied=true)}, {url,_->
            when {
                "action=query" in url->payload("""{"query":{"search":[{"title":"Q1"}]}}""",url)
                "action=wbgetentities" in url->payload(document(entity()),url)
                else->error("Unavailable")
            }
        },laneTimeout=200,totalTimeout=1200)
        val result=engine.discover(seed,extraPlatforms=listOf(Platform.REDDIT))
        assertTrue(result.candidates.any {it.target==instagram&&it.kind==AccountLinkKind.DIRECTORY})
        assertTrue(result.candidates.any {it.target.platform==Platform.REDDIT&&it.kind==AccountLinkKind.USERNAME})
    }
    @Test fun cancellationStopsChildrenAndDoesNotPublishAFinalSuccess()=runBlocking {
        val seen=mutableListOf<AccountDiscovery>();var stopped=0
        val engine=AccountDiscoveryEngine(routes,{try{delay(10_000);null}finally{stopped++}},{_,_->delay(10_000);error("unreachable")},laneTimeout=5000,totalTimeout=8000)
        val job=launch {engine.discover(seed,onUpdate={seen+=it})};delay(50);job.cancelAndJoin()
        assertTrue(stopped>0);assertTrue(seen.isNotEmpty());assertTrue(seen.none {it.complete})
        val count=seen.size;delay(30);assertEquals(count,seen.size)
    }
}
