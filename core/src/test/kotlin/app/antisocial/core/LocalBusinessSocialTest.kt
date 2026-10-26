package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class LocalBusinessSocialTest {
    private val routes=DarwinRoutes(com.google.gson.JsonParser.parseReader(javaClass.getResourceAsStream("/darwin-routes.json")!!.reader()).asJsonObject,
        UrlCleaner(com.google.gson.JsonParser.parseReader(javaClass.getResourceAsStream("/clean-links.json")!!.reader()).asJsonObject))
    private val at=1792940000000L
    private val area=LocalArea("fl","Sampleton","Sampleton, Florida, United States",LocalPoint(28.608588,-80.809445),25,countryCode="US")
    private val owner=LocalBusiness("coffee","Example Coffee",category="Cafe",address="2112A Example Rd, Sampleton",locality="Sampleton",phones=listOf("2025550101"),socials=listOf("https://www.facebook.com/examplecoffee"))
    private fun profile(body:String,links:List<String> = emptyList())=Profile(routes.recognize(owner.socials.single())!!,"Example Coffee",body,links=links,displayNameSupplied=true)

    @Test fun taggedAccountsKeepNumericFacebookAndNormalizeOnlyExplicitPlatformHandles() {
        assertEquals(listOf("https://www.facebook.com/123456789"),LocalBusinessSocial.accounts("facebook","123456789"))
        assertEquals(listOf("https://www.instagram.com/example"),LocalBusinessSocial.accounts("instagram","@example"))
        assertEquals(listOf("https://www.threads.com/@example"),LocalBusinessSocial.accounts("threads","example"))
        assertEquals(listOf("https://t.me/example"),LocalBusinessSocial.accounts("telegram","example"))
        assertEquals(listOf("https://bsky.app/profile/example.test"),LocalBusinessSocial.accounts("bluesky","example.test"))
        assertEquals(listOf("https://x.com/example"),LocalBusinessSocial.accounts("twitter","twitter.com/example"))
        assertTrue(LocalBusinessSocial.accounts("instagram","https://www.facebook.com/example").isEmpty())
        for(value in listOf("https://facebook.com/","https://facebook.com/sharer.php?u=https://coffee.test","https://instagram.com/p/ABC/","https://youtube.com/shorts/abc","https://tiktok.com/@example/video/123"))
            assertTrue(LocalBusinessSocial.accounts("",value).isEmpty(),value)
    }
    @Test fun osmAndAllThePlacesRetainTheSameCompleteExplicitAccountFields() {
        val tags=mapOf("name" to "Example Coffee","contact:threads" to "@coffee","telegram" to "coffee_news","contact:instagram" to "@coffee","facebook" to "123456789","website" to "https://facebook.com/123456789")
        val json=com.google.gson.Gson().toJson(tags)
        val osm=LocalPlaceSources.overpass("""{"elements":[{"type":"node","id":1,"lat":28.608588,"lon":-80.809445,"tags":$json}]}""","https://www.openstreetmap.org",at).businesses.single()
        assertEquals(4,osm.socials.size);assertTrue(osm.websites.isEmpty());assertTrue(osm.evidence.any {it.field=="socials"&&it.value.contains("123456789")})
        val props=com.google.gson.JsonParser.parseString(json).asJsonObject.apply {addProperty("@spider","example");addProperty("addr:city","Sampleton")}
        val atp=LocalAllThePlaces.parse("""{"features":[{"properties":$props,"geometry":{"type":"Point","coordinates":[-80.809445,28.608588]}}]}""","https://alltheplaces.test/output/example.geojson",at,area).businesses.single()
        assertEquals(osm.socials.toSet(),atp.socials.toSet());assertTrue(atp.websites.isEmpty())
    }
    @Test fun overtureAndOldCachedWebsiteFieldsRecoverExplicitSocialDestinationsWithoutLosingEvidence() {
        val url="https://www.facebook.com/123456789"
        val e=LocalDiscoveryIdentity.evidence("websites",url,"https://directory.test/record",at,LocalEvidenceKind.DIRECTORY)
        val old=owner.copy(socials=emptyList(),websites=listOf(url,"https://examplecoffee.test/"),evidence=listOf(e))
        val repaired=LocalBusinessOwnership.repairLegacy(old)
        assertEquals(listOf(url),repaired.socials);assertEquals(listOf("https://examplecoffee.test/"),repaired.websites);assertEquals(listOf(e),repaired.evidence)
        val data=LocalStructuredData.overture("""{"features":[{"properties":{"id":"gers-social-site","names":{"primary":"Example Coffee"},"websites":["$url"]},"geometry":{"type":"Point","coordinates":[-80.809445,28.608588]}}]}""","https://directory.test/overture",at).businesses.single()
        assertEquals(listOf(url),data.socials);assertTrue(data.websites.isEmpty());assertTrue(data.evidence.any {it.field=="websites"&&it.value==url})
    }
    @Test fun scopedMemberContactsExcludePublisherFooterShareButtonsAndOtherMembers() {
        val html="""<title>Example Coffee | Regional Chamber</title><header><a href='https://facebook.com/chamber'>Facebook</a></header>
            <div class='gz-search-details' itemscope itemtype='https://schema.org/LocalBusiness'>
            <meta itemprop='name' content='Example Coffee'><div itemprop='address' itemscope itemtype='https://schema.org/PostalAddress'>
            <span itemprop='streetAddress'>2112A Example Rd</span><span itemprop='addressLocality'>Sampleton</span><span itemprop='addressRegion'>FL</span></div>
            <a itemprop='url' href='https://examplecoffee.test'>Visit Website</a>
            <div class='gz-card-social'><a href='https://facebook.com/examplecoffee'>Facebook</a><a href='https://instagram.com/examplecoffee'>Instagram</a><a href='https://facebook.com/sharer.php?u=coffee'>Share</a></div>
            <div class='gz-details-hours'><p class='gz-details-subtitle'>Hours:</p><p>Monday - Saturday 7am - 5pm; Closed Sunday</p></div>
            <div class='gz-details-driving'><p class='gz-details-subtitle'>Driving Directions:</p><p>Entrance behind building</p></div></div>
            <footer><a href='https://instagram.com/chamber'>Instagram</a></footer>"""
        val data=LocalDocumentWashing.extract(Payload(html,"https://business.chamber.test/list/member/example-123","text/html",200),at,area)
        val place=data.businesses.single()
        assertEquals(setOf("https://www.facebook.com/examplecoffee","https://www.instagram.com/examplecoffee"),place.socials.toSet())
        assertTrue(place.hours.startsWith("Monday"));assertFalse(place.hours.startsWith("Hours:"))
        assertTrue(place.evidence.any {it.field=="visit notes"&&it.value=="Entrance behind building"})
        assertEquals(2,data.sources.count {it.kind==LocalSourceKind.SOCIAL&&it.businessId==place.id})
        assertTrue(LocalBusinessSocial.directory(html,"https://business.chamber.test/member",at,listOf(owner.copy(address="4980 Meadow Rd, Sampleton"))).businesses.isEmpty())
    }
    @Test fun duplicatePostalAddressObjectsDoNotEraseTheMembersLocationButDifferentBranchesStayUnresolved() {
        val address="""{"@type":"PostalAddress","streetAddress":"2001 14th Ave","addressLocality":"Vero Beach","addressRegion":"FL","postalCode":"00060"}"""
        val body="""<script type='application/ld+json'>{"@type":"LocalBusiness","name":"Coffee House 1420","url":"https://coffee.test","address":[$address,$address]}</script>"""
        val data=LocalStructuredData.html(body,"https://directory.test/member",at)
        assertEquals("2001 14th Ave, Vero Beach, FL, 00060",data.businesses.single().address)
        assertTrue(LocalStructuredData.html(body.replaceFirst("[$address,$address]","[$address,"+address.replace("2001","4000")+"]"),"https://directory.test/member",at).businesses.single().address.isBlank())
    }
    @Test fun attachedLocalProfileFeedsContactsLinkedAccountsAndTheExistingMenuRouter() {
        val p=profile("Sampleton coffeehouse\nPhone: (202) 555-0101\nMonday - Saturday: 6am - 6pm\nSunday: Closed\nEmail: hello@examplecoffee.test",listOf("https://instagram.com/examplecoffee","https://examplecoffee.test/menu.pdf","https://examplecoffee.test/contact"))
        val data=LocalBusinessSocial.profile(owner,p,routes,at)
        val b=data.businesses.single()
        assertEquals(owner.socials,b.socials)
        assertEquals("linked account",b.related!!.single().role)
        val instagram=profile("Sampleton\nPhone: (202) 555-0101").copy(target=routes.recognize("https://instagram.com/examplecoffee")!!)
        val confirmed=LocalBusinessSocial.profile(b,instagram,routes,at).businesses.single()
        assertTrue(confirmed.socials.contains(instagram.target.canonicalUrl));assertTrue(confirmed.related.orEmpty().none {it.role=="linked account"&&it.accounts.contains(instagram.target.canonicalUrl)})
        val wrong=instagram.copy(name="Morgan Example Realtor",description="Sampleton")
        assertEquals(b.socials,LocalBusinessSocial.profile(b,wrong,routes,at).businesses.single().socials)
        assertEquals("hello@examplecoffee.test",b.emails!!.single());assertTrue(b.hours.contains("Sunday: Closed"))
        assertEquals("https://examplecoffee.test/menu.pdf",LocalMenuRouting.known(b).single().url)
        assertTrue(data.sources.any {it.kind==LocalSourceKind.MENU&&it.menuBranch==true})
        assertTrue(data.sources.any {it.url=="https://examplecoffee.test/contact"&&it.businessId==null})
        assertTrue(b.evidence.filter {it.field in setOf("hours","phones","branch menu","business accounts")}.all {it.sourceUrl==p.target.canonicalUrl&&it.kind==LocalEvidenceKind.PUBLISHER})
    }
    @Test fun sharedBrandAccountAndUnattachedProfilesCannotOverwriteLocalVisitDetails() {
        val p=profile("Sampleton\nPhone: 2025550101\nHours: Daily 9am - 5pm",listOf("https://instagram.com/examplecoffee","https://brand.test/menu"))
        val shared=LocalBusinessSocial.profile(owner,p,routes,at,shared=true).businesses.single()
        assertTrue(shared.hours.isEmpty());assertEquals(owner.socials,shared.socials);assertTrue(shared.evidence.none {it.field=="branch menu"})
        assertEquals("brand",shared.related!!.single().role)
        assertTrue(LocalBusinessSocial.profile(owner,p.copy(target=routes.recognize("https://facebook.com/unrelated")!!),routes,at).businesses.isEmpty())
    }
    @Test fun anotherBranchesPublishedTelephoneBlocksLocalHoursEvenInTheSameTown() {
        val p=profile("Sampleton\nPhone: 2025550106\nHours: Daily 9am - 5pm",listOf("https://examplecoffee.test/menu"))
        val b=LocalBusinessSocial.profile(owner,p,routes,at).businesses.single()
        assertEquals(owner.phones,b.phones);assertTrue(b.hours.isEmpty());assertTrue(b.evidence.none {it.field=="branch menu"})
    }
    @Test fun matchedBusinessProfileUpdatesOlderDirectoryHoursButSharedOwnerContactDoesNotProveBusinessIdentity() {
        val old=owner.copy(hours="Monday - Friday: 9am - 5pm",checkedAt=at-1000,evidence=listOf(LocalDiscoveryIdentity.evidence("hours","Monday - Friday: 9am - 5pm","https://directory.test/member",at-1000,LocalEvidenceKind.DIRECTORY)))
        val data=LocalBusinessSocial.profile(old,profile("Phone: 2025550101\nMonday - Saturday: 6am - 6pm\nClosed Sunday"),routes,at).businesses.single()
        val current=LocalDiscoveryMerge.business(old,data)
        assertEquals("Monday - Saturday: 6am - 6pm · Closed Sunday",current.hours)
        val person=LocalBusinessSocial.profile(owner,profile("Phone: 2025550101\nHours: Monday - Friday 10am - 3pm").copy(name="Morgan Example Realtor"),routes,at).businesses.single()
        assertTrue(person.hours.isBlank());assertTrue(person.evidence.none {it.field=="hours"})
    }
    @Test fun unlabeledClaimsDoNotBecomeHoursAndProfileMentionsAreNotIdentityLinks() {
        val data=LocalBusinessSocial.profile(owner,profile("Sampleton\nWe sell 20 coffees every day and work with @otherstore",listOf("https://instagram.com/p/ABC/")),routes,at)
        val b=data.businesses.single();assertTrue(b.hours.isEmpty());assertEquals(owner.socials,b.socials)
        assertTrue(b.evidence.none {it.field=="business accounts"})
    }
}
