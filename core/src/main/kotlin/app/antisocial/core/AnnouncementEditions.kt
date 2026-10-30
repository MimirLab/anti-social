/* anti social, GPL-3.0-or-later. Literal presentation of admitted editions. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.parser.Parser
import java.text.Normalizer
import java.util.Locale

/** Reference suffixes do not disappear: each original retains its own path
 * through the shared message and attributed additions. This is not a detector. */
internal object AnnouncementEditions {
    private val word=Regex("\\S+")
    private val mention=Regex("@[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}")
    private val broadcast=Regex("/i/broadcasts/([A-Za-z0-9]{8,64})/?")
    private val space=Regex("[\\p{Z}\\s]+")
    data class Caption(val post:Post,val text:String,val message:String,val references:String,val referenceTokens:List<String>,val messageTokens:List<String>)
    private fun key(text:String)=Normalizer.normalize(text,Normalizer.Form.NFC).replace(space," ").trim()
    fun messageKey(caption:Caption)=key(caption.message)
    fun isMention(value:String)=mention.matches(value)
    private fun reference(value:String)=isMention(value)||value.startsWith("https://")&&value.toHttpUrlOrNull()?.let {it.isHttps&&it.username.isEmpty()&&it.password.isEmpty()}==true
    fun caption(post:Post):Caption? {
        if(post.preview||post.parentId!=null||post.body.length.toLong()+post.title.length>4096)return null
        var text=PostPresentation.content(post);repeat(2){text=Parser.unescapeEntities(text,false)}
        if(text.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val words=word.findAll(text).take(257).toList();if(words.size !in 3..256)return null
        var end=words.size
        while(end>0&&reference(words[end-1].value))end--
        if(end==0)return null
        val boundary=words.getOrNull(end)?.range?.first ?: text.length
        return Caption(post,text.trim(),text.substring(0,boundary).trim(),text.substring(boundary).trim(),words.drop(end).map {it.value},words.take(end).map {it.value})
    }
    /** Only a declared platform resource identity; no URL is fetched here. */
    fun broadcasts(post:Post):Set<String> = PostLinks.urls(post).asSequence().filter {"/i/broadcasts/" in it}.mapNotNull {raw->
        val url=raw.toHttpUrlOrNull() ?: return@mapNotNull null
        if(!url.isHttps||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||url.host !in setOf("x.com","www.x.com","twitter.com","www.twitter.com"))return@mapNotNull null
        broadcast.matchEntire(url.encodedPath)?.groupValues?.get(1)?.let {"x-live:$it"}
    }.take(8).toSet()

    /** Portable repeated-edition proof for saved manifests. This does not
     * infer cross-platform ownership or alter ordinary pair admission. */
    fun sameBroadcast(posts:List<Post>):Boolean {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.any {it.platform!=Platform.X||it.recoveredArchive!=null||it.publishedAt?.let {at->at>0}!=true})return false
        if(posts.map(CrossPosts::originalAccount).distinct().singleOrNull()==null)return false
        val dates=posts.map {requireNotNull(it.publishedAt)}
        if(dates.max()-dates.min()>10*60_000L)return false
        if(posts.map(::broadcasts).reduce {a,b->a.intersect(b)}.isEmpty())return false
        val docs=posts.map {caption(it) ?: return false};val full=docs.maxBy {it.messageTokens.size}
        if(full.messageTokens.size<20)return false
        return docs.all {doc->
            if(key(doc.message)==key(full.message))true else {
                val short=DecoratedPostCopies.short(doc.post) ?: return false
                if(short.tokens.size<5||short.tokens.distinct().size<5)return false
                val heading=full.messageTokens.take(doc.messageTokens.size).joinToString(" ")
                DecoratedPostCopies.short(full.post.copy(title="",body=heading))?.let {DecoratedPostCopies.compare(short,it)?.exact==true}==true
            }
        }
    }

