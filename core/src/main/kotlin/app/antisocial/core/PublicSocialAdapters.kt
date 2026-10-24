/*
 * AntiSocial — native public social data readers, GPL-3.0-or-later.
 * Protocol references: docs.joinmastodon.org/client/public/, join-lemmy.org/docs/
 * contributors/04-api.html, misskey-hub.net. Discovery ideas were independently
 * implemented after reviewing RSS-Bridge GettrBridge and RSSHub Gab/Telegram
 * readers; no third-party frontend runtime or account credentials are embedded.
 * Darwin remains the routing donor; protocol instances are not complete mirrors.
 */
package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.Instant
import java.util.Base64

/** A path is eligible only for the capabilities this concrete reader implements. */
fun publicSocialAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter? {
    if(source.frontend !in setOf("original","mastodon-api","mastodon-rss","lemmy-api","gettr-api","gab-api","pixelfed-atom","telegram-public","misskey-api"))return null
    return when(source.platform) {
        Platform.MASTODON -> if(source.frontend=="mastodon-rss")MastodonRssDataAdapter(source,gateway,routes)else MastodonDataAdapter(source,gateway,routes)
        Platform.LEMMY -> LemmyDataAdapter(source,gateway,routes)
        Platform.GETTR -> GettrDataAdapter(source,gateway,routes)
        Platform.GAB -> GabDataAdapter(source,gateway,routes)
        Platform.PIXELFED -> PixelfedDataAdapter(source,gateway,routes)
        Platform.TELEGRAM -> TelegramDataAdapter(source,gateway,routes)
        Platform.MISSKEY -> MisskeyDataAdapter(source,gateway,routes)
        else -> null
    }
}

/** Shared Mastodon-shaped read path; forks still require independent capability checks. */
fun mastodonProtocolAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter=MastodonDataAdapter(source,gateway,routes)

private fun JsonElement.obj(): JsonObject?=takeIf {it.isJsonObject}?.asJsonObject
private fun JsonObject.s(vararg keys: String): String=keys.firstNotNullOfOrNull {get(it)?.takeIf {v->v.isJsonPrimitive}?.asString?.takeIf(String::isNotBlank)}.orEmpty()
private fun JsonObject.o(key: String): JsonObject=get(key)?.obj() ?: JsonObject()
private fun JsonObject.a(key: String): List<JsonElement> =get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
private fun JsonObject.n(vararg keys: String): Long?=s(*keys).toLongOrNull()
private fun JsonObject.flag(key: String)=s(key)=="true"
private fun json(text: String): JsonElement=try {JsonParser.parseString(text)}catch(_: Exception){throw SourceFailure(HealthStatus.EMPTY,"Source returned no recognizable public data")}
private fun safeUrl(value: String,base: String): String? {
    if(value.isBlank())return null
    val url=base.toHttpUrlOrNull()?.resolve(value) ?: return null
    return url.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
}
private fun plain(html: String): String=Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>"),"\n").replace(Regex("(?i)</(?:p|div|h[1-6])>"),"\n")).wholeText().trim()
private fun stamp(value: String): Long?=value.toLongOrNull()?.let {if(it<10_000_000_000L)it*1000 else it} ?: runCatching {Instant.parse(if(value.endsWith("Z")||Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(value))value else value+"Z").toEpochMilli()}.getOrNull()
private fun links(html: String,base: String)=Jsoup.parse(html).select("a[href]").mapNotNull {safeUrl(it.attr("href"),base)}.distinct().take(12)
private fun chronological(posts: List<Post>)=posts.distinctBy {it.id}.sortedByDescending {it.publishedAt ?: 0L}
private val publicCaps=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.MEDIA)

/** Bookmarks carry opaque data, never a caller-controlled network destination. */
private data class SocialBookmark(val source: String,val target: String,val operation: String,val token: String,val extra: String="")
private fun bookmark(source: Source,target: Target,operation: String,token: String,extra: String=""): String? {
    if(token.isBlank()||token.length>8192)return null
    return "social:"+Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(SocialBookmark(source.key,target.canonicalUrl,operation,token,extra)).toByteArray(Charsets.UTF_8))
}
private fun unbookmark(raw: String?,source: Source,target: Target,operation: String): SocialBookmark? {
    if(raw==null)return null
    require(raw.length<=24*1024&&raw.startsWith("social:")){"Refresh this page to continue"}
    val value=runCatching {Gson().fromJson(String(Base64.getUrlDecoder().decode(raw.removePrefix("social:")),Charsets.UTF_8),SocialBookmark::class.java)}.getOrNull()
    require(runCatching {value!=null&&value.source==source.key&&value.target==target.canonicalUrl&&value.operation==operation&&value.token.length in 1..8192&&value.extra.length<=8192}.getOrDefault(false)){"Pagination changed the selected source or page"}
    return value
}

