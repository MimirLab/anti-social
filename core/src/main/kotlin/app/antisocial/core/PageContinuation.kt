/* anti social, GPL-3.0-or-later. Navigation ownership is separate from card provenance. */
package app.antisocial.core

object PageContinuation {
    fun merge(previous:Page,next:Page):Page {
        val added=next.posts.any {post->previous.posts.none {it.identityKey==post.identityKey}}||
            next.collections.any {item->previous.collections.none {it.target==item.target}}||
            next.accounts.any {account->previous.accounts.none {it.target==account.target}}
        val cursor=next.next?.takeIf {it!=previous.next&&(added||next.continuationProgress)}
        return previous.copy(posts=(previous.posts+next.posts).distinctBy {it.identityKey},
            collections=(previous.collections+next.collections).distinctBy {it.target.canonicalUrl},
            accounts=(previous.accounts+next.accounts).distinctBy {it.target.canonicalUrl},next=cursor,
            continuationSource=if(cursor!=null)next.continuationSource ?: next.provenance?.source else null,
            continuationProgress=next.continuationProgress,
            limitedTimeline=previous.limitedTimeline||next.limitedTimeline,continuationIssue=next.continuationIssue,
            notice=when {
                next.continuationProgress->next.notice ?: "Earlier pages are still available."
                !added&&next.next!=null->"This source supplied no new results."
                previous.provenance?.source!=next.provenance?.source->next.notice ?: previous.notice
                else->previous.notice
            })
    }
}
