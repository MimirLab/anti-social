/* AntiSocial, GPL-3.0-or-later. Passive contact-page facts for sites without JSON-LD. */
package app.antisocial.core

import org.jsoup.Jsoup

object LocalBusinessPages {
    private val states=mapOf("FL" to "Florida","CA" to "California","NY" to "New York","TX" to "Texas","AL" to "Alabama","AK" to "Alaska","AZ" to "Arizona","AR" to "Arkansas","CO" to "Colorado","CT" to "Connecticut","DE" to "Delaware","GA" to "Georgia","HI" to "Hawaii","ID" to "Idaho","IL" to "Illinois","IN" to "Indiana","IA" to "Iowa","KS" to "Kansas","KY" to "Kentucky","LA" to "Louisiana","ME" to "Maine","MD" to "Maryland","MA" to "Massachusetts","MI" to "Michigan","MN" to "Minnesota","MS" to "Mississippi","MO" to "Missouri","MT" to "Montana","NE" to "Nebraska","NV" to "Nevada","NH" to "New Hampshire","NJ" to "New Jersey","NM" to "New Mexico","NC" to "North Carolina","ND" to "North Dakota","OH" to "Ohio","OK" to "Oklahoma","OR" to "Oregon","PA" to "Pennsylvania","RI" to "Rhode Island","SC" to "South Carolina","SD" to "South Dakota","TN" to "Tennessee","UT" to "Utah","VT" to "Vermont","VA" to "Virginia","WA" to "Washington","WV" to "West Virginia","WI" to "Wisconsin","WY" to "Wyoming","DC" to "District of Columbia")
    private val usAddress=Regex("""\b(\d{1,6}[A-Za-z]?\s+(?:[\p{L}.'# -]|\d{1,3}(?:st|nd|rd|th)){2,90}?\s(?:Street|St|Road|Rd|Avenue|Ave|Boulevard|Blvd|Drive|Dr|Lane|Ln|Way|Court|Ct|Highway|Hwy|Parkway|Pkwy)\.?)(?:\s*,?\s*(?:Suite|Ste|Unit|#)\s*[\w-]+)?\s*[,\n ]+\s*([\p{L}][\p{L} .'-]{1,50}?)\s*,\s*([A-Z]{2})\s+(\d{5}(?:-\d{4})?)\b""",RegexOption.IGNORE_CASE)
    fun extract(body:String,url:String,markdown:String?,at:Long,area:LocalArea):LocalExtraction {
        require(body.length<=LocalStructuredData.MAX_DOCUMENT)
        val page=Jsoup.parse(body,url)
        page.select("script,style,noscript,template,iframe,form").remove()
        val leads=page.select("a[href]").filter {it.text().trim().matches(Regex("(?:visit (?:our |the )?)?(?:official )?website",RegexOption.IGNORE_CASE))}
            .mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("href"),url)}.filter {SocialLinkHosts.platform(it)==null}.distinct().take(6).map {LocalPublisherSource(it,label="Linked business website")}
        val title=page.title().substringBefore('|').trim().take(160)
        val heading=page.selectFirst("h1")?.text().orEmpty().trim()
        val rawLabel=if(heading.length in 2..100&&LocalDiscoveryIdentity.name(title).contains(LocalDiscoveryIdentity.name(heading)))heading else title
        val label=rawLabel.replace(Regex("\\s+menu(?:\\s*[-–—|].*)?$",RegexOption.IGNORE_CASE),"").trim()
        val article=page.selectFirst("meta[property=og:type]")?.attr("content")=="article"
        if(label.isBlank()||article||Regex("^(?:best|top)\\b.+\\b(?:in|near)\\b|top \\d+|reviews|my honest take|after visiting|tripadvisor|yelp",RegexOption.IGNORE_CASE).containsMatchIn(label))return LocalExtraction(sources=leads)
        // An explicit contact block or published US postal address is required.
        // Search snippets and prose such as "serving Example City" are not locations.
        val cards=page.select(".h-card,.vcard").take(8).mapNotNull {node->
            val street=node.selectFirst(".p-street-address,.street-address")?.text().orEmpty()
            val locality=node.selectFirst(".p-locality,.locality")?.text().orEmpty()
            if(street.isBlank()||locality.isBlank())null else Triple(street+", "+locality,locality,node.selectFirst(".p-name,.fn,.org")?.text().orEmpty().ifBlank {label})
        }
        // text() separates block elements even in compact/minified HTML;
        // wholeText() can otherwise join a heading directly to a street number.
        val explicit=if(area.countryCode=="US")usAddress.findAll(page.body()?.text().orEmpty().take(160000)).take(24).mapNotNull {m->
            val region=m.groupValues[3].uppercase();val state=states[region] ?: return@mapNotNull null
            if(!area.label.contains(state,true)&&!Regex("\\b$region\\b",RegexOption.IGNORE_CASE).containsMatchIn(area.label))return@mapNotNull null
            val city=m.groupValues[2].trim();Triple("${m.groupValues[1].trim()}, $city, $region ${m.groupValues[4]}",city,label)
        }.toList()else emptyList()
        val addresses=(cards+explicit).distinctBy {LocalDiscoveryMerge.street(it.first) ?: LocalDiscoveryIdentity.name(it.first)}.filter {LocalDiscoveryFilter.locality(area,it.second)}
        // Multiple unscoped addresses/phones may belong to a roundup or a chain.
        // Do not attach every contact to the page publisher. Typed records can
        // describe several branches; this fallback deliberately requires one.
        if(addresses.size!=1)return LocalExtraction(sources=leads)
        val phones=(page.select("a[href^=tel:]").take(16).map {it.attr("href").removePrefix("tel:").substringBefore('?').trim().take(100)}+
            Regex("(?:Phone|Telephone|Tel)\\s*:\\s*([+0-9][0-9() .-]{6,35})",RegexOption.IGNORE_CASE).findAll(page.text().take(32000)).take(4).map {it.groupValues[1].trim()}).filter {it.count(Char::isDigit) in 7..15}.distinctBy {LocalBusinessContact.phone(it)?.removePrefix("+") ?: it}.take(8)
        if(phones.size>3)return LocalExtraction(sources=leads)
        val emails=page.select("a[href^=mailto:]").take(16).mapNotNull {LocalBusinessContact.email(it.attr("href"))}.distinct().take(8)
        val description=page.selectFirst("meta[name=description],meta[property=og:description]")?.attr("content").orEmpty().take(2000)
        val declared=page.select("footer a[href],header a[href],[id=footer] a[href],[class*=footer] a[href],a[rel~=me],a[class*=social][href]").take(128)
            .mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("href"),url)}.filter(LocalStructuredData::accountLink).distinct().take(24)
        val siteNames=listOfNotNull(page.selectFirst("meta[property=og:site_name]")?.attr("content")?.takeIf(String::isNotBlank))+
            page.select("img.logo[alt],header img[alt],[id=logo]").take(8).map {if(it.hasAttr("alt"))it.attr("alt").replace(Regex("\\blogo\\b",RegexOption.IGNORE_CASE),"").trim()else it.text()}.filter(String::isNotBlank)
        val firstParty=LocalBusinessOwnership.firstParty(label,url,siteNames)
        val socials=if(firstParty)declared else emptyList()
        val places=addresses.map {(address,city,name)->
            val identity="contact:$url#"+LocalDiscoveryIdentity.hash(address)
            LocalBusiness("place:"+LocalDiscoveryIdentity.hash(identity),name,description=LocalDocumentWashing.text(description),address=address,locality=city,websites=listOf(url),socials=socials,phones=phones,
                sourceIds=listOf(identity),checkedAt=at,keywords=listOf(page.title().take(300)),emails=emails.takeIf {firstParty&&it.isNotEmpty()},related=if(!firstParty&&declared.isNotEmpty())listOf(LocalRelatedEntity(siteNames.firstOrNull() ?: java.net.URI(url).host,"source publisher",declared,url))else null,
                evidence=mapOf("name" to name,"address" to address,"locality" to city,"phones" to phones.joinToString(" · "),"emails" to if(firstParty)emails.joinToString(" · ")else "","description" to description,"business accounts" to socials.joinToString(" · ")).filterValues(String::isNotBlank).map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,if(firstParty)LocalEvidenceKind.PUBLISHER else LocalEvidenceKind.DIRECTORY)})
        }
        return LocalExtraction(businesses=places,sources=(leads+socials.map {LocalPublisherSource(it,LocalSourceKind.SOCIAL,places.singleOrNull()?.id,label)}).take(12))
    }
}
