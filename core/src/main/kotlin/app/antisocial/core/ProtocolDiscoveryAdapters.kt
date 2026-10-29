/* AntiSocial — public protocol routes discovered independently from upstream documentation.
 * GPL-3.0-or-later. Darwin remains the routing donor.
 * References: docs.fxembed.com/api/, www.tumblr.com/docs/api/v1,
 * join-lemmy.org/docs/contributors/04-api.html and misskey-dev/misskey public source.
 * No upstream runtime, credentials or executable web content are embedded.
 */
package app.antisocial.core

import com.google.gson.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.time.Instant
import java.util.Base64

/** Registrations describe concrete formats, rather than assuming all frontends speak HTML. */
fun protocolDiscoveryAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter?=when {
    source.frontend=="fxembed-api"&&source.platform in setOf(Platform.X,Platform.BLUESKY)->FxEmbedDataAdapter(source,gateway,routes)
    source.frontend=="vxembed-api"&&source.platform==Platform.X->VxEmbedDataAdapter(source,gateway,routes)
    source.frontend=="tumblr-legacy"&&source.platform==Platform.TUMBLR->TumblrLegacyDataAdapter(source,gateway,routes)
    source.frontend=="lemmy-rss"&&source.platform==Platform.LEMMY->ProtocolFeedAdapter(source,gateway,routes)
    source.frontend in setOf("misskey-rss","misskey-atom")&&source.platform==Platform.MISSKEY->ProtocolFeedAdapter(source,gateway,routes)
    source.frontend=="fxembed-rss"&&source.platform in setOf(Platform.X,Platform.BLUESKY)->ProtocolFeedAdapter(source,gateway,routes)
    source.frontend=="misskey-discussion"&&source.platform==Platform.MISSKEY->MisskeyDiscussionAdapter(source,gateway,routes)
    source.frontend in setOf("arctic-shift","arctic-shift-comments")&&source.platform==Platform.REDDIT&&source.role==SourceRole.CACHE->ArcticShiftDataAdapter(source,gateway,routes)
    source.frontend=="sotwe-api"&&source.platform==Platform.X->SotweDataAdapter(source,gateway,routes)
    else->null
}

private fun JsonElement.pObj(): JsonObject?=takeIf {it.isJsonObject}?.asJsonObject
private fun JsonObject.pText(vararg names: String)=names.firstNotNullOfOrNull {get(it)?.takeIf {v->v.isJsonPrimitive}?.let {v->runCatching {v.asString}.getOrNull()}?.takeIf(String::isNotBlank)}.orEmpty()
private fun JsonObject.pObject(name: String)=get(name)?.pObj() ?: JsonObject()
private fun JsonObject.pArray(name: String)=get(name)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
private fun JsonObject.pLong(vararg names: String)=pText(*names).toLongOrNull()
private fun pJson(text: String): JsonObject=try {JsonParser.parseString(text).pObj() ?: throw IllegalArgumentException()}catch(_: Exception){throw SourceFailure(HealthStatus.EMPTY,"The route returned no recognizable public data")}
private fun pUrl(text: String,base: String): String?=text.takeIf {it.isNotBlank()&&it.length<=8192}?.let {base.toHttpUrlOrNull()?.resolve(it)}?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
private fun pPlain(html: String)=Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>"),"\n").replace(Regex("(?i)</(?:p|div|blockquote|h[1-6])>"),"\n")).apply {select("script,style,iframe,form").remove()}.wholeText().trim()
private fun pStamp(text: String): Long?=text.toDoubleOrNull()?.takeIf {it.isFinite()&&it>0&&it<=253_402_300_799_999.0}?.let {if(it<10_000_000_000.0)(it*1000.0).toLong()else it.toLong()} ?: runCatching {Instant.parse(text).toEpochMilli()}.getOrNull()
private fun pChronological(posts: List<Post>)=posts.distinctBy {it.id}.sortedByDescending {it.publishedAt ?: 0}
private fun pUsable(page: Page): Page {if(page.posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"No readable public posts were returned; a reachable shell is not a working route");return page}
private data class ProtocolCursor(val source: String,val target: String,val token: String)
private fun pCursor(source: Source,target: Target,token: String?): String?=token?.takeIf {it.isNotBlank()&&it.length<=8192}?.let {"protocol:"+Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(ProtocolCursor(source.key,target.canonicalUrl,it)).toByteArray(Charsets.UTF_8))}
private fun pToken(source: Source,target: Target,cursor: String?): String? {
    if(cursor==null)return null
    require(cursor.length<=24*1024&&cursor.startsWith("protocol:")){"Refresh this page to continue"}
    val data=runCatching {Gson().fromJson(String(Base64.getUrlDecoder().decode(cursor.removePrefix("protocol:")),Charsets.UTF_8),ProtocolCursor::class.java)}.getOrNull()
    require(runCatching {data!=null&&data.source==source.key&&data.target==target.canonicalUrl&&data.token.length in 1..8192}.getOrDefault(false)){"Pagination changed the selected source or target"}
    return data!!.token
}
private val pReadCaps=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.MEDIA)
/** Gateway forbids insecure/private production destinations; loopback HTTP exists only in its opt-in fixtures. */
internal fun protocolDiscoveryProvenance(source: Source,payload: Payload,expectedRoot: String=source.root): Provenance {
    val destination=payload.finalUrl.toHttpUrlOrNull() ?: throw SourceFailure(HealthStatus.UNAVAILABLE,"The source returned an invalid final destination")
    val fixture=destination.scheme=="http"&&destination.host in setOf("localhost","127.0.0.1","::1")
    if(!fixture&&!DarwinRoutes.matches(URI(payload.finalUrl),expectedRoot))throw SourceFailure(HealthStatus.UNAVAILABLE,"The request left its selected source; the redirect is not a working alternate route")
    return Provenance(source,System.currentTimeMillis(),payload.finalUrl)
}
private abstract class ProtocolAdapter(final override val source: Source,protected val gateway: NetworkGateway,protected val routes: DarwinRoutes): DataAdapter {
    protected fun validate(target: Target) {require(target.platform==source.platform){"The route belongs to a different platform"}}
    protected fun provenance(payload: Payload,expectedRoot: String=source.root)=protocolDiscoveryProvenance(source,payload,expectedRoot)
    protected fun selectedRoot(target: Target): String {
        val selected=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid public target")
        if(source.role!=SourceRole.ORIGINAL) {
            val configured=source.root.toHttpUrlOrNull() ?: error("Invalid source")
            if(selected.host!=configured.host||selected.port!=configured.port)throw SourceFailure(HealthStatus.UNSUPPORTED,"Federated instance IDs cannot be used as interchangeable mirrors")
        }
        return selected.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    }
    override suspend fun feed(target: Target)=fetch(target)
    override fun sentinel(capability: Capability): Target=when(source.platform) {
        Platform.X->if(capability==Capability.POST)routes.target(Platform.X,"https://x.com/jack/status/20")else if(capability==Capability.SEARCH)routes.search(Platform.X,"space")else routes.target(Platform.X,"NASA")
        Platform.BLUESKY->if(capability==Capability.SEARCH)routes.search(Platform.BLUESKY,"space")else if(capability==Capability.POST)throw SourceFailure(HealthStatus.UNSUPPORTED,"An ordinary selected post establishes route health")else routes.target(Platform.BLUESKY,"bsky.app")
        Platform.TUMBLR->routes.target(Platform.TUMBLR,"staff")
        Platform.LEMMY->Target(Platform.LEMMY,TargetKind.COMMUNITY,"technology",source.root.trimEnd('/')+"/c/technology")
        Platform.MISSKEY->Target(Platform.MISSKEY,TargetKind.PROFILE,"support",source.root.trimEnd('/')+"/@support")
        else->throw SourceFailure(HealthStatus.UNSUPPORTED,"No public canary is configured")
    }
}