private abstract class PublicAdapter(final override val source: Source,protected val gateway: NetworkGateway,protected val routes: DarwinRoutes): DataAdapter {
    protected fun origin(target: Target): String {
        val root=if(source.role==SourceRole.ORIGINAL&&target.platform in setOf(Platform.MASTODON,Platform.LEMMY,Platform.PIXELFED,Platform.MISSKEY))target.canonicalUrl.toHttpUrlOrNull()?.let {it.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')} ?: source.root else source.root
        return root.trimEnd('/')
    }
    protected fun provenance(payload: Payload)=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
    protected fun validate(target: Target) {
        require(target.platform==source.platform){"Selected source belongs to another platform"}
        if(target.kind in setOf(TargetKind.SEARCH,TargetKind.TAG))throw SourceFailure(HealthStatus.UNSUPPORTED,"This public reader does not provide accountless search")
    }
    protected fun usable(page: Page): Page {
        if(page.posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No readable public posts were returned; this is not a working timeline")
        return page
    }
    override suspend fun feed(target: Target)=fetch(target)
    override fun sentinel(capability: Capability): Target {
        val root=source.root.trimEnd('/');val host=root.toHttpUrlOrNull()?.host.orEmpty()
        return when(source.platform) {
            Platform.MASTODON -> {
                val username=when(host){"mstdn.social"->"stux";"mastodon.social"->"Mastodon";else->throw SourceFailure(HealthStatus.UNSUPPORTED,"No public canary is configured for this instance; ordinary reads establish route health")}
                Target(source.platform,TargetKind.PROFILE,"$username@$host","$root/@$username")
            }
            Platform.LEMMY -> Target(source.platform,TargetKind.COMMUNITY,"technology@$host","$root/c/technology")
            Platform.GETTR -> Target(source.platform,TargetKind.PROFILE,"joerogan","https://gettr.com/user/joerogan")
            Platform.GAB -> Target(source.platform,TargetKind.PROFILE,"AmericanFamAssc","https://gab.com/AmericanFamAssc")
            Platform.PIXELFED -> Target(source.platform,TargetKind.PROFILE,"pixelfed@$host","$root/pixelfed")
            Platform.TELEGRAM -> Target(source.platform,TargetKind.PROFILE,"telegram","https://t.me/telegram")
            Platform.MISSKEY -> Target(source.platform,TargetKind.PROFILE,"support@$host","$root/@support")
            else -> throw SourceFailure(HealthStatus.UNSUPPORTED,"No public canary is configured")
        }
    }
}

/** Pure bounded parsers, shared by diagnostic and deterministic fixture tests. */
object PublicSocialParsing {
    fun mastodonStatus(node: JsonObject,platform: Platform,provenance: Provenance,root: String): Post? {
        if(node.s("visibility") in setOf("private","direct")||node.flag("deleted"))return null
        val account=node.o("account");val id=node.s("id").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,160}"))} ?: return null
        val canonical=safeUrl(node.s("url","uri"),root) ?: safeUrl("/@${account.s("username")}/$id",root) ?: return null
        val media=node.a("media_attachments").take(16).mapNotNull {v->val m=v.obj() ?: return@mapNotNull null;val url=safeUrl(m.s("url","remote_url"),root) ?: return@mapNotNull null
            val isVideo=m.s("type") in setOf("video","gifv")
            val metadata=m.o("meta")
            Media(url,if(isVideo)"video"else "image",safeUrl(m.s("preview_url"),root),m.s("description"),durationMs=if(isVideo)MediaDuration.seconds(metadata.o("original")["duration"]) ?: MediaDuration.seconds(metadata["duration"])else null)}
        val body=listOf(node.s("spoiler_text"),plain(node.s("content"))).filter(String::isNotBlank).joinToString("\n\n")
        if(body.isBlank()&&media.isEmpty())return null
        return Post(ContentIdentity.key(platform,canonical),platform,canonical,account.s("display_name","acct","username"),body,publishedAt=stamp(node.s("created_at")),avatar=safeUrl(account.s("avatar_static","avatar"),root),media=media,replyCount=node.n("replies_count"),score=node.n("favourites_count"),provenance=provenance,links=links(node.s("content"),root))
    }
    fun mastodonProfile(node: JsonObject,target: Target,base: String): Profile? {
        if(node.s("id").isBlank()||node.s("username").isBlank())return null
        val markup=node.s("note")+node.a("fields").joinToString(""){it.obj()?.s("value").orEmpty()}
        return Profile(target,node.s("display_name","username"),plain(node.s("note")),safeUrl(node.s("avatar_static","avatar"),base),node.n("followers_count"),links(markup,base),displayNameSupplied=node.s("display_name").isNotBlank(),verifiedWebsites=PublicSourceRecords.verifiedWebsites(node,base).takeIf {it.isNotEmpty()})
    }
    fun lemmyPosts(value: JsonObject,provenance: Provenance,base: String): List<Post> {
        val nodes=value.a("posts").ifEmpty {listOfNotNull(value.get("post_view"))}
        return nodes.take(100).mapNotNull {raw->val view=raw.obj() ?: return@mapNotNull null;val p=view.o("post");val author=view.o("creator");val counts=view.o("counts");val id=p.n("id") ?: return@mapNotNull null
            if(p.flag("removed")||p.flag("deleted"))return@mapNotNull null
            val canonical="$base/post/$id";val title=p.s("name");val body=p.s("body","embed_description")
            if(title.isBlank()&&body.isBlank())return@mapNotNull null
            val attachment=safeUrl(p.s("url"),base);val image=safeUrl(p.s("thumbnail_url"),base)
            Post(ContentIdentity.key(Platform.LEMMY,canonical),Platform.LEMMY,canonical,author.s("display_name","name"),body,title,stamp(p.s("published","published_at")),safeUrl(author.s("avatar"),base),listOfNotNull(image?.let {Media(it)}),counts.n("comments"),counts.n("score"),provenance=provenance,links=listOfNotNull(attachment))}
    }
    fun lemmyComments(value: JsonObject,target: Target,provenance: Provenance,base: String,postId: Long): List<Post> {
        val nodes=value.a("comments").mapNotNull {it.obj()}.take(100)
        val available=nodes.mapNotNull {it.o("comment").n("id")}.toSet()
        return nodes.mapNotNull {view->val c=view.o("comment");val creator=view.o("creator");val id=c.n("id") ?: return@mapNotNull null
            if(c.n("post_id")!=postId||c.flag("deleted")||c.flag("removed")||c.s("content").isBlank())return@mapNotNull null
            val canonical="$base/comment/$id";val path=c.s("path").split('.').mapNotNull(String::toLongOrNull);val parent=path.dropLast(1).lastOrNull()?.takeIf {it!=0L&&it in available}
            Post(ContentIdentity.key(Platform.LEMMY,canonical),Platform.LEMMY,canonical,creator.s("display_name","name"),c.s("content"),publishedAt=stamp(c.s("published","published_at")),avatar=safeUrl(creator.s("avatar"),base),score=view.o("counts").n("score"),parentId=if(parent!=null)ContentIdentity.key(Platform.LEMMY,"$base/comment/$parent")else ContentIdentity.key(Platform.LEMMY,target.canonicalUrl),provenance=provenance)}
    }
    fun gettr(value: JsonObject,provenance: Provenance): List<Post> {
        if(value.s("rc").isNotBlank()&&value.s("rc")!="OK")return emptyList()
        val result=value.o("result");val auxiliary=result.o("aux");val map=auxiliary.o("post");val users=auxiliary.o("uinf")
        val ids=result.o("data").a("list").mapNotNull {it.obj()?.o("activity")?.s("pstid","tgt_id")?.takeIf(String::isNotBlank)}
        val nodes=if(result.o("data").has("list"))ids.mapNotNull {map.get(it)?.obj()}else if(result.o("data").s("_t")=="post")listOf(result.o("data"))else emptyList()
        return nodes.take(100).mapNotNull {p->val id=p.s("_id").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,160}"))} ?: return@mapNotNull null
            if(p.s("vis").isNotBlank()&&p.s("vis")!="p")return@mapNotNull null
            val uid=p.s("uid");val user=users.o(uid);val base="https://media.gettr.com/";val image=safeUrl(p.s("main"),base)
            val media=p.a("imgs").take(16).mapNotNull {it.takeIf {v->v.isJsonPrimitive}?.asString?.let {url->safeUrl(url,base)}?.let {url->Media(url)}}.toMutableList()
            safeUrl(p.s("ovid","vid"),base)?.let {media+=Media(it,"video",image)} ?: image?.let {media+=Media(it)}
            val body=p.s("txt");if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            val canonical="https://gettr.com/post/$id"
            Post(ContentIdentity.key(Platform.GETTR,canonical),Platform.GETTR,canonical,user.s("nickname","username").ifBlank {uid},body,publishedAt=p.n("cdate"),avatar=safeUrl(user.s("ico"),base),media=media,replyCount=p.n("cm"),score=p.n("lkbpst"),provenance=provenance)}
    }
    fun gab(value: JsonObject,provenance: Provenance): List<Post> {
        val accounts=value.a("a").mapNotNull {it.obj()}.associateBy {it.s("i")};val attachments=value.a("ma").mapNotNull {it.obj()}.associateBy {it.s("i","id")}
        val selectedIds=value.a("t").mapNotNull {it.takeIf {v->v.isJsonPrimitive}?.asString}.toSet()
        return value.a("s").filter {v->!value.has("t")||v.obj()?.s("i") in selectedIds}.take(100).mapNotNull {raw->val p=raw.obj() ?: return@mapNotNull null;val account=accounts[p.s("ai")] ?: JsonObject();val canonical=safeUrl(p.s("ul","u"),"https://gab.com") ?: return@mapNotNull null
            if(p.s("v") in setOf("private","direct"))return@mapNotNull null
            val media=p.a("mai").take(16).mapNotNull {v->val m=attachments[v.takeIf {it.isJsonPrimitive}?.asString] ?: return@mapNotNull null;safeUrl(if(m.s("t","type")=="video")m.s("smp4","u","url")else m.s("u","url","pu"),"https://gab.com")?.let {Media(it,if(m.s("t","type")=="video")"video"else "image",safeUrl(m.s("pu","preview_url"),"https://gab.com"))}}
            val body=plain(p.s("c"));if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            Post(ContentIdentity.key(Platform.GAB,canonical),Platform.GAB,canonical,account.s("dn","un"),body,publishedAt=stamp(p.s("ca")),avatar=safeUrl(account.s("avs","av"),"https://gab.com"),media=media,replyCount=p.n("rc"),score=p.n("fc"),provenance=provenance,links=links(p.s("c"),"https://gab.com"))}
    }
    fun telegram(text: String,target: Target,provenance: Provenance): Page {
        val document=Jsoup.parse(text,provenance.finalUrl);document.outputSettings().prettyPrint(false);val channel=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.firstOrNull {it!="s"}.orEmpty()
        require(channel.matches(Regex("[A-Za-z0-9_]{3,64}"))){"A public channel username is required"}
        val selected=if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.toLongOrNull()else null
        val posts=document.select(".tgme_widget_message[data-post]").take(100).mapNotNull {node->val raw=node.attr("data-post");val parts=raw.split('/');val id=parts.getOrNull(1)?.toLongOrNull()
            if(parts.size!=2||!parts[0].equals(channel,true)||id==null||selected!=null&&selected!=id)return@mapNotNull null
            val canonical="https://t.me/$channel/$id";val markup=node.selectFirst(".tgme_widget_message_text")?.html().orEmpty();val body=plain(markup)
            fun styleUrl(style: String)=Regex("url\\(['\"]?([^)'\"]+)").find(style)?.groupValues?.get(1)?.let {safeUrl(it,provenance.finalUrl)}
            val media=node.select("a.tgme_widget_message_photo_wrap[style]").mapNotNull {styleUrl(it.attr("style"))?.let {url->Media(url)}}.toMutableList()
            media+=VideoExtraction.html(node,provenance.finalUrl).map {video->video.copy(preview=video.preview ?: node.selectFirst(".tgme_widget_message_video_thumb[style]")?.let {styleUrl(it.attr("style"))})}
            if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            val forwarded=node.select(".tgme_widget_message_forwarded_from a[href]").take(2).mapNotNull {a->safeUrl(a.absUrl("href"),provenance.finalUrl)?.let {PublicRelation(PublicRelationKind.FORWARD,it,a.text().take(160))}}
            Post(ContentIdentity.key(Platform.TELEGRAM,canonical),Platform.TELEGRAM,canonical,node.selectFirst(".tgme_widget_message_owner_name")?.text().orEmpty().ifBlank {channel},body,publishedAt=stamp(node.selectFirst("time[datetime]")?.attr("datetime").orEmpty()),avatar=node.selectFirst(".tgme_widget_message_user_photo img[src]")?.attr("src")?.let {safeUrl(it,provenance.finalUrl)},media=media,provenance=provenance,links=links(markup,provenance.finalUrl),publicRelations=forwarded.takeIf {it.isNotEmpty()})}
        val title=document.selectFirst(".tgme_channel_info_header_title")?.text().orEmpty().ifBlank {channel};val description=document.selectFirst(".tgme_channel_info_description")?.text().orEmpty()
        val next=if(selected==null)document.select("a.tme_messages_more[data-before]").firstNotNullOfOrNull {a->val url=provenance.finalUrl.toHttpUrlOrNull()?.resolve(a.attr("href"));url?.takeIf {it.pathSegments==listOf("s",channel)&&it.queryParameter("before")?.toLongOrNull()!=null}?.queryParameter("before")}else null
        return Page(chronological(posts),next,if(selected==null)Profile(target,title,description,document.selectFirst(".tgme_page_photo_image")?.attr("src")?.let {safeUrl(it,provenance.finalUrl)},displayNameSupplied=document.selectFirst(".tgme_channel_info_header_title")?.text()?.isNotBlank()==true)else null,provenance,notice="Public channel posts; private chats and discussion comments are unavailable.")
    }
    fun misskey(value: JsonElement,provenance: Provenance,base: String): List<Post> {
        val nodes=if(value.isJsonArray)value.asJsonArray.toList()else listOf(value)
        return nodes.take(100).mapNotNull {raw->val node=raw.obj() ?: return@mapNotNull null
            val n=if(node.s("text").isBlank()&&node.a("files").isEmpty()&&node.get("renote")?.isJsonObject==true)node.o("renote")else node
            if(n.s("visibility") in setOf("specified","followers"))return@mapNotNull null
            val id=n.s("id").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,160}"))} ?: return@mapNotNull null;val user=n.o("user")
            val canonical="$base/notes/$id" // Misskey IDs are local to this read instance, even for remote notes.
            val media=n.a("files").take(16).mapNotNull {v->val f=v.obj() ?: return@mapNotNull null;safeUrl(f.s("url"),base)?.let {Media(it,if(f.s("type").startsWith("video/"))"video"else "image",safeUrl(f.s("thumbnailUrl"),base),f.s("comment"))}}
            val body=listOf(n.s("cw"),n.s("text")).filter(String::isNotBlank).joinToString("\n\n");if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            Post(ContentIdentity.key(Platform.MISSKEY,canonical),Platform.MISSKEY,canonical,user.s("name","username"),body,publishedAt=stamp(n.s("createdAt")),avatar=safeUrl(user.s("avatarUrl"),base),media=media,replyCount=n.n("repliesCount"),score=n.n("reactionCount"),provenance=provenance)}
    }
}

