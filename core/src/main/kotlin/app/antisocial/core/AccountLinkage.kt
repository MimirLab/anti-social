/* anti social, GPL-3.0-or-later. Deterministic, bounded public account linkage. */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.util.Locale

/** A public association is a suggestion, never an automatic ownership merge. */
enum class AccountLinkKind { ENTERED, LOCAL, PROFILE, WEBSITE, RECIPROCAL, DIRECTORY, USERNAME }
data class AccountWebsite(val url:String,val label:String)
data class AccountWebLinks(val accounts:List<AccountCandidate>,val websites:List<String>,val nameHints:List<AccountNameHint> = emptyList(),val publisher:PublisherLinks?=null,val checkedAt:Long=0,val sourceUrl:String?=null,val publicAccounts:List<PublicAccount>?=null)

object AccountLinkage {
    const val MAX_DOCUMENT=512*1024
    private val hubs=setOf("linktr.ee","beacons.ai","bio.link","linkin.bio","campsite.bio","solo.to","lnk.bio","taplink.cc")
    private val properties=linkedMapOf(Platform.X to "P2002",Platform.INSTAGRAM to "P2003",Platform.FACEBOOK to "P2013",Platform.BLUESKY to "P12361",Platform.MASTODON to "P4033",Platform.THREADS to "P11892",Platform.TIKTOK to "P7085",Platform.REDDIT to "P4265",Platform.TELEGRAM to "P3789",Platform.YOUTUBE to "P2397")
    fun key(target:Target,routes:DarwinRoutes)=MentionNames.account(target,routes)?.key?.id ?: target.platform.id+":"+target.canonicalUrl.trimEnd('/')
    fun same(a:Target,b:Target,routes:DarwinRoutes)=key(a,routes)==key(b,routes)
    fun valid(target:Target)=if(target.platform==Platform.RSS)target.kind==TargetKind.FEED&&RssSources.feedUrl(target.canonicalUrl)!=null
        else ReaderScope.isFeedSource(target.platform)&&target.kind==TargetKind.PROFILE&&PublicSearchAdapter.profileAllowed(target)
    fun validIdentity(target:Target)=valid(target)||target.platform==Platform.YOUTUBE&&target.kind==TargetKind.PROFILE&&YouTubeUrls.recognize(target.canonicalUrl)?.kind==TargetKind.PROFILE
    fun webUrl(raw:String):String?=Subscriptions.httpsLink(raw)?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.newBuilder()?.fragment(null)?.build()?.toString()
    fun isHub(url:String)=url.toHttpUrlOrNull()?.host?.removePrefix("www.") in hubs
    fun websites(links:List<String>,routes:DarwinRoutes)=links.asSequence().mapNotNull(::webUrl).filter {routes.recognize(it)==null}
        .filterNot {val u=it.toHttpUrlOrNull()!!;u.host in setOf("keybase.io","keyoxide.org","www.wikidata.org")||Regex("(?i)\\.(pdf|jpg|png|mp4|zip)$").containsMatchIn(u.encodedPath)}
        .distinct().sortedBy {if(isHub(it))0 else 1}.take(2).toList()
    fun profiles(links:List<String>,routes:DarwinRoutes,kind:AccountLinkKind,source:String,at:Long,label:String,discoveryRouting:Boolean=false)=Subscriptions.candidates(links,routes,label,discoveryRouting)
        .map {it.copy(kind=kind,sourceUrl=source,checkedAt=at)}

