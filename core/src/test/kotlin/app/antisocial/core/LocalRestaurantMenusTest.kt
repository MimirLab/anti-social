package app.antisocial.core

import org.junit.Test
import kotlin.test.*

class LocalRestaurantMenusTest {
    private val b=LocalBusiness("coffee","Example Coffee","CafeOrCoffeeShop",address="12 Main St, Melbourne, FL 32901",locality="Melbourne",websites=listOf("https://examplecoffee.test/"))
    private val at=1792600000000L
    private val json="""{"@graph":[{"@type":"Restaurant","name":"Example Coffee","address":{"streetAddress":"12 Main Street","addressLocality":"Melbourne","postalCode":"32901"},"hasMenu":{"@id":"#lunch"}},{"@id":"#lunch","@type":"Menu","name":"Pickup lunch","hasMenuSection":[{"name":"Sandwiches","hasMenuItem":[{"@type":"MenuItem","name":"Mushroom sandwich","description":"Roasted mushrooms","suitableForDiet":"https://schema.org/VegetarianDiet","allergens":["wheat"],"offers":[{"name":"Small","price":"0","priceCurrency":"USD"},{"name":"Large","price":"12.50","priceCurrency":"USD","availability":"https://schema.org/OutOfStock"}]}]}]}]}"""
    @Test fun schemaReferencesSectionsVariantsAndExplicitAllergensSurviveWithoutGuessing() {
        val menu=LocalMenus.structured(json,"https://examplecoffee.test/menu",at,b).single()
        assertTrue(LocalMenus.valid(menu));assertEquals("Pickup",menu.channel)
        val dish=menu.items.single();assertEquals("Sandwiches",dish.section);assertEquals("Mushroom sandwich",dish.name)
        assertEquals(listOf("0","12.50"),dish.prices.map {it.amount});assertEquals(listOf("Small","Large"),dish.prices.map {it.label})
        assertEquals(listOf("Vegetarian"),dish.dietary);assertEquals(listOf("wheat"),dish.allergens)
    }
    @Test fun identicalChainNameAtAnotherStreetCityOrPostalCodeIsRejected() {
        for(body in listOf(json.replace("12 Main Street","88 Other Road"),json.replace("Melbourne","Orlando"),json.replace("32901","32801")))
            assertTrue(LocalMenus.structured(body,"https://ordering.test/menu",at,b,true).isEmpty())
        assertTrue(LocalMenus.structured(json.replace("Example Coffee","Different Restaurant"),"https://ordering.test/menu",at,b,true).isEmpty())
        val area=LocalArea("fl","Melbourne","Melbourne, Florida, United States",LocalPoint(28.08,-80.60),25,countryCode="US")
        assertTrue(LocalMenus.structured(json.replace("\"postalCode\":\"32901\"","\"postalCode\":\"32901\",\"addressCountry\":\"AU\""),"https://ordering.test/menu",at,b,true,area=area).isEmpty())
    }
    @Test fun standaloneMenuNeedsAnEndorsedLocationRouteAndDoesNotFetchRemoteContexts() {
        val body="""{"@context":"https://malicious.invalid/run","@type":"Menu","hasMenuItem":[{"name":"Tea","offers":{"price":"3.50"}},{"name":"Water"}]}"""
        assertTrue(LocalMenus.structured(body,"https://menu.test/a",at,b).isEmpty())
        val menu=LocalMenus.structured(body,"https://menu.test/a",at,b,true).single()
        assertEquals("",menu.items.first().prices.single().currency);assertTrue(menu.items.last().prices.isEmpty())
        assertTrue(menu.items.all {it.allergens.isEmpty()&&it.dietary.isEmpty()&&it.availability.isBlank()})
    }
    @Test fun rawHtmlKeepsPriceWithItsDishAndIgnoresNavigationCartAndScripts() {
        val html="""<nav class='menu-item'><h4>Account</h4><b class='price'>$100.00</b></nav><h1>Lunch Menu</h1><section><h2>Burgers</h2><div><h4><div>Cheeseburger</div><div class='price'>$9.34</div></h4><p>Beef and cheese</p></div><div class='menu-item'><h4>Fries</h4><div class='price'>$4.19</div></div><div class='menu-item'><h4>Subtotal</h4><div class='price'>$13.53</div></div></section><script>throw Error('never run')</script>"""
        assertTrue(LocalMenus.html(html,b.websites.first(),at,b).isEmpty())
        val menu=LocalMenus.html(html,b.websites.first(),at,b,true).single()
        assertEquals(listOf("Cheeseburger","Fries"),menu.items.map {it.name});assertEquals(listOf("9.34","4.19"),menu.items.flatMap {it.prices}.map {it.amount})
        assertEquals("Beef and cheese",menu.items.first().description);assertTrue(menu.partial)
    }
    @Test fun officialPageFindsOnlyOfferedMenuPdfAndOrderingRoutes() {
        val body="""<title>Example Coffee</title><meta property='og:site_name' content='Example Coffee'><a href='/menu'>Menu</a><a href='https://order.toasttab.com/online/example-melbourne'>Order pickup</a><a href='/checkout'>Order now</a><a href='https://malicious.invalid/'>News</a><iframe src='https://menu.imenupro.com/abc'></iframe><script src='https://analytics.invalid/a.js'></script>"""
        val data=LocalMenuRouting.discover(body,b.websites.single(),b,emptyList(),at)
        assertEquals(3,data.sources.size);assertTrue(data.sources.all {it.kind==LocalSourceKind.MENU&&it.businessId==b.id&&it.discoveredFrom==b.websites.single()})
        assertFalse(data.sources.any {it.url.contains("malicious")||it.url.contains("checkout")||it.url.contains("analytics")})
        val menu=LocalMenuRouting.discover("<h1>Menu</h1><a href='https://cdn.test/menu.pdf?v=2'>Click here for PDF</a><a href='/about'>About</a>","https://examplecoffee.test/menu",b,emptyList(),at,true)
        assertEquals("https://cdn.test/menu.pdf?v=2",menu.sources.single().url)
    }
    @Test fun directoryFooterCannotProvideAMenuWithoutMatchingLocation() {
        val html="<footer><a href='https://directory.test/menu'>Menu</a></footer>"
        assertTrue(LocalMenuRouting.discover(html,"https://directory.test/cafes",b,emptyList(),at).sources.isEmpty())
        val sibling=b.copy(id="other",address="88 Other Rd, Melbourne",evidence=listOf(LocalDiscoveryIdentity.evidence("address","88 Other Rd, Melbourne",b.websites.single(),at,LocalEvidenceKind.PUBLISHER)))
        assertTrue(LocalMenuRouting.discover(html,b.websites.single(),b,listOf(sibling),at,true).businesses.isEmpty())
    }
    @Test fun supportedPublicHostAdaptersKeepTheExactOfferedTenantAndNoCredentials() {
        assertEquals("https://getmymenuonline.com/api/v1/restaurants/example-coffee/menu",LocalMenuRouting.request("https://getmymenuonline.com/example-coffee"))
        assertEquals("https://menumigo.com/api/public/restaurants/example-coffee",LocalMenuRouting.request("https://menumigo.com/menu/example-coffee"))
        assertEquals("https://unrelated.test/example-coffee",LocalMenuRouting.request("https://unrelated.test/example-coffee"))
        assertEquals("Toast",LocalMenuRouting.provider("https://order.toasttab.com/online/example"));assertNull(LocalMenuRouting.provider("https://toasttab.com.bad.invalid/a"))
        val data=LocalMenuRouting.discover("<b data-glf-cuid='abc-1' data-glf-ruid='abc-2'>Menu</b>",b.websites.single(),b,emptyList(),at)
        assertEquals("https://www.foodbooking.com/ordering/restaurant/menu?company_uid=abc-1&restaurant_uid=abc-2",data.sources.single().url)
    }
    @Test fun aNewEditionRemovesDishesButDoesNotMixDeliveryAndPickupPrices() {
        val first=LocalMenus.structured(json,b.websites.single(),at,b).single()
        val changed=first.copy(title="New lunch",checkedAt=at+1,items=listOf(first.items.single().copy(name="New dish")))
        val delivery=first.copy(channel="Delivery",title="Delivery",items=listOf(first.items.single().copy(prices=listOf(LocalMenuPrice("20.00","USD")))))
        val merged=LocalMenus.merge(listOf(first,changed,delivery))
        assertEquals(2,merged.size);assertEquals("New dish",merged.single {it.channel=="Pickup"}.items.single().name)
        assertEquals("20.00",merged.single {it.channel=="Delivery"}.items.single().prices.single().amount)
    }
    @Test fun boundedSnapshotsAndPdfTextCannotInventDishPriceAssociations() {
        val first=LocalMenus.structured(json,b.websites.single(),at,b).single()
        val giant=first.copy(items=(0..10000).map {first.items.single().copy(name="Dish $it",description="x".repeat(500))})
        val bounded=LocalMenus.merge(listOf(giant)).single();assertTrue(bounded.partial);assertTrue(bounded.items.size<=LocalMenus.MAX_ITEMS);assertTrue(bounded.toString().length<42000)
        val doc=LocalMenus.document("Tea\nCoffee\n3.00 4.00", "https://cdn.test/menu.pdf",at,b,"Dine-in menu","a".repeat(64),false)!!
        assertTrue(doc.items.isEmpty());assertTrue(doc.text.contains("Coffee"));assertTrue(LocalMenus.valid(doc))
    }
    @Test fun menuFingerprintIgnoresRetrievalTimeAndStableMenusRestAtMostOneDay() {
        val menu=LocalMenus.structured(json,b.websites.single(),at,b).single()
        assertEquals(LocalMenus.fingerprint(menu),LocalMenus.fingerprint(menu.copy(checkedAt=at+1)))
        var history:LocalRouteYield?=null
        repeat(10){n->history=LocalBusinessEnrichment.observe(history,LocalExtraction(businesses=listOf(b.copy(menus=listOf(menu)))),12,at+n*86400000L)}
        assertTrue(history!!.nextAt-history!!.checkedAt<=86400000)
        assertFalse(LocalBusinessEnrichment.missing(b.copy(category="Library",name="Community Library")).contains(LocalBusinessField.MENU))
    }
    @Test fun aSharedMenuDoesNotClaimBranchPricesButAnExplicitAddressCan() {
        val html="<h1>Menu</h1><div class='menu-item'><h4>Tea</h4><span class='price'>$3.00</span></div>"
        val shared=LocalMenuRouting.discover(html,b.websites.single(),b,emptyList(),at).businesses.single().menus!!.single()
        assertEquals("Location prices unconfirmed",shared.location)
        val observed=b.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("address",b.address,b.websites.single(),at,LocalEvidenceKind.PUBLISHER)))
        val local=LocalMenuRouting.discover(html,b.websites.single(),b,listOf(observed),at).businesses.single().menus!!.single()
        assertEquals(b.address,local.location)
    }
    @Test fun conflictingStructuredIdentityCannotFallThroughToUnscopedHtmlPrices() {
        val html="<script type='application/ld+json'>"+json.replace("12 Main Street","88 Other Road")+"</script><div class='menu-item'><h4>Tea</h4><b class='price'>$3.00</b></div>"
        assertTrue(LocalMenus.html(html,"https://ordering.test/wrong-branch",at,b,true).isEmpty())
    }
    @Test fun publicLocatorUsesItsMatchedBranchAndDishFormsAreParsedWithoutSubmittingThem() {
        val url="https://examplecoffee.test/example-coffee/locations/"
        val owner=b.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("address",b.address,url,at,LocalEvidenceKind.PUBLISHER)))
        val locator="""<script>var glblLocalEnvironmentMarketplace = "fromtherestaurant.com";</script><form method='POST'><button data-lid='1'>Pickup</button><input name='token' value='never-send'></form>"""
        val route=LocalMenuRouting.discover(locator,url,b,listOf(owner),at).sources.single()
        assertEquals("https://examplecoffee.test/example-coffee/menu/?lid=1",route.url);assertEquals(true,route.menuBranch)
        val menu=LocalMenus.html("""<h1>Menu</h1><div><h3>Starters<span class='items-in-category-qty'>1</span></h3><form method='POST'><div><h4>Fries <span class='price'>$4.19</span></h4><input name='add-to-cart'><p>Potatoes</p></div><button>Add to cart</button></form></div>""",route.url,at,b,true).single()
        assertEquals("Fries",menu.items.single().name);assertEquals("Starters",menu.items.single().section)
        assertEquals("4.19",menu.items.single().prices.single().amount)
        assertTrue(LocalMenuRouting.discover(locator.replace("data-lid='1'","data-lid='1'")+"<button data-lid='2'>Pickup</button>",url,b,listOf(owner),at).sources.isEmpty())
    }
    @Test fun anUnsupportedPriceFormatCannotBecomeACheaperTruncatedPrice() {
        assertTrue(LocalMenus.priceText("$1,299.00").isEmpty())
        assertTrue(LocalMenus.priceText("$12.999").isEmpty())
        assertEquals(LocalMenuPrice("12,50","EUR"),LocalMenus.priceText("EUR 12,50").single())
    }
    @Test fun directoryRecommendationsAndUploadToolsCannotBecomeThisRestaurantsMenu() {
        val url="https://www.tripadvisor.com/Restaurant_Review-g34433-d4370432-Reviews-Example_Coffee.html"
        val owner=b.copy(evidence=listOf(LocalDiscoveryIdentity.evidence("address",b.address,url,at,LocalEvidenceKind.DIRECTORY)))
        val body="""<h1>Example Coffee</h1><p>12 Main St, Melbourne, FL 32901</p><a href='$url/menu'>Restaurant menu</a><a href='/delivery-Melbourne'>Food delivery</a><aside><h2>Other Restaurant</h2><a href='/Restaurant_Review-g34433-d1234567-Reviews-Other.html'>Order online</a></aside><a href='/upload_menu.php?id=4370432'>Update menu</a>"""
        val data=LocalMenuRouting.discover(body,url,b,listOf(owner),at)
        assertEquals(listOf("$url/menu"),data.sources.map {it.url})
        val polluted=owner.copy(evidence=owner.evidence+listOf("$url/menu","https://www.tripadvisor.com/Restaurant_Review-g34433-d1234567-Reviews-Other.html","https://www.tripadvisor.com/upload_menu.php?id=4370432").map {LocalDiscoveryIdentity.evidence("branch menu",it,url,at,LocalEvidenceKind.DIRECTORY)})
        assertEquals(listOf("$url/menu"),LocalMenuRouting.known(polluted).map {it.url})
        val pix="https://www.menupix.com/florida/restaurants/863663/Example-Coffee"
        assertTrue(LocalMenuRouting.admissible(LocalDiscoveryIdentity.evidence("menu","https://www.menupix.com/menudirectory/menu.php?id=863663",pix,at,LocalEvidenceKind.DIRECTORY),b))
        assertFalse(LocalMenuRouting.admissible(LocalDiscoveryIdentity.evidence("menu","https://www.menupix.com/menudirectory/menu.php?id=999999",pix,at,LocalEvidenceKind.DIRECTORY),b))
    }
    @Test fun rankBeforeCappingAndKeepSeekingPastAFeaturedItemsExcerpt() {
        val directory=(1..12).map {n->LocalDiscoveryIdentity.evidence("branch menu","https://www.menupix.com/menudirectory/menu.php?id=${863660+n}","https://www.menupix.com/florida/restaurants/${863660+n}/Example-Coffee",at,LocalEvidenceKind.DIRECTORY)}
        val full="https://examplecoffee.test/example-coffee/menu/?lid=1"
        val observed=LocalDiscoveryIdentity.evidence("address",b.address,"https://examplecoffee.test/example-coffee/locations/",at,LocalEvidenceKind.DIRECTORY)
        val owner=b.copy(websites=listOf("https://unverified.test/",b.websites.single()),evidence=directory+observed+LocalDiscoveryIdentity.evidence("branch menu",full,"https://examplecoffee.test/example-coffee/locations/",at,LocalEvidenceKind.DIRECTORY),
            menus=listOf(LocalMenu("https://examplecoffee.test/","Menu highlights",checkedAt=at,items=listOf(LocalMenuItem("Tea")),partial=true,fingerprint="a".repeat(64))))
        assertEquals(full,LocalMenuRouting.known(owner).first().url)
        assertTrue(LocalBusinessEnrichment.missing(owner).contains(LocalBusinessField.MENU))
        assertEquals(b.websites.single(),owner.websites.minBy {LocalBusinessDetails.websitePriority(owner,it)})
    }
}
