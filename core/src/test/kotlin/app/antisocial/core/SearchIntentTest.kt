package app.antisocial.core

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SearchIntentTest {
    private val area=LocalArea("fixture-area","Example City","Example City, Test Region",LocalPoint(10.0,20.0),25,countryCode="ZZ")
    private val indian=LocalBusiness("i","Saffron Palace","restaurant",point=area.point,keywords=listOf("cuisine","indian"))
    @Test fun cuisineQueriesReachStructuredTagsAndSurviveDownstreamMatchingWithoutNameKeywords() {
        for(query in listOf("indian food","Indian restaurants","find good indian food near me","indain resturants")) {
            assertTrue(query,LocalBusinessRouting.matches(query,indian))
            val q=LocalPlaceSources.overpassUrl(area,topic=query).toHttpUrl().queryParameter("data")!!
            assertTrue(query,q.contains("cuisine~"));assertTrue(q.contains("indian"));assertTrue(q.contains("around:25000"))
            val web=LocalBusinessRouting.queries(LocalDiscoveryQuery(area,query))
            assertEquals(2,web.size);assertTrue(web.all {it.contains("Example City, Test Region")})
        }
        assertFalse(LocalBusinessRouting.matches("indian food",indian.copy(keywords=listOf("cuisine","italian"))))
        assertTrue(LocalBusinessRouting.matches("tacos",indian.copy(name="Bob's Truck",category="fast food",keywords=listOf("cuisine","taco"))))
        assertFalse(LocalBusinessRouting.matches("-buffet",indian.copy(description="Lunch buffet")))
        assertEquals("spaceflight",SearchMeaning.remoteAlternative("rocket launches"))
        assertFalse(LocalBusinessRouting.matches("indian food",LocalBusiness("c","Indian Example Coffee","cafe")))
    }
    @Test fun businessAliasesClassifyUnnamedCategoriesAndKeepNamesAsLiteralPaths() {
        val bakery=LocalBusiness("b","Early Bird","bakery")
        assertTrue(LocalBusinessRouting.matches("bakeries",bakery));assertTrue(LocalBusinessRouting.matches("bakery",bakery))
        assertTrue(LocalPlaceSources.overpassUrl(area,topic="bakery").toHttpUrl().queryParameter("data")!!.contains("shop=\"bakery\""))
        val named=LocalBusiness("c","Indian Example Coffee","cafe")
        assertTrue(LocalBusinessRouting.matches("Indian Example Coffee",named))
        assertTrue(PlaceSearchVocabulary.parse("Indian Example Coffee").predicates().isEmpty())
        assertFalse(LocalBusinessRouting.matches("Sky Zone",LocalBusiness("s","Sky High","trampoline park")))
    }
    @Test fun quotedPhrasesBypassConceptAndMorphologyExpansion() {
        assertTrue(LocalBusinessRouting.matches("\"Indian Example Coffee\"",LocalBusiness("c","Indian Example Coffee Co.","cafe")))
        assertFalse(LocalBusinessRouting.matches("\"indian food\"",indian))
        assertNull(SearchMeaning.remoteAlternative("\"rocket launch\""))
        assertEquals(1,SearchMeaning.variants(SearchIntent.parse("\"rocket launch\"")).size)
        assertFalse(SearchIntent.parse("\"burger\"").matches("burgers"))
    }
    @Test fun multiwordConceptsRemainPhrasesAndExclusionsSurviveExpansion() {
        assertTrue(SearchMeaning.matches("spaceflight","The rocket launch is tomorrow"))
        assertFalse(SearchMeaning.matches("\"rocket launch\"","Rocket recovery followed the successful launch"))
        assertFalse(LocalBusinessRouting.matches("indian food without buffet",indian.copy(description="Lunch buffet every day")))
        assertTrue(LocalBusinessRouting.matches("indian food without buffet",indian))
        assertFalse(LocalDiscoveryFilter.matches("ice cream","Ice was packed into a first aid cream container"))
        assertTrue(SearchMeaning.matches("rocket launch -cancelled","A successful spaceflight"))
        assertFalse(SearchMeaning.matches("rocket launch -cancelled","The spaceflight is cancelled"))
    }
    @Test fun publicProviderExpansionRetainsOriginalPathsAndFrozenQueryLists() {
        val target=Target(Platform.INSTAGRAM,TargetKind.SEARCH,"spaceflight","https://www.instagram.com/explore/search/keyword/?q=spaceflight")
        val queries=PublicSearchAdapter.postQueries(target)
        assertEquals("site:instagram.com/p/ spaceflight",queries.first());assertEquals(4,queries.size)
        assertEquals(queries,PublicSearchAdapter.postQueries(target));assertTrue(queries.last().contains("rocket launch"))
        val exact=target.copy(identity="\"spaceflight\"")
        assertEquals(3,PublicSearchAdapter.postQueries(exact).size)
        assertEquals(listOf(PublicSearchAdapter.query(exact.copy(platform=Platform.X))),PublicSearchAdapter.postQueries(exact.copy(platform=Platform.X)))
        val x=PublicSearchAdapter.postQueries(target.copy(platform=Platform.X))
        assertEquals(2,x.size);assertEquals(PublicSearchAdapter.query(target.copy(platform=Platform.X)),x.first())
    }
    @Test fun conservativeMorphologyLeavesProtectedWordsAndIdentifiersAlone() {
        assertEquals("bakery",SearchText.plural("bakeries"));assertEquals("burger",SearchText.plural("burgers"))
        for(word in listOf("news","series","physics","business","nasa"))assertEquals(word,SearchText.plural(word))
        assertEquals("bessent",LocalDiscoveryFilter.normalizedQuery("Bessent"))
        assertNull(SearchMeaning.remoteAlternative("@Jones"));assertTrue(SearchIntent.parse("@alice_jones").matches("@alice_jones"))
    }
    @Test fun ordinaryTopicsAreNotCorrectedIntoUnrelatedBusinessCategories() {
        assertEquals("space",LocalDiscoveryFilter.normalizedQuery("space"))
        assertNull(SearchMeaning.remoteAlternative("space"))
        assertNull(SearchMeaning.remoteAlternative("Indian Example Coffee"))
        assertNull(SearchMeaning.remoteAlternative("Russian history"))
        val query=LocalBusinessRouting.queries(LocalDiscoveryQuery(area,"\"burgers\""))
        assertTrue(query.all {"\"burgers\"" in it})
        val event=LocalEventIntent.parse("\"spaceflight\" this weekend")
        assertEquals(LocalEventWindow.WEEKEND,event.window)
        assertTrue(event.matchText("A spaceflight briefing").matches)
        assertFalse(event.matchText("A rocket launch briefing").matches)
        assertTrue(event.discoveryText.contains("\"spaceflight\""))
    }
    @Test fun ftsAlternativesUseCompleteSafePathsAndKeepQuotedIdentity() {
        assertEquals("\"acorn diner\"",LocalSearch.query("\"Acorn Diner\""))
        val variants=LocalSearch.expandedQueries("rocket launch").joinToString(" ")
        assertTrue(variants.contains("spaceflight"));assertTrue(variants.length<8192)
        assertTrue(LocalSearch.expandedQueries("\"rocket launch\"").isEmpty())
        assertTrue(LocalSearch.expandedQueries("pastry").any {it.contains("pastries")})
    }
    @Test fun hyphenatedNamesDatesAndTermsDoNotTurnIntoAccidentalExclusions() {
        for(query in listOf("covid-19","part-time","Jean-Luc","2026-10-25")) {
            assertTrue(query,SearchIntent.parse(query).excluded.isEmpty())
            assertTrue(query,LocalSearch.excludedQueries(query).isEmpty())
            assertTrue(query,SearchIntent.parse(query).matches(query))
        }
        assertEquals(1,SearchIntent.parse("spaceflight -cancelled").excluded.size)
    }
    @Test fun quotedLongIdentifiersDoNotMatchATruncatedDifferentWord() {
        val exact="a".repeat(100)
        assertTrue(SearchIntent.parse("\"$exact\"").matches(exact))
        assertFalse(SearchIntent.parse("\"$exact\"").matches(exact+"tail"))
        assertEquals("\"$exact\"",LocalSearch.query("\"$exact\""))
    }
    @Test fun quoteAndExclusionControlsReachTheRegionalSourceCompilerAndItsCacheIdentity() {
        val exact=LocalPlaceSources.overpassUrl(area,topic="\"burger\"").toHttpUrl().queryParameter("data")!!
        assertFalse(exact.contains("cuisine"));assertTrue(exact.contains("name~"))
        val excluded=LocalPlaceSources.overpassUrl(area,topic="indian food -burger").toHttpUrl().queryParameter("data")!!
        assertTrue(excluded.contains("indian"));assertFalse(excluded.contains("cuisine~\"burger"))
        assertNotEquals(PlaceSearchVocabulary.parse("burger").fingerprint,PlaceSearchVocabulary.parse("\"burger\"").fingerprint)
        assertNull(SearchMeaning.remoteAlternative("C++"))
    }
    @Test fun technicalLiteralFeedSearchRetainsSymbolsRatherThanMatchingABroaderLanguageName():Unit=runBlocking {
        val page=Page(listOf(Post("1",Platform.X,"https://x.com/test/status/12345","Test","A C++ compiler"),Post("2",Platform.X,"https://x.com/test/status/12346","Test","A C# compiler")))
        assertEquals(listOf("1"),ScopedFeedSearch("C++",{page}).next(null).posts.map {it.id})
        assertEquals(listOf("1"),ScopedFeedSearch("\"C++\"",{page}).next(null).posts.map {it.id})
    }
    @Test fun conceptMatchesAndExclusionsCanReachTheEndOfALongRetainedDocument() {
        val beginning=(1..500).joinToString(" "){"introductory material"}
        assertTrue(SearchMeaning.matches("rocket launch",beginning+" successful spaceflight"))
        assertFalse(SearchMeaning.matches("rocket launch -cancelled",beginning+" spaceflight cancelled"))
    }
    @Test fun scopedFeedSearchUsesConceptsWithoutLosingLiteralResultsOrSourceBoundaries():Unit=runBlocking {
        val posts=listOf(Post("1",Platform.X,"https://x.com/nasa/status/12345","NASA","Successful spaceflight"),Post("2",Platform.X,"https://x.com/nasa/status/12346","NASA","The rocket launch is tomorrow"))
        val source=Page(posts)
        val result=ScopedFeedSearch("rocket launch",{source}).next(null)
        assertEquals(listOf("1","2"),result.posts.map {it.id})
        assertEquals(listOf("2"),ScopedFeedSearch("\"rocket launch\"",{source}).next(null).posts.map {it.id})
    }
}
