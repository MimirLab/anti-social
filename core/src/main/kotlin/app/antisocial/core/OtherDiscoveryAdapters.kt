/* AntiSocial, GPL-3.0-or-later. Independently implemented public readers.
 * Protocol field/path references: pixelfed/pixelfed routes/web-api.php,
 * PublicApiController.php (AGPL-3.0); xtrime-ru/TelegramRSS (MIT);
 * DIYgod/RSSHub lib/routes/baidu/tieba/{common,forum,post}.ts (AGPL-3.0).
 * No donor runtime, session, authentication token or third-party code is embedded.
 */
package app.antisocial.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.Base64

/** Each named transport is a distinct data path, not a generic healthy host. */
fun otherDiscoveryAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter?=when {
    source.platform==Platform.PIXELFED&&source.frontend=="pixelfed-public-api" -> PixelfedPublicDiscovery(source,gateway,routes)
    source.platform==Platform.PIXELFED&&source.frontend=="pixelfed-public-comments" -> PixelfedPublicDiscovery(source,gateway,routes,true)
    source.platform==Platform.PIXELFED&&source.frontend=="pixelfed-activitypub" -> PixelfedActivityDiscovery(source,gateway,routes)
    source.platform==Platform.TELEGRAM&&source.frontend=="telegram-rss" -> TelegramRssDiscovery(source,gateway,routes)
    source.platform==Platform.TIEBA&&source.frontend=="tieba-client" -> TiebaClientDiscovery(source,gateway,routes)
    source.platform==Platform.TRUTH_SOCIAL&&source.frontend=="truth-archive-rss" -> TruthArchiveDiscovery(source,gateway,routes)
    else -> null
}

private fun JsonElement?.discoveryObject()=this?.takeIf {it.isJsonObject}?.asJsonObject
private fun JsonObject.ds(key: String)=get(key)?.takeIf {it.isJsonPrimitive}?.asString.orEmpty()
private fun JsonObject.da(key: String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
private fun JsonObject.dobj(key: String)=get(key).discoveryObject() ?: JsonObject()
private fun discoveryJson(payload: Payload)=runCatching {JsonParser.parseString(payload.text)}.getOrElse {throw SourceFailure(HealthStatus.EMPTY,"The source returned no recognizable public data")}
private fun discoveryFailure(message: String): Nothing=throw SourceFailure(HealthStatus.EMPTY,message)
private fun discoverySafe(value: String)=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
private fun discoveryPlain(value: String)=Jsoup.parse(value).text().take(100_000)
private val discoveryCaps=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.FEED,Capability.POST,Capability.MEDIA)
internal fun otherDiscoveryRoot(source: Source,target: Target): String {
    val selected=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid public link")
    return if(source.role==SourceRole.ORIGINAL&&source.platform==Platform.PIXELFED)selected.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')else source.root.trimEnd('/')
}
private data class DiscoveryCursor(val source: String,val target: String,val page: Int=1,val account: String="",val before: String="")
private fun discoveryCursor(value: DiscoveryCursor)="discovery:"+Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(value).toByteArray(Charsets.UTF_8))
private fun discoveryCursor(raw: String?,source: Source,target: Target): DiscoveryCursor? {
    if(raw==null)return null
    require(raw.length<=4096&&raw.startsWith("discovery:")){"Refresh this page to continue"}
    val value=runCatching {Gson().fromJson(String(Base64.getUrlDecoder().decode(raw.removePrefix("discovery:")),Charsets.UTF_8),DiscoveryCursor::class.java)}.getOrNull()
    require(runCatching {value!=null&&value.source==source.key&&value.target==target.canonicalUrl&&value.page in 1..1000&&value.account.matches(Regex("[0-9]{0,40}"))&&value.before.matches(Regex("[0-9]{0,40}"))}.getOrDefault(false)){"Pagination changed the selected source or page"}
    return value
}