/** Deliberately shallow parsers: quotes, recommendations and tombstones are not extra posts. */
object ProtocolDiscoveryParsing {
    /** The API omits repeated per-post authors only when its top-level key/info establish ownership. */
    fun sotwe(data: JsonObject,target: Target,provenance: Provenance): Page {
        val username=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.singleOrNull().orEmpty();require(username.matches(Regex("[A-Za-z0-9_]{1,15}")))
        val info=data.pObject("info")
        if(!data.pText("key").equals(username,true)||!info.pText("screenName").equals(username,true)||info.pText("id").isBlank())throw SourceFailure(HealthStatus.EMPTY,"The source returned a different public account")
        if(info.pText("userProtected")=="true")throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This account is not public")
        val avatar=pUrl(info.pText("profileImageOriginal","profileImageMedium","profileImageThumbnail"),provenance.finalUrl)
        val posts=data.pArray("data").take(100).mapNotNull {it.pObj()}.mapNotNull {node->
            val id=node.pText("id").takeIf {it.matches(Regex("[0-9]{1,30}"))} ?: return@mapNotNull null
            val user=node.pObject("user");if(node.get("retweetedStatus")?.isJsonObject==true||node.pText("truncated")=="true"||user.pText("screenName").let {it.isNotBlank()&&!it.equals(username,true)})return@mapNotNull null
            val date=pStamp(node.pText("createdAt")) ?: return@mapNotNull null
            val body=node.pText("text");val media=node.pArray("mediaEntities").take(20).mapNotNull {raw->raw.pObj()?.let {m->
                when(m.pText("type")) {
                    "photo"->pUrl(m.pText("mediaURL"),provenance.finalUrl)?.let {Media(it,description=m.pText("extAltText","altText"))}
                    "video","animated_gif"->{
                        val info=m.pObject("videoInfo")
                        val variants=info.pArray("variants").take(32).mapNotNull {it.pObj()}.filter {it.pText("type")=="video/mp4"}
                            .mapNotNull {v->pUrl(v.pText("url"),provenance.finalUrl)?.let {it to (v.pLong("bitrate") ?: 0L)}}
                        variants.maxByOrNull {it.second}?.first?.let {url->
                            // This public feed supplies milliseconds and several
                            // progressive renditions. Retain them for the existing
                            // bounded sampler without another post/profile lookup.
                            Media(url,"video",pUrl(m.pText("mediaURL"),provenance.finalUrl),m.pText("extAltText","altText"),
                                durationMs=MediaDuration.milliseconds(info["duration"]),
                                samplingUrl=VideoAssetIdentity.smallRendition(url,variants)?.takeUnless {it==url})
                        }
                    }
                    else->null
                }
            }}
            if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            val links=node.pArray("urlEntities").take(24).mapNotNull {it.pObj()?.let {e->pUrl(e.pText("expandedURL"),provenance.finalUrl)}}.distinct().take(12)
            val canonical="https://x.com/$username/status/$id"
            Post(ContentIdentity.key(Platform.X,canonical),Platform.X,canonical,info.pText("name","screenName"),body,publishedAt=date,avatar=avatar,media=media,replyCount=node.pLong("replyCount"),score=node.pLong("favoriteCount"),provenance=provenance,links=links)
        }
        return pUsable(Page(pChronological(posts),profile=Profile(target,info.pText("name","screenName"),info.pText("description"),avatar,info.pLong("followerCount"),listOfNotNull(pUrl(info.pObject("urlEntity").pText("expandedURL"),provenance.finalUrl)),displayNameSupplied=info.pText("name").isNotBlank()),provenance=provenance,limitedTimeline=true,notice="Earlier posts and comments may need another source."))
    }
    fun redditArchivePost(node: JsonObject,provenance: Provenance,routes: DarwinRoutes): Post? {
        val id=node.pText("id").takeIf {it.matches(Regex("[a-z0-9]{1,20}"))} ?: return null
        val raw=pUrl(node.pText("permalink"),Platform.REDDIT.origin) ?: return null
        val selected=routes.recognize(raw)?.takeIf {it.platform==Platform.REDDIT&&it.kind==TargetKind.POST&&ContentIdentity.key(it.platform,it.canonicalUrl)=="post:$id"} ?: return null
        val title=node.pText("title");val body=node.pText("selftext").takeUnless {it in setOf("[removed]","[deleted]")}.orEmpty()
        val image=pUrl(node.pText("url_overridden_by_dest","url"),Platform.REDDIT.origin)?.takeIf {it.substringBefore('?').matches(Regex("https://.*\\.(?:jpe?g|png|gif|webp)",RegexOption.IGNORE_CASE))}
        val media=mutableListOf<Media>();image?.let {media+=Media(it)};VideoExtraction.reddit(node,provenance.finalUrl)?.let {media+=it}
        val metadata=node.pObject("media_metadata")
        node.pObject("gallery_data").pArray("items").take(20).mapNotNull {it.pObj()?.pText("media_id")}.distinct().forEach {key->val m=metadata.get(key)?.pObj() ?: return@forEach;val s=m.pObject("s");pUrl(s.pText("u","gif"),Platform.REDDIT.origin)?.let {media+=Media(it)}}
        if(title.isBlank()&&body.isBlank()&&media.isEmpty())return null
        return Post(ContentIdentity.key(Platform.REDDIT,selected.canonicalUrl),Platform.REDDIT,selected.canonicalUrl,node.pText("author"),body,title,pStamp(node.pText("created_utc")),media=media.distinctBy {it.url}.take(20),replyCount=node.pLong("num_comments"),score=node.pLong("score"),provenance=provenance.copy(cached=true),links=listOfNotNull(pUrl(node.pText("url_overridden_by_dest","url"),Platform.REDDIT.origin)?.takeIf {url->url!=selected.canonicalUrl&&media.none {it.url==url}}))
    }
    fun fxStatus(node: JsonObject,platform: Platform,provenance: Provenance,routes: DarwinRoutes): Post? {
        if(node.pText("type").let {it.isNotEmpty()&&it!="status"}||node.pObject("author").pText("protected")=="true")return null
        val provider=node.pText("provider");if(provider.isNotBlank()&&provider!=platform.id)return null
        val canonical=routes.recognize(node.pText("url"))?.takeIf {it.platform==platform&&it.kind==TargetKind.POST} ?: return null
        val author=node.pObject("author");val handle=author.pText("screen_name");val id=node.pText("id")
        if(handle.isBlank()||id.isBlank())return null
        val path=canonical.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty()
        if(platform==Platform.X&&(path.size!=3||path[1]!="status"||path[2]!=id||!path[0].equals(handle,true)))return null
        if(platform==Platform.BLUESKY&&(path.size!=4||path[0]!="profile"||path[2]!="post"||path[3]!=id||!path[1].equals(handle,true)&&path[1]!=author.pText("did","id")))return null
        val media=node.pObject("media");val images=media.pArray("photos").take(20).mapNotNull {raw->raw.pObj()?.let {p->pUrl(p.pText("url"),provenance.finalUrl)?.let {Media(it,description=p.pText("altText","alt_text"))}}}
        val videos=media.pArray("videos").take(8).mapNotNull {raw->raw.pObj()?.let {v->
            val formats=v.pArray("formats").take(32).mapNotNull {it.pObj()}.filter {it.pText("container")=="mp4"&&it.pText("codec") in setOf("","h264")}
            val preferred=formats.maxByOrNull {it.pLong("bitrate") ?: 0}
            pUrl(preferred?.pText("url").orEmpty().ifBlank {v.pText("url")},provenance.finalUrl)?.let {url->
                val candidates=formats.mapNotNull {format->pUrl(format.pText("url"),provenance.finalUrl)?.let {it to (format.pLong("bitrate") ?: 0L)}}
                Media(url,"video",pUrl(v.pText("thumbnail_url"),provenance.finalUrl),v.pText("altText","alt_text"),
                    durationMs=MediaDuration.seconds(v["duration"]),samplingUrl=VideoAssetIdentity.smallRendition(url,candidates))
            }
        }}
        val body=node.pText("text");if(body.isBlank()&&images.isEmpty()&&videos.isEmpty())return null
        val card=node.pObject("card");val links=listOfNotNull(pUrl(card.pText("url"),provenance.finalUrl))
        val parent=fxParent(node,platform,canonical,routes)
        return Post(ContentIdentity.key(platform,canonical.canonicalUrl),platform,canonical.canonicalUrl,author.pText("name").ifBlank {handle},body,publishedAt=pStamp(node.pText("created_timestamp","created_at")),avatar=pUrl(author.pText("avatar_url"),provenance.finalUrl),media=images+videos,replyCount=node.pLong("replies"),score=node.pLong("likes"),provenance=provenance,links=links,contextNotes=PublicContextNotes.fx(node,platform,canonical.canonicalUrl),
            parentId=parent?.let {ContentIdentity.key(platform,it.canonicalUrl)},relationId=id,sourceParentId=parent?.let {node.pObject("replying_to").pText("status")},
            extractionCoverage=parent?.let {ExtractionCoverage(text=if(body.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,attachments=if(images.isNotEmpty()||videos.isNotEmpty())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,replyParent=ExtractionStatus.PRESENT)})
    }
    private fun fxParent(node:JsonObject,platform:Platform,selected:Target,routes:DarwinRoutes):Target? {
        val reply=node.pObject("replying_to");val account=reply.pText("screen_name");val id=reply.pText("status")
        val x=platform==Platform.X
        if(platform !in setOf(Platform.X,Platform.BLUESKY)||!account.matches(if(x)Regex("[A-Za-z0-9_]{1,15}")else Regex("[A-Za-z0-9.:-]{1,253}"))||
            !id.matches(if(x)Regex("[0-9]{1,30}")else Regex("[A-Za-z0-9_-]{1,100}")))return null
        val expected=routes.recognize(if(x)"https://x.com/$account/status/$id"else "https://bsky.app/profile/$account/post/$id",platform) ?: return null
        val supplied=reply.pText("url")
        if(supplied.isNotBlank()) {
            val url=supplied.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
            val actual=routes.recognize(url.toString(),platform)?.takeIf {it.platform==platform&&it.kind==TargetKind.POST} ?: return null
            val parts=actual.canonicalUrl.toHttpUrlOrNull()?.pathSegments ?: return null
            if(x) {if(parts.size!=3||!parts[0].equals(account,true)||parts[1]!="status"||parts[2]!=id)return null}
            else if(parts.size!=4||parts[0]!="profile"||parts[1]!=account||parts[2]!="post"||parts[3]!=id)return null
            if(ContentIdentity.key(platform,actual.canonicalUrl)!=ContentIdentity.key(platform,expected.canonicalUrl))return null
        }
        return expected.takeUnless {ContentIdentity.key(platform,it.canonicalUrl)==ContentIdentity.key(platform,selected.canonicalUrl)}
    }
    /** A bounded, declared thread array, never a recursive walk through quotes
     * or recommendations. Only descendants of the selected post, by its actual
     * source author, can extend this unrolled publisher thread. */
    fun fxThread(data:JsonObject,selected:Post,provenance:Provenance,routes:DarwinRoutes):List<Post> {
        val candidates=data.pArray("thread").take(64).mapNotNull {it.pObj()?.let {node->fxStatus(node,selected.platform,provenance,routes)}}
        return listOf(selected)+PublisherThreads.descendants(selected,candidates)
    }
    /** Tumblr v1 is JSON with one known assignment wrapper, never arbitrary JavaScript. */
    fun tumblrDocument(text: String): JsonObject {
        val trimmed=text.trim().removePrefix("\uFEFF")
        val body=if(trimmed.startsWith("{"))trimmed else Regex("^var\\s+tumblr_api_read\\s*=\\s*(\\{.*})\\s*;?\\s*$",RegexOption.DOT_MATCHES_ALL).matchEntire(trimmed)?.groupValues?.get(1) ?: throw SourceFailure(HealthStatus.EMPTY,"No public legacy Tumblr data returned")
        return pJson(body)
    }
    fun tumblrPost(node: JsonObject,blog: String,target: Target,provenance: Provenance,routes: DarwinRoutes): Post? {
        val id=node.pText("id").takeIf {it.matches(Regex("[0-9]{1,30}"))} ?: return null
        val link=routes.recognize(node.pText("url-with-slug","url"))?.takeIf {it.platform==Platform.TUMBLR&&it.kind==TargetKind.POST} ?: return null
        val pieces=link.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();if(!pieces.firstOrNull().equals(blog,true)||pieces.filter {it.toLongOrNull()!=null}.firstOrNull()!=id)return null
        val markup=when(node.pText("type")) {
            "regular"->node.pText("regular-body")
            "photo"->node.pText("photo-caption")
            "quote"->listOf(node.pText("quote-text"),node.pText("quote-source")).filter(String::isNotBlank).joinToString("\n\n")
            "link"->node.pText("link-description")
            "conversation"->node.pText("conversation-text")
            "video"->node.pText("video-caption")+node.pText("video-player")
            "audio"->node.pText("audio-caption")
            "answer"->listOf(node.pText("question"),node.pText("answer")).filter(String::isNotBlank).joinToString("\n\n")
            else->return null
        }
        val html=Jsoup.parse(markup,provenance.finalUrl);val images=mutableListOf<Media>()
        if(node.pText("type")=="photo") {
            val gallery=node.pArray("photos").ifEmpty {listOf(node)}
            gallery.take(20).mapNotNull {it.pObj()?.let {p->pUrl(p.pText("photo-url-1280","photo-url-500","photo-url-400","photo-url-250"),provenance.finalUrl)}}.forEach {images+=Media(it)}
        }
        html.select("img[src]").take(20).mapNotNull {pUrl(it.attr("src"),provenance.finalUrl)?.let {url->Media(url,description=it.attr("alt"))}}.forEach {images+=it}
        val media=(images+VideoExtraction.html(html,provenance.finalUrl)).distinctBy {it.url}.take(20)
        val title=pPlain(node.pText("regular-title","link-text","conversation-title"));val body=pPlain(markup)
        if(title.isBlank()&&body.isBlank()&&media.isEmpty())return null
        val links=(html.select("a[href]").mapNotNull {pUrl(it.attr("href"),provenance.finalUrl)}+listOfNotNull(pUrl(node.pText("link-url"),provenance.finalUrl))).distinct().take(12)
        return Post(ContentIdentity.key(Platform.TUMBLR,link.canonicalUrl),Platform.TUMBLR,link.canonicalUrl,blog,body,title,pStamp(node.pText("unix-timestamp")),media=media,replyCount=node.pLong("note-count"),provenance=provenance,links=links)
    }
}

private class SotweDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.FEED,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==Platform.X&&target.kind in setOf(TargetKind.PROFILE,TargetKind.FEED)&&target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.let {it.size==1&&it[0].matches(Regex("[A-Za-z0-9_]{1,15}"))}==true
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This Sotwe route reads a selected public account")
        require(cursor==null){"Older-page retrieval is not verified on this public route"}
        val username=target.canonicalUrl.toHttpUrlOrNull()!!.pathSegments.single();val payload=gateway.text(source.root.trimEnd('/')+"/api/v3/user/$username/",target.canonicalUrl,maxBytes=2L*1024*1024)
        return ProtocolDiscoveryParsing.sotwe(pJson(payload.text),target,provenance(payload))
    }
}

