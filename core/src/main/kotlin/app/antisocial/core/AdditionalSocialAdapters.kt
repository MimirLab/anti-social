/* AntiSocial, GPL-3.0-or-later. Independently written public data readers.
 * LinkedIn JSON-LD discovery informed by RSSHub's AGPL-3.0 linkedin/posts.ts
 * and linkedin/utils.ts: https://github.com/DIYgod/RSSHub/tree/master/lib/routes/linkedin
 * Snapchat field names verified against its public __NEXT_DATA__ payload; no
 * application scripts are executed. Truth shares Mastodon-shaped read requests,
 * not an assumption that its live deployment allows anonymous access.
 */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant

fun additionalSocialAdapter(source: Source,gateway: NetworkGateway,routes: DarwinRoutes): DataAdapter?=when(source.platform) {
    Platform.LINKEDIN -> PublicDocumentAdapter(source,gateway,routes)
    Platform.SNAPCHAT -> PublicDocumentAdapter(source,gateway,routes)
    Platform.TRUTH_SOCIAL -> object: DataAdapter by mastodonProtocolAdapter(source,gateway,routes) {
        override fun sentinel(capability: Capability)=Target(Platform.TRUTH_SOCIAL,TargetKind.PROFILE,"realDonaldTrump","https://truthsocial.com/@realDonaldTrump")
    }
    else -> null
}

private class PublicDocumentAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.FEED,Capability.MEDIA)
    override fun sentinel(capability: Capability)=if(source.platform==Platform.LINKEDIN)routes.target(source.platform,"company/google")else routes.target(source.platform,"nasa")
    override suspend fun feed(target: Target)=fetch(target)
    override suspend fun profile(target: Target)=read(target,profileOnly=true)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(cursor==null){"This public snapshot has no verified continuation"}
        return read(target)
    }
    private suspend fun read(target: Target,profileOnly: Boolean=false): Page {
        require(target.platform==source.platform)
        if(target.kind !in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"This reader supports public profiles and posts")
        val original=target.canonicalUrl.toHttpUrlOrNull() ?: error("Invalid public social link")
        val url=if(source.role==SourceRole.ORIGINAL)target.canonicalUrl else source.root.trimEnd('/')+original.encodedPath+(original.encodedQuery?.let {"?$it"}.orEmpty())
        val payload=gateway.text(url,target.canonicalUrl,headers=mapOf("Accept-Language" to "en-US"))
        if(source.role!=SourceRole.ORIGINAL&&!DarwinRoutes.matches(URI(payload.finalUrl),source.root))throw SourceFailure(HealthStatus.UNAVAILABLE,"The alternate redirected outside its selected source")
        return when(source.platform) {
            Platform.LINKEDIN -> AdditionalSocialParsing.linkedIn(payload,target,source,profileOnly)
            Platform.SNAPCHAT -> AdditionalSocialParsing.snapchat(payload,target,source,profileOnly)
            else -> throw SourceFailure(HealthStatus.UNSUPPORTED,"No public document reader is available")
        }
    }
}

