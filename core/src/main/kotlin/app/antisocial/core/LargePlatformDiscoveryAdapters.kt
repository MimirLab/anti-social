/* AntiSocial, GPL-3.0-or-later. Independently implemented public document readers.
 * Request shapes and field contracts researched from original projects:
 * https://github.com/facebook/meta-embeds-for-wordpress (GPL-2.0-or-later)
 * https://github.com/AkitsukiNagi/FxThreads (AGPL-3.0)
 * https://github.com/seirenkr/OGInstagram and https://gist.github.com/kentbrew/8744482
 * No third-party application scripts, login sessions, or API credentials are used.
 */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Separate source identities let Darwin's routing learner rank each data surface. */
fun largePlatformDiscoveryAdapter(source: Source, gateway: NetworkGateway, routes: DarwinRoutes): DataAdapter? = when {
    source.platform==Platform.PINTEREST && source.frontend=="pinterest-widgets" -> PinterestWidgetAdapter(source,gateway,routes)
    source.platform==Platform.THREADS && source.frontend=="fxthreads-json" -> ScopedDiscoveryPostAdapter(source,gateway,routes)
    source.platform==Platform.INSTAGRAM && source.frontend=="oginstagram-activity" -> ScopedDiscoveryPostAdapter(source,gateway,routes)
    source.platform==Platform.INSTAGRAM && source.frontend=="anonimogram-api" -> AnonimogramAdapter(source,gateway,routes)
    source.platform==Platform.TIKTOK && source.frontend=="tiktok-creator-embed" -> TikTokCreatorEmbedAdapter(source,gateway,routes)
    source.platform==Platform.TIKTOK && source.frontend=="tikwm" -> ScopedDiscoveryPostAdapter(source,gateway,routes)
    source.platform==Platform.TIKTOK && source.frontend=="tikwm-comments" -> TikWmCommentsAdapter(source,gateway,routes)
    source.platform==Platform.TIKTOK && source.frontend in setOf("tikwm-search","tikwm-photo-search") -> TikWmSearchAdapter(source,gateway,routes)
    source.platform==Platform.SNAPCHAT && source.frontend=="snapchat-embed" -> ScopedDiscoveryPostAdapter(source,gateway,routes)
    source.platform in setOf(Platform.INSTAGRAM,Platform.THREADS,Platform.FACEBOOK) && source.frontend=="meta-oembed" -> ScopedDiscoveryPostAdapter(source,gateway,routes)
    else -> null
}

private data class TikWmCommentCursor(val source: String,val target: String,val token: String)
private class TikWmCommentsAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.COMMENTS)
    override fun accepts(target: Target)=target.platform==Platform.TIKTOK&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&LargePlatformDiscoveryParsing.postCode(target)!=null
    override fun sentinel(capability: Capability)=routes.recognize("https://www.tiktok.com/@tiktok/video/7694797676306615583")!!
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"This route supplies selected-post comments, not account feeds")
    override suspend fun profile(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"This route supplies comments, not account profiles")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This comment route needs a selected public TikTok post")
        val token=LargePlatformDiscoveryParsing.tikWmCommentToken(cursor,target,source)
        val id=requireNotNull(LargePlatformDiscoveryParsing.postCode(target))
        val payload=gateway.text(source.root.trimEnd('/')+"/api/comment/list?url=$id&count=20&cursor=${token ?: "0"}",target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to "application/json"))
        LargePlatformDiscoveryParsing.requireSource(payload,source)
        return LargePlatformDiscoveryParsing.tikWmComments(payload,target,source,token)
    }
}

private class TikTokCreatorEmbedAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind==TargetKind.PROFILE&&LargePlatformDiscoveryParsing.creatorTarget(target)
    override fun sentinel(capability: Capability)=routes.target(Platform.TIKTOK,"tiktok")
    override suspend fun feed(target: Target): Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"Creator embeds do not supply source dates for a chronological feed")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(cursor==null){"Creator embeds do not expose verified pagination"}
        return read(target,false)
    }
    override suspend fun profile(target: Target)=read(target,true)
    private suspend fun read(target: Target,profileOnly: Boolean): Page {
        if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This route reads public TikTok creator profiles")
        val username=target.identity.removePrefix("@")
        val payload=gateway.text(source.root.trimEnd('/')+"/embed/@"+DarwinRoutes.encode(username),target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept-Language" to "en-US"))
        LargePlatformDiscoveryParsing.requireSource(payload,source)
        return LargePlatformDiscoveryParsing.tikTokCreatorEmbed(payload,target,source,profileOnly)
    }
}