/** Historical third-party records are always cache evidence and never subscription refresh proof. */
private class ArcticShiftDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    private val discussion=source.frontend=="arctic-shift-comments"
    override val capabilities=if(discussion)setOf(Capability.POST,Capability.COMMENTS,Capability.MEDIA)else setOf(Capability.POST,Capability.TIMELINE,Capability.PROFILE,Capability.SEARCH,Capability.MEDIA)
    override fun accepts(target: Target): Boolean {
        if(target.platform!=Platform.REDDIT||target.kind !in setOf(TargetKind.POST,TargetKind.THREAD,TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.SEARCH))return false
        if(discussion&&target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))return false
        val scope=SearchScopes.from(target);if(target.kind==TargetKind.SEARCH&&scope==null||scope!=null&&scope.kind !in setOf(SearchScopeKind.COMMUNITY,SearchScopeKind.ACCOUNT))return false
        return target.kind !in setOf(TargetKind.POST,TargetKind.THREAD)||ContentIdentity.key(target.platform,target.canonicalUrl).matches(Regex("post:[a-z0-9]{1,20}"))
    }
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"Historical archive data cannot confirm a subscription refresh")
    override fun sentinel(capability: Capability): Target=throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive lookups run only for selected content, never heartbeat probes")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"The archive needs a public post, author, community or scoped search")
        val root=source.root.trimEnd('/');val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val token=pToken(source,target,cursor);val before=token?.toLongOrNull();require(token==null||before!=null&&before>0)
        if(post&&!discussion)require(cursor==null){"A selected archive post has no older-page continuation"}
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val id=if(post)ContentIdentity.key(target.platform,target.canonicalUrl).removePrefix("post:")else null
        val scope=SearchScopes.from(target);val author=if(!post&&target.kind==TargetKind.PROFILE)path.getOrNull(1).orEmpty().takeIf {path.firstOrNull() in setOf("u","user")}else scope?.takeIf {it.kind==SearchScopeKind.ACCOUNT}?.value
        val community=if(!post&&target.kind==TargetKind.COMMUNITY)path.getOrNull(1).orEmpty().takeIf {path.firstOrNull()=="r"}else scope?.takeIf {it.kind==SearchScopeKind.COMMUNITY}?.value
        require(author==null||author.matches(Regex("[A-Za-z0-9_-]{1,100}")));require(community==null||community.matches(Regex("[A-Za-z0-9_]{1,100}")))
        if(!post&&author==null&&community==null)throw SourceFailure(HealthStatus.UNSUPPORTED,"Archive text search must be narrowed to an author or subreddit")
        val endpoint=if(post)"/api/posts/ids?ids=$id"else "/api/posts/search?limit=20&sort=desc"+(author?.let {"&author=${DarwinRoutes.encode(it)}"}.orEmpty())+(community?.let {"&subreddit=${DarwinRoutes.encode(it)}"}.orEmpty())+(if(target.kind==TargetKind.SEARCH)"&query=${DarwinRoutes.encode(target.identity.take(1000))}"else "")+(before?.let {"&before=$it"}.orEmpty())
        val payload=if(cursor==null||!post)gateway.text(root+endpoint,target.canonicalUrl,maxBytes=2L*1024*1024)else null
        val data=payload?.let {pJson(it.text)};val provenance=payload?.let {provenance(it).copy(cached=true)}
        val nodes=data?.pArray("data").orEmpty().take(100).mapNotNull {it.pObj()}.filter {n->(id==null||n.pText("id")==id)&&(author==null||n.pText("author").equals(author,true))&&(community==null||n.pText("subreddit").equals(community,true))}
        val roots=if(provenance!=null)nodes.mapNotNull {ProtocolDiscoveryParsing.redditArchivePost(it,provenance,routes)}.filter {p->!post||ContentIdentity.key(p.platform,p.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}else emptyList()
        if(!post) {
            val next=nodes.lastOrNull()?.pLong("created_utc")?.takeIf {nodes.size>=20&&it>0&&it!=before}
            return pUsable(Page(pChronological(roots),pCursor(source,target,next?.toString()),profile=if(target.kind==TargetKind.PROFILE&&roots.isNotEmpty())Profile(target,author.orEmpty())else null,provenance=provenance,notice="Historical Arctic Shift records, not a live refresh. Archive coverage and capture times vary; missing media and comments may remain unavailable."))
        }
        if(cursor==null&&roots.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"This archive has no readable copy of the selected post")
        if(!discussion)return Page(roots,provenance=provenance,notice="Historical Arctic Shift post. This is an archive copy, not a current live refresh; comments use their own separate route.")
        val commentsPayload=try {gateway.text("$root/api/comments/search?link_id=$id&limit=50&sort=desc"+(before?.let {"&before=$it"}.orEmpty()),target.canonicalUrl,maxBytes=2L*1024*1024)}catch(e: CancellationException){throw e}catch(e: Exception){if(roots.isEmpty())throw e;return Page(roots,provenance=provenance,notice="Historical Arctic Shift post; archived comments could not be retrieved. This does not confirm that the live post or thread is available.")}
        val comments=pJson(commentsPayload.text).pArray("data").take(100).mapNotNull {it.pObj()}.filter {it.pText("link_id").removePrefix("t3_")==id&&it.pText("id").matches(Regex("[a-z0-9]{1,20}"))}
        val available=comments.map {it.pText("id")}.toSet();val commentProvenance=this.provenance(commentsPayload).copy(cached=true)
        val replies=comments.mapNotNull {node->val commentId=node.pText("id");val text=node.pText("body").takeUnless {it in setOf("","[removed]","[deleted]")} ?: return@mapNotNull null
            val canonical="https://www.reddit.com/comments/$id/_/$commentId";val parent=node.pText("parent_id");val parentId=when {parent=="t3_$id"||parent==id->ContentIdentity.key(Platform.REDDIT,target.canonicalUrl);parent.startsWith("t1_")&&parent.removePrefix("t1_") in available->"comment:$id:${parent.removePrefix("t1_")}";else->null}
            Post(ContentIdentity.key(Platform.REDDIT,canonical),Platform.REDDIT,canonical,node.pText("author"),text,publishedAt=pStamp(node.pText("created_utc")),score=node.pLong("score"),parentId=parentId,provenance=commentProvenance)
        }
        val next=comments.lastOrNull()?.pLong("created_utc")?.takeIf {comments.size>=50&&it>0&&it!=before}
        return Page(roots+pChronological(replies),pCursor(source,target,next?.toString()),provenance=provenance ?: commentProvenance,notice="Historical Arctic Shift post and available archived comments. This is not the current live thread; unknown capture dates remain unknown. Original comment ranking is unavailable.")
    }
}

