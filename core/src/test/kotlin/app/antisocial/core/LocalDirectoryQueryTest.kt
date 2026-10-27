package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LocalDirectoryQueryTest {
    private val area=LocalArea("fixture","Example City","Example City, Test Region",LocalPoint(10.0,20.0),5,"UTC")
    private fun code(text:String)=LocalPlaceSources.overpassUrl(area,topic=text).toHttpUrl().queryParameter("data")!!
    @Test fun typedCafePredicatesDoNotRepeatTheLegacyInterestRequest() {
        for(topic in listOf("coffee","cafes"))assertEquals(topic,1,Regex("amenity=\\\"?cafe\\\"?").findAll(code(topic)).count())
    }
    @Test fun recognizedCategoriesDoNotAlsoScanNameBrandAndAlternateNameRegexes() {
        for(text in listOf("library","indian food","bakery","cafes","ice cream")) {
            val query=code(text)
            assertTrue(text,query.contains("around:5000,10.00000,20.00000"))
            assertTrue(text,PlaceSearchVocabulary.parse(text).predicates().all {query.contains(it)})
            for(key in listOf("name","brand","alt_name"))assertFalse("$text must use its category rather than $key regex",query.contains("[$key~"))
        }
    }
    @Test fun unknownNamesRetainAllLiteralIdentityRoutes() {
        val query=code("Amber Acorn")
        for(key in listOf("name","brand","alt_name"))assertTrue(query.contains("[$key~\"amber.*acorn\",i]"))
    }
    @Test fun quotesKeepTheLiteralCategoryNameBypass() {
        val query=code("\"library\"")
        assertTrue(query.contains("[name~\"library\",i]"));assertFalse(query.contains("[amenity=\"library\"]"))
    }
    @Test fun aCategoryWordInsideAnUnresolvedNameRetainsItsNamedBranch() {
        assertTrue(code("Acorn coffee").contains("[name~\"acorn.*coffee\",i]"))
    }
    @Test fun exclusionsDoNotBecomePositiveDirectoryPredicates() {
        val query=code("library -cafe")
        assertTrue(query.contains("[amenity=\"library\"]"));assertFalse(query.contains("[amenity=\"cafe\"]"))
        assertTrue(PlaceSearchVocabulary.parse("library -cafe").terms.any {it.excluded})
    }
}