private class MastodonDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps+Capability.COMMENTS
    private suspend fun account(target: Target): Pair<JsonObject,Payload> {
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid public profile")
        val username=url.pathSegments.firstOrNull {it.isNotBlank()&&it!="users"}?.removePrefix("@") ?: error("A profile username is required")
        require(username.matches(Regex("[A-Za-z0-9_.-]{1,100}(?:@[A-Za-z0-9.-]+)?"))){"Invalid profile username"}
        val payload=gateway.text("${origin(target)}/api/v1/accounts/lookup?acct=${DarwinRoutes.encode(username)}",target.canonicalUrl,maxBytes=256*1024)
        val account=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"The source returned no public account")
        val expectedOwner=username.substringAfter('@',url.host)
        val actualAcct=account.s("acct");val actualOwner=actualAcct.substringAfter('@',url.host)
        val accountHost=safeUrl(account.s("url"),origin(target))?.toHttpUrlOrNull()?.host
        if(!account.s("username").equals(username.substringBefore('@'),true)||account.s("id").isBlank()||!actualOwner.equals(expectedOwner,true)||accountHost!=null&&!accountHost.equals(expectedOwner,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different account")
        return account to payload
    }
    override suspend fun profile(target: Target): Page {validate(target);val (account,payload)=account(target);return Page(emptyList(),profile=PublicSocialParsing.mastodonProfile(account,target,origin(target)),provenance=provenance(payload))}
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val root=origin(target)
        if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD)) {
            require(cursor==null){"This public context has no continuation"}
            val id=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty();require(id.matches(Regex("[A-Za-z0-9_-]{1,160}"))){"Invalid public status ID"}
            val payload=gateway.text("$root/api/v1/statuses/$id",target.canonicalUrl);val node=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No public status returned")
            if(node.s("id")!=id)throw SourceFailure(HealthStatus.EMPTY,"The source returned a different post")
            val provenance=provenance(payload);val parent=PublicSocialParsing.mastodonStatus(node,target.platform,provenance,root) ?: throw SourceFailure(HealthStatus.EMPTY,"No readable public post returned")
            val selected=parent.copy(canonicalUrl=target.canonicalUrl.substringBefore('?'),id=ContentIdentity.key(target.platform,target.canonicalUrl))
            val context=try {json(gateway.text("$root/api/v1/statuses/$id/context",target.canonicalUrl,maxBytes=2L*1024*1024).text).obj()}catch(e: CancellationException){throw e}catch(_: Exception){null}
            val children=context?.a("descendants").orEmpty().mapNotNull {it.obj()}.take(100)
            val identity=children.associate {it.s("id") to PublicSocialParsing.mastodonStatus(it,target.platform,provenance,root)?.id}+ (id to selected.id)
            val comments=children.mapNotNull {child->PublicSocialParsing.mastodonStatus(child,target.platform,provenance,root)?.copy(parentId=identity[child.s("in_reply_to_id")])}.filter {it.parentId!=null}
            return Page(listOf(selected)+chronological(comments),provenance=provenance,notice=if(context==null)"The post loaded, but its public replies could not be retrieved."else "Public context order is chronological; this source has no platform popularity ranking.")
        }
        val bookmark=unbookmark(cursor,source,target,"statuses");val (account,_)=if(bookmark==null)account(target)else JsonObject().apply {addProperty("id",bookmark.extra)} to Payload("",root,"",200)
        val accountId=account.s("id");require(accountId.matches(Regex("[A-Za-z0-9_-]{1,160}"))){"Invalid public account ID"}
        val url="$root/api/v1/accounts/$accountId/statuses?limit=20&exclude_replies=true&exclude_reblogs=true"+(bookmark?.let {"&max_id=${DarwinRoutes.encode(it.token)}"}.orEmpty())
        val payload=gateway.text(url,target.canonicalUrl);val values=json(payload.text).takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
        val posts=values.take(100).mapNotNull {v->v.obj()?.takeIf {it.o("account").s("id")==accountId&&it.get("reblog")?.isJsonObject!=true}?.let {PublicSocialParsing.mastodonStatus(it,target.platform,provenance(payload),root)}}
        val token=values.lastOrNull()?.obj()?.s("id").orEmpty().takeIf {values.size>=20&&it!=bookmark?.token}
        return usable(Page(chronological(posts),token?.let {app.antisocial.core.bookmark(source,target,"statuses",it,accountId)},if(bookmark==null)PublicSocialParsing.mastodonProfile(account,target,root)else null,provenance(payload)))
    }
}