private abstract class DiscoveryAdapter(final override val source: Source,protected val gateway: NetworkGateway,protected val routes: DarwinRoutes): DataAdapter {
    protected fun check(target: Target,kinds: Set<TargetKind>) {
        require(source.platform==target.platform){"Selected source belongs to another platform"}
        if(target.kind !in kinds)throw SourceFailure(HealthStatus.UNSUPPORTED,"This public source does not support the selected operation")
    }
    protected fun provenance(payload: Payload,expectedRoot: String=source.root): Provenance {
        if(!DarwinRoutes.matches(URI(payload.finalUrl),expectedRoot))throw SourceFailure(HealthStatus.UNAVAILABLE,"The selected source redirected outside its public pathway")
        return Provenance(source,System.currentTimeMillis(),payload.finalUrl)
    }
    protected fun usable(page: Page): Page {if(page.posts.isEmpty())discoveryFailure("This source returned no readable posts for the selected page");return page}
    protected fun root(target: Target): String {
        // A selected federated server is an origin, never another instance's mirror.
        return otherDiscoveryRoot(source,target)
    }
    override suspend fun feed(target: Target)=fetch(target)
}

private class PixelfedPublicDiscovery(source: Source,gateway: NetworkGateway,routes: DarwinRoutes,private val includeComments: Boolean=false): DiscoveryAdapter(source,gateway,routes) {
    override val capabilities=if(includeComments)setOf(Capability.POST,Capability.COMMENTS,Capability.MEDIA)else discoveryCaps
    override fun accepts(target: Target): Boolean {
        if(target.platform!=Platform.PIXELFED)return false
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank) ?: return false
        return !includeComments&&target.kind==TargetKind.PROFILE&&path.size==1||target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&path.size==3&&path[0]=="p"&&path[2].matches(Regex("[0-9]{1,40}"))
    }
    override fun sentinel(capability: Capability)=if(includeComments&&source.root.toHttpUrlOrNull()?.host=="pixelfed.social")Target(Platform.PIXELFED,TargetKind.POST,"p/admin/627439109860160968","https://pixelfed.social/p/admin/627439109860160968")else if(includeComments)throw SourceFailure(HealthStatus.UNSUPPORTED,"Ordinary public thread reads establish this server's comment route")else Target(Platform.PIXELFED,TargetKind.PROFILE,"pixelfed",source.root.trimEnd('/')+"/pixelfed")
    override suspend fun fetch(target: Target,cursor: String?): Page=read(target,cursor,false)
    override suspend fun profile(target: Target): Page=read(target,null,true)
    private suspend fun read(target: Target,cursor: String?,profileOnly: Boolean): Page {
        if(includeComments&&target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"This public comments pathway needs a selected post")
        check(target,setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD));val base=root(target)
        val path=target.canonicalUrl.toHttpUrlOrNull()!!.pathSegments.filter(String::isNotBlank);val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        if(selected) {
            require(cursor==null){"This post has no feed continuation"}
            // i/web/post links carry no author. Do not substitute a global timeline.
            if(path.size!=3||path[0]!="p"||!path[1].matches(Regex("[A-Za-z0-9_.-]{1,100}"))||!path[2].matches(Regex("[0-9]{1,40}")))throw SourceFailure(HealthStatus.UNSUPPORTED,"This public endpoint needs a post link containing its author")
            val payload=gateway.text("$base/api/pixelfed/v2/profile/${DarwinRoutes.encode(path[1])}/status/${path[2]}",target.canonicalUrl,maxBytes=2L*1024*1024)
            val node=discoveryJson(payload).discoveryObject()?.dobj("status") ?: discoveryFailure("No public Pixelfed post was returned")
            val post=verifiedStatus(node,target,base,path[1],null,provenance(payload,base)) ?: discoveryFailure("The public endpoint returned a different or unreadable post")
            if(ContentIdentity.key(post.platform,post.canonicalUrl)!=ContentIdentity.key(target.platform,target.canonicalUrl))discoveryFailure("The public endpoint returned a different post")
            if(!includeComments)return Page(listOf(post),provenance=provenance(payload,base),notice="Comments may need another source.")
            val accountId=node.dobj("account").ds("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: discoveryFailure("The selected post did not identify its public author account")
            // Despite the route naming its segment username, the upstream
            // controller performs Profile::findOrFail: this is the numeric ID.
            val replies=gateway.text("$base/api/pixelfed/v2/comments/$accountId/status/${path[2]}?limit=20",target.canonicalUrl,maxBytes=2L*1024*1024)
            val records=discoveryJson(replies).discoveryObject()?.get("data")?.takeIf {it.isJsonArray}?.asJsonArray?.toList() ?: discoveryFailure("The public comments route returned no recognizable thread")
            val comments=records.take(100).mapNotNull {it.discoveryObject()}.mapNotNull {record->
                if(record.ds("in_reply_to_id")!=path[2]||record.ds("visibility") !in setOf("public","unlisted"))return@mapNotNull null
                val canonical=record.ds("url").toHttpUrlOrNull() ?: return@mapNotNull null
                if(canonical.host!=target.canonicalUrl.toHttpUrlOrNull()?.host||canonical.port!=target.canonicalUrl.toHttpUrlOrNull()?.port||record.ds("id")==path[2])return@mapNotNull null
                val replyPath=canonical.pathSegments.filter(String::isNotBlank)
                if(replyPath.size!=3||replyPath[0]!="p"||!replyPath[1].equals(record.dobj("account").ds("username"),true)||replyPath[2]!=record.ds("id"))return@mapNotNull null
                val reply=PublicSocialParsing.mastodonStatus(record,Platform.PIXELFED,provenance(replies,base),base)?.takeIf {it.publishedAt!=null} ?: return@mapNotNull null
                reply.copy(parentId=post.id)
            }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
            return Page(listOf(post)+comments,provenance=provenance(replies,base),notice="Up to 20 direct replies, newest first. Earlier and nested replies may need another source.")
        }
        require(path.size==1&&path[0].matches(Regex("[A-Za-z0-9_.-]{1,100}"))){"Enter a public Pixelfed username"}
        val username=path[0];val mark=discoveryCursor(cursor,source,target)
        val lookup=if(mark==null)gateway.text("$base/api/v1/accounts/lookup?acct=${DarwinRoutes.encode(username)}",target.canonicalUrl,maxBytes=256*1024)else null
        lookup?.let {provenance(it,base)}
        val account=lookup?.let {discoveryJson(it).discoveryObject()} ?: JsonObject().apply {addProperty("id",mark!!.account)}
        val id=account.ds("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: discoveryFailure("The source did not identify this public account")
        if(mark==null&&(!account.ds("username").equals(username,true)||account.ds("acct").contains('@')||account.ds("url").toHttpUrlOrNull()?.let {it.host!=target.canonicalUrl.toHttpUrlOrNull()!!.host}==true))discoveryFailure("The source returned a different public account")
        val profile=if(mark==null)PublicSocialParsing.mastodonProfile(account,target,base)else null
        if(profileOnly)return Page(emptyList(),profile=profile ?: discoveryFailure("No public profile was returned"),provenance=lookup?.let {provenance(it,base)})
        val payload=gateway.text("$base/api/pixelfed/v1/accounts/$id/statuses?limit=20&exclude_replies=true"+(mark?.before?.let {"&max_id=$it"}.orEmpty()),target.canonicalUrl,maxBytes=2L*1024*1024)
        val data=discoveryJson(payload).takeIf {it.isJsonArray}?.asJsonArray?.toList() ?: discoveryFailure("No public Pixelfed timeline was returned")
        val posts=data.take(100).mapNotNull {it.discoveryObject()?.let {node->verifiedStatus(node,target,base,username,id,provenance(payload,base))}}.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        val last=data.lastOrNull().discoveryObject()?.ds("id")?.takeIf {it.matches(Regex("[0-9]{1,40}"))&&it!=mark?.before}
        return usable(Page(posts,if(data.size>=20&&last!=null)discoveryCursor(DiscoveryCursor(source.key,target.canonicalUrl,account=id,before=last))else null,profile,provenance(payload,base),notice="Comments may need another source."))
    }
    private fun verifiedStatus(node: JsonObject,target: Target,base: String,username: String,accountId: String?,provenance: Provenance): Post? {
        val account=node.dobj("account");val canonical=node.ds("url").toHttpUrlOrNull() ?: return null
        if(canonical.host!=target.canonicalUrl.toHttpUrlOrNull()?.host||!account.ds("username").equals(username,true)||accountId!=null&&account.ds("id")!=accountId)return null
        val parts=canonical.pathSegments.filter(String::isNotBlank)
        if(parts.size!=3||parts[0]!="p"||!parts[1].equals(username,true)||parts[2]!=node.ds("id")||node.ds("visibility") !in setOf("public","unlisted"))return null
        return PublicSocialParsing.mastodonStatus(node,Platform.PIXELFED,provenance,base)?.takeIf {it.publishedAt!=null}
    }
}

