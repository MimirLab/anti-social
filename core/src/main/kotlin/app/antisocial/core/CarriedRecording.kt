/* anti social, GPL-3.0-or-later. Exact supplied clips, not cover resemblance. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A public clip can be carried by several platforms. This narrow path needs
 * the exact same qualified progressive URL; it neither unwraps another source's
 * proxy nor grants byte-cache or signature equivalence to other renditions.
 * Group admission additionally requires the existing local publisher linkage.
 * Portable saved-copy validation establishes shared material, not account control. */
internal object CarriedRecording {
    private val path=Regex("^/(?:amplify_video|ext_tw_video)/[0-9]{1,40}/vid/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.mp4$")
    private val tag=Regex("[0-9]{1,6}")
    private val publicPath=Regex("^/ext_tw_video/[0-9]{1,40}/pu/vid/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.mp4$")
    fun key(post:Post):String? {
        if(post.preview||post.parentId!=null||post.recoveredArchive!=null||post.body.length.toLong()+post.title.length>16_384||
            post.publishedAt?.let {it>0}!=true||SocialLinkHosts.platform(post.canonicalUrl)!=post.platform||
            !ContentIdentity.key(post.platform,post.canonicalUrl).startsWith("post:"))return null
        val media=post.media.singleOrNull()?.takeIf {it.type=="video"&&it.audioUrl==null} ?: return null
        if(media.durationMs?.let {it !in 2_000..VideoFootage.MAX_DURATION_MS}==true||media.mimeType?.let {it!="video/mp4"}==true)return null
        val raw=media.url.takeIf {it.length in 1..8192&&it.none(Char::isISOControl)} ?: return null
        val url=raw.toHttpUrlOrNull()?.takeIf {it.isHttps&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()&&it.fragment==null&&
            it.host in setOf("video.twimg.com","video-s.twimg.com")&&(path.matches(it.encodedPath)||publicPath.matches(it.encodedPath))} ?: return null
        if(url.queryParameterNames.any {it!="tag"}||url.queryParameterValues("tag").let {it.size>1||it.any {v->v==null||!tag.matches(v)}})return null
        return raw
    }
    fun accepts(posts:List<Post>):Boolean {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size||posts.map {it.platform}.distinct().size<2)return false
        val keys=posts.map {key(it) ?: return false}
        if(keys.distinct().size!=1)return false
        val times=posts.map {requireNotNull(it.publishedAt)}
        if(times.max()-times.min()>6*60*60_000L)return false
        val durations=posts.mapNotNull {it.media.single().durationMs}
        return durations.isEmpty()||durations.max()-durations.min()<=maxOf(750L,durations.max()/100)
    }
    fun collect(groups:List<PostGroup>,owners:Map<String,String?>,checkpoint:()->Unit):List<PostGroup> {
        if(groups.size<2)return groups
        val buckets=linkedMapOf<Pair<String,String>,MutableList<Int>>()
        groups.forEachIndexed {index,group->
            checkpoint()
            val owner=group.posts.map {owners[CrossPosts.originalKey(it)]}.distinct().singleOrNull() ?: return@forEachIndexed
            val keys=group.posts.map {key(it) ?: return@forEachIndexed}.distinct()
            keys.singleOrNull()?.let {buckets.getOrPut(owner to it){mutableListOf()}.add(index)}
        }
        val replacements=hashMapOf<Int,PostGroup>();val removed=hashSetOf<Int>()
        for(bucket in buckets.values.filter {it.size in 2..128}) {
            val families=mutableListOf<MutableList<Int>>()
            for(index in bucket.sortedWith(compareBy<Int>{groups[it].posts.minOf {p->requireNotNull(p.publishedAt)}}.thenBy {CrossPosts.originalKey(groups[it].primary)})) {
                checkpoint()
                val target=families.filter {accepts((it+index).flatMap {i->groups[i].posts})}.singleOrNull()
                if(target==null)families+=mutableListOf(index)else target+=index
            }
            for(family in families.filter {it.size>1}) {
                val posts=family.flatMap {groups[it].posts}.sortedWith(compareBy<Post>{it.publishedAt}.thenBy(CrossPosts::originalKey))
                replacements[family.first()]=PostGroup(posts);removed+=family.drop(1)
            }
        }
        return groups.indices.filter {it !in removed}.map {replacements[it] ?: groups[it]}
    }
}