private class FxEmbedDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=pReadCaps+Capability.SEARCH
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind in setOf(TargetKind.PROFILE,TargetKind.FEED,TargetKind.POST,TargetKind.THREAD,TargetKind.SEARCH,TargetKind.TAG)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);val token=pToken(source,target,cursor);val root=source.root.trimEnd('/');val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val search=target.kind in setOf(TargetKind.SEARCH,TargetKind.TAG)
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();var handle=""
        val endpoint=when {
            post->{require(cursor==null){"This selected-post route has no pagination"};val method=if(target.kind==TargetKind.THREAD)"thread"else "status";if(source.platform==Platform.X){val id=path.getOrNull(2).orEmpty();require(path.getOrNull(1)=="status"&&id.matches(Regex("[0-9]{1,30}")));"/2/$method/$id"}else {handle=path.getOrNull(1).orEmpty();val id=path.getOrNull(3).orEmpty();require(path.getOrNull(0)=="profile"&&path.getOrNull(2)=="post"&&handle.matches(Regex("[A-Za-z0-9.:-]{1,253}"))&&id.matches(Regex("[A-Za-z0-9_-]{1,100}")));"/2/$method/${DarwinRoutes.encode(handle)}/$id"}}
            search->{var query=if(target.kind==TargetKind.TAG)"#"+target.identity.removePrefix("#")else target.identity;val scope=SearchScopes.from(target);if(scope!=null)query=when(scope.kind){SearchScopeKind.ACCOUNT->"from:${scope.value} ($query)";SearchScopeKind.TAG->"#${scope.value} ($query)";else->throw SourceFailure(HealthStatus.UNSUPPORTED,"This search scope needs a selected public feed")};require(query.length in 1..2000);"/2/search?q=${DarwinRoutes.encode(query)}&feed=latest&count=30"}
            target.kind in setOf(TargetKind.PROFILE,TargetKind.FEED)->{handle=if(source.platform==Platform.X)path.firstOrNull().orEmpty()else path.getOrNull(1).orEmpty();require(handle.matches(if(source.platform==Platform.X)Regex("[A-Za-z0-9_]{1,15}")else Regex("[A-Za-z0-9.:-]{1,253}")));"/2/profile/${DarwinRoutes.encode(handle)}/statuses?count=20"}
            else->throw SourceFailure(HealthStatus.UNSUPPORTED,"This route reads public posts, profiles and search results")
        }
        val url=root+endpoint+(token?.let {(if(endpoint.contains('?'))"&"else "?")+"cursor=${DarwinRoutes.encode(it)}"}.orEmpty());val payload=gateway.text(url,target.canonicalUrl,maxBytes=2L*1024*1024)
        val data=pJson(payload.text);val code=data.pLong("code");if(code!=null&&code!=200L)throw SourceFailure(if(code==429L)HealthStatus.RATE_LIMITED else if(code==401L||code==403L)HealthStatus.LOGIN_REQUIRED else HealthStatus.UNAVAILABLE,"The public embed provider could not retrieve this content")
        val provenance=provenance(payload);val nodes=if(post)listOfNotNull(data.get("status")?.pObj(),data.get("tweet")?.pObj()).take(1)else data.pArray("results").take(100).mapNotNull {it.pObj()}
        val profileUser=data.pObject("user")
        val profileUserName=profileUser.pText("screen_name","username")
        if(!post&&!search&&profileUserName.isNotBlank()&&!profileUserName.equals(handle,true))throw SourceFailure(HealthStatus.EMPTY,"The public embed provider returned a different account")
        if(!post&&!search&&profileUser.pText("protected","is_protected","private")=="true")throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This account is not public")
        val namedAuthors=mutableListOf<Pair<Long,JsonObject>>()
        val posts=nodes.mapNotNull {node->ProtocolDiscoveryParsing.fxStatus(node,source.platform,provenance,routes)?.let {p->
            val didProof=source.platform==Platform.BLUESKY&&handle.startsWith("did:")&&node.pText("at_uri")=="at://$handle/app.bsky.feed.post/${node.pText("id")}"&&node.pText("id").isNotBlank()
            val accepted=when {
                post->ContentIdentity.key(p.platform,p.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)||didProof&&p.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()==path.getOrNull(3)
                search->true
                else->{val parts=p.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val owner=if(source.platform==Platform.X)parts.firstOrNull()else parts.getOrNull(1);owner.equals(handle,true)||didProof}
            }
            if(!accepted)null else {
                val author=node.pObject("author")
                if(!post&&!search&&author.pText("screen_name").equals(handle,true)&&author.pText("name").isNotBlank())namedAuthors+=((p.publishedAt ?: 0L) to author)
                if(post&&didProof)p.copy(canonicalUrl=target.canonicalUrl,id=ContentIdentity.key(target.platform,target.canonicalUrl))else p
            }
        }}
        val bottom=data.pObject("cursor").pText("bottom").takeIf {!post&&it!=token}
        // Explicit account metadata or an exact, already validated post author's
        // name is evidence. A Post.author fallback or the requested handle is not.
        val namedAccount=profileUser.takeIf {profileUserName.equals(handle,true)&&it.pText("name").isNotBlank()}
            ?: namedAuthors.maxByOrNull {it.first}?.second
        val profile=if(!post&&!search&&posts.isNotEmpty())Profile(target,namedAccount?.pText("name") ?: handle,
            namedAccount?.pText("description").orEmpty(),namedAccount?.pText("avatar_url")?.let {pUrl(it,provenance.finalUrl)},displayNameSupplied=namedAccount!=null)else null
        val editions=if(target.kind==TargetKind.THREAD)posts.singleOrNull()?.let {ProtocolDiscoveryParsing.fxThread(data,it,provenance,routes)}.orEmpty()else posts
        return pUsable(Page(pChronological(editions),pCursor(source,target,bottom),profile,provenance,notice=null))
    }
}

