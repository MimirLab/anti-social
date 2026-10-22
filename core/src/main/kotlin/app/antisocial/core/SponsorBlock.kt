/* AntiSocial, GPL-3.0-or-later. Independently written SponsorBlock API client.
 * Segment data: SponsorBlock contributors, CC BY-NC-SA-4.0; sponsor.ajay.app. */
package app.antisocial.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import kotlin.math.abs

enum class SponsorCategory(val id: String,val label: String,val detail: String) {
    SPONSOR("sponsor","Paid promotions","Sponsors and paid product placements."),
    SELFPROMO("selfpromo","Self promotion","Unpaid promotions and merchandise."),
    INTERACTION("interaction","Subscribe reminders","Requests to like, subscribe or share."),
    INTRO("intro","Intros and intermissions","Animations and pauses before the content."),
    OUTRO("outro","Endcards and credits","Closing sequences after the content."),
    PREVIEW("preview","Previews and recaps","Repeated teasers and earlier material."),
    HOOK("hook","Hooks and greetings","Opening greetings and attention grabbers."),
    MUSIC("music_offtopic","Non-music sections","Talking and other sections around music."),
    FILLER("filler","Tangents and jokes","Aggressive: may skip context or entertainment."),
    HIGHLIGHT("poi_highlight","Jump to the point","Start at the community highlight. May omit context or reveal the outcome.");
    companion object {fun fromId(id: String)=entries.firstOrNull {it.id==id}}
}

data class SponsorSettings(val enabled: Boolean=true,val categories: Set<String> = SponsorCategory.entries.map {it.id}.toSet())
data class SponsorSegment(val id: String,val category: String,val startMs: Long,val endMs: Long,val videoDurationMs: Long=0,val action: String="skip")
data class SponsorSkip(val fromMs: Long,val toMs: Long,val segmentIds: Set<String>,val categories: Set<String>,val highlight: Boolean=false)

/** Only canonical YouTube hosts are interpreted here. A proxy URL or a random
 * eleven-character string cannot cause a lookup for unrelated content. */
object SponsorBlock {
    const val API="https://sponsor.ajay.app"
    const val MAX_BYTES=512L*1024
    private const val MAX_DURATION=14L*24*60*60*1000
    private val videoPattern=Regex("[A-Za-z0-9_-]{11}")
    fun validVideoId(value: String)=videoPattern.matches(value)
    fun videoId(value: String): String? {
        val url=value.toHttpUrlOrNull() ?: return null
        if(url.username.isNotEmpty()||url.password.isNotEmpty())return null
        val parts=url.pathSegments
        val id=when(url.host) {
            "youtu.be"->parts.firstOrNull()
            "youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtube-nocookie.com","www.youtube-nocookie.com"->when(parts.firstOrNull()) {
                "watch"->url.queryParameter("v")
                "shorts","embed","live","v"->parts.getOrNull(1)
                else->null
            }
            else->null
        }
        return id?.takeIf(::validVideoId)
    }
    fun hash(videoId: String): String {
        require(validVideoId(videoId)){"Invalid YouTube video"}
        return MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun requestUrl(videoId: String,endpoint: String=API): String = endpoint.toHttpUrl().newBuilder()
        .addPathSegments("api/skipSegments/${hash(videoId).take(4)}")
        .addQueryParameter("categories",SponsorCategory.entries.joinToString(",",prefix="[",postfix="]"){"\"${it.id}\""})
        .addQueryParameter("actionTypes","[\"skip\",\"mute\",\"poi\"]")
        .addQueryParameter("service","YouTube").addQueryParameter("trimUUIDs","true").build().toString()

    /** Prefix responses contain other videos. Match the exact ID locally and
     * reject unsupported action types, malformed numbers and metadata labels. */
    fun parse(value: String,videoId: String): List<SponsorSegment> {
        require(validVideoId(videoId))
        require(value.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"SponsorBlock response is too large"}
        val root=JsonParser.parseString(value)
        require(root.isJsonArray&&root.asJsonArray.size()<=4096){"Invalid SponsorBlock response"}
        val rows=root.asJsonArray.filter {it.isJsonObject}.map {it.asJsonObject}.filter {it.string("videoID")==videoId}
        return rows.flatMap {row->
            val segments=row.get("segments")?.takeIf {it.isJsonArray}?.asJsonArray ?: return@flatMap emptyList()
            require(segments.size()<=512){"Too many SponsorBlock segments"}
            segments.mapNotNull {element->
                if(!element.isJsonObject)return@mapNotNull null
                val item=element.asJsonObject
                if(item.get("hidden")?.let {it.isJsonPrimitive&&(it.asJsonPrimitive.isBoolean&&it.asBoolean||it.asJsonPrimitive.isNumber&&it.asDouble>0)}==true)return@mapNotNull null
                val category=SponsorCategory.fromId(item.string("category") ?: "") ?: return@mapNotNull null
                val action=item.string("actionType") ?: "skip"
                if(action !in setOf("skip","mute","poi"))return@mapNotNull null
                if((action=="poi")!=(category==SponsorCategory.HIGHLIGHT))return@mapNotNull null
                val segment=item.get("segment")?.takeIf {it.isJsonArray}?.asJsonArray ?: return@mapNotNull null
                if(segment.size()!=2)return@mapNotNull null
                fun time(index: Int): Long?=segment[index].takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asDouble
                    ?.takeIf {it.isFinite()&&it>=0&&it<=MAX_DURATION/1000.0}?.let {(it*1000).toLong()}
                val start=time(0) ?: return@mapNotNull null;val end=time(1) ?: return@mapNotNull null
                if(action=="poi"&&start!=end||action!="poi"&&end-start<100)return@mapNotNull null
                val declared=item.get("videoDuration")
                val seconds=if(declared==null)0.0 else declared.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isNumber}?.asDouble ?: return@mapNotNull null
                if(!seconds.isFinite()||seconds<0||seconds>MAX_DURATION/1000.0)return@mapNotNull null
                val duration=(seconds*1000).toLong()
                if(duration>0&&end>duration+1000)return@mapNotNull null
                val id=item.string("UUID")?.takeIf {it.length in 1..128&&it.all {c->c.isLetterOrDigit()||c=='-'||c=='_'}} ?: return@mapNotNull null
                SponsorSegment(id,category.id,start,end,duration,action)
            }
        }.distinctBy {it.id}.take(512).sortedWith(compareBy({it.startMs},{it.endMs},{it.id}))
    }
    private fun JsonObject.string(key: String)=get(key)?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString
}

