/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor.
 * Archive discovery protocols researched from TheTechRobo/youtubevideofinder
 * (Copyright 2022-2026 TheTechRobo, Apache-2.0). This is an independent native
 * reader; that project's implementation is not embedded. See docs/YOUTUBE_RECOVERY.md.
 */
package app.antisocial.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A recovered *same-ID* recording, not merely a watch page or a similar title. */
data class RecoveredYouTubeVideo(
    val videoId: String,
    val media: Media,
    val sourceUrl: String,
    val provider: String,
    val capturedAt: Long?=null,
    val title: String="",
    val body: String="",
    val author: String="",
    val publishedAt: Long?=null
) {
    /** Keep current attribution/text when recovering a failed player. Archive
     * metadata fills missing fields; fetching time is never an invented capture date. */
    fun asPost(current: Post?=null,now: Long=System.currentTimeMillis()): Post {
        val canonical="https://www.youtube.com/watch?v=$videoId"
        val root=sourceUrl.toHttpUrlOrNull()?.let {"${it.scheme}://${it.host}"}.orEmpty()
        val provenance=Provenance(Source(Platform.YOUTUBE,"youtube-archive",root,role=SourceRole.CACHE),now,sourceUrl)
        val archive=capturedAt?.let {ArchiveRecovery(provider,it,sourceUrl)}
        val base=current ?: Post(videoId,Platform.YOUTUBE,canonical,author,body,title,publishedAt)
        return base.copy(canonicalUrl=canonical,title=base.title.ifBlank {title},body=base.body.ifBlank {body},
            author=base.author.ifBlank {author},publishedAt=base.publishedAt ?: publishedAt,
            media=listOf(media),provenance=provenance,recoveredArchive=archive,archivedVersion=null,preview=false)
    }
    fun asPage(current: Post?=null,now: Long=System.currentTimeMillis()): Page=asPost(current,now).let {Page(listOf(it),provenance=it.provenance)}
}

/** A small, on-error-only recovery ladder. No uploads, archive submissions,
 * browser scripts, account credentials, title searches or background crawling.
 * Positive results have passed a bounded container-prefix probe; playback can
 * still fail later, and [rejectedUrls] lets the player move to another recording.
 */
