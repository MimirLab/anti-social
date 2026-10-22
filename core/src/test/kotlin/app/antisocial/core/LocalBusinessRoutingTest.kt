package app.antisocial.core

import kotlin.test.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalBusinessRoutingTest {
    private val area=LocalArea("fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08363,-80.60811),25,"America/New_York",countryCode="US")
    private val url="https://business.example/melbourne/"
    @Test fun amusementParksAndTheirBrandAreReadableEvenWhenSchemaNameIsOnlyTheCity() {
        val body="""<title>Sky Zone Palm Bay - Melbourne | Sky Zone Trampoline Park</title><script type='application/ld+json'>{"@graph":[{"@type":"AmusementPark","name":"Palm Bay - Melbourne","url":"$url","address":{"streetAddress":"750 Bass Pro Dr NE","addressLocality":"Palm Bay","addressRegion":"FL"},"geo":{"latitude":28.03404,"longitude":-80.65263},"telephone":"(321) 953-0284"},{"@type":"WebSite","name":"Sky Zone Trampoline Park","url":"https://business.example/"}]}</script>"""
        val place=LocalDocumentWashing.extract(Payload(body,url,"text/html",200),1,area).businesses.single {it.point!=null}
        assertTrue(LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),place));assertTrue(LocalDiscoveryFilter.matches("sky zone",LocalBusinessRouting.text(place)))
        assertTrue(place.name.contains("Sky Zone"));assertEquals("(321) 953-0284",place.phones.single())
    }
    @Test fun businessCategoryMatchingHandlesPluralsAndAClearCategoryTypo() {
        assertTrue(LocalDiscoveryFilter.matches("tacos","Taco Dive"));assertTrue(LocalDiscoveryFilter.matches("toacos","Taco truck"))
        assertTrue(LocalDiscoveryFilter.matches("burgers","Hamburger restaurant"));assertFalse(LocalDiscoveryFilter.matches("sky zone","Sky High Park"))
        assertEquals("bessent",LocalDiscoveryFilter.normalizedQuery("Bessent"))
    }
    @Test fun explicitSearchUsesTheRegionWithoutEngineeringNoise() {
        val plan=LocalBusinessRouting.queries(LocalDiscoveryQuery(area,"sky zone"))
        assertEquals("sky zone Melbourne, Florida",plan.first());assertEquals(2,plan.size)
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
        val body="""{"features":[{"properties":{"osm_type":"N","osm_id":6389146342,"osm_key":"leisure","osm_value":"trampoline_park","name":"Sky Zone","street":"Bass Pro Drive Northeast","housenumber":"750","city":"Palm Bay","state":"FL","postcode":"32905"},"geometry":{"coordinates":[-80.6527693,28.0334911]}},{"properties":{"osm_type":"R","osm_id":12,"osm_key":"place","name":"Unrelated town"},"geometry":{"coordinates":[-80.6,28.1]}}]}"""
        val place=LocalPlaceSources.photon(body,1).businesses.single()
        assertEquals("osm:node/6389146342",place.id);assertTrue(LocalDiscoveryFilter.business(LocalDiscoveryQuery(area),place))
        val request=LocalPlaceSources.photonUrl(LocalDiscoveryQuery(area,"sky zone")).toHttpUrl();assertNotNull(request.queryParameter("bbox"));assertEquals("20",request.queryParameter("limit"))
    }
    @Test fun passiveContactFallbackReadsAnExplicitAddressAndRejectsOtherStates() {
        val body="""<title>Burger Inn</title><meta property='og:site_name' content='Burger Inn'><meta name='description' content='Roadside burgers'><div>1819 N Harbor City Blvd<br>Melbourne, FL 32935<br><a href='tel:13212542211'>(321) 254-2211</a></div><footer><a href='mailto:hello@burger.example'>Email</a><a href='https://www.facebook.com'>Facebook</a><a href='https://www.facebook.com/TheBurgerInn'>Our page</a></footer>"""
        val place=LocalDocumentWashing.extract(Payload(body,url,"text/html",200),1,area).businesses.single()
        assertEquals("Burger Inn",place.name);assertEquals("Melbourne",place.locality);assertTrue(place.address.startsWith("1819"));assertEquals(listOf("hello@burger.example"),place.emails)
        assertEquals(listOf("https://www.facebook.com/TheBurgerInn"),place.socials)
        assertTrue(LocalBusinessPages.extract(body.replace("FL 32935","CA 32935"),url,null,1,area).businesses.isEmpty())
        assertTrue(LocalBusinessPages.extract("<title>Burger Inn</title><p>Serving Melbourne</p>",url,null,1,area).businesses.isEmpty())
    }
    @Test fun sameBusinessFusesStreetAbbreviationsAndTheButKeepsOtherBranches() {
        val a=LocalBusiness("a","Burger Inn",address="1819 N Harbor City Blvd, Melbourne, FL 32935",locality="Melbourne",socials=listOf("https://www.facebook.com/live"))
        val b=a.copy(id="b",name="The Burger Inn",address="1819 North Harbor City Boulevard, Melbourne, Florida, United States",socials=listOf("https://www.instagram.com/live/"))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,b));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b)).size)
        assertEquals(2,LocalDiscoveryMerge.business(a,b).socials.size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1820 North Harbor City Boulevard, Melbourne")))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(locality="Orlando",phones=emptyList())))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a.copy(address=a.address+", Suite 1"),b.copy(address=b.address+", Suite 2")))
        val map=a.copy(id="map",address="1819 Harbor City Boulevard, Melbourne",point=LocalPoint(28.1366641,-80.6336082))
        val directory=b.copy(point=LocalPoint(28.1367,-80.6336))
        assertEquals(1,LocalDiscoveryMerge.businesses(listOf(map,directory)).size)
        assertEquals("Restaurant",LocalDiscoveryMerge.business(a.copy(category="Restaurant"),b.copy(category="LocalBusiness")).category)
        val apostrophe=a.copy(name="Francisco’s Mexican Restaurant",address="2447 N Wickham Rd")
        assertTrue(LocalDiscoveryMerge.sameBusiness(apostrophe,apostrophe.copy(id="other",name="Franciscos Mexican Restaurant",address="2447 North Wickham Road, Ste 146")))
    }
    @Test fun coffeeCompanySuffixAndAnOmittedBuildingLetterUseCoordinatesButBranchesRemainDistinct() {
        val a=LocalBusiness("a","Indian River Coffee Co.",address="2112A Sarno Rd",locality="Melbourne",point=LocalPoint(28.121794,-80.6562),phones=listOf("+13212598005"))
        val b=a.copy(id="b",name="Indian River Coffee",address="2112 Sarno Rd",point=LocalPoint(28.1219726,-80.6561172))
        val separate=a.copy(id="c",address="4980 N Wickham Rd, Ste 101",point=LocalPoint(28.189625,-80.67228))
        assertEquals(2,LocalDiscoveryMerge.businesses(listOf(a,b,separate)).size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="2112B Sarno Rd")))
        val premise=a.copy(id="premise",address="Sarno Business Complex, 2112A Sarno Rd, Melbourne, Florida, 32935, US",point=null)
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,premise));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b,premise)).size)
        val menu=b.copy(id="menu",name="Indian River Coffee Company",point=null,phones=listOf("tel:3212598005"))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,menu));assertEquals(1,LocalDiscoveryMerge.businesses(listOf(a,b,premise,menu)).size)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,menu.copy(address="2112B Sarno Rd")))
        val named=a.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("name",a.name,"https://www.openstreetmap.org/node/1",1,LocalEvidenceKind.DIRECTORY)))
        val headline="Highly Rated Indian River Coffee Co., Inc. – Cafe in Melbourne, FL"
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
        val a=LocalBusiness("a","Burger Inn",address="1819 N Harbor City Blvd",locality="Melbourne",point=LocalPoint(28.136674,-80.6335837),phones=listOf("+13212542211"),checkedAt=1,
            evidence=listOf(LocalDiscoveryIdentity.evidence("location","28.136674,-80.6335837","https://www.openstreetmap.org/node/1",1,LocalEvidenceKind.DIRECTORY)))
        val b=a.copy(id="b",point=LocalPoint(28.133567,-80.654632),phones=listOf("(321) 254-2211"),checkedAt=2,
            evidence=listOf(LocalDiscoveryIdentity.evidence("location","28.133567,-80.654632","https://directory.example/shop",2,LocalEvidenceKind.DIRECTORY)))
        assertTrue(LocalDiscoveryMerge.sameBusiness(a,b));assertEquals(a.point,LocalDiscoveryMerge.business(a,b).point)
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1819 S Harbor City Blvd")))
        assertFalse(LocalDiscoveryMerge.sameBusiness(a,b.copy(address="1819 N Harbor City Blvd SW")))
        val page="""<title>El Sombrero Mexican Cuisine menu - Melbourne FL</title><p>Phone (321) 802-5732 We make ordering easy. Learn more 4990 Stack Boulevard, Melbourne, FL 32901</p>"""
        val parsed=LocalBusinessPages.extract(page,url,null,1,area).businesses.single()
        assertEquals("4990 Stack Boulevard, Melbourne, FL 32901",parsed.address);assertEquals("El Sombrero Mexican Cuisine",parsed.name)
    }
    @Test fun roundupsAndReviewAuthorsCannotBecomeTheListedBusinesses() {
        val list="""<title>Best Tacos in Melbourne, FL – TacosTradition</title><h1>Best Tacos in Melbourne, FL</h1><p>1700 W New Haven Ave, Melbourne, FL 32904</p><p>1800 W Hibiscus Blvd, Melbourne, FL 32901</p><a href='https://real-business.example/'>Website</a>"""
        val result=LocalBusinessPages.extract(list,url,null,1,area)
        assertTrue(result.businesses.isEmpty());assertEquals("https://real-business.example/",result.sources.single().url)
        val review="""<title>Burger Inn Melbourne: My Honest Take After Visiting - Eat The Way</title><meta property='og:site_name' content='Eat The Way'><p>1819 N Harbor City Blvd, Melbourne, FL 32935</p>"""
        assertTrue(LocalBusinessPages.extract(review,url,null,1,area).businesses.isEmpty())
        val directory="""<title>Taco Dive Melbourne - RestaurantJump</title><meta property='og:site_name' content='RestaurantJump'><h1>Taco Dive Melbourne</h1><p>401 E New Haven Ave, Melbourne, FL 32901</p><a href='tel:3214418365'>Call</a>"""
        assertEquals("Taco Dive Melbourne",LocalBusinessPages.extract(directory,url,null,1,area).businesses.single().name)
    }
    @Test fun unknownAndEmptySocialRoutesAreNotWorkingAccounts() {
        val target=Target(Platform.FACEBOOK,TargetKind.PROFILE,"TheBurgerInn","https://www.facebook.com/TheBurgerInn")
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList())))
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target,target.identity))))
        assertTrue(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target,"Burger Inn",description="Open for lunch"))))
        assertFalse(LocalAccountAvailability.usable(target,Page(emptyList(),profile=Profile(target.copy(identity="other",canonicalUrl="https://www.facebook.com/other"),"Other",description="Not this account"))))
    }
    @Test fun directoryPublisherAndExplicitOwnerStaySeparateFromBusinessAccounts() {
        val directory="https://melbournedirections.example/s/coffee"
        val body="""<script type='application/ld+json'>{"@graph":[{"@type":"LocalBusiness","name":"Indian River Coffee Co., Inc.","address":{"addressLocality":"Melbourne"},"sameAs":"https://www.facebook.com/IndianRiverCoffee","owner":{"@type":"Person","name":"Jane Example","sameAs":"https://www.facebook.com/janeOwner"}},{"@type":"WebSite","name":"Melbourne Directions","publisher":{"@type":"Organization","name":"Realty Directions"}}]}</script><footer><a href='https://www.facebook.com/joefrenchrealtor'>Facebook</a><a href='mailto:realtor@example.org'>Email</a></footer>"""
        val data=LocalStructuredData.html(body,directory,1,area)
        val coffee=data.businesses.first {it.name.startsWith("Indian River")}
        assertEquals(listOf("https://www.facebook.com/IndianRiverCoffee"),coffee.socials)
        assertTrue(coffee.emails.orEmpty().isEmpty())
        assertEquals("Jane Example",coffee.related.orEmpty().first {it.role=="owner"}.name)
        assertEquals(listOf("https://www.facebook.com/janeOwner"),coffee.related.orEmpty().first {it.role=="owner"}.accounts)
        assertEquals("Realty Directions",coffee.related.orEmpty().first {it.role=="source publisher"}.name)
        assertFalse(data.sources.any {it.url.contains("joefrench")})
    }
    @Test fun previouslyMisattributedFooterAccountsAreReclassifiedWithoutDroppingTheirOrigin() {
        val link="https://www.facebook.com/joefrenchrealtor"
        val raw=LocalBusiness("a","Indian River Coffee Co., Inc.",socials=listOf(link),evidence=listOf(LocalDiscoveryIdentity.evidence("socials",link,"https://www.melbournedirections.com/s/coffee",1,LocalEvidenceKind.PUBLISHER)))
        val repaired=LocalBusinessOwnership.repairLegacy(raw)
        assertTrue(repaired.socials.isEmpty());assertEquals(link,repaired.related!!.single().accounts.single());assertEquals("source publisher",repaired.related!!.single().role)
        assertEquals(repaired,LocalBusinessOwnership.repairLegacy(repaired))
        val overture=raw.copy(evidence=raw.evidence.map {it.copy(kind=LocalEvidenceKind.DIRECTORY)})
        assertEquals(overture,LocalBusinessOwnership.repairLegacy(overture))
    }
    @Test fun unitsConvertWithoutChangingExistingRadiusAndContactsRejectIntentInjection() {
        assertEquals("15.5 mi",LocalDistanceUnit.MILES.radius(25));assertEquals(40,LocalDistanceUnit.MILES.toKm(25));assertEquals(25,area.radiusKm)
        assertEquals("25 km",LocalDistanceUnit.KILOMETRES.radius(25));assertEquals("mi",LocalDistanceUnit.MILES.id)
        assertEquals("+13212542211",LocalBusinessContact.phone("+1 (321) 254-2211"));assertNull(LocalBusinessContact.phone("tel:321;intent:evil"))
        assertEquals("hello@example.org",LocalBusinessContact.email("mailto:hello@example.org?subject=Hi"));assertNull(LocalBusinessContact.email("hello@example.org\r\nBcc:evil"))
    }
}
