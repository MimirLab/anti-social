/* anti social, GPL-3.0-or-later. Published business/account bridges, without model calls. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

object LocalBusinessSocial {
    private val origins=mapOf("facebook" to "https://www.facebook.com/", "instagram" to "https://www.instagram.com/",
        "twitter" to "https://x.com/", "x" to "https://x.com/", "threads" to "https://www.threads.net/@",
        "tiktok" to "https://www.tiktok.com/@", "telegram" to "https://t.me/", "youtube" to "https://www.youtube.com/@",
        "bluesky" to "https://bsky.app/profile/")
    /** Dataset handles are interpreted only under their explicit platform key.
     * An arbitrary URL, share button, or post cannot become an account. */
    private val separators=Regex("[;\\n]|,\\s*(?=https?://)")
    private val handle=Regex("[A-Za-z0-9_.-]{1,200}")
    fun accounts(platform:String,value:String):List<String> = if(value.isBlank())emptyList()else value.take(16384).split(separators)
        .take(16).mapNotNull {raw->
            val literal=raw.trim();if(literal.isEmpty())return@mapNotNull null
            val url=when {
                literal.contains("://")->LocalDiscoveryIdentity.url(literal)
                SocialLinkHosts.platform("https://$literal")!=null->LocalDiscoveryIdentity.url("https://$literal")
                literal.removePrefix("@").matches(handle)->origins[platform]?.let {LocalDiscoveryIdentity.url(it+literal.removePrefix("@"))}
                else->null
            } ?: return@mapNotNull null
            val expected=when(platform){"twitter","x"->Platform.X;"facebook"->Platform.FACEBOOK;"instagram"->Platform.INSTAGRAM;"threads"->Platform.THREADS;"tiktok"->Platform.TIKTOK;"telegram"->Platform.TELEGRAM;"youtube"->Platform.YOUTUBE;"bluesky"->Platform.BLUESKY;"linkedin"->Platform.LINKEDIN;"mastodon"->Platform.MASTODON;else->null}
            url.takeIf {LocalStructuredData.accountLink(it)&&(expected==null||SocialLinkHosts.platform(it)==expected)}
        }.distinct().take(24)
    fun tagged(value:(String)->String)=listOf("facebook","instagram","twitter","x","youtube","tiktok","mastodon","threads","bluesky","linkedin","telegram")
        .flatMap {platform->listOf(value("contact:$platform"),value(platform)).flatMap {accounts(platform,it)}}.distinct().take(24)

    /** Older directory projections sometimes put Facebook in the website slot.
     * Reclassify the published URL; retain the original field evidence. */
    fun normalize(b:LocalBusiness):LocalBusiness {
        val sites=b.websites.flatMap {LocalDiscoveryIdentity.urls(it)}.distinct()
        val profiles=sites.filter(LocalStructuredData::accountLink)
        return if(profiles.isEmpty()&&sites==b.websites)b else b.copy(websites=sites.filterNot(LocalStructuredData::accountLink).take(12),socials=(b.socials+profiles).distinct().take(24))
    }
    fun sources(b:LocalBusiness)=(b.socials+b.related.orEmpty().filter {it.role=="linked account"}.flatMap {it.accounts}).filter(LocalStructuredData::accountLink).distinct().take(2)
        .map {LocalPublisherSource(it,LocalSourceKind.SOCIAL,b.id,b.name)}

    /** GrowthZone/MemberZone contact cards are scoped to the member, not the
     * surrounding chamber navigation. Match the typed name AND street first. */
    fun directory(body:String,url:String,at:Long,subjects:List<LocalBusiness>):LocalExtraction {
        if(!body.contains("gz-search-details",true))return LocalExtraction()
        val page=Jsoup.parse(body,url)
        val updates=page.select(".gz-search-details[itemscope]").take(8).mapNotNull {node->
            if(node.attr("itemtype").substringAfterLast('/')!="LocalBusiness")return@mapNotNull null
            val name=node.selectFirst("[itemprop=name]")?.let {it.attr("content").ifBlank {it.text()}}.orEmpty()
            val street=node.selectFirst("[itemprop=streetAddress]")?.text().orEmpty()
            val owner=subjects.singleOrNull {LocalBusinessOwnership.sameBrand(name,it.name)&&LocalDiscoveryMerge.street(street)!=null&&LocalDiscoveryMerge.street(street)==LocalDiscoveryMerge.street(it.address)} ?: return@mapNotNull null
            val accounts=node.select(".gz-card-social a[href]").take(24).mapNotNull {LocalDiscoveryIdentity.url(it.absUrl("href"),url)}.filter(LocalStructuredData::accountLink).distinct()
            val hours=node.selectFirst(".gz-details-hours")?.clone()?.apply {select(".gz-details-subtitle").remove()}?.text().orEmpty().take(2000)
            val notes=node.selectFirst(".gz-details-driving")?.clone()?.apply {select(".gz-details-subtitle").remove()}?.text().orEmpty().take(2000)
            val fields=listOf("business accounts" to accounts.joinToString(" · "),"hours" to hours,"visit notes" to notes).filter {it.second.isNotBlank()}
                .map {(field,value)->LocalDiscoveryIdentity.evidence(field,value,url,at,LocalEvidenceKind.DIRECTORY)}
            owner.copy(socials=(owner.socials+accounts).distinct().take(24),hours=hours.ifBlank {owner.hours},checkedAt=at,evidence=owner.evidence+fields)
        }
        return LocalExtraction(businesses=updates,sources=updates.flatMap(::sources))
    }

    private val phone=Regex("(?<![\\p{L}\\d])\\+?\\d[\\d() .-]{7,25}\\d(?![\\p{L}\\d])")
    private val email=Regex("[A-Za-z0-9.!#$%&'*+/=_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")
    private val hours=Regex("(?i)^(?:(?:opening|business|store|cafe|coffeehouse|roastery)\\s+)?hours\\s*[:：]\\s*(.{3,400})$|^(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?|daily|every day)\\b.{2,400}$",RegexOption.IGNORE_CASE)
    private val branchUnit=Regex("(?<![\\p{L}\\p{N}])(?:suite\\b|ste\\b|unit\\b|#)\\s*([a-z0-9-]+)",RegexOption.IGNORE_CASE)
    /** Published ownership and a city name do not identify one branch. */
    fun branch(b:LocalBusiness,profile:Profile,shared:Boolean=false):Boolean {
        val body=LocalDocumentWashing.lines(profile.description,8000)
        val phones=phone.findAll(body).mapNotNull {LocalBusinessContact.phone(it.value)}.toList()
        return branch(b,profile,body,phones,shared)
    }
    private fun branch(b:LocalBusiness,profile:Profile,body:String,phones:List<String>,shared:Boolean):Boolean {
        if(shared||!LocalBusinessOwnership.sameBrand(b.name,profile.name))return false
        val phoneMatches=phones.any {p->b.phones.any {LocalBusinessContact.phoneKey(it)==LocalBusinessContact.phoneKey(p)}}
        if(phones.isNotEmpty()&&b.phones.isNotEmpty()&&!phoneMatches)return false
        val locality=LocalDiscoveryIdentity.name(b.locality)
        val location=locality.isNotBlank()&&(" "+LocalDiscoveryIdentity.name(profile.name+" "+body+" "+profile.target.identity)+" ").contains(" $locality ")
        val street=LocalDiscoveryMerge.street(b.address)
        val unit=branchUnit.find(b.address)?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
        val lines=body.lines()
        val addresses=lines.mapIndexedNotNull {index,line->LocalDiscoveryMerge.street(line)?.let {road->
            val adjacent=lines.getOrNull(index+1)?.trim()?.trimEnd(',')?.let {branchUnit.matchEntire(it)}?.groupValues?.get(1)
            road to (branchUnit.find(line)?.groupValues?.get(1) ?: adjacent)?.lowercase(java.util.Locale.ROOT)
        }}.distinct()
        // This extractor cannot assign one global hours block among several
        // published branches, or let a shared call centre overrule an address.
        val streets=addresses.map {it.first}.distinct();val units=addresses.mapNotNull {it.second}.distinct()
        if(streets.size>1||units.size>1||street!=null&&streets.any {it!=street}||unit!=null&&units.any {it!=unit})return false
        return phoneMatches||location&&street!=null&&streets.singleOrNull()==street&&(unit==null||units.singleOrNull()==unit)
    }
    /** Only an already attached, identity-matched profile may contribute. A
     * shared brand account cannot supply branch-specific phones/hours/menus. */
    fun profile(b:LocalBusiness,profile:Profile,routes:DarwinRoutes,at:Long,shared:Boolean=false):LocalExtraction {
        fun matches(url:String)=routes.recognize(url)?.let {AccountLinkage.same(it,profile.target,routes)}==true
        val attached=b.socials.any(::matches)
        val candidate=b.related.orEmpty().any {it.role=="linked account"&&it.accounts.any(::matches)}
        if(!attached&&!candidate)return LocalExtraction()
        val body=LocalDocumentWashing.lines(profile.description,8000)
        val phones=phone.findAll(body).mapNotNull {LocalBusinessContact.phone(it.value)}.distinctBy(LocalBusinessContact::phoneKey).take(4).toList()
        val nameMatches=LocalBusinessOwnership.sameBrand(b.name,profile.name)
        val branch=branch(b,profile,body,phones,shared)
        if(!attached&&!(nameMatches&&branch))return LocalExtraction(businesses=listOf(b),validEmpty=true)
        val supplied=(profile.links.orEmpty()+Subscriptions.urls(profile.description.take(8000))).mapNotNull {LocalDiscoveryIdentity.url(it)}.distinct().take(24)
        val linked=AccountLinkage.profiles(supplied,routes,AccountLinkKind.PROFILE,profile.target.canonicalUrl,at,"Published business profile").map {it.target.canonicalUrl}
        val website=supplied.filter {SocialLinkHosts.platform(it)==null}
        val fields=mutableListOf<LocalFieldEvidence>()
        fun add(field:String,value:String){if(value.isNotBlank())fields+=LocalDiscoveryIdentity.evidence(field,value,profile.target.canonicalUrl,at,if(branch)LocalEvidenceKind.PUBLISHER else LocalEvidenceKind.PUBLIC_POST)}
        val description=if(branch&&b.description.isBlank())LocalDocumentWashing.text(profile.description,2000)else b.description
        if(nameMatches&&!branch)add("account description",LocalDocumentWashing.text(profile.description,2000))
        if(description!=b.description)add("description",description)
        val ownAccounts=if(!attached)listOf(profile.target.canonicalUrl)else emptyList()
        if(ownAccounts.isNotEmpty())add("business accounts",ownAccounts.joinToString(" · "))
        val declaredHours=if(branch)body.lineSequence().map(String::trim).filter {(hours.matches(it)||Regex("(?i)^closed (?:on )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?[.!]?$").matches(it))&&Regex("(?i)\\d|closed|24\\s*/\\s*7").containsMatchIn(it)}.take(8).joinToString(" · ")else ""
        val emails=if(branch)email.findAll(body).mapNotNull {LocalBusinessContact.email(it.value)}.distinct().take(4).toList()else emptyList()
        if(branch){add("phones",phones.joinToString(" · "));add("emails",emails.joinToString(" · "));add("hours",declaredHours)}
        val menu=if(branch)website.filter {Regex("(?i)(?:^|[/_-])menus?(?:[/.?_-]|$)").containsMatchIn(it.toHttpUrlOrNull()?.encodedPath.orEmpty())||LocalMenuRouting.provider(it)!=null}.take(3)else emptyList()
        menu.forEach {add("branch menu",it)}
        // Any other offered website is a follow-up candidate, not yet an
        // official branch website. Its own identity/address must be extracted.
        val freshLinks=linked.filterNot {url->b.socials.any {known->routes.recognize(known)?.let {a->routes.recognize(url)?.let {AccountLinkage.same(a,it,routes)}}==true}}
        val related=if(freshLinks.isNotEmpty())listOf(LocalRelatedEntity(profile.name,if(shared)"brand"else "linked account",freshLinks,profile.target.canonicalUrl))else emptyList()
        val updated=b.copy(description=description,socials=(b.socials+ownAccounts).distinct().take(24),phones=(b.phones+if(branch)phones else emptyList()).distinctBy {LocalBusinessContact.phoneKey(it) ?: it}.take(8),
            emails=(b.emails.orEmpty()+emails).distinct().take(8).takeIf {it.isNotEmpty()},hours=declaredHours.ifBlank {b.hours},evidence=b.evidence+fields,related=(b.related.orEmpty().mapNotNull {e->if(e.role!="linked account")e else e.copy(accounts=e.accounts.filterNot(::matches)).takeIf {it.accounts.isNotEmpty()}}+related).distinct().take(16).takeIf {it.isNotEmpty()},checkedAt=at)
        return LocalExtraction(businesses=listOf(updated),sources=(LocalMenuRouting.known(updated)+website.map {LocalPublisherSource(it,label=b.name,discoveredFrom=profile.target.canonicalUrl)}+sources(updated).filter {it.url!=profile.target.canonicalUrl}).distinctBy {it.url}.take(12))
    }
}
