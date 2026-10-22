/* AntiSocial, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates persisted/imported archive-only records before treating them as readable content. */
internal object RecoveredArchives {
    private val timestamp=Regex("^/(?:web|noFrame/replay|wayback)/([0-9]{14})(?:[a-z]{1,4}_)?/")
    private val date=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private fun captured(url: String)=runCatching {
        val value=timestamp.find(URI(url).rawPath.orEmpty())?.groupValues?.get(1) ?: return@runCatching null
        LocalDateTime.parse(value,date).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()
    internal fun validVersion(post: Post,version: ArchivedVersion,now: Long): Boolean=runCatching {
        val original=ArchiveUrls.original(version.archiveUrl) ?: return@runCatching false
        val url=URI(version.archiveUrl)
        val provider=when(url.host){"web.archive.org"->"Internet Archive";"arquivo.pt"->"Arquivo.pt";else->return@runCatching false}
        val snapshot=version.snapshot
        // Archive HTML can legitimately reference an asset captured on another day.
        // Keep that replay pinned to the same archive, without claiming simultaneity.
        fun asset(value: String)=ArchiveUrls.safeAsset(value,version.archiveUrl)&&(captured(value)?.let {it in 1..now} ?: false)
        ArchiveUrls.samePost(post,original)&&version.provider==provider&&version.capturedAt==captured(version.archiveUrl)&&
            version.capturedAt in 1..now+CachePolicy.DAY&&snapshot.author.length<=500&&snapshot.body.length+snapshot.title.length<=128*1024&&
            (snapshot.body.isNotBlank()||snapshot.title.isNotBlank()||snapshot.media.isNotEmpty())&&snapshot.media.size<=16&&snapshot.links.size<=24&&snapshot.links.sumOf {it.length}<=64*1024&&
            snapshot.media.all {asset(it.url)&&(it.preview?.let(::asset) ?: true)&&(it.audioUrl?.let(::asset) ?: true)&&it.description.length<=4096}&&(snapshot.avatar?.let(::asset) ?: true)
    }.getOrDefault(false)
    fun valid(post: Post,now: Long=System.currentTimeMillis()): Boolean=runCatching {
        val recovery=post.recoveredArchive ?: return@runCatching false
        val provenance=post.provenance ?: return@runCatching false
        if(post.platform==Platform.YOUTUBE&&provenance.source.frontend=="youtube-archive")return@runCatching validYouTube(post,now)
        val origin=URI(recovery.archiveUrl).let {"${it.scheme}://${it.rawAuthority}"}
        !post.preview&&post.parentId==null&&post.archivedVersion==null&&post.replyCount==null&&post.score==null&&
            provenance.source.platform==post.platform&&provenance.source.frontend=="archive-recovery"&&provenance.source.role==SourceRole.ALTERNATE&&
            provenance.source.root==origin&&provenance.finalUrl==recovery.archiveUrl&&
            validVersion(post,ArchivedVersion(recovery.provider,recovery.capturedAt,recovery.archiveUrl,PostSnapshot.from(post)),now)
    }.getOrDefault(false)

    /** Saved/imported YouTube recoveries retain exact ID-bound archive origins.
     * They are archive recordings, not captured HTML editions of a live post. */
    private fun validYouTube(post: Post,now: Long): Boolean {
        val recovery=post.recoveredArchive ?: return false
        val provenance=post.provenance ?: return false
        val id=YouTubeUrls.videoId(post.canonicalUrl) ?: return false
        fun url(value: String)=value.takeIf {it.length<=16*1024&&it.none {c->c<=' '||c=='\\'}}?.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()}
        val archive=url(recovery.archiveUrl) ?: return false
        val origin="https://${archive.host}"
        if(post.preview||post.parentId!=null||post.archivedVersion!=null||provenance.source.platform!=Platform.YOUTUBE||
            provenance.source.role!=SourceRole.CACHE||provenance.source.root!=origin||provenance.finalUrl!=recovery.archiveUrl||
            provenance.fetchedAt !in 1..now+CachePolicy.DAY||recovery.capturedAt !in 1..now+CachePolicy.DAY||
            post.body.length.toLong()+post.title.length>128*1024||post.author.length>1024||post.media.size !in 1..16)return false
        val provider=when(archive.host) {
            "web.archive.org"->recovery.provider=="Wayback Machine"&&captured(recovery.archiveUrl)==recovery.capturedAt
            "ghostarchive.org"->recovery.provider=="Ghostarchive"&&archive.encodedPath=="/varchive/$id"&&archive.query==null
            "preservetube.com"->recovery.provider=="PreserveTube"&&archive.encodedPath=="/watch"&&archive.queryParameterNames==setOf("v")&&archive.queryParameter("v")==id
            "archive.org"->recovery.provider=="Internet Archive"&&archive.pathSegments.let {it.size==2&&it[0]=="details"&&it[1] in setOf("youtube-$id","youtube_$id",id)}&&archive.query==null
            "hobune.stream"->recovery.provider=="Hobune"&&archive.encodedPath in setOf("/videos/$id","/tpa-h/videos/$id")&&archive.query==null
            else->false
        }
        if(!provider)return false
        fun boundMedia(value: String): Boolean {
            val asset=url(value) ?: return false
            return when(archive.host) {
                "web.archive.org"->{
                    if(asset.host!="web.archive.org"||captured(value)?.let {it !in 1..now+CachePolicy.DAY}!=false)return false
                    val original=ArchiveUrls.original(value)?.toHttpUrlOrNull() ?: return false
                    original.host.endsWith(".googlevideo.com")&&original.encodedPath=="/videoplayback"&&original.queryParameter("video_id")==id ||
                        original.host=="wayback-fakeurl.archive.org"&&original.encodedPath=="/yt/$id"
                }
                "ghostarchive.org"->asset.host in setOf("ghostarchive.org","ghostvideo.b-cdn.net")&&asset.pathSegments.any {it==id||it.substringBeforeLast('.')==id}
                "archive.org"->asset.host=="archive.org"&&asset.pathSegments.let {it.size==3&&it[0]=="download"&&it[1]==archive.pathSegments[1]&&it[2].isNotBlank()&&it[2]!=".."}
                "hobune.stream"->asset.host=="hobune.stream"&&asset.pathSegments.any {it==id||it.substringBeforeLast('.')==id}
                // PreserveTube explicitly supplies an opaque CDN source in its
                // exact-ID record; it need not repeat that ID in its filename.
                "preservetube.com"->true
                else->false
            }
        }
        return post.media.all {m->m.type=="video"&&boundMedia(m.url)&&(m.audioUrl?.let(::boundMedia) ?: true)&&
            (m.preview?.let {url(it)!=null} ?: true)&&m.description.length<=4096&&
            (m.audioMimeType?.let {mime->m.audioUrl!=null&&mime.length<=256&&mime.startsWith("audio/")} ?: true)}
    }
}