    /** Preserve identity attributes before washing away page navigation. No script,
     * embedded asset or browser code is executed; the HTML is never persisted. */
    fun website(payload:Payload,routes:DarwinRoutes,at:Long,headers:Map<String,List<String>> = emptyMap()):AccountWebLinks {
        if(payload.text.length>MAX_DOCUMENT)return AccountWebLinks(emptyList(),emptyList())
        HtmlAdmission.check(payload.text)
        val page=Jsoup.parse(payload.text,payload.finalUrl)
        val links=mutableListOf<String>();val more=mutableListOf<String>();val names=mutableListOf<AccountNameHint>();val public=mutableListOf<PublicAccount>()
        val path=payload.finalUrl.toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/').lowercase(Locale.ROOT)
        val publisherPage=path.isEmpty()||path in setOf("about","contact","links","bio","connect","home","index.html")
        val identitySelector="a[rel~=me],link[rel~=me],[itemprop=sameAs][href]"
        val selector=if(isHub(payload.finalUrl))"a[href]"else if(publisherPage)"header a[href],footer a[href],[class*=social] a[href],$identitySelector"else identitySelector
        page.select(selector).take(256).filterNot {n->n.parents().any {it.tagName() in setOf("article")||Regex("(?i)(?:^|[-_ ])(?:share|sharing|social-share)(?:$|[-_ ])").containsMatchIn(it.className())}}.forEach {n->
            webUrl(n.absUrl("href"))?.let {links+=it;if(isHub(it))more+=it}
        }
        fun strings(e:JsonElement?):List<String> = when {e==null->emptyList();e.isJsonPrimitive&&e.asJsonPrimitive.isString->listOf(e.asString);e.isJsonArray->e.asJsonArray.take(24).flatMap {strings(it)};else->emptyList()}
        fun inspect(e:JsonElement,depth:Int=0) {
            if(depth>4)return
            if(e.isJsonArray){e.asJsonArray.take(24).forEach {inspect(it,depth+1)};return}
            if(!e.isJsonObject)return
            val o=e.asJsonObject
            val types=strings(o["@type"]).map {it.substringAfterLast('/')}.toSet()
            val identity=types.any {it in setOf("Person","Organization","Corporation","GovernmentOrganization","NGO","LocalBusiness","WebSite","ProfilePage")}
            val declared=strings(o["url"])+strings(o["@id"])
            val pageHost=payload.finalUrl.toHttpUrlOrNull()?.host
            val sameSite=declared.isEmpty()||declared.any {it.startsWith('#')||it.toHttpUrlOrNull()?.host==pageHost}
            val documentKey=webUrl(payload.finalUrl)?.trimEnd('/')
            val describesPage=declared.any {raw->raw.startsWith('#')||webUrl(raw)?.trimEnd('/')==documentKey}
            if(identity&&sameSite&&(publisherPage||depth==0||describesPage)) {
                val sameAs=strings(o["sameAs"]);links+=sameAs
                val name=strings(o["name"]).singleOrNull()
                val group=java.security.MessageDigest.getInstance("SHA-256").digest(o.toString().toByteArray()).take(16).joinToString(""){"%02x".format(it)}
                public+=sameAs.mapNotNull {PublicAccounts.parse(it,routes,name.orEmpty(),AccountLinkKind.WEBSITE,payload.finalUrl,group,sameAs.take(24))}
                if(name!=null)profiles(sameAs,routes,AccountLinkKind.WEBSITE,payload.finalUrl,at,"").forEach {c->
                    MentionNames.account(c.target,routes)?.let {account->MentionNames.sanitizeDisplayName(name,account.key)}?.let {
                        names+=AccountNameHint(c.target,it,AccountLinkKind.WEBSITE,payload.finalUrl,at,group)
                    }
                }
            }
            o["@graph"]?.let {inspect(it,depth+1)}
            if("ProfilePage" in types)o["mainEntity"]?.let {inspect(it,depth+1)}
        }
        page.select("script[type=application/ld+json]").take(8).forEach {n->if(n.data().length<=64*1024)runCatching {JsonParser.parseString(n.data())}.getOrNull()?.let {inspect(it)}}
        val label="Linked from "+payload.finalUrl.toHttpUrlOrNull()?.host.orEmpty()
        public+=links.mapNotNull {PublicAccounts.parse(it,routes,kind=AccountLinkKind.PROFILE,source=payload.finalUrl)}
        return AccountWebLinks(profiles(links,routes,AccountLinkKind.WEBSITE,payload.finalUrl,at,label),more.distinct().take(2),names.distinctBy {key(it.target,routes)}.take(32),PublicRelations.publisher(page,payload.finalUrl,headers),at,payload.finalUrl,public.distinctBy(PublicAccounts::key).take(48))
    }
    private fun handle(target:Target)=when(target.platform) {
        Platform.YOUTUBE->target.identity.removePrefix("channel/").takeIf {it.matches(Regex("UC[A-Za-z0-9_-]{22}"))}
        Platform.REDDIT->target.identity.removePrefix("user/").removePrefix("u/")
        Platform.MASTODON->target.canonicalUrl.toHttpUrlOrNull()?.let {it.encodedPath.trim('/').removePrefix("@")+"@"+it.host}
        else->target.identity.removePrefix("profile/").removePrefix("@").takeIf {it.matches(Regex("[A-Za-z0-9_.@:-]{1,256}"))}
    }
    fun wikidataUrl(target:Target):String? {
        val property=properties[target.platform] ?: return null;val name=handle(target) ?: return null
        return "https://www.wikidata.org/w/api.php".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("action","query").addQueryParameter("list","search").addQueryParameter("srsearch","haswbstatement:$property=$name")
            .addQueryParameter("srnamespace","0").addQueryParameter("srlimit","3").addQueryParameter("format","json").addQueryParameter("formatversion","2").build().toString()
    }
    fun wikidataEntitiesUrl(body:String):String? {
        if(body.length>MAX_DOCUMENT)return null
        val ids=runCatching {JsonParser.parseString(body).asJsonObject["query"].asJsonObject["search"].asJsonArray.take(3)
            .mapNotNull {it.asJsonObject["title"]?.asString?.takeIf {id->id.matches(Regex("Q[1-9][0-9]*"))}}.distinct()}.getOrNull().orEmpty()
        if(ids.isEmpty())return null
        return "https://www.wikidata.org/w/api.php".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("action","wbgetentities")
            .addQueryParameter("ids",ids.joinToString("|")).addQueryParameter("props","claims|labels").addQueryParameter("languages","en").addQueryParameter("format","json").build().toString()
    }
    private fun propertyTarget(property:String,value:String,routes:DarwinRoutes,discoveryRouting:Boolean=false):Target? {
        val platform=properties.entries.firstOrNull {it.value==property}?.key ?: return null
        val raw=if(platform==Platform.YOUTUBE)"https://www.youtube.com/channel/$value"else if(platform==Platform.MASTODON) {
            val parts=value.removePrefix("@").split('@');if(parts.size!=2)return null
            "https://${parts[1]}/@${parts[0]}"
        }else if(platform==Platform.REDDIT)"u/"+value.removePrefix("user/").removePrefix("u/")else value
        return runCatching {routes.target(platform,raw)}.getOrNull()?.takeIf {it.platform==platform&&(if(discoveryRouting)validIdentity(it)else valid(it))}
    }
    fun wikidata(body:String,seed:Target,routes:DarwinRoutes,at:Long,discoveryRouting:Boolean=false):AccountWebLinks {
        if(body.length>MAX_DOCUMENT)return AccountWebLinks(emptyList(),emptyList())
        val entities=runCatching {JsonParser.parseString(body).asJsonObject["entities"].asJsonObject}.getOrNull() ?: return AccountWebLinks(emptyList(),emptyList())
        if(entities.size()>3)return AccountWebLinks(emptyList(),emptyList())
        data class Claim(val property:String,val value:String,val preferred:Boolean)
        val records=entities.entrySet().take(3).mapNotNull {(id,value)->
            if(!id.matches(Regex("Q[1-9][0-9]*")))return@mapNotNull null
            val claims=runCatching {value.asJsonObject["claims"].asJsonObject}.getOrNull() ?: return@mapNotNull null
            val selected=(properties.values.toList()+listOf("P856","P3040")).flatMap {property->
                claims[property]?.takeIf {it.isJsonArray}?.asJsonArray?.take(16).orEmpty().mapNotNull {e->runCatching {
                    val o=e.asJsonObject
                    if(o["rank"]?.asString=="deprecated"||o["qualifiers"]?.asJsonObject?.has("P582")==true)return@runCatching null
                    val snak=o["mainsnak"].asJsonObject
                    if(snak["snaktype"]?.asString!="value")return@runCatching null
                    val data=snak["datavalue"].asJsonObject["value"]
                    if(!data.isJsonPrimitive||!data.asJsonPrimitive.isString)return@runCatching null
                    Claim(property,data.asString,o["rank"]?.asString=="preferred")
                }.getOrNull()}
            }
            val matching=selected.filter {propertyTarget(it.property,it.value,routes,discoveryRouting)?.let {account->same(account,seed,routes)}==true}
            if(matching.isEmpty())null else Triple(id,selected,if(matching.any {it.preferred})2 else 1)
        }
        // Prefer an explicit preferred seed statement. Several equally ranked
        // entities sharing a handle remain ambiguous, not a manufactured owner.
        val rank=records.maxOfOrNull {it.third} ?: return AccountWebLinks(emptyList(),emptyList())
        val record=records.filter {it.third==rank}.singleOrNull() ?: return AccountWebLinks(emptyList(),emptyList())
        val source="https://www.wikidata.org/wiki/"+record.first
        val accounts=record.second.mapNotNull {propertyTarget(it.property,it.value,routes,discoveryRouting)}.distinctBy {key(it,routes)}.take(20)
            .map {AccountCandidate(it,"Listed together on Wikidata",AccountLinkKind.DIRECTORY,source,at)}
        val websites=record.second.filter {it.property=="P856"}.mapNotNull {webUrl(it.value)}.distinct().take(2)
        val label=runCatching {entities[record.first].asJsonObject["labels"].asJsonObject["en"].asJsonObject["value"].asString}.getOrNull()
        val hints=accounts.mapNotNull {c->
            val account=MentionNames.account(c.target,routes) ?: return@mapNotNull null
            val name=label?.let {MentionNames.sanitizeDisplayName(it,account.key)} ?: return@mapNotNull null
            AccountNameHint(c.target,name,AccountLinkKind.DIRECTORY,source,at)
        }
        return AccountWebLinks(accounts,websites,hints,publicAccounts=if(discoveryRouting)accounts.map {PublicAccounts.from(it.target,label.orEmpty(),AccountLinkKind.DIRECTORY).copy(sourceUrl=source)}+record.second.filter {it.property=="P3040"}.mapNotNull {PublicAccounts.parse("https://soundcloud.com/"+it.value,routes,label.orEmpty(),AccountLinkKind.DIRECTORY,source)}else null)
    }
    fun keybaseUrl(seed:Target):String? {
        val field=when(seed.platform){Platform.X->"twitter";Platform.REDDIT->"reddit";else->return null}
        val name=handle(seed) ?: return null
        return "https://keybase.io/_/api/1.0/user/lookup.json".toHttpUrlOrNull()!!.newBuilder().addQueryParameter(field,name).addQueryParameter("fields","basics,proofs_summary").build().toString()
    }
    fun keybase(body:String,seed:Target,routes:DarwinRoutes,at:Long):List<AccountCandidate> {
        if(body.length>MAX_DOCUMENT)return emptyList()
        val root=runCatching {JsonParser.parseString(body).asJsonObject}.getOrNull() ?: return emptyList()
        if(runCatching {root["status"].asJsonObject["code"].asInt}.getOrNull()!=0)return emptyList()
        val user=runCatching {root["them"].asJsonArray.single().asJsonObject}.getOrNull() ?: return emptyList()
        val proofs=runCatching {user["proofs_summary"].asJsonObject["all"].asJsonArray}.getOrNull() ?: return emptyList()
        val accounts=proofs.take(32).mapNotNull {e->runCatching {
            val o=e.asJsonObject;if(o["state"]?.asInt!=1)return@runCatching null
            val p=when(o["proof_type"]?.asString){"twitter"->Platform.X;"reddit"->Platform.REDDIT;else->return@runCatching null}
            routes.target(p,(if(p==Platform.REDDIT)"u/"else "")+o["nametag"].asString).takeIf(::valid)
        }.getOrNull()}
        if(accounts.none {same(it,seed,routes)})return emptyList()
        val username=runCatching {user["basics"].asJsonObject["username"].asString}.getOrNull()?.takeIf {it.matches(Regex("[A-Za-z0-9_]{1,64}"))} ?: return emptyList()
        return accounts.distinctBy {key(it,routes)}.map {AccountCandidate(it,"Associated by Keybase",AccountLinkKind.DIRECTORY,"https://keybase.io/$username",at)}
    }
    fun usernameTargets(seed:Target,platforms:List<Platform>,routes:DarwinRoutes):List<Target> {
        val username=handle(seed)?.takeIf {it.matches(Regex("[A-Za-z][A-Za-z0-9_]{2,29}"))} ?: return emptyList()
        return platforms.distinct().filter {it!=seed.platform&&it in setOf(Platform.X,Platform.INSTAGRAM,Platform.FACEBOOK,Platform.THREADS,Platform.TIKTOK,Platform.REDDIT,Platform.TELEGRAM,Platform.TRUTH_SOCIAL,Platform.GAB)}
            .mapNotNull {runCatching {routes.target(it,(if(it==Platform.REDDIT)"u/"else "")+username)}.getOrNull()?.takeIf(::valid)}.take(4)
    }
}

