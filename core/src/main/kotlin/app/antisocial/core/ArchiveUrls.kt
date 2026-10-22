package app.antisocial.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Read-only archive replay URLs and their original post identities. Never follows a URL. */
object ArchiveUrls {
    private val date=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val replaySegment=Regex("([0-9]{14})(?:[a-z]{1,4}_)?")
    private val xHosts=setOf("x.com","www.x.com","twitter.com","www.twitter.com","mobile.twitter.com","m.twitter.com","mobile.x.com","m.x.com")

    /** Unwrap only a valid timestamped replay, never an archive submission or search endpoint. */
    fun original(replayUrl: String): String? {
        val archive=publicUrl(replayUrl)?.takeIf {it.isHttps&&it.port==443} ?: return null
        val prefixes=when(archive.host) {
            "web.archive.org" -> listOf("/web/")
            "arquivo.pt" -> listOf("/noFrame/replay/","/wayback/")
            else -> return null
        }
        val uri=runCatching {URI(replayUrl)}.getOrNull() ?: return null
        val path=uri.rawPath.orEmpty()
        val prefix=prefixes.firstOrNull(path::startsWith) ?: return null
        val tail=path.removePrefix(prefix)
        val match=replaySegment.matchEntire(tail.substringBefore('/')) ?: return null
        if(runCatching {LocalDateTime.parse(match.groupValues[1],date).year>=1990}.getOrDefault(false).not())return null
        val embedded=tail.substringAfter('/',"")+(uri.rawQuery?.let {"?$it"}.orEmpty())
        if(!embedded.startsWith("https://",true)&&!embedded.startsWith("http://",true))return null
        return embedded.takeIf {publicUrl(it)!=null}
    }

    /** Candidate is the unwrapped original. Default HTTP/HTTPS ports may differ historically. */
    fun samePost(current: Post,candidate: String): Boolean {
        val expected=publicUrl(current.canonicalUrl) ?: return false
        val actual=publicUrl(candidate) ?: return false
        if(nondefaultPort(expected)!=nondefaultPort(actual))return false
        val sameHost=if(current.platform==Platform.X)expected.host in xHosts&&actual.host in xHosts else expected.host==actual.host
        if(!sameHost)return false
        val expectedKey=ContentIdentity.key(current.platform,current.canonicalUrl)
        return expectedKey.startsWith("post:")&&ContentIdentity.key(current.platform,candidate)==expectedKey
    }

    /** Artwork must itself be a replay on the same selected archive, not merely share its host. */
    fun safeAsset(replayUrl: String,archiveUrl: String): Boolean {
        if(original(replayUrl)==null||original(archiveUrl)==null)return false
        val asset=publicUrl(replayUrl) ?: return false
        val archive=publicUrl(archiveUrl) ?: return false
        return asset.scheme==archive.scheme&&asset.host==archive.host&&asset.port==archive.port
    }

    private fun publicUrl(value: String): HttpUrl? {
        if(value.isBlank()||value.length>16*1024||value.any {it<=' '||it=='\u007f'||it=='\\'})return null
        val raw=runCatching {URI(value)}.getOrNull() ?: return null
        if(raw.host.isNullOrBlank()||raw.rawUserInfo!=null)return null
        val url=value.toHttpUrlOrNull() ?: return null
        if(url.scheme !in setOf("http","https")||url.username.isNotEmpty()||url.password.isNotEmpty())return null
        return url
    }
    private fun nondefaultPort(url: HttpUrl)=url.port.takeUnless {it==(if(url.isHttps)443 else 80)}
}
