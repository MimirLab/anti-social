package app.antisocial.core

import org.junit.Assert.*
import org.junit.Test

class KnownPlaceBrandsTest {
    private val index=PlaceBrandIndex("""{"schema":1,"brands":[["Q12345678901","Aster Bowl",["aster bowl"],["001"],["ca",[20,10,5]],"amenity","fast_food"],["Q12345678902","Aster Bowl",["aster bowl"],["ca"],[],"amenity","fast_food"]]}""")
    @Test fun countryAndCircularExceptionsKeepIndependentSameNameBusinessesSeparate() {
        assertNull(index.match("Aster Bowl","restaurant","us",LocalPoint(10.0,20.0)))
        assertEquals("Q12345678901",index.match("Aster Bowl","restaurant","us",LocalPoint(20.0,30.0))?.id)
        assertEquals("Q12345678902",index.match("Aster Bowl","fast food","ca",LocalPoint(20.0,30.0))?.id)
        assertNull(index.match("Aster Bowl","restaurant",null,LocalPoint(20.0,30.0)))
        assertNull(index.match("Aster Bowl","restaurant","us",null))
    }
    @Test fun exactAliasAndCompatibleCategoryAreRequired() {
        val point=LocalPoint(20.0,30.0)
        assertNull(index.match("Aster Bowl Hardware","restaurant","us",point))
        assertNull(index.match("Aster Bowl","hardware store","us",point))
        assertEquals("Q12345678901",index.match("Aster Bowl","beverage supplier","us",point)?.id)
        assertEquals("Q12345678901",index.match("Aster Bowl","","us",point)?.id)
    }
    @Test fun overlappingConflictingCatalogIdentitiesCannotPickAWinner() {
        val duplicate=PlaceBrandIndex("""{"schema":1,"brands":[["Q12345678901","Aster Bowl",["aster bowl"],["us"],[],"amenity","fast_food"],["Q12345678902","Aster Bowl",["aster bowl"],["us"],[],"amenity","fast_food"]]}""")
        assertNull(duplicate.match("Aster Bowl","restaurant","us",LocalPoint(20.0,30.0)))
    }
    @Test fun publishedGlobalBrandGroupsBareBranchesWithoutAlteringTheirFacts() {
        val origin=LocalPoint(40.0,-100.0)
        val name="McDonald’s"
        val rows=(1..14).map {n->LocalBusiness("bare-$n",name,category="restaurant",address="$n Example St",point=LocalPoint(40+n*.001,-100.0),phones=listOf("+1202555${n.toString().padStart(4,'0')}"))}
        val groups=LocalBusinessDeck.groupsForArea(rows,origin,"US")
        assertEquals(1,groups.size);assertEquals(rows,groups.single().locations)
        assertTrue(rows.all {it.evidence.isEmpty()&&it.websites.isEmpty()})
    }
    @Test fun specificStorefrontNamesDisambiguateSharedParentBrandLabels() {
        val point=LocalPoint(40.0,-100.0)
        assertEquals("Q752941",KnownPlaceBrands.match("Taco Bell","restaurant","US",point)?.id)
        assertEquals("Q111972226",KnownPlaceBrands.match("Taco Bell Cantina","restaurant","US",point)?.id)
        val rows=(1..24).map {n->LocalBusiness("taco-$n","Taco Bell",category="mexican restaurant",address="$n Example St",point=LocalPoint(40+n*.001,-100.0))}
        val groups=LocalBusinessDeck.groupsForArea(rows,point,"US")
        assertEquals(1,groups.size);assertEquals(rows,groups.single().locations)
        val variants=rows.take(2).map {it.copy(id="cantina-"+it.id,name="Taco Bell Cantina")}
        val formats=LocalBusinessDeck.groupsForArea(rows+variants,point,"US")
        assertEquals(setOf("Taco Bell","Taco Bell Cantina"),formats.map {it.title}.toSet())
        assertEquals((rows+variants).toSet(),formats.flatMap {it.locations}.toSet())
    }

    @Test fun storefrontSpecificityPreservesScopeAndGenuineIdentityConflicts() {
        val rows="""["Q12345678901","Aster Bowl",["aster bowl"],["us"],[],"amenity","fast_food",[0]],["Q12345678902","Aster Bowl Express",["aster bowl","aster bowl express"],["us","ca"],[],"amenity","fast_food",[1]]"""
        val index=PlaceBrandIndex("""{"schema":2,"brands":[$rows]}""")
        val point=LocalPoint(40.0,-100.0)
        assertEquals("Q12345678901",index.match("Aster Bowl","restaurant","us",point)?.id)
        assertEquals("Q12345678902",index.match("Aster Bowl Express","restaurant","us",point)?.id)
        assertEquals("Q12345678902",index.match("Aster Bowl","restaurant","ca",point)?.id)
        assertNull(index.match("Aster Bowl","hardware store","us",point))
        assertNull(index.match("Aster Bowl","restaurant","fr",point))
        assertNull(index.match("Aster Bowl Extra","restaurant","us",point))
        val conflicting=PlaceBrandIndex("""{"schema":2,"brands":[$rows,["Q12345678903","Aster Bowl",["aster bowl"],["us"],[],"amenity","fast_food",[0]]]}""")
        assertNull(conflicting.match("Aster Bowl","restaurant","us",point))
    }

}
