/* AntiSocial, GPL-3.0-or-later. Independent readers of the documented Invidious and
 * Piped public APIs. NewPipe Extractor supplies the permitted original fallback. */
package app.antisocial.core

import com.google.gson.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class YouTubeAdapter(override val source: Source,private val gateway: NetworkGateway,private val routes: DarwinRoutes): DataAdapter {
    override val capabilities=Capability.entries.toSet()
    private val original get()=source.role==SourceRole.ORIGINAL
    private val piped get()=source.frontend=="piped"
    private val native by lazy {YouTubeNewPipe(gateway,source,routes)}
    override fun accepts(target: Target)=target.platform==Platform.YOUTUBE&&target.kind in setOf(TargetKind.PROFILE,TargetKind.POST,TargetKind.THREAD,TargetKind.SEARCH)
    override fun sentinel(capability: Capability)=if(capability in setOf(Capability.POST,Capability.COMMENTS,Capability.MEDIA))routes.target(Platform.YOUTUBE,YouTubeUrls.canonical("jNQXAC9IVRw"))else if(capability==Capability.SEARCH)routes.search(Platform.YOUTUBE,"NewPipe")else routes.target(Platform.YOUTUBE,"@NASA")

    private data class Cursor(val source: String,val target: String,val mode: String,val token: String,val channel: String?=null)
    private fun cursor(target: Target,mode: String,token: String?,channel: String?=null): String?=token?.takeIf {it.isNotBlank()&&it.length<=12_000}?.let {"yt:"+Gson().toJson(Cursor(source.key,target.canonicalUrl,mode,it,channel))}
    private fun token(target: Target,mode: String,value: String?): Cursor?=value?.let {
        require(it.length<=24*1024&&it.startsWith("yt:")){"Invalid YouTube continuation"}
        Gson().fromJson(it.removePrefix("yt:"),Cursor::class.java).also {c->require(c.source==source.key&&c.target==target.canonicalUrl&&c.mode==mode&&c.token.length in 1..12_000){"YouTube continuation belongs to another source or search"}}
    }
    private suspend fun read(path: String,target: Target,query: Map<String,String> = emptyMap(),sameOrigin: Boolean=false): Pair<JsonElement,Provenance> {
        val base=source.root.toHttpUrlOrNull() ?: error("Invalid YouTube reader")
        val url=base.newBuilder().encodedPath(base.encodedPath.trimEnd('/')+path).query(null).apply {query.forEach {(k,v)->addQueryParameter(k,v)}}.build()
        val payload=if(sameOrigin)gateway.contextText(url.toString(),target.canonicalUrl,4L*1024*1024,sameOrigin=true)else gateway.text(url.toString(),target.canonicalUrl,maxBytes=4L*1024*1024)
        val final=payload.finalUrl.toHttpUrlOrNull() ?: error("Invalid YouTube reader response")
        require(final.host==base.host&&final.port==base.port&&final.scheme==base.scheme&&final.encodedPath==url.encodedPath&&final.encodedQuery==url.encodedQuery){"YouTube reader redirected outside its selected source"}
        val json=runCatching {JsonParser.parseString(payload.text)}.getOrElse {throw SourceFailure(HealthStatus.CHALLENGE,"The YouTube reader returned a web page instead of public data")}
        if(json.isJsonObject&&json.asJsonObject.has("error"))throw SourceFailure(HealthStatus.UNAVAILABLE,text(json.asJsonObject,"error").take(160))
        return json to Provenance(source,System.currentTimeMillis(),payload.finalUrl)
    }
    override suspend fun fetch(target: Target,cursor: String?): Page {
        require(accepts(target)){"Unsupported YouTube target"}
        if(original)return native.fetch(target,cursor)
        if(target.kind in setOf(TargetKind.POST,TargetKind.THREAD)) {
            require(cursor==null){"A video has no post continuation"}
            val id=YouTubeUrls.videoId(target.canonicalUrl) ?: error("Invalid YouTube video")
            val (json,provenance)=videoData(id,target)
            val post=post(json,provenance,id) ?: throw SourceFailure(HealthStatus.EMPTY,"The reader did not return this YouTube video")
            return Page(listOf(post),provenance=provenance)
        }
        if(target.kind==TargetKind.SEARCH)return search(target,cursor,false)
        val c=token(target,"channel",cursor)
        val channel=c?.channel ?: target.identity.removePrefix("channel/")
        require(channel.matches(Regex("@?[\\p{L}\\p{N}_.-]{1,100}|(?:user|c)/[A-Za-z0-9_.-]{1,100}"))){"Invalid YouTube channel"}
        val (json,provenance)=if(piped)read(if(c==null)"/channel/$channel"else "/nextpage/channel/$channel",target,c?.let {mapOf("nextpage" to it.token)}.orEmpty())
            else read("/api/v1/channels/$channel/videos",target,c?.let {mapOf("continuation" to it.token)}.orEmpty())
        val o=json.takeIf {it.isJsonObject}?.asJsonObject
        val items=if(json.isJsonArray)json.asJsonArray else array(o,if(piped)"relatedStreams"else "videos")
        val posts=items.mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {item->post(item,provenance)}}.sortedByDescending {it.publishedAt ?: Long.MIN_VALUE}
        val next=cursor(target,"channel",o?.let {text(it,if(piped)"nextpage"else "continuation")},o?.let {text(it,"id").ifBlank {channel}} ?: channel)
        val profile=Profile(target,o?.let {text(it,"name").ifBlank {text(it,"author")} }?.ifBlank {target.identity} ?: target.identity,o?.let {plain(text(it,"description"))}.orEmpty(),o?.let {safe(text(it,"avatarUrl"),provenance.finalUrl)},o?.let {number(it,"subscriberCount") ?: number(it,"subCount")},displayNameSupplied=o?.let {(text(it,"name").isNotBlank()||text(it,"author").isNotBlank())&&profileIdentity(it,target)}==true)
        return Page(posts,next,profile,provenance)
    }
    override suspend fun profile(target: Target): Page {
        if(original)return native.fetch(target,null)
        if(piped)return fetch(target)
        val id=target.identity.removePrefix("channel/")
        val (j,p)=read("/api/v1/channels/$id",target)
        val o=j.asJsonObject
        val profile=Profile(target,text(o,"author").ifBlank {target.identity},plain(text(o,"description")),thumbnail(o,"authorThumbnails",p.finalUrl),number(o,"subCount"),displayNameSupplied=text(o,"author").isNotBlank()&&profileIdentity(o,target))
        return Page(array(o,"latestVideos").mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {v->post(v,p)}},profile=profile,provenance=p)
    }
    override suspend fun feed(target: Target)=fetch(target)
    override suspend fun directory(target: Target,cursor: String?): Page=if(original)native.directory(target,cursor)else search(target,cursor,true)
    private suspend fun search(target: Target,value: String?,directory: Boolean): Page {
        val mode=if(directory)"directory"else "search"
        val c=token(target,mode,value)
        val params=if(piped)mapOf("q" to target.identity,"filter" to if(directory)"channels"else "videos")+(c?.let {mapOf("nextpage" to it.token)}.orEmpty())
            else mapOf("q" to target.identity,"type" to if(directory)"channel"else "video","sort_by" to "upload_date","page" to (c?.token ?: "1"))
        val (j,p)=read(if(piped){if(c==null)"/search"else "/nextpage/search"}else "/api/v1/search",target,params)
        val o=j.takeIf {it.isJsonObject}?.asJsonObject
        val items=if(j.isJsonArray)j.asJsonArray else array(o,"items")
        val accounts=items.mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {v->
            val path=text(v,if(piped)"url"else "authorUrl").ifBlank {text(v,"authorId").takeIf(String::isNotBlank)?.let {"/channel/$it"}.orEmpty()}
            val t=YouTubeUrls.recognize(publicPath(path))?.takeIf {it.kind==TargetKind.PROFILE} ?: return@let null
            if(!directory&&text(v,"type") !in setOf("channel","channelItem"))return@let null
            Profile(t,text(v,if(piped)"name"else "author").ifBlank {text(v,"name")},plain(text(v,"description")),if(piped)safe(text(v,"thumbnail"),p.finalUrl)else thumbnail(v,"authorThumbnails",p.finalUrl),number(v,"subCount") ?: number(v,"subscribers"),displayNameSupplied=text(v,"name").isNotBlank()||text(v,"author").isNotBlank())
        }}
        val posts=if(directory)emptyList()else items.mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {v->post(v,p)}}.sortedByDescending {it.publishedAt ?: Long.MIN_VALUE}
        val next=if(items.isEmpty())null else cursor(target,mode,if(piped)o?.let {text(it,"nextpage")}else ((c?.token?.toIntOrNull() ?: 1)+1).takeIf {it<=100}?.toString())
        return Page(posts,next,provenance=p,accounts=accounts)
    }
    override suspend fun comments(target: Target,cursor: String?): Page {
        if(original)return native.comments(target,cursor)
        val id=YouTubeUrls.videoId(target.canonicalUrl) ?: error("Invalid YouTube video")
        val c=token(target,"comments",cursor)
        val sort=target.canonicalUrl.toHttpUrlOrNull()?.queryParameter("sort") ?: "new"
        val (j,p)=read(if(piped){if(c==null)"/comments/$id"else "/nextpage/comments/$id"}else "/api/v1/comments/$id",target,
            if(piped)c?.let {mapOf("nextpage" to it.token)}.orEmpty()else mapOf("sort_by" to if(sort=="top")"top"else "new")+c?.let {mapOf("continuation" to it.token)}.orEmpty())
        val o=j.asJsonObject
        val root=ContentIdentity.key(Platform.YOUTUBE,YouTubeUrls.canonical(id))
        val comments=array(o,"comments").mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {v->
            val cid=text(v,"commentId").takeIf {it.matches(Regex("[A-Za-z0-9_.-]{1,256}"))} ?: return@let null
            val body=plain(text(v,if(piped)"commentText"else "content"));if(body.isBlank())return@let null
            val url=YouTubeUrls.canonical(id)+"&lc=$cid"
            Post(ContentIdentity.key(Platform.YOUTUBE,url),Platform.YOUTUBE,url,text(v,"author"),body,publishedAt=number(v,"published")?.times(1000),avatar=if(piped)safe(text(v,"thumbnail"),p.finalUrl)else thumbnail(v,"authorThumbnails",p.finalUrl),replyCount=number(v,"replyCount"),score=number(v,"likeCount"),parentId=root,provenance=p)
        }}
        return Page(comments,cursor(target,"comments",text(o,if(piped)"nextpage"else "continuation")),provenance=p,notice=if(piped&&sort!="top")"This source supplies its public ranking; exact publication times may be unavailable."else null)
    }
    private suspend fun videoData(id: String,target: Target,sameOrigin: Boolean=false): Pair<JsonObject,Provenance> {
        val (j,p)=read(if(piped)"/streams/$id"else "/api/v1/videos/$id",target,sameOrigin=sameOrigin)
        val o=j.takeIf {it.isJsonObject}?.asJsonObject ?: throw SourceFailure(HealthStatus.EMPTY,"No video data")
        if(!piped&&text(o,"videoId")!=id)throw SourceFailure(HealthStatus.EMPTY,"The reader returned another video")
        require(text(o,"title").isNotBlank()){"Video title missing"}
        return o to p
    }
    suspend fun playback(id: String,rejectedUrls: Set<String> = emptySet(),muxedOnly: Boolean=false): YouTubePlayback {
        require(YouTubeUrls.validId(id))
        if(original)return native.playback(id,rejectedUrls,muxedOnly)
        val (o,p)=videoData(id,YouTubeUrls.recognize(YouTubeUrls.canonical(id))!!)
        val media=YouTubeStreams.select(o,p.finalUrl,piped,rejectedUrls,muxedOnly) ?: throw SourceFailure(HealthStatus.EMPTY,"This reader has no usable video stream")
        return YouTubePlayback(id,media,p.finalUrl,source)
    }
    /** Caption/audio descriptors are derived from the same bounded public video
     * record. This performs no media request or playback probe. */
    internal suspend fun contextMetadata(id: String): ContextVideoMetadata {
        require(YouTubeUrls.validId(id))
        if(original)return native.contextMetadata(id)
        val (json,provenance)=videoData(id,YouTubeUrls.recognize(YouTubeUrls.canonical(id))!!,sameOrigin=true)
        return ContextVideoParsing.fromJson(json,source,provenance.finalUrl,id,piped)
    }
    private fun post(o: JsonObject,p: Provenance,knownId: String?=null): Post? {
        val id=knownId ?: text(o,"videoId").takeIf(YouTubeUrls::validId) ?: YouTubeUrls.videoId("https://www.youtube.com"+text(o,"url")) ?: return null
        if(text(o,"title").isBlank())return null
        val canonical=YouTubeUrls.canonical(id)
        val preview=if(piped)safe(text(o,"thumbnailUrl").ifBlank {text(o,"thumbnail")},p.finalUrl)else thumbnail(o,"videoThumbnails",p.finalUrl)
        val published=number(o,"published")?.takeIf {it>0}?.times(1000) ?: number(o,"uploaded")?.takeIf {it>0} ?: text(o,"uploadDate").takeIf(String::isNotBlank)?.let {date(it)}
        val duration=number(o,if(piped)"duration"else "lengthSeconds")?.takeIf {it>0&&it<31_536_000}?.times(1000)
        return Post("post:$id",Platform.YOUTUBE,canonical,text(o,if(piped)"uploader"else "author").ifBlank {text(o,"uploaderName")},plain(text(o,"description")),title=text(o,"title"),publishedAt=published,avatar=if(piped)safe(text(o,"uploaderAvatar"),p.finalUrl)else thumbnail(o,"authorThumbnails",p.finalUrl),media=listOf(Media(canonical,"video",preview,durationMs=duration)),score=number(o,"likeCount") ?: number(o,"likes"),provenance=p,links=listOfNotNull(text(o,if(piped)"uploaderUrl"else "authorUrl").takeIf(String::isNotBlank)?.let {YouTubeUrls.recognize(publicPath(it))?.canonicalUrl}))
    }
    companion object {
        /** An endpoint path alone is not proof that returned account metadata belongs to it. */
        internal fun profileIdentity(o: JsonObject,target: Target): Boolean {
            val requested=YouTubeUrls.recognize(target.canonicalUrl)?.takeIf {it.kind==TargetKind.PROFILE} ?: return false
            if(requested.identity.startsWith("channel/")) {
                val channel=requested.identity.removePrefix("channel/")
                val ids=listOf("authorId","channelId","id").map {text(o,it)}.filter {it.matches(Regex("UC[A-Za-z0-9_-]{22}"))}
                if(ids.isEmpty()||ids.any {it!=channel})return false
                val declared=text(o,"authorUrl").takeIf(String::isNotBlank)?.let {YouTubeUrls.recognize(publicPath(it))}
                return declared?.identity?.takeIf {it.startsWith("channel/")}?.let {it==requested.identity} ?: true
            }
            val declared=text(o,"authorUrl").takeIf(String::isNotBlank)?.let {YouTubeUrls.recognize(publicPath(it))}
                ?.takeIf {it.kind==TargetKind.PROFILE} ?: return false
            return declared.identity.equals(requested.identity,true)
        }
        private fun publicPath(value: String)=if(value.startsWith("https://"))value else "https://www.youtube.com/"+value.trimStart('/')
        internal fun text(o: JsonObject,key: String)=runCatching {o[key]?.takeIf {it.isJsonPrimitive}?.asString}.getOrNull().orEmpty()
        internal fun number(o: JsonObject,key: String)=runCatching {o[key]?.asLong}.getOrNull()
        internal fun array(o: JsonObject?,key: String)=o?.get(key)?.takeIf {it.isJsonArray}?.asJsonArray ?: JsonArray()
        internal fun safe(value: String,base: String)=base.toHttpUrlOrNull()?.resolve(value)?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()?.takeIf {value.isNotBlank()}
        private fun thumbnail(o: JsonObject,key: String,base: String)=array(o,key).mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject}.sortedByDescending {number(it,"width") ?: 0}.firstNotNullOfOrNull {safe(text(it,"url"),base)}
        private fun plain(value: String)=if(value.contains('<'))Jsoup.parse(value).wholeText().trim()else value
        private fun date(value: String)=runCatching {Instant.parse(value).toEpochMilli()}.getOrNull() ?: runCatching {LocalDate.parse(value.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()}.getOrNull()
    }
}

