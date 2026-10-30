/* anti social, GPL-3.0-or-later. Declared asset identity is not authentication. */
package app.antisocial.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

/** Provider recording identifiers are stronger than equal covers. A qualified
 * Nitter proxy may expose that identifier without a playable rendition. Keep
 * its actual transport URL; this never turns a preview into a video or grants
 * permission to fetch the embedded address. */
object DeclaredVideo {
    private val picture=Regex("^/(amplify_video|ext_tw_video)_thumb/([0-9]{1,40})/img/[A-Za-z0-9_-]{1,200}\\.(?:jpg|jpeg|png|webp)$")
    private val signature=Regex("[A-Za-z0-9_-]{1,256}")
    private fun https(raw:String)=raw.takeIf {it.length in 1..8192&&it.none(Char::isISOControl)}?.toHttpUrlOrNull()
        ?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null}
    private fun root(provenance:Provenance):HttpUrl? {
        val source=provenance.source
        if(source.platform!=Platform.X||source.frontend!="nitter"||source.role!=SourceRole.ALTERNATE)return null
        val root=https(source.root)?.takeIf {it.query==null} ?: return null
        val final=https(provenance.finalUrl.substringBefore('#')) ?: return null
        return root.takeIf {final.host==it.host&&final.port==it.port&&
            (it.encodedPath=="/"||final.encodedPath==it.encodedPath.trimEnd('/')||final.encodedPath.startsWith(it.encodedPath.trimEnd('/')+"/"))}
    }
    private fun relative(url:HttpUrl,root:HttpUrl):List<String>? {
        if(url.host!=root.host||url.port!=root.port||url.query!=null)return null
        val prefix=root.pathSegments.filter(String::isNotEmpty)
        return url.pathSegments.takeIf {it.take(prefix.size)==prefix}?.drop(prefix.size)
    }
    internal fun proxyRecording(url:String,provenance:Provenance):String? {
        val root=root(provenance) ?: return null
        val parts=relative(https(url) ?: return null,root) ?: return null
        val raw=when {
            parts.size==3&&parts[0]=="video"&&signature.matches(parts[1])->parts[2]
            parts.size==4&&parts[0]=="video"&&parts[1]=="enc"&&signature.matches(parts[2])->
                runCatching {String(Base64.getUrlDecoder().decode(parts[3]),Charsets.UTF_8)}.getOrNull()
            else->null
        } ?: return null
        val original=https(raw) ?: return null
        return VideoAssetIdentity.key(original.toString())?.takeIf {it.startsWith("twitter:")}
    }
    fun key(post:Post,media:Media):String? {
        if(post.platform!=Platform.X)return null
        if(media.type=="video") {
            if(https(media.url)==null)return null
            VideoAssetIdentity.key(media.url)?.takeIf {it.startsWith("twitter:")}?.let {return it}
            return post.provenance?.let {proxyRecording(media.url,it)}
        }
        if(media.type!="image"||media.url==post.avatar)return null
        val provenance=post.provenance ?: return null;val root=root(provenance) ?: return null
        val parts=relative(https(media.url) ?: return null,root) ?: return null
        if(parts.size!=2||parts[0]!="pic")return null
        val declared=https("https://pbs.twimg.com/"+parts[1]) ?: return null
        val match=picture.matchEntire(declared.encodedPath) ?: return null
        if(declared.queryParameterNames.any {it !in setOf("name","format")})return null
        return "twitter:${match.groupValues[1]}:${match.groupValues[2]}"
    }
    /** Only one recording and no separate still/gallery. No caption or cover
     * resemblance can supply a missing or conflicting provider identifier. */
    internal fun recording(post:Post):String? {
        if(post.platform!=Platform.X||post.preview||post.parentId!=null||post.recoveredArchive!=null||post.media.isEmpty()||post.media.size>2||
            post.body.length.toLong()+post.title.length>16_384||post.publishedAt?.let {it>0}!=true)return null
        if(!ContentIdentity.key(Platform.X,post.canonicalUrl).startsWith("post:")||SocialLinkHosts.platform(post.canonicalUrl)!=Platform.X)return null
        val keys=post.media.map {key(post,it) ?: return null}.distinct()
        return keys.singleOrNull()
    }
    internal fun same(first:Post,second:Post):Boolean {
        val recording=recording(first) ?: return false
        return recording==recording(second)&&kotlin.math.abs(requireNotNull(first.publishedAt)-requireNotNull(second.publishedAt))<=6*60*60_000L
    }

    /** Work on frozen caption families: an alternate caption must not consume
     * an original before its existing cross-platform counterparts can join.
     * Revalidate the whole union; a reused middle clip cannot bridge families.
     * Overflow is unexamined, not evidence of difference. */
    internal fun collect(groups:List<PostGroup>,proof:(List<Post>)->Boolean,checkpoint:()->Unit):List<PostGroup> {
        if(groups.size<2)return groups
        val buckets=linkedMapOf<String,MutableList<Int>>()
        groups.forEachIndexed {index,group->
            checkpoint()
            val clips=group.posts.mapNotNull(::recording).distinct()
            clips.singleOrNull()?.let {buckets.getOrPut(it){mutableListOf()}.add(index)}
        }
        val replacements=hashMapOf<Int,PostGroup>();val removed=hashSetOf<Int>()
        for(bucket in buckets.values.filter {it.size in 2..128}) {
            val families=mutableListOf<MutableList<Int>>()
            val order=bucket.sortedWith(compareBy<Int>{groups[it].posts.minOf {p->p.publishedAt ?: Long.MAX_VALUE}}.thenBy {CrossPosts.originalKey(groups[it].primary)})
            for(index in order) {
                checkpoint()
                val options=families.filter {family->
                    val originals=(family+index).flatMap {groups[it].posts}
                    originals.size<=CrossPosts.MAX_EDITIONS&&proof(originals)
                }
                val target=options.singleOrNull()
                if(target==null)families+=mutableListOf(index)else target+=index
            }
            for(family in families.filter {it.size>1}) {
                val originals=family.flatMap {groups[it].posts}.sortedWith(compareBy<Post>{it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey))
                replacements[family.first()]=PostGroup(originals);removed+=family.drop(1)
            }
        }
        return groups.indices.filter {it !in removed}.map {replacements[it] ?: groups[it]}
    }
}