/** Format alternative on the same original server; not a federation mirror. */
private class MastodonRssDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps-Capability.POST
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);require(cursor==null){"This public RSS feed has no verified continuation"}
        if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"This RSS route supplies profile posts, not arbitrary posts or comments")
        val canonical=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid public profile")
        val username=canonical.pathSegments.lastOrNull().orEmpty().removePrefix("@");require(username.matches(Regex("[A-Za-z0-9_.-]{1,100}"))){"A local public profile is required"}
        return gateway.feed("${origin(target)}/@$username.rss",target,source,maxBytes=1024L*1024){payload->
        if(!Regex("<rss(?:\\s|>)").containsMatchIn(payload.text))throw SourceFailure(HealthStatus.EMPTY,"The public RSS endpoint returned a shell instead of a feed")
        val provenance=provenance(payload);val page=Extraction.rss(payload.text,target,provenance,routes)
        val channel=Jsoup.parse(payload.text,"",Parser.xmlParser()).selectFirst("rss > channel") ?: throw SourceFailure(HealthStatus.EMPTY,"No public RSS channel returned")
        val profileLink=channel.children().firstOrNull {it.normalName()=="link"}?.text()?.toHttpUrlOrNull()
        if(profileLink==null||profileLink.host!=canonical.host||profileLink.port!=canonical.port||profileLink.encodedPath.trimEnd('/')!="/@$username")throw SourceFailure(HealthStatus.EMPTY,"The source returned a different profile feed")
        val avatar=channel.selectFirst("image > url")?.text()?.let {safeUrl(it,target.canonicalUrl)}
        val posts=page.posts.filter {p->val url=p.canonicalUrl.toHttpUrlOrNull();url!=null&&url.host==canonical.host&&url.port==canonical.port&&url.pathSegments.size==2&&url.pathSegments[0]=="@$username"&&url.pathSegments[1].matches(Regex("[A-Za-z0-9_-]{1,160}"))&&(p.body.isNotBlank()||p.media.isNotEmpty())}.map {it.copy(id=ContentIdentity.key(it.platform,it.canonicalUrl),author=channel.children().firstOrNull {e->e.normalName()=="title"}?.text().orEmpty().ifBlank {username},avatar=avatar,provenance=provenance)}
        val profile=Profile(target,channel.children().firstOrNull {it.normalName()=="title"}?.text().orEmpty().ifBlank {username},channel.children().firstOrNull {it.normalName()=="description"}?.text().orEmpty(),avatar)
        usable(Page(chronological(posts),profile=profile,provenance=provenance,limitedTimeline=true,notice="Public RSS backup from the selected server. Comments and older-page retrieval are unavailable on this format."))
        }
    }
}

