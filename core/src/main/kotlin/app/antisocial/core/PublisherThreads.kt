/* anti social, GPL-3.0-or-later. Declared publisher continuations only. */
package app.antisocial.core

object PublisherThreads {
    fun descendants(root:Post,posts:List<Post>):List<Post> {
        val account=CrossPosts.originalAccount(root) ?: return emptyList()
        val candidates=posts.take(128).filter {it.platform==root.platform&&it.id!=root.id&&CrossPosts.originalAccount(it)==account&&it.parentId!=null}.distinctBy {it.id}.take(64)
        val accepted=linkedMapOf(root.id to root)
        repeat(candidates.size) {
            val next=candidates.filter {it.id !in accepted&&it.parentId in accepted}
            if(next.isEmpty())return accepted.values.drop(1)
            next.forEach {accepted[it.id]=it}
        }
        return accepted.values.drop(1)
    }
}
