package app.antisocial.core

import kotlin.test.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalBusinessRoutingTest {
    private val area=LocalArea("fl","Sampleton","Sampleton, Florida, United States",LocalPoint(28.612218,-80.807555),25,"America/New_York",countryCode="US")
    private val url="https://business.example/sampleton/"
    @Test fun amusementParksAndTheirBrandAreReadableEvenWhenSchemaNameIsOnlyTheCity() {
        val body="""<title>Sky Zone Mapleford - Sampleton | Sky Zone Trampoline Park</title><script type='application/ld+json'>{"@graph":[{"@type":"AmusementPark","name":"Mapleford - Sampleton","url":"$url","address":{"streetAddress":"750 Sample Park Dr NE","addressLocality":"Mapleford","addressRegion":"FL"},"geo":{"latitude":28.562628,"longitude":-80.852075},"telephone":"(202) 555-0109"},{"@type":"WebSite","name":"Sky Zone Trampoline Park","url":"https://business.example/"}]}</script>"""
        val place=LocalDocumentWashing.extract(Payload(body,url,"text/html",200),1,area).businesses.single {it.point!=null}
        assertTrue(LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),place));assertTrue(LocalDiscoveryFilter.matches("sky zone",LocalBusinessRouting.text(place)))
        assertTrue(place.name.contains("Sky Zone"));assertEquals("(202) 555-0109",place.phones.single())
    }
    @Test fun businessCategoryMatchingHandlesPluralsAndAClearCategoryTypo() {
        assertTrue(LocalDiscoveryFilter.matches("tacos","Taco Dive"));assertTrue(LocalDiscoveryFilter.matches("toacos","Taco truck"))
        assertTrue(LocalDiscoveryFilter.matches("burgers","Hamburger restaurant"));assertFalse(LocalDiscoveryFilter.matches("sky zone","Sky High Park"))
        assertEquals("bessent",LocalDiscoveryFilter.normalizedQuery("Bessent"))
    }
    @Test fun explicitSearchUsesTheRegionWithoutEngineeringNoise() {
        val plan=LocalBusinessRouting.queries(LocalDiscoveryQuery(area,"sky zone"))
        assertEquals("sky zone Sampleton, Florida",plan.first());assertEquals(2,plan.size)
        assertTrue(plan.all {"Florida" in it});assertTrue(plan.none {it.contains("business official")})
    }
    @Test fun targetedDirectoryCoversFullRadiusAndLeisureWithoutInjectableSyntax() {
        val named=LocalPlaceSources.overpassUrl(area,topic="Sky Zone").toHttpUrl().queryParameter("data")!!
        assertTrue(named.contains("around:25000"));assertTrue(named.contains("name~\"sky.*zone\""));assertFalse(named.contains("[shop][name]"))
        val taco=LocalPlaceSources.overpassUrl(area,topic="tacos").toHttpUrl().queryParameter("data")!!
        assertTrue(taco.contains("cuisine~\"mexican"))
        val hostile=LocalPlaceSources.overpassUrl(area,topic="x\"];out body;/*").toHttpUrl().queryParameter("data")!!
        assertFalse(hostile.contains("x\"];out"))
    }
    @Test fun photonReturnsABusinessWithSharedOsmIdentityAndRealCoordinates() {
        val body="""{"features":[{"properties":{"osm_type":"N","osm_id":900000000000007,"osm_key":"leisure","osm_value":"trampoline_park","name":"Sky Zone","street":"Sample Park Drive Northeast","housenumber":"750","city":"Mapleford","state":"FL","postcode":"00005"},"geometry":{"coordinates":[-80.8522143,28.5620791]}},{"properties":{"osm_type":"R","osm_id":12,"osm_key":"place","name":"Unrelated town"},"geometry":{"coordinates":[-80.799445,28.628588]}}]}"""
        val place=LocalPlaceSources.photon(body,1).businesses.single()
        assertEquals("osm:node/900000000000007",place.id);assertTrue(LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),place))
        val request=LocalPlaceSources.photonUrl(LocalDiscoveryQuery(area,"sky zone")).toHttpUrl();assertNotNull(request.queryParameter("bbox"));assertEquals("20",request.queryParameter("limit"))
    }
    @Test fun passiveContactFallbackReadsAnExplicitAddressAndRejectsOtherStates() {
        val body="""<title>Acorn Diner</title><meta property='og:site_name' content='Acorn Diner'><meta name='description' content='Roadside burgers'><div>1819 N Example Park Blvd<br>Sampleton, FL 00035<br><a href='tel:12025550100'>(202) 555-0100</a></div><footer><a href='mailto:hello@burger.example'>Email</a><a href='https://www.facebook.com'>Facebook</a><a href='https://www.facebook.com/Theacorndiner'>Our page</a></footer>"""
        val place=LocalDocumentWashing.extract(Payload(body,url,"text/html",200),1,area).businesses.single()
        assertEquals("Acorn Diner",place.name);assertEquals("Sampleton",place.locality);assertTrue(place.address.startsWith("1819"));assertEquals(listOf("hello@burger.example"),place.emails)
        assertEquals(listOf("https://www.facebook.com/Theacorndiner"),place.socials)
        assertTrue(LocalBusinessPages.extract(body.replace("FL 00035","CA 00035"),url,null,1,area).businesses.isEmpty())
        assertTrue(LocalBusinessPages.extract("<title>Acorn Diner</title><p>Serving Sampleton</p>",url,null,1,area).businesses.isEmpty())
    }
    @Test fun sameBusinessFusesStreetAbbreviationsAndTheButKeepsOtherBranches() {
        val a=LocalBusiness("a","Acorn Diner",address="1819 N Example Park Blvd, Sampleton, FL 00035",locality="Sampleton",socials=listOf("https://www.facebook.com/live"))
        val b=a.copy(id="b",name="The Acorn Diner",address="1819 North Example Park Boulevard, Sampleton, Florida, United States",socials=listOf("https://www.instagram.com/live/"))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,b));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b)).size)
        assertEquals(2,LocalDiscoveryMerge.business(a,b).socials.size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1820 North Example Park Boulevard, Sampleton")))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(locality="Orlando",phones=emptyList())))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a.copy(address=a.address+", Suite 1"),b.copy(address=b.address+", Suite 2")))
        val map=a.copy(id="map",address="1819 Example Park Boulevard, Sampleton",point=LocalPoint(28.6652521,-80.8330532))
        val directory=b.copy(point=LocalPoint(28.665288,-80.833045))
        assertEquals(1,LocalDiscoveryMerge.businesses(listOf(map,directory)).size)
        assertEquals("Restaurant",LocalDiscoveryMerge.business(a.copy(category="Restaurant"),b.copy(category="LocalBusiness")).category)
        val apostrophe=a.copy(name="Francisco’s Mexican Restaurant",address="2447 N Meadow Rd")
        assertTrue(LocalDiscoveryMerge.sameBusiness(apostrophe,apostrophe.copy(id="other",name="Franciscos Mexican Restaurant",address="2447 North Meadow Road, Ste 146")))
    }
    @Test fun coffeeCompanySuffixAndAnOmittedBuildingLetterUseCoordinatesButBranchesRemainDistinct() {
        val a=LocalBusiness("a","Indian Example Coffee Co.",address="2112A Example Rd",locality="Sampleton",point=LocalPoint(28.650382,-80.855645),phones=listOf("+12025550101"))
        val b=a.copy(id="b",name="Indian Example Coffee",address="2112 Example Rd",point=LocalPoint(28.6505606,-80.8555622))
        val separate=a.copy(id="c",address="4980 N Meadow Rd, Ste 101",point=LocalPoint(28.718213,-80.871725))
        assertEquals(2,LocalDiscoveryMerge.businesses(listOf(a,b,separate)).size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="2112B Example Rd")))
        val premise=a.copy(id="premise",address="Example Business Complex, 2112A Example Rd, Sampleton, Florida, 00035, US",point=null)
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,premise));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b,premise)).size)
        val menu=b.copy(id="menu",name="Indian Example Coffee Company",point=null,phones=listOf("tel:2025550101"))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,menu));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b,premise,menu)).size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,menu.copy(address="2112B Example Rd")))
        val named=a.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("name",a.name,"https://www.openstreetmap.org/node/1",1,LocalEvidenceKind.DIRECTORY)))
        val headline="Highly Rated Indian Example Coffee Co., Inc. – Cafe in Sampleton, FL"
        val promoted=b.copy(name=headline,checkedAt=2,evidence=listOf(LocalDiscoveryIdentity.evidence("name",headline,"https://directory.example/coffee",2,LocalEvidenceKind.DIRECTORY)))
        assertEquals(named.name,LocalDiscoveryMerge.business(named,promoted).name)
        assertEquals(named.name,LocalDiscoveryMerge.business(promoted,named).name)
        assertTrue(LocalDiscoveryMerge.business(named,promoted).evidence.any {it.value==headline})
        val renamed=promoted.copy(evidence=promoted.evidence.map {it.copy(kind=LocalEvidenceKind.PUBLISHER)})
        assertEquals(headline,LocalDiscoveryMerge.business(named,renamed).name)
        assertEquals(listOf("https://www.coffee.example/","https://shop.coffee.example/"),LocalDiscoveryIdentity.urls("https://www.coffee.example/,%20https://shop.coffee.example/"))
        assertTrue(LocalBusinessRouting.queries(LocalDiscoveryQuery(area,"tacos")).last().startsWith("Mexican restaurants"))
    }
    @Test fun aBadDirectoryPinCannotSplitTheSameAddressAndPhoneOrReplaceTheMappedPin() {
        val a=LocalBusiness("a","Acorn Diner",address="1819 N Example Park Blvd",locality="Sampleton",point=LocalPoint(28.665262,-80.8330287),phones=listOf("+12025550100"),checkedAt=1,
            evidence=listOf(LocalDiscoveryIdentity.evidence("location","28.665262,-80.8330287","https://www.openstreetmap.org/node/1",1,LocalEvidenceKind.DIRECTORY)))
        val b=a.copy(id="b",point=LocalPoint(28.662155,-80.854077),phones=listOf("(202) 555-0100"),checkedAt=2,
            evidence=listOf(LocalDiscoveryIdentity.evidence("location","28.662155,-80.854077","https://directory.example/shop",2,LocalEvidenceKind.DIRECTORY)))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,b));assertEquals(a.point,LocalDiscoveryMerge.business(a,b).point)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1819 S Example Park Blvd")))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1819 N Example Park Blvd SW")))
        val page="""<title>El Sombrero Mexican Cuisine menu - Sampleton FL</title><p>Phone (202) 555-0108 We make ordering easy. Learn more 4990 Stack Boulevard, Sampleton, FL 00001</p>"""
        val parsed=LocalBusinessPages.extract(page,url,null,1,area).businesses.single()
        assertEquals("4990 Stack Boulevard, Sampleton, FL 00001",parsed.address);assertEquals("El Sombrero Mexican Cuisine",parsed.name)
    }
    @Test fun roundupsAndReviewAuthorsCannotBecomeTheListedBusinesses() {
        val list="""<title>Best Tacos in Sampleton, FL – TacosTradition</title><h1>Best Tacos in Sampleton, FL</h1><p>1700 W Garden Ave, Sampleton, FL 00004</p><p>1800 W Hibiscus Blvd, Sampleton, FL 00001</p><a href='https://real-business.example/'>Website</a>"""
        val result=LocalBusinessPages.extract(list,url,null,1,area)
        assertTrue(result.businesses.isEmpty());assertEquals("https://real-business.example/",result.sources.single().url)
        val review="""<title>Acorn Diner Sampleton: My Honest Take After Visiting - Eat The Way</title><meta property='og:site_name' content='Eat The Way'><p>1819 N Example Park Blvd, Sampleton, FL 00035</p>"""
        assertTrue(LocalBusinessPages.extract(review,url,null,1,area).businesses.isEmpty())
        val directory="""<title>Taco Dive Sampleton - RestaurantJump</title><meta property='og:site_name' content='RestaurantJump'><h1>Taco Dive Sampleton</h1><p>401 E Garden Ave, Sampleton, FL 00001</p><a href='tel:2025550102'>Call</a>"""
        assertEquals("Taco Dive Sampleton",LocalBusinessPages.extract(directory,url,null,1,area).businesses.single().name)
    }
    @Test fun unknownAndEmptySocialRoutesAreNotWorkingAccounts() {
        val target=Target(Platform.FACEBOOK,TargetKind.PROFILE,"Theacorndiner","https://www.facebook.com/Theacorndiner")
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList())))
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target,target.identity))))
        assertTrue(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target,"Acorn Diner",description="Open for lunch"))))
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target.copy(identity="other",canonicalUrl="https://www.facebook.com/other"),"Other",description="Not this account"))))
    }
    @Test fun directoryPublisherAndExplicitOwnerStaySeparateFromBusinessAccounts() {
        val directory="https://sampletondirections.example/s/coffee"
        val body="""<script type='application/ld+json'>{"@graph":[{"@type":"LocalBusiness","name":"Indian Example Coffee Co., Inc.","address":{"addressLocality":"Sampleton"},"sameAs":"https://www.facebook.com/indianexamplecoffee","owner":{"@type":"Person","name":"Jane Example","sameAs":"https://www.facebook.com/janeOwner"}},{"@type":"WebSite","name":"Sampleton Directions","publisher":{"@type":"Organization","name":"Realty Directions"}}]}</script><footer><a href='https://www.facebook.com/morganexamplerealtor'>Facebook</a><a href='mailto:realtor@example.org'>Email</a></footer>"""
        val data=LocalStructuredData.html(body,directory,1,area)
        val coffee=data.businesses.first {it.name.startsWith("Indian Example")}
        assertEquals(listOf("https://www.facebook.com/indianexamplecoffee"),coffee.socials)
        assertTrue(coffee.emails.orEmpty().isEmpty())
        assertEquals("Jane Example",coffee.related.orEmpty().first {it.role=="owner"}.name)
        assertEquals(listOf("https://www.facebook.com/janeOwner"),coffee.related.orEmpty().first {it.role=="owner"}.accounts)
        assertEquals("Realty Directions",coffee.related.orEmpty().first {it.role=="source publisher"}.name)
        assertFalse(data.sources.any {it.url.contains("morganexample")})
    }
    @Test fun previouslyMisattributedFooterAccountsAreReclassifiedWithoutDroppingTheirOrigin() {
        val link="https://www.facebook.com/morganexamplerealtor"
        val raw=LocalBusiness("a","Indian Example Coffee Co., Inc.",socials=listOf(link),evidence=listOf(LocalDiscoveryIdentity.evidence("socials",link,"https://www.sampletondirections.com/s/coffee",1,LocalEvidenceKind.PUBLISHER)))
        val repaired=LocalBusinessOwnership.repairLegacy(raw)
        assertTrue(repaired.socials.isEmpty());assertEquals(link,repaired.related!!.single().accounts.single());assertEquals("source publisher",repaired.related!!.single().role)
        assertEquals(repaired,LocalBusinessOwnership.repairLegacy(repaired))
        val overture=raw.copy(evidence=raw.evidence.map {it.copy(kind=LocalEvidenceKind.DIRECTORY)})
        assertEquals(overture,LocalBusinessOwnership.repairLegacy(overture))
    }
    @Test fun unitsConvertWithoutChangingExistingRadiusAndContactsRejectIntentInjection() {
        assertEquals("15.5 mi",LocalDistanceUnit.MILES.radius(25));assertEquals(40,LocalDistanceUnit.MILES.toKm(25));assertEquals(25,area.radiusKm)
        assertEquals("25 km",LocalDistanceUnit.KILOMETRES.radius(25));assertEquals("mi",LocalDistanceUnit.MILES.id)
        assertEquals("+12025550100",LocalBusinessContact.phone("+1 (202) 555-0100"));assertNull(LocalBusinessContact.phone("tel:321;intent:evil"))
        assertEquals("hello@example.org",LocalBusinessContact.email("mailto:hello@example.org?subject=Hi"));assertNull(LocalBusinessContact.email("hello@example.org\r\nBcc:evil"))
    }
}