private class VxEmbedDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=setOf(Capability.POST,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"This embed route reads selected public posts")
        require(cursor==null);val selected=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val id=selected.getOrNull(2).orEmpty();require(selected.getOrNull(1)=="status"&&id.matches(Regex("[0-9]{1,30}")))
        val payload=gateway.text(source.root.trimEnd('/')+"/${selected.first()}/status/$id",target.canonicalUrl,maxBytes=1024L*1024);val data=pJson(payload.text);val canonical=routes.recognize(data.pText("tweetURL"))
        if(data.pText("tweetID")!=id||canonical?.platform!=Platform.X||ContentIdentity.key(Platform.X,canonical.canonicalUrl)!=ContentIdentity.key(Platform.X,target.canonicalUrl)||data.pText("user_screen_name").let {it.isBlank()||!it.equals(selected.first(),true)})throw SourceFailure(HealthStatus.EMPTY,"The embed provider returned a different public post")
        val media=data.pArray("media_extended").take(20).mapNotNull {raw->raw.pObj()?.let {m->pUrl(m.pText("url"),payload.finalUrl)?.let {Media(it,if(m.pText("type")=="video"||m.pText("type")=="gif")"video"else "image",pUrl(m.pText("thumbnail_url"),payload.finalUrl),m.pText("altText"))}}}
        val body=data.pText("text");if(body.isBlank()&&media.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The embed contained no readable public post")
        val provenance=provenance(payload);return Page(listOf(Post(ContentIdentity.key(Platform.X,target.canonicalUrl),Platform.X,target.canonicalUrl,data.pText("user_name","user_screen_name"),body,publishedAt=pStamp(data.pText("date_epoch")),avatar=pUrl(data.pText("user_profile_image_url"),payload.finalUrl),media=media,replyCount=data.pLong("replies"),score=data.pLong("likes"),provenance=provenance)),provenance=provenance,notice="Public embed; replies and user timelines are not supplied by this reader.")
    }
}

