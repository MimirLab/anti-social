package app.antisocial.core

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.Normalizer

/** A comparison of extracted post fields, never a claim about every capture or media bytes. */
enum class ArchiveComparison { SAME, DIFFERENT, INCONCLUSIVE }

object ArchiveContent {
    private val whitespace=Regex("[\\s\\u00a0\\u2007\\u202f]+")
    private val links=Regex("https?://[^\\s<>]+",RegexOption.IGNORE_CASE)
    private val tracking=setOf("fbclid","gclid","dclid","msclkid","igshid","mc_cid","mc_eid","yclid","_hsenc","_hsmi")
    private val transientMedia=setOf("signature","sig","token","expires","exp","policy","key-pair-id","width","height","w","h","quality","q","format","fm","name")

    /** Local cache key. No counters, author decoration, retrieval dates or archive state participate. */
    fun signature(post: Post): String {
        val body=text(post.body)
        val title=text(post.title).takeUnless {it==body}.orEmpty()
        return hash(listOf(post.platform.id,ContentIdentity.key(post.platform,post.canonicalUrl),title,body,
            post.media.flatMap {listOfNotNull(it.url,it.audioUrl)}.map {mediaIdentity(it) ?: canonicalUrl(it,true)}.sorted().joinToString("\u0001")).joinToString("\u0000"))
    }

    fun compare(current: Post,archived: Post): ArchiveComparison {
        if(current.platform!=archived.platform||ContentIdentity.key(current.platform,current.canonicalUrl)!=ContentIdentity.key(archived.platform,archived.canonicalUrl)||current.preview||archived.preview)return ArchiveComparison.INCONCLUSIVE
        val body=text(current.body);val olderBody=text(archived.body)
        val title=text(current.title).takeUnless {it==body}.orEmpty()
        val olderTitle=text(archived.title).takeUnless {it==olderBody}.orEmpty()
        // Missing or visibly truncated fields are extraction gaps, not evidence of an edit.
        if(body.isNotBlank()&&olderBody.isNotBlank()&&body!=olderBody) {
            if(truncated(body,olderBody)||truncated(olderBody,body))return ArchiveComparison.INCONCLUSIVE
            return ArchiveComparison.DIFFERENT
        }
        if(body.isBlank()!=olderBody.isBlank())return ArchiveComparison.INCONCLUSIVE
        if(title.isNotBlank()&&olderTitle.isNotBlank()&&title!=olderTitle) {
            if(truncated(title,olderTitle)||truncated(olderTitle,title))return ArchiveComparison.INCONCLUSIVE
            return ArchiveComparison.DIFFERENT
        }
        if(title.isBlank()!=olderTitle.isBlank())return ArchiveComparison.INCONCLUSIVE
        val hasText=body.isNotBlank()||title.isNotBlank()
        if(current.media.isEmpty()&&archived.media.isEmpty())return if(hasText)ArchiveComparison.SAME else ArchiveComparison.INCONCLUSIVE
        if(current.media.isEmpty()||archived.media.isEmpty())return ArchiveComparison.INCONCLUSIVE
        if((current.media+archived.media).flatMap {listOfNotNull(it.url,it.audioUrl)}.any {value->runCatching {URI(unwrap(value))}.getOrNull()?.let {it.host.isNullOrBlank()||it.rawUserInfo!=null||it.scheme?.lowercase() !in setOf("http","https")}!=false})return ArchiveComparison.INCONCLUSIVE
        if(current.media.mapNotNull {it.audioUrl}.map {canonicalUrl(it,true)}.toSet()!=archived.media.mapNotNull {it.audioUrl}.map {canonicalUrl(it,true)}.toSet())return ArchiveComparison.INCONCLUSIVE
        val currentUrls=current.media.map {canonicalUrl(it.url,true)}.toSet()
        val archivedUrls=archived.media.map {canonicalUrl(it.url,true)}.toSet()
        if(currentUrls==archivedUrls&&currentUrls.none {it.isBlank()})return ArchiveComparison.SAME
        if(current.media.size!=archived.media.size)return ArchiveComparison.INCONCLUSIVE
        val currentIds=current.media.map {mediaIdentity(it.url)}
        val archivedIds=archived.media.map {mediaIdentity(it.url)}
        // Unknown CDN paths and replay proxies may simply be different encodings of one asset.
        if(currentIds.any {it==null}||archivedIds.any {it==null})return ArchiveComparison.INCONCLUSIVE
        return if(currentIds.toSet()==archivedIds.toSet())ArchiveComparison.SAME else ArchiveComparison.DIFFERENT
    }