private class ScopedDiscoveryPostAdapter(override val source: Source, private val gateway: NetworkGateway, private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.POST,Capability.MEDIA)
    override fun accepts(target: Target)=target.platform==source.platform&&target.kind in setOf(TargetKind.POST,TargetKind.THREAD)&&LargePlatformDiscoveryParsing.postCode(target)!=null
    override fun sentinel(capability: Capability): Target = when(source.platform) {
        Platform.INSTAGRAM -> routes.recognize("https://www.instagram.com/p/fA9uwTtkSN/")!!
        Platform.THREADS -> routes.recognize("https://www.threads.com/@threads/post/DWjTI0cgH5O/")!!
        Platform.TIKTOK -> routes.recognize("https://www.tiktok.com/@tiktok/video/7694797676306615583")!!
        Platform.SNAPCHAT -> routes.recognize("https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYYWtidGhudGZpAX1TKn0JAX1TKnXJAAAAAA")!!
        else -> routes.recognize("https://www.facebook.com/kevinloveofficial/posts/pfbid0nWhZeiMVjzLHVjzR6QngXeVug8Nw4YxncbbZrquMu72r3HM8a73k55keRWiaWNLTl/")!!
    }
    override suspend fun feed(target: Target): Page = throw SourceFailure(HealthStatus.UNSUPPORTED,"This public embed route reads individual posts, not timelines")
    override suspend fun profile(target: Target): Page = throw SourceFailure(HealthStatus.UNSUPPORTED,"This public embed route reads individual posts, not profiles")
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(cursor==null){"This public snapshot has no verified continuation"}
        if(target.platform!=source.platform || target.kind !in setOf(TargetKind.POST,TargetKind.THREAD))throw SourceFailure(HealthStatus.UNSUPPORTED,"This public route reads individual posts")
        val code=LargePlatformDiscoveryParsing.postCode(target) ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"The selected link has no supported public post identifier")
        val root=source.root.trimEnd('/')
        val url=when(source.frontend) {
            "fxthreads-json" -> "$root/api/post/${DarwinRoutes.encode(code)}"
            "oginstagram-activity" -> "$root/p/${DarwinRoutes.encode(code)}"
            "tikwm" -> "$root/api/?url="+DarwinRoutes.encode(target.canonicalUrl)
            "snapchat-embed" -> "$root/spotlight/"+DarwinRoutes.encode(code)+"/embed"
            "meta-oembed" -> root+when(source.platform) {
                Platform.THREADS -> "/oembed"
                Platform.INSTAGRAM -> "/v25.0/instagram_oembed"
                else -> if(target.canonicalUrl.toHttpUrlOrNull()?.pathSegments?.firstOrNull() in setOf("reel","watch","videos"))"/v25.0/oembed_video" else "/v25.0/oembed_post"
            }+"?url="+DarwinRoutes.encode(target.canonicalUrl)+"&omitscript=true"
            else -> throw SourceFailure(HealthStatus.UNSUPPORTED,"Unknown public post format")
        }
        val payload=gateway.text(url,target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to if(source.frontend=="oginstagram-activity")"application/activity+json" else "application/json","Accept-Language" to "en-US"))
        LargePlatformDiscoveryParsing.requireSource(payload,source)
        return when(source.frontend) {
            "fxthreads-json" -> LargePlatformDiscoveryParsing.fxThreads(payload,target,source)
            "oginstagram-activity" -> {
                val selected=if(payload.text.trimStart().startsWith('{'))payload else {
                    val linked=LargePlatformDiscoveryParsing.instagramActivityLink(payload,target,source)
                    gateway.text(linked,target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to "application/activity+json")).also {LargePlatformDiscoveryParsing.requireSource(it,source)}
                }
                LargePlatformDiscoveryParsing.instagramActivity(selected,target,source)
            }
            "tikwm" -> LargePlatformDiscoveryParsing.tikWm(payload,target,source)
            "snapchat-embed" -> AdditionalSocialParsing.snapchat(payload,target,source)
            else -> LargePlatformDiscoveryParsing.metaOembed(payload,target,source)
        }
    }
}

