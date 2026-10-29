/* anti social, GPL-3.0-or-later. Bounded additions to established collections. */
package app.antisocial.core

/** Initial groups are frozen anchors. A leaf needs one unambiguous destination;
 * accepting it never manufactures another anchor or a transitive match. */
internal object AnnouncementCollections {
    private data class Row(val index:Int,val posts:List<Post>,val owner:String,val platforms:Set<Platform>,val first:Long,val last:Long)
    private data class Short(val row:Row,val captions:List<DecoratedPostCopies.Prepared>) {
        val key get()=row.owner to captions.first().text
    }
    private fun close(a:Row,b:Row,window:Long)=maxOf(a.last,b.last)-minOf(a.first,b.first)<=window
    fun collect(groups:List<PostGroup>,owners:Map<String,String?>,artwork:(Post,Post)->Boolean,checkpoint:()->Unit={}):List<PostGroup> {
        if(groups.size<2)return groups
        val rows=groups.mapIndexedNotNull {i,group->
            checkpoint()
            val posts=group.posts
            if(posts.any {it.preview||it.parentId!=null||it.recoveredArchive!=null||it.publishedAt?.let {at->at>0}!=true})return@mapIndexedNotNull null
            val owner=posts.map {owners[CrossPosts.originalKey(it)]}.distinct().singleOrNull() ?: return@mapIndexedNotNull null
            Row(i,posts,owner,posts.mapTo(hashSetOf()){it.platform},posts.minOf {requireNotNull(it.publishedAt)},posts.maxOf {requireNotNull(it.publishedAt)})
        }
        val captions=mutableMapOf<String,AnnouncementEditions.Caption?>()
        fun caption(post:Post)=captions.getOrPut(CrossPosts.originalKey(post)){AnnouncementEditions.caption(post)}
        val short=mutableMapOf<String,DecoratedPostCopies.Prepared?>()
        fun projection(post:Post)=short.getOrPut(CrossPosts.originalKey(post)) {
            val value=caption(post) ?: return@getOrPut null
            if(value.messageTokens.size>14)return@getOrPut null
            DecoratedPostCopies.short(post)
        }
        fun matches(values:List<DecoratedPostCopies.Prepared>)=values.drop(1).all {DecoratedPostCopies.compare(values.first(),it)?.exact==true}
        val shorts=rows.mapNotNull {row->
            if(row.posts.any {it.media.isEmpty()})return@mapNotNull null
            val values=row.posts.map {projection(it) ?: return@mapNotNull null}
            Short(row,values).takeIf {matches(values)}
        }
        val anchors=shorts.filter {it.row.platforms.size>=2}.groupBy {it.key}
        // A real page/video title can precede an otherwise byte-for-byte
        // common announcement body. Keep that title, but do not require the
        // user to read the complete body on a separate card again.
        val bodyAnchors=rows.filter {it.platforms.size>=2}.mapNotNull {row->
            val docs=row.posts.map {caption(it) ?: return@mapNotNull null}
            val first=docs.first()
            if(first.messageTokens.size<12||first.messageTokens.distinct().size<10||first.message.length<80||docs.any {AnnouncementEditions.messageKey(it)!=AnnouncementEditions.messageKey(first)})return@mapNotNull null
            (row.owner to AnnouncementEditions.messageKey(first)) to row
        }.groupBy({it.first},{it.second})
        val broadcasts=mutableMapOf<String,Set<String>>()
        fun streams(post:Post)=broadcasts.getOrPut(CrossPosts.originalKey(post)){AnnouncementEditions.broadcasts(post)}
        val streamAnchors=mutableMapOf<Pair<String,String>,MutableSet<Int>>()
        rows.filter {it.platforms.size>=2}.forEach {row->row.posts.forEach post@{post->
            val ids=streams(post);if(ids.isEmpty())return@post
            val text=caption(post) ?: return@post
            if(text.messageTokens.size<20)return@post
            ids.forEach {streamAnchors.getOrPut(row.owner to it){linkedSetOf()}.add(row.index)}
        }}
        if(anchors.isEmpty()&&streamAnchors.isEmpty()&&bodyAnchors.isEmpty())return groups
        val byIndex=rows.associateBy {it.index}
        val choices=linkedMapOf<Int,Set<Int>>()
        for(leaf in rows.filter {it.platforms.size==1}) {
            checkpoint()
            val own=leaf.posts.mapNotNull {projection(it)}.takeIf {it.size==leaf.posts.size&&matches(it)}
            val possible=linkedSetOf<Int>()
            val bodies=leaf.posts.mapNotNull {post->
                if(PostPresentation.text(post).title.isBlank()||post.body.isBlank()||!CrossPostFusion.neutralCopyFraming(post))null
                else AnnouncementEditions.caption(post.copy(title=""))
            }
            if(bodies.size==leaf.posts.size&&bodies.map(AnnouncementEditions::messageKey).distinct().size==1) {
                val targets=bodyAnchors[leaf.owner to AnnouncementEditions.messageKey(bodies.first())].orEmpty()
                if(targets.size<=128)for(anchor in targets) {
                    if(leaf.platforms.any {it in anchor.platforms}||!close(leaf,anchor,10*60_000L))continue
                    if(AnnouncementEditions.compose(anchor.posts+leaf.posts)!=null)possible+=anchor.index
                }
            }
            val bucket=own?.let {anchors[leaf.owner to it.first().text]}.orEmpty()
            if(own!=null&&bucket.size<=128&&leaf.posts.all {it.media.isNotEmpty()})for(anchor in bucket) {
                if(leaf.platforms.any {it in anchor.row.platforms}||!close(leaf,anchor.row,30*60_000L))continue
                if(matches(anchor.captions+own))possible+=anchor.row.index
            }
            // A trailing account reference is retained as an attributed
            // appendix. A complete, distinctive seven-word statement already
            // corroborated on two linked platforms need not wait for pixels;
            // shorter statements still require observed matching artwork.
            if(leaf.posts.all {it.media.isNotEmpty()}) {
                val reduced=leaf.posts.map {post->
                    val cap=caption(post) ?: return@map null
                    if(cap.referenceTokens.isEmpty()||cap.referenceTokens.any {!AnnouncementEditions.isMention(it)&&!CrossPosts.opaqueLink(it)&&!CrossPosts.originalLink(it,post)})return@map null
                    // The message projection has intentionally detached the
                    // reference suffix. Classify acquired mention navigation
                    // against the original body before that suffix is absent.
                    val links=post.links?.filterNot {CrossPosts.nitterMentionLink(post,it)}
                    DecoratedPostCopies.short(post.copy(title="",body=cap.message,links=links))?.copy(post=post)
                }
                if(reduced.all {it!=null}) {
                    val values=reduced.filterNotNull()
                    val targets=anchors[leaf.owner to values.first().text].orEmpty()
                    if(targets.size<=128)for(anchor in targets) {
                        if(leaf.platforms.any {it in anchor.row.platforms}||!close(leaf,anchor.row,10*60_000L)||!matches(anchor.captions+values))continue
                        val distinctive=values.all {it.tokens.size>=7&&it.tokens.distinct().size>=6}
                        if(distinctive||leaf.posts.all {post->anchor.row.posts.any {artwork(post,it)}})possible+=anchor.row.index
                    }
                }
            }
            // A complete short heading can introduce the exact same declared
            // live broadcast as a fuller post by the same actual account.
            val streamTargets=leaf.posts.flatMap {post->streams(post).flatMap {streamAnchors[leaf.owner to it].orEmpty()}}.toSet()
            if(streamTargets.size<=128)for(index in streamTargets) {
                val anchor=byIndex[index] ?: continue
                if(index==leaf.index||!close(leaf,anchor,10*60_000L))continue
                if(leaf.posts.all {post->
                    val p=projection(post);val cap=caption(post);val account=CrossPosts.originalAccount(post)
                    p!=null&&cap!=null&&account!=null&&p.tokens.size>=5&&p.tokens.distinct().size>=5&&anchor.posts.any {source->
                        account==CrossPosts.originalAccount(source)&&streams(post).intersect(streams(source)).isNotEmpty()&&
                            caption(source)?.takeIf {it.messageTokens.size>=20}?.let {full->
                                val heading=full.messageTokens.take(cap.messageTokens.size).joinToString(" ")
                                DecoratedPostCopies.short(source.copy(title="",body=heading))?.let {DecoratedPostCopies.compare(p,it)?.exact==true}
                            }==true
                    }
                })possible+=index
            }
            if(possible.size==1&&groups[possible.single()].posts.size+leaf.posts.size<=CrossPosts.MAX_EDITIONS)choices[leaf.index]=possible
        }
        if(choices.isEmpty())return groups
        val assignments=choices.entries.groupBy {it.value.single()}.mapValues {(_,leaves)->
            // Two different candidates on one platform remain unresolved.
            leaves.groupBy {byIndex.getValue(it.key).platforms.single()}.values.filter {it.size==1}.flatten().map {it.key}
        }.filter { (index,children)->children.isNotEmpty()&&groups[index].posts.size+children.sumOf {groups[it].posts.size}<=CrossPosts.MAX_EDITIONS }
        val removed=assignments.values.flatten().toSet()
        return groups.indices.filter {it !in removed}.map {index->
            val children=assignments[index].orEmpty()
            if(children.isEmpty())groups[index]else {
                val posts=(groups[index].posts+children.flatMap {groups[it].posts}).distinctBy(CrossPosts::originalKey)
                PostGroup(posts.sortedWith(compareBy<Post>{it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey)))
            }
        }
    }
}