    private fun truncated(shorter: String,longer: String): Boolean {
        val suffix=when {shorter.endsWith("…")->"…";shorter.endsWith("...")->"...";else->return false}
        val prefix=shorter.removeSuffix(suffix).trimEnd()
        return prefix.isNotEmpty()&&longer.startsWith(prefix)
    }
    private fun text(value: String): String {
        val decoded=Jsoup.parseBodyFragment(value).apply {select("script,style,noscript").remove()}.text()
        val normalized=Normalizer.normalize(decoded,Normalizer.Form.NFC).replace("\u200b","").replace("\ufeff","")
        return whitespace.replace(links.replace(normalized){match->
            val trailing=match.value.takeLastWhile {it in ".,;!?)\"'"}
            canonicalUrl(match.value.dropLast(trailing.length),false)+trailing
        }," ").trim()
    }
    private fun canonicalUrl(value: String,media: Boolean): String {
        val raw=unwrap(value)
        val uri=runCatching {URI(raw)}.getOrNull() ?: return raw
        val host=uri.host?.lowercase() ?: return raw
        if(uri.scheme?.lowercase() !in setOf("http","https"))return raw
        val query=uri.rawQuery?.split('&')?.filterNot {part->
            val key=runCatching {URLDecoder.decode(part.substringBefore('='),"UTF-8")}.getOrDefault(part.substringBefore('=')).lowercase()
            key.startsWith("utm_")||key in tracking||media&&(key in transientMedia||key.startsWith("x-amz-")||key.startsWith("x-goog-"))
        }?.joinToString("&").orEmpty()
        val port=uri.port.takeUnless {it==-1||it==443&&uri.scheme.equals("https",true)||it==80&&uri.scheme.equals("http",true)}?.let {":$it"}.orEmpty()
        return "${uri.scheme.lowercase()}://$host$port${uri.rawPath.orEmpty()}"+(if(query.isBlank())""else "?$query")+(if(media)""else uri.rawFragment?.let {"#$it"}.orEmpty())
    }
    private fun unwrap(value: String): String {
        val uri=runCatching {URI(value)}.getOrNull() ?: return value
        val prefix=when(uri.host?.lowercase()) {
            "web.archive.org","wayback.archive-it.org" -> "(?:web/|[0-9]+/)?"
            "arquivo.pt" -> "(?:noFrame/replay/|wayback/)"
            else -> return value
        }
        val match=Regex("^/$prefix[0-9]{1,14}[a-z_]*/(https?://.+)$",RegexOption.IGNORE_CASE).matchEntire(uri.rawPath.orEmpty()) ?: return value
        return match.groupValues[1]+uri.rawQuery?.let {"?$it"}.orEmpty()
    }
    private fun mediaIdentity(value: String): String? {
        val uri=runCatching {URI(unwrap(value))}.getOrNull() ?: return null
        val host=uri.host?.lowercase() ?: return null
        val path=uri.path.orEmpty()
        return when {
            host=="pbs.twimg.com" -> Regex("^/media/([A-Za-z0-9_-]+)(?:/|\\.|$)").find(path)?.groupValues?.get(1)?.let {"twitter-media:$it"}
                ?: Regex("^/(?:tweet_video_thumb|ext_tw_video_thumb|amplify_video_thumb)/([A-Za-z0-9_-]+)(?:/|\\.|$)").find(path)?.groupValues?.get(1)?.let {"twitter-video:$it"}
            host=="video.twimg.com" -> Regex("^/(?:ext_tw_video|amplify_video|tweet_video)/([A-Za-z0-9_-]+)(?:/|\\.|$)").find(path)?.groupValues?.get(1)?.let {"twitter-video:$it"}
            host=="i.redd.it"||host=="preview.redd.it" -> Regex("^/([a-z0-9]{8,})(?:\\.[A-Za-z0-9]+)?$").matchEntire(path)?.groupValues?.get(1)?.let {"reddit-image:$it"}
            host=="v.redd.it" -> path.trim('/').substringBefore('/').takeIf {it.matches(Regex("[a-z0-9]{8,}"))}?.let {"reddit-video:$it"}
            host=="cdn.bsky.app" -> Regex("/plain/(did:[^/]+)/([^/@]+)(?:@|/|$)").find(path)?.let {"bluesky:${it.groupValues[1]}:${it.groupValues[2]}"}
            else -> null
        }
    }
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

enum class ArchiveCheckOutcome { DIFFERENT, SAME, NOT_FOUND, FAILED, CANCELLED, RATE_LIMITED }
data class ArchiveBudget(val starts: List<Long> = emptyList(),val lastStarted: Long = 0L,val blockedUntil: Long = 0L)

/** Passive dwell checks have one shared budget regardless of provider, changed text or Tor mode. */
class ArchiveCheckPolicy(private val clock: ()->Long=System::currentTimeMillis) {
    private data class Lease(val post: String,val signature: String,val tor: Boolean,val started: Long)
    private val starts=ArrayDeque<Long>()
    private val posts=linkedMapOf<String,Long>()
    private var active: Lease?=null
    private var lastStarted=0L
    private var pausedUntil=0L