private class TumblrLegacyDataAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=pReadCaps
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind in setOf(TargetKind.PROFILE,TargetKind.FEED,TargetKind.POST,TargetKind.THREAD)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(target.kind !in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD,TargetKind.FEED))throw SourceFailure(HealthStatus.UNSUPPORTED,"The public legacy format reads a selected blog or post")
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val blog=path.firstOrNull().orEmpty().removePrefix("@");require(blog.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}")))
        val post=target.kind in setOf(TargetKind.POST,TargetKind.THREAD);val id=if(post)path.drop(1).firstOrNull {it.matches(Regex("[0-9]{1,30}"))} ?: error("Invalid public Tumblr post")else null
        val token=pToken(source,target,cursor);val offset=token?.toIntOrNull() ?: 0;require(offset in 0..100_000&&(!post||cursor==null))
        val payload=gateway.text("https://$blog.tumblr.com/api/read/json?num=20"+(if(post)"&id=$id"else "&start=$offset"),target.canonicalUrl,maxBytes=2L*1024*1024)
        val data=ProtocolDiscoveryParsing.tumblrDocument(payload.text);val feed=data.pObject("tumblelog");if(!feed.pText("name").equals(blog,true))throw SourceFailure(HealthStatus.EMPTY,"The source returned a different public blog")
        val provenance=provenance(payload,"https://$blog.tumblr.com");val nodes=data.pArray("posts");val posts=nodes.take(100).mapNotNull {it.pObj()?.let {n->ProtocolDiscoveryParsing.tumblrPost(n,blog,target,provenance,routes)}}.filter {!post||ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)}
        val total=data.pLong("posts-total");val more=!post&&nodes.size==20&&(total==null||offset+20<total)
        return pUsable(Page(pChronological(posts),if(more)pCursor(source,target,(offset+20).toString())else null,if(!post)Profile(target,feed.pText("title","name"),pPlain(feed.pText("description")))else null,provenance,notice=null))
    }
}

