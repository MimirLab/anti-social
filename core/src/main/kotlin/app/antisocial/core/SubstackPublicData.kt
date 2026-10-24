/* anti social, GPL-3.0-or-later. Public identities and publication feeds, without scripts or sessions. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.StringReader

data class SubstackIdentity(val account:PublicAccount,val description:String="",val links:List<String> = emptyList(),
    val publications:List<PublicAccount> = emptyList(),val readingList:List<PublicAccount> = emptyList(),val complete:Boolean=true)

/** A user and a publication have separate stable identities. An article link
 * is never promoted to the identity of the person who happened to share it. */
object SubstackPublicData {
    const val MAX_DOCUMENT=3*1024*1024
    private val handle=Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
    private val number=Regex("[1-9][0-9]{0,19}")
    private val subdomain=Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val serviceHosts=setOf("www","open","api","support","reader","cdn","assets","publish","publisher-api")
    private fun JsonObject.text(key:String)=get(key)?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString}.getOrNull()}.orEmpty()
    private fun JsonObject.id(key:String)=text(key).takeIf(number::matches)
    private fun JsonObject.obj(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.array(key:String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    fun account(raw:String,kind:AccountLinkKind=AccountLinkKind.PROFILE,source:String?=null):PublicAccount? {
        val u=RssSources.feedUrl(raw.substringBefore('#'))?.toHttpUrlOrNull() ?: return null
        if(u.port!=443)return null
        val host=u.host.removePrefix("www.");val parts=u.pathSegments.filter(String::isNotBlank)
        if(host in setOf("substack.com","open.substack.com")) {
            if(parts.size==1&&parts[0].startsWith('@')&&handle.matches(parts[0].removePrefix("@")))
                return PublicAccount("substack","https://substack.com/@"+parts[0].removePrefix("@").lowercase(),kind=kind,sourceUrl=source)
            if(parts.size==2&&parts[0]=="profile") {
                val id=parts[1].substringBefore('-').takeIf(number::matches) ?: return null
                if(!parts[1].matches(Regex("[1-9][0-9]{0,19}(?:-[A-Za-z0-9_-]{1,160})?")))return null
                return PublicAccount("substack","https://substack.com/profile/"+parts[1],id="user:$id",kind=kind,sourceUrl=source)
            }
            return null
        }
        if(!u.host.endsWith(".substack.com")||parts !in listOf(emptyList<String>(),listOf("feed")))return null
        val name=u.host.removeSuffix(".substack.com")
        if(!subdomain.matches(name)||name in serviceHosts)return null
        return PublicAccount("substack","https://$name.substack.com",kind=kind,sourceUrl=source)
    }
    fun profileRequest(account:PublicAccount):String {
        require(account.provider=="substack"&&SubstackPublicData.account(account.url)!=null)
        val selected=account.id?.takeIf {it.startsWith("user:")}?.removePrefix("user:")?.takeIf(number::matches)
            ?: account.url.toHttpUrlOrNull()!!.pathSegments.single().removePrefix("@").takeIf(handle::matches)
            ?: error("Open a public Substack profile")
        return "https://on.substack.com/api/v1/user/$selected/public_profile"
    }
    private val selected=setOf("id","name","handle","bio","profile_disabled","twitterAccount","userLinks","primaryPublication","publicationUsers","subscriptions","subscriptionsTruncated","publication","pub")
    /** Skip large unneeded arrays and analytics at the JSON reader. Nothing in
     * the discarded fields becomes a saved identity or an inferred association. */
    private fun json(text:String):JsonObject {
        require(text.length<=MAX_DOCUMENT)
        val out=JsonObject()
        JsonReader(StringReader(text)).use {reader->
            reader.strictness=Strictness.STRICT;reader.nestingLimit=32;reader.beginObject()
            while(reader.hasNext()) {
                val key=reader.nextName()
                if(key !in selected){reader.skipValue();continue}
                if(key in setOf("subscriptions","publicationUsers","userLinks")&&reader.peek()==JsonToken.BEGIN_ARRAY) {
                    val values=JsonArray();reader.beginArray();var count=0
                    val limit=if(key=="subscriptions")30 else 24
                    while(reader.hasNext()){if(count++<limit)values.add(JsonParser.parseReader(reader))else reader.skipValue()}
                    reader.endArray();out.add(key,values)
                }else out.add(key,JsonParser.parseReader(reader))
            }
            reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT)
        }
        return out
    }
    private fun checked(payload:Payload):JsonObject {
        if(payload.status==429)throw SourceFailure(HealthStatus.RATE_LIMITED,"The public Substack source is rate limited")
        if(payload.status !in 200..299)throw SourceFailure(HealthStatus.UNAVAILABLE,"Public Substack source returned ${payload.status}",httpStatus=payload.status)
        return json(payload.text)
    }
    private fun publication(o:JsonObject,source:String):PublicAccount? {
        val id=o.id("id") ?: return null;val name=o.text("subdomain").lowercase()
        if(!subdomain.matches(name)||name in serviceHosts)return null
        val alias="https://$name.substack.com"
        val custom=o.text("custom_domain").takeIf {it.isNotBlank()&&o.text("custom_domain_optional")!="true"}?.let {raw->
            val url=(if(raw.startsWith("https://"))raw else "https://$raw").toHttpUrlOrNull()
            url?.takeIf {it.encodedPath=="/"&&it.query==null&&it.port==443&&RssSources.feedUrl(it.toString())!=null}?.toString()?.trimEnd('/')
        }
        val author=o.text("author_handle").takeIf(handle::matches)?.let {"https://substack.com/@$it"}
        return PublicAccount("substack",alias,o.text("name").take(160),"publication:$id",AccountLinkKind.PROFILE,source,
            links=listOfNotNull(author),readerUrl=(custom ?: alias)+"/feed")
    }
    fun publication(requested:PublicAccount,payload:Payload):SubstackIdentity {
        require(account(requested.url)?.provider=="substack")
        require(payload.status in 200..299){"Public publication source returned ${payload.status}"}
        val root=if(payload.text.trimStart().startsWith('<')) {
            require(payload.text.length<=MAX_DOCUMENT);HtmlAdmission.check(payload.text)
            val node=Jsoup.parse(payload.text,payload.finalUrl).selectFirst("script#preloads-json[type=application/json]")
                ?: throw SourceFailure(HealthStatus.EMPTY,"No public Substack publication identity was supplied")
            json(node.data())
        }else checked(payload)
        val pub=root.obj("publication") ?: root.obj("pub") ?: throw SourceFailure(HealthStatus.EMPTY,"No public publication record was supplied")
        val value=publication(pub,requested.url) ?: throw SourceFailure(HealthStatus.EMPTY,"No usable public publication identity")
        require(value.url.equals(requested.url.trimEnd('/'),true)&&
            (requested.id?.startsWith("publication:")!=true||requested.id==value.id)){"A different publication was returned"}
        return SubstackIdentity(value,pub.text("hero_text").ifBlank {pub.text("author_bio")}.take(4000),value.links,listOf(value))
    }
    fun profile(requested:PublicAccount,payload:Payload):SubstackIdentity {
        val root=checked(payload);val id=root.id("id") ?: throw SourceFailure(HealthStatus.EMPTY,"No public Substack user identity")
        val name=root.text("handle").takeIf(handle::matches)
        val expected=requested.id?.takeIf {it.startsWith("user:")}?.removePrefix("user:")
        require(if(expected!=null)id==expected else name?.equals(requested.url.toHttpUrlOrNull()?.pathSegments?.singleOrNull()?.removePrefix("@"),true)==true){"A different Substack profile was returned"}
        require(root.text("profile_disabled")!="true"){"This profile is unavailable"}
        val url=if(name==null)"https://substack.com/profile/$id"else "https://substack.com/@$name"
        val user=requested.copy(url=url,name=root.text("name").take(160),id="user:$id")
        val links=root.array("userLinks").mapNotNull {it.takeIf {v->v.isJsonObject}?.asJsonObject?.text("url")?.let {raw->AccountLinkage.webUrl(raw)}}.toMutableList()
        root.obj("twitterAccount")?.takeIf {it.id("user_id")==id}?.text("screen_name")?.takeIf {it.matches(Regex("[A-Za-z0-9_]{1,15}"))}?.let {links+="https://x.com/$it"}
        val own=listOfNotNull(root.obj("primaryPublication")?.takeIf {it.id("author_id")==id||it.id("primary_user_id")==id||it.id("user_id")==id}?.let {publication(it,url)})+root.array("publicationUsers").mapNotNull {value->
            val o=value.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            if(o.id("user_id")!=id||o.text("public")!="true"||o.text("role")!="admin")null else o.obj("publication")?.let {publication(it,url)}
        }
        val reading=root.array("subscriptions").mapNotNull {value->
            val o=value.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            if(o.id("user_id")!=id||o.text("visibility")!="public")null else o.obj("publication")?.let {publication(it,url)}
        }.distinctBy(PublicAccounts::key).filter {p->own.none {PublicAccounts.key(it)==PublicAccounts.key(p)}}
        return SubstackIdentity(user,root.text("bio").take(4000),links.distinct().take(24),own.distinctBy(PublicAccounts::key).take(8),reading,
            root.text("subscriptionsTruncated")!="true"&&root.array("subscriptions").size<30)
    }
    fun recommendations(owner:PublicAccount,payload:Payload):List<BridgeNeighbor> {
        require(payload.status in 200..299&&owner.id?.startsWith("publication:")==true)
        val expected=owner.id!!.removePrefix("publication:")
        val root=PublicRelations.json(payload.text);require(root.isJsonArray)
        return root.asJsonArray.take(30).mapNotNull {value->
            val o=value.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            if(o.id("recommending_publication_id")!=expected)return@mapNotNull null
            val pub=o.obj("recommendedPublication")?.let {publication(it,owner.url)} ?: return@mapNotNull null
            if(pub.id!=o.id("recommended_publication_id")?.let {"publication:$it"}||pub.id==owner.id)return@mapNotNull null
            BridgeNeighbor(pub,AdjacentKind.FEATURED,"Recommended by this publication",owner.url)
        }.distinctBy {PublicAccounts.key(it.account)}
    }
    fun webLinks(value:SubstackIdentity,routes:DarwinRoutes,at:Long):AccountWebLinks {
        val publications=value.publications.mapNotNull {pub->pub.readerUrl?.let {feed->AccountCandidate(RssSources.target(feed),"Publishes ${pub.name.ifBlank {"a newsletter"}} on Substack",AccountLinkKind.PROFILE,value.account.url,at)}}
        val accounts=AccountLinkage.profiles(value.links,routes,AccountLinkKind.PROFILE,value.account.url,at,"Linked from this Substack profile")+publications
        val hints=value.publications.mapNotNull {pub->pub.readerUrl?.let {feed->AccountNameHint(RssSources.target(feed),pub.name,AccountLinkKind.PROFILE,value.account.url,at)}}
        return AccountWebLinks(accounts,AccountLinkage.websites(value.links,routes),hints,checkedAt=at,sourceUrl=value.account.url,
            publicAccounts=(listOf(value.account)+value.publications+value.links.mapNotNull {PublicAccounts.parse(it,routes,source=value.account.url)}).distinctBy(PublicAccounts::key))
    }
    fun bridge(value:SubstackIdentity,at:Long,recommended:List<BridgeNeighbor> = emptyList()):BridgePage {
        val reading=value.readingList.map {BridgeNeighbor(it,AdjacentKind.FOLLOWS,"Publicly listed subscription",value.account.url)}
        return BridgePage(value.account,value.description,(value.links+value.publications.map {it.url}).distinct().take(24),
            (recommended+reading).distinctBy {it.kind.name+PublicAccounts.key(it.account)}.take(60),at,
            "Published recommendations and public subscriptions; a reading list does not establish common ownership",value.complete,value.publications)
    }
}