    /** Tiny local courtesy metadata contains no post identifiers, queries or content. */
    @Synchronized fun budget(): ArchiveBudget {
        trim(clock())
        return ArchiveBudget(starts.toList(),lastStarted,pausedUntil)
    }
    @Synchronized fun restoreBudget(value: ArchiveBudget) {
        val now=clock();trim(now)
        if(value.starts.size>6||value.starts.distinct().size!=value.starts.size||value.starts.any {it<0||it>now}||value.lastStarted<0||value.lastStarted>now||value.starts.any {it>value.lastStarted}||value.blockedUntil<0)return
        val recent=(starts+value.starts.filter {now-it<HOUR}).distinct().sorted().takeLast(6)
        starts.clear();recent.forEach(starts::addLast)
        lastStarted=maxOf(lastStarted,value.lastStarted)
        pausedUntil=maxOf(pausedUntil,value.blockedUntil.coerceAtMost(now+HOUR))
    }

    @Synchronized fun claim(postKey: String,contentSignature: String,tor: Boolean): Boolean {
        if(postKey.isBlank()||contentSignature.isBlank()||postKey.length>16_384||contentSignature.length>256)return false
        val now=clock();trim(now)
        if(active!=null||now<pausedUntil||starts.size>=6||(starts.isNotEmpty()||lastStarted!=0L)&&now-lastStarted<30_000)return false
        val key=hash(postKey)
        if(posts[key]?.let {now<it}==true)return false
        starts.addLast(now);lastStarted=now;posts[key]=now+DAY;active=Lease(key,contentSignature,tor,now)
        while(posts.size>512)posts.remove(posts.keys.first())
        return true
    }
    @Synchronized fun complete(postKey: String,contentSignature: String,tor: Boolean,outcome: ArchiveCheckOutcome) {
        val lease=active ?: return
        if(lease.post!=hash(postKey)||lease.signature!=contentSignature||lease.tor!=tor)return
        val now=clock()
        if(outcome==ArchiveCheckOutcome.NOT_FOUND)posts[lease.post]=now+3*DAY
        else posts[lease.post]=maxOf(posts[lease.post] ?: 0L,now+DAY)
        if(outcome==ArchiveCheckOutcome.RATE_LIMITED)pausedUntil=maxOf(pausedUntil,now+HOUR)
        active=null;trim(now)
    }
    private fun trim(now: Long) {
        while(starts.firstOrNull()?.let {now-it>=HOUR}==true)starts.removeFirst()
        posts.entries.removeIf {now>=it.value}
        if(active?.let {now-it.started>=60_000}==true)active=null
    }
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private companion object {const val HOUR=3_600_000L;const val DAY=86_400_000L}
}