class YouTubeRecovery internal constructor(
    private val transport: YouTubeRecoveryTransport,
    private val clock: ()->Long=System::currentTimeMillis
) {
    constructor(gateway: NetworkGateway,clock: ()->Long=System::currentTimeMillis):this(GatewayYouTubeRecoveryTransport(gateway),clock)
    private data class Cached(val at: Long,val expires: Long,val value: RecoveredYouTubeVideo?,val failures: Int)
    private val cache=linkedMapOf<String,Cached>()
    private val mutex=Mutex()

    suspend fun recover(videoId: String,rejectedUrls: Set<String> = emptySet(),muxedOnly: Boolean=false): RecoveredYouTubeVideo? {
        if(!idPattern.matches(videoId)||!transport.ready())return null
        val rejected=rejectedUrls.filter {it.length<=16*1024}.take(16).toSet()
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(rejected.sorted().joinToString("\n").toByteArray()).take(8).joinToString("") {"%02x".format(it)}
        val key="$videoId|${transport.partition()}|$fingerprint|muxed=$muxedOnly"
        return mutex.withLock {
            val now=clock()
            val previous=cache[key]
            if(previous!=null&&now>=previous.at&&now<previous.expires)return@withLock previous.value
            val result=withTimeoutOrNull(if(transport.tor())40_000L else 22_000L) {
                transport.bounded {discover(videoId,rejected,muxedOnly)}
            }
            val failures=if(result==null)(previous?.failures ?: 0).coerceAtMost(6)+1 else 0
            val lifetime=if(result!=null)6*60*60_000L else (5*60_000L*(1L shl (failures-1).coerceIn(0,6))).coerceAtMost(6*60*60_000L)
            val completed=clock()
            cache.remove(key);cache[key]=Cached(completed,completed+lifetime,result,failures)
            while(cache.size>64)cache.remove(cache.keys.first())
            result
        }
    }

    suspend fun clear()=mutex.withLock {cache.clear()}

    private class Budget {
        var reads=0;var bytes=0L
        fun request() {if(++reads>8)throw RecoveryBudgetEnded()}
        fun consume(count: Int) {bytes+=count;if(bytes>1024*1024)throw RecoveryBudgetEnded()}
    }
    private class RecoveryBudgetEnded: Exception()
    private suspend fun discover(id: String,rejected: Set<String>,muxedOnly: Boolean): RecoveredYouTubeVideo? {
        val budget=Budget()
        suspend fun read(url: String): Payload? {
            budget.request()
            return attempt {transport.text(url)}?.also {budget.consume(it.text.toByteArray().size)}
        }
        suspend fun check(candidate: RecoveredYouTubeVideo): RecoveredYouTubeVideo? {
            if(muxedOnly&&candidate.media.audioUrl!=null)return null
            if(candidate.media.url in rejected||candidate.media.audioUrl in rejected)return null
            budget.request()
            val prefix=attempt {transport.prefix(candidate.media.url,candidate.sourceUrl)} ?: return null
            budget.consume(prefix.size)
            val actual=container(prefix) ?: return null
            // A declared type alone does not make an HTML error page a stream.
            if(VideoFormats.normalize(candidate.media.mimeType)?.let {it!=actual}==true)return null
            candidate.media.audioUrl?.let {audio->
                budget.request()
                val audioPrefix=attempt {transport.prefix(audio,candidate.sourceUrl)} ?: return null
                budget.consume(audioPrefix.size)
                if(!audioContainer(audioPrefix))return null
            }
            return candidate.copy(media=candidate.media.copy(mimeType=actual))
        }
        try {
            read("https://web.archive.org/__wb/videoinfo?vtype=youtube&vid=$id")?.let {payload->
                if(exactEndpoint(payload.finalUrl,"web.archive.org","/__wb/videoinfo")&&payload.finalUrl.toHttpUrlOrNull()?.queryParameter("vid")==id)
                    wayback(id,payload.text).forEach {candidate->check(candidate)?.let {return it}}
            }
            // The legacy Wayback recording endpoint remains useful when its
            // newer format index is unavailable. A redirect or 200 is not
            // sufficient: it must return an actual media prefix below.
            val legacy="https://web.archive.org/web/0id_/http://wayback-fakeurl.archive.org/yt/$id"
            if(!muxedOnly)check(RecoveredYouTubeVideo(id,Media(legacy,"video"),legacy,"Wayback Machine"))?.let {return it}
            read("https://ghostarchive.org/varchive/$id")?.let {payload->html(id,payload,"Ghostarchive")?.let {check(it)?.let {found->return found}}}
            read("https://api.preservetube.com/video/$id")?.let {payload->preserve(id,payload)?.let {check(it)?.let {found->return found}}}
            // These are the exact public item conventions used by Video Finder.
            // Individual files must also carry the requested ID or an exact
            // source declaration; collection existence alone proves nothing.
            for(identifier in listOf("youtube-$id","youtube_$id",id)) {
                read("https://archive.org/metadata/$identifier")?.let {payload->archiveItem(id,identifier,payload).forEach {check(it)?.let {found->return found}}}
            }
            for(path in listOf("/videos/$id","/tpa-h/videos/$id")) {
                read("https://hobune.stream$path")?.let {payload->html(id,payload,"Hobune")?.let {check(it)?.let {found->return found}}}
            }
        }catch(_: RecoveryBudgetEnded) { /* A future attempt may discover another route. */ }
        return null
    }

    private suspend fun <T> attempt(block: suspend ()->T): T?=try {block()}catch(e: CancellationException){throw e}catch(_: Exception){null}

    companion object {
        private val idPattern=Regex("[A-Za-z0-9_-]{11}")
        private val stampFormat=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
        private fun string(value: JsonObject,key: String)=value[key]?.takeIf {it.isJsonPrimitive&&!it.asJsonPrimitive.isBoolean}?.asString.orEmpty()
        private fun json(value: String)=runCatching {JsonParser.parseString(value).takeIf {it.isJsonObject}?.asJsonObject}.getOrNull()
        private fun safe(value: String): String?=value.takeIf {it.length<=16*1024&&it.none {c->c<=' '||c=='\\'}}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
        private fun exactEndpoint(value: String,host: String,path: String)=safe(value)?.toHttpUrlOrNull()?.let {it.host==host&&it.encodedPath==path}==true
        private fun timestamp(value: String): Long? {
            if(!Regex("[0-9]{14}").matches(value))return null
            return runCatching {LocalDateTime.parse(value,stampFormat).takeIf {it.year in 1990..2100}?.toInstant(ZoneOffset.UTC)?.toEpochMilli()}.getOrNull()
        }
        private fun date(value: String): Long?=listOf<()->Long>(
            {Instant.parse(value).toEpochMilli()},
            {ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()},
            {LocalDateTime.parse(value,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC).toEpochMilli()},
            {LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()}
        ).firstNotNullOfOrNull {runCatching(it).getOrNull()}
        private fun values(element: JsonElement?): List<JsonObject> =element?.takeIf {it.isJsonArray}?.asJsonArray?.take(24)?.mapNotNull {it.takeIf(JsonElement::isJsonObject)?.asJsonObject}.orEmpty()

        internal fun wayback(id: String,text: String): List<RecoveredYouTubeVideo> {
            if(!idPattern.matches(id))return emptyList()
            val record=json(text) ?: return emptyList()
            val formats=record["formats"] ?: return emptyList()
            val split=formats.isJsonObject
            val video=if(split)values(formats.asJsonObject["video"])else values(formats)
            val audio=if(split)values(formats.asJsonObject["audio"]).firstNotNullOfOrNull {item->replay(item,id)?.let {it to string(item,"mimetype")}}else null
            if(split&&audio==null)return emptyList()
            return video.mapNotNull {format->
                val mime=VideoFormats.normalize(string(format,"mimetype"))?.takeIf {it in setOf("video/mp4","video/webm")} ?: return@mapNotNull null
                val url=replay(format,id) ?: return@mapNotNull null
                val codec=string(format,"codec")
                // Some array responses also describe separate streams. Never
                // silently deliver a recording known to have no audio track.
                if(!split&&(record["dmux"]?.toString()=="true"||codec.substringAfter(',',"").trim().equals("Unknwn",true)))return@mapNotNull null
                RecoveredYouTubeVideo(id,Media(url,"video",mimeType=mime,audioUrl=audio?.first,audioMimeType=audio?.second),
                    url,"Wayback Machine",timestamp(string(format,"timestamp")))
            }.take(2)
        }
        private fun replay(format: JsonObject,id: String): String? {
            val time=string(format,"timestamp").takeIf {timestamp(it)!=null} ?: return null
            val original=string(format,"url").toHttpUrlOrNull() ?: return null
            if(original.port !in setOf(80,443)||original.username.isNotEmpty()||original.password.isNotEmpty())return null
            val known=original.host.endsWith(".googlevideo.com")&&original.encodedPath=="/videoplayback" ||
                original.host=="wayback-fakeurl.archive.org"&&original.encodedPath=="/yt/$id"
            if(!known||original.host.endsWith(".googlevideo.com")&&original.queryParameter("video_id")!=id)return null
            return "https://web.archive.org/web/${time}id_/$original"
        }

        internal fun preserve(id: String,payload: Payload): RecoveredYouTubeVideo? {
            if(!exactEndpoint(payload.finalUrl,"api.preservetube.com","/video/$id")&&!exactEndpoint(payload.finalUrl,"preservetube.com","/video/$id"))return null
            val item=json(payload.text) ?: return null
            if(string(item,"id")!=id||item["error"]!=null||item["disabled"]?.toString()=="true")return null
            // Current PreserveTube deliberately withholds the source in these
            // states. Do not trigger restoration or archival as a side effect.
            if(item["deletion_stage"]?.takeUnless {it.isJsonNull}!=null)return null
            val source=safe(string(item,"source")) ?: return null
            val preview=safe(string(item,"thumbnail"))
            return RecoveredYouTubeVideo(id,Media(source,"video",preview),"https://preservetube.com/watch?v=$id","PreserveTube",
                date(string(item,"archived")),string(item,"title").take(2048),Jsoup.parse(string(item,"description")).wholeText().take(65536),
                string(item,"channel").take(1024),date(string(item,"published")))
        }

        internal fun archiveItem(id: String,identifier: String,payload: Payload): List<RecoveredYouTubeVideo> {
            if(!exactEndpoint(payload.finalUrl,"archive.org","/metadata/$identifier"))return emptyList()
            val record=json(payload.text) ?: return emptyList()
            if(record["is_dark"]?.toString()=="true")return emptyList()
            val metadata=record["metadata"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return emptyList()
            if(string(metadata,"identifier")!=identifier)return emptyList()
            val source=string(metadata,"originalurl").ifBlank {string(metadata,"source")}
            val declared=source.toHttpUrlOrNull()?.let {it.host in setOf("youtube.com","www.youtube.com","m.youtube.com")&&it.encodedPath=="/watch"&&it.queryParameter("v")==id || it.host=="youtu.be"&&it.encodedPath=="/$id"}==true
            val files=values(record["files"])
            val videos=files.filter {item->
                val name=string(item,"name")
                name.length in 1..1024&&!name.startsWith('/')&&name.split('/').none {it==".."}&&
                    name.substringAfterLast('.').lowercase() in setOf("mp4","webm")&&
                    (Regex("(?<![A-Za-z0-9_-])${Regex.escape(id)}(?![A-Za-z0-9_-])").containsMatchIn(name)||name.contains("-$id.")||name.contains("_$id."))
            }.ifEmpty {if(declared)files.filter {string(it,"name").substringAfterLast('.').lowercase() in setOf("mp4","webm")}.takeIf {it.size==1}.orEmpty()else emptyList()}
            return videos.take(2).mapNotNull {file->
                val name=string(file,"name")
                if(name.length !in 1..1024||name.startsWith('/')||name.split('/').any {it==".."})return@mapNotNull null
                val url="https://archive.org".toHttpUrlOrNull()!!.newBuilder().addPathSegment("download").addPathSegment(identifier).addPathSegment(name).build().toString()
                RecoveredYouTubeVideo(id,Media(url,"video",mimeType=if(name.endsWith(".webm",true))"video/webm"else "video/mp4"),
                    "https://archive.org/details/$identifier","Internet Archive",date(string(metadata,"publicdate")).let {it ?: date(string(metadata,"addeddate"))},
                    string(metadata,"title").take(2048),Jsoup.parse(string(metadata,"description")).wholeText().take(65536),string(metadata,"creator").take(1024),date(string(metadata,"date")))
            }
        }

        internal fun html(id: String,payload: Payload,provider: String): RecoveredYouTubeVideo? {
            val path=when(provider) {"Ghostarchive"->"/varchive/$id";"Hobune"->payload.finalUrl.toHttpUrlOrNull()?.encodedPath?.takeIf {it in setOf("/videos/$id","/tpa-h/videos/$id")} ?: return null;else->return null}
            val host=if(provider=="Ghostarchive")"ghostarchive.org"else "hobune.stream"
            if(!exactEndpoint(payload.finalUrl,host,path))return null
            val document=Jsoup.parse(payload.text,payload.finalUrl)
            val media=VideoExtraction.html(document,payload.finalUrl).firstOrNull {video->
                val url=video.url.toHttpUrlOrNull() ?: return@firstOrNull false
                // Both public archives expose exact-ID video paths. Reject
                // decorative/trailer players and external YouTube embeds.
                url.host.let {it==host||provider=="Ghostarchive"&&it=="ghostvideo.b-cdn.net"}&&
                    url.pathSegments.any {it==id||it.substringBeforeLast('.')==id}
            } ?: return null
            val pageText=document.body().text()
            val rfcDate="([A-Za-z]{3},\\s+[0-9]{1,2}\\s+[A-Za-z]{3}\\s+[0-9]{4}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}\\s+(?:GMT|UTC|[+-][0-9]{4}))"
            val archived=Regex("Archive date:\\s*$rfcDate").find(pageText)?.groupValues?.get(1)?.let(::date)
            val published=Regex("Original upload date:\\s*$rfcDate").find(pageText)?.groupValues?.get(1)?.let(::date)
            val author=document.select("a[href*=youtube.com/channel/],a[href*=youtube.com/user/]").firstOrNull()?.text().orEmpty().take(1024)
            val title=document.select("h2,h1").firstOrNull()?.text()?.takeIf(String::isNotBlank)
                ?: document.title().substringBefore(" | ").take(2048)
            val body=document.selectFirst("#desc,.description,#description")?.wholeText().orEmpty().take(65536)
            return RecoveredYouTubeVideo(id,media,payload.finalUrl,provider,archived,title,body,author,published)
        }

        /** Only recognizable media bytes count; 200/Content-Type/extension do not. */
        internal fun container(bytes: ByteArray): String? {
            if(bytes.size>=12&&String(bytes,4,4,Charsets.US_ASCII)=="ftyp")return "video/mp4"
            if(bytes.size>=4&&bytes[0]==0x1a.toByte()&&bytes[1]==0x45.toByte()&&bytes[2]==0xdf.toByte()&&bytes[3]==0xa3.toByte())return "video/webm"
            return VideoFormats.sniff(bytes)
        }
        private fun audioContainer(bytes: ByteArray)=container(bytes) in setOf("video/mp4","video/webm")||
            bytes.size>=4&&String(bytes,0,4,Charsets.US_ASCII)=="OggS"
    }
}

internal interface YouTubeRecoveryTransport {
    suspend fun text(url: String): Payload
    suspend fun prefix(url: String,sourceUrl: String): ByteArray
    suspend fun bounded(block: suspend ()->RecoveredYouTubeVideo?): RecoveredYouTubeVideo?=block()
    fun ready()=true
    fun tor()=false
    fun partition()="direct"
}

private class GatewayYouTubeRecoveryTransport(private val gateway: NetworkGateway): YouTubeRecoveryTransport {
    override suspend fun text(url: String)=gateway.text(url,maxBytes=256L*1024)
    override suspend fun prefix(url: String,sourceUrl: String)=gateway.sample(url,sourceUrl,limit=4096)
    override suspend fun bounded(block: suspend ()->RecoveredYouTubeVideo?)=gateway.boundedHealthRead(maxBytes=1024L*1024,maxRequests=8,read=block)
    override fun ready()=gateway.currentPolicy().let {!it.tor||it.ready&&it.socksPort in 1..65535}
    override fun tor()=gateway.currentPolicy().tor
    override fun partition()="${gateway.currentPolicy()}|${gateway.originalFallback}"
}
