/* AntiSocial, GPL-3.0-or-later. Published visit information, scoped to the described branch. */
package app.antisocial.core

import com.google.gson.*
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object LocalBusinessFacts {
    private fun rows(v:JsonElement?):List<JsonElement> = when {v==null||v.isJsonNull->emptyList();v.isJsonArray->v.asJsonArray.take(24);else->listOf(v)}
    private fun literal(v:JsonElement?):String=if(v?.isJsonPrimitive==true)v.asString else ""
    private fun text(v:JsonElement?)=LocalDocumentWashing.text(if(v?.isJsonObject==true)literal(v.asJsonObject["name"])else literal(v),1000)
    fun schema(o:JsonObject,url:String,at:Long,kind:LocalEvidenceKind):List<LocalFieldEvidence> {
        val result=mutableListOf<LocalFieldEvidence>()
        fun add(key:String,value:String){if(value.isNotBlank())result+=LocalDiscoveryIdentity.evidence(key,value.take(2000),url,at,kind)}
        fun links(key:String,value:JsonElement?){rows(value).mapNotNull {v->
            val target=if(v.isJsonObject)literal(v.asJsonObject["url"])else literal(v)
            if(target.lowercase() in setOf("true","false","yes","no")||target.any {it.isWhitespace()||it in "{}"})null else LocalDiscoveryIdentity.url(target,url)
        }.distinct().take(3).forEach {add(key,it)}}
        links("menu",o["hasMenu"] ?: o["menu"])
        links("booking",o["acceptsReservations"]?.takeUnless {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean})
        rows(o["potentialAction"]).filter(JsonElement::isJsonObject).forEach {v->val action=v.asJsonObject
            val type=literal(action["@type"]).substringAfterLast('/')
            val field=when(type){"ReserveAction"->"booking";"OrderAction"->"ordering";else->null} ?: return@forEach
            val target=action["target"];links(field,if(target?.isJsonObject==true)target.asJsonObject["urlTemplate"] ?: target.asJsonObject["url"]else target)
        }
        add("cuisine",rows(o["servesCuisine"]).map(::text).filter(String::isNotBlank).joinToString(", "))
        add("price range",text(o["priceRange"]))
        add("payment",rows(o["paymentAccepted"]).map(::text).filter(String::isNotBlank).joinToString(", "))
        val services=(rows(o["availableService"])+rows(o["makesOffer"]).mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("itemOffered")}).map(::text).filter(String::isNotBlank).distinct().take(8)
        add("services",services.joinToString(" · "))
        val amenities=rows(o["amenityFeature"]).filter(JsonElement::isJsonObject).mapNotNull {v->val a=v.asJsonObject;val name=text(a["name"]);val value=literal(a["value"])
            if(name.isBlank()||value.isBlank()||!Regex("accessib|wheelchair|step.free|hearing|braille",RegexOption.IGNORE_CASE).containsMatchIn(name))null else name+": "+when(value){"true"->"Yes";"false"->"No";else->value}
        };add("accessibility",amenities.joinToString(" · "))
        val special=rows(o["specialOpeningHoursSpecification"]).filter(JsonElement::isJsonObject).mapNotNull {v->val h=v.asJsonObject
            val from=literal(h["validFrom"]);val to=literal(h["validThrough"]);val opens=literal(h["opens"]);val closes=literal(h["closes"])
            if(from.isBlank()||opens.isBlank()||closes.isBlank())null else from+(if(to.isNotBlank()&&to!=from)"–$to"else "")+": $opens–$closes"
        };add("special hours",special.joinToString(" · "))
        return result.take(20)
    }
    fun html(body:String,url:String,at:Long,b:LocalBusiness,subjects:List<LocalBusiness>):LocalBusiness? {
        val page=Jsoup.parse(body,url)
        val siteNames=page.select("meta[property=og:site_name]").map {it.attr("content")}
        val declared=subjects.any {LocalDiscoveryMerge.sameBusiness(b,it)&&it.evidence.any {e->e.kind==LocalEvidenceKind.PUBLISHER}}
        val owned=declared||LocalBusinessOwnership.firstParty(b.name,url,siteNames)
        if(!owned)return null
        // A shared chain domain does not make a sibling branch's phone ours.
        if(subjects.any {LocalBusinessOwnership.sameBrand(b.name,it.name)&&it.address.isNotBlank()&&!LocalDiscoveryMerge.sameBusiness(b,it)})return null
        val evidence=mutableListOf<LocalFieldEvidence>()
        fun add(field:String,value:String){evidence+=LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.PUBLISHER)}
        val phones=page.select("a[href^=tel:]").map {it.attr("href").removePrefix("tel:")}.filter {LocalBusinessContact.phone(it)!=null}.distinctBy(LocalBusinessContact::phoneKey)
        val emails=page.select("a[href^=mailto:]").mapNotNull {LocalBusinessContact.email(it.attr("href"))}.distinct().take(8)
        val contactPhones=if(phones.size==1)phones else emptyList()
        if(contactPhones.isNotEmpty())add("phones",contactPhones.joinToString(" · "))
        if(emails.isNotEmpty())add("emails",emails.joinToString(" · "))
        page.select("a[href]").take(1000).forEach {a->
            val label=LocalDocumentWashing.text(a.text(),120).lowercase()
            val key=when {
                label.matches(Regex("(?:our |food |drink |view (?:our )?)?menus?"))->"menu"
                label.matches(Regex("(?:book|reserve)(?: a table| now| online)?|reservations"))->"booking"
                label.matches(Regex("order(?: online| now| pickup| delivery)?"))->"ordering"
                label.matches(Regex("(?:our )?services"))->"services page"
                label.matches(Regex("(?:our |upcoming )?events|calendar"))->"calendar"
                else->null
            } ?: return@forEach
            val link=LocalDiscoveryIdentity.url(a.absUrl("href"),url) ?: return@forEach
            if(SocialLinkHosts.platform(link)==null&&link.toHttpUrlOrNull()?.encodedPath !in listOf("/login","/account"))add(key,link)
        }
        if(evidence.isEmpty())return null
        return b.copy(phones=(b.phones+contactPhones).distinctBy {LocalBusinessContact.phoneKey(it) ?: it}.take(8),emails=(b.emails.orEmpty()+emails).distinct().take(8).takeIf {it.isNotEmpty()},
            checkedAt=at,evidence=b.evidence+evidence.distinctBy {it.field+it.value}.take(20))
    }
}
