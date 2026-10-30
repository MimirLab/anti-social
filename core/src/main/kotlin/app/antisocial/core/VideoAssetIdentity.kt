/* anti social, GPL-3.0-or-later. Asset keys never rewrite playback URLs. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class VideoAcquisitionKey(val url:String,val samplingUrl:String?,val durationMs:Long?,val mimeType:String?,val audioUrl:String?)

/** Qualifies renditions of one supplied clip; never uses a caption, account,
 * thumbnail or an arbitrary query-bearing website as a video identifier. */
object VideoAssetIdentity {
    /** A qualified identity for derived scene observations. It grants no byte
     * cache, signature, playback or attachment-suppression equivalence. */
    fun observationKey(raw:String):String? {
        if(raw.length !in 1..8192||raw.any(Char::isISOControl))return null
        if(raw.contains("tiktokcdn",true))tiktokObservation(raw)?.let {return it}
        return key(raw)
    }
    private val tiktokHost=Regex("v[0-9]{1,3}\\.tiktokcdn(?:-eu|-us)?\\.com")
    private val tiktokObject=Regex("^/[0-9a-f]{32}/[0-9a-f]{8}(/video/tos/[a-z0-9-]{2,32}/[a-z0-9-]{2,80}/[A-Za-z0-9_-]{24,128}/)$")
    private val deliveryToken=Regex("[A-Za-z0-9._~-]{16,256}={0,2}")
    private val deliveryTrace=Regex("[0-9]{14}[A-Fa-f0-9]{16,64}")
    /** Observed signed-CDN form only. Keep the authority, complete TOS object
     * namespace and every other query field, including unknown transformations.
     * Prefix expiry/signature and these two delivery fields do not identify a
     * new observed object. They remain untouched in the actual request URL. */
    private fun tiktokObservation(raw:String):String? {
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null&&tiktokHost.matches(it.host)} ?: return null
        val path=tiktokObject.matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
        val fields=url.encodedQuery?.split('&') ?: return null
        for((name,pattern) in listOf("ft" to deliveryToken,"l" to deliveryTrace)) {
            val values=url.queryParameterValues(name)
            if(values.size!=1||values.single()?.let(pattern::matches)!=true||fields.count {it.substringBefore('=')==name}!=1)return null
        }
        val identity=url.newBuilder().encodedPath(path).removeAllQueryParameters("ft").removeAllQueryParameters("l").build()
        return "tiktok-observation:$identity"
    }
    private fun progressiveCandidate(raw:String)=VideoFormats.mime(Media(raw,"video")) in setOf(null,"video/mp4","video/webm")
    private val twitter=Regex("^/(amplify_video|ext_tw_video)/([0-9]{1,40})/(?:vid|pl)/")
    private val numericPost=Regex("post:[0-9]{1,40}")
    fun key(raw:String):String? {
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()} ?: return null
        YouTubeUrls.videoId(raw)?.let {return "youtube:$it"}
        if(url.host in setOf("video.twimg.com","video-s.twimg.com"))twitter.find(url.encodedPath)?.let {return "twitter:${it.groupValues[1]}:${it.groupValues[2]}"}
        if(url.host=="v.redd.it")url.pathSegments.firstOrNull()?.takeIf {it.matches(Regex("[A-Za-z0-9]{4,40}"))}?.let {return "reddit:$it"}
        // This already-used public blob route supplies an account DID and a
        // canonical raw/SHA-256 CID. Qualify its observation route, not a file
        // digest: the bounded sampler still checks bytes and three scenes.
        if(url.host=="pds-cache.fxbsky.app"&&url.port==443&&url.query==null&&url.fragment==null) {
            val parts=url.pathSegments
            if(parts.size==2&&parts[0].matches(Regex("did:plc:[a-z2-7]{24}"))&&blobCid(parts[1])&&
                url.encodedPath=="/${parts[0]}/${parts[1]}")return "bluesky-blob:${parts[0]}:${parts[1]}"
        }
        // CDN shard and expiring access parameters do not identify a new file.
        // Keep the complete path; different media objects remain different.
        if((url.host.endsWith(".fbcdn.net")||url.host.endsWith(".cdninstagram.com"))&&
            url.encodedPath.endsWith(".mp4",true))return "meta:"+url.encodedPath
        return null
    }
    /** CIDv1 / raw codec / SHA-256, including canonical base32 padding bits.
     * Do not accept a CID-looking arbitrary path as a qualified clip. */
    private fun blobCid(value:String):Boolean {
        if(value.length!=59||value.first()!='b')return false
        val bytes=ByteArray(36);var index=0;var bits=0;var buffer=0
        for(character in value.drop(1)) {
            val digit="abcdefghijklmnopqrstuvwxyz234567".indexOf(character)
            if(digit<0)return false
            buffer=(buffer shl 5)or digit;bits+=5
            if(bits>=8) {
                bits-=8;if(index==bytes.size)return false
                bytes[index++]=(buffer ushr bits).toByte();buffer=buffer and ((1 shl bits)-1)
            }
        }
        return index==36&&bits==2&&buffer==0&&bytes[0]==1.toByte()&&bytes[1]==0x55.toByte()&&bytes[2]==0x12.toByte()&&bytes[3]==0x20.toByte()
    }
    /** Only source-advertised renditions of the same qualified provider clip.
     * The selected playback URL and every playable edition remain unchanged. */
    fun smallRendition(playback:String,candidates:List<Pair<String,Long>>):String? {
        val clip=key(playback) ?: return null
        return candidates.filter {it.second>0&&it.first.length<=8192&&key(it.first)==clip&&progressiveCandidate(it.first)}.minByOrNull {it.second}?.first
    }
    fun validSampling(media:Media):Boolean {
        val candidate=media.samplingUrl ?: return true
        return media.type=="video"&&candidate.length<=8192&&progressiveCandidate(candidate)&&key(media.url)?.let {it==key(candidate)}==true
    }
    fun sampleUrl(media:Media)=media.samplingUrl?.takeIf {validSampling(media)} ?: media.url
    fun acquisitionKey(media:Media)=VideoAcquisitionKey(media.url,media.samplingUrl,media.durationMs,media.mimeType,media.audioUrl)

    /** A retained public X clip can lack the metadata now kept by its reader.
     * Only an exact post and qualified progressive provider clip can ask for a
     * small current metadata read; arbitrary opaque videos stay excluded. */
    fun canResolveMetadata(post:Post):Boolean {
        if(post.platform!=Platform.X||post.preview||post.parentId!=null||post.recoveredArchive!=null||post.provenance?.source?.role==SourceRole.CACHE)return false
        val canonical=post.canonicalUrl.takeIf {it.length<=8192}?.toHttpUrlOrNull() ?: return false
        if(!canonical.isHttps||canonical.username.isNotEmpty()||canonical.password.isNotEmpty()||SocialLinkHosts.platform(post.canonicalUrl)!=Platform.X||
            !numericPost.matches(ContentIdentity.key(Platform.X,post.canonicalUrl)))return false
        val media=post.media.singleOrNull {it.type=="video"} ?: return false
        return media.audioUrl==null&&key(media.url)?.startsWith("twitter:")==true&&VideoFormats.mime(media) in setOf("video/mp4","video/webm")&&validSampling(media)
    }

    /** Borrow acquisition hints only from one qualified provider clip. Keep
     * playback, captions, previews and exact-byte provenance on their original. */
    fun withMetadata(current:Media,supplied:Media):Media? {
        if(current.type!="video"||supplied.type!="video"||current.audioUrl!=null||supplied.audioUrl!=null||!validSampling(current)||!validSampling(supplied))return null
        val clip=key(current.url) ?: return null
        if(key(supplied.url)!=clip)return null
        val declared=supplied.durationMs
        if(declared!=null&&declared !in 2_000..VideoFootage.MAX_DURATION_MS)return null
        val existing=current.durationMs
        if(existing!=null&&existing !in 2_000..VideoFootage.MAX_DURATION_MS)return null
        if(existing!=null&&declared!=null&&kotlin.math.abs(existing-declared)>maxOf(750L,maxOf(existing,declared)/100))return null
        val candidate=(supplied.samplingUrl ?: supplied.url).takeIf {url->
            url!=current.url&&key(url)==clip&&VideoFormats.mime(Media(url,"video")) in setOf("video/mp4","video/webm")
        }
        return current.copy(durationMs=existing ?: declared,samplingUrl=current.samplingUrl ?: candidate).takeUnless {it==current}
    }
}