/** Per-post federation objects are a real representation; the current outbox is not. */
private class PixelfedActivityDiscovery(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DiscoveryAdapter(source,gateway,routes) {
    override val capabilities=setOf(Capability.POST,Capability.MEDIA)
    override fun accepts(target: Target): Boolean {
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotBlank) ?: return false
        return target.platform==Platform.PIXELFED&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&path.size==3&&path[0]=="p"&&path[1].matches(Regex("[A-Za-z0-9_.-]{1,100}"))&&path[2].matches(Regex("[0-9]{1,40}"))
    }
    override fun sentinel(capability: Capability): Target {
        if(source.root.toHttpUrlOrNull()?.host!="pixelfed.social")throw SourceFailure(HealthStatus.UNSUPPORTED,"Ordinary public post reads establish this server's object route")
        return Target(Platform.PIXELFED,TargetKind.POST,"p/admin/627439109860160968","https://pixelfed.social/p/admin/627439109860160968")
    }
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"An individual ActivityPub object is not a profile feed")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This federation route needs a selected public post containing its author")
        require(cursor==null){"This public object has no continuation"}
        val selected=target.canonicalUrl.toHttpUrlOrNull()!!;val parts=selected.pathSegments.filter(String::isNotBlank);val base=root(target)
        val payload=gateway.text("$base/p/${DarwinRoutes.encode(parts[1])}/${parts[2]}.json",target.canonicalUrl,maxBytes=2L*1024*1024,headers=mapOf("Accept" to "application/activity+json"))
        val node=discoveryJson(payload).discoveryObject() ?: discoveryFailure("No public ActivityPub object was returned")
        val canonical=discoverySafe(node.ds("url").ifBlank {node.ds("id")} ) ?: discoveryFailure("This object has no public post identity")
        val url=canonical.toHttpUrlOrNull()!!;val actor=discoverySafe(node.ds("attributedTo"))?.toHttpUrlOrNull() ?: discoveryFailure("The public object had no author identity")
        val audience=(node.da("to")+node.da("cc")).mapNotNull {it.takeIf {it.isJsonPrimitive}?.asString}
        if(node.ds("type")!="Note"||url.host!=selected.host||url.port!=selected.port||ContentIdentity.key(Platform.PIXELFED,canonical)!=ContentIdentity.key(Platform.PIXELFED,target.canonicalUrl)||
            url.pathSegments.filter(String::isNotBlank)!=parts||actor?.host!=selected.host||actor.pathSegments.filter(String::isNotBlank) !in listOf(listOf("users",parts[1]),listOf(parts[1]))||"https://www.w3.org/ns/activitystreams#Public" !in audience)discoveryFailure("The source returned a different or non-public federation object")
        val published=runCatching {Instant.parse(node.ds("published")).toEpochMilli()}.getOrNull() ?: discoveryFailure("The public object had no usable publication date")
        val media=node.da("attachment").take(16).mapNotNull {it.discoveryObject()}.mapNotNull {attachment->
            val mime=attachment.ds("mediaType");if(!mime.startsWith("image/")&&!mime.startsWith("video/"))return@mapNotNull null
            discoverySafe(attachment.ds("url"))?.let {Media(it,if(mime.startsWith("video/"))"video"else "image",description=discoveryPlain(attachment.ds("name")),mimeType=mime)}}
        val body=listOf(discoveryPlain(node.ds("summary")),discoveryPlain(node.ds("content"))).filter(String::isNotBlank).joinToString("\n\n")
        if(body.isBlank()&&media.isEmpty())discoveryFailure("The federation object contained no readable content")
        val provenance=provenance(payload,base)
        return Page(listOf(Post(ContentIdentity.key(Platform.PIXELFED,canonical),Platform.PIXELFED,canonical,parts[1],body,publishedAt=published,media=media,provenance=provenance)),provenance=provenance,notice="Comments may need another source.")
    }
}

