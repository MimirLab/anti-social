/* AntiSocial, GPL-3.0-or-later. Indexed local relationships, never model-generated links. */
package app.antisocial.android

import app.antisocial.core.*

internal data class ContextReviewSession(val snapshot:String,val policy:String)
internal data class ContextReviewFailure(val source:String,val revision:String,val token:String,val stream:ContextStream)
/** Shared immutable source/reading references, never serialized into a token. */
internal data class ContextPreparedReview(val document:ContextDocument,val reading:ContextReadingInput,val background:ContextBackground?) {
    fun sameInput(other:ContextPreparedReview)=ContextSources.review(document)==ContextSources.review(other.document)&&
        reading==other.reading&&background?.fingerprint==other.background?.fingerprint
}

internal object ContextSources {
    fun post(owner: String) = "merged:$owner"
    private fun replyId(platform: Platform, id: String) = ContentIdentity.reference(platform,id)
    fun reply(origin: String, platform: Platform, id: String) = "reply:"+Repository.hash("$origin\n${platform.id}\n${replyId(platform,id)}")
    fun keys(document: ContextDocument): List<String> {
        val aliases=document.metadata["contextAliases"]?.let {raw->com.google.gson.JsonParser.parseString(raw).asJsonArray.map {it.asString}.also {require(it.size<=4)}}.orEmpty()
        return (listOfNotNull(document.id,document.metadata["contextKey"])+aliases).distinct()
    }
    fun parents(owner: String, document: ContextDocument) = buildList {
        if(document.kind=="comment")add(post(owner))
        document.metadata["contextParent"]?.takeIf {it.isNotBlank()}?.let(::add)
    }.filter {it !in keys(document)}.distinct()
    fun publications(document:ContextDocument)=ContextSourcePublication.from(document)
    // Retrieval location is citation provenance. Publication time and known
    // edition dates qualify relative wording, including an unchanged caption.
    fun review(document: ContextDocument) = document.copy(fetchedAt=0,url="",metadata=document.metadata.filterKeys {
        it in setOf("medium","published","editionTimes","editionTimes2","editionTimesOmitted","language","platform","timestamps","uncertain","truncated","deleted","contextKey","contextParent","contextParentUnavailable")
    })
}

/** One acquisition/processing pass shares source objects and their lazy text
 * hashes. The LRU is discarded before the next acquisition; it is not a second
 * persistent copy of a subject in every comment. */