    /** General literal passage display after independent collection proof.
     * Keep reference suffixes out of lexical/number comparison, then put every
     * original suffix back on its own exact source path. */
    fun composePassages(posts:List<Post>):PostFusion? {
        val docs=posts.map {caption(it) ?: return null}
        val messages=docs.map {it.post.copy(title="",body=it.message)}
        val fusion=CaptionWeave.composeSourceMentions(messages) ?: return null
        val runs=fusion.weave.orEmpty().toMutableList()
        if(docs.any {it.references.isNotBlank()})runs+=FusionRun(false,docs.indices.groupBy {key(docs[it].references)}.values.map {indexes->
            FusionVariant(docs[indexes.first()].references,indexes.mapTo(linkedSetOf()){posts[it].platform},indexes.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])},
                docs[indexes.first()].referenceTokens.filter(::isMention).joinToString(" "))
        })
        for(doc in docs) {
            val original=CrossPosts.originalKey(doc.post)
            val text=runs.map {it.variants.singleOrNull {v->original in v.originalKeys}?.text ?: return null}.joinToString(" ")
            if(key(text)!=key(doc.text))return null
        }
        val text=runs.filter {it.variants.any {v->v.visibleText.isNotBlank()}}.joinToString(" "){run->if(run.shared)run.variants.single().visibleText else run.variants.filter {it.visibleText.isNotBlank()}.joinToString(" / ","[","]") {
            it.visibleText+" — "+PlatformOrder.sort(it.platforms).joinToString(" · "){p->p.label}
        }}
        return PostFusion(listOf(FusionSegment(text,posts.mapTo(linkedSetOf()){it.platform},false,posts.mapTo(linkedSetOf(),CrossPosts::originalKey))),runs)
    }

    /** Literal prefix collation for short, independently admitted captions.
     * Branches retain complete coupled tails. A shorter source can finish while
     * the remaining sources share their continuation; nothing is synthesized
     * and every original has an explicit path, including same-platform edits.
     * Long captions retain the existing passage aligners. */
    fun composePrefixes(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return null
        val docs=posts.map {caption(it) ?: return null}
        val words=docs.map {doc->word.findAll(doc.message).map {it.value}.toList()}
        if(words.all {it.size>=24})return null
        fun token(value:String)=if(value.contains("://")||'@' in value||PostPresentation.urlMatches(value).any())key(value)else key(value).lowercase(Locale.ROOT)
        val keys=words.map {it.map(::token)}
        if(keys.distinct().size !in 2..8)return null
        var common=0
        while(common<keys.minOf {it.size}&&keys.all {it[common]==keys.first()[common]})common++
        val heading=words.first().take(common)
        if(heading.count {it.any(Char::isLetterOrDigit)&&!it.startsWith("https://")}<8||heading.joinToString(" ").length<40||keys.first().take(common).distinct().size<6)return null
        val positions=IntArray(docs.size)
        val all=docs.indices.toList();val allKeys=posts.mapTo(linkedSetOf(),CrossPosts::originalKey)
        val runs=mutableListOf<FusionRun>()
        fun emit(indices:List<Int>,text:String) {
            val variants=mutableListOf(FusionVariant(text,indices.mapTo(linkedSetOf()){posts[it].platform},indices.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])}))
            val absent=all.filter {it !in indices}
            if(absent.isNotEmpty())variants+=FusionVariant("",absent.mapTo(linkedSetOf()){posts[it].platform},absent.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])})
            runs+=FusionRun(absent.isEmpty(),variants)
        }
        fun branch(indices:List<Int>) {
            var length=0
            while(indices.all {positions[it]+length<keys[it].size}&&indices.all {keys[it][positions[it]+length]==keys[indices.first()][positions[indices.first()]+length]})length++
            if(length>0) {
                val preferred=indices.maxBy {words[it].size}
                emit(indices,words[preferred].subList(positions[preferred],positions[preferred]+length).joinToString(" "))
                indices.forEach {positions[it]+=length}
            }
            indices.filter {positions[it]<keys[it].size}.groupBy {keys[it][positions[it]]}.values.forEach(::branch)
        }
        branch(all)
        if(docs.any {it.references.isNotBlank()})runs+=FusionRun(false,all.groupBy {key(docs[it].references)}.values.map {indices->
            FusionVariant(docs[indices.first()].references,indices.mapTo(linkedSetOf()){posts[it].platform},indices.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])},
                docs[indices.first()].referenceTokens.filter(::isMention).joinToString(" "))
        })
        if(runs.size>16)return null
        for(i in docs.indices) {
            val original=CrossPosts.originalKey(posts[i])
            val projected=runs.flatMap {run->
                val selected=run.variants.singleOrNull {original in it.originalKeys} ?: return null
                word.findAll(selected.text).map {token(it.value)}.toList()
            }
            if(projected!=word.findAll(docs[i].text).map {token(it.value)}.toList())return null
        }
        val text=runs.filter {it.variants.any {v->v.visibleText.isNotBlank()}}.joinToString(" "){run->if(run.shared)run.variants.single().visibleText else
            run.variants.filter {it.visibleText.isNotBlank()}.joinToString(" / ","[","]"){it.visibleText+" — "+PlatformOrder.sort(it.platforms).joinToString(" · "){p->p.label}}}
        return PostFusion(listOf(FusionSegment(text,posts.mapTo(linkedSetOf()){it.platform},false,allKeys)),runs)
    }

    /** Called only for an already admitted collection. Supports one literal
     * message, or a complete heading and one otherwise identical fuller edition. */
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return null
        val docs=posts.map {caption(it) ?: return null}
        val versions=docs.groupBy {key(it.message)}
        if(versions.size !in 1..2)return null
        val shortest=docs.minBy {it.messageTokens.size}
        val prefix=shortest.messageTokens.map(::key)
        if(prefix.size<3||prefix.distinct().size<3||shortest.message.length<12)return null
        val atStart=docs.all {it.messageTokens.take(prefix.size).map(::key)==prefix}
        val atEnd=docs.all {it.messageTokens.takeLast(prefix.size).map(::key)==prefix}
        if(!atStart&&!atEnd)return null
        if(versions.size==1&&docs.map {key(it.references)}.distinct().size==1)return null
        val allKeys=posts.mapTo(linkedSetOf(),CrossPosts::originalKey)
        val platforms=posts.mapTo(linkedSetOf()){it.platform}
        val shared=FusionRun(true,listOf(FusionVariant(shortest.message,platforms,allKeys)))
        val runs=mutableListOf<FusionRun>()
        if(atStart)runs+=shared
        fun alternatives(parts:List<String>,references:Boolean=false) {
            if(parts.all(String::isBlank))return
            val values=parts.indices.groupBy {key(parts[it])}.values.map {indexes->
                FusionVariant(parts[indexes.first()],indexes.mapTo(linkedSetOf()){posts[it].platform},indexes.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])},
                    if(references)word.findAll(parts[indexes.first()]).map {it.value}.filter(::isMention).joinToString(" ")else null)
            }
            runs+=FusionRun(false,values)
        }
        if(versions.size==2)alternatives(docs.map {doc->
            val words=word.findAll(doc.message).toList()
            if(atStart)doc.message.substring(words[prefix.lastIndex].range.last+1).trim()
            else doc.message.substring(0,words[words.size-prefix.size].range.first).trim()
        })
        if(!atStart)runs+=shared
        alternatives(docs.map {it.references},references=true)
        // Literal source reconstruction prevents a compact display from losing
        // an inserted clause, reference, case-sensitive URL or source edition.
        for(doc in docs) {
            val original=CrossPosts.originalKey(doc.post)
            val projected=runs.map {run->run.variants.singleOrNull {original in it.originalKeys}?.text ?: return null}.joinToString(" ")
            if(key(projected)!=key(doc.text))return null
        }
        val text=runs.filter {it.variants.any {v->v.visibleText.isNotBlank()}}.joinToString(" "){run->if(run.shared)run.variants.single().visibleText else
            run.variants.filter {it.visibleText.isNotBlank()}.joinToString(" / ","[","]"){it.visibleText+" — "+PlatformOrder.sort(it.platforms).joinToString(" · "){platform->platform.label}}}
        return PostFusion(listOf(FusionSegment(text,platforms,false,allKeys)),runs)
    }
}