private class PinterestWidgetAdapter(override val source: Source, private val gateway: NetworkGateway, private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE,Capability.POST,Capability.MEDIA)
    override fun accepts(target: Target): Boolean {
        if(target.platform!=source.platform||!LargePlatformDiscoveryParsing.originalTarget(target))return false
        val path=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        return when(target.kind) {
            TargetKind.POST,TargetKind.THREAD -> path.size==2&&path[0]=="pin"&&path[1].matches(Regex("[0-9]{1,40}"))
            TargetKind.PROFILE,TargetKind.COMMUNITY -> path.size in 1..2&&path.all {it.matches(Regex("[A-Za-z0-9_-]{1,100}"))}
            else -> false
        }
    }
    override fun sentinel(capability: Capability)=routes.target(Platform.PINTEREST,"nasa")
    override suspend fun feed(target: Target)=fetch(target)
    override suspend fun profile(target: Target)=read(target,true)
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(cursor==null){"Pinterest widgets do not expose verified pagination"}
        return read(target,false)
    }
    private suspend fun read(target: Target,profileOnly: Boolean): Page {
        if(!accepts(target))throw SourceFailure(HealthStatus.UNSUPPORTED,"This is a Pinterest widget route for a public profile, board or pin")
        val segments=target.canonicalUrl.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        val endpoint=when {
            target.kind in setOf(TargetKind.POST,TargetKind.THREAD) && segments.size==2 && segments[0]=="pin" && segments[1].matches(Regex("[0-9]{1,40}")) -> "pins/info/?pin_ids=${segments[1]}"
            target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY) && segments.size==1 && segments[0].matches(Regex("[A-Za-z0-9_-]{1,64}")) -> "users/${segments[0]}/pins/"
            target.kind in setOf(TargetKind.PROFILE,TargetKind.COMMUNITY) && segments.size==2 && segments.all {it.matches(Regex("[A-Za-z0-9_-]{1,100}"))} -> "boards/${segments[0]}/${segments[1]}/pins/"
            else -> throw SourceFailure(HealthStatus.UNSUPPORTED,"This widget reads public pins, profiles and boards")
        }
        val payload=gateway.text(source.root.trimEnd('/')+"/v3/pidgets/"+endpoint,target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to "application/json"))
        LargePlatformDiscoveryParsing.requireSource(payload,source)
        return LargePlatformDiscoveryParsing.pinterestWidget(payload,target,source,profileOnly)
    }
}