private class TelegramRssDiscovery(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DiscoveryAdapter(source,gateway,routes) {
    override val capabilities=discoveryCaps
    override fun accepts(target: Target)=target.platform==Platform.TELEGRAM&&target.kind in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD)
    override fun sentinel(capability: Capability)=routes.target(Platform.TELEGRAM,"telegram")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        check(target,setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD));val path=target.canonicalUrl.toHttpUrlOrNull()!!.pathSegments.filter {it.isNotBlank()&&it!="s"}
        val channel=path.firstOrNull()?.takeIf {it.matches(Regex("[A-Za-z0-9_]{3,64}"))} ?: error("Enter a public Telegram channel")
        val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val id=if(selected)path.getOrNull(1)?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: error("Invalid public message ID")else null
        val mark=discoveryCursor(cursor,source,target);if(selected)require(cursor==null){"This message has no continuation"}
        val url=source.root.trimEnd('/')+"/rss/$channel"+(if(selected)"?id=$id&limit=1"else if(mark==null)"?limit=20"else "/${mark.page}?limit=20")
        val payload=gateway.text(url,target.canonicalUrl,maxBytes=2L*1024*1024)
        val document=Jsoup.parse(payload.text,"",Parser.xmlParser());val feed=document.selectFirst("rss > channel") ?: discoveryFailure("The relay returned a page shell instead of a feed")
        val feedLink=feed.children().firstOrNull {it.normalName()=="link"}?.text().orEmpty()
        val identity=routes.recognize(feedLink)
        if(identity?.platform!=Platform.TELEGRAM||identity.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.filter {it!="s"}?.firstOrNull()?.equals(channel,true)!=true)discoveryFailure("The relay returned a different public channel")
        val provenance=provenance(payload);val page=Extraction.rss(payload.text,target,provenance,routes)
        val posts=page.posts.filter {post->val p=post.canonicalUrl.toHttpUrlOrNull();p?.host in setOf("t.me","telegram.me")&&p!!.pathSegments.filter {it!="s"}.let {it.size==2&&it[0].equals(channel,true)&&it[1].matches(Regex("[0-9]{1,40}"))&&(!selected||it[1]==id)}&&post.publishedAt!=null}.map {it.copy(id=ContentIdentity.key(it.platform,it.canonicalUrl))}.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        return usable(Page(posts,if(!selected&&posts.size>=20&&(mark?.page ?: 1)<1000)discoveryCursor(DiscoveryCursor(source.key,target.canonicalUrl,(mark?.page ?: 1)+1))else null,if(!selected)Profile(target,feed.selectFirst("title")?.text()?.take(300).orEmpty().ifBlank {channel},feed.selectFirst("description")?.text()?.take(20_000).orEmpty())else null,provenance,notice="This public channel snapshot may be delayed or incomplete. Comment threads are unavailable from this source."))
    }
}

