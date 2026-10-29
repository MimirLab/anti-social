/* anti social, GPL-3.0-or-later. */
package app.antisocial.android

import app.antisocial.core.*
import kotlin.math.abs

/** Scheduling only, never a matching or account-ownership assertion. Start at
 * the retained reading position and its locally linked publisher's nearby
 * editions. No names, handle guesses, downloads or whole-cache startup barrier. */
internal fun feedImagePreparation(
    posts:List<Post>,scope:List<Post>,follows:List<Follow>,memberships:Map<String,Set<String>>,anchorKey:String?
):List<String> {
    val anchor=scope.firstOrNull {it.identityKey==anchorKey}
        ?: scope.maxByOrNull {it.publishedAt ?: Long.MIN_VALUE} ?: return emptyList()
    val accounts=follows.filter {it.enabled}.associate {it.id to Subscriptions.id(it)}
    fun owners(post:Post)=memberships[post.identityKey].orEmpty().mapNotNull(accounts::get).toSet()
    val publishers=owners(anchor)
    fun sameAccount(post:Post)=post.platform==anchor.platform&&post.author==anchor.author&&anchor.author.isNotBlank()
    fun linked(post:Post)=sameAccount(post)||publishers.isNotEmpty()&&owners(post).any(publishers::contains)
    fun nearby(post:Post):Boolean {
        val at=anchor.publishedAt ?: return false;val other=post.publishedAt ?: return false
        return other in (at-CrossPosts.MAX_REPEAT_WINDOW)..(at+CrossPosts.MAX_REPEAT_WINDOW)
    }
    fun parent(post:Post)=sameAccount(post)&&(anchor.parentId==post.id||post.parentId==anchor.id)
    fun pictures(post:Post)=post.media.count {it.type=="image"||it.type=="video"&&it.preview!=null}
    val peers=posts.asSequence().filter {it.identityKey!=anchor.identityKey&&linked(it)&&nearby(it)}
        .sortedWith(compareByDescending<Post>(::parent).thenByDescending(::pictures)
            .thenBy {abs((it.publishedAt ?: 0)-(anchor.publishedAt ?: 0))}.thenBy {it.identityKey})
    return (sequenceOf(anchor)+peers).take(12).flatMap {post->post.media.asSequence().mapNotNull {
        when(it.type){"image"->it.url;"video"->it.preview;else->null}
    }}.distinct().take(24).toList()
}
