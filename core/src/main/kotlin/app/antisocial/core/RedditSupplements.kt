/* anti social, GPL-3.0-or-later. Explicit same-author thread supplements. */
package app.antisocial.core

import org.jsoup.parser.Parser
import java.util.Locale

/** A title-only submission and its publisher's own substantial first comment
 * need one reading card. This never adds ordinary audience replies to a post. */
internal object RedditSupplements {
    private val words=Regex("[\\p{L}\\p{N}]+")
    private fun normalized(value:String)=TextWhitespace.collapse(Parser.unescapeEntities(value,false)).lowercase(Locale.ROOT)
    private fun identity(p:Post)=ContentIdentity.key(p.platform,p.canonicalUrl)
    private fun titleOnly(p:Post)=p.platform==Platform.REDDIT&&!p.preview&&p.parentId==null&&p.body.isBlank()&&p.media.isEmpty()&&p.links.isNullOrEmpty()&&
        identity(p).startsWith("post:")&&p.title.length in 35..1000&&words.findAll(p.title).count()>=6
    private fun comment(p:Post)=p.platform==Platform.REDDIT&&!p.preview&&p.parentId==null&&identity(p).startsWith("comment:")&&
        p.media.isEmpty()&&p.body.length in 160..4096&&words.findAll(p.body).count()>=24&&words.findAll(p.body).map {it.value.lowercase(Locale.ROOT)}.toSet().size>=12
    private fun reaction(p:Post)=p.platform==Platform.REDDIT&&!p.preview&&p.parentId==null&&identity(p).startsWith("comment:")&&p.media.isEmpty()&&p.links.isNullOrEmpty()&&
        p.body.length in 1..32&&p.body.codePoints().anyMatch {Character.getType(it)==Character.OTHER_SYMBOL.toInt()}&&
        p.body.codePoints().allMatch {Character.isWhitespace(it)||it==0x200d||Character.getType(it) in setOf(Character.OTHER_SYMBOL.toInt(),Character.MODIFIER_SYMBOL.toInt(),Character.NON_SPACING_MARK.toInt())}
    private fun heading(p:Post,account:String):String? {
        val name=account.removePrefix("reddit:")
        return Regex("^/?u/${Regex.escape(name)} on (.+)$",RegexOption.IGNORE_CASE).matchEntire(p.title)?.groupValues?.get(1)
    }
    fun collection(posts:List<Post>):Boolean {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return false
        val parents=posts.filter(::titleOnly);val comments=posts.filter(::comment);val reactions=posts.filter(::reaction)
        if(parents.isEmpty()||comments.isEmpty()||parents.size+comments.size+reactions.size!=posts.size)return false
        val authors=posts.map {CrossPosts.declaredRedditProfileAuthor(it) ?: return false}
        val account=authors.distinct().singleOrNull() ?: return false
        val times=(parents+comments).map {it.publishedAt?.takeIf {t->t>0} ?: return false}
        if(times.max()-times.min()>600_000)return false
        val heading=comments.map {heading(it,account)?.let(::normalized) ?: return false}.distinct().singleOrNull() ?: return false
        if(parents.any {normalized(it.title)!=heading}||comments.map {normalized(it.body)}.distinct().size!=1||
            comments.map {PostLinks.urls(it).toSet()}.distinct().size!=1)return false
        // Every omitted title-only card has an actual parent-ID relationship;
        // a matching headline, account or timestamp alone is insufficient.
        return parents.all {p->comments.any {c->identity(c).split(':').getOrNull(1)==identity(p).removePrefix("post:")}}&&
            reactions.all {r->val at=r.publishedAt ?: return false
                at>=times.min()&&at-times.max()<=CrossPosts.MAX_REPEAT_WINDOW&&heading(r,account)?.let(::normalized)==heading&&
                    parents.any {identity(r).split(':').getOrNull(1)==identity(it).removePrefix("post:")}}
    }
    fun collect(groups:List<PostGroup>):List<PostGroup> {
        val candidates=groups.indices.filter {groups[it].posts.all(::comment)}
        if(candidates.size>128)return groups
        val byParent=buildMap<String,MutableSet<Int>> {
            for(i in candidates)for(p in groups[i].posts)identity(p).split(':').getOrNull(1)?.let {getOrPut(it){linkedSetOf()}.add(i)}
        }
        val assignments=groups.indices.filter {groups[it].posts.all(::titleOnly)}.mapNotNull {i->
            val possible=groups[i].posts.flatMap {byParent[identity(it).removePrefix("post:")].orEmpty()}.distinct()
                .filter {collection(groups[it].posts+groups[i].posts)}
            possible.singleOrNull()?.let {i to it}
        }.toMap()
        val accepted=assignments.values.distinct().mapNotNull {target->
            val children=assignments.filterValues {it==target}.keys
            val all=groups[target].posts+children.flatMap {groups[it].posts}
            if(collection(all))target to PostGroup(all.sortedWith(compareBy<Post>{it.publishedAt}.thenBy(CrossPosts::originalKey)))else null
        }.toMap()
        val joined=groups.indices.filter {i->assignments[i]?.let {it in accepted}!=true}.map {accepted[it] ?: groups[it]}
        val roots=joined.indices.filter {collection(joined[it].posts)}
        val echoes=joined.indices.filter {joined[it].posts.all(::reaction)}
        if(roots.size>128||echoes.size>128)return joined
        val additions=echoes.mapNotNull {i->roots.filter {collection(joined[it].posts+joined[i].posts)}.singleOrNull()?.let {i to it}}.toMap()
        return joined.indices.filter {it !in additions}.map {i->
            val all=joined[i].posts+additions.filterValues {it==i}.keys.flatMap {joined[it].posts}
            if(all.size==joined[i].posts.size||!collection(all))joined[i]else PostGroup(all.sortedWith(compareBy<Post>{it.publishedAt}.thenBy(CrossPosts::originalKey)))
        }+additions.keys.filter {i->!collection(joined[additions.getValue(i)].posts+additions.filterValues {it==additions.getValue(i)}.keys.flatMap {joined[it].posts})}.map {joined[it]}
    }
    fun compose(posts:List<Post>):PostFusion? {
        if(!collection(posts))return null
        val parents=posts.filter(::titleOnly);val comments=posts.filter(::comment)
        return PostFusion(listOf(
            FusionSegment(parents.first().title,setOf(Platform.REDDIT),true,posts.mapTo(linkedSetOf(),CrossPosts::originalKey)),
            FusionSegment(comments.first().body,setOf(Platform.REDDIT),false,comments.mapTo(linkedSetOf(),CrossPosts::originalKey)))+
            posts.filter(::reaction).groupBy {it.body}.values.map {rows->FusionSegment(rows.first().body,setOf(Platform.REDDIT),false,rows.mapTo(linkedSetOf(),CrossPosts::originalKey))})
    }
}
