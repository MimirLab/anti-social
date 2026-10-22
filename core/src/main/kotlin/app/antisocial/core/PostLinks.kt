/* AntiSocial, GPL-3.0-or-later. Literal source destinations, never model-generated URLs. */
package app.antisocial.core

/** RSS anchor labels may be shortened and old cached records may have a small
 * attachment list. Complete source headings remain another exact source of URLs.
 * Bound the inventory without letting account mentions consume the whole list. */
object PostLinks {
    const val MAX_LINKS=256
    private val absolute=Regex("https?://[^\\s<>\\\"\\[\\]]+",RegexOption.IGNORE_CASE)
    fun urls(post:Post):List<String> = (post.links.orEmpty().asSequence()+
        sequenceOf(post.title,post.body).flatMap {text->absolute.findAll(text.take(131072)).map {it.value}})
        .map {it.trim().trimEnd('.',',',')',';',']','}')}
        .filter {it.length<=16384&&'…' !in it&&!it.endsWith("...")&&(it.startsWith("https://",true)||it.startsWith("http://",true))}
        .filter {it!=post.canonicalUrl}.distinct().take(MAX_LINKS).toList()
    /** A permalink response often includes its discussion or neighbouring posts.
     * Only the requested original is the linked material. */
    fun linkedPost(page:Page,target:Target):Post?=page.posts.firstOrNull {
        !it.preview&&it.platform==target.platform&&
            ContentIdentity.key(it.platform,it.canonicalUrl)==ContentIdentity.key(target.platform,target.canonicalUrl)
    }
}
