/* anti social, GPL-3.0-or-later. Keep every original and playable edition. */
package app.antisocial.core

data class VideoFootageRequest(val first:Post,val second:Post,val originalKeys:Set<String>,val resolveMetadata:Boolean=false) {
    val key get()=listOf(CrossPosts.originalKey(first),CrossPosts.originalKey(second)).sorted().joinToString("|")
    val acquisitionKey get()=listOf(first,second).sortedBy(CrossPosts::originalKey).map {post->
        CrossPosts.originalKey(post) to post.media.singleOrNull {it.type=="video"}?.let(VideoAssetIdentity::acquisitionKey)
    }
}

/** Frozen caption collections can share sampled footage despite different
 * wording. The supplied proof lookup must be precomputed off the UI thread;
 * this admission pass neither downloads nor decodes a recording. An accepted
 * family is a complete clique, never a bridge through a reused roundup clip.
 */
internal object SharedVideoCollections {
    private data class Row(val index:Int,val group:PostGroup,val owner:String,val first:Long,val last:Long)
    private fun rows(groups:List<PostGroup>,owners:Map<String,String?>,checkpoint:()->Unit)=groups.mapIndexedNotNull {index,group->
            checkpoint()
            if(group.posts.any {it.preview||it.parentId!=null||it.recoveredArchive!=null||it.publishedAt?.let {time->time>0}!=true})return@mapIndexedNotNull null
            val owner=group.posts.map {owners[CrossPosts.originalKey(it)]}.distinct().singleOrNull() ?: return@mapIndexedNotNull null
            val clips=group.posts.map {post->post.media.filter {it.type=="video"}.map {VideoAssetIdentity.key(it.url) ?: it.url}.distinct()}
            if(clips.all {it.isEmpty()}||clips.any {it.size>1})return@mapIndexedNotNull null
            Row(index,group,owner,group.posts.minOf {requireNotNull(it.publishedAt)},group.posts.maxOf {requireNotNull(it.publishedAt)})
    }
    /** A finite visible-card plan, not a scan of every recording. Existing
     * caption groups need no video work unless another owned group is nearby.
     * Adaptive playlists and multi-clip originals are outside this sampler. */
    fun requests(groups:List<PostGroup>,owners:Map<String,String?>,limit:Int=12,checkpoint:()->Unit={}):List<VideoFootageRequest> {
        require(limit in 1..16)
        if(groups.size<2)return emptyList()
        val candidates=rows(groups,owners,checkpoint).sortedByDescending {it.last}.take(128)
        // Validate each captured raster set once, not once per candidate pair.
        val observed=candidates.flatMap {it.group.posts}.flatMap {it.media}.filter {it.type=="video"}.distinct().associateWith {m->
            m.videoFootage?.takeIf {it.scenes.size>=3&&VideoFootage.valid(m)}
        }
        val resolvable=candidates.flatMap {it.group.posts}.distinct().associateWith(VideoAssetIdentity::canResolveMetadata)
        fun selected(group:PostGroup)=group.posts.filter {post->
            // An opaque URL can be nominated by the same runtime/rendition
            // evidence; the sampler checks its already fetched prefix first.
            post.media.any {it.type=="video"&&it.audioUrl==null&&(it.durationMs==null||it.durationMs in 2_000..VideoFootage.MAX_DURATION_MS)&&VideoFormats.mime(it) in setOf(null,"video/mp4","video/webm")&&VideoAssetIdentity.validSampling(it)}
        }.minWithOrNull(compareBy<Post> {post->post.media.none {observed[it]!=null}}
            .thenBy {post->post.media.none {it.type=="video"&&it.samplingUrl!=null}}
            .thenBy {resolvable[it]!=true}.thenBy(CrossPosts::originalKey))
        val selected=candidates.associate {it.index to selected(it.group)}
        val metadata=candidates.associate {it.index to (resolvable[selected[it.index]]==true)}
        val plans=mutableListOf<Pair<Long,VideoFootageRequest>>()
        for(i in candidates.indices)for(j in i+1 until candidates.size) {
            checkpoint();val a=candidates[i];val b=candidates[j]
            if(a.owner!=b.owner||maxOf(a.last,b.last)-minOf(a.first,b.first)>6*60*60_000L||a.group.posts.size+b.group.posts.size>CrossPosts.MAX_EDITIONS)continue
            val first=selected[a.index] ?: continue;val second=selected[b.index] ?: continue
            val left=first.media.singleOrNull {it.type=="video"} ?: continue;val right=second.media.singleOrNull {it.type=="video"} ?: continue
            val observedLeft=observed[left];val observedRight=observed[right]
            val leftDuration=left.durationMs ?: observedLeft?.durationMs
            val rightDuration=right.durationMs ?: observedRight?.durationMs
            // A measured peer already needs no acquisition. Older qualified X
            // clips may instead ask the settled-card lane for current public
            // metadata first; an opaque URL alone still cannot trigger work.
            val missing=left.samplingUrl==null&&right.samplingUrl==null&&(leftDuration==null||rightDuration==null)&&observedLeft==null&&observedRight==null
            val resolve=missing&&(metadata[a.index]==true||metadata[b.index]==true)
            if(missing&&!resolve)continue
            if(left.videoFootage?.scenes?.size?.let {it>=3}==true&&right.videoFootage?.scenes?.size?.let {it>=3}==true)continue
            if(leftDuration?.let {l->rightDuration?.let {r->kotlin.math.abs(l-r)>maxOf(750L,maxOf(l,r)/100)}}==true)continue
            plans+=kotlin.math.abs(a.last-b.last) to VideoFootageRequest(first,second,(a.group.posts+b.group.posts).mapTo(linkedSetOf(),CrossPosts::originalKey),resolve)
        }
        return plans.sortedWith(compareBy<Pair<Long,VideoFootageRequest>> {it.second.resolveMetadata}.thenBy {it.first}.thenByDescending {maxOf(it.second.first.publishedAt ?: 0,it.second.second.publishedAt ?: 0)}.thenBy {it.second.key})
            .map {it.second}.distinctBy {it.key}.take(limit)
    }
    fun collect(groups:List<PostGroup>,owners:Map<String,String?>,proof:(Post,Post)->SharedVideoFootage?,checkpoint:()->Unit={}):List<PostGroup> {
        if(groups.size<2)return groups
        val rows=rows(groups,owners,checkpoint)
        val edges=HashSet<Pair<Int,Int>>()
        fun key(a:Int,b:Int)=minOf(a,b) to maxOf(a,b)
        rows.groupBy {it.owner}.values.forEach {owned->
            val ordered=owned.sortedWith(compareBy<Row> {it.first}.thenBy {it.index})
            for(i in ordered.indices)for(j in i+1 until ordered.size) {
                checkpoint()
                val a=ordered[i];val b=ordered[j]
                if(b.first-a.first>6*60*60_000L)break
                if(maxOf(a.last,b.last)-minOf(a.first,b.first)>6*60*60_000L||a.group.posts.size+b.group.posts.size>CrossPosts.MAX_EDITIONS)continue
                if(a.group.posts.any {first->b.group.posts.any {second->proof(first,second)?.let(VideoFootage::qualified)==true}})edges+=key(a.index,b.index)
            }
        }
        if(edges.isEmpty())return groups
        val families=ArrayList<MutableList<Int>>()
        val eligible=rows.mapTo(hashSetOf()){it.index}
        groups.indices.sortedWith(compareByDescending<Int> {groups[it].primary.publishedAt ?: Long.MIN_VALUE}.thenBy {CrossPosts.originalKey(groups[it].primary)}).forEach {index->
            checkpoint()
            val options=if(index in eligible)families.filter {family->
                family.all {key(it,index)in edges}&&family.sumOf {groups[it].posts.size}+groups[index].posts.size<=CrossPosts.MAX_EDITIONS
            }else emptyList()
            val destination=options.singleOrNull()
            if(destination==null)families+=mutableListOf(index)else destination+=index
        }
        return families.map {family->
            if(family.size==1)groups[family.single()]else PostGroup(family.flatMap {groups[it].posts}.distinctBy(CrossPosts::originalKey)
                .sortedWith(compareBy<Post> {it.publishedAt ?: Long.MAX_VALUE}.thenBy(CrossPosts::originalKey)))
        }
    }
}