/** Playback session state is local. A deliberate seek back can replay a skipped
 * part without fighting an automatic seek on every progress tick. */
class SponsorSkipSession {
    private val handled=mutableSetOf<String>()
    private var highlightConsidered=false
    fun next(positionMs: Long,durationMs: Long,segments: List<SponsorSegment>,settings: SponsorSettings=SponsorSettings()): SponsorSkip? {
        if(!settings.enabled||durationMs<=0||positionMs<0||positionMs>=durationMs)return null
        val usable=segments.filter {it.category in settings.categories&&it.id !in handled&&
            it.startMs in 0 until durationMs&&it.endMs in it.startMs..durationMs&&
            (it.videoDurationMs==0L||abs(it.videoDurationMs-durationMs)<=2000)}
        if(!highlightConsidered) {
            highlightConsidered=true
            if(positionMs<=5000)usable.firstOrNull {it.action=="poi"&&it.startMs>positionMs+1000}?.let {
                return SponsorSkip(positionMs,it.startMs,setOf(it.id),setOf(it.category),highlight=true)
            }
        }
        val ranges=usable.filter {it.action in setOf("skip","mute")&&it.endMs>it.startMs}
        val first=ranges.firstOrNull {positionMs>=it.startMs&&positionMs<it.endMs} ?: return null
        var end=first.endMs;val included=mutableSetOf(first)
        do {
            val previous=end
            ranges.filter {it.startMs<=end&&it.endMs>=first.startMs}.forEach {included+=it;end=maxOf(end,it.endMs)}
        }while(end>previous)
        return SponsorSkip(positionMs,end,included.map {it.id}.toSet(),included.map {it.category}.toSet())
    }
    fun applied(skip: SponsorSkip){handled+=skip.segmentIds}
}

/** Read-only use. No user identity, viewing reports, submissions or votes. */
class SponsorBlockClient(private val gateway: NetworkGateway,private val endpoint: String=SponsorBlock.API) {
    suspend fun read(videoId: String): List<SponsorSegment> {
        val url=SponsorBlock.requestUrl(videoId,endpoint)
        return gateway.boundedHealthRead(maxBytes=SponsorBlock.MAX_BYTES,maxRequests=2) {
            try {SponsorBlock.parse(gateway.text(url,sourceUrl=SponsorBlock.API,maxBytes=SponsorBlock.MAX_BYTES).text,videoId)}
            catch(e: SourceFailure){if(e.httpStatus==404)emptyList()else throw e}
        }
    }
}