/** Select content streams only. Advertising/telemetry objects are never read. */
object YouTubeStreams {
    fun select(o: JsonObject,base: String,piped: Boolean,rejected: Set<String> = emptySet(),muxedOnly: Boolean=false): Media? {
        fun text(j: JsonObject,k: String)=YouTubeAdapter.text(j,k)
        fun number(j: JsonObject,k: String)=YouTubeAdapter.number(j,k)
        fun values(key: String)=YouTubeAdapter.array(o,key).mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject}
        fun url(j: JsonObject)=YouTubeAdapter.safe(text(j,"url"),base)?.takeUnless {it in rejected}
        fun mime(j: JsonObject)=VideoFormats.normalize(text(j,if(piped)"mimeType"else "type"))
        fun height(j: JsonObject)=number(j,"height")?.toInt() ?: Regex("[0-9]{2,4}").find(text(j,"qualityLabel").ifBlank {text(j,"quality")}.ifBlank {text(j,"resolution")} )?.value?.toIntOrNull() ?: 0
        val video=values(if(piped)"videoStreams"else "formatStreams").filter {url(it)!=null&&mime(it)!=null&&(!piped||it["videoOnly"]?.asBoolean!=true)}
            .filter {height(it)<=1080}.sortedWith(compareByDescending<JsonObject> {height(it)}.thenBy {mime(it)!="video/mp4"})
        video.firstOrNull()?.let {return Media(url(it)!!,"video",mimeType=mime(it))}
        if(muxedOnly)return null
        for((key,type) in listOf((if(piped)"hls"else "hlsUrl") to VideoFormats.HLS,(if(piped)"dash"else "dashUrl") to VideoFormats.DASH))YouTubeAdapter.safe(text(o,key),base)?.takeUnless {it in rejected}?.let {return Media(it,"video",mimeType=type)}
        val adaptive=values(if(piped)"videoStreams"else "adaptiveFormats")
        val audio=values(if(piped)"audioStreams"else "adaptiveFormats").filter {url(it)!=null&&text(it,if(piped)"mimeType"else "type").startsWith("audio/")}.sortedWith(compareBy<JsonObject> {!text(it,if(piped)"mimeType"else "type").startsWith("audio/mp4")}.thenByDescending {number(it,"bitrate") ?: 0}).firstOrNull() ?: return null
        val v=adaptive.filter {url(it)!=null&&mime(it)!=null&&height(it) in 1..1080}.sortedWith(compareBy<JsonObject> {mime(it)!="video/mp4"}.thenByDescending {height(it)}).firstOrNull() ?: return null
        return Media(url(v)!!,"video",mimeType=mime(v),audioUrl=url(audio),audioMimeType=text(audio,if(piped)"mimeType"else "type").substringBefore(';'))
    }
}