/** Independent syndication formats on an existing route; strict feed identity is required. */
private class ProtocolFeedAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=pReadCaps-Capability.POST
    override fun accepts(target: Target): Boolean {
        if(target.platform!=source.platform||target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.FEED))return false
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty()
        return when(source.platform){Platform.LEMMY->path.size==2&&path[0] in setOf("c","u");Platform.MISSKEY->path.size==1&&path[0].startsWith('@');Platform.X->path.size==1;Platform.BLUESKY->path.size==2&&path[0]=="profile";else->false}
    }
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);require(cursor==null){"This feed has no verified older-page continuation"};if(target.kind !in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY,TargetKind.FEED))throw SourceFailure(HealthStatus.UNSUPPORTED,"This syndication format supplies selected public profile or community posts")
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val root=if(source.platform in setOf(Platform.LEMMY,Platform.MISSKEY))selectedRoot(target)else source.root.trimEnd('/')
        val profilePath: String;val endpoint: String
        when(source.platform) {
            Platform.LEMMY->{require(path.size==2&&path[0] in setOf("c","u")&&path[1].matches(Regex("[A-Za-z0-9_.-]{1,100}")));profilePath="/${path[0]}/${path[1]}";endpoint="/feeds/${path[0]}/${path[1]}.xml?sort=New"}
            Platform.MISSKEY->{val name=path.singleOrNull()?.removePrefix("@").orEmpty();require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")));profilePath="/@$name";endpoint=profilePath+(if(source.frontend=="misskey-atom")".atom"else ".rss")}
            Platform.X->{val name=path.singleOrNull().orEmpty();require(name.matches(Regex("[A-Za-z0-9_]{1,15}")));profilePath="/$name";endpoint="$profilePath/feed.xml?count=20"}
            Platform.BLUESKY->{require(path.size==2&&path[0]=="profile"&&path[1].matches(Regex("[A-Za-z0-9.:-]{1,253}")));profilePath="/profile/${path[1]}";endpoint="$profilePath/feed.xml?count=20"}
            else->throw SourceFailure(HealthStatus.UNSUPPORTED,"No verified public feed format")
        }
        return gateway.feed(root+endpoint,target,source,maxBytes=2L*1024*1024){payload->
        val body=payload.text.trim()
        if(!Regex("^(?:<\\?xml[^>]*>\\s*)?<(?:[A-Za-z0-9_]+:)?(?:rss|feed)(?:\\s|>)",RegexOption.IGNORE_CASE).containsMatchIn(body))throw SourceFailure(HealthStatus.EMPTY,"The feed returned an HTML shell instead of posts")
        val provenance=provenance(payload,root);val page=Extraction.rss(body,target,provenance,routes);val document=Jsoup.parse(body,"",Parser.xmlParser());val feed=document.selectFirst("rss > channel,feed") ?: throw SourceFailure(HealthStatus.EMPTY,"No syndication channel returned")
        val metadataLinks=feed.children().filter {it.normalName().substringAfter(':')=="link"}.filter {it.attr("rel") in setOf("","alternate")}.mapNotNull {pUrl(it.attr("href").ifBlank {it.text()},root)}
        val accepted=metadataLinks.any {link->val recognized=routes.recognize(link,source.platform);recognized?.platform==source.platform&&recognized.canonicalUrl.substringBefore('?').trimEnd('/')==target.canonicalUrl.substringBefore('?').trimEnd('/')} || source.platform in setOf(Platform.LEMMY,Platform.MISSKEY)&&metadataLinks.any {link->val parsed=link.toHttpUrlOrNull();val selected=target.canonicalUrl.toHttpUrlOrNull();parsed!=null&&selected!=null&&parsed.host==selected.host&&parsed.port==selected.port&&parsed.encodedPath.trimEnd('/')==profilePath}
        if(!accepted)throw SourceFailure(HealthStatus.EMPTY,"The source returned a different public feed")
        val posts=page.posts.filter {post->val url=post.canonicalUrl.toHttpUrlOrNull() ?: return@filter false;val selected=target.canonicalUrl.toHttpUrlOrNull()!!;val recognized=routes.recognize(post.canonicalUrl,source.platform);when(source.platform){Platform.X->recognized?.platform==source.platform&&recognized.kind==TargetKind.POST&&url.pathSegments.firstOrNull().equals(path[0],true);Platform.BLUESKY->recognized?.platform==source.platform&&recognized.kind==TargetKind.POST&&url.pathSegments.getOrNull(1).equals(path[1],true);Platform.LEMMY->url.host==selected.host&&url.port==selected.port&&url.pathSegments.size==2&&url.pathSegments[0]=="post"&&url.pathSegments[1].toLongOrNull()!=null;Platform.MISSKEY->url.host==selected.host&&url.port==selected.port&&url.pathSegments.size==2&&url.pathSegments[0]=="notes"&&url.pathSegments[1].matches(Regex("[A-Za-z0-9_-]{1,160}"));else->false}&&(post.body.isNotBlank()||post.title.isNotBlank()||post.media.isNotEmpty())}.map {it.copy(id=ContentIdentity.key(it.platform,it.canonicalUrl),provenance=provenance)}
        pUsable(Page(pChronological(posts),profile=Profile(target,feed.children().firstOrNull {it.normalName()=="title"}?.text().orEmpty().ifBlank {path.last()}),provenance=provenance,limitedTimeline=true,notice="Public syndication backup; comments and older-page retrieval are unavailable on this format."))
        }
    }
}