private class LemmyDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps+Capability.COMMENTS
    override suspend fun profile(target: Target): Page {
        validate(target)
        require(target.kind in setOf(TargetKind.COMMUNITY,TargetKind.PROFILE)){"A selected public community or person is required"}
        val root=origin(target);val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        val community=target.kind==TargetKind.COMMUNITY
        require(path.size==2&&path[0]==(if(community)"c"else "u")) {"A selected public community or person is required"}
        val name=path[1]
        require(name.matches(Regex("[A-Za-z0-9_.@-]{1,160}"))){"A public community or profile is required"}
        val endpoint=if(community)"community?name="else "person?username="
        val payload=gateway.text("$root/api/v3/$endpoint${DarwinRoutes.encode(name)}",target.canonicalUrl)
        val data=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"This route returned no recognizable public entity metadata")
        val profile=Extraction.lemmyProfile(data,target) ?: throw SourceFailure(HealthStatus.EMPTY,"This route did not identify the selected public community or person")
        return Page(emptyList(),profile=profile,provenance=provenance(payload))
    }
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val root=origin(target);val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        if(target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.firstOrNull()=="comment")throw SourceFailure(HealthStatus.UNSUPPORTED,"Direct comment links need post resolution; open the parent public post on this reader")
        val bookmark=unbookmark(cursor,source,target,if(post)"comments"else "posts");val page=bookmark?.token?.toIntOrNull() ?: 1;require(page in 1..10000){"Invalid page"}
        if(post) {
            val id=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.toLongOrNull() ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"This link is not a public Lemmy post")
            val postPayload=if(cursor==null)gateway.text("$root/api/v3/post?id=$id",target.canonicalUrl)else null
            val posts=postPayload?.let {PublicSocialParsing.lemmyPosts(json(it.text).obj() ?: JsonObject(),provenance(it),root)}.orEmpty()
            val popular=target.canonicalUrl.toHttpUrlOrNull()?.queryParameter("sort") in setOf("top","Top","slop")
            val payload=try {gateway.text("$root/api/v3/comment/list?post_id=$id&sort=${if(popular)"Top"else "New"}&limit=50&page=$page",target.canonicalUrl)}catch(e: CancellationException){throw e}catch(e: Exception){if(posts.isEmpty())throw e;return Page(posts,provenance=postPayload?.let(::provenance),notice="The post loaded, but public comments are unavailable on this route.")}
            val data=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No recognizable public discussion returned");val comments=PublicSocialParsing.lemmyComments(data,target,provenance(payload),root,id)
            return Page(posts+comments,if(data.a("comments").size>=50)app.antisocial.core.bookmark(source,target,"comments",(page+1).toString())else null,provenance=provenance(payload))
        }
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val name=path.lastOrNull().orEmpty()
        require(name.matches(Regex("[A-Za-z0-9_.@-]{1,160}"))){"A public community or profile is required"}
        val isUser=target.kind==TargetKind.PROFILE||path.firstOrNull()=="u"
        val endpoint=if(isUser)"person?username=${DarwinRoutes.encode(name)}&sort=New&limit=20&page=$page"else "post/list?community_name=${DarwinRoutes.encode(name)}&sort=New&limit=20&page=$page"
        val payload=gateway.text("$root/api/v3/$endpoint",target.canonicalUrl);val data=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No recognizable public posts returned")
        val profile=if(isUser)data.o("person_view").o("person").let {person->if(person.s("name").isBlank())null else Profile(target,person.s("display_name","name"),person.s("bio"),safeUrl(person.s("avatar"),root),displayNameSupplied=person.s("display_name").isNotBlank())}else null
        val posts=PublicSocialParsing.lemmyPosts(data,provenance(payload),root)
        return usable(Page(chronological(posts),if(data.a("posts").size>=20)app.antisocial.core.bookmark(source,target,"posts",(page+1).toString())else null,profile,provenance(payload),notice="Public Lemmy v3 API; servers requiring authentication or only v4 need another route."))
    }
}

