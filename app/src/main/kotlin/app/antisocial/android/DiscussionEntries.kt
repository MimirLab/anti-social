/* AntiSocial, GPL-3.0-or-later. Darwin remains the routing donor. */
package app.antisocial.android

import app.antisocial.core.*

internal data class DiscussionEntry(val key: String,val root: Post,val post: Post?=null,val community: AnywhereComment?=null,val depth: Int=0)

/** Source rankings are interleaved, not compared as a universal score. Newest
 * sorts conversation roots and keeps their already-obtained reply trees intact. */
internal fun discussionEntries(state: DiscussionState,communityEnabled: Boolean): List<DiscussionEntry> {
    val selected=if(state.selected=="all")state.posts else state.posts.filter {it.platform.id==state.selected}
    val sources=selected.map {root->state.thread(root)?.page?.let {Discussions.comments(root,it)}.orEmpty().map {listOf(DiscussionEntry("platform:${it.platform.id}:${ContentIdentity.key(it.platform,it.canonicalUrl)}",root,post=it))}}.toMutableList()
    if(state.selected=="all"&&communityEnabled)state.posts.forEach {root->
        state.shared(root)?.page?.let {page->sources+=page.items.map {node->
            page.copy(items=listOf(node)).flattened().map {(comment,depth)->DiscussionEntry("community:${page.pageUrl}:${comment.id}",root,community=comment,depth=depth)}
        }}
    }
    val blocks=if(state.order==ReadingOrder.CHRONOLOGICAL)sources.flatten().sortedByDescending {block->block.firstOrNull()?.let {it.post?.publishedAt ?: it.community?.timestamp} ?: Long.MIN_VALUE}
        else sources.flatMap {entries->entries.mapIndexed {index,block->index to block}}.sortedBy {it.first}.map {it.second}
    return blocks.flatten().distinctBy {it.key}
}
