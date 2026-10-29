/* anti social, GPL-3.0-or-later. Read declared link previews from admitted HTML. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

/** No fetch or post/author identity inference. Preview words remain labelled
 * attachment description; they never become an authored caption. */
internal object NitterLinkCards {
    private val excluded=setOf("quote","quote-big","quoted-tweet","quote-tweet","tweet-content","replies","related-tweets")
    data class Captured(val links:List<String>,val images:List<Media>)
    private val empty=Captured(emptyList(),emptyList())
    fun read(post:Element,base:String,belongsTo:(String)->Boolean):Captured {
        if(post.classNames().any {it in excluded})return empty
        val owner=if(post.hasClass("timeline-item"))post else post.select(".timeline-item").asSequence().filter {node->
            node.parents().takeWhile {it!==post}.none {parent->parent.hasClass("timeline-item")||parent.classNames().any {it in excluded}}
        }.take(2).toList().singleOrNull() ?: post
        fun own(node:Element):Boolean {
            var current:Element?=node
            while(current!=null&&current!==owner) {
                if(current.classNames().any {it in excluded})return false
                if(current!==node&&current.hasClass("timeline-item"))return false
                current=current.parent()
            }
            return current===owner
        }
        fun url(raw:String)=raw.takeIf {it.length in 1..8192}?.let {base.toHttpUrlOrNull()?.resolve(it)}
            ?.takeIf {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
        val cards=owner.select(".card").asSequence().filter {own(it)&&!it.hasClass("attachments")}.take(4).toList()
        if(cards.isEmpty())return empty
        val originals=owner.select("a.tweet-link[href],.tweet-date a[href]").asSequence().filter(::own)
            .mapNotNull {url(it.attr("href"))?.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()}.distinct().take(2).toList()
        if(originals.size!=1||!belongsTo(originals.single()))return empty
        val links=linkedSetOf<String>();val images=linkedMapOf<String,Media>()
        for(card in cards) {
            val containers=card.children().filter {it.normalName()=="a"&&it.hasClass("card-container")&&it.hasAttr("href")}
            val container=containers.singleOrNull() ?: continue
            val destination=url(container.attr("href")) ?: continue
            links+=destination
            val title=container.selectFirst(".card-title")?.text().orEmpty().take(512)
            val excerpt=container.selectFirst(".card-description")?.text().orEmpty().take(2048)
            for(image in container.select(".card-image img[src]").asSequence().filter(::own).take(2)) {
                val asset=url(image.attr("src")) ?: continue
                val description=listOfNotNull(
                    "Linked preview"+(if(title.isBlank())""else ": $title"),
                    excerpt.takeIf(String::isNotBlank)?.let {"Source excerpt: $it"},
                    image.attr("alt").take(2048).takeIf(String::isNotBlank)?.let {"Image description: $it"}
                ).joinToString("\n")
                images.putIfAbsent(asset,Media(asset,description=description))
            }
        }
        return Captured(links.toList(),images.values.toList())
    }
}
