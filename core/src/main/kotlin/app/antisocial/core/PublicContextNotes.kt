/* AntiSocial, GPL-3.0-or-later. Preserve public annotations already supplied by a route. */
package app.antisocial.core

import com.google.gson.JsonObject

data class PublicContextNote(val text:String,val sourceUrl:String,val links:List<String> = emptyList(),val status:String?=null)
object PublicContextNotes {
    fun valid(post:Post):Boolean=runCatching {
        val original=ArchiveUrls.original(post.canonicalUrl) ?: post.canonicalUrl
        post.contextNotes.orEmpty().size<=4&&post.contextNotes.orEmpty().all {note->
            post.platform==Platform.X&&note.text.isNotBlank()&&note.text.length<=12_000&&
            PublicSourceRecords.safe(note.sourceUrl)!=null&&SocialLinkHosts.platform(note.sourceUrl)==Platform.X&&
            ContentIdentity.key(Platform.X,note.sourceUrl)==ContentIdentity.key(Platform.X,original)&&
            note.links.size<=16&&note.links.all {PublicSourceRecords.safe(it)!=null}&&
            (note.status==null||note.status.length<=80&&note.status.none(Char::isISOControl))
        }
    }.getOrDefault(false)
    /** FxEmbed's v2 contract provides text/facets, not rating status or the
     * complete set of notes. Never manufacture a Helpful rating from presence. */
    fun fx(node:JsonObject,platform:Platform,canonical:String):List<PublicContextNote>? {
        if(platform!=Platform.X)return null
        val note=node["community_note"]?.takeIf {it.isJsonObject}?.asJsonObject ?: return null
        val text=note["text"]?.takeIf {it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString?.takeIf {it.isNotBlank()&&it.length<=12_000} ?: return null
        val facets=note["facets"]?.takeIf {it.isJsonArray}?.asJsonArray?.take(32).orEmpty()
        val urls=facets.mapNotNull {entry->entry.takeIf {it.isJsonObject}?.asJsonObject?.let {f->
            if(f["type"]?.takeIf {it.isJsonPrimitive}?.asString!="url")null else PublicSourceRecords.safe(f["replacement"]?.takeIf {it.isJsonPrimitive}?.asString)
        }}+Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(text).take(32).mapNotNull {PublicSourceRecords.safe(it.value.trimEnd('.',',',')',']',';'))}.toList()
        return listOf(PublicContextNote(text,canonical,urls.distinct().take(16)))
    }
}