/** This independently maintained archive covers one account, not Truth Social. */
private class TruthArchiveDiscovery(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DiscoveryAdapter(source,gateway,routes) {
    override val capabilities=discoveryCaps-Capability.FEED
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"An archived snapshot cannot establish a successful current subscription refresh")
    override fun accepts(target: Target): Boolean {
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: return false
        val path=url.pathSegments.filter(String::isNotBlank)
        return target.platform==Platform.TRUTH_SOCIAL&&url.host=="truthsocial.com"&&path.firstOrNull().equals("@realDonaldTrump",true)&&
            (target.kind==TargetKind.PROFILE&&path.size==1||target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&path.size==2&&path[1].matches(Regex("[0-9]{1,40}")))
    }
    override fun sentinel(capability: Capability)=routes.target(Platform.TRUTH_SOCIAL,"realDonaldTrump")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This independent archive covers only @realDonaldTrump")
        require(cursor==null){"This archive feed has no verified continuation"}
        val payload=gateway.text(source.root.trimEnd('/')+"/feed?limit=100",target.canonicalUrl,maxBytes=2L*1024*1024)
        val document=Jsoup.parse(payload.text,"",Parser.xmlParser());val rss=document.selectFirst("rss") ?: discoveryFailure("The archive returned a page shell instead of a feed")
        if(rss.attr("xmlns:truth")!="https://truthsocial.com/ns")discoveryFailure("The archive did not provide original-post identities")
        val provenance=provenance(payload).copy(cached=true);val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        fun org(item: org.jsoup.nodes.Element,tag: String)=item.children().firstOrNull {it.tagName()==tag}?.text().orEmpty()
        val posts=rss.select("channel > item").take(100).mapNotNull {item->
            val canonical=discoverySafe(org(item,"truth:originalUrl")) ?: return@mapNotNull null
            val url=canonical.toHttpUrlOrNull()!!;val parts=url.pathSegments.filter(String::isNotBlank);val id=org(item,"truth:originalId")
            if(url.host!="truthsocial.com"||parts.size!=2||!parts[0].equals("@realDonaldTrump",true)||!id.matches(Regex("[0-9]{1,40}"))||parts[1]!=id)return@mapNotNull null
            if(selected&&ContentIdentity.key(Platform.TRUTH_SOCIAL,canonical)!=ContentIdentity.key(target.platform,target.canonicalUrl))return@mapNotNull null
            val archive=org(item,"link").toHttpUrlOrNull()?.takeIf {it.isHttps&&it.host in setOf("trumpstruth.org","www.trumpstruth.org")&&it.encodedPath.matches(Regex("/statuses/[0-9]+"))}?.toString() ?: return@mapNotNull null
            val timestamp=runCatching {ZonedDateTime.parse(org(item,"pubDate"),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()}.getOrNull() ?: return@mapNotNull null
            val markup=org(item,"description");val body=discoveryPlain(markup);val media=item.children().filter {it.tagName()=="media:content"}.take(16).mapNotNull {node->
                discoverySafe(node.attr("url"))?.let {Media(it,if(node.attr("medium")=="video")"video"else "image",node.children().firstOrNull {it.tagName()=="media:thumbnail"}?.attr("url")?.let(::discoverySafe))}}
            if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            // Publication is not capture time. Do not invent an Archived MO/YR
            // badge from it; retain the dated item and explicit archive provenance.
            Post(ContentIdentity.key(Platform.TRUTH_SOCIAL,canonical),Platform.TRUTH_SOCIAL,canonical,org(item,"dc:creator").ifBlank {"realDonaldTrump"},body,publishedAt=timestamp,media=media,provenance=provenance.copy(finalUrl=archive),links=listOf(archive))
        }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        return usable(Page(posts,profile=if(!selected)Profile(target,"Donald J. Trump")else null,provenance=provenance,notice="Archived copies from Trump’s Truth, covering @realDonaldTrump only. Up to 100 posts, including some that may have been removed, with no verified capture timestamps or comment threads. Publication dates do not establish current availability."))
    }
}

