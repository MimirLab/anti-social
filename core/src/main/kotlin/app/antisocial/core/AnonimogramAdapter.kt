/* anti social, GPL-3.0-or-later. Independently implemented public JSON reader.
 * Protocol observed in https://anonimogram.com/ and its published browser client.
 * No site scripts, login tokens, cookies or quota-identity rotation. */
package app.antisocial.core

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader
import java.util.Base64

internal class AnonimogramAdapter(override val source:Source,private val gateway:NetworkGateway,private val routes:DarwinRoutes):DataAdapter {
    override val capabilities=setOf(Capability.PROFILE,Capability.TIMELINE)
    override fun accepts(target:Target)=AnonimogramData.username(target,routes)!=null
    override fun sentinel(capability:Capability)=routes.target(Platform.INSTAGRAM,"nasa")
    override suspend fun feed(target:Target):Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"This public snapshot has no verified chronological feed")
    private suspend fun read(target:Target,kind:String,cursor:String?=null):Payload {
        val name=requireNotNull(AnonimogramData.username(target,routes)){"Open a public Instagram profile"}
        val url=(source.root.trimEnd('/')+"/api/$kind/$name").toHttpUrlOrNull()!!.newBuilder()
            .apply {cursor?.let {addQueryParameter("cursor",it)}}.build()
        val payload=gateway.text(url.toString(),target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to "application/json"),allowedOrigins=setOf(source.root))
        if(payload.finalUrl.toHttpUrlOrNull()!=url)throw SourceFailure(HealthStatus.EMPTY,"The public reader changed the requested profile")
        return payload
    }
    override suspend fun profile(target:Target)=AnonimogramData.profile(read(target,"profile"),target,source,routes)
    override suspend fun fetch(target:Target,cursor:String?):Page {
        val token=cursor?.let {AnonimogramData.cursor(it,target,source)}
        return AnonimogramData.posts(read(target,"posts",token),target,source,routes,token)
    }
}

/** Nullable source fields stay unknown. These records are on-demand snapshots,
 * not original-post, full-video, complete-gallery or feed-refresh proof. */
