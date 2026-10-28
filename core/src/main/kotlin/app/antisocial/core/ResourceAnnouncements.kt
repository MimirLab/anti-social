/* anti social, GPL-3.0-or-later. Literal editions of a shared resource announcement. */
package app.antisocial.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer

/** A complete shared closing paragraph and its explicit destination can be
 * distributed with different short introductions. This is not a homepage vote:
 * the whole substantial paragraph must agree. Originals and recordings survive. */
internal object ResourceAnnouncements {
    private val words=Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")
    private val whitespace=Regex("[\\s\\p{Z}]+")
    private val paragraph=Regex("\n[\t ]*\n")
    private val corrections=Regex("\\b(?:correction|corrected|retracted|cancelled|canceled|postponed|rescheduled|false|untrue|misleading)\\b",RegexOption.IGNORE_CASE)
    private fun normalized(value:String)=Normalizer.normalize(value,Normalizer.Form.NFC).replace(whitespace," ").trim()
    private data class Edition(val post:Post,val text:String,val introduction:String,val statement:String,val reference:String,val destination:String) {
        val key get()=normalized(statement)+"\u0001"+destination
    }
    private fun prepare(post:Post):Edition? {
        if(post.preview||post.parentId!=null||post.title.length.toLong()+post.body.length>4096||post.media.isEmpty()||'\n' !in post.body)return null
        val text=PostPresentation.content(post)
        if(text.any {it in '\u202a'..'\u202e'||it in '\u2066'..'\u2069'})return null
        val boundary=paragraph.findAll(text).lastOrNull() ?: return null
        val introduction=text.substring(0,boundary.range.first).trim()
        if(introduction.length !in 8..160||words.findAll(introduction).count() !in 2..12||corrections.containsMatchIn(introduction)||introduction.contains("://"))return null
        val tail=text.substring(boundary.range.last+1).trim()
        val reference=tail.split(whitespace).lastOrNull() ?: return null
        if(reference.contains("...")||reference.contains('…')||'@' in reference)return null
        val url=(if(reference.startsWith("https://",true))reference else "https://$reference").toHttpUrlOrNull() ?: return null
        if(!url.isHttps||url.port!=443||url.username.isNotEmpty()||url.password.isNotEmpty()||'.' !in url.host||CrossPosts.opaqueLink(url.toString()))return null
        if(PostPresentation.destinationLabels(tail).none {it.toHttpUrlOrNull()?.toString()==url.toString()})return null
        val statement=tail.removeSuffix(reference).trim()
        val terms=words.findAll(statement).map {it.value}.toList()
        if(statement.length<100||terms.size !in 12..48||terms.distinct().size<10||statement.contains("://"))return null
        return Edition(post,text,introduction,statement,reference,url.toString())
    }
    fun key(post:Post):String?=prepare(post)?.key
    fun compose(posts:List<Post>):PostFusion? {
        if(posts.size !in 2..CrossPosts.MAX_EDITIONS||posts.map(CrossPosts::originalKey).distinct().size!=posts.size)return null
        val editions=posts.map {prepare(it) ?: return null}
        if(editions.map {it.key}.distinct().size!=1)return null
        // A repeated resource footer must not collapse two otherwise matching
        // appointments, times or claims whose introductions were corrected.
        for(i in editions.indices)for(j in i+1 until editions.size)
            if(!CrossPostFusion.compatibleIntroductions(editions[i].introduction,editions[j].introduction))return null
        val allKeys=posts.mapTo(linkedSetOf(),CrossPosts::originalKey);val platforms=posts.mapTo(linkedSetOf()){it.platform}
        fun variants(value:(Edition)->String,reference:Boolean=false):FusionRun {
            val values=editions.indices.groupBy {normalized(value(editions[it]))}.values.map {indexes->
                FusionVariant(value(editions[indexes.first()]),indexes.mapTo(linkedSetOf()){posts[it].platform},indexes.mapTo(linkedSetOf()){CrossPosts.originalKey(posts[it])},if(reference)""else null)
            }
            return FusionRun(values.size==1,values)
        }
        val runs=listOf(variants({it.introduction}),FusionRun(true,listOf(FusionVariant(editions.first().statement,platforms,allKeys))),variants({it.reference},reference=true))
        for(edition in editions) {
            val source=CrossPosts.originalKey(edition.post)
            val text=runs.map {it.variants.singleOrNull {v->source in v.originalKeys}?.text ?: return null}.joinToString(" ")
            if(normalized(text)!=normalized(edition.text))return null
        }
        val text=runs.filter {it.variants.any {v->v.visibleText.isNotBlank()}}.joinToString(" "){run->
            if(run.shared)run.variants.single().visibleText else run.variants.joinToString(" / ","[","]"){variant->
                variant.visibleText+" — "+PlatformOrder.sort(variant.platforms).joinToString(" · "){it.label}
            }
        }
        return PostFusion(listOf(FusionSegment(text,platforms,false,allKeys)),runs)
    }
}