/** Only structured records belonging to the selected identity become native cards. */
object AdditionalSocialParsing {
    private fun JsonElement?.obj()=this?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonElement?.string()=this?.takeIf {it.isJsonPrimitive}?.asString.orEmpty()
    private fun JsonObject.at(vararg keys: String): JsonElement? {var current: JsonElement?=this;keys.forEach {current=current.obj()?.get(it)};return current}
    private fun JsonObject.s(vararg keys: String)=at(*keys).string()
    private fun JsonObject.array(vararg keys: String)=at(*keys)?.takeIf {it.isJsonArray}?.asJsonArray?.toList().orEmpty()
    private fun text(html: String)=Jsoup.parse(html).text().take(100_000)
    private fun safe(value: String): String?=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    private fun stamp(value: String)=value.toLongOrNull()?.let {if(it<10_000_000_000L)it*1000 else it} ?: runCatching {Instant.parse(value).toEpochMilli()}.getOrNull()
    private fun objects(root: JsonElement?): List<JsonObject> {
        val objects=ArrayList<JsonObject>();var remaining=20_000
        fun visit(value: JsonElement?,depth: Int) {
            if(value==null||depth>40||--remaining<0)return
            if(value.isJsonObject){objects+=value.asJsonObject;value.asJsonObject.entrySet().forEach {visit(it.value,depth+1)}}
            else if(value.isJsonArray)value.asJsonArray.forEach {visit(it,depth+1)}
        }
        visit(root,0);return objects
    }
    private fun fail(payload: Payload,message: String): Nothing {
        val body=payload.text.take(100_000).lowercase()
        throw SourceFailure(when {body.contains("captcha")||body.contains("checkpoint")||body.contains("challenge-platform")->HealthStatus.CHALLENGE;body.contains("sign in to view")||body.contains("login_required")||payload.finalUrl.contains("/login")||payload.finalUrl.contains("/authwall")->HealthStatus.LOGIN_REQUIRED;else->HealthStatus.EMPTY},message)
    }
    private fun key(platform: Platform,url: String)=ContentIdentity.key(platform,url)
    private fun linkedInId(url: String)=Regex("(?:activity-|ugcPost:|share:)([0-9]{5,30})").find(url)?.groupValues?.get(1)
    private fun company(url: String): String? {
        val parsed=url.toHttpUrlOrNull() ?: return null
        if(parsed.host!="linkedin.com"&&!parsed.host.endsWith(".linkedin.com"))return null
        val segments=parsed.pathSegments.filter(String::isNotBlank)
        return if(segments.size==2&&segments[0] in setOf("company","school"))segments[1].lowercase()else null
    }
    fun linkedIn(payload: Payload,target: Target,source: Source,profileOnly: Boolean=false): Page {
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        val records=document.select("script[type=application/ld+json]").take(32).flatMap {script->objects(runCatching {JsonParser.parseString(script.data())}.getOrNull())}
        val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        val selectedId=if(selected)linkedInId(target.canonicalUrl) ?: fail(payload,"This link does not identify a public LinkedIn post")else null
        val selectedCompany=if(selected)null else company(target.canonicalUrl) ?: fail(payload,"This reader supports public LinkedIn company and school pages")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        fun recordUrl(node: JsonObject)=node.s("url").ifBlank {node.s("mainEntityOfPage")}.ifBlank {node.s("@id")}
        val matching=records.filter {it.s("@type")=="SocialMediaPosting"||selected&&it.s("@type") in setOf("VideoObject","Article","BlogPosting")}.filter {node->
            val id=linkedInId(recordUrl(node))
            if(selected)id==selectedId else company(node.s("author","url"))==selectedCompany
        }
        val posts=matching.mapNotNull {node->
            val url=safe(recordUrl(node))?.takeIf {it.toHttpUrlOrNull()?.host?.let {host->host=="linkedin.com"||host.endsWith(".linkedin.com")}==true} ?: return@mapNotNull null
            if(linkedInId(url)==null)return@mapNotNull null
            val body=text(node.s("text").ifBlank {node.s("description")});val headline=text(node.s("headline"));val published=stamp(node.s("datePublished").ifBlank {node.s("uploadDate")}) ?: return@mapNotNull null
            val image=node.get("image");val images=when {image?.isJsonPrimitive==true->listOfNotNull(safe(image.asString));image?.isJsonArray==true->image.asJsonArray.take(8).mapNotNull {safe(it.string())};image?.isJsonObject==true->listOfNotNull(safe(image.asJsonObject.s("url")));else->emptyList()}
            val media=images.map {Media(it)}.toMutableList()
            if(node.s("@type")=="VideoObject")safe(node.s("contentUrl"))?.let {media+=Media(it,"video",safe(node.s("thumbnailUrl")))}
            if(body.isBlank()&&headline.isBlank()&&media.isEmpty())return@mapNotNull null
            val canonical=if(selected)target.canonicalUrl.substringBefore('?')else url
            val actor=node.at("author").obj() ?: node.at("creator").obj()
            val interactions=node.array("interactionStatistic")
            val score=node.s("interactionStatistic","userInteractionCount").toLongOrNull() ?: interactions.mapNotNull {it.obj()}.firstOrNull {it.s("interactionType").endsWith("/LikeAction")}?.s("userInteractionCount")?.toLongOrNull()
            Post(key(Platform.LINKEDIN,canonical),Platform.LINKEDIN,canonical,text(actor?.s("name").orEmpty()),body,headline,published,avatar=safe(actor?.s("image","url").orEmpty()),media=media,replyCount=node.s("commentCount").toLongOrNull(),score=score,provenance=provenance,aiDisclosures=StructuredAiMetadata.declarations(node).takeIf {it.isNotEmpty()})
        }.let(StructuredAiMetadata::mergeExactPosts).sortedByDescending {it.publishedAt}.take(30)
        val organisation=records.firstOrNull {it.s("@type") in setOf("Organization","Corporation","EducationalOrganization")&&company(it.s("url"))==selectedCompany}
        val name=organisation?.s("name")?.takeIf {it.isNotBlank()} ?: posts.firstOrNull()?.author
        if(posts.isEmpty()&&!(profileOnly&&!selected&&organisation!=null))fail(payload,"This route returned no readable public LinkedIn posts for the selected page")
        val profile=if(!selected&&name!=null)Profile(target,text(name),text(organisation?.s("description").orEmpty()),safe(organisation?.s("logo").orEmpty()),links=listOfNotNull(safe(organisation?.s("sameAs").orEmpty())))else null
        return Page(if(profileOnly)emptyList()else posts,profile=profile,provenance=provenance,notice="Public company-page snapshot; comments, personal profiles and further pages are unavailable through this reader.")
    }
    private fun snapchatData(payload: Payload): JsonObject {
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        val data=runCatching {JsonParser.parseString(document.selectFirst("script#__NEXT_DATA__")?.data().orEmpty()).asJsonObject}.getOrNull() ?: fail(payload,"This source did not return structured public Snapchat data")
        return data.at("props","pageProps").obj() ?: fail(payload,"This source did not return a public Snapchat profile")
    }
    fun snapchat(payload: Payload,target: Target,source: Source,profileOnly: Boolean=false): Page {
        val data=snapchatData(payload)
        val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        val wanted=if(selected)path.lastOrNull()else null
        val selectedEntry=if(selected)data.array("spotlightFeed","spotlightStories").mapNotNull {it.obj()}.firstOrNull {it.s("story","storyId","value")==wanted}else null
        val creator=selectedEntry?.at("metadata","videoMetadata","creator","personCreator").obj()
        val account=data.at("userProfile","publicProfileInfo").obj() ?: creator?.takeIf {selected}?.let {person->JsonObject().apply {addProperty("username",person.s("username"));addProperty("title",person.s("name"));addProperty("websiteUrl",person.s("websiteUrl"));selectedEntry?.array("metadata","contextCards").orEmpty().mapNotNull {it.obj()}.firstOrNull {it.s("contextType")=="3"&&it.s("subtitle")==person.s("username")}?.let {addProperty("profilePictureUrl",it.s("thumbnailUrl"))}}} ?: fail(payload,"This route returned no public Snapchat profile")
        val username=account.s("username").takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,64}"))} ?: fail(payload,"This source did not identify its public Snapchat profile")
        val requested=path.firstOrNull()?.removePrefix("@")?.takeIf {path.firstOrNull()?.startsWith("@")==true} ?: if(path.firstOrNull()=="add")path.getOrNull(1)else if(selected&&path.firstOrNull()=="spotlight")creator?.s("username")else null
        if(!username.equals(requested,true))fail(payload,"This source returned a different Snapchat profile")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val profile=Profile(target,text(account.s("title").ifBlank {username}),text(account.s("bio")),safe(account.s("profilePictureUrl")),account.s("subscriberCount").toLongOrNull(),listOfNotNull(safe(account.s("websiteUrl")))+account.array("sameAsLinks").mapNotNull {safe(it.string())}.take(12))
        val highlights=(data.array("spotlightHighlights")+listOfNotNull(data.at("story"),selectedEntry?.at("story"))).mapNotNull {it.obj()}.take(60)
        val posts=highlights.mapNotNull {story->
            val id=story.s("storyId","value").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,256}"))} ?: return@mapNotNull null
            if(selected&&id!=wanted)return@mapNotNull null
            val snaps=story.array("snapList").mapNotNull {it.obj()}.filter {it.s("isSponsored","value")!="true"}
            val attachments=snaps.take(16).mapNotNull {snap->safe(snap.s("snapUrls","mediaUrl"))?.let {url->Media(url,if(snap.s("snapMediaType")=="1")"video"else "image",safe(snap.s("snapUrls","mediaPreviewUrl","value")),text(snap.s("snapTitle","value")))}}
            val published=snaps.firstNotNullOfOrNull {stamp(it.s("timestampInSec","value"))} ?: return@mapNotNull null
            if(attachments.isEmpty())return@mapNotNull null
            val canonical="https://www.snapchat.com/@$username/spotlight/$id"
            val body=listOf(story.s("storyTitle","value"),story.s("storySubtitle","value")).filter(String::isNotBlank).joinToString("\n").let(::text)
            Post(key(Platform.SNAPCHAT,canonical),Platform.SNAPCHAT,canonical,profile.name,body,publishedAt=published,avatar=profile.avatar,media=attachments,provenance=provenance)
        }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        if(posts.isEmpty()&&!profileOnly)fail(payload,if(selected)"This route did not return the selected public Snapchat post"else "This public Snapchat profile has no readable dated highlights in its snapshot")
        return Page(if(profileOnly)emptyList()else posts,profile=if(selected)null else profile,provenance=provenance,notice="Public dated highlights from the page snapshot; private snaps, comments and further pages are unavailable through this reader.")
    }
}