internal object AnonimogramData {
    private const val PREFIX="anonimogram:"
    private val handle=Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,29}")
    fun username(target:Target,routes:DarwinRoutes):String? {
        if(target.platform!=Platform.INSTAGRAM||target.kind!=TargetKind.PROFILE)return null
        val actual=routes.recognize(target.canonicalUrl,Platform.INSTAGRAM)?.takeIf {it.platform==Platform.INSTAGRAM&&it.kind==TargetKind.PROFILE} ?: return null
        val name=actual.identity.removePrefix("@").trim('/')
        return name.takeIf {handle.matches(it)&&it.equals(target.identity.removePrefix("@").trim('/'),true)}
    }
    private fun JsonObject.text(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString.orEmpty()
    private fun JsonObject.obj(key:String)=get(key)?.takeIf {it.isJsonObject}?.asJsonObject
    private fun JsonObject.flag(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean
    private fun JsonObject.count(key:String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asString?.toLongOrNull()?.takeIf {it>=0}
    private fun safe(raw:String)=raw.takeIf {it.length<=8192}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    private fun json(payload:Payload):JsonObject=try {
        require(payload.text.length<=1024*1024)
        JsonReader(StringReader(payload.text)).use {reader->
            reader.strictness=Strictness.STRICT;reader.nestingLimit=24
            val value=JsonParser.parseReader(reader)
            require(reader.peek()==JsonToken.END_DOCUMENT&&value.isJsonObject)
            value.asJsonObject.also {if(it.has("error"))throw SourceFailure(HealthStatus.UNAVAILABLE,"The public reader could not supply this snapshot")}
        }
    }catch(e:SourceFailure){throw e}catch(_:Exception){throw SourceFailure(HealthStatus.EMPTY,"The public reader returned no usable JSON snapshot")}
    private fun stamp(payload:Payload,source:Source,now:Long)=Provenance(source,now,payload.finalUrl)
    fun profile(payload:Payload,target:Target,source:Source,routes:DarwinRoutes,now:Long=System.currentTimeMillis()):Page {
        val expected=requireNotNull(username(target,routes));val data=json(payload)
        if(!data.text("username").equals(expected,true))throw SourceFailure(HealthStatus.EMPTY,"The public reader returned a different profile")
        val name=data.text("fullName").trim().take(512)
        val bio=data.text("biography").take(16_384);val avatar=safe(data.text("avatarUrl"))
        if(name.isBlank()&&bio.isBlank()&&avatar==null)throw SourceFailure(HealthStatus.EMPTY,"The public reader supplied only a username")
        val stats=data.obj("stats")
        val profile=Profile(target,name.ifBlank {expected},bio,avatar,stats?.count("followers"),
            links=listOfNotNull(safe(data.text("externalUrl"))),displayNameSupplied=name.isNotBlank(),
            persistentId=data.get("id")?.takeIf {it.isJsonPrimitive}?.asString?.takeIf {it.matches(Regex("[1-9][0-9]{0,39}"))},following=stats?.count("following"))
        return Page(emptyList(),profile=profile,provenance=stamp(payload,source,now),
            notice=if(data.flag("isPrivate")==true)"Only public profile details are available for this private account."else null)
    }
    fun posts(payload:Payload,target:Target,source:Source,routes:DarwinRoutes,previous:String?=null,now:Long=System.currentTimeMillis()):Page {
        val name=requireNotNull(username(target,routes));val data=json(payload);val provenance=stamp(payload,source,now)
        val items=data["items"]?.takeIf {it.isJsonArray}?.asJsonArray ?: throw SourceFailure(HealthStatus.EMPTY,"The public reader returned no caption snapshot")
        val posts=items.take(24).mapNotNull {item->
            val row=item.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val code=row.text("shortcode").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,64}"))} ?: return@mapNotNull null
            row.text("username").takeIf(String::isNotBlank)?.let {if(!it.equals(name,true))return@mapNotNull null}
            val url="https://www.instagram.com/${if(row.flag("isReel")==true)"reel"else "p"}/$code/"
            val media=row["media"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(16)?.mapNotNull media@{value->
                val asset=value.takeIf {it.isJsonObject}?.asJsonObject ?: return@media null
                val link=safe(asset.text("url")) ?: return@media null
                val thumbnail=safe(asset.text("thumbnailUrl")) ?: safe(row.text("thumbnailUrl"))
                val path=link.toHttpUrlOrNull()!!.encodedPath.lowercase()
                val image=Regex("\\.(?:jpe?g|png|webp|avif|gif)$").containsMatchIn(path)
                // The live source labels a JPEG poster as video, with its URL
                // equal to thumbnailUrl. Do not manufacture a playable stream.
                val video=asset.text("type")=="video"&&!image&&link!=thumbnail
                if(!video&&!image&&asset.text("type")!="image"&&link!=thumbnail)return@media null
                Media(link,if(video)"video"else "image",if(video)thumbnail else null)
            }.orEmpty().distinctBy {it.url}.ifEmpty {listOfNotNull(safe(row.text("thumbnailUrl"))?.let {Media(it)})}
            val body=row.text("caption").take(100_000)
            if(body.isBlank()&&media.isEmpty())return@mapNotNull null
            val rawTime=row["timestamp"]?.takeIf {it.isJsonPrimitive}?.asString
            val time=rawTime?.takeIf {!it.trimStart().startsWith('-')}?.let(Extraction::timestamp)?.takeIf {it in 1_262_304_000_000L..now+300_000}
            Post("post:$code",Platform.INSTAGRAM,url,name,body,publishedAt=time,media=media,replyCount=row.count("commentsCount"),score=row.count("likesCount"),provenance=provenance,preview=true,
                extractionCoverage=ExtractionCoverage(text=if(body.isNotBlank())ExtractionStatus.PRESENT else ExtractionStatus.UNKNOWN,attachments=if(media.isNotEmpty())ExtractionStatus.PARTIAL else ExtractionStatus.UNKNOWN))
        }.distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}
        if(posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"The public reader returned no readable captions or preview media")
        val next=data.text("nextCursor").takeIf {data.flag("hasNextPage")==true&&it.isNotBlank()&&it!=previous&&it.length<=2048&&!it.any(Char::isISOControl)}
        val token=next?.let {value->PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(JsonObject().apply {
            addProperty("source",source.key);addProperty("target",target.canonicalUrl);addProperty("cursor",value)
        }.toString().toByteArray(Charsets.UTF_8))}
        return Page(posts,next=token,provenance=provenance,limitedTimeline=true,
            notice="Public preview: captions and images; videos and galleries may be incomplete.",
            continuationIssue=if(data.flag("hasNextPage")==true&&next==null)"This source did not supply a usable continuation."else null)
    }
    fun cursor(raw:String,target:Target,source:Source):String {
        require(raw.length<=8192&&raw.startsWith(PREFIX)){"Refresh this profile to continue"}
        val value=runCatching {json(Payload(String(Base64.getUrlDecoder().decode(raw.removePrefix(PREFIX)),Charsets.UTF_8),"","application/json",200))}.getOrNull()
        val token=value?.text("cursor")
        require(value?.keySet()==setOf("source","target","cursor")&&value?.text("source")==source.key&&value?.text("target")==target.canonicalUrl&&token!=null&&token.length in 1..2048&&!token.any(Char::isISOControl)){"Profile continuation changed source or account"}
        return token
    }
}