private class TiebaClientDiscovery(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DiscoveryAdapter(source,gateway,routes) {
    override val capabilities=setOf(Capability.TIMELINE,Capability.FEED,Capability.POST,Capability.COMMENTS,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==Platform.TIEBA&&target.kind in setOf(TargetKind.COMMUNITY,TargetKind.POST,TargetKind.THREAD)
    override fun sentinel(capability: Capability)=routes.target(Platform.TIEBA,"Android",TargetKind.COMMUNITY)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        check(target,setOf(TargetKind.COMMUNITY,TargetKind.POST,TargetKind.THREAD));val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        // Starting with the selected root prevents a reply-only page from passing as a post.
        if(post)require(cursor==null){"Refresh this public thread to load its available first-page replies"}
        val mark=discoveryCursor(cursor,source,target);val page=mark?.page ?: 1
        val parsed=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid Tieba link")
        val id=if(post)parsed.pathSegments.getOrNull(1)?.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: error("Invalid public thread ID")else null
        val forum=if(!post)parsed.queryParameter("kw")?.takeIf {it.isNotBlank()&&it.length<=100} ?: target.identity.takeIf {it.isNotBlank()&&it.length<=100} ?: error("Enter a public forum name")else null
        val fields=linkedMapOf("_client_id" to "wappc_${System.currentTimeMillis()}_${java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)}","_client_type" to "2","_client_version" to "12.20.1.0","_phone_imei" to "000000000000000","from" to "tieba","rn" to "30","pn" to page.toString())
        if(post){fields["kz"]=id!!;fields["r"]="0";fields["lz"]="0"}else fields["kw"]=forum!!
        // Public client request marker; this is not a user credential. No BDUSS,
        // actual device identifier, login or persistent client token is transmitted.
        val signing=fields.toSortedMap().entries.joinToString(""){it.key+"="+it.value}+"tiebaclient!!!"
        fields["sign"]=MessageDigest.getInstance("MD5").digest(signing.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val payload=gateway.form(source.root.trimEnd('/')+if(post)"/c/f/pb/page"else "/c/f/frs/page",fields,target.canonicalUrl)
        val data=discoveryJson(payload).discoveryObject() ?: discoveryFailure("The public client endpoint returned no records")
        if(data.ds("error_code")!="0")throw SourceFailure(if(data.ds("error_msg").contains("登录"))HealthStatus.LOGIN_REQUIRED else HealthStatus.EMPTY,"The public Tieba client endpoint is unavailable for this page")
        val provenance=provenance(payload);val users=data.da("user_list").mapNotNull {it.discoveryObject()}.associateBy {it.ds("id")}
        fun author(id: String)=users[id]?.let {it.ds("name_show").ifBlank {it.ds("name")}}.orEmpty()
        if(!post) {
            if(!data.dobj("forum").ds("name").equals(forum,true))discoveryFailure("The public client endpoint returned a different forum")
            val records=data.da("thread_list").take(100)
            val posts=records.mapNotNull {it.discoveryObject()}.mapNotNull {node->
                val thread=node.ds("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
                val timestamp=node.ds("create_time").toLongOrNull()?.takeIf {it>0}?.times(1000) ?: return@mapNotNull null
                val content=tiebaContent(node.da("first_post_content").ifEmpty {node.da("abstract")});val title=discoveryPlain(node.ds("title"));if(title.isBlank()&&content.first.isBlank()&&content.second.isEmpty())return@mapNotNull null
                val canonical="https://tieba.baidu.com/p/$thread"
                Post(ContentIdentity.key(Platform.TIEBA,canonical),Platform.TIEBA,canonical,author(node.ds("author_id")),content.first,title,timestamp,media=content.second,replyCount=node.ds("reply_num").toLongOrNull(),provenance=provenance)
            }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
            val hasMore=data.dobj("page").ds("has_more") in setOf("1","true")
            return usable(Page(posts,if(hasMore&&page<1000)discoveryCursor(DiscoveryCursor(source.key,target.canonicalUrl,page+1))else null,provenance=provenance,notice=null))
        }
        val thread=data.dobj("thread");if(thread.ds("id")!=id)discoveryFailure("The public client endpoint returned a different thread")
        val records=data.da("post_list").take(100).mapNotNull {it.discoveryObject()};val rootRecord=records.firstOrNull {it.ds("floor")=="1"} ?: discoveryFailure("The public endpoint did not return this thread's original post")
        val rootUrl="https://tieba.baidu.com/p/$id";val rootKey=ContentIdentity.key(Platform.TIEBA,rootUrl)
        val posts=records.mapNotNull {node->
            val pid=node.ds("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            val timestamp=node.ds("time").toLongOrNull()?.takeIf {it>0}?.times(1000) ?: return@mapNotNull null
            val root=node===rootRecord;val content=tiebaContent(node.da("content"));if(content.first.isBlank()&&content.second.isEmpty())return@mapNotNull null
            val canonical=if(root)rootUrl else "$rootUrl?pid=$pid#$pid"
            Post(ContentIdentity.key(Platform.TIEBA,canonical),Platform.TIEBA,canonical,author(node.ds("author_id")),content.first,if(root)discoveryPlain(thread.ds("title"))else "",timestamp,media=content.second,parentId=if(root)null else rootKey,provenance=provenance)
        }
        if(posts.none {it.id==rootKey})discoveryFailure("The public endpoint did not return a readable original post")
        return Page(posts.sortedWith(compareBy<Post>{it.parentId!=null}.thenByDescending {it.publishedAt}),provenance=provenance,notice="First-page replies only. Earlier and nested replies may need another source.")
    }
    private fun tiebaContent(nodes: List<JsonElement>): Pair<String,List<Media>> {
        val text=StringBuilder();val media=ArrayList<Media>()
        nodes.take(128).mapNotNull {it.discoveryObject()}.forEach {node->
            when(node.ds("type")) {
                "3" -> discoverySafe(node.ds("origin_src").ifBlank {node.ds("src")})?.let {if(media.size<16)media+=Media(it)}
                "2" -> node.ds("c").takeIf {it.length<=100}?.let {text.append(it)}
                else -> text.append(discoveryPlain(node.ds("text")))
            }
        }
        return text.toString().take(100_000) to media
    }
}