internal class ContextBackgroundResolver(private val store: ContextStore,private val owner: String,
    private val lookup:(String)->ContextDocument?={store.documentForContextKey(owner,it)}) {
    private val readingSources=object: LinkedHashMap<String,ContextBackgroundSource>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,ContextBackgroundSource>?)=size>32 || values.sumOf {it.text.length.toLong()}>8L*1024*1024
    }
    private val sources=object: LinkedHashMap<String,ContextBackgroundSource>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,ContextBackgroundSource>?)=size>32 || values.sumOf {it.text.length.toLong()}>8L*1024*1024
    }
    private fun source(key: String): ContextBackgroundSource = sources.getOrPut(key) {
        val document=lookup(key)
        if(document==null)ContextBackgroundSource("",unavailable=true)
        else {
            val publications=ContextSources.publications(document)
            val dates=publications.mapNotNull {it.time?.instant}.distinct().sorted()
            val timing=when(dates.size) {
                0->if(publications.isEmpty())null else "publication times unavailable"
                1->(if(publications.any {it.time?.earliestKnownEdition==true})"earliest known edition "else "published ")+dates.single()+if(publications.any {it.time==null})"; includes undated editions"else ""
                else->"${dates.size} known publication times, ${dates.first()} to ${dates.last()}; relative wording may differ"
            }
            val label=listOfNotNull(document.author.filterNot(Char::isISOControl).take(80).takeIf {it.isNotBlank()},timing).joinToString(" · ").take(256)
            ContextBackgroundSource(document.text,label,incomplete=document.metadata["truncated"].equals("true",true)||dates.size>1||document.metadata.containsKey("editionTimesOmitted"),
                uncertain=document.metadata["uncertain"].equals("true",true),unavailable=document.metadata["deleted"].equals("true",true),
                interpretationIdentity=Repository.hash(publications.sortedWith(compareBy<ContextSourcePublication> {it.speaker}.thenBy {it.timeIdentity}).joinToString("\n") {it.speaker+"\u001f"+it.timeIdentity}+"\n"+document.metadata["editionTimesOmitted"]),sourceId=document.id)
        }
    }
    fun background(document: ContextDocument): ContextBackground? {
        if(document.kind!="comment"&&document.metadata["contextParent"].isNullOrBlank())return null
        val post=source(ContextSources.post(owner))
        val parent=if(document.metadata["contextParentUnavailable"].equals("true",true))ContextBackgroundSource("",unavailable=true)
            else document.metadata["contextParent"]?.takeIf {it.isNotBlank()}?.let(::source)
        if(parent!=null)return ContextBackground(post,parent)
        // Compatibility with the former generated frame. Match the exact old
        // subject prefix, not arbitrary delimiters inside untrusted post text.
        val oldPrefix="Subject post:\n"+post.text.take(600)
        val legacy=document.parentText.let {when {
            it==oldPrefix->""
            it.startsWith(oldPrefix+"\nReply parent:\n")->it.removePrefix(oldPrefix+"\nReply parent:\n")
            else->it
        }}
        return ContextBackground(post,legacy.takeIf {it.isNotBlank()}?.let {ContextBackgroundSource(it,"Earlier excerpt",incomplete=true)})
    }
    /** Prepare referenced bodies once. An unconfirmed translation is explicit
     * background uncertainty, never a reason to veto the child's own text. */
    suspend fun readingBackground(document:ContextDocument,read:suspend (ContextDocument,String,String)->String):ContextBackground? {
        val original=background(document) ?: return null
        suspend fun convert(source:ContextBackgroundSource?,key:String?):ContextBackgroundSource? {
            if(source==null||source.text.isBlank())return source
            readingSources[source.fingerprint]?.let {return it}
            val linked=key?.let(lookup)
            val text=if(linked!=null&&linked.text==source.text)read(linked,"body",linked.text)else read(document,"parent",source.text)
            val reading=if(linked!=null&&linked.text==source.text)store.reading(owner,linked,"body",linked.text)else store.reading(owner,document,"parent",source.text)
            return ContextBackgroundSource(text,source.label,source.incomplete,source.unavailable,source.uncertain||reading?.languageUnconfirmed==true,source.interpretationIdentity,source.sourceId).also {readingSources[source.fingerprint]=it}
        }
        return ContextBackground(convert(original.post,ContextSources.post(owner)),convert(original.reply,document.metadata["contextParent"]))
    }
    /** Publication is local and synchronous. It reuses exactly the prepared
     * view and its uncertainty; absent preparation is still unavailable. */
    fun cachedReadingBackground(document:ContextDocument):ContextBackground? {
        val original=background(document) ?: return null
        fun convert(source:ContextBackgroundSource?,key:String?):ContextBackgroundSource? {
            if(source==null||source.text.isBlank())return source
            readingSources[source.fingerprint]?.let {return it}
            val linked=key?.let(lookup)
            val reading=if(linked!=null&&linked.text==source.text)store.reading(owner,linked,"body",linked.text)
                else store.reading(owner,document,"parent",source.text)
            return ContextBackgroundSource(reading?.text.orEmpty(),source.label,source.incomplete,
                source.unavailable||reading==null,source.uncertain||reading?.languageUnconfirmed==true,source.interpretationIdentity,source.sourceId).also {readingSources[source.fingerprint]=it}
        }
        return ContextBackground(convert(original.post,ContextSources.post(owner)),convert(original.reply,document.metadata["contextParent"]))
    }
}