/** Misskey's public direct replies endpoint is distinct from account timelines. */
private class MisskeyDiscussionAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): ProtocolAdapter(source,gateway,routes) {
    override val capabilities=setOf(Capability.POST,Capability.COMMENTS,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.let {it.size==2&&it[0]=="notes"}==true
    override suspend fun fetch(target: Target,cursor: String?): Page {
        validate(target);if(target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"Select a public note to read its direct replies")
        val root=selectedRoot(target);val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty();val id=path.getOrNull(1).orEmpty();require(path.size==2&&path[0]=="notes"&&id.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        val token=pToken(source,target,cursor);require(token==null||token.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        val parentPayload=if(cursor==null)gateway.json("$root/api/notes/show",JsonObject().apply {addProperty("noteId",id)},target.canonicalUrl)else null
        val parent=parentPayload?.let {payload->val value=pJson(payload.text);if(value.pText("id")!=id||value.get("renote")?.isJsonObject==true&&value.pText("text").isBlank()&&value.pArray("files").isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The source did not return the selected public note");PublicSocialParsing.misskey(value,provenance(payload,root),root).singleOrNull()} ?: if(cursor==null)throw SourceFailure(HealthStatus.EMPTY,"No selected public note returned")else null
        val request=JsonObject().apply {addProperty("noteId",id);addProperty("limit",30);token?.let {addProperty("untilId",it)}}
        val payload=try {gateway.json("$root/api/notes/replies",request,target.canonicalUrl)}catch(e: CancellationException){throw e}catch(e: Exception){if(parent==null)throw e;return Page(listOf(parent),provenance=parentPayload?.let {provenance(it,root)},notice="The public note loaded, but this server did not supply public replies.")}
        val values=try {JsonParser.parseString(payload.text).takeIf {it.isJsonArray}?.asJsonArray?.toList() ?: throw IllegalArgumentException()}catch(_: Exception){throw SourceFailure(HealthStatus.EMPTY,"No recognizable public reply stream returned")}
        val provenance=provenance(payload,root);val replies=values.take(100).mapNotNull {it.pObj()}.filter {it.pText("replyId")==id&&it.pText("id")!=id}.mapNotNull {node->PublicSocialParsing.misskey(node,provenance,root).singleOrNull()?.takeIf {it.canonicalUrl.endsWith("/"+node.pText("id"))}?.copy(parentId=ContentIdentity.key(target.platform,target.canonicalUrl))}
        val bottom=values.lastOrNull()?.pObj()?.pText("id")?.takeIf {values.size>=30&&it!=token&&it.matches(Regex("[A-Za-z0-9_-]{1,160}"))}
        return Page(listOfNotNull(parent)+pChronological(replies),pCursor(source,target,bottom),provenance=provenance,notice="Public direct replies only, newest first. Nested reply retrieval and popularity ranking are not provided by this route.")
    }
}