/** User initiated only. Independent failures cannot erase already delivered
 * accounts; cancellation stops children and prevents a final cache publication. */
class AccountDiscoveryEngine(private val routes:DarwinRoutes,private val profile:suspend (Target)->Profile?,private val web:suspend (String,String)->Payload,
    private val clock:()->Long=System::currentTimeMillis,private val laneTimeout:Long=6_000L,private val totalTimeout:Long=20_000L,
    private val cachedWebsite:(String)->AccountWebLinks?={null},private val rememberWebsite:(String,AccountWebLinks)->Unit={_,_->},private val discoveryRouting:Boolean=false,
    private val publicIdentity:(suspend (PublicAccount)->AccountWebLinks)?=null) {
    suspend fun discover(seed:Target,known:Profile?=null,local:List<Follow> = emptyList(),extraPlatforms:List<Platform> = emptyList(),onUpdate:(AccountDiscovery)->Unit={}):AccountDiscovery {
        val allowed:(Target)->Boolean=if(discoveryRouting)AccountLinkage::validIdentity else AccountLinkage::valid
        require(allowed(seed))
        val mutex=Mutex();val gate=Semaphore(2);val candidates=linkedMapOf<String,AccountCandidate>();val names=linkedMapOf<String,AccountNameHint>();val sites=linkedSetOf<String>();val public=linkedMapOf<String,PublicAccount>();var name=known?.name?.takeIf(String::isNotBlank) ?: seed.identity
        val existing=local.filter {AccountLinkage.same(it.target,seed,routes)}.map(Subscriptions::id).toSet()
        val confirmed=local.filter {Subscriptions.id(it) in existing}.map {AccountLinkage.key(it.target,routes)}.toSet()
        candidates[AccountLinkage.key(seed,routes)]=AccountCandidate(seed,"Account you opened",AccountLinkKind.ENTERED,seed.canonicalUrl,clock())
        fun snapshot(complete:Boolean)=AccountDiscovery(name,candidates.values.sortedBy {it.kind.ordinal}.take(20),if(complete&&candidates.size==1)"No other accounts were found on the available sources. You can subscribe now or try matching names."else "Choose the accounts that belong together. Suggestions stay unselected.",complete,names.values.toList(),if(discoveryRouting)public.values.take(48)else null)
        suspend fun publish(found:List<AccountCandidate> = emptyList(),hints:List<AccountNameHint> = emptyList(),routesFound:List<PublicAccount> = emptyList())=mutex.withLock {
            if(discoveryRouting)routesFound.take(48).forEach {account->if(public.size<48)public[PublicAccounts.key(account)]=account}
            for(c in found.filter {allowed(it.target)}.take(20)) {
                val key=AccountLinkage.key(c.target,routes);val old=candidates[key]
                if(old==null&&candidates.size<20||old!=null&&c.kind.ordinal<old.kind.ordinal)candidates[key]=c
            }
            hints.take(20).filter {AccountLinkage.key(it.target,routes) in candidates}.forEach {hint->
                val key=AccountLinkage.key(hint.target,routes)+"|"+hint.sourceUrl
                if(names.size<40||key in names)names[key]=hint
            }
            currentCoroutineContext().ensureActive();onUpdate(snapshot(false))
        }
        suspend fun <T> attempt(block:suspend ()->T):T?=try {gate.withPermit {withTimeoutOrNull(laneTimeout){block()}}}catch(e:CancellationException){throw e}catch(_:Exception){null}
        suspend fun site(url:String,depth:Int=0) {
            val clean=AccountLinkage.webUrl(url) ?: return
            val accepted=mutex.withLock {sites.size<3&&sites.add(clean)};if(!accepted)return
            val result=cachedWebsite(clean) ?: attempt {
                val substack=SubstackPublicData.account(clean)
                val parsed=if(substack!=null&&publicIdentity!=null)publicIdentity.invoke(substack)else AccountLinkage.website(web(clean,seed.canonicalUrl),routes,clock())
                parsed.also {currentCoroutineContext().ensureActive();rememberWebsite(clean,it)}
            } ?: return
            publish(result.accounts+(if(discoveryRouting)result.publicAccounts.orEmpty().mapNotNull {a->PublicAccounts.target(a,routes)?.let {AccountCandidate(it,"Linked public account",a.kind,a.sourceUrl,clock())}}else emptyList()),result.nameHints+(if(discoveryRouting)result.publicAccounts.orEmpty().filter {it.name.isNotBlank()&&it.identityGroup!=null&&it.sourceUrl!=null}.mapNotNull {a->PublicAccounts.target(a,routes)?.let {AccountNameHint(it,a.name.take(160),a.kind,a.sourceUrl!!,clock(),a.identityGroup)}}else emptyList()),result.publicAccounts.orEmpty())
            if(depth==0)coroutineScope {result.websites.forEach {launch {site(it,1)}}}
        }
        suspend fun inspect(p:Profile) {
            if(!AccountLinkage.same(p.target,seed,routes))return
            mutex.withLock {if(p.name.isNotBlank())name=p.name.take(160)}
            val links=(p.links.orEmpty()+Subscriptions.urls(p.description.take(16_384))).distinct().take(48)
            val accounts=AccountLinkage.profiles(links,routes,AccountLinkKind.PROFILE,seed.canonicalUrl,clock(),"Linked from ${seed.platform.label} profile",discoveryRouting)
            // A bio can link to another person. Transfer a seed's label only
            // within a reader-confirmed bundle; named sameAs/directory records
            // supply their own identity labels independently.
            val hints=if(p.displayNameSupplied)local.filter {AccountLinkage.key(it.target,routes) in confirmed}.mapNotNull {c->
                val account=MentionNames.account(c.target,routes) ?: return@mapNotNull null
                MentionNames.sanitizeDisplayName(p.name,account.key)?.let {AccountNameHint(c.target,it,AccountLinkKind.LOCAL,seed.canonicalUrl,clock())}
            }else emptyList()
            publish(accounts,hints,if(discoveryRouting)links.mapNotNull {PublicAccounts.parse(it,routes,source=seed.canonicalUrl)}else emptyList())
            coroutineScope {AccountLinkage.websites(links,routes).forEach {launch {site(it)}}}
        }
        publish()
        publish(local.filter {Subscriptions.id(it) in existing}.map {AccountCandidate(it.target,"Already linked in your subscriptions",AccountLinkKind.LOCAL,it.target.canonicalUrl,clock())})
        withTimeoutOrNull(totalTimeout) {supervisorScope {
            if(seed.platform==Platform.RSS)SubstackPublicData.account(seed.canonicalUrl)?.let {launch {site(it.url)}}
            launch {val p=known?.takeIf {AccountLinkage.same(it.target,seed,routes)&&(it.links.orEmpty().isNotEmpty()||it.description.isNotBlank()||!discoveryRouting&&it.displayNameSupplied)} ?: attempt {profile(seed)};if(p!=null)inspect(p)}
            launch {AccountLinkage.wikidataUrl(seed)?.let {url->attempt {web(url,seed.canonicalUrl)}?.let {search->
                val entityUrl=AccountLinkage.wikidataEntitiesUrl(search.text) ?: return@let
                val payload=attempt {web(entityUrl,seed.canonicalUrl)} ?: return@let
                val result=AccountLinkage.wikidata(payload.text,seed,routes,clock(),discoveryRouting);publish(result.accounts+(if(discoveryRouting)result.publicAccounts.orEmpty().mapNotNull {a->PublicAccounts.target(a,routes)?.let {AccountCandidate(it,"Linked public account",a.kind,a.sourceUrl,clock())}}else emptyList()),result.nameHints+(if(discoveryRouting)result.publicAccounts.orEmpty().filter {it.name.isNotBlank()&&it.identityGroup!=null&&it.sourceUrl!=null}.mapNotNull {a->PublicAccounts.target(a,routes)?.let {AccountNameHint(it,a.name.take(160),a.kind,a.sourceUrl!!,clock(),a.identityGroup)}}else emptyList()),result.publicAccounts.orEmpty())
                coroutineScope {result.websites.take(1).forEach {launch {site(it)}}}
            }}}
            launch {AccountLinkage.keybaseUrl(seed)?.let {url->attempt {web(url,seed.canonicalUrl)}?.let {publish(AccountLinkage.keybase(it.text,seed,routes,clock()))}}}
            if(extraPlatforms.isNotEmpty())launch {
                // Let explicit links arrive first; do not spend a slot checking
                // the same candidate twice. At most four supported sites, no crawl.
                delay(300)
                coroutineScope {AccountLinkage.usernameTargets(seed,extraPlatforms,routes).forEach {target->launch {
                    if(mutex.withLock {AccountLinkage.key(target,routes) in candidates})return@launch
                    val p=attempt {profile(target)} ?: return@launch
                    if(!p.displayNameSupplied||!AccountLinkage.same(p.target,target,routes))return@launch
                    val back=AccountLinkage.profiles(p.links.orEmpty()+Subscriptions.urls(p.description.take(16_384)),routes,AccountLinkKind.PROFILE,p.target.canonicalUrl,clock(),"").any {AccountLinkage.same(it.target,seed,routes)}
                    publish(listOf(AccountCandidate(target,if(back)"Links back to this profile"else "Matching username; check before adding",if(back)AccountLinkKind.RECIPROCAL else AccountLinkKind.USERNAME,target.canonicalUrl,clock())))
                }}}
            }
        }}
        currentCoroutineContext().ensureActive()
        return mutex.withLock {snapshot(true).also(onUpdate)}
    }
}
