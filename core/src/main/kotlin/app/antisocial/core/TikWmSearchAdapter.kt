/* anti social, GPL-3.0-or-later. Public TikWM search, independently implemented.
 * Route evidence: https://www.tikwm.com/ (Search and Search Photo).
 * Every request and redirect remains under NetworkGateway policy. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

internal class TikWmSearchAdapter(override val source:Source,private val gateway:NetworkGateway,private val routes:DarwinRoutes):DataAdapter {
    override val capabilities=setOf(Capability.SEARCH)
    override fun accepts(target:Target)=target.platform==Platform.TIKTOK&&target.kind==TargetKind.SEARCH&&SearchScopes.from(target)==null
    override fun sentinel(capability:Capability)=routes.search(Platform.TIKTOK,"nasa")
    override suspend fun feed(target:Target):Page=throw SourceFailure(HealthStatus.UNSUPPORTED,"This route searches posts, not subscription feeds")
    override suspend fun fetch(target:Target,cursor:String?):Page {
        require(accepts(target)){"TikWM search requires an unscoped TikTok query"}
        val previous=cursor?.let {readCursor(it,target)} ?: "0"
        val kind=if(source.frontend=="tikwm-photo-search")"photo"else "feed"
        val payload=gateway.text(source.root.trimEnd('/')+"/api/$kind/search?keywords="+DarwinRoutes.encode(target.identity)+"&count=12&cursor=$previous&hd=0",target.canonicalUrl,maxBytes=1024L*1024,headers=mapOf("Accept" to "application/json"))
        LargePlatformDiscoveryParsing.requireSource(payload,source)
        val root=runCatching {JsonParser.parseString(payload.text).asJsonObject}.getOrElse {
            val challenge=Regex("captcha|challenge-platform|just a moment",RegexOption.IGNORE_CASE).containsMatchIn(payload.text.take(32_000))
            throw SourceFailure(if(challenge)HealthStatus.CHALLENGE else HealthStatus.UNAVAILABLE,"TikWM did not return public search data")
        }
        fun text(o:JsonObject,key:String)=o[key]?.takeIf {it.isJsonPrimitive}?.asString.orEmpty()
        if(text(root,"code")!="0") {
            val limited=Regex("rate.?limit|too many|limit.*request|requests?.*(?:second|minute)",RegexOption.IGNORE_CASE).containsMatchIn(text(root,"msg"))
            throw SourceFailure(if(limited)HealthStatus.RATE_LIMITED else HealthStatus.UNAVAILABLE,"TikWM search is unavailable",retryAfterMs=if(limited)60_000 else null)
        }
        val data=root["data"]?.takeIf {it.isJsonObject}?.asJsonObject ?: throw SourceFailure(HealthStatus.EMPTY,"TikWM returned no search records")
        val records=data["videos"]?.takeIf {it.isJsonArray}?.asJsonArray ?: throw SourceFailure(HealthStatus.EMPTY,"TikWM returned no search records")
        val posts=records.take(30).mapNotNull {value->
            val row=value.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val author=row["author"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return@mapNotNull null
            val username=text(author,"unique_id").takeIf {it.matches(Regex("[A-Za-z0-9_.]{1,64}"))} ?: return@mapNotNull null
            val id=text(row,"video_id").ifBlank {text(row,"id")}.takeIf {it.matches(Regex("[0-9]{1,40}"))} ?: return@mapNotNull null
            val photo=row["images"]?.takeIf {it.isJsonArray}?.asJsonArray?.isEmpty==false
            val destination=routes.recognize("https://www.tiktok.com/@$username/${if(photo)"photo"else "video"}/$id") ?: return@mapNotNull null
            val single=JsonObject().apply {addProperty("code",0);add("data",row)}
            try {LargePlatformDiscoveryParsing.tikWm(payload.copy(text=single.toString()),destination,source).posts.singleOrNull()}
            catch(_:SourceFailure){null}
        }.distinctBy {ContentIdentity.key(it.platform,it.canonicalUrl)}
        if(posts.isEmpty())throw SourceFailure(HealthStatus.EMPTY,"TikWM returned no readable matching posts")
        val more=data["hasMore"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isBoolean}?.asBoolean==true
        val next=text(data,"cursor").takeIf {it.matches(Regex("[0-9]{1,20}"))&&it!=previous&&it!="0"}
        return Page(posts,next=if(more&&next!=null)writeCursor(target,next)else null,provenance=posts.first().provenance,
            notice=if(more&&next==null)"Posts loaded; this source did not supply a usable continuation."else null)
    }
    private fun writeCursor(target:Target,offset:String):String {
        val value=JsonObject().apply {addProperty("source",source.key);addProperty("target",target.canonicalUrl);addProperty("offset",offset)}
        return "tikwm-search:"+Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray(Charsets.UTF_8))
    }
    private fun readCursor(raw:String,target:Target):String {
        require(raw.length<=4096&&raw.startsWith("tikwm-search:")){"Refresh this search to continue"}
        val value=runCatching {JsonParser.parseString(String(Base64.getUrlDecoder().decode(raw.removePrefix("tikwm-search:")),Charsets.UTF_8)).asJsonObject}.getOrNull()
        fun text(key:String)=value?.get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
        require(value?.keySet()==setOf("source","target","offset")&&text("source")==source.key&&text("target")==target.canonicalUrl&&text("offset")?.matches(Regex("[0-9]{1,20}"))==true){"Search continuation changed source or query"}
        return text("offset")!!
    }
}