private class GettrDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val mark=unbookmark(cursor,source,target,"posts")
        val api=source.root.trimEnd('/');val url=if(post) {require(cursor==null);val id=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty();require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")));"$api/u/post/$id?incl=poststats%7Cuserinfo"}else {
            val user=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty();require(user.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            "$api/u/user/$user/posts?offset=0&max=20&dir=fwd&incl=posts&fp=f_uo"+(mark?.let {"&cursor=${DarwinRoutes.encode(it.token)}"}.orEmpty())}
        val payload=gateway.text(url,target.canonicalUrl);val value=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No public posts returned");val posts=PublicSocialParsing.gettr(value,provenance(payload))
        val selected=if(post)posts.filter {ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}else posts
        val username=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        if(!post) {
            val data=value.o("result").o("data");val recipients=data.a("list").mapNotNull {it.obj()?.s("receiver_id")?.takeIf(String::isNotBlank)}
            if(recipients.any {!it.equals(username,true)}||data.s("_id").startsWith("ufd_")&&!data.s("_id").removePrefix("ufd_").equals(username,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different account feed")
        }
        val token=value.o("result").o("aux").s("cursor").takeIf {it.isNotBlank()&&it!=mark?.token&&!post}
        return usable(Page(chronological(selected),token?.let {bookmark(source,target,"posts",it)},if(!post&&selected.isNotEmpty())Profile(target,username)else null,provenance=provenance(payload),notice="Public posts; accountless comments and platform search are unavailable on this reader."))
    }
}

private class GabDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val root=source.root.trimEnd('/');val isPost=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val mark=unbookmark(cursor,source,target,"statuses")
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val user=path.firstOrNull().orEmpty();require(user.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        if(isPost) {
            require(cursor==null){"This public post has no feed continuation"};val id=path.lastOrNull().orEmpty();require(id.matches(Regex("[0-9]{1,40}"))){"Invalid public status ID"}
            val payload=gateway.text("$root/api/v1/statuses/$id",target.canonicalUrl);val data=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No public post returned")
            if(data.s("id")!=id||!data.o("account").s("username").equals(user,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different public post")
            val post=PublicSocialParsing.mastodonStatus(data,Platform.GAB,provenance(payload),root) ?: throw SourceFailure(HealthStatus.EMPTY,"No readable public post returned")
            return Page(listOf(post),provenance=provenance(payload),notice="Public post; accountless comments are unavailable on this reader.")
        }
        val accountPayload=if(mark==null)gateway.text("$root/api/v1/account_by_username/${DarwinRoutes.encode(user)}",target.canonicalUrl,maxBytes=256*1024)else null
        val account=accountPayload?.let {json(it.text).obj()} ?: JsonObject().apply {addProperty("id",mark!!.extra)}
        if(mark==null&&!account.s("username").equals(user,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different account")
        val id=account.s("id");require(id.matches(Regex("[0-9]{1,40}"))){"Invalid public account ID"}
        // Compact feed is a distinct data path from the Mastodon-shaped account lookup.
        val payload=gateway.text("$root/api/v2/accounts/$id/statuses?sort_by=no-reposts"+(mark?.let {"&max_id=${DarwinRoutes.encode(it.token)}"}.orEmpty()),target.canonicalUrl)
        val data=json(payload.text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No recognizable public posts returned");val all=PublicSocialParsing.gab(data,provenance(payload))
        val posts=all.filter {it.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.firstOrNull().equals(user,true)}
        val token=data.a("t").lastOrNull()?.takeIf {it.isJsonPrimitive}?.asString?.takeIf {data.a("t").size>=20&&it!=mark?.token&&!isPost}
        return usable(Page(chronological(posts),token?.let {bookmark(source,target,"statuses",it,id)},if(mark==null&&!isPost)PublicSocialParsing.mastodonProfile(account,target,root)else null,provenance(payload),notice="Public posts; comments and platform search are unavailable on this reader."))
    }
}

private class PixelfedDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps-Capability.POST
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"The public Atom route supplies profile feeds, not arbitrary posts or comments")
        val root=origin(target);val username=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().removePrefix("@");require(username.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val mark=unbookmark(cursor,source,target,"atom");val url=if(mark==null)"$root/users/$username.atom"else {
            val next=mark.token.toHttpUrlOrNull();val selected=root.toHttpUrlOrNull();require(next!=null&&selected!=null&&next.host==selected.host&&next.port==selected.port&&next.scheme==selected.scheme&&next.encodedPath=="/users/$username.atom"){"Feed pagination left its selected source"};next.toString()}
        return gateway.feed(url,target,source,maxBytes=2L*1024*1024){payload->
        if(!Regex("<feed(?:\\s|>)").containsMatchIn(payload.text))throw SourceFailure(HealthStatus.EMPTY,"The public Atom endpoint returned a page shell instead of a feed")
        val provenance=provenance(payload);val page=Extraction.rss(payload.text,target,provenance,routes);val document=Jsoup.parse(payload.text,"",Parser.xmlParser());val feed=document.selectFirst("feed")
        val author=feed?.children()?.firstOrNull {it.normalName()=="author"}?.selectFirst("name")?.text().orEmpty()
        if(author.isNotBlank()&&!author.equals(username,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different profile feed")
        val avatar=feed?.children()?.firstOrNull {it.normalName()=="icon"}?.text()?.let {safeUrl(it,root)}
        val profile=Profile(target,author.ifBlank {username},feed?.children()?.firstOrNull {it.normalName()=="subtitle"}?.text().orEmpty(),avatar)
        val posts=page.posts.filter {it.canonicalUrl.toHttpUrlOrNull()?.host==target.canonicalUrl.toHttpUrlOrNull()?.host}.map {it.copy(id=ContentIdentity.key(it.platform,it.canonicalUrl),avatar=avatar,provenance=provenance)}
        usable(Page(chronological(posts),page.next?.let {bookmark(source,target,"atom",it)},profile,provenance,limitedTimeline=true,notice="Public Atom feed; availability depends on server and account settings. Comments are unavailable."))
        }
    }
}

private class TelegramDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty().filter {it!="s"};val channel=path.firstOrNull().orEmpty();require(channel.matches(Regex("[A-Za-z0-9_]{3,64}")))
        val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val mark=unbookmark(cursor,source,target,"channel")
        val url=if(post) {require(cursor==null);val id=path.getOrNull(1)?.toLongOrNull() ?: error("Invalid public message ID");"${source.root}/$channel/$id?embed=1&mode=tme"}else "${source.root}/s/$channel"+(mark?.let {require(it.token.toLongOrNull()!=null);"?before=${it.token}"}.orEmpty())
        val payload=gateway.text(url,target.canonicalUrl,maxBytes=2L*1024*1024);val parsed=PublicSocialParsing.telegram(payload.text,target,provenance(payload))
        return usable(parsed.copy(next=parsed.next?.takeIf {it!=mark?.token}?.let {bookmark(source,target,"channel",it)}))
    }
}

private class MisskeyDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): PublicAdapter(source,gateway,routes) {
    override val capabilities=publicCaps
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val root=origin(target);val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val mark=unbookmark(cursor,source,target,"notes");var profile: Profile?=null
        val request=JsonObject();val operation: String
        if(post) {require(cursor==null);operation="notes/show";val id=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty();require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")));request.addProperty("noteId",id)}else {
            val username=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().removePrefix("@");require(username.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
            val account=if(mark==null) {val fields=JsonObject().apply {addProperty("username",username)};json(gateway.json("$root/api/users/show",fields,target.canonicalUrl).text).obj() ?: throw SourceFailure(HealthStatus.EMPTY,"No public profile returned")}else JsonObject().apply {addProperty("id",mark.extra)}
            if(mark==null&&!account.s("username").equals(username,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different account")
            val id=account.s("id");require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")))
            if(mark==null)profile=Profile(target,account.s("name","username"),account.s("description"),safeUrl(account.s("avatarUrl"),root),account.n("followersCount"),account.a("fields").mapNotNull {it.obj()?.s("value")?.let {v->safeUrl(v,root)}},displayNameSupplied=account.s("name").isNotBlank())
            operation="users/notes";request.addProperty("userId",id);request.addProperty("limit",20);request.addProperty("includeReplies",false);request.addProperty("includeMyRenotes",false);mark?.let {request.addProperty("untilId",it.token)}
        }
        val payload=gateway.json("$root/api/$operation",request,target.canonicalUrl);val value=json(payload.text);val posts=PublicSocialParsing.misskey(value,provenance(payload),root)
        val token=if(!post&&value.isJsonArray&&value.asJsonArray.size()>=20)value.asJsonArray.lastOrNull()?.obj()?.s("id")?.takeIf {it!=mark?.token}else null
        val userId=request.s("userId")
        return usable(Page(chronological(posts),token?.let {bookmark(source,target,"notes",it,userId)},profile,provenance(payload),notice="Public notes; replies, popularity ordering and fork compatibility remain source dependent and are not enabled by this reader."))
    }
}
