package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class LocalBusinessDeckTest {
    private fun claim(field:String,value:String)=LocalFieldEvidence(field,value,"https://directory.example/branch",100,LocalEvidenceKind.DIRECTORY)
    private fun branch(id:String,brand:String?=null,brandName:String?=null,site:String?=null)=LocalBusiness(id,"Cobalt Burger",category="restaurant",address="$id Sample St",
        websites=listOfNotNull(site),evidence=listOfNotNull(brand?.let {claim("brand id",it)},brandName?.let {claim("brand",it)}))

    @Test fun regionalTagsKeepBrandIdentityWithoutRequiringBranchWebsites() {
        val body="""{"elements":[{"type":"node","id":1,"lat":10.0,"lon":20.0,"tags":{"name":"Cobalt Burger","amenity":"fast_food","brand":"Cobalt Burger","brand:wikidata":"Q12345678901"}},{"type":"node","id":2,"lat":10.01,"lon":20.0,"tags":{"name":"Cobalt Burger","amenity":"fast_food","brand":"Cobalt Burger","brand:wikidata":"Q12345678901"}}]}"""
        val rows=LocalPlaceSources.overpass(body,"https://directory.example/api",100).businesses
        assertEquals(2,rows.size)
        assertTrue(rows.all {b->b.evidence.any {it.field=="brand id"&&it.value=="Q12345678901"}})
        assertEquals(1,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun declaredBrandNamesCanGroupLocationsWhenNoIdentifierOrWebsiteWasPublished() {
        val rows=listOf(branch("one",brandName="Cobalt Burger"),branch("two",brandName="Cobalt Burger"))
        assertEquals(listOf(rows),LocalBusinessDeck.groups(rows).map {it.locations})
    }
    @Test fun anUnbrandedWebsiteCannotBridgeConflictingBrandIdentitiesInAnyInputOrder() {
        val a=branch("one","Q12345678901",site="https://cobaltburger.example/one")
        val b=branch("two","Q12345678902",site="https://cobaltburger.example/two")
        val unknown=branch("three",site="https://cobaltburger.example/three")
        for(rows in listOf(listOf(a,b,unknown),listOf(unknown,a,b),listOf(b,unknown,a)))assertEquals(3,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun chainTitleUsesItsDeclaredBrandRatherThanTheNearestBranchSuffix() {
        val rows=listOf(branch("one","Q12345678901","Cobalt Burger").copy(name="Cobalt Burger #101"),branch("two","Q12345678901","Cobalt Burger").copy(name="Cobalt Burger #202"))
        assertEquals("Cobalt Burger",LocalBusinessDeck.groups(rows).single().title)
    }
    @Test fun aMultiWebsiteRecordCannotTransitivelyBridgeDifferentBrandIdentifiers() {
        val a=branch("one","Q12345678901",site="https://cobaltburger.example")
        val b=branch("two","Q12345678902",site="https://cobaltburger.test")
        val unknown=branch("three").copy(websites=a.websites+b.websites)
        for(rows in listOf(listOf(a,unknown,b),listOf(b,a,unknown),listOf(unknown,b,a)))assertEquals(3,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun locationsAreNearestFirstRegardlessOfArrivalAndMissingCoordinatesStayLast() {
        val origin=LocalPoint(10.0,20.0)
        val near=branch("near","Q12345678901").copy(point=origin,phones=listOf("+12025550101"))
        val far=branch("far","Q12345678901").copy(point=LocalPoint(10.01,20.0),hours="Tuesday 9am–5pm")
        val unknown=branch("unknown","Q12345678901")
        val incoming=mutableListOf(unknown,far,near);val before=incoming.toList()
        val grouped=LocalBusinessDeck.groups(incoming,origin).single()
        assertEquals(listOf(near,far,unknown),grouped.locations)
        assertEquals(near,grouped.primary)
        assertEquals(before,incoming)
        assertEquals(listOf(far,near,unknown),LocalBusinessDeck.groups(listOf(unknown,near,far),far.point).single().locations)
    }
    @Test fun sharedOperatorsAndSimilarNamesDoNotEstablishAChain() {
        val rows=listOf(branch("one"),branch("two")).map {it.copy(related=listOf(LocalRelatedEntity("Example Holdings","operator",emptyList(),"https://directory.example/branch")))}
        assertEquals(2,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun intactLegacyOsmBrandTagsWorkFromCacheWithoutInferringBrandsFromOtherKeywords() {
        val rows=listOf(branch("one"),branch("two")).map {it.copy(sourceIds=listOf("osm:node/${it.id}"),keywords=listOf("cuisine","burger","brand","Cobalt Burger"))}
        assertEquals(1,LocalBusinessDeck.groups(rows).size)
        assertEquals(2,LocalBusinessDeck.groups(rows.map {it.copy(keywords=listOf("operator","Cobalt Burger"))}).size)
        assertEquals(2,LocalBusinessDeck.groups(rows.map {it.copy(sourceIds=listOf("web:${it.id}"))}).size)
        assertEquals(2,LocalBusinessDeck.groups(rows.map {it.copy(keywords=listOf("brand","Cobalt Burger","unstructured"))}).size)
    }
    @Test fun aSharedBrandNameDoesNotOverrideDifferentOfficialWebsites() {
        val rows=listOf(branch("one",brandName="Cobalt Burger",site="https://cobaltburger.example"),branch("two",brandName="Cobalt Burger",site="https://cobaltburger.test"))
        val groups=LocalBusinessDeck.groups(rows)
        assertEquals(2,groups.size);assertEquals(2,groups.map {it.id}.distinct().size)
    }
    @Test fun conflictingClaimsStaySeparateWithUniqueKeysEvenWhenTheirWeakLabelsMatch() {
        val rows=(1..3).map {n->branch("branch$n",brandName="Cobalt Burger",site="https://cobaltburger.example").copy(evidence=listOf(claim("brand id","Q12345678901"),claim("brand id","Q12345678902"),claim("brand","Cobalt Burger")))}
        val groups=LocalBusinessDeck.groups(rows)
        assertEquals(3,groups.size);assertEquals(3,groups.map {it.id}.distinct().size)
    }
    @Test fun scopedSchemaBrandRelationshipsCanGroupTheirBranches() {
        val rows=listOf(branch("one"),branch("two")).map {it.copy(related=listOf(LocalRelatedEntity("Cobalt Burger","brand",emptyList(),"https://publisher.example/branch")))}
        assertEquals(1,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun anEstablishedMultiLocationSiteIncludesSparseSameNamePlaceRecords() {
        val anchors=(1..2).map {n->branch("site$n",site="https://cobaltburger.example/").copy(address="${n*10} Sample St")}
        val sparse=(3..13).map {n->branch("sparse$n").copy(address="${n*10} Sample St",category="")}
        val directory=branch("directory",site="https://www.restaurantji.com/fixture/entry").copy(address="140 Sample St")
        val rows=sparse+directory+anchors
        val group=LocalBusinessDeck.groups(rows).single()
        assertEquals(14,group.locations.size)
        assertEquals(rows.toSet(),group.locations.toSet())
    }
    @Test fun oneWebsiteClaimDoesNotEstablishMultipleLocations() {
        val rows=listOf(branch("one",site="https://cobaltburger.example/").copy(address="10 Sample St"),branch("two").copy(address="20 Sample St"))
        assertEquals(2,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun aSparseNameCannotChooseBetweenTwoEstablishedSitesOrContradictItsOwnIdentity() {
        val anchors=(1..2).map {n->branch("a$n",site="https://cobaltburger.example/").copy(address="${n*10} Sample St")}
        val other=(3..4).map {n->branch("b$n",site="https://cobaltburger.test/").copy(address="${n*10} Sample St")}
        val bare=branch("bare").copy(address="50 Sample St")
        assertEquals(3,LocalBusinessDeck.groups(anchors+other+bare).size)
        assertEquals(2,LocalBusinessDeck.groups(anchors+bare.copy(evidence=listOf(claim("brand id","Q12345678902")))).size)
        assertEquals(2,LocalBusinessDeck.groups(anchors+bare.copy(websites=listOf("https://cobaltburger.test/"))).size)
    }
    @Test fun aSparseBusinessWithAContradictingCategoryStaysOutsideTheChain() {
        val anchors=(1..2).map {n->branch("site$n",site="https://cobaltburger.example/").copy(address="${n*10} Sample St")}
        assertEquals(2,LocalBusinessDeck.groups(anchors+branch("hardware").copy(category="hardware store",address="30 Sample St")).size)
    }
    @Test fun explicitlyBoundAbbreviatedWebsitesCanEstablishAChain() {
        val rows=(1..2).map {n->branch("site$n",site="https://cb.example/").copy(address="${n*10} Sample St",evidence=listOf(claim("websites","https://cb.example/")))}
        assertEquals(1,LocalBusinessDeck.groups(rows).size)
        assertEquals(2,LocalBusinessDeck.groups(rows.map {it.copy(evidence=emptyList())}).size)
        assertEquals(2,LocalBusinessDeck.groups(rows.map {it.copy(evidence=listOf(claim("websites","https://other.example/")))}).size)
    }
    @Test fun sharedOrderingProvidersAreNotChainWebsites() {
        val rows=(1..2).map {n->val url="https://toasttab.com/fixture$n";branch("provider$n",site=url).copy(address="${n*10} Sample St",evidence=listOf(claim("websites",url)))}
        assertEquals(2,LocalBusinessDeck.groups(rows).size)
    }
    @Test fun overtureBrandNamesAndIdentifiersSurviveNormalization() {
        val body="""{"features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[20,10]},"properties":{"id":"fixture-brand-one","names":{"primary":"Cobalt Burger Downtown"},"brand":{"names":{"primary":"Cobalt Burger"},"wikidata":"Q12345678901"}}},{"type":"Feature","geometry":{"type":"Point","coordinates":[20,10.01]},"properties":{"id":"fixture-brand-two","names":{"primary":"Cobalt Burger Riverside"},"brand":{"names":{"primary":"Cobalt Burger"},"wikidata":"Q12345678901"}}}]}"""
        val rows=LocalStructuredData.overture(body,"https://directory.example/places",100).businesses
        assertTrue(rows.all {b->b.evidence.any {it.field=="brand id"&&it.value=="Q12345678901"}})
        assertTrue(rows.all {b->b.evidence.any {it.field=="brand"&&it.value=="Cobalt Burger"}})
        assertEquals("Cobalt Burger",LocalBusinessDeck.groups(rows).single().title)
    }
    @Test fun aScopedCatalogCanQualifyCompatibleDeclaredBrandsAcrossOfficialSiteVariants() {
        val origin=LocalPoint(40.0,-100.0)
        val rows=(1..12).map {n->LocalBusiness("taco-declared-$n","Taco Bell",category="restaurant",address="$n Example St",point=LocalPoint(40+n*.001,-100.0),
            websites=listOf(if(n%2==0)"https://locations.tacobell.com/branch$n"else "https://www.tacobell.com/branch$n"),phones=listOf("+1202555${n.toString().padStart(4,'0')}"),evidence=listOf(claim("brand","Taco Bell")))}
        val groups=LocalBusinessDeck.groupsForArea(rows,origin,"US")
        assertEquals(1,groups.size);assertEquals(rows,groups.single().locations)
        assertEquals(2,LocalBusinessDeck.groupsForArea(rows.take(1)+rows[1].copy(evidence=listOf(claim("brand id","Q12345678902"),claim("brand","Taco Bell"))),origin,"US").size)
        assertEquals(2,LocalBusinessDeck.groupsForArea(rows.take(1)+rows[1].copy(evidence=listOf(claim("brand","Unrelated Tacos"))),origin,"US").size)
    }

    @Test fun qualifiedSourceNamesSurviveLocalityHeadingsAndGenericWebsiteCategories() {
        val origin=LocalPoint(40.0,-100.0)
        val ordinary=LocalBusiness("ordinary","Taco Bell",category="restaurant",address="10 Example St",point=origin)
        val heading=ordinary.copy(id="heading",name="Taco Bell Exampletown",category="Store",address="20 Example St",point=LocalPoint(40.01,-100.0),evidence=listOf(claim("name","Taco Bell")))
        assertEquals(listOf(listOf(ordinary,heading)),LocalBusinessDeck.groupsForArea(listOf(ordinary,heading),origin,"US").map {it.locations})
        assertEquals(2,LocalBusinessDeck.groupsForArea(listOf(ordinary,heading.copy(category="hardware store")),origin,"US").size)
        assertEquals(2,LocalBusinessDeck.groupsForArea(listOf(ordinary,heading.copy(evidence=heading.evidence.map {it.copy(kind=LocalEvidenceKind.PUBLIC_POST)})),origin,"US").size)
        val conflicting=heading.copy(evidence=heading.evidence+claim("name","McDonald's"))
        val other=ordinary.copy(id="other",name="McDonald's",address="30 Example St")
        assertEquals(3,LocalBusinessDeck.groupsForArea(listOf(ordinary,conflicting,other),origin,"US").size)
    }

}