/** Bounded, schema-specific extraction: no arbitrary recursive object harvesting. */
object LargePlatformDiscoveryParsing {
    private fun JsonElement?.obj()=this?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonElement?.value()=this?.takeIf {it.isJsonPrimitive}?.let {runCatching {it.asString}.getOrDefault("")}.orEmpty()
    private fun JsonObject.s(key: String)=get(key).value()
    private fun JsonObject.o(key: String)=get(key).obj()
    private fun JsonObject.a(key: String)=get(key)?.takeIf {it.isJsonArray}?.asJsonArray?.take(100).orEmpty()
    private fun safe(value: String)=value.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    private fun plain(value: String)=Jsoup.parseBodyFragment(value.take(100_000)).text().take(100_000)
    private fun date(value: String): Long? = value.toLongOrNull()?.takeIf {it>0&&it<=253402300799999L}?.let {if(it<10000000000L)it*1000 else it}
        ?: runCatching {Instant.parse(value).toEpochMilli()}.getOrNull()
        ?: runCatching {ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()}.getOrNull()
    private fun json(payload: Payload): JsonObject {
        if(payload.text.length>1024*1024)throw SourceFailure(HealthStatus.UNAVAILABLE,"This source returned an oversized public snapshot")
        return runCatching {JsonParser.parseString(payload.text).asJsonObject}.getOrElse {fail(payload,"This source did not return its public JSON format")}
    }
    private fun fail(payload: Payload,message: String): Nothing {
        val sample=payload.text.take(32000).lowercase()
        throw SourceFailure(when {
            sample.contains("captcha")||sample.contains("challenge-platform")||sample.contains("checkpoint") -> HealthStatus.CHALLENGE
            sample.contains("login_required")||sample.contains("access token")||sample.contains("sign in to view")||payload.finalUrl.contains("/login") -> HealthStatus.LOGIN_REQUIRED
            else -> HealthStatus.EMPTY
        },message)
    }
    internal fun requireSource(payload: Payload,source: Source) {
        if(!runCatching {DarwinRoutes.matches(URI(payload.finalUrl),source.root)}.getOrDefault(false))throw SourceFailure(HealthStatus.UNAVAILABLE,"The public route redirected outside its selected source")
    }
    private fun parts(url: String)=url.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
    internal fun originalTarget(target: Target): Boolean {
        val url=target.canonicalUrl.toHttpUrlOrNull() ?: return false
        if(!url.isHttps||url.username.isNotEmpty()||url.password.isNotEmpty()||url.port!=443)return false
        val domains=if(target.platform==Platform.THREADS)setOf("threads.com","threads.net")else setOf(target.platform.searchDomain)
        return domains.any {url.host==it||url.host.endsWith(".$it")}
    }
    internal fun creatorTarget(target: Target): Boolean {
        val username=target.identity.removePrefix("@")
        return originalTarget(target)&&username.matches(Regex("[A-Za-z0-9_.]{1,64}"))&&parts(target.canonicalUrl)==listOf("@$username")
    }
    internal fun postCode(target: Target): String? {
        if(!originalTarget(target))return null
        val path=parts(target.canonicalUrl)
        return when(target.platform) {
            Platform.INSTAGRAM -> path.indexOfFirst {it in setOf("p","reel","reels")}.takeIf {it>=0}?.let {path.getOrNull(it+1)}?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,64}"))}
            Platform.THREADS -> if(path.firstOrNull()=="t")path.getOrNull(1) else path.indexOf("post").takeIf {it>=0}?.let {path.getOrNull(it+1)}
            Platform.TIKTOK -> path.indexOfFirst {it in setOf("video","photo")}.takeIf {it>=0}?.let {path.getOrNull(it+1)}?.takeIf {it.matches(Regex("[0-9]{1,40}"))}
            Platform.SNAPCHAT -> path.indexOf("spotlight").takeIf {it>=0}?.let {path.getOrNull(it+1)}
            Platform.FACEBOOK -> path.indexOfFirst {it in setOf("posts","videos","reel")}.takeIf {it>=0}?.let {path.getOrNull(it+1)}
                ?: target.canonicalUrl.toHttpUrlOrNull()?.let {it.queryParameter("story_fbid") ?: it.queryParameter("v")}
            else -> null
        }?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,256}"))}
    }
    private fun samePost(candidate: String,target: Target,source: Source?=null): Boolean {
        val wanted=postCode(target) ?: return false
        val url=candidate.toHttpUrlOrNull() ?: return false
        val host=target.platform.searchDomain
        val allowed=url.host==host||url.host.endsWith(".$host") || target.platform==Platform.THREADS&&(url.host=="threads.net"||url.host.endsWith(".threads.net")) || source!=null&&runCatching {DarwinRoutes.matches(URI(candidate),source.root)}.getOrDefault(false)
        // Alternate ActivityStreams URLs retain the original post path but use
        // the selected reader host. Canonicalize only that already-checked host.
        val normalized=if(source!=null&&runCatching {DarwinRoutes.matches(URI(candidate),source.root)}.getOrDefault(false))url.newBuilder().scheme("https").host(target.platform.searchDomain).port(443).build().toString()else candidate
        return allowed && postCode(target.copy(canonicalUrl=normalized))==wanted
    }
    private fun owner(target: Target)=parts(target.canonicalUrl).firstOrNull()?.takeIf {it.startsWith('@')}?.removePrefix("@")

    fun fxThreads(payload: Payload,target: Target,source: Source): Page {
        val node=json(payload)
        if(node.s("id")!=postCode(target))fail(payload,"The embed service returned a different Threads post")
        val author=node.o("author") ?: fail(payload,"The embed did not identify the post author")
        val username=author.s("username").takeIf {it.matches(Regex("[A-Za-z0-9_.]{1,64}"))} ?: fail(payload,"The embed did not identify the post author")
        if(owner(target)?.equals(username,true)==false)fail(payload,"The embed returned a different Threads author")
        if(node.s("url").isNotBlank()&&!samePost(node.s("url"),target))fail(payload,"The embed URL identifies a different post")
        val body=plain(node.s("content"))
        val media=node.a("medias").take(16).mapNotNull {it.obj()}.mapNotNull {m->safe(m.s("url"))?.let {Media(it,if(m.s("is_video")=="true")"video"else "image",safe(m.s("cover_image")))}}
        if(body.isBlank()&&media.isEmpty())fail(payload,"This embed returned no readable post content")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val post=Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,plain(author.s("full_name").ifBlank {username}),body,publishedAt=date(node.s("timestamp")),avatar=safe(author.s("avatar_url")),media=media,score=node.o("stats")?.s("likes")?.toLongOrNull(),replyCount=node.o("stats")?.s("comments")?.toLongOrNull(),provenance=provenance)
        return Page(listOf(post),provenance=provenance,notice="Public embed snapshot; replies and further pages are unavailable on this route.")
    }

    fun instagramActivity(payload: Payload,target: Target,source: Source): Page {
        val node=json(payload)
        if(node.s("type")!="Note" || !samePost(node.s("url"),target,source))fail(payload,"This activity record does not identify the selected Instagram post")
        if((node.a("to")+node.a("cc")).none {it.value()=="https://www.w3.org/ns/activitystreams#Public"})fail(payload,"This activity record does not declare a public audience")
        val actor=node.s("attributedTo").toHttpUrlOrNull() ?: fail(payload,"The activity record did not identify its author")
        if(!runCatching {DarwinRoutes.matches(URI(actor.toString()),source.root)}.getOrDefault(false))fail(payload,"The activity record points to an unrelated author")
        val path=actor.pathSegments.filter(String::isNotBlank)
        val username=path.getOrNull(1)?.takeIf {path.size==2&&path[0]=="users"&&it.matches(Regex("[A-Za-z0-9_.]{1,64}"))} ?: fail(payload,"The activity record did not identify its author")
        val selectedOwner=parts(target.canonicalUrl).takeIf {it.size>2&&it[0] !in setOf("p","reel","reels")}?.firstOrNull()
        if(selectedOwner?.equals(username,true)==false)fail(payload,"The activity record identifies a different Instagram author")
        val fragment=Jsoup.parseBodyFragment(node.s("content").take(100_000))
        // OGInstagram emits a separate first paragraph for display statistics.
        fragment.body().children().firstOrNull()?.takeIf {it.tagName()=="p"&&it.childrenSize()==1&&it.child(0).tagName()=="b"}?.remove()
        val body=fragment.text().take(100_000)
        val media=node.a("attachment").take(16).mapNotNull {it.obj()}.mapNotNull {m->
            val mime=m.s("mediaType");if(!mime.startsWith("image/")&&!mime.startsWith("video/"))null else safe(m.s("url"))?.let {Media(it,if(mime.startsWith("video/"))"video"else "image",mimeType=mime)}
        }
        if(body.isBlank()&&media.isEmpty())fail(payload,"The activity record contains no readable post")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        return Page(listOf(Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,username,body,publishedAt=date(node.s("published")),media=media,provenance=provenance)),provenance=provenance,notice="Public Instagram embed snapshot; the source does not expose comment threads or continuation on this route.")
    }

    internal fun instagramActivityLink(payload: Payload,target: Target,source: Source): String {
        if(payload.text.length>1024*1024)throw SourceFailure(HealthStatus.UNAVAILABLE,"The embed document is too large")
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        val declared=document.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        if(!samePost(declared,target))fail(payload,"The embed document identifies another Instagram post")
        return document.select("link[rel=alternate][type=application/activity+json][href]").take(8).mapNotNull {safe(it.attr("href"))}.firstOrNull {link->
            val path=parts(link)
            runCatching {DarwinRoutes.matches(URI(link),source.root)}.getOrDefault(false)&&path.size==4&&path[0]=="users"&&path[1].matches(Regex("[A-Za-z0-9_.]{1,64}"))&&path[2]=="statuses"&&path[3].matches(Regex("[0-9]{1,128}"))
        } ?: fail(payload,"This embed did not advertise a local structured post record")
    }

    fun tikWm(payload: Payload,target: Target,source: Source): Page {
        val root=json(payload)
        if(Regex("rate.?limit|too many|limit.*request|requests?.*(?:second|minute)",RegexOption.IGNORE_CASE).containsMatchIn(root.s("msg")))throw SourceFailure(HealthStatus.RATE_LIMITED,"TikWM asked this reader to slow down",retryAfterMs=60_000)
        if(root.s("code")!="0")fail(payload,"This source did not return the selected public TikTok post")
        val data=root.o("data") ?: fail(payload,"The selected public TikTok post was not available")
        val id=data.s("id").ifBlank {data.s("video_id")}
        if(id!=postCode(target))fail(payload,"This source returned a different TikTok post")
        val author=data.o("author") ?: fail(payload,"This source did not identify the TikTok author")
        val username=author.s("unique_id").takeIf {it.matches(Regex("[A-Za-z0-9_.]{1,64}"))} ?: fail(payload,"This source did not identify the TikTok author")
        if(!username.equals(owner(target),true))fail(payload,"This source returned a different TikTok author")
        val images=data.a("images").take(32).mapNotNull {safe(it.value())}.map {Media(it)}
        val cover=safe(data.s("origin_cover").ifBlank {data.s("cover")})
        val media=images.ifEmpty {listOfNotNull(safe(data.s("wmplay").ifBlank {data.s("play")})?.let {Media(it,"video",cover)})}
        val body=plain(data.s("title"))
        if(body.isBlank()&&media.isEmpty())fail(payload,"This source returned no readable TikTok post content")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val post=Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,plain(author.s("nickname").ifBlank {username}),body,publishedAt=date(data.s("create_time")),avatar=safe(author.s("avatar")),media=media,replyCount=data.s("comment_count").toLongOrNull(),score=data.s("digg_count").toLongOrNull(),provenance=provenance)
        return Page(listOf(post),provenance=provenance,notice=null)
    }

    internal fun tikWmCommentToken(raw: String?,target: Target,source: Source): String? {
        if(raw==null)return null
        require(raw.length<=4096&&raw.startsWith("tikwm-comments:")){"Refresh this comment stream to continue"}
        val value=runCatching {Gson().fromJson(String(Base64.getUrlDecoder().decode(raw.removePrefix("tikwm-comments:")),Charsets.UTF_8),TikWmCommentCursor::class.java)}.getOrNull()
        require(runCatching {value!=null&&value.source==source.key&&value.target==target.canonicalUrl&&value.token.matches(Regex("[0-9]{1,20}"))}.getOrDefault(false)){"Comment pagination changed source or selected post"}
        return value!!.token
    }

    /** The endpoint returns its own ranking; Newest can sort these dated entries locally. */
    fun tikWmComments(payload: Payload,target: Target,source: Source,previousToken: String?=null): Page {
        val root=json(payload)
        if(Regex("rate.?limit|too many|limit.*request|requests?.*(?:second|minute)",RegexOption.IGNORE_CASE).containsMatchIn(root.s("msg")))throw SourceFailure(HealthStatus.RATE_LIMITED,"TikWM asked this reader to slow down",retryAfterMs=60_000)
        if(root.s("code")!="0")fail(payload,"This source did not return public comments for the selected post")
        val id=postCode(target)?.takeIf {target.platform==Platform.TIKTOK} ?: throw SourceFailure(HealthStatus.UNSUPPORTED,"Invalid public TikTok post identity")
        val data=root.o("data") ?: fail(payload,"This source did not supply its public comment data")
        val nodes=data.a("comments").take(50).mapNotNull {it.obj()}
        if(nodes.any {it.s("video_id")!=id})fail(payload,"This comment page belongs to another TikTok post")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val original=target.canonicalUrl.toHttpUrlOrNull()!!.newBuilder().removeAllQueryParameters("comment_id").build()
        val parent=ContentIdentity.key(Platform.TIKTOK,original.toString())
        val comments=nodes.mapNotNull {node->
            val commentId=node.s("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            if(node.s("status")!="1")return@mapNotNull null
            val user=node.o("user") ?: return@mapNotNull null
            val username=user.s("unique_id").takeIf {it.matches(Regex("[A-Za-z0-9_.]{1,64}"))} ?: return@mapNotNull null
            val body=plain(node.s("text")).takeIf {it.isNotBlank()} ?: return@mapNotNull null
            val published=date(node.s("create_time")) ?: return@mapNotNull null
            val canonical=original.newBuilder().setQueryParameter("comment_id",commentId).build().toString()
            Post(ContentIdentity.key(Platform.TIKTOK,canonical),Platform.TIKTOK,canonical,plain(user.s("nickname").ifBlank {username}),body,publishedAt=published,avatar=safe(user.s("avatar")),score=node.s("digg_count").toLongOrNull(),replyCount=node.s("reply_total").toLongOrNull(),parentId=parent,provenance=provenance)
        }.distinctBy {it.canonicalUrl}
        if(comments.isEmpty())fail(payload,"This source returned no readable public comments for the selected post")
        val next=data.s("cursor").takeIf {data.s("hasMore")=="true"&&it.matches(Regex("[0-9]{1,20}"))&&it!=(previousToken ?: "0")}?.let {
            "tikwm-comments:"+Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(TikWmCommentCursor(source.key,target.canonicalUrl,it)).toByteArray(Charsets.UTF_8))
        }
        return Page(comments,next,provenance=provenance,notice="Public comments in TikWM's supplied ranking. Newest sorts the available entries by their source dates. Earlier or nested replies may be missing; this reader does not expand reply threads.")
    }

    fun metaOembed(payload: Payload,target: Target,source: Source): Page {
        val node=json(payload)
        if(node.o("error")!=null)fail(payload,"The public embed endpoint did not make this post available")
        val fragment=Jsoup.parseBodyFragment(node.s("html").take(512_000))
        // Validate the actual embed identity before reading any human text. Never
        // execute its scripts or follow its iframe/third-party image requests here.
        val embeds=fragment.select("blockquote[cite],div[data-href],blockquote[data-instgrm-permalink],iframe[src]").take(16)
        val selected=embeds.firstOrNull {element->
            val candidates=listOf(element.attr("cite"),element.attr("data-href"),element.attr("data-instgrm-permalink"),element.attr("src"))
            candidates.any {candidate->samePost(candidate,target)||candidate.toHttpUrlOrNull()?.queryParameter("href")?.let {samePost(it,target)}==true}
        } ?: fail(payload,"The embed did not identify the selected public post")
        selected.select("script,style,iframe").remove()
        val paragraphs=selected.select("p").take(100).map {it.text().trim()}.filter(String::isNotBlank)
        val body=paragraphs.joinToString("\n\n").take(100_000)
        val image=safe(node.s("thumbnail_url"))
        if(body.isBlank()) {
            // An embed iframe or account title is a useful preview, never working
            // full content. A thumbnail by itself cannot pass a POST health check.
            if(image==null)fail(payload,"This public embed contains no readable post text")
        }
        val author=plain(node.s("author_name"))
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val post=Post(ContentIdentity.key(target.platform,target.canonicalUrl),target.platform,target.canonicalUrl,author,body,media=listOfNotNull(image?.let {Media(it)}),provenance=provenance,preview=true)
        return Page(listOf(post),provenance=provenance,notice="Official public embed preview. Its text or media may be incomplete; this is not a complete post or comment thread.")
    }

    fun pinterestWidget(payload: Payload,target: Target,source: Source,profileOnly: Boolean=false): Page {
        val root=json(payload)
        if(root.s("status")!="success")fail(payload,"Pinterest did not return a public widget snapshot")
        val path=parts(target.canonicalUrl)
        val selected=target.kind in setOf(TargetKind.POST,TargetKind.THREAD)
        val rawData=root.get("data")
        val data=rawData.obj()
        val pins=if(selected&&rawData?.isJsonArray==true)rawData.asJsonArray.take(100) else data?.a("pins").orEmpty()
        if(data==null&&!(selected&&rawData?.isJsonArray==true))fail(payload,"Pinterest did not return widget data")
        val user=data?.o("user")
        val board=data?.o("board")
        val requestedUser=path.firstOrNull().orEmpty()
        if(!selected) {
            fun userName(node: JsonObject?): String {
                val profile=node?.s("profile_url")?.toHttpUrlOrNull()
                val profileName=profile?.takeIf {(it.host=="pinterest.com"||it.host.endsWith(".pinterest.com"))&&it.pathSegments.filter(String::isNotBlank).size==1}?.pathSegments?.firstOrNull(String::isNotBlank)
                return node?.s("username").orEmpty().ifBlank {profileName.orEmpty()}
            }
            val actualUser=userName(user).ifBlank {userName(board?.o("owner"))}
            val boardUrl=board?.s("url")?.let {if(it.startsWith('/'))"https://www.pinterest.com$it"else it}
            val sameBoard=path.size==2&&boardUrl?.toHttpUrlOrNull()?.let {u->(u.host=="pinterest.com"||u.host.endsWith(".pinterest.com"))&&u.pathSegments.filter(String::isNotBlank).map(String::lowercase)==path.map(String::lowercase)}==true
            if(!actualUser.equals(requestedUser,true)&&!sameBoard)fail(payload,"The widget did not identify the selected Pinterest profile or board")
            if(path.size==2&&!sameBoard)fail(payload,"The widget did not identify the selected Pinterest board")
        }
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val posts=pins.take(60).mapNotNull {it.obj()}.mapNotNull {pin->
            val id=pin.s("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            if(selected&&id!=path.getOrNull(1))return@mapNotNull null
            val pinner=pin.o("pinner")
            val images=pin.o("images")
            val image=listOf("orig","originals","736x","564x","474x","237x").firstNotNullOfOrNull {images?.o(it)?.s("url")?.let(::safe)}
                ?: images?.entrySet()?.take(12)?.mapNotNull {it.value.obj()?.s("url")?.let(::safe)}?.firstOrNull()
            val videos=pin.o("videos")?.o("video_list")
            val video=listOf("V_720P","V_EXP7","V_HLSV4").firstNotNullOfOrNull {videos?.o(it)?.s("url")?.let(::safe)}
            val media=listOfNotNull(video?.let {Media(it,"video",image)} ?: image?.let {Media(it)})
            val body=plain(pin.s("description"));val title=plain(pin.s("title"))
            if(body.isBlank()&&title.isBlank()&&media.isEmpty())return@mapNotNull null
            val canonical="https://www.pinterest.com/pin/$id/"
            Post(ContentIdentity.key(Platform.PINTEREST,canonical),Platform.PINTEREST,canonical,plain(pinner?.s("full_name").orEmpty().ifBlank {pinner?.s("username").orEmpty()}),body,title,date(pin.s("created_at")),avatar=safe(pinner?.s("image_small_url").orEmpty()),media=media,links=listOfNotNull(safe(pin.s("link"))),provenance=provenance)
        }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        val profile=if(!selected&&user!=null)Profile(target,plain(user.s("full_name").ifBlank {requestedUser}),plain(user.s("about")),safe(user.s("image_large_url").ifBlank {user.s("image_small_url")}),user.s("follower_count").toLongOrNull())else null
        if(posts.isEmpty()&&!(profileOnly&&profile!=null))fail(payload,"The widget returned no readable public pins for the selected target")
        return Page(if(profileOnly)emptyList()else posts,profile=profile,provenance=provenance,notice="Public Pinterest widget snapshot; comments and pagination are not available. Pins without source dates cannot advance a chronological subscription.")
    }

    fun tikTokCreatorEmbed(payload: Payload,target: Target,source: Source,profileOnly: Boolean=false): Page {
        if(payload.text.length>1024*1024)throw SourceFailure(HealthStatus.UNAVAILABLE,"The creator embed is too large")
        val username=target.identity.removePrefix("@")
        if(target.kind!=TargetKind.PROFILE||!username.matches(Regex("[A-Za-z0-9_.]{1,64}")))throw SourceFailure(HealthStatus.UNSUPPORTED,"This is a public creator-profile route")
        val document=Jsoup.parse(payload.text,payload.finalUrl)
        val script=document.selectFirst("script#__FRONTITY_CONNECT_STATE__[type=application/json]")?.data().orEmpty()
        val state=runCatching {JsonParser.parseString(script).asJsonObject}.getOrNull() ?: fail(payload,"This embed did not supply its public creator snapshot")
        val data=state.o("source")?.o("data")?.o("/embed/@$username") ?: fail(payload,"The embed does not identify the selected creator")
        val user=data.o("userInfo") ?: fail(payload,"This embed did not return a public creator")
        if(!user.s("uniqueId").equals(username,true))fail(payload,"The embed returned another creator")
        if(user.s("privateAccount")=="true")throw SourceFailure(HealthStatus.LOGIN_REQUIRED,"This creator is not available as a public embed")
        val provenance=Provenance(source,System.currentTimeMillis(),payload.finalUrl)
        val profile=Profile(target,plain(user.s("nickname").ifBlank {username}),plain(user.s("signature")),safe(user.s("avatarThumbUrl")),user.s("followerCount").toLongOrNull())
        val posts=data.a("videoList").take(10).mapNotNull {it.obj()}.mapNotNull {item->
            if(item.s("privateItem")=="true"||!item.s("authorUniqueId").equals(username,true))return@mapNotNull null
            val id=item.s("id").takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            val media=safe(item.s("playAddr"))?.let {Media(it,"video",safe(item.s("coverUrl")))} ?: return@mapNotNull null
            val canonical="https://www.tiktok.com/@$username/video/$id"
            Post(ContentIdentity.key(Platform.TIKTOK,canonical),Platform.TIKTOK,canonical,profile.name,plain(item.s("desc")),publishedAt=date(item.s("createTime")),avatar=profile.avatar,media=listOf(media),provenance=provenance)
        }.distinctBy {it.id}.sortedByDescending {it.publishedAt}
        if(posts.isEmpty()&&!profileOnly)fail(payload,"The creator embed returned no readable public posts")
        return Page(if(profileOnly)emptyList()else posts,profile=profile,provenance=provenance,notice="Public creator embed snapshot, limited to the supplied recent posts. This representation does not supply reliable publication dates or comment threads.")
    }
}
