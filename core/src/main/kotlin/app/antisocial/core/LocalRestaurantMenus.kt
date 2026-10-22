/* AntiSocial, GPL-3.0-or-later. Read-only, branch-scoped menu routing and inert extraction. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Unknown prices/currency/availability stay unknown. Different sales channels never merge prices. */
data class LocalMenuPrice(val amount:String,val currency:String="",val label:String="")
data class LocalMenuItem(val name:String,val section:String="",val description:String="",
    val prices:List<LocalMenuPrice> = emptyList(),val dietary:List<String> = emptyList(),
    val allergens:List<String> = emptyList(),val availability:String="",val options:List<String> = emptyList())
data class LocalMenu(val sourceUrl:String,val title:String,val channel:String="Published menu",val location:String="",
    val checkedAt:Long,val items:List<LocalMenuItem> = emptyList(),val text:String="",val partial:Boolean=false,
    val modifiedAt:String="",val fingerprint:String="",val sourceHash:String="")

object LocalMenus {
    const val MAX_ITEMS=160
    const val MAX_TEXT=20000
    fun valid(menu:LocalMenu):Boolean = LocalDiscoveryIdentity.url(menu.sourceUrl)!=null&&menu.title.length in 1..100&&
        menu.channel in setOf("Published menu","Dine-in","Pickup","Delivery","Catering","Specials")&&menu.location.length<=2000&&menu.checkedAt>=0&&menu.modifiedAt.length<=64&&
        menu.text.length<=MAX_TEXT&&menu.fingerprint.matches(Regex("[a-f0-9]{64}"))&&(menu.sourceHash.isBlank()||menu.sourceHash.matches(Regex("[a-f0-9]{64}")))&&
        menu.items.size<=MAX_ITEMS&&menu.items.all {item->item.name.length in 1..180&&item.section.length<=100&&item.description.length<=500&&item.prices.size<=8&&
            item.prices.all {p->amount(p.amount)!=null&&p.currency==currency(p.currency)&&p.label.length<=80}&&item.dietary.size<=12&&item.dietary.all {it.length<=300}&&
            item.allergens.size<=16&&item.allergens.all {it.length<=80}&&item.availability in setOf("","Available","Unavailable")&&item.options.size<=8&&item.options.all {it.length<=120}}
    private fun clean(value:String,limit:Int=300)=LocalDocumentWashing.text(value,limit).trim()
    private fun rows(v:JsonElement?):List<JsonElement> = when {v==null||v.isJsonNull->emptyList();v.isJsonArray->v.asJsonArray.take(256);else->listOf(v)}
    private fun str(v:JsonElement?):String=if(v?.isJsonPrimitive==true)runCatching {v.asString}.getOrDefault("")else ""
    private fun text(v:JsonElement?,limit:Int=300)=clean(if(v?.isJsonObject==true)str(v.asJsonObject["name"])else str(v),limit)
    private fun type(o:JsonObject,name:String)=rows(o["@type"]).any {str(it).substringAfterLast('/').substringAfterLast('#')==name}
    private fun amount(v:String)=v.trim().takeIf {it.matches(Regex("\\d{1,5}(?:[.,]\\d{1,2})?"))}
    private fun currency(v:String)=v.takeIf {it.matches(Regex("[A-Z]{3}|[$€£¥₹]"))}.orEmpty()
    fun channel(value:String)=when {
        Regex("\\bdeliver(?:y|ies)\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)->"Delivery"
        Regex("\\b(?:pickup|pick.up|takeout|take.away)\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)->"Pickup"
        Regex("\\bcater(?:ing)?\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)->"Catering"
        Regex("\\b(?:specials?|happy.hour|today)\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)->"Specials"
        Regex("\\b(?:dine.in|in.store)\\b",RegexOption.IGNORE_CASE).containsMatchIn(value)->"Dine-in"
        else->"Published menu"
    }
    fun fingerprint(menu:LocalMenu)=LocalDiscoveryIdentity.hash(menu.copy(checkedAt=0,fingerprint="",sourceHash="").toString())
    /** Refresh replaces a source edition, including removed dishes. Other channels remain separate. */
    fun merge(values:List<LocalMenu>):List<LocalMenu> {
        var budget=40000;var items=MAX_ITEMS
        val editions=values.filter {LocalDiscoveryIdentity.url(it.sourceUrl)!=null&&(it.items.isNotEmpty()||it.text.isNotBlank())}
            .groupBy {it.sourceUrl+"|"+it.channel}.values.flatMap {editions->val latest=editions.maxOf {it.checkedAt};editions.filter {it.checkedAt==latest}.distinctBy {it.title}}
        return editions.filterNot {highlights->highlights.title=="Menu highlights"&&highlights.items.isNotEmpty()&&editions.any {full->
            full.channel==highlights.channel&&full.sourceUrl.toHttpUrlOrNull()?.host==highlights.sourceUrl.toHttpUrlOrNull()?.host&&full.items.size>highlights.items.size&&
                highlights.items.all {item->full.items.any {it.name.equals(item.name,true)&&it.prices==item.prices}}
        }}
            .sortedWith(compareBy<LocalMenu>{it.items.isEmpty()}.thenBy {it.partial}.thenByDescending {it.checkedAt}).take(4).mapNotNull {menu->
                val selected=menu.items.take(items).takeWhile {item->
                    val cost=item.toString().length
                    if(budget<cost)false else {budget-=cost;true}
                };items-=selected.size
                val body=menu.text.take(minOf(MAX_TEXT,budget.coerceAtLeast(0)));budget-=body.length
                if(selected.isEmpty()&&body.isBlank())null else menu.copy(items=selected,text=body,partial=menu.partial||selected.size<menu.items.size||body.length<menu.text.length)
            }
    }
    fun search(menu:LocalMenu)=menu.title+" "+menu.text+" "+menu.items.joinToString(" "){listOf(it.name,it.description,it.section,it.dietary.joinToString(" ")).joinToString(" ")}
    private fun snapshot(url:String,title:String,at:Long,b:LocalBusiness,items:List<LocalMenuItem>,label:String=title,partial:Boolean=false,modified:String="",location:Boolean=false):LocalMenu? {
        val kept=items.distinct().take(MAX_ITEMS)
        if(kept.isEmpty())return null
        val menu=LocalMenu(url,clean(title,100).ifBlank {"Menu"},channel(label),if(location)b.address else "Location prices unconfirmed",at,kept,partial=partial||items.size>MAX_ITEMS,modifiedAt=modified.take(64))
        return menu.copy(fingerprint=fingerprint(menu))
    }
    /** Explicit foreign addresses defeat an endorsed URL, even for the same chain. */
    private fun matches(o:JsonObject,b:LocalBusiness):Boolean {
        val name=text(o["name"])
        if(name.isNotBlank()&&!LocalBusinessOwnership.sameBrand(b.name,name))return false
        val a=o["address"]
        val address=if(a?.isJsonObject==true)listOf("streetAddress","addressLocality","addressRegion","postalCode").joinToString(", "){text(a.asJsonObject[it])}else text(a)
        val street=LocalDiscoveryMerge.street(address);val ours=LocalDiscoveryMerge.street(b.address)
        if(street!=null&&ours!=null&&street!=ours)return false
        val city=if(a?.isJsonObject==true)text(a.asJsonObject["addressLocality"])else ""
        if(city.isNotBlank()&&b.locality.isNotBlank()&&LocalDiscoveryIdentity.name(city)!=LocalDiscoveryIdentity.name(b.locality))return false
        val zip=Regex("\\b\\d{5}(?:-\\d{4})?\\b")
        val theirZip=if(a?.isJsonObject==true)text(a.asJsonObject["postalCode"])else ""
        val ourZip=zip.find(b.address)?.value
        if(theirZip.isNotBlank()&&ourZip!=null&&theirZip!=ourZip)return false
        return true
    }
    private fun country(o:JsonObject,area:LocalArea?):Boolean {
        val expected=area?.countryCode?.uppercase() ?: return true
        val address=o["address"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return true
        val value=text(address["addressCountry"]).uppercase().trim()
        if(value.isBlank())return true
        val code=when(value){"UNITED STATES","UNITED STATES OF AMERICA","USA"->"US";"UNITED KINGDOM","GREAT BRITAIN","UK"->"GB";"AUSTRALIA"->"AU";"CANADA"->"CA";else->value}
        return code==expected||code.length>2&&area.label.contains(value,true)
    }
    private fun identifies(body:String,b:LocalBusiness,area:LocalArea?):Boolean? {
        val root=runCatching {JsonParser.parseString(body)}.getOrNull() ?: return null
        val found=mutableListOf<JsonObject>();var nodes=0
        fun visit(v:JsonElement,depth:Int=0) {
            if(depth>12||nodes++>2048)return
            if(v.isJsonArray)v.asJsonArray.take(256).forEach {visit(it,depth+1)}
            if(v.isJsonObject){val o=v.asJsonObject
                if(o.has("hasMenu")||listOf("Restaurant","CafeOrCoffeeShop","FoodEstablishment").any {type(o,it)})found+=o
                o.entrySet().filter {it.key!="@context"}.take(64).forEach {(_,child)->if(child.isJsonObject||child.isJsonArray)visit(child,depth+1)}
            }
        };visit(root)
        return found.takeIf {it.isNotEmpty()}?.any {matches(it,b)&&country(it,area)}
    }
    private fun branch(o:JsonObject,b:LocalBusiness):Boolean {
        val address=o["address"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val street=address?.get("streetAddress")?.let(::text)?.let(LocalDiscoveryMerge::street)
        val ours=LocalDiscoveryMerge.street(b.address)
        val phone=LocalBusinessContact.phoneKey(str(o["telephone"]))
        return matches(o,b)&&(street!=null&&street==ours||phone!=null&&b.phones.any {LocalBusinessContact.phoneKey(it)==phone})
    }
    private fun offer(o:JsonObject):List<LocalMenuPrice> = rows(o["offers"]).mapNotNull {v->
        if(!v.isJsonObject)return@mapNotNull null
        val offer=v.asJsonObject;val spec=offer["priceSpecification"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val price=amount(str(offer["price"] ?: spec?.get("price"))) ?: return@mapNotNull null
        LocalMenuPrice(price,currency(str(offer["priceCurrency"] ?: spec?.get("priceCurrency"))),text(offer["name"]).take(80))
    }.distinct().take(8)
    private fun schemaItem(o:JsonObject,section:String):LocalMenuItem? {
        val name=text(o["name"],180);if(name.isBlank())return null
        val available=str(o["isAvailable"]).let {when(it){"true"->"Available";"false"->"Unavailable";else->
            rows(o["offers"]).firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("availability")?.let(::str)?.substringAfterLast('/')?.let {status->when(status){"InStock"->"Available";"OutOfStock","SoldOut"->"Unavailable";else->""}}.orEmpty()}}
        return LocalMenuItem(name,clean(section,100),text(o["description"],500),offer(o),
            rows(o["suitableForDiet"]).map {text(it).substringAfterLast('/').removeSuffix("Diet").replace(Regex("([a-z])([A-Z])"),"$1 $2")}.filter(String::isNotBlank).take(12),
            rows(o["allergens"]).map {text(it,80)}.filter(String::isNotBlank).take(16),available,
            rows(o["modifiers"]).map {text(it,120)}.filter(String::isNotBlank).take(8))
    }
    /** JSON is data only. @context, scripts and offered URLs are never executed or fetched here. */
    fun structured(body:String,url:String,at:Long,b:LocalBusiness,endorsed:Boolean=false,label:String="Menu",branchProof:Boolean=false,area:LocalArea?=null):List<LocalMenu> {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val root=runCatching {JsonParser.parseString(body)}.getOrNull() ?: return emptyList()
        val nodes=mutableListOf<JsonObject>();var count=0
        fun visit(v:JsonElement,depth:Int=0) {
            if(depth>12||count++>=2048)return
            if(v.isJsonObject){val o=v.asJsonObject;nodes+=o;o.entrySet().filter {it.key!="@context"}.take(64).forEach {(_,child)->if(child.isJsonArray||child.isJsonObject)visit(child,depth+1)}}
            else if(v.isJsonArray)v.asJsonArray.take(256).forEach {visit(it,depth+1)}
        };visit(root)
        val ids=nodes.filter {str(it["@id"]).isNotBlank()}.associateBy {str(it["@id"])}
        fun resolve(v:JsonElement):JsonObject?=v.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {o->if(o.size()==1&&o.has("@id"))ids[str(o["@id"])]else o}
        val restaurants=nodes.filter {o->o.has("hasMenu")||rows(o["@type"]).any {str(it).substringAfterLast('/') in setOf("Restaurant","CafeOrCoffeeShop","FoodEstablishment","BarOrPub","Brewery","Bakery","FastFoodRestaurant")}}
        val matching=restaurants.filter {matches(it,b)&&country(it,area)}
        if(restaurants.isNotEmpty()&&matching.isEmpty())return emptyList()
        val roots=if(restaurants.isNotEmpty())matching.flatMap {rows(it["hasMenu"] ?: it["menu"])}.mapNotNull(::resolve)
            else if(endorsed)nodes.filter {type(it,"Menu")}else emptyList()
        val menus=roots.mapNotNull {m->
            val items=mutableListOf<LocalMenuItem>();var visited=0
            fun section(o:JsonObject,name:String,depth:Int=0) {
                if(depth>6||visited++>256||items.size>=MAX_ITEMS+1)return
                rows(o["hasMenuItem"]).mapNotNull(::resolve).forEach {item->schemaItem(item,name)?.let(items::add)}
                rows(o["hasMenuSection"]).mapNotNull(::resolve).forEach {s->section(s,text(s["name"]).ifBlank {name},depth+1)}
            };section(m,"")
            snapshot(url,text(m["name"],100).ifBlank {label},at,b,items,label+" "+text(m["name"]),modified=str(m["dateModified"]),location=branchProof||matching.any {branch(it,b)})
        }
        if(menus.isNotEmpty())return merge(menus)
        // Menumigo's documented public JSON uses restaurant + categories, not Schema.org.
        if(url.toHttpUrlOrNull()?.host?.removePrefix("www.")=="menumigo.com") {
            val restaurant=nodes.firstOrNull {it.has("categories")&&it.has("name")} ?: return emptyList()
            if(!matches(restaurant,b)||!country(restaurant,area)||!endorsed)return emptyList()
            val items=rows(restaurant["categories"]).flatMap {v->if(!v.isJsonObject)emptyList()else {
                val c=v.asJsonObject;rows(c["items"]).mapNotNull {row->row.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {item->
                    val name=text(item["name"],180);if(name.isBlank())null else LocalMenuItem(name,text(c["name"],100),text(item["description"],500),
                        amount(str(item["price"]))?.let {listOf(LocalMenuPrice(it,currency(str(item["currency"] ?: restaurant["currency"]))))}.orEmpty(),
                        rows(item["dietaryTags"]).map {text(it,80)}.take(12),rows(item["allergens"]).map {text(it,80)}.take(16),
                        when(str(item["available"])){"false"->"Unavailable";"true"->"Available";else->""},rows(item["variants"]).map {text(it,120)}.take(8))
                }}
            }}
            return listOfNotNull(snapshot(url,"Menu",at,b,items,label,location=branchProof||branch(restaurant,b)))
        }
        return emptyList()
    }
    /** Price and dish must share a small structural record. No global price/name zip. */
    fun html(body:String,url:String,at:Long,b:LocalBusiness,endorsed:Boolean=false,label:String="Menu",branchProof:Boolean=false,area:LocalArea?=null):List<LocalMenu> {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val page=Jsoup.parse(body,url)
        val scripts=page.select("script[type=application/ld+json]").take(24).filter {it.data().length<=256*1024}
        val identities=scripts.mapNotNull {identifies(it.data(),b,area)}
        if(identities.isNotEmpty()&&identities.none {it})return emptyList()
        val json=scripts.flatMap {s->structured(s.data(),url,at,b,endorsed,label,branchProof,area)}
        if(json.isNotEmpty())return merge(json)
        if(!endorsed)return emptyList()
        // Ordering pages often wrap each published dish in a form. Keep its
        // inert text while discarding every control and executable component.
        page.select("form").unwrap()
        page.select("script,style,noscript,template,input,button,select,textarea,nav,header,footer").remove()
        val candidates=page.select("[itemtype$=MenuItem],.menu-item,.menu_item,.menuItem,.menu-item-container,.menu-item-row,[class*=menu-item-card],tr,li").toMutableList()
        page.select(".price,[class*=menu-item-price],[class*=menuItemPrice]").take(256).mapNotNullTo(candidates) {price->
            price.parents().take(3).firstOrNull {node->node.tagName() !in setOf("h2","h3","h4","h5")&&node.text().length in 3..1500&&node.select("h2,h3,h4,h5,.menu-item-name,[class*=menuItemName]").size==1}
        }
        val order=page.allElements.withIndex().associate {it.value to it.index}
        val items=candidates.distinct().sortedBy {order[it] ?: Int.MAX_VALUE}.take(512).mapNotNull {node->
            if(node.text().length>1500||node.select(".price,[class*=menu-item-price]").size>8)return@mapNotNull null
            val priceNodes=node.select(".price,[class*=menu-item-price],[class*=menuItemPrice],[itemprop=price],td:last-child")
            val prices=priceNodes.flatMap {p->priceText(p.attr("content").ifBlank {p.text()})}.distinct().take(8)
            if(prices.isEmpty())return@mapNotNull null
            val heading=node.selectFirst("[itemprop=name],.menu-item-name,.menu_item_name,[class*=menuItemName],h2,h3,h4,h5,td:first-child") ?: return@mapNotNull null
            val clone=heading.clone();clone.select(".price,[class*=price],[itemprop=price]").remove()
            val name=clean(clone.text(),180).trimEnd('.','·',' ')
            if(name.isBlank()||name.lowercase() in setOf("subtotal","total","delivery","tax","service fee","minimum order"))return@mapNotNull null
            val section=node.parents().take(4).firstNotNullOfOrNull {it.selectFirst("h2,h3")?.takeUnless {h->h==heading||h.parents().contains(node)}?.clone()?.apply {select(".badge,.count,[class*=category-qty]").remove()}?.text()}.orEmpty()
            val description=node.selectFirst("[itemprop=description],.description,[class*=menu-item-description],p")?.text().orEmpty()
            LocalMenuItem(name,clean(section,100),clean(description,500),prices)
        }.distinct()
        val title=page.selectFirst("h1")?.text()?.takeIf {it.contains("menu",true)}?.let {clean(it,100)} ?: label
        return listOfNotNull(snapshot(url,title,at,b,items,label,partial=true,location=branchProof))
    }
    fun priceText(value:String):List<LocalMenuPrice> = Regex("(?<![\\d.,])(?:(USD|EUR|GBP|CAD|AUD|[$€£¥₹])\\s*(\\d{1,5}(?:[.,]\\d{1,2})?)|(\\d{1,5}[.,]\\d{2})\\s*(USD|EUR|GBP|CAD|AUD|€|£)?)(?![\\d.,])").findAll(value.take(200)).take(8).map {m->
        if(m.groupValues[2].isNotBlank())LocalMenuPrice(m.groupValues[2],m.groupValues[1])else LocalMenuPrice(m.groupValues[3],m.groupValues[4])
    }.toList()
    fun document(text:String,url:String,at:Long,b:LocalBusiness,label:String,hash:String,partial:Boolean,branchProof:Boolean=false):LocalMenu? {
        val clean=text.lineSequence().map {it.trim().take(500)}.filter(String::isNotBlank).joinToString("\n").take(MAX_TEXT)
        if(clean.isBlank())return null
        // PDF columns have ambiguous row associations. Preserve readable text
        // instead of inventing dish-price pairings or guessing allergens.
        val location=if(branchProof||LocalDiscoveryMerge.street(b.address)?.let {LocalDiscoveryMerge.street(text)==it}==true)b.address else "Location prices unconfirmed"
        val menu=LocalMenu(url,label.take(100).ifBlank {"Menu"},channel(label),location,at,text=clean,partial=partial||text.length>MAX_TEXT,sourceHash=hash)
        return menu.copy(fingerprint=fingerprint(menu))
    }
}

object LocalMenuRouting {
    private val directories=setOf("allmenus.com","menupix.com","restaurantji.com","restaurantguru.com","tripadvisor.com","yelp.com","321linkzter.com")
    fun directory(url:String)=url.toHttpUrlOrNull()?.host?.let {h->directories.any {h==it||h.endsWith(".$it")}}==true
    private val providers=linkedMapOf("toasttab.com" to "Toast","toast.site" to "Toast","chownow.com" to "ChowNow","foodbooking.com" to "FoodBooking",
        "gloriafood.com" to "GloriaFood","popmenu.com" to "Popmenu","getbento.com" to "BentoBox","imenupro.com" to "iMenuPro",
        "menutech.com" to "Menutech","gotab.io" to "GoTab","menumigo.com" to "Menumigo","getmymenuonline.com" to "GetMyMenuOnline",
        "beermenus.com" to "BeerMenus","streetfoodfinder.com" to "StreetFoodFinder","cloveronline.com" to "Clover","s4shops.com" to "SpotOn",
        "allmenus.com" to "Allmenus","menupix.com" to "MenuPix","restaurantji.com" to "Restaurantji")
    fun provider(url:String)=url.toHttpUrlOrNull()?.host?.let {h->providers.entries.firstOrNull {h==it.key||h.endsWith("."+it.key)}?.value}
    fun restaurant(b:LocalBusiness)=b.menus.orEmpty().isNotEmpty()||b.evidence.any {it.field in setOf("menu","branch menu","cuisine","ordering")}||
        Regex("restaurant|cafe|coffee|brewery|\\bbar\\b|barorpub|bakery|food|deli|pizzeria|pizza|diner|burger|taco|ice.?cream",RegexOption.IGNORE_CASE).containsMatchIn(b.category+" "+b.name)
    private fun usable(url:String):Boolean {
        val u=url.toHttpUrlOrNull() ?: return false
        return u.isHttps&&SocialLinkHosts.platform(url)==null&&!Regex("(?:^|/)(?:login|account|cart|checkout|giftcards?|gift-cards?)(?:/|$)|(?:^|/)(?:upload|submit|edit|manage|claim)[^/]*",RegexOption.IGNORE_CASE).containsMatchIn(u.encodedPath)
    }
    private fun sameListing(target:String,origin:String):Boolean {
        val t=target.toHttpUrlOrNull() ?: return false;val o=origin.toHttpUrlOrNull() ?: return false
        if(t.host!=o.host)return false
        val path=o.encodedPath.trimEnd('/')
        if(path.isNotEmpty()&&t.encodedPath.startsWith("$path/")&&Regex("(?:^|/)menus?(?:/|$)",RegexOption.IGNORE_CASE).containsMatchIn(t.encodedPath.removePrefix(path)))return true
        val id=t.queryParameter("id")?.takeIf {it.matches(Regex("[0-9]{4,16}"))} ?: return false
        return o.pathSegments.any {it==id||it.startsWith("$id-")}&&t.encodedPath.contains("menu",true)
    }
    fun admissible(e:LocalFieldEvidence,b:LocalBusiness):Boolean {
        val target=LocalDiscoveryIdentity.url(e.value)?.takeIf(::usable) ?: return false
        return !directory(e.sourceUrl)||sameListing(target,e.sourceUrl)||!directory(target)&&LocalBusinessDetails.verifiedWebsite(b,target)
    }
    fun request(url:String):String {
        val u=url.toHttpUrlOrNull() ?: return url
        val parts=u.pathSegments.filter(String::isNotBlank)
        val slug=when {
            u.host.removePrefix("www.")=="getmymenuonline.com"&&parts.size==1->parts[0]
            u.host.removePrefix("www.")=="menumigo.com"&&parts.size==2&&parts[0] in setOf("menu","m","r")->parts[1]
            else->return url
        }.takeIf {it.matches(Regex("[a-zA-Z0-9_-]{1,100}"))} ?: return url
        return if(u.host.removePrefix("www.")=="menumigo.com")"https://menumigo.com/api/public/restaurants/$slug"else "https://getmymenuonline.com/api/v1/restaurants/$slug/menu"
    }
    fun known(b:LocalBusiness):List<LocalPublisherSource> = b.evidence.filter {it.field in setOf("menu","branch menu","ordering")&&admissible(it,b)}.sortedBy {if(it.field=="branch menu")-2 else if(it.kind==LocalEvidenceKind.DIRECTORY)-1 else 0}.mapNotNull {e->
        LocalDiscoveryIdentity.url(e.value)?.takeIf(::usable)?.let {LocalPublisherSource(it,LocalSourceKind.MENU,b.id,if(e.field=="ordering")"Online menu"else "Menu",discoveredFrom=e.sourceUrl,menuBranch=e.field=="branch menu"||e.kind==LocalEvidenceKind.DIRECTORY)}
    }.distinctBy {it.url}.sortedBy {priority(it,b)}.take(6)
    fun priority(source:LocalPublisherSource,b:LocalBusiness):Int {
        val url=source.url.toHttpUrlOrNull() ?: return 99
        return (if(directory(source.url))25 else if(b.websites.any {it.toHttpUrlOrNull()?.host==url.host})0 else if(provider(source.url)!=null)2 else 6)+
            (if(Regex("(?:^|/)menus?(?:[/.]|$)",RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath))-4 else 0)+
            (if(url.encodedPath.endsWith(".pdf",true))4 else 0)+(if(source.label.contains("order",true))1 else 0)
    }
    /** Follow only offered menu routes. Embedded frames are recognized data
     * sources; they are never loaded as frames or allowed to fetch subresources. */
    fun discover(body:String,url:String,b:LocalBusiness,subjects:List<LocalBusiness>,at:Long,linked:Boolean=false,branchProof:Boolean=false,area:LocalArea?=null):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        if(!restaurant(b))return LocalExtraction()
        val page=Jsoup.parse(body,url)
        fun observed(p:LocalBusiness)=p.evidence.any {it.field=="address"&&it.value.isNotBlank()&&it.sourceUrl==url}
        val matching=subjects.any {LocalDiscoveryMerge.sameBusiness(b,it)&&observed(it)}
        val owned=LocalBusinessOwnership.firstParty(b.name,url,page.select("meta[property=og:site_name]").map {it.attr("content")})
        if(!matching&&!owned&&!linked)return LocalExtraction()
        val siblings=subjects.any {LocalBusinessOwnership.sameBrand(b.name,it.name)&&observed(it)&&!LocalDiscoveryMerge.sameBusiness(b,it)}
        if(linked&&subjects.any(::observed)&&!matching)return LocalExtraction()
        if(siblings&&!matching)return LocalExtraction()
        val evidence=mutableListOf<LocalFieldEvidence>();val sources=mutableListOf<LocalPublisherSource>()
        val path=url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val menuPage=linked||Regex("(?:^|[/_-])menus?(?:[/_.-]|$)",RegexOption.IGNORE_CASE).containsMatchIn(path)
        val phones=page.select("a[href^=tel:]").mapNotNull {LocalBusinessContact.phoneKey(it.attr("href").removePrefix("tel:"))}.distinct()
        val branchKnown=matching||branchProof||phones.size==1&&b.phones.any {LocalBusinessContact.phoneKey(it)==phones.single()}
        val ftr=page.select("script:not([src])").take(32).any {s->Regex("glblLocalEnvironmentMarketplace\\s*=\\s*['\"]fromtherestaurant\\.com['\"]").containsMatchIn(s.data().take(64000))}
        val publisher=owned||ftr&&matching
        fun offer(value:String?,label:String,node:Element?=null) {
            val target=LocalDiscoveryIdentity.url(value,url)?.takeIf(::usable) ?: return
            if(target==url)return
            if(Regex("\\b(?:add|edit|upload|submit|update|claim)\\b.*\\bmenu",RegexOption.IGNORE_CASE).containsMatchIn(label))return
            if(!publisher&&(!linked||directory(url))) {
                val declared=subjects.filter {LocalDiscoveryMerge.sameBusiness(b,it)}.flatMap {it.evidence}.any {it.field in setOf("menu","ordering")&&it.checkedAt==at&&it.sourceUrl==url&&it.value==target}
                val container=node?.parents()?.take(5)?.firstOrNull {it.tagName() !in setOf("body","html")&&it.select("h1,h2,h3").isNotEmpty()}
                val headings=container?.select("h1,h2,h3")?.map {it.text()}?.filterNot {it.lowercase() in setOf("menu","menus","hours","photos","reviews","location","about","order","order online")}.orEmpty()
                val scoped=container!=null&&container.text().length<=4000&&headings.isNotEmpty()&&headings.all {LocalBusinessOwnership.sameBrand(b.name,it)}&&
                    LocalDiscoveryMerge.street(container.text())?.let {it==LocalDiscoveryMerge.street(b.address)}==true
                if(!declared&&!sameListing(target,url)&&!LocalBusinessDetails.verifiedWebsite(b,target)&&!scoped)return
            }
            if(siblings&&node!=null) {
                val container=node.parents().take(5).firstOrNull {LocalDiscoveryMerge.street(it.text())!=null} ?: return
                if(LocalDiscoveryMerge.street(container.text())!=LocalDiscoveryMerge.street(b.address))return
            }
            // One hop through an advertised menu can expose PDFs/hosted menus,
            // but may not recursively wander a directory or navigation tree.
            if(linked&&!owned&&!matching&&provider(target)==null&&!target.substringBefore('?').endsWith(".pdf",true))return
            evidence+=LocalDiscoveryIdentity.evidence(if(branchKnown)"branch menu"else "menu",target,url,at,if(publisher||linked)LocalEvidenceKind.PUBLISHER else LocalEvidenceKind.DIRECTORY)
            sources+=LocalPublisherSource(target,LocalSourceKind.MENU,b.id,label.take(100).ifBlank {"Menu"},discoveredFrom=url,menuBranch=branchKnown)
        }
        page.select("a[href],iframe[src],embed[src],object[data]").take(1000).forEach {node->
            val href=if(node.tagName()=="a")node.attr("href")else node.attr(if(node.tagName()=="object")"data"else "src")
            val label=node.text().ifBlank {node.attr("title")}.take(150)
            val candidate=LocalDiscoveryIdentity.url(href,url) ?: return@forEach
            val words=label+" "+candidate.toHttpUrlOrNull()?.encodedPath.orEmpty()
            val menu=Regex("\\bmenus?\\b|\\b(?:order|ordering|pickup|takeout|delivery)\\b",RegexOption.IGNORE_CASE).containsMatchIn(words.replace(Regex("[-_/]")," "))
            if(menu||provider(candidate)!=null&&(node.tagName()!="a"||label.contains("order",true))||menuPage&&candidate.substringBefore('?').endsWith(".pdf",true))offer(candidate,label,node)
        }
        // GloriaFood advertises the tenant directly in its documented button.
        if(owned||matching)page.select("[data-glf-cuid][data-glf-ruid]").take(2).forEach {node->
            val cuid=node.attr("data-glf-cuid");val ruid=node.attr("data-glf-ruid")
            if(cuid.matches(Regex("[a-zA-Z0-9-]{1,100}"))&&ruid.matches(Regex("[a-zA-Z0-9-]{1,100}")))offer("https://www.foodbooking.com/ordering/restaurant/menu?company_uid=$cuid&restaurant_uid=$ruid","Online menu",node)
        }
        // FromTheRestaurant's public locator advertises its provider and
        // branch ID. Its menu is an ordinary GET, without the ordering form,
        // session cookie, timezone post, location permission or JS runtime.
        val locationUrl=url.toHttpUrlOrNull()
        val parts=locationUrl?.pathSegments.orEmpty().filter(String::isNotBlank)
        if(ftr&&(owned||matching)&&parts.size==2&&parts[1]=="locations"&&parts[0].matches(Regex("[a-z0-9-]{1,100}"))) {
            val buttons=page.select("button[data-lid]").take(32)
            val ids=buttons.map {it.attr("data-lid")}.distinct()
            val selected=buttons.filter {node->
                if(ids.size==1)branchKnown else node.parents().take(5).any {parent->
                    parent.select("button[data-lid]").map {it.attr("data-lid")}.distinct().size==1&&LocalDiscoveryMerge.street(parent.text())?.let {it==LocalDiscoveryMerge.street(b.address)}==true
                }
            }.map {it.attr("data-lid")}.distinct().filter {it.matches(Regex("[0-9]{1,12}"))}
            if(selected.size==1)offer(locationUrl!!.newBuilder().encodedPath("/${parts[0]}/menu/").query(null).addQueryParameter("lid",selected.single()).build().toString(),"Menu")
        }
        val menus=LocalMenus.html(body,url,at,b,owned||matching||linked,if(menuPage)page.selectFirst("h1")?.text()?.takeIf {it.length in 1..100} ?: "Menu"else "Menu highlights",branchKnown,area)
        val updated=b.copy(evidence=b.evidence+evidence.distinctBy {it.value}.take(8),menus=LocalMenus.merge(b.menus.orEmpty()+menus),checkedAt=at)
        return LocalExtraction(businesses=if(menus.isNotEmpty()||evidence.isNotEmpty())listOf(updated)else emptyList(),sources=sources.distinctBy {it.url}.sortedBy {priority(it,b)}.take(if(linked)2 else 4))
    }
}
