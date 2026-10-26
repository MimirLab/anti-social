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
}
